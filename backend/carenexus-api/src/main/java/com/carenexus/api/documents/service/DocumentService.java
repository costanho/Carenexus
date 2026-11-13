package com.carenexus.api.documents.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.DocumentUploadedEvent;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.documents.dto.response.DocumentResponse;
import com.carenexus.api.documents.model.Document;
import com.carenexus.api.documents.model.DocumentTypes;
import com.carenexus.api.documents.repository.DocumentReferenceRepository;
import com.carenexus.api.documents.repository.DocumentRepository;
import com.carenexus.api.documents.repository.DocumentSpecifications;
import com.carenexus.api.documents.repository.DocumentUploaderRepository;
import com.carenexus.api.storage.FileScanner;
import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.ScanResult;
import com.carenexus.api.storage.StorageException;
import com.carenexus.api.storage.StoredFile;
import com.carenexus.api.storage.StoredFileNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Documents in a patient's record: upload (scanned, stored through {@link FileStorage}), paged list, metadata,
 * download (streamed through the API) and soft delete. Every method authorises the caller with
 * {@link AuthorizationService#requirePatientAccess} and audits successful access (resource type {@link #RESOURCE}).
 *
 * Who may do what (contract section 1.2):
 * <ul>
 *   <li>upload: {@code MANAGE_DOCUMENTS} on the patient;</li>
 *   <li>list, metadata, download: the read permission of the document's type ({@link DocumentTypes#readPermission});
 *       a caregiver's list is filtered in the database to the types the grant may read;</li>
 *   <li>delete: {@code MANAGE_DOCUMENTS} and the caller is the patient, the uploader or an administrator.</li>
 * </ul>
 * Deleted rows and rows without a stored file answer 404 for everyone.
 */
@Service
@Slf4j
public class DocumentService {

    public static final String RESOURCE = "DOCUMENT";

    /** Rate-limit bucket of uploads (60 per hour per caller, application.yml). */
    public static final String UPLOAD_BUCKET = "document-upload";

    /** Largest stored file, in bytes (equals spring.servlet.multipart.max-file-size). */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    static final String NOT_FOUND = "Document not found";
    static final String FILE_NOT_AVAILABLE = "The file for this document is not available";
    static final String FILE_EMPTY = "The file is empty";
    static final String FILE_TYPE_NOT_ALLOWED = "This file type is not allowed. Upload a PDF, JPEG, PNG or DICOM file.";
    static final String FILE_INFECTED = "The file was rejected by the security scan";
    static final String STORAGE_UNAVAILABLE = "File storage is temporarily unavailable. Please try again later.";
    static final String DELETE_NOT_ALLOWED = "Only the uploader, the patient or an administrator can delete this document";

    private static final Pattern PATIENT_ID = Pattern.compile("^PAT-\\d{5,}$");
    private static final Pattern DOCTOR_TITLE = Pattern.compile("(?i)^dr(\\.|\\s|$).*");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("uploadedAt"), Sort.Order.desc("documentId"));

    private final DocumentRepository documentRepository;
    private final DocumentUploaderRepository uploaderRepository;
    private final DocumentReferenceRepository referenceRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final RateLimitGuard rateLimitGuard;
    private final FileStorage fileStorage;
    private final FileScanner fileScanner;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    /** REQUIRED: the short write of an upload (joins the caller's transaction when there is one). */
    private final TransactionTemplate writeTransaction;

    public DocumentService(DocumentRepository documentRepository, DocumentUploaderRepository uploaderRepository,
                           DocumentReferenceRepository referenceRepository, AuthorizationService authorizationService,
                           AuditService auditService, RateLimitGuard rateLimitGuard, FileStorage fileStorage,
                           FileScanner fileScanner, ApplicationEventPublisher eventPublisher, Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.uploaderRepository = uploaderRepository;
        this.referenceRepository = referenceRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.rateLimitGuard = rateLimitGuard;
        this.fileStorage = fileStorage;
        this.fileScanner = fileScanner;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** A validated, normalised upload request (the result of {@link #prepareUpload}). */
    public record UploadCommand(String patientSystemId, String documentType, String title, String description,
                                String linkType, Integer linkId, MultipartFile file) {
    }

    /** An opened download: the metadata for the response headers and the stream (the caller closes it). */
    public record DocumentDownload(String originalFilename, String contentType, long sizeBytes, InputStream content) {
    }

    // ==================== UPLOAD ====================

    /**
     * Step 2 of the upload: the field checks Bean Validation cannot express (patient id pattern, enums, the
     * link rules), after trimming and upper-casing. Reads nothing and changes nothing; the controller calls it
     * before the idempotency check. Problems are reported for the alphabetically first field.
     *
     * @throws BadRequestException (code VALIDATION_FAILED) with the message {@code "<field>: <text>"}
     */
    public UploadCommand prepareUpload(String patientSystemId, String documentType, String title, String description,
                                       String linkType, Integer linkId, MultipartFile file) {
        String type = DocumentTypes.normalize(documentType);
        String link = DocumentTypes.normalize(linkType);
        String patient = DocumentTypes.normalize(patientSystemId);
        boolean linkKnown = link != null && DocumentTypes.LINK_TYPES.contains(link);
        String cleanTitle = title == null ? "" : title.trim();
        String cleanDescription = description == null || description.isBlank() ? null : description.trim();

        // Alphabetical field order: description, documentType, file, linkId, linkType, patientSystemId, title.
        if (cleanDescription != null && cleanDescription.length() > Document.DESCRIPTION_MAX) {
            throw invalid("description: size must be between 0 and " + Document.DESCRIPTION_MAX);
        }
        if (type == null) {
            throw invalid("documentType: must not be blank");
        }
        if (!DocumentTypes.DOCUMENT_TYPES.contains(type)) {
            throw invalid("documentType: must be one of " + DocumentTypes.documentTypeList());
        }
        if (linkKnown) {
            String required = DocumentTypes.REQUIRED_TYPE_FOR_LINK.get(link);
            if (required != null && !required.equals(type)) {
                throw invalid("documentType: must be " + required + " when linkType is " + link);
            }
        }
        if (file == null) {
            throw invalid("file: is required");
        }
        if (linkId != null && linkId < 1) {
            throw invalid("linkId: must be greater than 0");
        }
        if (linkId != null && link == null) {
            throw invalid("linkId: requires linkType");
        }
        if (link != null && !linkKnown) {
            throw invalid("linkType: must be one of " + DocumentTypes.linkTypeList());
        }
        if (patient == null) {
            throw invalid("patientSystemId: must not be blank");
        }
        if (!PATIENT_ID.matcher(patient).matches()) {
            throw invalid("patientSystemId: must be a patient id such as PAT-00001");
        }
        if (cleanTitle.isEmpty()) {
            throw invalid("title: must not be blank");
        }
        if (cleanTitle.length() > Document.TITLE_MAX) {
            throw invalid("title: size must be between 0 and " + Document.TITLE_MAX);
        }
        return new UploadCommand(patient, type, cleanTitle, cleanDescription, link, linkId, file);
    }

    /**
     * Steps 4 to 8 of the upload: authorisation ({@code MANAGE_DOCUMENTS}), rate limit, patient and link target,
     * scan and allow-list, storage under {@code documents/<yyyy>/<mm>/<uuid>}, row, audit {@code CREATE} and
     * {@link DocumentUploadedEvent}. Equivalent to {@link #commitUpload commitUpload(storeUpload(command))}.
     *
     * Deliberately not {@code @Transactional}: the checks are short reads, and the scan and {@link FileStorage#put}
     * (which may read up to 10 MB from a slow provider) run before any transaction of this method is opened, so they
     * do not hold a pooled database connection. Only the insert, the audit row and the event run in one short
     * transaction. If that transaction does not commit, the stored file is deleted again (best effort).
     *
     * With an {@code Idempotency-Key} the controller calls the two phases through
     * {@link com.carenexus.api.common.idempotency.IdempotencyService#execute(String, String, Class,
     * java.util.function.Supplier, java.util.function.Function, java.util.function.Consumer)}: the replay check
     * comes first, then {@link #storeUpload} outside any transaction, then {@link #commitUpload} inside the
     * idempotency transaction, and {@link #discardUpload} after a failed commit. A slow scanner or storage provider
     * therefore never holds a pooled connection on that path either.
     */
    public DocumentResponse upload(UploadCommand command) {
        return commitUpload(storeUpload(command));
    }

    /** A file that passed the checks and the scan and is stored, but has no documents row yet. */
    public record StoredUpload(Document row, String storageKey) {
    }

    /**
     * Phase one of the upload: authorisation, rate limit, patient and link target, scan, and the file write to
     * storage. Opens no transaction of its own.
     */
    public StoredUpload storeUpload(UploadCommand command) {
        authorizationService.requirePatientAccess(command.patientSystemId(), PatientPermission.MANAGE_DOCUMENTS);
        String caller = authorizationService.currentSystemId();
        rateLimitGuard.check(UPLOAD_BUCKET, caller);

        if (!referenceRepository.patientExists(command.patientSystemId())) {
            throw new ResourceNotFoundException("Patient not found");
        }
        if (command.linkId() != null
                && !referenceRepository.linkTargetExists(command.linkType(), command.linkId(), command.patientSystemId())) {
            throw invalid("linkId: no matching item for this patient");
        }

        MultipartFile file = command.file();
        long size = file.getSize();
        if (size <= 0 || file.isEmpty()) {
            throw new BadRequestException(ErrorResponse.FILE_REJECTED, FILE_EMPTY);
        }
        if (size > MAX_FILE_BYTES) {
            // Normally refused by the multipart limits before the controller runs; kept as a safety net.
            throw new MaxUploadSizeExceededException(MAX_FILE_BYTES);
        }
        String contentType = scan(file);
        String originalFilename = DocumentFileNames.sanitize(file.getOriginalFilename(), contentType);

        LocalDateTime now = LocalDateTime.now(clock);
        String key = storageKey(now.toLocalDate());
        StoredFile stored = store(key, contentType, file, size);

        Document row = Document.builder()
                .patientSystemId(command.patientSystemId())
                .uploadedBySystemId(caller)
                .documentType(command.documentType())
                .title(command.title())
                .description(command.description())
                .storageKey(stored.key())
                .originalFilename(originalFilename)
                .contentType(contentType)
                .sizeBytes(stored.size())
                .sha256(stored.sha256())
                .linkType(command.linkType())
                .linkId(command.linkId())
                .isDeleted(false)
                .uploadedAt(now)
                .build();
        return new StoredUpload(row, key);
    }

    /**
     * Phase two of the upload: the row, the audit row and the event in one short transaction (joining the caller's
     * when one is active). The stored file is deleted again if that transaction does not commit.
     */
    public DocumentResponse commitUpload(StoredUpload upload) {
        String key = upload.storageKey();
        try {
            return writeTransaction.execute(status -> {
                // Covers a rollback of this transaction, or of the caller's when the write joined it.
                deleteStoredFileUnlessCommitted(key);
                Document saved = documentRepository.saveAndFlush(upload.row());
                auditService.logClinicalAccess("CREATE", RESOURCE, saved.getDocumentId(), saved.getPatientSystemId());
                eventPublisher.publishEvent(new DocumentUploadedEvent(saved.getDocumentId(), saved.getPatientSystemId(),
                        saved.getUploadedBySystemId(), saved.getDocumentType()));
                return toResponses(List.of(saved)).get(0);
            });
        } catch (RuntimeException | Error writeFailure) {
            // Also covers a transaction that could not even begin (no synchronisation registered then).
            deleteStoredFileQuietly(key);
            throw writeFailure;
        }
    }

    /** Undoes phase one after a failed commit: deletes the stored file (best effort, never throws). */
    public void discardUpload(StoredUpload upload) {
        if (upload != null) {
            deleteStoredFileQuietly(upload.storageKey());
        }
    }

    // ==================== READ ====================

    /**
     * A patient's documents, newest first ({@code uploaded_at DESC, document_id DESC}), filtered in the database.
     * Patients (own record), related doctors and administrators see every type; a caregiver sees the types the
     * grant may read and gets 403 when it may read none (or lacks the permission of an explicit {@code type}).
     */
    @Transactional
    public PageResponse<DocumentResponse> listForPatient(String patientSystemId, String type, String linkType,
                                                         Integer linkId, Integer page, Integer size) {
        String documentType = DocumentTypes.normalize(type);
        String link = DocumentTypes.normalize(linkType);
        if (linkId != null && linkId < 1) {
            throw invalid("linkId: must be greater than 0");
        }
        if (linkId != null && link == null) {
            throw invalid("linkId: requires linkType");
        }
        if (link != null && !DocumentTypes.LINK_TYPES.contains(link)) {
            throw invalid("linkType: must be one of " + DocumentTypes.linkTypeList());
        }
        if (documentType != null && !DocumentTypes.DOCUMENT_TYPES.contains(documentType)) {
            throw invalid("type: must be one of " + DocumentTypes.documentTypeList());
        }
        String patient = patientSystemId == null ? "" : patientSystemId.trim();
        if (patient.isEmpty()) {
            throw new ResourceNotFoundException("Patient not found");
        }

        Set<String> readableTypes = readableTypes(patient, documentType);

        Pageable pageable = PageParams.of(page, size, NEWEST_FIRST);
        Page<Document> rows = documentRepository.findAll(
                DocumentSpecifications.visibleOfPatient(patient)
                        .and(DocumentSpecifications.typeIn(readableTypes))
                        .and(DocumentSpecifications.linkType(link))
                        .and(DocumentSpecifications.linkId(linkId)),
                pageable);

        auditService.logClinicalAccess("READ", RESOURCE, null, patient);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /** Metadata of one visible document; needs the read permission of its type. */
    @Transactional
    public DocumentResponse getDocument(Integer documentId) {
        Document document = findVisible(documentId);
        authorizationService.requirePatientAccess(document.getPatientSystemId(),
                DocumentTypes.readPermission(document.getDocumentType()), document.getDocumentId());
        auditService.logClinicalAccess("READ", RESOURCE, document.getDocumentId(), document.getPatientSystemId());
        return toResponses(List.of(document)).get(0);
    }

    /**
     * Opens the stored file of one visible document for streaming; needs the read permission of its type. The
     * {@code READ} audit row is written only once the stream was opened.
     *
     * @throws ResourceNotFoundException when the document does not exist for the API, or its file is missing
     * @throws ApiException              503 PROVIDER_UNAVAILABLE when the storage cannot be read
     */
    @Transactional
    public DocumentDownload openDownload(Integer documentId) {
        Document document = findVisible(documentId);
        authorizationService.requirePatientAccess(document.getPatientSystemId(),
                DocumentTypes.readPermission(document.getDocumentType()), document.getDocumentId());
        InputStream content;
        try {
            content = fileStorage.open(document.getStorageKey());
        } catch (StoredFileNotFoundException missing) {
            log.warn("Stored file missing for document {}", document.getDocumentId());
            throw new ResourceNotFoundException(FILE_NOT_AVAILABLE);
        } catch (StorageException failure) {
            log.error("Could not open the stored file of document {}: {}", document.getDocumentId(),
                    failure.getClass().getName());
            throw storageUnavailable();
        }
        // From here on the stream is ours until the response converter takes it: close it if anything fails,
        // including the commit of this transaction after the method returned.
        try {
            closeUnlessCommitted(content);
            auditService.logClinicalAccess("READ", RESOURCE, document.getDocumentId(), document.getPatientSystemId());
            long size = document.getSizeBytes() == null ? -1 : document.getSizeBytes();
            return new DocumentDownload(document.getOriginalFilename(), document.getContentType(), size, content);
        } catch (RuntimeException | Error failure) {
            closeQuietly(content);
            throw failure;
        }
    }

    // ==================== DELETE ====================

    /**
     * Soft delete: {@code is_deleted = TRUE} and {@code deleted_at}; the stored file is kept and the storage is not
     * called. Needs {@code MANAGE_DOCUMENTS} on the patient, and the caller must be the patient, the uploader or an
     * administrator. A second delete answers 404.
     */
    @Transactional
    public void delete(Integer documentId) {
        Document document = documentRepository.findVisibleByIdForUpdate(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        authorizationService.requirePatientAccess(document.getPatientSystemId(), PatientPermission.MANAGE_DOCUMENTS,
                document.getDocumentId());
        String caller = authorizationService.currentSystemId();
        if (!mayDelete(document, caller)) {
            try {
                auditService.logAccessDenied(caller, RESOURCE, document.getDocumentId(),
                        document.getPatientSystemId(), null, "Caller is neither the patient, the uploader nor an admin");
            } catch (RuntimeException auditFailure) {
                log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
            }
            throw new AuthorizationException(DELETE_NOT_ALLOWED, RESOURCE, document.getDocumentId());
        }
        document.setIsDeleted(true);
        document.setDeletedAt(LocalDateTime.now(clock));
        documentRepository.save(document);
        auditService.logClinicalAccess("DELETE", RESOURCE, document.getDocumentId(), document.getPatientSystemId());
    }

    // ==================== HELPERS ====================

    private Document findVisible(Integer documentId) {
        if (documentId == null) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return documentRepository.findVisibleById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    /**
     * The document types the caller may list for this patient, or a 403. {@code documentType} is the optional
     * filter (already validated).
     */
    private Set<String> readableTypes(String patientSystemId, String documentType) {
        boolean caregiver = AuthorizationService.CAREGIVER.equals(authorizationService.currentRole());
        if (documentType != null) {
            PatientPermission needed = caregiver ? DocumentTypes.readPermission(documentType) : PatientPermission.DOCUMENTS;
            authorizationService.requirePatientAccess(patientSystemId, needed);
            return Set.of(documentType);
        }
        if (!caregiver) {
            authorizationService.requirePatientAccess(patientSystemId, PatientPermission.DOCUMENTS);
            return new LinkedHashSet<>(DocumentTypes.DOCUMENT_TYPES);
        }
        Set<String> types = new LinkedHashSet<>();
        for (PatientPermission permission : DocumentTypes.READ_PERMISSIONS) {
            if (authorizationService.canAccessPatient(patientSystemId, permission)) {
                types.addAll(DocumentTypes.typesReadableWith(permission));
            }
        }
        if (types.isEmpty()) {
            // Throws the right 403 (no active grant, or "does not allow viewing documents") and audits the denial.
            authorizationService.requirePatientAccess(patientSystemId, PatientPermission.DOCUMENTS);
            types.addAll(DocumentTypes.typesReadableWith(PatientPermission.DOCUMENTS));
        }
        return types;
    }

    private boolean mayDelete(Document document, String caller) {
        return caller.equals(document.getPatientSystemId())
                || caller.equals(document.getUploadedBySystemId())
                || authorizationService.isAdmin();
    }

    /** Runs the scanner and applies the allow-list; returns the detected type in its lower-case spelling. */
    private String scan(MultipartFile file) {
        ScanResult result;
        try (InputStream in = file.getInputStream()) {
            result = fileScanner.scan(in, file.getContentType(), file.getOriginalFilename());
        } catch (IOException e) {
            log.error("Could not read an uploaded file for scanning: {}", e.getClass().getName());
            throw storageUnavailable();
        } catch (StorageException e) {
            log.error("File scanner {} failed: {}", fileScanner.providerName(), e.getClass().getName());
            throw storageUnavailable();
        }
        if (result == null || !result.clean()) {
            log.warn("Upload rejected by scanner {}: {}", fileScanner.providerName(),
                    result == null ? "no result" : result.reason());
            throw new BadRequestException(ErrorResponse.FILE_REJECTED, FILE_INFECTED);
        }
        String allowed = DocumentTypes.allowedContentType(result.detectedContentType());
        if (allowed == null) {
            log.info("Upload rejected: detected content type {} is not allowed", result.detectedContentType());
            throw new BadRequestException(ErrorResponse.FILE_REJECTED, FILE_TYPE_NOT_ALLOWED);
        }
        return allowed;
    }

    private StoredFile store(String key, String contentType, MultipartFile file, long size) {
        try (InputStream in = file.getInputStream()) {
            return fileStorage.put(key, contentType, in, size);
        } catch (IOException | StorageException e) {
            log.error("Could not store an uploaded document under storage provider {}: {}",
                    fileStorage.providerName(), e.getClass().getName());
            deleteStoredFileQuietly(key);
            throw storageUnavailable();
        }
    }

    /** {@code documents/<yyyy>/<mm>/<uuid>} from the clinic date; never derived from the client's file name. */
    private static String storageKey(LocalDate clinicDate) {
        return String.format(Locale.ROOT, "documents/%04d/%02d/%s",
                clinicDate.getYear(), clinicDate.getMonthValue(), UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
    }

    /** Removes the stored file if the surrounding transaction rolls back (best effort). */
    private void deleteStoredFileUnlessCommitted(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteStoredFileQuietly(key);
                }
            }
        });
    }

    /** Closes an opened download stream if the surrounding transaction does not commit. */
    private static void closeUnlessCommitted(InputStream content) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    closeQuietly(content);
                }
            }
        });
    }

    private static void closeQuietly(InputStream content) {
        try {
            content.close();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not close a download stream: {}", e.getClass().getName());
        }
    }

    private void deleteStoredFileQuietly(String key) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete the orphaned stored file of a failed upload: {}", e.getClass().getName());
        }
    }

    private static ApiException storageUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.PROVIDER_UNAVAILABLE, STORAGE_UNAVAILABLE);
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(ErrorResponse.VALIDATION_FAILED, message);
    }

    /** Maps rows to responses, resolving every uploader's name and role with one query. */
    private List<DocumentResponse> toResponses(List<Document> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        Set<String> uploaderIds = documents.stream().map(Document::getUploadedBySystemId).collect(Collectors.toSet());
        Map<String, User> uploaders = uploaderRepository.findBySystemIdIn(uploaderIds).stream()
                .collect(Collectors.toMap(User::getSystemId, Function.identity(), (a, b) -> a));
        String caller = authorizationService.currentSystemId();
        boolean admin = authorizationService.isAdmin();

        List<DocumentResponse> responses = new ArrayList<>(documents.size());
        for (Document d : documents) {
            User uploader = uploaders.get(d.getUploadedBySystemId());
            String role = uploaderRole(uploader, d.getUploadedBySystemId());
            boolean canDelete = admin || caller.equals(d.getPatientSystemId()) || caller.equals(d.getUploadedBySystemId());
            responses.add(new DocumentResponse(
                    d.getDocumentId(),
                    d.getPatientSystemId(),
                    d.getUploadedBySystemId(),
                    displayName(uploader, role, d.getUploadedBySystemId()),
                    role,
                    d.getDocumentType(),
                    d.getTitle(),
                    d.getDescription(),
                    d.getOriginalFilename(),
                    d.getContentType(),
                    d.getSizeBytes(),
                    d.getLinkType(),
                    d.getLinkId(),
                    d.getUploadedAt(),
                    canDelete));
        }
        return responses;
    }

    private static String uploaderRole(User uploader, String systemId) {
        if (uploader != null && uploader.getRole() != null) {
            return uploader.getRole().toUpperCase(Locale.ROOT);
        }
        String prefix = systemId == null || systemId.length() < 3 ? "" : systemId.substring(0, 3);
        return switch (prefix) {
            case "PAT" -> AuthorizationService.PATIENT;
            case "DOC" -> AuthorizationService.DOCTOR;
            case "CGV" -> AuthorizationService.CAREGIVER;
            case "ADM" -> AuthorizationService.ADMIN;
            default -> prefix;
        };
    }

    /** {@code "First Last"}, {@code "Dr. First Last"} for doctors (no doubled title). */
    static String displayName(User uploader, String role, String systemId) {
        if (uploader == null) {
            return systemId;
        }
        String first = uploader.getFirstName() == null ? "" : uploader.getFirstName().trim();
        String last = uploader.getLastName() == null ? "" : uploader.getLastName().trim();
        String name = (first + " " + last).trim();
        if (name.isEmpty()) {
            return systemId;
        }
        if (AuthorizationService.DOCTOR.equals(role) && !DOCTOR_TITLE.matcher(first).matches()) {
            return "Dr. " + name;
        }
        return name;
    }
}
