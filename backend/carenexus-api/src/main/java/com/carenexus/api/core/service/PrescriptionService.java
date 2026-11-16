package com.carenexus.api.core.service;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreatePrescriptionRequest;
import com.carenexus.api.core.dto.request.UpdatePrescriptionRequest;
import com.carenexus.api.core.dto.response.PrescriptionResponse;
import com.carenexus.api.core.model.Prescription;
import com.carenexus.api.core.repository.PrescriptionRepository;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Prescriptions. Every public method authorizes the current caller first and writes a clinical audit row after it
 * succeeds.
 *
 * <ul>
 *   <li>Issue: an active DOCTOR with a care relationship to the patient
 *       ({@link ConsultationService#requireTreatingDoctor}); the prescriber is always the caller and a new
 *       prescription is always ACTIVE. Publishes {@link PrescriptionIssuedEvent}.</li>
 *   <li>Edit (instructions, expiry date, refills only) and status changes: the prescribing doctor only.
 *       Cancel through DELETE: the prescribing doctor or an administrator. No row is ever deleted.</li>
 *   <li>Status machine: ACTIVE and ON_HOLD interchange; either can become COMPLETED, CANCELLED or EXPIRED, which
 *       are terminal (409). A change to the current status is a no-op. Every real change publishes
 *       {@link PrescriptionStatusChangedEvent}.</li>
 *   <li>Reads: {@link PatientPermission#MEDICATIONS} on the prescription's patient
 *       ({@link AuthorizationService#requirePatientAccess}); doctor-scoped lists require the doctor themself.</li>
 * </ul>
 * Lists are ordered {@code prescribed_date DESC, prescription_id DESC}. "Clinic today" is {@code LocalDate.now(clock)}
 * (the application clock runs in the clinic zone).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PrescriptionService {

    public static final String RESOURCE = "PRESCRIPTION";
    static final PatientPermission PERMISSION = PatientPermission.MEDICATIONS;

    /** Row cap of the deprecated array endpoints. */
    public static final int LEGACY_LIST_CAP = 200;
    public static final int MAX_QUERY_LENGTH = 100;

    /** Newest first; the primary key makes the order total so pages never overlap. */
    public static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("prescribedDate"), Sort.Order.desc("prescriptionId"));

    public static final String STATUS_MESSAGE = "must be one of ACTIVE, ON_HOLD, COMPLETED, CANCELLED, EXPIRED";

    private static final Pattern SYSTEM_ID = Pattern.compile(ValidationPatterns.SYSTEM_ID);
    private static final Pattern TIME = Pattern.compile(ValidationPatterns.TIME_HH_MM);
    /** A leading "Dr." / "Dr" title in a stored name, any case (same rule as the web's doctorDisplayName). */
    private static final Pattern DOCTOR_TITLE = Pattern.compile("^dr(\\.\\s*|\\s+|$)", Pattern.CASE_INSENSITIVE);

    private final PrescriptionRepository prescriptionRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ==================== ISSUE ====================

    /**
     * Issues a prescription (status ACTIVE) for the calling doctor. Body {@code doctorSystemId} and {@code status}
     * are ignored. Schedule times are de-duplicated, sorted and handed to the medication module through
     * {@link PrescriptionIssuedEvent}; they are not stored here.
     */
    public PrescriptionResponse createPrescription(CreatePrescriptionRequest request) {
        if (request == null) {
            throw new BadRequestException("Malformed request body");
        }
        LocalDate today = today();
        LocalDate prescribedDate = request.getPrescribedDate() != null ? request.getPrescribedDate() : today;
        if (prescribedDate.isAfter(today)) {
            throw invalid("prescribedDate", "must not be in the future");
        }
        if (request.getExpiryDate() != null && request.getExpiryDate().isBefore(prescribedDate)) {
            throw invalid("expiryDate", "must not be before prescribedDate");
        }
        String route = normalizeRoute(request.getRoute());
        List<String> scheduleTimes = normalizeScheduleTimes(request.getScheduleTimes());
        String patientSystemId = requireText(request.getPatientSystemId(), "patientSystemId");
        requireSystemIdFormat(patientSystemId, "patientSystemId");
        String medicationName = requireText(request.getMedicationName(), "medicationName");
        String dosage = requireText(request.getDosage(), "dosage");
        String frequency = requireText(request.getFrequency(), "frequency");
        int refills = request.getRefillsAllowed() != null ? request.getRefillsAllowed() : 0;
        requireRefills(refills);

        // The prescriber is always the calling doctor; request.doctorSystemId is ignored.
        String doctorSystemId = consultationService.requireTreatingDoctor(
                patientSystemId, request.getConsultationId(), PERMISSION);

        Prescription prescription = Prescription.builder()
                .patientSystemId(patientSystemId)
                .doctorSystemId(doctorSystemId)
                .consultationId(request.getConsultationId())
                .medicationName(medicationName)
                .genericName(trimToNull(request.getGenericName()))
                .dosage(dosage)
                .frequency(frequency)
                .route(route)
                .quantity(trimToNull(request.getQuantity()))
                .refillsAllowed(refills)
                .specialInstructions(trimToNull(request.getSpecialInstructions()))
                .status(Prescription.ACTIVE)
                .prescribedDate(prescribedDate)
                .expiryDate(request.getExpiryDate())
                .build();

        Prescription saved = prescriptionRepository.saveAndFlush(prescription);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getPrescriptionId(), saved.getPatientSystemId());
        eventPublisher.publishEvent(new PrescriptionIssuedEvent(
                saved.getPrescriptionId(), saved.getConsultationId(), saved.getPatientSystemId(),
                saved.getDoctorSystemId(), saved.getMedicationName(), saved.getFrequency(),
                saved.getPrescribedDate(), saved.getExpiryDate(), scheduleTimes));
        return toResponse(saved, today);
    }

    // ==================== READ ====================

    /** One prescription; read permission on its patient. */
    @Transactional(readOnly = true)
    public PrescriptionResponse getPrescriptionById(Integer prescriptionId) {
        Prescription prescription = load(prescriptionId);
        authorizationService.requirePatientAccess(prescription.getPatientSystemId(), PERMISSION, prescriptionId);
        auditService.logClinicalAccess("READ", RESOURCE, prescriptionId, prescription.getPatientSystemId());
        return toResponse(prescription, today());
    }

    /**
     * The canonical paged list ({@code GET /api/prescriptions}). Filters combine with AND; {@code status} matches the
     * effective status. Scope by role:
     * <ul>
     *   <li>PATIENT: own prescriptions ({@code patientSystemId} defaults to the caller; another patient is 403);</li>
     *   <li>CAREGIVER: {@code patientSystemId} is required, read permission on that patient;</li>
     *   <li>DOCTOR: with {@code doctorSystemId} it must be the caller (rows the caller wrote, optionally narrowed by
     *       patient); with only {@code patientSystemId} the care relationship is required and every prescriber's
     *       rows are returned; with neither, the caller's own rows;</li>
     *   <li>ADMIN: no restriction.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public PageResponse<PrescriptionResponse> listPrescriptions(String patientSystemId, String doctorSystemId, String status,
                                                                Integer consultationId, String q, int page, int size) {
        String patient = trimToNull(patientSystemId);
        String doctor = trimToNull(doctorSystemId);
        requireSystemIdFormat(patient, "patientSystemId");
        requireSystemIdFormat(doctor, "doctorSystemId");
        String statusFilter = normalizeStatusFilter(status);
        if (consultationId != null && consultationId <= 0) {
            throw invalid("consultationId", "must be a positive number");
        }
        String query = trimToNull(q);
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw invalid("q", "must be at most 100 characters");
        }

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
                    throw invalid("patientSystemId", "is required");
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
                // no restriction
            }
            default -> authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                    AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        }

        LocalDate today = today();
        Pageable pageable = PageParams.of(page, size, NEWEST_FIRST);
        Page<Prescription> rows = prescriptionRepository.findAll(
                filters(patient, doctor, statusFilter, consultationId, query, today), pageable);
        auditPage(rows.getContent(), patient);
        return PageResponse.of(rows, toResponses(rows.getContent(), today));
    }

    /**
     * Deprecated array: one patient's prescriptions, newest first, at most {@value #LEGACY_LIST_CAP}.
     * Read permission on the patient.
     */
    @Transactional(readOnly = true)
    public List<PrescriptionResponse> getPatientPrescriptions(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        List<Prescription> rows = prescriptionRepository.findByPatientSystemId(patientSystemId, legacyPage());
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows, today());
    }

    /**
     * Deprecated array: prescriptions this doctor wrote, newest first, at most {@value #LEGACY_LIST_CAP}.
     * The doctor themself or an administrator.
     */
    @Transactional(readOnly = true)
    public List<PrescriptionResponse> getDoctorPrescriptions(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Prescription> rows = prescriptionRepository.findByDoctorSystemId(doctorSystemId, legacyPage());
        auditService.logListRead(RESOURCE, rows, Prescription::getPatientSystemId);
        return toResponses(rows, today());
    }

    /**
     * Deprecated array behind {@code GET /api/doctors/prescriptions}: prescriptions the calling doctor wrote,
     * optionally filtered by effective {@code status} and {@code patientSystemId}, newest first, at most
     * {@value #LEGACY_LIST_CAP}. An administrator gets an empty list (an administrator writes no prescriptions).
     */
    @Transactional(readOnly = true)
    public List<PrescriptionResponse> getCallerDoctorPrescriptions(String status, String patientSystemId) {
        String patient = trimToNull(patientSystemId);
        requireSystemIdFormat(patient, "patientSystemId");
        String statusFilter = normalizeStatusFilter(status);
        if (authorizationService.isAdmin()) {
            return List.of();
        }
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String caller = authorizationService.currentSystemId();
        LocalDate today = today();
        List<Prescription> rows = prescriptionRepository.findAll(
                filters(patient, caller, statusFilter, null, null, today), legacyPage()).getContent();
        auditService.logListRead(RESOURCE, rows, Prescription::getPatientSystemId);
        return toResponses(rows, today);
    }

    // ==================== CHANGE ====================

    /**
     * Replaces the editable part (refills, expiry date, instructions) of a prescription that is still ACTIVE or
     * ON_HOLD. Prescribing doctor only. Publishes no event (a past expiry date is picked up by the expiry job).
     */
    public PrescriptionResponse updatePrescription(Integer prescriptionId, UpdatePrescriptionRequest request) {
        if (request == null) {
            throw new BadRequestException("Malformed request body");
        }
        Prescription prescription = loadForUpdate(prescriptionId);
        consultationService.requireAuthorDoctor(prescription.getDoctorSystemId(), prescription.getPatientSystemId(),
                RESOURCE, prescriptionId, false);
        requireChangeable(prescription);

        if (request.getRefillsAllowed() == null) {
            throw invalid("refillsAllowed", "is required");
        }
        requireRefills(request.getRefillsAllowed());
        if (request.getExpiryDate() != null && request.getExpiryDate().isBefore(prescription.getPrescribedDate())) {
            throw invalid("expiryDate", "must not be before prescribedDate");
        }

        prescription.setRefillsAllowed(request.getRefillsAllowed());
        prescription.setExpiryDate(request.getExpiryDate());
        prescription.setSpecialInstructions(trimToNull(request.getSpecialInstructions()));
        Prescription saved = prescriptionRepository.saveAndFlush(prescription);
        auditService.logClinicalAccess("UPDATE", RESOURCE, prescriptionId, saved.getPatientSystemId());
        return toResponse(saved, today());
    }

    /**
     * Moves a prescription to {@code status} (trimmed, case-insensitive). Prescribing doctor only. The same status
     * is a no-op (no audit row, no event); a terminal prescription answers 409.
     */
    public PrescriptionResponse updatePrescriptionStatus(Integer prescriptionId, String status) {
        String target = normalizeStatus(status);
        Prescription prescription = loadForUpdate(prescriptionId);
        consultationService.requireAuthorDoctor(prescription.getDoctorSystemId(), prescription.getPatientSystemId(),
                RESOURCE, prescriptionId, false);
        String previous = prescription.getStatus();
        if (transition(prescription, target)) {
            prescription = prescriptionRepository.saveAndFlush(prescription);
            auditService.logClinicalAccess("UPDATE", RESOURCE, prescriptionId, prescription.getPatientSystemId());
            publishStatusChange(prescription, previous);
        }
        return toResponse(prescription, today());
    }

    /**
     * Cancels a prescription (status CANCELLED); no row is ever deleted. Prescribing doctor or administrator.
     * An already cancelled prescription is a no-op; a completed or expired one answers 409.
     */
    public void deletePrescription(Integer prescriptionId) {
        Prescription prescription = loadForUpdate(prescriptionId);
        consultationService.requireAuthorDoctor(prescription.getDoctorSystemId(), prescription.getPatientSystemId(),
                RESOURCE, prescriptionId, true);
        String previous = prescription.getStatus();
        if (transition(prescription, Prescription.CANCELLED)) {
            prescriptionRepository.saveAndFlush(prescription);
            auditService.logClinicalAccess("DELETE", RESOURCE, prescriptionId, prescription.getPatientSystemId());
            publishStatusChange(prescription, previous);
        }
    }

    // ==================== RULES ====================

    /**
     * Applies the status machine. Returns false for a no-op (same status); throws 409 when the stored status is
     * terminal. From ACTIVE or ON_HOLD every other status is allowed.
     */
    static boolean transition(Prescription prescription, String target) {
        String current = prescription.getStatus();
        if (target.equals(current)) {
            return false;
        }
        requireChangeable(prescription);
        prescription.setStatus(target);
        return true;
    }

    private static void requireChangeable(Prescription prescription) {
        if (prescription.isTerminal()) {
            throw new ConflictException("Prescription is " + prescription.getStatus() + " and can no longer be changed");
        }
    }

    private void publishStatusChange(Prescription prescription, String previous) {
        eventPublisher.publishEvent(new PrescriptionStatusChangedEvent(prescription.getPrescriptionId(),
                prescription.getPatientSystemId(), prescription.getDoctorSystemId(), previous, prescription.getStatus()));
    }

    /** Status values accepted by the status endpoints: trimmed, upper-cased, one of the five. */
    static String normalizeStatus(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Prescription.STATUSES.contains(value)) {
            throw invalid("status", STATUS_MESSAGE);
        }
        return value;
    }

    /** A list filter: blank means none. */
    static String normalizeStatusFilter(String status) {
        return trimToNull(status) == null ? null : normalizeStatus(status);
    }

    private static String normalizeRoute(String route) {
        String value = trimToNull(route);
        if (value == null) {
            return Prescription.ROUTE_ORAL;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!Prescription.ROUTES.contains(value)) {
            throw invalid("route", CreatePrescriptionRequest.ROUTE_MESSAGE);
        }
        return value;
    }

    /** De-duplicated and sorted ascending ("HH:mm" sorts chronologically); never null. */
    static List<String> normalizeScheduleTimes(List<String> times) {
        if (times == null || times.isEmpty()) {
            return List.of();
        }
        if (times.size() > CreatePrescriptionRequest.MAX_SCHEDULE_TIMES) {
            throw invalid("scheduleTimes", "must contain at most 8 times");
        }
        for (int i = 0; i < times.size(); i++) {
            String time = times.get(i);
            if (time == null || !TIME.matcher(time).matches()) {
                throw invalid("scheduleTimes[" + i + "]", CreatePrescriptionRequest.TIME_MESSAGE);
            }
        }
        return List.copyOf(new TreeSet<>(times));
    }

    private static void requireRefills(int refills) {
        if (refills < 0 || refills > 99) {
            throw invalid("refillsAllowed", "must be between 0 and 99");
        }
    }

    private static String requireText(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw invalid(field, "is required");
        }
        return trimmed;
    }

    private static void requireSystemIdFormat(String systemId, String field) {
        if (systemId != null && !SYSTEM_ID.matcher(systemId).matches()) {
            throw invalid(field, "must be a valid system id");
        }
    }

    private static BadRequestException invalid(String field, String message) {
        return new BadRequestException(ErrorResponse.VALIDATION_FAILED, field + ": " + message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================== QUERIES ====================

    /**
     * Filters of the lists, combined with AND; null means "no filter". {@code status} matches the effective status:
     * ACTIVE / ON_HOLD need an expiry date that is null or not before today; EXPIRED is stored EXPIRED or a stored
     * ACTIVE / ON_HOLD past its expiry date; COMPLETED / CANCELLED are the stored status.
     */
    static Specification<Prescription> filters(String patientSystemId, String doctorSystemId, String status,
                                               Integer consultationId, String q, LocalDate today) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (patientSystemId != null) {
                predicates.add(cb.equal(root.get("patientSystemId"), patientSystemId));
            }
            if (doctorSystemId != null) {
                predicates.add(cb.equal(root.get("doctorSystemId"), doctorSystemId));
            }
            if (consultationId != null) {
                predicates.add(cb.equal(root.get("consultationId"), consultationId));
            }
            if (status != null) {
                Expression<String> stored = root.get("status");
                Expression<LocalDate> expiry = root.get("expiryDate");
                switch (status) {
                    case Prescription.ACTIVE, Prescription.ON_HOLD -> predicates.add(cb.and(
                            cb.equal(stored, status),
                            cb.or(cb.isNull(expiry), cb.greaterThanOrEqualTo(expiry, today))));
                    case Prescription.EXPIRED -> predicates.add(cb.or(
                            cb.equal(stored, Prescription.EXPIRED),
                            cb.and(stored.in(Prescription.ACTIVE, Prescription.ON_HOLD), cb.lessThan(expiry, today))));
                    default -> predicates.add(cb.equal(stored, status));
                }
            }
            if (q != null) {
                String pattern = "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("medicationName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("genericName")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** {@code %}, {@code _} and {@code \} match literally. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Pageable legacyPage() {
        return PageRequest.of(0, LEGACY_LIST_CAP, NEWEST_FIRST);
    }

    private void auditPage(List<Prescription> rows, String scopedPatient) {
        if (rows.isEmpty()) {
            if (scopedPatient != null) {
                auditService.logClinicalAccess("READ", RESOURCE, null, scopedPatient);
            }
            return;
        }
        auditService.logListRead(RESOURCE, rows, Prescription::getPatientSystemId);
    }

    private Prescription load(Integer prescriptionId) {
        if (prescriptionId == null) {
            throw new ResourceNotFoundException("Prescription not found");
        }
        return prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
    }

    /** Loads the row with a write lock, so a status change never races the expiry job. */
    private Prescription loadForUpdate(Integer prescriptionId) {
        if (prescriptionId == null) {
            throw new ResourceNotFoundException("Prescription not found");
        }
        return prescriptionRepository.findByIdForUpdate(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    // ==================== MAPPING ====================

    private List<PrescriptionResponse> toResponses(List<Prescription> rows, LocalDate today) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = doctorNames(rows.stream().map(Prescription::getDoctorSystemId).toList());
        return rows.stream().map(row -> map(row, names.get(row.getDoctorSystemId()), today)).toList();
    }

    private PrescriptionResponse toResponse(Prescription prescription, LocalDate today) {
        Map<String, String> names = doctorNames(List.of(prescription.getDoctorSystemId()));
        return map(prescription, names.get(prescription.getDoctorSystemId()), today);
    }

    /** Prescriber display names, one batched query. Accounts without a name are absent from the map. */
    private Map<String, String> doctorNames(Collection<String> systemIds) {
        List<String> ids = systemIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<String, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (Object[] row : prescriptionRepository.findAccountNames(ids)) {
            String name = doctorDisplayName((String) row[1], (String) row[2]);
            if (name != null) {
                names.put((String) row[0], name);
            }
        }
        return names;
    }

    /**
     * "Dr. First Last": first and last name joined with whitespace collapsed; "Dr. " is prefixed unless the name
     * already starts with a "Dr" title. Null when the account has no name.
     */
    static String doctorDisplayName(String firstName, String lastName) {
        String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName))
                .replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            return null;
        }
        return DOCTOR_TITLE.matcher(name).lookingAt() ? name : "Dr. " + name;
    }

    private static PrescriptionResponse map(Prescription prescription, String doctorName, LocalDate today) {
        return PrescriptionResponse.builder()
                .prescriptionId(prescription.getPrescriptionId())
                .patientSystemId(prescription.getPatientSystemId())
                .doctorSystemId(prescription.getDoctorSystemId())
                .doctorName(doctorName)
                .consultationId(prescription.getConsultationId())
                .medicationName(prescription.getMedicationName())
                .genericName(prescription.getGenericName())
                .dosage(prescription.getDosage())
                .frequency(prescription.getFrequency())
                .route(prescription.getRoute())
                .quantity(prescription.getQuantity())
                .refillsAllowed(prescription.getRefillsAllowed())
                .specialInstructions(prescription.getSpecialInstructions())
                .status(prescription.getStatus())
                .effectiveStatus(prescription.effectiveStatus(today))
                .prescribedDate(prescription.getPrescribedDate())
                .expiryDate(prescription.getExpiryDate())
                .createdAt(prescription.getCreatedAt())
                .build();
    }
}
