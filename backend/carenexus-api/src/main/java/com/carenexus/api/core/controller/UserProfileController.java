package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.core.dto.request.CreateEmergencyContactRequest;
import com.carenexus.api.core.dto.request.UpdateEmergencyContactRequest;
import com.carenexus.api.core.dto.request.UpdateUserProfileRequest;
import com.carenexus.api.core.dto.request.UpdateUserSettingsRequest;
import com.carenexus.api.core.dto.response.EmergencyContactResponse;
import com.carenexus.api.core.dto.response.UserProfileResponse;
import com.carenexus.api.core.dto.response.UserSettingsResponse;
import com.carenexus.api.core.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Profile, emergency contacts and notification settings. Thin: authorization, field rules and audit live in
 * {@link UserProfileService}.
 *
 * - /api/profile, /api/profile/settings: the caller's own account (identity from the JWT).
 * - /api/profile/user/{systemId}: self or ADMIN; for a PATIENT target also a related doctor (read) or a caregiver
 *   with an active grant (read; write needs MANAGE_PROFILE, and RECORDS for health fields).
 * - /api/profile/settings/user/{systemId}: self or ADMIN.
 * - Emergency contacts: judged against the contact's patient (PROFILE to read, MANAGE_PROFILE to write).
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Tag(name = "Profile & Settings", description = "Own and dependent profiles, emergency contacts and notification settings")
public class UserProfileController {

    private final UserProfileService userProfileService;

    // ==================== PROFILE ====================

    @GetMapping
    @StandardErrors
    @Operation(summary = "Get your own profile",
            description = "Any signed-in user. Never redacted. A CAREGIVER also gets their relationship and, when the "
                    + "account has no phone, the phone of the caregiver record. A PATIENT read writes a READ / "
                    + "PROFILE audit row.")
    @ApiResponse(responseCode = "200", description = "The user profile")
    public ResponseEntity<UserProfileResponse> getMyProfile() {
        return ResponseEntity.ok(userProfileService.getMyProfile());
    }

    @GetMapping("/user/{systemId}")
    @StandardErrors
    @Operation(summary = "Get a profile by system id",
            description = "The account owner or an ADMIN (any account); a related DOCTOR or a CAREGIVER with an "
                    + "active grant for a PATIENT. Blood type, allergies, chronic conditions and health status are "
                    + "null for callers who may not view the patient's records. Reading a PATIENT profile writes a "
                    + "READ / PROFILE audit row.")
    @ApiResponse(responseCode = "200", description = "The user profile")
    public ResponseEntity<UserProfileResponse> getUserProfile(
            @Parameter(description = "System id of the account", example = "PAT-00001") @PathVariable String systemId) {
        return ResponseEntity.ok(userProfileService.getUserProfile(systemId));
    }

    @PutMapping
    @StandardErrors
    @Operation(summary = "Update your own profile",
            description = "Any signed-in user; partial (only the fields sent change). healthStatus and email are "
                    + "refused with 400 when non-blank. Date of birth, gender, blood type and the lists apply to "
                    + "PATIENT accounts only. 409 when the phone belongs to another account. Audited (UPDATE / "
                    + "PROFILE for a patient, PROFILE_UPDATE / ACCOUNT otherwise).")
    @ApiResponse(responseCode = "200", description = "The updated user profile")
    public ResponseEntity<UserProfileResponse> updateMyProfile(@Valid @RequestBody UpdateUserProfileRequest request) {
        return ResponseEntity.ok(userProfileService.updateMyProfile(request));
    }

    @PutMapping("/user/{systemId}")
    @StandardErrors
    @Operation(summary = "Update a profile by system id",
            description = "The account owner, an ADMIN, or a CAREGIVER whose active grant for the PATIENT allows "
                    + "editing the profile. A caregiver also needs records access to send bloodType, allergies or "
                    + "chronicConditions (otherwise 403 and nothing is saved). Same body rules as PUT /api/profile.")
    @ApiResponse(responseCode = "200", description = "The updated user profile")
    public ResponseEntity<UserProfileResponse> updateUserProfile(
            @Parameter(description = "System id of the account", example = "PAT-00001") @PathVariable String systemId,
            @Valid @RequestBody UpdateUserProfileRequest request) {
        return ResponseEntity.ok(userProfileService.updateUserProfile(systemId, request));
    }

    // ==================== EMERGENCY CONTACTS ====================

    @PostMapping("/emergency-contacts")
    @StandardErrors
    @Operation(summary = "Add an emergency contact",
            description = "A PATIENT (own contacts; patientSystemId defaults to the caller), a CAREGIVER allowed to "
                    + "edit the patient's profile, or an ADMIN. At most 10 contacts per patient (409). isPrimary "
                    + "true demotes the current primary contact. Accepts Idempotency-Key: a replay answers 200 with "
                    + "the first response and creates nothing. Audited (CREATE / EMERGENCY_CONTACT).")
    @ApiResponse(responseCode = "201", description = "The contact was created")
    @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key")
    public ResponseEntity<EmergencyContactResponse> createEmergencyContact(
            @Parameter(description = "Optional duplicate-request key, 8 to 64 characters of [A-Za-z0-9_-]")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateEmergencyContactRequest request) {
        IdempotentResult<EmergencyContactResponse> result =
                userProfileService.createEmergencyContact(request, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/emergency-contacts")
    @StandardErrors
    @Operation(summary = "List your own emergency contacts",
            description = "PATIENT only (400 for other roles). Primary contact first, then oldest first; at most 10 "
                    + "can exist. Audited (READ / EMERGENCY_CONTACT).")
    @ApiResponse(responseCode = "200", description = "The emergency contact list (JSON array)")
    public ResponseEntity<List<EmergencyContactResponse>> getMyEmergencyContacts() {
        return ResponseEntity.ok(userProfileService.getMyEmergencyContacts());
    }

    @GetMapping("/emergency-contacts/patient/{patientSystemId}")
    @StandardErrors
    @Operation(summary = "List a patient's emergency contacts",
            description = "The patient, a related DOCTOR, a CAREGIVER with an active grant, or an ADMIN. Primary "
                    + "contact first, then oldest first. Audited (READ / EMERGENCY_CONTACT).")
    @ApiResponse(responseCode = "200", description = "The emergency contact list (JSON array)")
    public ResponseEntity<List<EmergencyContactResponse>> getPatientEmergencyContacts(
            @Parameter(description = "System id of the patient", example = "PAT-00001")
            @PathVariable String patientSystemId) {
        return ResponseEntity.ok(userProfileService.getPatientEmergencyContacts(patientSystemId));
    }

    @GetMapping("/emergency-contacts/{contactId}")
    @StandardErrors
    @Operation(summary = "Get one emergency contact",
            description = "Same callers as the patient's contact list, judged against the contact's patient. "
                    + "Audited (READ / EMERGENCY_CONTACT).")
    @ApiResponse(responseCode = "200", description = "The emergency contact")
    public ResponseEntity<EmergencyContactResponse> getEmergencyContactById(@PathVariable Integer contactId) {
        return ResponseEntity.ok(userProfileService.getEmergencyContactById(contactId));
    }

    @PutMapping("/emergency-contacts/{contactId}")
    @StandardErrors
    @Operation(summary = "Update an emergency contact",
            description = "PATIENT (own), CAREGIVER allowed to edit the patient's profile, or ADMIN. Partial update; "
                    + "relationship and email are cleared by \"\". isPrimary true demotes the previous primary "
                    + "contact. Audited (UPDATE / EMERGENCY_CONTACT).")
    @ApiResponse(responseCode = "200", description = "The updated emergency contact")
    public ResponseEntity<EmergencyContactResponse> updateEmergencyContact(
            @PathVariable Integer contactId,
            @Valid @RequestBody UpdateEmergencyContactRequest request) {
        return ResponseEntity.ok(userProfileService.updateEmergencyContact(contactId, request));
    }

    @DeleteMapping("/emergency-contacts/{contactId}")
    @StandardErrors
    @Operation(summary = "Delete an emergency contact",
            description = "PATIENT (own), CAREGIVER allowed to edit the patient's profile, or ADMIN. Deleting the "
                    + "primary contact promotes nobody. Audited (DELETE / EMERGENCY_CONTACT).")
    @ApiResponse(responseCode = "204", description = "The contact was deleted")
    public ResponseEntity<Void> deleteEmergencyContact(@PathVariable Integer contactId) {
        userProfileService.deleteEmergencyContact(contactId);
        return ResponseEntity.noContent().build();
    }

    // ==================== SETTINGS ====================

    @GetMapping("/settings")
    @StandardErrors
    @Operation(summary = "Get your notification settings",
            description = "Any signed-in user, own settings; defaults when never saved. Audited (SETTINGS_READ / "
                    + "USER_SETTINGS).")
    @ApiResponse(responseCode = "200", description = "The user settings")
    public ResponseEntity<UserSettingsResponse> getMySettings() {
        return ResponseEntity.ok(userProfileService.getMySettings());
    }

    @PatchMapping("/settings")
    @StandardErrors
    @Operation(summary = "Change your notification settings",
            description = "Any signed-in user, own settings; partial ({} changes nothing). timezone \"\" means "
                    + "clinic time. With quiet hours on, start and end must both be set and differ. Removed fields "
                    + "(profileVisibility, dataSharingEnabled, language, theme, weeklySummary, twoFactorEnabled) are "
                    + "ignored. Audited (SETTINGS_UPDATE / USER_SETTINGS).")
    @ApiResponse(responseCode = "200", description = "The updated user settings")
    public ResponseEntity<UserSettingsResponse> patchMySettings(@Valid @RequestBody UpdateUserSettingsRequest request) {
        return ResponseEntity.ok(userProfileService.updateMySettings(request));
    }

    @GetMapping("/settings/user/{systemId}")
    @StandardErrors
    @Operation(summary = "Get an account's notification settings",
            description = "The account owner or an ADMIN only. Audited (SETTINGS_READ / USER_SETTINGS).")
    @ApiResponse(responseCode = "200", description = "The user settings")
    public ResponseEntity<UserSettingsResponse> getUserSettings(
            @Parameter(description = "System id of the account", example = "PAT-00001") @PathVariable String systemId) {
        return ResponseEntity.ok(userProfileService.getUserSettings(systemId));
    }

    @PutMapping("/settings/user/{systemId}")
    @StandardErrors
    @Operation(summary = "Change an account's notification settings",
            description = "The account owner or an ADMIN only. Same body and rules as PATCH /api/profile/settings. "
                    + "Audited (SETTINGS_UPDATE / USER_SETTINGS).")
    @ApiResponse(responseCode = "200", description = "The updated user settings")
    public ResponseEntity<UserSettingsResponse> updateUserSettings(
            @Parameter(description = "System id of the account", example = "PAT-00001") @PathVariable String systemId,
            @Valid @RequestBody UpdateUserSettingsRequest request) {
        return ResponseEntity.ok(userProfileService.updateUserSettings(systemId, request));
    }

    /**
     * @deprecated use PATCH /api/profile/settings (identical body and response).
     */
    @Deprecated
    @PutMapping("/settings")
    @StandardErrors
    @Operation(summary = "Change your notification settings (deprecated)", deprecated = true,
            description = "Alias of PATCH /api/profile/settings with the identical body, partial semantics, "
                    + "response and audit.")
    @ApiResponse(responseCode = "200", description = "The updated user settings")
    public ResponseEntity<UserSettingsResponse> updateMySettings(@Valid @RequestBody UpdateUserSettingsRequest request) {
        return ResponseEntity.ok(userProfileService.updateMySettings(request));
    }

    /**
     * @deprecated use PATCH /api/profile/settings. Kept while frontend/e2e specs call it (rule 0.8).
     */
    @Deprecated
    @PatchMapping("/settings/notifications")
    @StandardErrors
    @Operation(summary = "Change your notification settings (deprecated)", deprecated = true,
            description = "Alias of PATCH /api/profile/settings with the identical body, partial semantics, "
                    + "response and audit. Removed once no client calls it.")
    @ApiResponse(responseCode = "200", description = "The updated user settings")
    public ResponseEntity<UserSettingsResponse> updateNotificationSettings(
            @Valid @RequestBody UpdateUserSettingsRequest request) {
        return ResponseEntity.ok(userProfileService.updateMySettings(request));
    }
}
