package com.carenexus.api.core.service;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.common.repository.AuditLogRepository;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateMedicalRecordRequest;
import com.carenexus.api.core.dto.request.UpdateMedicalRecordRequest;
import com.carenexus.api.core.dto.response.MedicalRecordResponse;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.MedicalRecord;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.MedicalRecordRepository;
import com.carenexus.api.terminology.dto.request.IcdCodeInput;
import com.carenexus.api.terminology.provider.CodeValidation;
import com.carenexus.api.terminology.provider.IcdCodeFormat;
import com.carenexus.api.terminology.provider.TerminologyProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Medical records. Every public method enforces access for the current caller and writes a clinical audit row
 * (resource type {@value #RESOURCE}) after it succeeds.
 *
 * <ul>
 *   <li>Create: an active DOCTOR with a care relationship to the patient ({@code requireTreatingDoctor}); the
 *       author is always the caller.</li>
 *   <li>Read: the authoring doctor, or {@code requirePatientAccess(patient, RECORDS)} (patient self, related
 *       doctor, caregiver whose grant allows records, admin).</li>
 *   <li>Update and soft delete: the authoring doctor or an administrator.</li>
 *   <li>A soft-deleted record answers 404 to everybody and is never listed.</li>
 *   <li>At most one live CONSULT record per consultation (V40 partial unique index); the automatic record of a
 *       completed consultation is created by {@link #recordCompletedConsultation}.</li>
 * </ul>
 * Lists are sorted {@code created_at DESC, record_id DESC} and filtered and paged in the database.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MedicalRecordService {

    public static final String RESOURCE = "MEDICAL_RECORD";
    private static final PatientPermission PERMISSION = PatientPermission.RECORDS;

    /** Hard cap of the deprecated array endpoints. */
    public static final int LEGACY_LIST_CAP = 200;
    public static final int MAX_AUTO_TITLE = CreateMedicalRecordRequest.MAX_TITLE;
    static final String CONSULT_RECORD_EXISTS = "A consultation record already exists for this consultation";
    static final String AUTO_TITLE_FALLBACK = "Consultation";
    private static final Pattern DOCTOR_TITLE = Pattern.compile("^dr(\\.\\s*|\\s+|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final String INSERT_CONSULTATION_RECORD = """
            INSERT INTO medical_records (patient_system_id, doctor_system_id, consultation_id, record_type, title,
                                         description, treatment_plan, observations, icd_codes, source, is_deleted,
                                         created_at, updated_at)
            SELECT ?, ?, ?, 'CONSULT', ?, ?, ?, ?, NULL, 'CONSULTATION', FALSE, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM medical_records
                              WHERE consultation_id = ? AND record_type = 'CONSULT' AND is_deleted = FALSE)
            ON CONFLICT DO NOTHING
            RETURNING record_id
            """;

    private final MedicalRecordRepository medicalRecordRepository;
    private final ConsultationRepository consultationRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;
    private final UserContext userContext;
    private final TerminologyProvider terminologyProvider;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final Clock clock;

    // ==================== WRITES ====================

    /**
     * Files a record ({@code source = MANUAL}). DOCTOR only; the author is the caller.
     */
    public MedicalRecordResponse createRecord(CreateMedicalRecordRequest request) {
        String doctorSystemId = consultationService.requireTreatingDoctor(
                request.getPatientSystemId(), request.getConsultationId(), PERMISSION);

        String recordType = normalizeType(request.getRecordType());
        String icdCodes = normalizeIcdCodes(request.getIcdCodes());
        requireNoOtherConsultRecord(recordType, request.getConsultationId(), 0);

        LocalDateTime now = now();
        MedicalRecord record = MedicalRecord.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(doctorSystemId)
                .consultationId(request.getConsultationId())
                .recordType(recordType)
                .title(request.getTitle().trim())
                .description(trimToNull(request.getDescription()))
                .treatmentPlan(trimToNull(request.getTreatmentPlan()))
                .observations(trimToNull(request.getObservations()))
                .icdCodes(icdCodes)
                .source(MedicalRecord.SOURCE_MANUAL)
                .isDeleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        MedicalRecord saved = medicalRecordRepository.saveAndFlush(record);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getRecordId(), saved.getPatientSystemId());
        return toResponse(saved);
    }

    /**
     * Partial update. Authoring doctor or administrator.
     */
    public MedicalRecordResponse updateRecord(Integer recordId, UpdateMedicalRecordRequest request) {
        MedicalRecord record = load(recordId);
        consultationService.requireAuthorDoctor(record.getDoctorSystemId(), record.getPatientSystemId(), RESOURCE, recordId, true);

        if (request.getRecordType() != null) {
            record.setRecordType(normalizeType(request.getRecordType()));
        }
        if (request.getTitle() != null) {
            record.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            record.setDescription(trimToNull(request.getDescription()));
        }
        if (request.getTreatmentPlan() != null) {
            record.setTreatmentPlan(trimToNull(request.getTreatmentPlan()));
        }
        if (request.getObservations() != null) {
            record.setObservations(trimToNull(request.getObservations()));
        }
        if (request.getIcdCodes() != null) {
            record.setIcdCodes(normalizeIcdCodes(request.getIcdCodes()));
        }
        requireNoOtherConsultRecord(record.getRecordType(), record.getConsultationId(), record.getRecordId());

        medicalRecordRepository.saveAndFlush(record);
        // updated_at is stamped by the database trigger: read the stored row back.
        entityManager.refresh(record);
        auditService.logClinicalAccess("UPDATE", RESOURCE, recordId, record.getPatientSystemId());
        return toResponse(record);
    }

    /**
     * Soft delete ({@code is_deleted}, {@code deleted_at} = clinic now). Authoring doctor or administrator.
     */
    public void softDeleteRecord(Integer recordId) {
        MedicalRecord record = load(recordId);
        consultationService.requireAuthorDoctor(record.getDoctorSystemId(), record.getPatientSystemId(), RESOURCE, recordId, true);

        record.setIsDeleted(true);
        record.setDeletedAt(now());
        medicalRecordRepository.saveAndFlush(record);
        auditService.logClinicalAccess("DELETE", RESOURCE, recordId, record.getPatientSystemId());
    }

    /**
     * Creates the CONSULT record of a completed consultation, at most once per consultation. Runs inside the
     * transaction of the completing request (called by the ConsultationCompletedEvent listener). A repeated event,
     * or a live CONSULT record already filed by hand, inserts nothing and never fails.
     *
     * @return the new record id, or null when no row was inserted
     */
    public Integer recordCompletedConsultation(ConsultationCompletedEvent event) {
        if (event == null || event.consultationId() == null) {
            return null;
        }
        Consultation consultation = consultationRepository.findById(event.consultationId()).orElse(null);
        if (consultation == null) {
            log.warn("Consultation {} completed but not found; no medical record created", event.consultationId());
            return null;
        }

        LocalDateTime now = now();
        Timestamp stamp = Timestamp.valueOf(now);
        List<Integer> inserted = jdbcTemplate.queryForList(INSERT_CONSULTATION_RECORD, Integer.class,
                consultation.getPatientSystemId(),
                consultation.getDoctorSystemId(),
                consultation.getConsultationId(),
                automaticTitle(consultation.getChiefComplaint(), consultation.getDiagnosis()),
                trimToNull(consultation.getClinicalNotes()),
                trimToNull(consultation.getTreatmentPlan()),
                trimToNull(consultation.getPhysicalExamination()),
                stamp, stamp,
                consultation.getConsultationId());
        if (inserted.isEmpty()) {
            log.debug("Consultation {} already has a live CONSULT record", consultation.getConsultationId());
            return null;
        }
        Integer recordId = inserted.get(0);
        auditAsCompletingDoctor(recordId, consultation.getPatientSystemId(), consultation.getDoctorSystemId());
        log.info("Created medical record {} for completed consultation {}", recordId, consultation.getConsultationId());
        return recordId;
    }

    // ==================== READS ====================

    /**
     * One record. The authoring doctor may always read it; anyone else needs read permission on the patient.
     */
    @Transactional(readOnly = true)
    public MedicalRecordResponse getRecordById(Integer recordId) {
        MedicalRecord record = load(recordId);
        boolean author = AuthorizationService.DOCTOR.equals(authorizationService.currentRole())
                && authorizationService.currentSystemId().equals(record.getDoctorSystemId());
        if (!author) {
            authorizationService.requirePatientAccess(record.getPatientSystemId(), PERMISSION, recordId);
        }
        auditService.logClinicalAccess("READ", RESOURCE, recordId, record.getPatientSystemId());
        return toResponse(record);
    }

    /**
     * The canonical paged list (GET /api/medical-records). Scope by role:
     * PATIENT own records (another patient: 403); CAREGIVER needs {@code patientSystemId} and the records
     * permission; DOCTOR with {@code doctorSystemId} = self lists own records, with only {@code patientSystemId}
     * lists that patient's records from every author (care relationship required), with neither lists own records;
     * ADMIN unrestricted.
     */
    @Transactional(readOnly = true)
    public PageResponse<MedicalRecordResponse> searchRecords(String patientSystemId, String doctorSystemId, String type,
                                                             Integer consultationId, String q, int page, int size) {
        String patient = blankToNull(patientSystemId);
        String doctor = blankToNull(doctorSystemId);
        String role = authorizationService.currentRole();
        String caller = authorizationService.currentSystemId();

        switch (role) {
            case AuthorizationService.PATIENT -> {
                if (patient == null) {
                    patient = caller;
                }
                authorizationService.requirePatientAccess(patient, PERMISSION);
            }
            case AuthorizationService.CAREGIVER -> {
                if (patient == null) {
                    throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "patientSystemId: is required");
                }
                authorizationService.requirePatientAccess(patient, PERMISSION);
            }
            case AuthorizationService.DOCTOR -> {
                if (doctor != null) {
                    authorizationService.requireDoctorSelf(doctor);
                } else if (patient != null) {
                    authorizationService.requirePatientAccess(patient, PERMISSION);
                } else {
                    doctor = caller;
                }
            }
            case AuthorizationService.ADMIN -> {
                // unrestricted
            }
            default -> authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                    AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        }

        Pageable pageable = PageParams.of(page, size, null);
        Page<MedicalRecord> rows = query(patient, doctor, type, consultationId, q, pageable);
        auditList(rows.getContent(), patient);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /**
     * Legacy array: a patient's live records from every author, newest first, at most {@value #LEGACY_LIST_CAP}.
     */
    @Transactional(readOnly = true)
    public List<MedicalRecordResponse> getPatientRecords(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        List<MedicalRecord> rows = query(patientSystemId, null, null, null, null, legacyPage()).getContent();
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    /**
     * Legacy array: records a doctor authored (that doctor or an admin), newest first, at most
     * {@value #LEGACY_LIST_CAP}.
     */
    @Transactional(readOnly = true)
    public List<MedicalRecordResponse> getDoctorRecords(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<MedicalRecord> rows = query(null, doctorSystemId, null, null, null, legacyPage()).getContent();
        auditService.logListRead(RESOURCE, rows, MedicalRecord::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * Legacy array of GET /api/doctors/medical-records: records the calling doctor authored, optionally narrowed by
     * type and patient, newest first, at most {@value #LEGACY_LIST_CAP}. An administrator gets an empty list.
     */
    @Transactional(readOnly = true)
    public List<MedicalRecordResponse> getCallerDoctorRecords(String type, String patientSystemId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        if (authorizationService.isAdmin()) {
            return List.of();
        }
        String doctorSystemId = authorizationService.currentSystemId();
        List<MedicalRecord> rows = query(blankToNull(patientSystemId), doctorSystemId, type, null, null, legacyPage())
                .getContent();
        auditService.logListRead(RESOURCE, rows, MedicalRecord::getPatientSystemId);
        return toResponses(rows);
    }

    // ==================== INTERNALS ====================

    private Page<MedicalRecord> query(String patient, String doctor, String type, Integer consultationId, String q,
                                      Pageable pageable) {
        String recordType = blankToNull(type) == null ? "" : normalizeType(type);
        String text = blankToNull(q);
        String qPattern = text == null ? "" : "%" + escapeLike(text.trim()) + "%";
        return medicalRecordRepository.search(
                patient == null ? "" : patient,
                doctor == null ? "" : doctor,
                recordType,
                consultationId == null ? 0 : consultationId,
                qPattern,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }

    private static Pageable legacyPage() {
        return PageRequest.of(0, LEGACY_LIST_CAP);
    }

    /** One READ row per distinct patient on the page; one row for the scoped patient when the page is empty. */
    private void auditList(List<MedicalRecord> rows, String scopedPatient) {
        if (rows.isEmpty()) {
            if (scopedPatient != null) {
                auditService.logClinicalAccess("READ", RESOURCE, null, scopedPatient);
            }
            return;
        }
        auditService.logListRead(RESOURCE, rows, MedicalRecord::getPatientSystemId);
    }

    /**
     * The automatic record's CREATE row is written as the doctor completing the consultation. On the real path
     * that doctor is the authenticated caller (full request context); without one, the row is written directly.
     */
    private void auditAsCompletingDoctor(Integer recordId, String patientSystemId, String doctorSystemId) {
        if (Objects.equals(userContext.getCurrentUserSystemId(), doctorSystemId)) {
            auditService.logClinicalAccess("CREATE", RESOURCE, recordId, patientSystemId);
            return;
        }
        auditLogRepository.save(AuditLog.builder()
                .systemId(doctorSystemId)
                .actorRole(AuthorizationService.DOCTOR)
                .action("CREATE")
                .resourceType(RESOURCE)
                .resourceId(recordId)
                .patientSystemId(patientSystemId)
                .status("SUCCESS")
                .build());
    }

    private MedicalRecord load(Integer recordId) {
        if (recordId == null) {
            throw new ResourceNotFoundException("Medical record not found");
        }
        return medicalRecordRepository.findByRecordIdAndIsDeletedFalse(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Medical record not found"));
    }

    private void requireNoOtherConsultRecord(String recordType, Integer consultationId, Integer ownRecordId) {
        if (!MedicalRecord.TYPE_CONSULT.equals(recordType) || consultationId == null) {
            return;
        }
        if (medicalRecordRepository.existsByConsultationIdAndRecordTypeAndIsDeletedFalseAndRecordIdNot(
                consultationId, MedicalRecord.TYPE_CONSULT, ownRecordId == null ? 0 : ownRecordId)) {
            throw new ConflictException(CONSULT_RECORD_EXISTS);
        }
    }

    /** Trimmed, upper-cased record type; must be one of the eight values. */
    private static String normalizeType(String recordType) {
        String type = recordType == null ? "" : recordType.trim().toUpperCase(Locale.ROOT);
        if (!MedicalRecord.RECORD_TYPES.contains(type)) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED,
                    "recordType: " + CreateMedicalRecordRequest.RECORD_TYPE_MESSAGE);
        }
        return type;
    }

    /**
     * Validates and normalises the code list: each code trimmed and upper-cased and checked with the terminology
     * provider (INVALID gives 400 with the index as sent); repeats are dropped (first kept); a blank description
     * is filled from the catalogue, or "" for a code outside it. Returns the JSON to store, or null for no codes.
     */
    String normalizeIcdCodes(List<IcdCodeInput> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Map<String, String> codes = new LinkedHashMap<>();
        for (int i = 0; i < input.size(); i++) {
            IcdCodeInput item = input.get(i);
            if (item == null) {
                throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "icdCodes[" + i + "]: must not be null");
            }
            if (item.getCode() == null || item.getCode().isBlank()) {
                throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "icdCodes[" + i + "].code: is required");
            }
            String code = IcdCodeFormat.normalize(item.getCode());
            if (terminologyProvider.validate(code) == CodeValidation.INVALID) {
                throw new BadRequestException(ErrorResponse.VALIDATION_FAILED,
                        "icdCodes[" + i + "].code: is not a valid ICD-10 code");
            }
            if (codes.containsKey(code)) {
                continue;
            }
            String description = trimToNull(item.getDescription());
            codes.put(code, description != null ? description : terminologyProvider.describe(code).orElse(""));
        }
        List<Map<String, String>> stored = new ArrayList<>(codes.size());
        codes.forEach((code, description) -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("code", code);
            entry.put("description", description);
            stored.add(entry);
        });
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise ICD codes", e);
        }
    }

    /**
     * Title of an automatic record: the chief complaint, else the diagnosis, else "Consultation"; whitespace
     * collapsed, cut to 197 characters plus "..." when longer than 200.
     */
    static String automaticTitle(String chiefComplaint, String diagnosis) {
        String title = collapse(chiefComplaint);
        if (title.isEmpty()) {
            title = collapse(diagnosis);
        }
        if (title.isEmpty()) {
            return AUTO_TITLE_FALLBACK;
        }
        if (title.length() <= MAX_AUTO_TITLE) {
            return title;
        }
        int cut = MAX_AUTO_TITLE - 3;
        if (Character.isHighSurrogate(title.charAt(cut - 1))) {
            cut--;
        }
        return title.substring(0, cut) + "...";
    }

    private static String collapse(String text) {
        return text == null ? "" : WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    private List<MedicalRecordResponse> toResponses(List<MedicalRecord> rows) {
        Map<String, String> names = doctorNames(rows.stream().map(MedicalRecord::getDoctorSystemId).toList());
        return rows.stream().map(r -> toResponse(r, names.get(r.getDoctorSystemId()))).toList();
    }

    private MedicalRecordResponse toResponse(MedicalRecord record) {
        return toResponse(record, doctorNames(List.of(record.getDoctorSystemId())).get(record.getDoctorSystemId()));
    }

    private MedicalRecordResponse toResponse(MedicalRecord record, String doctorName) {
        return MedicalRecordResponse.builder()
                .recordId(record.getRecordId())
                .patientSystemId(record.getPatientSystemId())
                .doctorSystemId(record.getDoctorSystemId())
                .doctorName(doctorName)
                .consultationId(record.getConsultationId())
                .recordType(record.getRecordType())
                .title(record.getTitle())
                .description(record.getDescription())
                .treatmentPlan(record.getTreatmentPlan())
                .observations(record.getObservations())
                .icdCodes(record.getIcdCodes())
                .icdCodeList(icdCodeList(record.getIcdCodes()))
                .source(record.getSource() != null ? record.getSource() : MedicalRecord.SOURCE_MANUAL)
                .isDeleted(Boolean.TRUE.equals(record.getIsDeleted()))
                .deletedAt(record.getDeletedAt())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    /**
     * The stored codes as a typed list, tolerant of older rows: an object needs a textual {@code code}; a plain
     * string becomes a code without description; anything else is skipped. {@code verified} is computed now.
     */
    List<MedicalRecordResponse.IcdCode> icdCodeList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Stored ICD codes are not valid JSON ({})", e.getClass().getSimpleName());
            return List.of();
        }
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<MedicalRecordResponse.IcdCode> result = new ArrayList<>();
        for (JsonNode item : root) {
            String code;
            String description;
            if (item.isObject() && item.path("code").isTextual()) {
                code = item.get("code").asText();
                description = item.path("description").isTextual() ? item.get("description").asText() : "";
            } else if (item.isTextual()) {
                code = item.asText();
                description = "";
            } else {
                continue;
            }
            code = IcdCodeFormat.normalize(code);
            if (code == null || code.isEmpty()) {
                continue;
            }
            result.add(new MedicalRecordResponse.IcdCode(code, description,
                    terminologyProvider.validate(code) == CodeValidation.VERIFIED));
        }
        return result;
    }

    /** "Dr. First Last" per doctor id (one IN query); ids whose user row has no name are absent. */
    private Map<String, String> doctorNames(List<String> doctorSystemIds) {
        List<String> ids = doctorSystemIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (Object[] row : medicalRecordRepository.findUserNames(ids)) {
            String name = doctorDisplayName((String) row[1], (String) row[2]);
            if (name != null) {
                names.put((String) row[0], name);
            }
        }
        return names;
    }

    /** Same rule as the web's doctorDisplayName: no second "Dr." when the stored name already carries it. */
    static String doctorDisplayName(String firstName, String lastName) {
        String name = collapse((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName));
        if (name.isEmpty()) {
            return null;
        }
        return DOCTOR_TITLE.matcher(name).lookingAt() ? name : "Dr. " + name;
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Clinic wall-clock now, at the database's microsecond precision. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
