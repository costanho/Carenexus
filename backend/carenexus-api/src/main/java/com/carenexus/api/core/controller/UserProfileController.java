package com.carenexus.api.core.controller;

import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateEmergencyContactRequest;
import com.carenexus.api.core.dto.request.UpdateUserProfileRequest;
import com.carenexus.api.core.dto.request.UpdateUserSettingsRequest;
import com.carenexus.api.core.dto.response.EmergencyContactResponse;
import com.carenexus.api.core.dto.response.UserProfileResponse;
import com.carenexus.api.core.dto.response.UserSettingsResponse;
import com.carenexus.api.core.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Profile, emergency contacts and settings.
 *
 * Authorization:
 * - /api/profile, /api/profile/settings*: the caller's own account (system ID from the JWT).
 * - /user/{systemId}: self or ADMIN; for a PATIENT target also a related doctor (read) or a caregiver with an
 *   active grant (read; write needs can_manage_care_plan). Health fields are hidden from callers who may not
 *   view the patient's records, and only the account owner or an ADMIN can change the login email.
 * - /settings/user/{systemId}: self or ADMIN.
 * - Emergency contacts: authorized against the contact's patient (PROFILE to read, MANAGE_PROFILE to write).
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<UserProfileResponse> getMyProfile(
            @RequestAttribute("systemId") String systemId) {
        UserProfileResponse profile = userProfileService.getUserProfile(systemId);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/user/{systemId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable String systemId) {
        if (isSelfOrAdmin(systemId)) {
            return ResponseEntity.ok(userProfileService.getUserProfile(systemId));
        }
        // Someone else's account: only a patient's profile can be shared (with their doctors / caregivers)
        requirePatientTarget(systemId);
        authorizationService.requirePatientAccess(systemId, PatientPermission.PROFILE);

        UserProfileResponse profile = redactHealthFields(userProfileService.getUserProfile(systemId));
        auditService.logClinicalAccess("READ", "PROFILE", null, systemId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateUserProfileRequest request) {
        UserProfileResponse updatedProfile = userProfileService.updateUserProfile(systemId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    @PutMapping("/user/{systemId}")
    public ResponseEntity<UserProfileResponse> updateUserProfile(
            @PathVariable String systemId,
            @RequestBody UpdateUserProfileRequest request) {
        if (isSelfOrAdmin(systemId)) {
            return ResponseEntity.ok(userProfileService.updateUserProfile(systemId, request));
        }
        // Only a caregiver allowed to manage this patient may edit on their behalf
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        requirePatientTarget(systemId);
        authorizationService.requirePatientAccess(systemId, PatientPermission.MANAGE_PROFILE);
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            throw new RuntimeException("Only the account holder can change the login email");
        }

        UserProfileResponse updatedProfile = redactHealthFields(userProfileService.updateUserProfile(systemId, request));
        auditService.logClinicalAccess("UPDATE", "PROFILE", null, systemId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PostMapping("/emergency-contacts")
    public ResponseEntity<EmergencyContactResponse> createEmergencyContact(
            @RequestBody CreateEmergencyContactRequest request) {
        if ((request.getPatientSystemId() == null || request.getPatientSystemId().isBlank())
                && AuthorizationService.PATIENT.equals(authorizationService.currentRole())) {
            request.setPatientSystemId(authorizationService.currentSystemId());
        }
        if (request.getPatientSystemId() == null || request.getPatientSystemId().isBlank()) {
            throw new RuntimeException("Patient System ID is required");
        }
        requireEmergencyContactWrite(request.getPatientSystemId(), null);

        EmergencyContactResponse response = userProfileService.createEmergencyContact(request);
        auditService.logClinicalAccess("CREATE", "EMERGENCY_CONTACT", response.getContactId(), response.getPatientSystemId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/emergency-contacts")
    public ResponseEntity<List<EmergencyContactResponse>> getMyEmergencyContacts(
            @RequestAttribute("systemId") String systemId) {
        UserProfileResponse profile = userProfileService.getUserProfile(systemId);
        if (profile.getPatientSystemId() == null) {
            throw new RuntimeException("Only patients can have emergency contacts");
        }
        List<EmergencyContactResponse> contacts = userProfileService.getPatientEmergencyContacts(profile.getPatientSystemId());
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/emergency-contacts/patient/{patientSystemId}")
    public ResponseEntity<List<EmergencyContactResponse>> getPatientEmergencyContacts(
            @PathVariable String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.PROFILE);
        List<EmergencyContactResponse> contacts = userProfileService.getPatientEmergencyContacts(patientSystemId);
        auditService.logClinicalAccess("READ", "EMERGENCY_CONTACT", null, patientSystemId);
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<EmergencyContactResponse> getEmergencyContactById(
            @PathVariable Integer contactId) {
        String patientSystemId = userProfileService.getEmergencyContactPatientSystemId(contactId);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.PROFILE, contactId);
        EmergencyContactResponse contact = userProfileService.getEmergencyContactById(contactId);
        auditService.logClinicalAccess("READ", "EMERGENCY_CONTACT", contactId, patientSystemId);
        return ResponseEntity.ok(contact);
    }

    @PutMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<EmergencyContactResponse> updateEmergencyContact(
            @PathVariable Integer contactId,
            @RequestBody CreateEmergencyContactRequest request) {
        String patientSystemId = userProfileService.getEmergencyContactPatientSystemId(contactId);
        requireEmergencyContactWrite(patientSystemId, contactId);
        EmergencyContactResponse updatedContact = userProfileService.updateEmergencyContact(contactId, request);
        auditService.logClinicalAccess("UPDATE", "EMERGENCY_CONTACT", contactId, patientSystemId);
        return ResponseEntity.ok(updatedContact);
    }

    @DeleteMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<Void> deleteEmergencyContact(@PathVariable Integer contactId) {
        String patientSystemId = userProfileService.getEmergencyContactPatientSystemId(contactId);
        requireEmergencyContactWrite(patientSystemId, contactId);
        userProfileService.deleteEmergencyContact(contactId);
        auditService.logClinicalAccess("DELETE", "EMERGENCY_CONTACT", contactId, patientSystemId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public ResponseEntity<UserSettingsResponse> getMySettings(
            @RequestAttribute("systemId") String systemId) {
        UserSettingsResponse settings = userProfileService.getUserSettings(systemId);
        return ResponseEntity.ok(settings);
    }

    @GetMapping("/settings/user/{systemId}")
    public ResponseEntity<UserSettingsResponse> getUserSettings(@PathVariable String systemId) {
        authorizationService.requireSelfOrAdmin(systemId);
        UserSettingsResponse settings = userProfileService.getUserSettings(systemId);
        return ResponseEntity.ok(settings);
    }

    @PutMapping("/settings")
    public ResponseEntity<UserSettingsResponse> updateMySettings(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PutMapping("/settings/user/{systemId}")
    public ResponseEntity<UserSettingsResponse> updateUserSettings(
            @PathVariable String systemId,
            @RequestBody UpdateUserSettingsRequest request) {
        authorizationService.requireSelfOrAdmin(systemId);
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/notifications")
    public ResponseEntity<UserSettingsResponse> updateNotificationSettings(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/privacy")
    public ResponseEntity<UserSettingsResponse> updatePrivacySettings(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/appearance")
    public ResponseEntity<UserSettingsResponse> updateAppearanceSettings(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/language")
    public ResponseEntity<UserSettingsResponse> updateLanguageSettings(
            @RequestAttribute("systemId") String systemId,
            @RequestParam String language) {
        UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                .language(language)
                .build();
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/timezone")
    public ResponseEntity<UserSettingsResponse> updateTimezoneSettings(
            @RequestAttribute("systemId") String systemId,
            @RequestParam String timezone) {
        UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                .timezone(timezone)
                .build();
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/theme")
    public ResponseEntity<UserSettingsResponse> updateThemeSettings(
            @RequestAttribute("systemId") String systemId,
            @RequestParam String theme) {
        UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                .theme(theme)
                .build();
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    /**
     * Two-factor authentication is not implemented: enabled=true is rejected with 400 by the service,
     * enabled=false is accepted (it is already off).
     */
    @PatchMapping("/settings/two-factor")
    public ResponseEntity<UserSettingsResponse> toggleTwoFactorAuthentication(
            @RequestAttribute("systemId") String systemId,
            @RequestParam Boolean enabled) {
        UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                .twoFactorEnabled(enabled)
                .build();
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(systemId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    // ==================== HELPERS ====================

    private boolean isSelfOrAdmin(String targetSystemId) {
        return authorizationService.currentSystemId().equals(targetSystemId) || authorizationService.isAdmin();
    }

    /**
     * Health summary fields (blood type, allergies, conditions, status) are only returned to callers who may view
     * the patient's records; e.g. a caregiver without can_view_records still sees the demographics.
     */
    private UserProfileResponse redactHealthFields(UserProfileResponse profile) {
        String patientSystemId = profile.getPatientSystemId();
        if (patientSystemId == null || !authorizationService.canAccessPatient(patientSystemId, PatientPermission.RECORDS)) {
            profile.setBloodType(null);
            profile.setAllergies(null);
            profile.setChronicConditions(null);
            profile.setHealthStatus(null);
        }
        return profile;
    }

    /**
     * Another user's account may only be reached through the patient-access rules; any other target
     * (doctor, caregiver, admin account) is self-or-admin only.
     */
    private void requirePatientTarget(String targetSystemId) {
        if (!AuthorizationService.PATIENT.equals(userProfileService.getUserRole(targetSystemId))) {
            authorizationService.requireSelfOrAdmin(targetSystemId);
        }
    }

    /**
     * Writes to a patient's emergency contacts: the patient, a caregiver with can_manage_care_plan, or an ADMIN.
     */
    private void requireEmergencyContactWrite(String patientSystemId, Integer contactId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER, AuthorizationService.ADMIN);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_PROFILE, contactId);
    }
}
