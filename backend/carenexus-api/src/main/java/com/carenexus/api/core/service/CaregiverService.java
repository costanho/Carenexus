package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.calls.service.CallInfoService;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.AdminPasswordResetRequestedEvent;
import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.common.event.CaregiverApprovalPendingEvent;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.common.event.CaregiverMessagingRegainedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateDependentRequest;
import com.carenexus.api.core.dto.request.GrantCaregiverAccessRequest;
import com.carenexus.api.core.dto.request.InviteDependentRequest;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentActivityPageResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentActivityResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.CaregiverRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.core.repository.PatientRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Caregiver (proxy) access to dependents.
 *
 * Who may change a dependent_access grant:
 * - the PATIENT it belongs to (or an ADMIN): any change, via the /api/patients/me/caregiver-access and the
 *   deprecated /api/patients/me/caregivers endpoints;
 * - the CAREGIVER holding it: may only NARROW an active grant (turn permissions off, lower the access level,
 *   bring the expiry earlier). Any widening of the EFFECTIVE permissions is rejected with 403.
 *
 * Data flags are normalised on every save (create, grant, both PATCH variants): FULL_ACCESS stores all ten data
 * flags true, VIEW_ONLY stores the three write flags false; EDIT_ONLY and CUSTOM store the flags as sent. Rows saved
 * before this rule may still hold other values, so permission checks always use PatientPermission.isGrantedBy.
 *
 * Communication consent (messaging the care team, joining video calls): can_message_care_team and can_join_calls
 * are only REQUESTS. They take effect when the patient approves them (approval stamp *_approved_at +
 * *_approved_by_system_id = patient). Only the patient who owns the grant approves, by setting the flag true on a
 * grant or PATCH. Admin changes never approve. Turning a flag off (anyone) clears its approval in the same save.
 * FULL_ACCESS never implies them. See PatientPermission.isGrantedBy.
 *
 * Managed dependents: a caregiver may create a dependent without email and password (with a guardian attestation).
 * That account cannot sign in until the caregiver who created it sends an invitation (the email is stored and the
 * account module mails a set-password link) and the patient sets a password.
 *
 * Events, all inside the changing transaction after the save:
 * - CaregiverGrantRecordedEvent: exactly one per request that changed the grant (consent ledger), kind GRANTED,
 *   PERMISSIONS_CHANGED, COMMUNICATION_APPROVED, EXTENDED, NARROWED or REVOKED (EXPIRED comes from the sweeper);
 * - CaregiverAccessChangedEvent with the effective values after every successful change and every DELETE;
 * - CaregiverMessagingRegainedEvent (messaging effective again after a gap) and CaregiverApprovalPendingEvent (the
 *   grant started / stopped waiting for the patient's approval);
 * - AdminPasswordResetRequestedEvent for the invitation of a managed dependent.
 *
 * The class default is read-only, so every write method carries its own @Transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaregiverService {

    /** Audit resource of a grant. */
    static final String RESOURCE = "DEPENDENT_ACCESS";
    /** Audit resource of the people in a grant list (names, contact and profile fields). */
    static final String PROFILE_RESOURCE = "PROFILE";
    static final String APPOINTMENT_RESOURCE = "APPOINTMENT";
    static final String CONSULTATION_RESOURCE = "CONSULTATION";
    static final String ACCOUNT_RESOURCE = "ACCOUNT";

    /** Hard cap of the grant lists and of each list of the legacy activity feed. */
    static final int LIST_CAP = 200;
    /** Default activity window: the last 90 days plus all future rows. */
    static final int DEFAULT_WINDOW_DAYS = 90;
    /** Upper bound used for an open window end (PostgreSQL timestamps go far beyond it). */
    private static final LocalDateTime OPEN_END = LocalDateTime.of(9999, 12, 31, 0, 0);
    /** Caregivers never see unfinished consultation notes. */
    static final List<String> CAREGIVER_CONSULTATION_STATUSES = List.of("COMPLETED", "CANCELLED");

    private static final String AUTH_CAREGIVER = "CAREGIVER";
    private static final String FULL_ACCESS = "FULL_ACCESS";
    private static final String VIEW_ONLY = "VIEW_ONLY";
    private static final Set<String> ACCESS_LEVELS = Set.of(FULL_ACCESS, VIEW_ONLY, "EDIT_ONLY", "CUSTOM");
    private static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "OTHER");
    private static final Set<String> BLOOD_TYPES = Set.of("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-");
    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile(com.carenexus.api.common.dto.ValidationPatterns.PHONE);

    static final String ONLY_REDUCE_MESSAGE =
            "You can only reduce your own access. Ask the patient to grant additional permissions.";
    static final String CANNOT_EXTEND_MESSAGE = "You cannot extend your own access. Ask the patient to extend it.";
    static final String NO_CAREGIVER_MESSAGE = "No caregiver account found for this email";
    static final String ALREADY_GRANTED_MESSAGE = "This caregiver already has access. Change their permissions instead.";
    static final String NOT_FOUND_MESSAGE = "Access record not found";
    static final String NOT_HOLDER_MESSAGE = "Not authorized to update this access record";
    static final String NOT_OWNER_MESSAGE = "Not authorized to change this access record";
    static final String INACTIVE_MESSAGE =
            "This access has expired or been revoked. Ask the patient to grant access again.";
    static final String REVOKED_MESSAGE = "This access has been revoked";
    static final String NOT_A_CAREGIVER_MESSAGE = "Only proxy/caregiver accounts can add a dependent";
    static final String NOT_A_PATIENT_MESSAGE = "Only patients can grant caregiver access";
    static final String CREDENTIALS_TOGETHER_MESSAGE = "Email and password must be provided together, or both left empty";
    static final String ATTESTATION_REQUIRED_MESSAGE = "Confirm that you are this person's parent or legal guardian";
    static final String NOT_CREATOR_MESSAGE = "Only the caregiver who created this account can send the invitation";
    static final String HAS_SIGN_IN_MESSAGE = "This person already has sign-in details";
    static final String EMAIL_TAKEN_MESSAGE = "Email already registered";
    static final String WINDOW_ORDER_MESSAGE = "The 'to' date must not be before the 'from' date";

    private final DependentAccessRepository dependentAccessRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final CaregiverRepository caregiverRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final CallInfoService callInfoService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ==================== CAREGIVER VIEW ====================

    /**
     * The caller's ACTIVE grants, oldest grant first, at most {@value #LIST_CAP}. Audited as one PROFILE list read
     * per dependent returned.
     */
    public List<CaregiverDependentResponse> getDependentsForCaregiver(String caregiverSystemId) {
        List<DependentAccess> grants =
                dependentAccessRepository.findActiveForCaregiver(caregiverSystemId, PageRequest.of(0, LIST_CAP));
        List<CaregiverDependentResponse> result = toResponses(grants, true);
        auditService.logListRead(PROFILE_RESOURCE, result, CaregiverDependentResponse::getPatientSystemId);
        return result;
    }

    /**
     * Legacy (parameterless) activity feed: the default window (last {@value #DEFAULT_WINDOW_DAYS} days plus all
     * future rows), at most {@value #LIST_CAP} rows per list, newest first.
     */
    public CaregiverDependentActivityResponse getActivityForDependents(String caregiverSystemId) {
        Activity activity = loadActivity(caregiverSystemId, defaultWindowStart(), null, PageRequest.of(0, LIST_CAP));
        return CaregiverDependentActivityResponse.builder()
                .appointments(activity.appointments().getContent())
                .consultations(activity.consultations().getContent())
                .build();
    }

    /**
     * Paged activity feed of the caller's dependents. Appointments come only from dependents whose ACTIVE grant gives
     * APPOINTMENTS, consultations only from those giving CONSULTATIONS; the others are silently left out.
     *
     * @param from window start (clinic date, inclusive); null = clinic today minus {@value #DEFAULT_WINDOW_DAYS} days
     * @param to   window end (clinic date, inclusive); null = open (all future rows)
     * @param page zero-based page (negative becomes 0); one page and size apply to both lists
     * @param size page size, clamped to 1..100 (default 20)
     */
    public CaregiverDependentActivityPageResponse getActivityPage(String caregiverSystemId, LocalDate from, LocalDate to,
                                                                  Integer page, Integer size) {
        LocalDate start = from != null ? from : defaultWindowStart();
        if (to != null && to.isBefore(start)) {
            throw new BadRequestException(WINDOW_ORDER_MESSAGE);
        }
        Activity activity = loadActivity(caregiverSystemId, start, to, PageParams.of(page, size, Sort.unsorted()));
        return CaregiverDependentActivityPageResponse.builder()
                .from(start)
                .to(to)
                .appointments(activity.appointments())
                .consultations(activity.consultations())
                .build();
    }

    /**
     * Caregiver-side edit of their own grant: only narrowing is allowed, and only while the grant is active.
     */
    @Transactional
    public CaregiverDependentResponse updateDependentAccess(String caregiverSystemId, Integer accessId,
                                                            UpdateDependentAccessRequest request) {
        DependentAccess access = loadForCaregiver(caregiverSystemId, accessId);

        // Communication permissions can only be turned on by the patient (and then need their approval)
        if (request != null && (turnsOn(request.getCanMessageCareTeam(), access.getCanMessageCareTeam())
                || turnsOn(request.getCanJoinCalls(), access.getCanJoinCalls()))) {
            throw new AuthorizationException(ONLY_REDUCE_MESSAGE, RESOURCE, accessId);
        }
        // A caregiver never turns on a stored data flag the patient did not grant (kept from the earlier rule that
        // web forms rely on): billing flags never, any other flag only while the level already gives it.
        if (turnsOnUngrantedDataFlag(request, access)) {
            throw new AuthorizationException(ONLY_REDUCE_MESSAGE, RESOURCE, accessId);
        }

        DependentAccess before = copyOf(access);
        // A date-only value for the current expiry day means "unchanged" (it would otherwise parse to 23:59:59)
        if (request != null && request.getExpiresAt() != null && before.getExpiresAt() != null
                && request.getExpiresAt().trim().equals(before.getExpiresAt().toLocalDate().toString())) {
            request.setExpiresAt(null);
        }
        applyChanges(access, request, false);
        if (request != null) {
            // No approver: a caregiver can only turn these off (which clears the approval)
            applyCommunicationChanges(access, request.getCanMessageCareTeam(), request.getCanJoinCalls(), null);
        }
        normalizeFlags(access);

        // The rule is on EFFECTIVE permissions: nothing may be granted after the change that was not granted before.
        for (PatientPermission permission : PatientPermission.values()) {
            if (permission.isGrantedBy(access) && !permission.isGrantedBy(before)) {
                throw new AuthorizationException(ONLY_REDUCE_MESSAGE, RESOURCE, accessId);
            }
        }
        if (before.getExpiresAt() != null
                && (access.getExpiresAt() == null || access.getExpiresAt().isAfter(before.getExpiresAt()))) {
            throw new AuthorizationException(CANNOT_EXTEND_MESSAGE, RESOURCE, accessId);
        }

        DependentAccess saved = dependentAccessRepository.save(access);
        auditService.logClinicalAccess("UPDATE", RESOURCE, accessId, saved.getPatientSystemId());
        if (!sameGrantState(before, saved)) {
            recordGrant(saved, caregiverSystemId, CaregiverGrantRecordedEvent.NARROWED);
        }
        publishChange(before, saved);
        return toResponse(saved, true);
    }

    /**
     * Atomically create a dependent: users row (PATIENT, active) + patients row + dependent_access grant for the
     * calling caregiver, authorised by the caregiver. Any failure rolls all three back.
     *
     * With email and password the patient can sign in at once. Without both the account is managed (cannot sign in)
     * and the caregiver must attest to being the parent or legal guardian. A phone another account already uses is
     * stored as the patient's contact phone instead of the account phone, so a shared family number is accepted.
     */
    @Transactional
    public CaregiverDependentResponse createDependent(String caregiverSystemId, CreateDependentRequest request) {
        if (request == null) {
            throw new BadRequestException("Dependent details are required");
        }
        if (caregiverRepository.findBySystemId(caregiverSystemId).isEmpty()) {
            throw new AuthorizationException(NOT_A_CAREGIVER_MESSAGE, RESOURCE, null);
        }

        // Validate everything before creating anything
        String email = blankToNull(request.getEmail());
        String password = request.getPassword() == null || request.getPassword().isBlank() ? null : request.getPassword();
        if ((email == null) != (password == null)) {
            throw new BadRequestException(CREDENTIALS_TOGETHER_MESSAGE);
        }
        boolean managed = email == null;
        boolean attested = Boolean.TRUE.equals(request.getGuardianAttested());
        if (managed && !attested) {
            throw new BadRequestException(ATTESTATION_REQUIRED_MESSAGE);
        }
        String phone = blankToNull(request.getPhone());
        if (phone != null && (phone.length() > 20 || !PHONE_PATTERN.matcher(phone).matches())) {
            throw new BadRequestException("Enter a valid phone number");
        }
        LocalDate dateOfBirth = parseDateOfBirth(request.getDateOfBirth());
        String gender = optionalEnum(request.getGender(), GENDERS, "Gender must be MALE, FEMALE or OTHER");
        String bloodType = optionalEnum(request.getBloodType(), BLOOD_TYPES, "Invalid blood type");
        String relationship = requireRelationship(request.getRelationship());
        String accessLevel = parseAccessLevel(request.getAccessLevel());
        LocalDateTime expiresAt = parseFutureExpiry(request.getExpiresAt());
        String allergies = toJsonArray(request.getAllergies());
        String chronicConditions = toJsonArray(request.getChronicConditions());

        // A number another account already uses (a family phone) becomes the patient's contact phone
        boolean phoneShared = phone != null && userRepository.findByPhone(phone).isPresent();
        User user = authService.createActiveUserAccount(request.getFirstName(), request.getLastName(),
                email, password, phoneShared ? null : phone, "PATIENT", false, false);

        patientRepository.save(Patient.builder()
                .systemId(user.getSystemId())
                .dateOfBirth(dateOfBirth)
                .gender(gender)
                .bloodType(bloodType)
                // healthStatus in the request is ignored: only a related doctor or an admin sets it
                .healthStatus("STABLE")
                .allergies(allergies)
                .chronicConditions(chronicConditions)
                .contactPhone(phoneShared ? phone : null)
                .build());

        DependentAccess access = DependentAccess.builder()
                .patientSystemId(user.getSystemId())
                .caregiverSystemId(caregiverSystemId)
                // The caregiver creates the dependent's account and is the one authorizing this grant
                .authorizedBySystemId(caregiverSystemId)
                .relationship(relationship)
                .accessLevel(accessLevel)
                .canViewRecords(Boolean.TRUE.equals(request.getCanViewRecords()))
                .canViewAppointments(Boolean.TRUE.equals(request.getCanViewAppointments()))
                .canViewMedications(Boolean.TRUE.equals(request.getCanViewMedications()))
                .canViewLabResults(Boolean.TRUE.equals(request.getCanViewLabResults()))
                .canViewImaging(Boolean.TRUE.equals(request.getCanViewImaging()))
                .canViewConsultations(Boolean.TRUE.equals(request.getCanViewConsultations()))
                .canScheduleAppointments(Boolean.TRUE.equals(request.getCanScheduleAppointments()))
                .canManageCarePlan(Boolean.TRUE.equals(request.getCanManageCarePlan()))
                .canViewBilling(Boolean.TRUE.equals(request.getCanViewBilling()))
                .canManageBilling(Boolean.TRUE.equals(request.getCanManageBilling()))
                // Communication permissions are stored as REQUESTS only (no approval stamp): the patient must
                // approve them. FULL_ACCESS does not set them.
                .canMessageCareTeam(Boolean.TRUE.equals(request.getCanMessageCareTeam()))
                .canJoinCalls(Boolean.TRUE.equals(request.getCanJoinCalls()))
                .guardianAttestedAt(attested ? Instant.now(clockOrDefault()) : null)
                .expiresAt(expiresAt)
                .isActive(true)
                .build();
        normalizeFlags(access);
        DependentAccess saved = dependentAccessRepository.save(access);

        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getAccessId(), saved.getPatientSystemId());
        recordGrant(saved, caregiverSystemId, CaregiverGrantRecordedEvent.GRANTED);
        publishChange(null, saved);
        return toResponse(saved, true);
    }

    /**
     * Invite a managed dependent to sign in: stores the email on the patient's account and publishes
     * AdminPasswordResetRequestedEvent so the account module mails a set-password link. Repeatable (re-sends the link,
     * may correct the email). Only the caregiver who created the dependent, while the grant is active and the account
     * still has no password.
     */
    @Transactional
    public CaregiverDependentResponse inviteDependent(String caregiverSystemId, Integer accessId,
                                                      InviteDependentRequest request) {
        String email = request == null ? null : blankToNull(request.getEmail());
        if (email == null) {
            throw new BadRequestException("Email is required");
        }
        email = email.toLowerCase(Locale.ROOT);

        DependentAccess access = loadForCaregiver(caregiverSystemId, accessId);
        if (!caregiverSystemId.equals(access.getAuthorizedBySystemId())) {
            throw new AuthorizationException(NOT_CREATOR_MESSAGE, RESOURCE, accessId);
        }
        User patient = userRepository.findBySystemId(access.getPatientSystemId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (patient.getPasswordHash() != null) {
            throw new ConflictException(HAS_SIGN_IN_MESSAGE);
        }
        String patientSystemId = patient.getSystemId();
        if (userRepository.findFirstByEmailIgnoreCaseOrderByUserIdAsc(email)
                .filter(other -> !patientSystemId.equals(other.getSystemId()))
                .isPresent()) {
            throw new ConflictException(EMAIL_TAKEN_MESSAGE);
        }

        // The account module's listener reads the stored address inside this transaction: save it first.
        patient.setEmail(email);
        userRepository.saveAndFlush(patient);
        eventPublisher.publishEvent(new AdminPasswordResetRequestedEvent(patientSystemId, caregiverSystemId));
        auditService.logAccountEvent(caregiverSystemId, "DEPENDENT_INVITE", ACCOUNT_RESOURCE, patientSystemId, "SUCCESS");
        return toResponse(access, true);
    }

    // ==================== PATIENT VIEW (grant management) ====================

    /**
     * Deprecated list (GET /api/patients/me/caregivers): the patient's ACTIVE grants only, newest first, at most
     * {@value #LIST_CAP}. Same rows and audit as {@link #getCaregiverAccessForPatient} minus expired grants.
     */
    public List<CaregiverDependentResponse> getCaregiversForPatient(String patientSystemId) {
        return getCaregiverAccessForPatient(patientSystemId).stream()
                .filter(grant -> "ACTIVE".equals(grant.getAccessStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Every grant of this patient that has not been revoked (active and expired), newest first, at most
     * {@value #LIST_CAP}. Audited as a PROFILE list read.
     */
    public List<CaregiverDependentResponse> getCaregiverAccessForPatient(String patientSystemId) {
        List<DependentAccess> grants =
                dependentAccessRepository.findUnrevokedForPatient(patientSystemId, PageRequest.of(0, LIST_CAP));
        List<CaregiverDependentResponse> result = toResponses(grants, false);
        auditService.logListRead(PROFILE_RESOURCE, result, CaregiverDependentResponse::getPatientSystemId);
        return result;
    }

    /**
     * The patient grants an existing caregiver account access. Communication flags set true here are approved by
     * the patient in the same save. A previously revoked grant for the same caregiver is re-used (UNIQUE
     * caregiver/patient) with fresh values (approvals and guardian attestation cleared); an unrevoked one gives 409.
     *
     * Frozen signature: be-caregiver-requests-consent calls it when the patient approves an access request.
     */
    @Transactional
    public CaregiverDependentResponse grantCaregiverAccessAsPatient(String patientSystemId,
                                                                    GrantCaregiverAccessRequest request) {
        if (request == null) {
            throw new BadRequestException("Caregiver details are required");
        }
        String email = request.getCaregiverEmail() == null ? "" : request.getCaregiverEmail().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            throw new BadRequestException("Caregiver email is required");
        }
        String relationship = requireRelationship(request.getRelationship());
        String accessLevel = parseAccessLevel(request.getAccessLevel());
        LocalDateTime expiresAt = parseFutureExpiry(request.getExpiresAt());

        if (patientRepository.findBySystemId(patientSystemId).isEmpty()) {
            throw new AuthorizationException(NOT_A_PATIENT_MESSAGE, RESOURCE, null);
        }
        User caregiver = userRepository.findFirstByEmailIgnoreCaseOrderByUserIdAsc(email)
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                .filter(user -> AUTH_CAREGIVER.equalsIgnoreCase(user.getRole()))
                .filter(user -> caregiverRepository.findBySystemId(user.getSystemId()).isPresent())
                .orElseThrow(() -> new ResourceNotFoundException(NO_CAREGIVER_MESSAGE));

        Optional<DependentAccess> existing =
                dependentAccessRepository.findByCaregiverSystemIdAndPatientSystemId(caregiver.getSystemId(), patientSystemId);
        if (existing.isPresent() && existing.get().getRevokedAt() == null
                && Boolean.TRUE.equals(existing.get().getIsActive())) {
            throw new ConflictException(ALREADY_GRANTED_MESSAGE);
        }

        DependentAccess before = existing.map(CaregiverService::copyOf).orElse(null);
        DependentAccess access = existing.orElseGet(DependentAccess::new);
        boolean reused = access.getAccessId() != null;
        access.setPatientSystemId(patientSystemId);
        access.setCaregiverSystemId(caregiver.getSystemId());
        access.setAuthorizedBySystemId(patientSystemId);
        access.setRelationship(relationship);
        access.setAccessLevel(accessLevel);
        access.setCanViewRecords(Boolean.TRUE.equals(request.getCanViewRecords()));
        access.setCanViewAppointments(Boolean.TRUE.equals(request.getCanViewAppointments()));
        access.setCanViewMedications(Boolean.TRUE.equals(request.getCanViewMedications()));
        access.setCanViewLabResults(Boolean.TRUE.equals(request.getCanViewLabResults()));
        access.setCanViewImaging(Boolean.TRUE.equals(request.getCanViewImaging()));
        access.setCanViewConsultations(Boolean.TRUE.equals(request.getCanViewConsultations()));
        access.setCanScheduleAppointments(Boolean.TRUE.equals(request.getCanScheduleAppointments()));
        access.setCanManageCarePlan(Boolean.TRUE.equals(request.getCanManageCarePlan()));
        access.setCanViewBilling(Boolean.TRUE.equals(request.getCanViewBilling()));
        access.setCanManageBilling(Boolean.TRUE.equals(request.getCanManageBilling()));
        normalizeFlags(access);
        // Fresh grant: no approval or attestation carried over from a revoked row
        access.setCanMessageCareTeam(false);
        access.setCanJoinCalls(false);
        clearMessagingApproval(access);
        clearCallsApproval(access);
        access.setGuardianAttestedAt(null);
        applyCommunicationChanges(access, Boolean.TRUE.equals(request.getCanMessageCareTeam()),
                Boolean.TRUE.equals(request.getCanJoinCalls()), patientSystemId);
        access.setExpiresAt(expiresAt);
        access.setRevokedAt(null);
        access.setIsActive(true);
        if (reused) {
            // @PrePersist only runs on insert
            access.setAuthorizedDate(LocalDateTime.now());
        }

        DependentAccess saved = dependentAccessRepository.save(access);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getAccessId(), saved.getPatientSystemId());
        recordGrant(saved, patientSystemId, CaregiverGrantRecordedEvent.GRANTED);
        publishChange(before, saved);
        return toResponse(saved, false);
    }

    /**
     * Patient (or admin) changes a caregiver's grant: permissions, access level and expiry (empty string removes
     * the expiry). Revoked grants cannot be edited.
     *
     * Setting canMessageCareTeam / canJoinCalls to true approves it when the caller is the patient (also when the
     * flag was already requested but not approved). Admin changes never approve.
     *
     * An expired grant can be extended here. Extending (or re-approving) never brings back the caregiver's old
     * conversation memberships: see CaregiverMessagingRegainedEvent.
     */
    @Transactional
    public CaregiverDependentResponse updateCaregiverAccessAsPatient(String callerSystemId, boolean callerIsAdmin,
                                                                     Integer accessId, UpdateDependentAccessRequest request) {
        DependentAccess access = loadForPatient(callerSystemId, callerIsAdmin, accessId);
        if (!Boolean.TRUE.equals(access.getIsActive()) || access.getRevokedAt() != null) {
            throw new ConflictException(REVOKED_MESSAGE);
        }
        DependentAccess before = copyOf(access);
        applyChanges(access, request, true);
        if (request != null) {
            boolean callerIsPatient = !callerIsAdmin && access.getPatientSystemId().equals(callerSystemId);
            applyCommunicationChanges(access, request.getCanMessageCareTeam(), request.getCanJoinCalls(),
                    callerIsPatient ? access.getPatientSystemId() : null);
        }
        normalizeFlags(access);
        DependentAccess saved = dependentAccessRepository.save(access);
        auditService.logClinicalAccess("UPDATE", RESOURCE, accessId, saved.getPatientSystemId());
        String kind = patientChangeKind(before, saved);
        if (kind != null) {
            recordGrant(saved, callerSystemId, kind);
        }
        publishChange(before, saved);
        return toResponse(saved, false);
    }

    /**
     * Patient (or admin) revokes a caregiver's grant. Idempotent: CaregiverAccessChangedEvent is published every
     * time so conversations and calls are cleaned up even if an earlier attempt failed; the audit row and
     * CaregiverGrantRecordedEvent(REVOKED) only for the request that revoked it.
     *
     * Frozen signature: later waves revoke with (actorSystemId, true, accessId).
     */
    @Transactional
    public void revokeCaregiverAccessAsPatient(String callerSystemId, boolean callerIsAdmin, Integer accessId) {
        DependentAccess access = loadForPatient(callerSystemId, callerIsAdmin, accessId);
        DependentAccess before = copyOf(access);
        DependentAccess saved = access;
        if (access.getRevokedAt() == null || Boolean.TRUE.equals(access.getIsActive())) {
            access.setIsActive(false);
            access.setRevokedAt(LocalDateTime.now());
            clearMessagingApproval(access);
            clearCallsApproval(access);
            saved = dependentAccessRepository.save(access);
            auditService.logClinicalAccess("DELETE", RESOURCE, accessId, access.getPatientSystemId());
            recordGrant(saved, callerSystemId, CaregiverGrantRecordedEvent.REVOKED);
        }
        publishChange(before, saved);
    }

    // ==================== ACTIVITY FEED ====================

    private record Activity(PageResponse<AppointmentResponse> appointments,
                            PageResponse<ConsultationResponse> consultations) {
    }

    private LocalDate defaultWindowStart() {
        return LocalDate.now(clockOrDefault()).minusDays(DEFAULT_WINDOW_DAYS);
    }

    private Clock clockOrDefault() {
        return clock != null ? clock : Clock.systemDefaultZone();
    }

    /** Window [from 00:00, to + 1 day 00:00) in clinic wall-clock time; filter, order and page in the database. */
    private Activity loadActivity(String caregiverSystemId, LocalDate from, LocalDate to, Pageable pageable) {
        List<DependentAccess> grants =
                dependentAccessRepository.findActiveForCaregiver(caregiverSystemId, PageRequest.of(0, LIST_CAP));
        List<String> appointmentPatients = patientsGranted(grants, PatientPermission.APPOINTMENTS);
        List<String> consultationPatients = patientsGranted(grants, PatientPermission.CONSULTATIONS);

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime endExclusive = to == null ? OPEN_END : to.plusDays(1).atStartOfDay();
        Page<Appointment> appointments = appointmentPatients.isEmpty() ? Page.empty(pageable)
                : dependentAccessRepository.findDependentAppointments(appointmentPatients, start, endExclusive, pageable);
        Page<Consultation> consultations = consultationPatients.isEmpty() ? Page.empty(pageable)
                : dependentAccessRepository.findDependentConsultations(consultationPatients,
                        CAREGIVER_CONSULTATION_STATUSES, start, endExclusive, pageable);

        Set<String> patientIds = new LinkedHashSet<>(appointmentPatients);
        patientIds.addAll(consultationPatients);
        Map<String, String> names = patientIds.isEmpty() ? Map.of()
                : dependentAccessRepository.findUsersBySystemIdIn(patientIds).stream()
                        .collect(Collectors.toMap(User::getSystemId, CaregiverService::fullName, (a, b) -> a));

        auditService.logListRead(APPOINTMENT_RESOURCE, appointments.getContent(), Appointment::getPatientSystemId);
        auditService.logListRead(CONSULTATION_RESOURCE, consultations.getContent(), Consultation::getPatientSystemId);

        return new Activity(
                PageResponse.of(appointments, toAppointmentResponses(appointments.getContent(), names)),
                PageResponse.of(consultations, toConsultationResponses(consultations.getContent(), names)));
    }

    private static List<String> patientsGranted(List<DependentAccess> grants, PatientPermission permission) {
        return grants.stream()
                .filter(CaregiverService::isCurrentlyActive)
                .filter(permission::isGrantedBy)
                .map(DependentAccess::getPatientSystemId)
                .distinct()
                .collect(Collectors.toList());
    }

    /** One batch lookup of the call fields for the page (no per-row queries). */
    private List<AppointmentResponse> toAppointmentResponses(List<Appointment> rows, Map<String, String> names) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Integer, CallInfoService.CallInfo> calls = callInfoService.describeAppointments(rows);
        List<AppointmentResponse> result = new ArrayList<>(rows.size());
        for (Appointment a : rows) {
            CallInfoService.CallInfo call = calls.getOrDefault(a.getAppointmentId(), CallInfoService.CallInfo.NONE);
            result.add(AppointmentResponse.builder()
                    .appointmentId(a.getAppointmentId())
                    .patientSystemId(a.getPatientSystemId())
                    .patientName(names.getOrDefault(a.getPatientSystemId(), a.getPatientSystemId()))
                    .doctorSystemId(a.getDoctorSystemId())
                    .facilityId(a.getFacilityId())
                    .scheduledAt(a.getScheduledAt())
                    .durationMinutes(a.getDurationMinutes())
                    .type(a.getType())
                    .status(a.getStatus())
                    .reasonForVisit(a.getReasonForVisit())
                    // Legacy meeting links are never returned.
                    .videoConsultationLink(null)
                    .reminderSent(a.getReminderSent())
                    .notes(a.getNotes())
                    .createdAt(a.getCreatedAt())
                    .updatedAt(a.getUpdatedAt())
                    .callStatus(call.callStatus())
                    .callJoinRoute(call.callJoinRoute())
                    .joinWindowOpensAt(call.joinWindowOpensAt())
                    .joinWindowClosesAt(call.joinWindowClosesAt())
                    .build());
        }
        return result;
    }

    /** One batch lookup of the appointment types for the page (no per-row queries). */
    private List<ConsultationResponse> toConsultationResponses(List<Consultation> rows, Map<String, String> names) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Integer> appointmentIds = rows.stream().map(Consultation::getAppointmentId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Integer, String> types = new HashMap<>();
        if (!appointmentIds.isEmpty()) {
            dependentAccessRepository.findAppointmentsByIdIn(appointmentIds)
                    .forEach(a -> types.put(a.getAppointmentId(), a.getType()));
        }
        return rows.stream().map(c -> ConsultationResponse.builder()
                        .consultationId(c.getConsultationId())
                        .appointmentId(c.getAppointmentId())
                        .appointmentType(types.get(c.getAppointmentId()))
                        .patientSystemId(c.getPatientSystemId())
                        .patientName(names.getOrDefault(c.getPatientSystemId(), c.getPatientSystemId()))
                        .doctorSystemId(c.getDoctorSystemId())
                        .chiefComplaint(c.getChiefComplaint())
                        .historyOfPresentIllness(c.getHistoryOfPresentIllness())
                        .physicalExamination(c.getPhysicalExamination())
                        .clinicalNotes(c.getClinicalNotes())
                        .diagnosis(c.getDiagnosis())
                        .treatmentPlan(c.getTreatmentPlan())
                        .followUpInstructions(c.getFollowUpInstructions())
                        .followUpRequired(c.getFollowUpRequired())
                        .followUpDate(c.getFollowUpDate())
                        .durationMinutes(c.getDurationMinutes())
                        .status(c.getStatus())
                        .startedAt(c.getStartedAt())
                        .completedAt(c.getCompletedAt())
                        .createdAt(c.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    // ==================== HELPERS ====================

    /** 404, then 403 for another caregiver's grant, then 403 for an expired or revoked grant. */
    private DependentAccess loadForCaregiver(String caregiverSystemId, Integer accessId) {
        DependentAccess access = dependentAccessRepository.findById(accessId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        if (!access.getCaregiverSystemId().equals(caregiverSystemId)) {
            throw new AuthorizationException(NOT_HOLDER_MESSAGE, RESOURCE, accessId);
        }
        if (!isCurrentlyActive(access)) {
            throw new AuthorizationException(INACTIVE_MESSAGE, RESOURCE, accessId);
        }
        return access;
    }

    private DependentAccess loadForPatient(String callerSystemId, boolean callerIsAdmin, Integer accessId) {
        DependentAccess access = dependentAccessRepository.findById(accessId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        if (!callerIsAdmin && !access.getPatientSystemId().equals(callerSystemId)) {
            throw new AuthorizationException(NOT_OWNER_MESSAGE, RESOURCE, accessId);
        }
        return access;
    }

    private static boolean turnsOn(Boolean requested, Boolean current) {
        return Boolean.TRUE.equals(requested) && !Boolean.TRUE.equals(current);
    }

    /**
     * Caregiver-side rule for the stored data flags (checked against the grant as it is before the change):
     * <ul>
     *   <li>billing flags can never be turned on by the caregiver, whatever the access level;</li>
     *   <li>any other flag can be turned on only when the caregiver already holds the matching permission (through
     *       FULL_ACCESS), which is what happens when a FULL_ACCESS grant is narrowed to a lower level that keeps
     *       some of the flags.</li>
     * </ul>
     */
    private static boolean turnsOnUngrantedDataFlag(UpdateDependentAccessRequest request, DependentAccess access) {
        if (request == null) {
            return false;
        }
        if (turnsOn(request.getCanViewBilling(), access.getCanViewBilling())
                || turnsOn(request.getCanManageBilling(), access.getCanManageBilling())) {
            return true;
        }
        return turnsOnWithout(PatientPermission.RECORDS, request.getCanViewRecords(), access.getCanViewRecords(), access)
                || turnsOnWithout(PatientPermission.APPOINTMENTS, request.getCanViewAppointments(), access.getCanViewAppointments(), access)
                || turnsOnWithout(PatientPermission.MEDICATIONS, request.getCanViewMedications(), access.getCanViewMedications(), access)
                || turnsOnWithout(PatientPermission.LAB_RESULTS, request.getCanViewLabResults(), access.getCanViewLabResults(), access)
                || turnsOnWithout(PatientPermission.IMAGING, request.getCanViewImaging(), access.getCanViewImaging(), access)
                || turnsOnWithout(PatientPermission.CONSULTATIONS, request.getCanViewConsultations(), access.getCanViewConsultations(), access)
                || turnsOnWithout(PatientPermission.MANAGE_APPOINTMENTS, request.getCanScheduleAppointments(), access.getCanScheduleAppointments(), access)
                || turnsOnWithout(PatientPermission.MANAGE_CARE_PLAN, request.getCanManageCarePlan(), access.getCanManageCarePlan(), access);
    }

    /** The request turns a stored flag on while the grant does not currently give the matching permission. */
    private static boolean turnsOnWithout(PatientPermission permission, Boolean requested, Boolean current,
                                          DependentAccess access) {
        return turnsOn(requested, current) && !permission.isGrantedBy(access);
    }

    /**
     * The stored data flags follow the access level: FULL_ACCESS stores all ten data flags true, VIEW_ONLY stores
     * the three write flags false. EDIT_ONLY and CUSTOM keep the flags as set. Communication flags are untouched.
     */
    static void normalizeFlags(DependentAccess access) {
        if (FULL_ACCESS.equals(access.getAccessLevel())) {
            access.setCanViewRecords(true);
            access.setCanViewAppointments(true);
            access.setCanViewMedications(true);
            access.setCanViewLabResults(true);
            access.setCanViewImaging(true);
            access.setCanViewConsultations(true);
            access.setCanScheduleAppointments(true);
            access.setCanManageCarePlan(true);
            access.setCanViewBilling(true);
            access.setCanManageBilling(true);
        } else if (VIEW_ONLY.equals(access.getAccessLevel())) {
            access.setCanScheduleAppointments(false);
            access.setCanManageCarePlan(false);
            access.setCanManageBilling(false);
        }
    }

    /**
     * Sets the communication flags (null = unchanged). {@code approverSystemId} is the patient when the patient is
     * the caller, else null: with an approver, a flag set true is stamped approved (if not approved yet). In every
     * case a flag that ends up false loses its approval, keeping the V15 CHECK constraints satisfied.
     */
    static void applyCommunicationChanges(DependentAccess access, Boolean messaging, Boolean calls,
                                          String approverSystemId) {
        boolean approve = approverSystemId != null && approverSystemId.equals(access.getPatientSystemId());
        if (messaging != null) {
            access.setCanMessageCareTeam(messaging);
            if (messaging && approve && !PatientPermission.MESSAGING.isGrantedBy(access)) {
                access.setMessagingApprovedAt(Instant.now());
                access.setMessagingApprovedBySystemId(approverSystemId);
            }
        }
        if (calls != null) {
            access.setCanJoinCalls(calls);
            if (calls && approve && !PatientPermission.JOIN_CALLS.isGrantedBy(access)) {
                access.setCallsApprovedAt(Instant.now());
                access.setCallsApprovedBySystemId(approverSystemId);
            }
        }
        if (!PatientPermission.MESSAGING.isGrantedBy(access)) {
            clearMessagingApproval(access);
        }
        if (!PatientPermission.JOIN_CALLS.isGrantedBy(access)) {
            clearCallsApproval(access);
        }
    }

    private static void clearMessagingApproval(DependentAccess access) {
        access.setMessagingApprovedAt(null);
        access.setMessagingApprovedBySystemId(null);
    }

    private static void clearCallsApproval(DependentAccess access) {
        access.setCallsApprovedAt(null);
        access.setCallsApprovedBySystemId(null);
    }

    /** One consent-ledger event for a request that changed the grant. */
    private void recordGrant(DependentAccess saved, String actorSystemId, String kind) {
        eventPublisher.publishEvent(new CaregiverGrantRecordedEvent(saved.getAccessId(), saved.getPatientSystemId(),
                saved.getCaregiverSystemId(), actorSystemId, kind, saved.getGuardianAttestedAt() != null));
    }

    /**
     * Kind of a patient or admin change, first rule that matches: a communication flag became approved, the expiry
     * moved later or was removed, anything else changed. Null when nothing changed.
     */
    static String patientChangeKind(DependentAccess before, DependentAccess after) {
        boolean approved = (!PatientPermission.MESSAGING.isGrantedBy(before) && PatientPermission.MESSAGING.isGrantedBy(after))
                || (!PatientPermission.JOIN_CALLS.isGrantedBy(before) && PatientPermission.JOIN_CALLS.isGrantedBy(after));
        if (approved) {
            return CaregiverGrantRecordedEvent.COMMUNICATION_APPROVED;
        }
        if (before.getExpiresAt() != null
                && (after.getExpiresAt() == null || after.getExpiresAt().isAfter(before.getExpiresAt()))) {
            return CaregiverGrantRecordedEvent.EXTENDED;
        }
        return sameGrantState(before, after) ? null : CaregiverGrantRecordedEvent.PERMISSIONS_CHANGED;
    }

    /**
     * Nothing that matters changed: same level, same expiry, same communication requests and approvals, and the same
     * EFFECTIVE data permissions. A stored flag that only follows the normalisation of an old row (for example the
     * data flags of a FULL_ACCESS grant saved before flags were normalised) is not a change of the grant.
     */
    static boolean sameGrantState(DependentAccess a, DependentAccess b) {
        if (!Objects.equals(a.getAccessLevel(), b.getAccessLevel())
                || !Objects.equals(a.getExpiresAt(), b.getExpiresAt())
                || !Objects.equals(a.getCanMessageCareTeam(), b.getCanMessageCareTeam())
                || !Objects.equals(a.getCanJoinCalls(), b.getCanJoinCalls())
                || !Objects.equals(a.getMessagingApprovedAt(), b.getMessagingApprovedAt())
                || !Objects.equals(a.getCallsApprovedAt(), b.getCallsApprovedAt())) {
            return false;
        }
        for (PatientPermission permission : PatientPermission.values()) {
            if (permission.isGrantedBy(a) != permission.isGrantedBy(b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param before the grant as it was before this change (copyOf), or null for a new row
     */
    private void publishChange(DependentAccess before, DependentAccess access) {
        boolean active = isCurrentlyActive(access);
        eventPublisher.publishEvent(new CaregiverAccessChangedEvent(
                access.getAccessId(),
                access.getPatientSystemId(),
                access.getCaregiverSystemId(),
                !active,
                active && PatientPermission.MESSAGING.isGrantedBy(access),
                active && PatientPermission.JOIN_CALLS.isGrantedBy(access)));

        if (before != null && !messagingEffective(before) && messagingEffective(access)) {
            eventPublisher.publishEvent(new CaregiverMessagingRegainedEvent(
                    access.getAccessId(), access.getPatientSystemId(), access.getCaregiverSystemId(), isExpired(before)));
        }

        boolean wasPending = before != null && isApprovalPending(before);
        boolean pending = isApprovalPending(access);
        if (wasPending != pending) {
            eventPublisher.publishEvent(new CaregiverApprovalPendingEvent(
                    access.getAccessId(), access.getPatientSystemId(), access.getCaregiverSystemId(), pending,
                    pending && Boolean.TRUE.equals(access.getCanMessageCareTeam()) && !PatientPermission.MESSAGING.isGrantedBy(access),
                    pending && Boolean.TRUE.equals(access.getCanJoinCalls()) && !PatientPermission.JOIN_CALLS.isGrantedBy(access)));
        }
    }

    private static boolean messagingEffective(DependentAccess access) {
        return isCurrentlyActive(access) && PatientPermission.MESSAGING.isGrantedBy(access);
    }

    /** Not revoked, but past its expiry. */
    private static boolean isExpired(DependentAccess access) {
        return Boolean.TRUE.equals(access.getIsActive()) && access.getRevokedAt() == null
                && access.getExpiresAt() != null && !access.getExpiresAt().isAfter(LocalDateTime.now());
    }

    /** Active grant with a communication flag requested but not (yet) approved by the patient. */
    static boolean isApprovalPending(DependentAccess access) {
        return isCurrentlyActive(access)
                && ((Boolean.TRUE.equals(access.getCanMessageCareTeam()) && !PatientPermission.MESSAGING.isGrantedBy(access))
                    || (Boolean.TRUE.equals(access.getCanJoinCalls()) && !PatientPermission.JOIN_CALLS.isGrantedBy(access)));
    }

    private static String accessStatus(DependentAccess access) {
        if (access.getRevokedAt() != null || !Boolean.TRUE.equals(access.getIsActive())) {
            return "REVOKED";
        }
        if (access.getExpiresAt() != null && !access.getExpiresAt().isAfter(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    private static String requireRelationship(String raw) {
        String relationship = raw == null ? "" : raw.trim();
        if (relationship.isEmpty()) {
            throw new BadRequestException("Relationship is required");
        }
        if (relationship.length() > 80) {
            throw new BadRequestException("Relationship must be at most 80 characters");
        }
        return relationship;
    }

    private static String parseAccessLevel(String raw) {
        String accessLevel = raw == null || raw.isBlank() ? VIEW_ONLY : raw.trim().toUpperCase(Locale.ROOT);
        if (!ACCESS_LEVELS.contains(accessLevel)) {
            throw new BadRequestException(CreateDependentRequest.ACCESS_LEVEL_MESSAGE);
        }
        return accessLevel;
    }

    private static LocalDateTime parseFutureExpiry(String raw) {
        LocalDateTime expiresAt = parseExpiry(raw);
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw new BadRequestException("Access expiry must be in the future");
        }
        return expiresAt;
    }

    private static boolean isCurrentlyActive(DependentAccess access) {
        return Boolean.TRUE.equals(access.getIsActive())
                && access.getRevokedAt() == null
                && (access.getExpiresAt() == null || access.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private void applyChanges(DependentAccess access, UpdateDependentAccessRequest request, boolean allowClearExpiry) {
        if (request == null) {
            return;
        }
        if (request.getAccessLevel() != null && !request.getAccessLevel().isBlank()) {
            String level = request.getAccessLevel().trim().toUpperCase(Locale.ROOT);
            if (!ACCESS_LEVELS.contains(level)) {
                throw new BadRequestException(CreateDependentRequest.ACCESS_LEVEL_MESSAGE);
            }
            access.setAccessLevel(level);
        }
        if (request.getExpiresAt() != null) {
            if (request.getExpiresAt().isBlank()) {
                if (!allowClearExpiry) {
                    throw new AuthorizationException(CANNOT_EXTEND_MESSAGE, RESOURCE, access.getAccessId());
                }
                access.setExpiresAt(null);
            } else {
                access.setExpiresAt(parseExpiry(request.getExpiresAt()));
            }
        }
        if (request.getCanViewRecords() != null)          access.setCanViewRecords(request.getCanViewRecords());
        if (request.getCanViewAppointments() != null)     access.setCanViewAppointments(request.getCanViewAppointments());
        if (request.getCanViewMedications() != null)      access.setCanViewMedications(request.getCanViewMedications());
        if (request.getCanViewLabResults() != null)       access.setCanViewLabResults(request.getCanViewLabResults());
        if (request.getCanViewImaging() != null)          access.setCanViewImaging(request.getCanViewImaging());
        // canMessageCareTeam / canJoinCalls: see applyCommunicationChanges (they carry the approval rule)
        if (request.getCanViewConsultations() != null)    access.setCanViewConsultations(request.getCanViewConsultations());
        if (request.getCanScheduleAppointments() != null) access.setCanScheduleAppointments(request.getCanScheduleAppointments());
        if (request.getCanManageCarePlan() != null)       access.setCanManageCarePlan(request.getCanManageCarePlan());
        if (request.getCanViewBilling() != null)          access.setCanViewBilling(request.getCanViewBilling());
        if (request.getCanManageBilling() != null)        access.setCanManageBilling(request.getCanManageBilling());
    }

    private static DependentAccess copyOf(DependentAccess a) {
        return DependentAccess.builder()
                .accessId(a.getAccessId())
                .patientSystemId(a.getPatientSystemId())
                .caregiverSystemId(a.getCaregiverSystemId())
                .authorizedBySystemId(a.getAuthorizedBySystemId())
                .relationship(a.getRelationship())
                .accessLevel(a.getAccessLevel())
                .canViewRecords(a.getCanViewRecords())
                .canViewAppointments(a.getCanViewAppointments())
                .canViewMedications(a.getCanViewMedications())
                .canViewLabResults(a.getCanViewLabResults())
                .canViewImaging(a.getCanViewImaging())
                .canMessageCareTeam(a.getCanMessageCareTeam())
                .canViewConsultations(a.getCanViewConsultations())
                .canScheduleAppointments(a.getCanScheduleAppointments())
                .canManageCarePlan(a.getCanManageCarePlan())
                .canViewBilling(a.getCanViewBilling())
                .canManageBilling(a.getCanManageBilling())
                .canJoinCalls(a.getCanJoinCalls())
                .messagingApprovedAt(a.getMessagingApprovedAt())
                .messagingApprovedBySystemId(a.getMessagingApprovedBySystemId())
                .callsApprovedAt(a.getCallsApprovedAt())
                .callsApprovedBySystemId(a.getCallsApprovedBySystemId())
                .guardianAttestedAt(a.getGuardianAttestedAt())
                .authorizedDate(a.getAuthorizedDate())
                .expiresAt(a.getExpiresAt())
                .revokedAt(a.getRevokedAt())
                .expiryProcessedAt(a.getExpiryProcessedAt())
                .isActive(a.getIsActive())
                .build();
    }

    /**
     * "YYYY-MM-DD" (end of that day), ISO local date-time, or ISO date-time with offset (converted to clinic time;
     * the JVM default zone is the clinic zone). Blank -> null.
     */
    static LocalDateTime parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // try the other formats
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // try a plain date
        }
        try {
            return LocalDate.parse(value).atTime(23, 59, 59);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Access expiry must be a date in the format YYYY-MM-DD");
        }
    }

    private static LocalDate parseDateOfBirth(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Date of birth must be in the format YYYY-MM-DD");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new BadRequestException("Date of birth cannot be in the future");
        }
        if (date.isBefore(LocalDate.now().minusYears(130))) {
            throw new BadRequestException("Please enter a valid date of birth");
        }
        return date;
    }

    private static String optionalEnum(String raw, Set<String> allowed, String message) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new BadRequestException(message);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toJsonArray(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> cleaned = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize list", e);
        }
    }

    private static String fullName(User user) {
        return user.getFirstName() + " " + user.getLastName();
    }

    private CaregiverDependentResponse toResponse(DependentAccess access, boolean caregiverView) {
        return toResponses(List.of(access), caregiverView).get(0);
    }

    /**
     * Maps grants with one users query and one patients query for the whole list (no per-row lookups).
     *
     * @param caregiverView true when the caller is the caregiver: health fields (date of birth, blood type,
     *                      health status) are only included when the grant allows viewing records, the caregiver's
     *                      own name and email are left out, and canInvite is computed.
     */
    private List<CaregiverDependentResponse> toResponses(List<DependentAccess> grants, boolean caregiverView) {
        if (grants.isEmpty()) {
            return List.of();
        }
        Set<String> patientIds = grants.stream().map(DependentAccess::getPatientSystemId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> userIds = new LinkedHashSet<>(patientIds);
        if (!caregiverView) {
            grants.forEach(g -> userIds.add(g.getCaregiverSystemId()));
        }
        Map<String, User> users = byKey(dependentAccessRepository.findUsersBySystemIdIn(userIds), User::getSystemId);
        Map<String, Patient> patients = byKey(patientRepository.findBySystemIdIn(patientIds), Patient::getSystemId);
        return grants.stream()
                .map(g -> toResponse(g, caregiverView, users.get(g.getPatientSystemId()),
                        caregiverView ? null : users.get(g.getCaregiverSystemId()), patients.get(g.getPatientSystemId())))
                .collect(Collectors.toList());
    }

    private static <T> Map<String, T> byKey(Collection<T> rows, Function<T, String> key) {
        Map<String, T> map = new HashMap<>();
        if (rows != null) {
            rows.stream().filter(Objects::nonNull).forEach(row -> map.putIfAbsent(key.apply(row), row));
        }
        return map;
    }

    private CaregiverDependentResponse toResponse(DependentAccess access, boolean caregiverView, User patientUser,
                                                  User caregiverUser, Patient patient) {
        boolean active = isCurrentlyActive(access);
        boolean managed = patientUser != null && patientUser.getPasswordHash() == null;
        CaregiverDependentResponse.CaregiverDependentResponseBuilder builder = CaregiverDependentResponse.builder()
                .accessId(access.getAccessId())
                .caregiverSystemId(access.getCaregiverSystemId())
                .patientSystemId(access.getPatientSystemId())
                .relationship(access.getRelationship())
                .accessLevel(access.getAccessLevel())
                .canViewRecords(access.getCanViewRecords())
                .canViewAppointments(access.getCanViewAppointments())
                .canViewMedications(access.getCanViewMedications())
                .canViewLabResults(access.getCanViewLabResults())
                .canViewImaging(access.getCanViewImaging())
                .canMessageCareTeam(access.getCanMessageCareTeam())
                .canViewConsultations(access.getCanViewConsultations())
                .canScheduleAppointments(access.getCanScheduleAppointments())
                .canManageCarePlan(access.getCanManageCarePlan())
                .canViewBilling(access.getCanViewBilling())
                .canManageBilling(access.getCanManageBilling())
                .authorizedBySystemId(access.getAuthorizedBySystemId())
                .authorizedDate(access.getAuthorizedDate())
                .expiresAt(access.getExpiresAt())
                .isActive(access.getIsActive())
                .revokedAt(access.getRevokedAt())
                .canJoinCalls(access.getCanJoinCalls())
                .messagingApprovedAt(access.getMessagingApprovedAt())
                .callsApprovedAt(access.getCallsApprovedAt())
                .messagingAllowed(active && PatientPermission.MESSAGING.isGrantedBy(access))
                .joinCallsAllowed(active && PatientPermission.JOIN_CALLS.isGrantedBy(access))
                .pendingApproval(isApprovalPending(access))
                .accessStatus(accessStatus(access))
                .guardianAttestedAt(access.getGuardianAttestedAt())
                .managed(managed)
                .canInvite(caregiverView && managed && active
                        && access.getCaregiverSystemId().equals(access.getAuthorizedBySystemId()));

        if (patientUser != null) {
            builder.patientName(fullName(patientUser))
                   .patientEmail(patientUser.getEmail())
                   .patientPhone(patientUser.getPhone());
        }
        if (caregiverUser != null) {
            builder.caregiverName(fullName(caregiverUser))
                   .caregiverEmail(caregiverUser.getEmail());
        }

        boolean includeHealth = !caregiverView || PatientPermission.RECORDS.isGrantedBy(access);
        if (patient != null) {
            builder.patientGender(patient.getGender());
            if (patientUser == null || patientUser.getPhone() == null) {
                builder.patientPhone(patient.getContactPhone());
            }
            if (includeHealth) {
                builder.patientHealthStatus(patient.getHealthStatus())
                       .patientDateOfBirth(patient.getDateOfBirth())
                       .patientBloodType(patient.getBloodType());
            }
        }
        return builder.build();
    }
}
