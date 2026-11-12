package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateEmergencyContactRequest;
import com.carenexus.api.core.dto.request.UpdateEmergencyContactRequest;
import com.carenexus.api.core.dto.request.UpdateUserProfileRequest;
import com.carenexus.api.core.dto.request.UpdateUserSettingsRequest;
import com.carenexus.api.core.dto.response.EmergencyContactResponse;
import com.carenexus.api.core.dto.response.UserProfileResponse;
import com.carenexus.api.core.dto.response.UserSettingsResponse;
import com.carenexus.api.core.model.Caregiver;
import com.carenexus.api.core.model.EmergencyContact;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.CaregiverRepository;
import com.carenexus.api.core.repository.EmergencyContactRepository;
import com.carenexus.api.core.repository.PatientRepository;
import com.carenexus.api.core.repository.UserSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Profiles, emergency contacts and notification settings (/api/profile).
 *
 * Every public method authorizes the caller first (identity from the JWT through {@link AuthorizationService}) and
 * audits the successful operation itself:
 * <ul>
 *   <li>profile of a PATIENT (read or update, by anyone, the patient included): clinical row READ / UPDATE PROFILE;</li>
 *   <li>update of a non-patient profile: account event PROFILE_UPDATE / ACCOUNT;</li>
 *   <li>emergency contacts: clinical rows CREATE / READ / UPDATE / DELETE EMERGENCY_CONTACT;</li>
 *   <li>settings: account events SETTINGS_READ / SETTINGS_UPDATE on USER_SETTINGS.</li>
 * </ul>
 * Denials are audited by {@link AuthorizationService}.
 *
 * Field rules: healthStatus is set by the patient's doctor elsewhere and email changes only through the
 * email-change flow, so a non-blank value of either is refused (400) for every caller. A caregiver editing a
 * dependent needs MANAGE_PROFILE, and additionally RECORDS when the body carries bloodType, allergies or
 * chronicConditions (otherwise the whole request is refused with 403). Callers without RECORDS see the health
 * fields of a patient's profile as null.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserProfileService {

    /** Audit resource type of profile reads and updates of a patient. */
    public static final String RESOURCE = "PROFILE";
    public static final String EMERGENCY_CONTACT_RESOURCE = "EMERGENCY_CONTACT";
    public static final String SETTINGS_RESOURCE = "USER_SETTINGS";
    public static final String ACCOUNT_RESOURCE = "ACCOUNT";

    /** Idempotency scope of POST /api/profile/emergency-contacts. */
    public static final String EMERGENCY_CONTACT_SCOPE = "POST /api/profile/emergency-contacts";

    static final int MAX_LIST_ENTRIES = 50;
    static final int MAX_LIST_ENTRY_LENGTH = 100;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final CaregiverRepository caregiverRepository;
    private final EmergencyContactRepository emergencyContactRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** The two list fields of a patient profile and their error texts. */
    private enum ListField {
        ALLERGIES("Allergies must be a list", "Each allergy must be 1 to 100 characters",
                "Allergies can have at most 50 entries"),
        CHRONIC_CONDITIONS("Chronic conditions must be a list", "Each chronic condition must be 1 to 100 characters",
                "Chronic conditions can have at most 50 entries");

        final String notAList;
        final String badEntry;
        final String tooMany;

        ListField(String notAList, String badEntry, String tooMany) {
            this.notAList = notAList;
            this.badEntry = badEntry;
            this.tooMany = tooMany;
        }
    }

    /** The account a profile call is about, and whether its health fields must be hidden from the caller. */
    private record ProfileTarget(User user, boolean redactHealth) {
    }

    // ==================== LOOKUPS ====================

    /**
     * Uppercase role of the user (PATIENT, DOCTOR, CAREGIVER, ADMIN). 404 if the user does not exist.
     */
    @Transactional(readOnly = true)
    public String getUserRole(String systemId) {
        return findUser(systemId).getRole();
    }

    /**
     * Patient that owns this emergency contact. 404 if the contact does not exist.
     */
    @Transactional(readOnly = true)
    public String getEmergencyContactPatientSystemId(Integer contactId) {
        return findContact(contactId).getPatientSystemId();
    }

    // ==================== PROFILE ====================

    /**
     * GET /api/profile: the caller's own profile, never redacted. For a CAREGIVER it carries the caregiver's
     * relationship and falls back to the caregiver record's phone.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile() {
        return getUserProfile(authorizationService.currentSystemId());
    }

    /**
     * GET /api/profile/user/{systemId}: own profile or any account for an ADMIN; another account only when it is a
     * PATIENT the caller may access (related doctor, caregiver with an active grant). Health fields are hidden from
     * callers without RECORDS on the patient.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(String systemId) {
        ProfileTarget target = authorizeProfileRead(systemId);
        UserProfileResponse response = buildProfile(target.user());
        if (target.redactHealth()) {
            redactHealthFields(response);
        }
        if (isPatient(target.user())) {
            auditService.logClinicalAccess("READ", RESOURCE, null, target.user().getSystemId());
        }
        return response;
    }

    /**
     * PUT /api/profile: partial update of the caller's own profile.
     */
    public UserProfileResponse updateMyProfile(UpdateUserProfileRequest request) {
        return updateUserProfile(authorizationService.currentSystemId(), request);
    }

    /**
     * PUT /api/profile/user/{systemId}: partial update by the account owner, an ADMIN, or a CAREGIVER whose active
     * grant for the PATIENT gives MANAGE_PROFILE (plus RECORDS for blood type, allergies and chronic conditions).
     * One transaction: a request that fails changes nothing.
     */
    public UserProfileResponse updateUserProfile(String systemId, UpdateUserProfileRequest request) {
        String callerSystemId = authorizationService.currentSystemId();
        boolean caregiverEdit = false;
        User user;
        if (callerSystemId.equals(systemId) || authorizationService.isAdmin()) {
            user = findUser(systemId);
        } else {
            // Someone else's account: only a caregiver editing a dependent patient.
            authorizationService.requireRole(AuthorizationService.CAREGIVER);
            user = findUser(systemId);
            if (!isPatient(user)) {
                authorizationService.requireSelfOrAdmin(systemId);
            }
            authorizationService.requirePatientAccess(systemId, PatientPermission.MANAGE_PROFILE);
            caregiverEdit = true;
        }

        rejectReadOnlyFields(request);
        if (caregiverEdit && touchesHealthFields(request)) {
            authorizationService.requirePatientAccess(systemId, PatientPermission.RECORDS);
        }
        String allergies = request.getAllergies() == null ? null
                : normalizeList(request.getAllergies(), ListField.ALLERGIES);
        String chronicConditions = request.getChronicConditions() == null ? null
                : normalizeList(request.getChronicConditions(), ListField.CHRONIC_CONDITIONS);

        String phone = request.getPhone();
        if (phone != null && !phone.equals(user.getPhone())) {
            userRepository.findByPhone(phone)
                    .filter(other -> !other.getSystemId().equals(user.getSystemId()))
                    .ifPresent(other -> {
                        throw new ConflictException("Phone number already registered");
                    });
        }

        setIfPresent(request.getFirstName(), user::setFirstName);
        setIfPresent(request.getLastName(), user::setLastName);
        setIfPresent(phone, user::setPhone);
        userRepository.saveAndFlush(user);

        if (isPatient(user)) {
            patientRepository.findBySystemId(user.getSystemId()).ifPresent(patient -> {
                setIfPresent(request.getDateOfBirth(), patient::setDateOfBirth);
                if (request.getGender() != null) {
                    patient.setGender(emptyToNull(request.getGender().toUpperCase(Locale.ROOT)));
                }
                if (request.getBloodType() != null) {
                    patient.setBloodType(emptyToNull(request.getBloodType().toUpperCase(Locale.ROOT)));
                }
                setIfPresent(allergies, patient::setAllergies);
                setIfPresent(chronicConditions, patient::setChronicConditions);
                patientRepository.saveAndFlush(patient);
            });
            auditService.logClinicalAccess("UPDATE", RESOURCE, null, user.getSystemId());
        } else {
            auditService.logAccountEvent(callerSystemId, "PROFILE_UPDATE", ACCOUNT_RESOURCE, user.getSystemId(), "SUCCESS");
        }

        UserProfileResponse response = buildProfile(user);
        if (caregiverEdit && !authorizationService.canAccessPatient(user.getSystemId(), PatientPermission.RECORDS)) {
            redactHealthFields(response);
        }
        return response;
    }

    // ==================== EMERGENCY CONTACTS ====================

    /**
     * POST /api/profile/emergency-contacts without an Idempotency-Key.
     */
    public EmergencyContactResponse createEmergencyContact(CreateEmergencyContactRequest request) {
        return createEmergencyContact(request, null).body();
    }

    /**
     * POST /api/profile/emergency-contacts: a PATIENT adds to their own contacts (patientSystemId defaults to the
     * caller), a CAREGIVER with MANAGE_PROFILE or an ADMIN names the patient. Authorization runs before the
     * Idempotency-Key is looked at; a replay returns the stored body and writes nothing.
     */
    public IdempotentResult<EmergencyContactResponse> createEmergencyContact(CreateEmergencyContactRequest request,
                                                                             String idempotencyKey) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.ADMIN);
        String patientSystemId = request.getPatientSystemId();
        if (patientSystemId == null && AuthorizationService.PATIENT.equals(authorizationService.currentRole())) {
            patientSystemId = authorizationService.currentSystemId();
        }
        if (patientSystemId == null || patientSystemId.isBlank()) {
            throw new BadRequestException("Patient System ID is required");
        }
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_PROFILE);

        String patient = patientSystemId;
        return idempotencyService.execute(EMERGENCY_CONTACT_SCOPE, idempotencyKey, EmergencyContactResponse.class,
                () -> insertEmergencyContact(patient, request));
    }

    /**
     * GET /api/profile/emergency-contacts: the signed-in PATIENT's own contacts.
     */
    @Transactional(readOnly = true)
    public List<EmergencyContactResponse> getMyEmergencyContacts() {
        String callerSystemId = authorizationService.currentSystemId();
        if (!AuthorizationService.PATIENT.equals(authorizationService.currentRole())) {
            throw new BadRequestException("Only patients can have emergency contacts");
        }
        List<EmergencyContactResponse> contacts = listContacts(callerSystemId);
        auditService.logClinicalAccess("READ", EMERGENCY_CONTACT_RESOURCE, null, callerSystemId);
        return contacts;
    }

    /**
     * GET /api/profile/emergency-contacts/patient/{patientSystemId}: needs PROFILE on the patient. An ADMIN asking
     * about an id that is not a patient gets an empty list.
     */
    @Transactional(readOnly = true)
    public List<EmergencyContactResponse> getPatientEmergencyContacts(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.PROFILE);
        if (patientRepository.findBySystemId(patientSystemId).isEmpty()) {
            return List.of();
        }
        List<EmergencyContactResponse> contacts = listContacts(patientSystemId);
        auditService.logClinicalAccess("READ", EMERGENCY_CONTACT_RESOURCE, null, patientSystemId);
        return contacts;
    }

    /**
     * GET /api/profile/emergency-contacts/{contactId}: needs PROFILE on the contact's patient.
     */
    @Transactional(readOnly = true)
    public EmergencyContactResponse getEmergencyContactById(Integer contactId) {
        EmergencyContact contact = findContact(contactId);
        authorizationService.requirePatientAccess(contact.getPatientSystemId(), PatientPermission.PROFILE, contactId);
        auditService.logClinicalAccess("READ", EMERGENCY_CONTACT_RESOURCE, contactId, contact.getPatientSystemId());
        return toResponse(contact);
    }

    /**
     * PUT /api/profile/emergency-contacts/{contactId}: partial update; PATIENT, CAREGIVER or ADMIN with
     * MANAGE_PROFILE on the contact's patient. isPrimary = true demotes the previous primary contact in the same
     * transaction.
     */
    public EmergencyContactResponse updateEmergencyContact(Integer contactId, UpdateEmergencyContactRequest request) {
        EmergencyContact contact = findContact(contactId);
        String patientSystemId = contact.getPatientSystemId();
        requireEmergencyContactWrite(patientSystemId, contactId);
        emergencyContactRepository.lockPatient(patientSystemId);

        setIfPresent(request.getName(), contact::setName);
        if (request.getRelationship() != null) {
            contact.setRelationship(emptyToNull(request.getRelationship()));
        }
        setIfPresent(request.getPhone(), contact::setPhone);
        if (request.getEmail() != null) {
            contact.setEmail(emptyToNull(request.getEmail()));
        }
        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            emergencyContactRepository.demoteOtherPrimary(patientSystemId, contactId);
            contact.setIsPrimary(true);
        } else if (Boolean.FALSE.equals(request.getIsPrimary())) {
            contact.setIsPrimary(false);
        }
        setIfPresent(request.getIsCaregiver(), contact::setIsCaregiver);

        EmergencyContact saved = emergencyContactRepository.saveAndFlush(contact);
        auditService.logClinicalAccess("UPDATE", EMERGENCY_CONTACT_RESOURCE, contactId, patientSystemId);
        return toResponse(saved);
    }

    /**
     * DELETE /api/profile/emergency-contacts/{contactId}: PATIENT, CAREGIVER or ADMIN with MANAGE_PROFILE on the
     * contact's patient. Deleting the primary contact promotes nobody.
     */
    public void deleteEmergencyContact(Integer contactId) {
        EmergencyContact contact = findContact(contactId);
        String patientSystemId = contact.getPatientSystemId();
        requireEmergencyContactWrite(patientSystemId, contactId);
        emergencyContactRepository.delete(contact);
        emergencyContactRepository.flush();
        auditService.logClinicalAccess("DELETE", EMERGENCY_CONTACT_RESOURCE, contactId, patientSystemId);
    }

    // ==================== SETTINGS ====================

    /**
     * GET /api/profile/settings: the caller's settings (defaults when never saved; the row is created then).
     */
    public UserSettingsResponse getMySettings() {
        return getUserSettings(authorizationService.currentSystemId());
    }

    /**
     * GET /api/profile/settings/user/{systemId}: the account owner or an ADMIN.
     */
    public UserSettingsResponse getUserSettings(String systemId) {
        authorizeSettings(systemId);
        UserSettings settings = loadOrCreateSettings(systemId);
        auditService.logAccountEvent(authorizationService.currentSystemId(), "SETTINGS_READ", SETTINGS_RESOURCE,
                systemId, "SUCCESS");
        return toResponse(settings);
    }

    /**
     * PATCH /api/profile/settings (and its deprecated aliases): partial update of the caller's settings.
     */
    public UserSettingsResponse updateMySettings(UpdateUserSettingsRequest request) {
        return updateUserSettings(authorizationService.currentSystemId(), request);
    }

    /**
     * PUT /api/profile/settings/user/{systemId}: partial update by the account owner or an ADMIN. The time zone must
     * be a known zone id ("" stores null: clinic time); with quiet hours on, the resulting start and end must both
     * be set and differ. A failed request saves nothing.
     */
    public UserSettingsResponse updateUserSettings(String systemId, UpdateUserSettingsRequest request) {
        authorizeSettings(systemId);
        UserSettings settings = loadOrCreateSettings(systemId);

        String timezone = settings.getTimezone();
        if (request.getTimezone() != null) {
            timezone = resolveTimezone(request.getTimezone());
        }
        LocalTime quietStart = request.getQuietHoursStart() != null
                ? LocalTime.parse(request.getQuietHoursStart(), HH_MM) : settings.getQuietHoursStart();
        LocalTime quietEnd = request.getQuietHoursEnd() != null
                ? LocalTime.parse(request.getQuietHoursEnd(), HH_MM) : settings.getQuietHoursEnd();
        boolean quietEnabled = request.getQuietHoursEnabled() != null
                ? request.getQuietHoursEnabled() : Boolean.TRUE.equals(settings.getQuietHoursEnabled());
        if (quietEnabled) {
            if (quietStart == null || quietEnd == null) {
                throw new BadRequestException("Set a start and an end time to turn on quiet hours");
            }
            if (quietStart.equals(quietEnd)) {
                throw new BadRequestException("Quiet hours start and end must be different");
            }
        }

        setIfPresent(request.getEmailNotifications(), settings::setEmailNotifications);
        setIfPresent(request.getSmsNotifications(), settings::setSmsNotifications);
        setIfPresent(request.getPushNotifications(), settings::setPushNotifications);
        setIfPresent(request.getAppointmentReminders(), settings::setAppointmentReminders);
        setIfPresent(request.getAppointmentReminder1h(), settings::setAppointmentReminder1h);
        setIfPresent(request.getAppointmentChangeAlerts(), settings::setAppointmentChangeAlerts);
        setIfPresent(request.getPrescriptionReminders(), settings::setPrescriptionReminders);
        setIfPresent(request.getMedicationReminders(), settings::setMedicationReminders);
        setIfPresent(request.getLabResultAlerts(), settings::setLabResultAlerts);
        setIfPresent(request.getImagingResultAlerts(), settings::setImagingResultAlerts);
        setIfPresent(request.getConsultationNotifications(), settings::setConsultationNotifications);
        setIfPresent(request.getVitalsAlerts(), settings::setVitalsAlerts);
        setIfPresent(request.getCriticalAlerts(), settings::setCriticalAlerts);
        setIfPresent(request.getReferralAlerts(), settings::setReferralAlerts);
        setIfPresent(request.getCarePlanUpdateAlerts(), settings::setCarePlanUpdateAlerts);
        setIfPresent(request.getAccessChangeAlerts(), settings::setAccessChangeAlerts);
        setIfPresent(request.getLoginAlerts(), settings::setLoginAlerts);
        setIfPresent(request.getBillingNotifications(), settings::setBillingNotifications);
        setIfPresent(request.getVideoCallAlerts(), settings::setVideoCallAlerts);
        setIfPresent(request.getMessageNotifications(), settings::setMessageNotifications);
        settings.setQuietHoursEnabled(quietEnabled);
        settings.setQuietHoursStart(quietStart);
        settings.setQuietHoursEnd(quietEnd);
        settings.setTimezone(timezone);

        UserSettings saved = userSettingsRepository.saveAndFlush(settings);
        auditService.logAccountEvent(authorizationService.currentSystemId(), "SETTINGS_UPDATE", SETTINGS_RESOURCE,
                systemId, "SUCCESS");
        return toResponse(saved);
    }

    // ==================== AUTHORIZATION HELPERS ====================

    /**
     * Read rules of GET /api/profile/user/{systemId}: owner and ADMIN see everything; anyone else only a PATIENT's
     * profile, with PROFILE access, and without the health fields unless they also hold RECORDS.
     */
    private ProfileTarget authorizeProfileRead(String systemId) {
        String callerSystemId = authorizationService.currentSystemId();
        if (callerSystemId.equals(systemId) || authorizationService.isAdmin()) {
            return new ProfileTarget(findUser(systemId), false);
        }
        User user = findUser(systemId);
        if (!isPatient(user)) {
            authorizationService.requireSelfOrAdmin(systemId);
        }
        authorizationService.requirePatientAccess(systemId, PatientPermission.PROFILE);
        boolean redact = !authorizationService.canAccessPatient(systemId, PatientPermission.RECORDS);
        return new ProfileTarget(user, redact);
    }

    /**
     * Writes to a patient's emergency contacts: PATIENT (own), CAREGIVER with MANAGE_PROFILE, or ADMIN.
     */
    private void requireEmergencyContactWrite(String patientSystemId, Integer contactId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER,
                AuthorizationService.ADMIN);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_PROFILE, contactId);
    }

    /**
     * Settings belong to the account: the owner or an ADMIN (404 for an ADMIN naming an unknown account).
     */
    private void authorizeSettings(String systemId) {
        authorizationService.requireSelfOrAdmin(systemId);
        if (!userRepository.existsBySystemId(systemId)) {
            throw new ResourceNotFoundException("User not found");
        }
    }

    // ==================== PROFILE HELPERS ====================

    private UserProfileResponse buildProfile(User user) {
        UserProfileResponse.UserProfileResponseBuilder builder = UserProfileResponse.builder()
                .userId(user.getUserId())
                .systemId(user.getSystemId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .email(user.getEmail())
                .role(user.getRole())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt());

        if (isPatient(user)) {
            builder.patientSystemId(user.getSystemId());
            patientRepository.findBySystemId(user.getSystemId()).ifPresent((Patient patient) -> builder
                    .dateOfBirth(patient.getDateOfBirth())
                    .gender(patient.getGender())
                    .bloodType(patient.getBloodType())
                    .allergies(patient.getAllergies())
                    .chronicConditions(patient.getChronicConditions())
                    .healthStatus(patient.getHealthStatus()));
            builder.emergencyContacts(listContacts(user.getSystemId()));
        } else if (AuthorizationService.CAREGIVER.equals(user.getRole())) {
            caregiverRepository.findBySystemId(user.getSystemId()).ifPresent((Caregiver caregiver) -> {
                builder.relationship(caregiver.getRelationship());
                if (user.getPhone() == null || user.getPhone().isBlank()) {
                    builder.phone(caregiver.getPhone());
                }
            });
        }
        return builder.build();
    }

    /**
     * Health summary fields are only returned to callers who may view the patient's records.
     */
    private static void redactHealthFields(UserProfileResponse profile) {
        profile.setBloodType(null);
        profile.setAllergies(null);
        profile.setChronicConditions(null);
        profile.setHealthStatus(null);
    }

    private static void rejectReadOnlyFields(UpdateUserProfileRequest request) {
        if (isNotBlank(request.getHealthStatus())) {
            throw new BadRequestException("Health status is set by your doctor and cannot be changed here");
        }
        if (isNotBlank(request.getEmail())) {
            throw new BadRequestException("Email cannot be changed here. Use Change email in your security settings");
        }
    }

    /** bloodType, allergies or chronicConditions present in the body (clearing values included). */
    private static boolean touchesHealthFields(UpdateUserProfileRequest request) {
        return request.getBloodType() != null || request.getAllergies() != null
                || request.getChronicConditions() != null;
    }

    /**
     * allergies / chronic_conditions are jsonb arrays of strings. The request carries the array as a JSON string;
     * blank clears the list. Returns the normalised JSON (entries trimmed, in the order sent).
     */
    private String normalizeList(String value, ListField field) {
        if (value.isBlank()) {
            return "[]";
        }
        JsonNode node;
        try {
            node = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        } catch (JsonProcessingException e) {
            throw new BadRequestException(field.notAList);
        }
        if (node == null || !node.isArray()) {
            throw new BadRequestException(field.notAList);
        }
        if (node.size() > MAX_LIST_ENTRIES) {
            throw new BadRequestException(field.tooMany);
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode entry : node) {
            if (!entry.isTextual()) {
                throw new BadRequestException(field.badEntry);
            }
            String text = entry.asText().trim();
            if (text.isEmpty() || text.length() > MAX_LIST_ENTRY_LENGTH) {
                throw new BadRequestException(field.badEntry);
            }
            normalized.add(text);
        }
        return normalized.toString();
    }

    // ==================== EMERGENCY CONTACT HELPERS ====================

    /**
     * Runs inside the idempotent action: the patient row is locked so the 10-contact cap and the single primary
     * contact hold when two requests for the same patient run at once.
     */
    private EmergencyContactResponse insertEmergencyContact(String patientSystemId, CreateEmergencyContactRequest request) {
        if (emergencyContactRepository.lockPatient(patientSystemId).isEmpty()) {
            throw new ResourceNotFoundException("Patient not found");
        }
        if (emergencyContactRepository.countByPatientSystemId(patientSystemId) >= EmergencyContact.MAX_PER_PATIENT) {
            throw new ConflictException("A patient can have at most " + EmergencyContact.MAX_PER_PATIENT
                    + " emergency contacts");
        }
        boolean primary = Boolean.TRUE.equals(request.getIsPrimary());
        if (primary) {
            emergencyContactRepository.demotePrimary(patientSystemId);
        }
        EmergencyContact saved = emergencyContactRepository.saveAndFlush(EmergencyContact.builder()
                .patientSystemId(patientSystemId)
                .name(request.getName())
                .relationship(emptyToNull(request.getRelationship()))
                .phone(request.getPhone())
                .email(emptyToNull(request.getEmail()))
                .isPrimary(primary)
                .isCaregiver(Boolean.TRUE.equals(request.getIsCaregiver()))
                .build());
        auditService.logClinicalAccess("CREATE", EMERGENCY_CONTACT_RESOURCE, saved.getContactId(), patientSystemId);
        return toResponse(saved);
    }

    private List<EmergencyContactResponse> listContacts(String patientSystemId) {
        return emergencyContactRepository.findTop50ByPatientSystemIdOrderByIsPrimaryDescContactIdAsc(patientSystemId)
                .stream()
                .map(UserProfileService::toResponse)
                .toList();
    }

    private EmergencyContact findContact(Integer contactId) {
        return emergencyContactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("Emergency contact not found"));
    }

    private static EmergencyContactResponse toResponse(EmergencyContact contact) {
        return EmergencyContactResponse.builder()
                .contactId(contact.getContactId())
                .patientSystemId(contact.getPatientSystemId())
                .name(contact.getName())
                .relationship(contact.getRelationship())
                .phone(contact.getPhone())
                .email(contact.getEmail())
                .isPrimary(contact.getIsPrimary())
                .isCaregiver(contact.getIsCaregiver())
                .createdAt(contact.getCreatedAt())
                .build();
    }

    // ==================== SETTINGS HELPERS ====================

    private UserSettings loadOrCreateSettings(String systemId) {
        return userSettingsRepository.findBySystemId(systemId).orElseGet(() -> {
            userSettingsRepository.insertDefaultsIfAbsent(systemId, LocalDateTime.now(clock));
            return userSettingsRepository.findBySystemId(systemId)
                    .orElseThrow(() -> new IllegalStateException("Settings row missing after insert"));
        });
    }

    /** "" (after trimming) stores null (clinic time); anything else must be a known zone id, compared exactly. */
    private static String resolveTimezone(String requested) {
        String zone = requested.trim();
        if (zone.isEmpty()) {
            return null;
        }
        if (!ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new BadRequestException("Unknown time zone");
        }
        return zone;
    }

    private static UserSettingsResponse toResponse(UserSettings settings) {
        return UserSettingsResponse.builder()
                .settingId(settings.getSettingId())
                .userId(null)
                .systemId(settings.getSystemId())
                .emailNotifications(settings.getEmailNotifications())
                .smsNotifications(settings.getSmsNotifications())
                .pushNotifications(settings.getPushNotifications())
                .appointmentReminders(settings.getAppointmentReminders())
                .appointmentReminder1h(settings.getAppointmentReminder1h())
                .appointmentChangeAlerts(settings.getAppointmentChangeAlerts())
                .prescriptionReminders(settings.getPrescriptionReminders())
                .medicationReminders(settings.getMedicationReminders())
                .labResultAlerts(settings.getLabResultAlerts())
                .imagingResultAlerts(settings.getImagingResultAlerts())
                .consultationNotifications(settings.getConsultationNotifications())
                .vitalsAlerts(settings.getVitalsAlerts())
                .criticalAlerts(settings.getCriticalAlerts())
                .referralAlerts(settings.getReferralAlerts())
                .carePlanUpdateAlerts(settings.getCarePlanUpdateAlerts())
                .accessChangeAlerts(settings.getAccessChangeAlerts())
                .loginAlerts(settings.getLoginAlerts())
                .billingNotifications(settings.getBillingNotifications())
                .videoCallAlerts(settings.getVideoCallAlerts())
                .messageNotifications(settings.getMessageNotifications())
                .quietHoursEnabled(settings.getQuietHoursEnabled())
                .quietHoursStart(formatTime(settings.getQuietHoursStart()))
                .quietHoursEnd(formatTime(settings.getQuietHoursEnd()))
                .timezone(settings.getTimezone())
                .createdAt(settings.getCreatedAt())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }

    private static String formatTime(LocalTime time) {
        return time == null ? null : time.format(HH_MM);
    }

    // ==================== SMALL HELPERS ====================

    private User findUser(String systemId) {
        return userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private static boolean isPatient(User user) {
        return AuthorizationService.PATIENT.equals(user.getRole());
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
