package com.carenexus.api.account.legal.service;

import com.carenexus.api.account.legal.dto.request.AcceptLegalRequest;
import com.carenexus.api.account.legal.dto.request.PublishLegalDocumentRequest;
import com.carenexus.api.account.legal.dto.response.LegalAcceptanceAdminResponse;
import com.carenexus.api.account.legal.dto.response.LegalAcceptanceResponse;
import com.carenexus.api.account.legal.dto.response.LegalCurrentResponse;
import com.carenexus.api.account.legal.dto.response.LegalDocumentResponse;
import com.carenexus.api.account.legal.dto.response.LegalDocumentSummary;
import com.carenexus.api.account.legal.dto.response.LegalStatusResponse;
import com.carenexus.api.account.legal.model.LegalAcceptance;
import com.carenexus.api.account.legal.model.LegalDocument;
import com.carenexus.api.account.legal.repository.LegalAcceptanceRepository;
import com.carenexus.api.account.legal.repository.LegalDocumentHeader;
import com.carenexus.api.account.legal.repository.LegalDocumentRepository;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Terms of service and privacy policy: the published versions, what each account accepted, and the rules that
 * decide whether an account still has to accept something.
 *
 * <ul>
 *   <li>The current version of a type is the one with the greatest effective date (tie: newest row). Versions are
 *       immutable and never scheduled: a published version is current at once.</li>
 *   <li>Versions are opaque labels compared exactly (case-sensitive) after trimming.</li>
 *   <li>An account has to act ({@code legalActionRequired}) while it has not accepted the current version of
 *       either type. Nothing else in the API is refused because of it.</li>
 *   <li>Acceptance always belongs to the signed-in account; nobody accepts for anybody else.</li>
 * </ul>
 *
 * Audit: LEGAL_ACCEPT (resource ACCOUNT, actor and target the accepting account) per row written, LEGAL_PUBLISH
 * (resource LEGAL_DOCUMENT, actor the administrator) per version published. Reads are not audited.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class LegalService {

    static final String RESOURCE = "ACCOUNT";
    static final String DOCUMENT_RESOURCE = "LEGAL_DOCUMENT";
    public static final String ACTION_ACCEPT = "LEGAL_ACCEPT";
    public static final String ACTION_PUBLISH = "LEGAL_PUBLISH";

    public static final String DOCUMENT_NOT_FOUND = "Legal document not found";
    public static final String USER_NOT_FOUND = "User not found";
    public static final String STALE_ACCEPT_MESSAGE =
            "This version is no longer current. Review the latest version and accept it.";
    public static final String STALE_REGISTRATION_MESSAGE =
            "The terms or privacy policy changed. Review the current version and accept it.";
    public static final String DUPLICATE_VERSION_MESSAGE = "This version of the document already exists";
    public static final String EARLIER_THAN_CURRENT_MESSAGE =
            "effectiveAt must not be earlier than the current version's effective date";
    static final String FUTURE_EFFECTIVE_MESSAGE = "effectiveAt: must be a date in the past or in the present";

    /** Hard cap of the caller's own acceptance list. */
    static final int MAX_OWN_ACCEPTANCES = 50;

    private static final Sort ACCEPTANCE_ORDER = Sort.by(Sort.Order.desc("acceptedAt"), Sort.Order.desc("acceptanceId"));

    private final LegalDocumentRepository documentRepository;
    private final LegalAcceptanceRepository acceptanceRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    /** Outcome of an acceptance: the caller's state afterwards and whether a row was written (201) or not (200). */
    public record AcceptOutcome(LegalStatusResponse status, boolean created) {
    }

    // ==================== PUBLIC ====================

    /**
     * Current version of both documents, without the text. Public.
     */
    @Transactional(readOnly = true)
    public LegalCurrentResponse current() {
        Map<String, LegalDocumentHeader> current = currentByType();
        return new LegalCurrentResponse(summaryOf(current.get(LegalDocument.TYPE_TERMS)),
                summaryOf(current.get(LegalDocument.TYPE_PRIVACY)));
    }

    /**
     * Current text of one document. Public. {@code rawType} is case-insensitive ({@code terms}, {@code PRIVACY}).
     *
     * @throws ResourceNotFoundException for any other type
     */
    @Transactional(readOnly = true)
    public LegalDocumentResponse currentDocument(String rawType) {
        String docType = LegalDocument.normalizeType(rawType);
        if (docType == null) {
            throw new ResourceNotFoundException(DOCUMENT_NOT_FOUND);
        }
        return documentRepository.findCurrentByDocType(docType)
                .map(LegalService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(DOCUMENT_NOT_FOUND));
    }

    // ==================== SIGNED-IN ACCOUNT ====================

    /**
     * The caller's acceptances and the current documents still to accept. Any role, own account only.
     */
    @Transactional(readOnly = true)
    public LegalStatusResponse myStatus() {
        return statusOf(authorizationService.currentSystemId());
    }

    /**
     * Records that the caller accepts the current version of one document. Any role, own account only.
     * Repeating it for a version already accepted writes nothing and returns the current state.
     *
     * @param ipAddress client address of the request (recorded, cut to 45 characters)
     * @param userAgent User-Agent header of the request (recorded, cut to 500 characters)
     * @throws BadRequestException code LEGAL_ACCEPTANCE_REQUIRED when the version is not the current one
     */
    public AcceptOutcome accept(AcceptLegalRequest request, String ipAddress, String userAgent) {
        String systemId = authorizationService.currentSystemId();
        String docType = LegalDocument.normalizeType(request == null ? null : request.getDocType());
        if (docType == null) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "docType: must be TERMS or PRIVACY");
        }
        String version = trimToNull(request.getVersion());
        if (version == null) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "version: must not be blank");
        }

        LegalDocumentHeader current = currentByType().get(docType);
        if (current == null) {
            throw new ResourceNotFoundException(DOCUMENT_NOT_FOUND);
        }
        if (!current.version().equals(version)) {
            throw new BadRequestException(ErrorResponse.LEGAL_ACCEPTANCE_REQUIRED, STALE_ACCEPT_MESSAGE);
        }

        boolean created = record(systemId, docType, version, ipAddress, userAgent);
        return new AcceptOutcome(statusOf(systemId), created);
    }

    /**
     * True when the account has not accepted the current version of at least one document. Drives the session
     * flag {@code legalActionRequired}.
     */
    @Transactional(readOnly = true)
    public boolean isActionRequired(String systemId) {
        return acceptanceRepository.countUnacceptedCurrentVersions(systemId) > 0;
    }

    // ==================== REGISTRATION ====================

    /**
     * Applies the versions a self-registration accepted, inside the registration transaction.
     * <ul>
     *   <li>Accounts created by a caregiver or an administrator ({@code createdBySystemId} set): nothing is
     *       written; the person accepts at first sign-in.</li>
     *   <li>Each version is judged on its own: null or blank means "not accepted yet" and writes nothing.</li>
     *   <li>A version that is not the current one aborts the registration with 400 LEGAL_ACCEPTANCE_REQUIRED.</li>
     *   <li>A current version writes one row with the request's address and User-Agent, audited LEGAL_ACCEPT.</li>
     * </ul>
     */
    public void recordRegistrationAcceptances(UserRegisteredEvent event) {
        if (event == null || event.systemId() == null || event.createdBySystemId() != null) {
            return;
        }
        Map<String, String> accepted = new LinkedHashMap<>();
        putIfPresent(accepted, LegalDocument.TYPE_TERMS, event.acceptedTermsVersion());
        putIfPresent(accepted, LegalDocument.TYPE_PRIVACY, event.acceptedPrivacyVersion());
        if (accepted.isEmpty()) {
            return;
        }

        // Check every version before writing anything.
        Map<String, LegalDocumentHeader> current = currentByType();
        for (Map.Entry<String, String> entry : accepted.entrySet()) {
            LegalDocumentHeader currentVersion = current.get(entry.getKey());
            if (currentVersion == null || !currentVersion.version().equals(entry.getValue())) {
                log.info("Registration of {} refused: stale {} version", event.systemId(), entry.getKey());
                throw new BadRequestException(ErrorResponse.LEGAL_ACCEPTANCE_REQUIRED, STALE_REGISTRATION_MESSAGE);
            }
        }
        accepted.forEach((docType, version) ->
                record(event.systemId(), docType, version, event.ip(), event.userAgent()));
    }

    // ==================== ADMINISTRATION ====================

    /**
     * Acceptance history of one account with the recorded evidence, newest first. ADMIN only.
     *
     * @throws ResourceNotFoundException when no account has this system id
     */
    @Transactional(readOnly = true)
    public PageResponse<LegalAcceptanceAdminResponse> acceptancesOf(String systemId, int page, int size) {
        authorizationService.requireAdmin();
        String target = trimToNull(systemId);
        if (target == null || !userRepository.existsBySystemId(target)) {
            throw new ResourceNotFoundException(USER_NOT_FOUND);
        }
        Map<String, String> currentVersions = currentVersions();
        Page<LegalAcceptance> rows = acceptanceRepository.findBySystemId(target,
                PageParams.of(page, size, ACCEPTANCE_ORDER));
        return PageResponse.of(rows, row -> new LegalAcceptanceAdminResponse(row.getAcceptanceId(), row.getSystemId(),
                row.getDocType(), row.getVersion(), row.getAcceptedAt(), row.getIpAddress(), row.getUserAgent(),
                isCurrent(currentVersions, row)));
    }

    /**
     * Publishes a new version of one document; it is current at once. ADMIN only.
     *
     * @throws BadRequestException (VALIDATION_FAILED) when effectiveAt is in the future
     * @throws ConflictException   when the version exists for this type, or effectiveAt is earlier than the current
     *                             version's
     */
    public LegalDocumentResponse publish(PublishLegalDocumentRequest request) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();

        String docType = LegalDocument.normalizeType(request.getDocType());
        if (docType == null) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "docType: must be TERMS or PRIVACY");
        }
        String version = trimToNull(request.getVersion());
        if (version == null || !version.matches(PublishLegalDocumentRequest.VERSION_PATTERN)) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED,
                    "version: must be 1 to 20 letters, digits, dots, dashes or underscores");
        }
        String title = trimToNull(request.getTitle());
        if (title == null || title.length() > 200) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED,
                    title == null ? "title: must not be blank" : "title: size must be between 0 and 200");
        }
        String contentMd = request.getContentMd();
        if (contentMd == null || contentMd.isBlank() || contentMd.length() > 100000) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, contentMd == null || contentMd.isBlank()
                    ? "contentMd: must not be blank" : "contentMd: size must be between 0 and 100000");
        }

        Instant now = Instant.now(clock);
        Instant effectiveAt = request.getEffectiveAt() == null ? now : request.getEffectiveAt();
        if (effectiveAt.isAfter(now)) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, FUTURE_EFFECTIVE_MESSAGE);
        }
        effectiveAt = effectiveAt.truncatedTo(ChronoUnit.SECONDS);

        // One publish per type at a time, so the checks below see every committed version.
        documentRepository.lockVersionsOfType(docType);
        if (documentRepository.existsByDocTypeAndVersion(docType, version)) {
            throw new ConflictException(DUPLICATE_VERSION_MESSAGE);
        }
        Instant currentEffectiveAt = documentRepository.findCurrentByDocType(docType)
                .map(LegalDocument::getEffectiveAt)
                .orElse(null);
        if (currentEffectiveAt != null && effectiveAt.isBefore(currentEffectiveAt)) {
            throw new ConflictException(EARLIER_THAN_CURRENT_MESSAGE);
        }

        LegalDocument saved = documentRepository.saveAndFlush(LegalDocument.builder()
                .docType(docType)
                .version(version)
                .title(title)
                .contentMd(contentMd)
                .effectiveAt(effectiveAt)
                .createdAt(now.truncatedTo(ChronoUnit.SECONDS))
                .publishedBySystemId(adminSystemId)
                .build());
        auditService.logAccountEvent(adminSystemId, ACTION_PUBLISH, DOCUMENT_RESOURCE, null, "SUCCESS");
        log.info("Legal document {} version {} (id {}) published by {}", docType, version, saved.getDocumentId(),
                adminSystemId);
        return toResponse(saved);
    }

    // ==================== INTERNALS ====================

    /** Writes one acceptance row unless it exists; audits a written row. Returns true when a row was written. */
    private boolean record(String systemId, String docType, String version, String ipAddress, String userAgent) {
        int inserted = acceptanceRepository.insertIfAbsent(systemId, docType, version,
                Instant.now(clock).truncatedTo(ChronoUnit.SECONDS),
                cut(trimToNull(ipAddress), LegalAcceptance.MAX_IP_LENGTH),
                cut(userAgent, LegalAcceptance.MAX_USER_AGENT_LENGTH));
        if (inserted > 0) {
            auditService.logAccountEvent(systemId, ACTION_ACCEPT, RESOURCE, systemId, "SUCCESS");
            return true;
        }
        return false;
    }

    private LegalStatusResponse statusOf(String systemId) {
        Map<String, LegalDocumentHeader> current = currentByType();
        Map<String, String> currentVersions = new LinkedHashMap<>();
        current.forEach((type, header) -> currentVersions.put(type, header.version()));

        List<LegalAcceptanceResponse> accepted = acceptanceRepository
                .findTop50BySystemIdOrderByAcceptedAtDescAcceptanceIdDesc(systemId).stream()
                .limit(MAX_OWN_ACCEPTANCES)
                .map(row -> new LegalAcceptanceResponse(row.getAcceptanceId(), row.getDocType(), row.getVersion(),
                        row.getAcceptedAt(), isCurrent(currentVersions, row)))
                .toList();

        List<LegalDocumentSummary> pending = new ArrayList<>();
        for (String type : LegalDocument.TYPES) {
            LegalDocumentHeader header = current.get(type);
            if (header != null && !acceptanceRepository.existsBySystemIdAndDocTypeAndVersion(systemId, type,
                    header.version())) {
                pending.add(summaryOf(header));
            }
        }
        return new LegalStatusResponse(!pending.isEmpty(), accepted, List.copyOf(pending));
    }

    /** Current version of each type that has one, keyed by type in TERMS, PRIVACY order. */
    private Map<String, LegalDocumentHeader> currentByType() {
        Map<String, LegalDocumentHeader> byType = new LinkedHashMap<>();
        for (LegalDocumentHeader header : documentRepository.findCurrentHeaders()) {
            byType.putIfAbsent(header.docType(), header);
        }
        Map<String, LegalDocumentHeader> ordered = new LinkedHashMap<>();
        for (String type : LegalDocument.TYPES) {
            if (byType.containsKey(type)) {
                ordered.put(type, byType.get(type));
            }
        }
        return ordered;
    }

    private Map<String, String> currentVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        currentByType().forEach((type, header) -> versions.put(type, header.version()));
        return versions;
    }

    private static boolean isCurrent(Map<String, String> currentVersions, LegalAcceptance row) {
        return row.getVersion() != null && row.getVersion().equals(currentVersions.get(row.getDocType()));
    }

    private static LegalDocumentSummary summaryOf(LegalDocumentHeader header) {
        if (header == null) {
            return null;
        }
        return new LegalDocumentSummary(header.docType(), header.version(), header.title(), header.effectiveAt(),
                header.pendingLegalReview());
    }

    private static LegalDocumentResponse toResponse(LegalDocument document) {
        return new LegalDocumentResponse(document.getDocType(), document.getVersion(), document.getTitle(),
                document.getContentMd(), document.getEffectiveAt(),
                LegalDocument.isPendingLegalReview(document.getContentMd()));
    }

    private static void putIfPresent(Map<String, String> target, String docType, String version) {
        String trimmed = trimToNull(version);
        if (trimmed != null) {
            target.put(docType, trimmed);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
