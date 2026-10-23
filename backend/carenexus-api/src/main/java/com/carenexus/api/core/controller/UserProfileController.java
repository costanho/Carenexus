package com.carenexus.api.core.controller;

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

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping
    public ResponseEntity<UserProfileResponse> getMyProfile(
            @RequestAttribute("systemId") String systemId) {
        UserProfileResponse profile = userProfileService.getUserProfile(systemId);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/user/{systemId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable String systemId) {
        UserProfileResponse profile = userProfileService.getUserProfile(systemId);
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
        UserProfileResponse updatedProfile = userProfileService.updateUserProfile(systemId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    @PostMapping("/emergency-contacts")
    public ResponseEntity<EmergencyContactResponse> createEmergencyContact(
            @RequestBody CreateEmergencyContactRequest request) {
        EmergencyContactResponse response = userProfileService.createEmergencyContact(request);
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
        List<EmergencyContactResponse> contacts = userProfileService.getPatientEmergencyContacts(patientSystemId);
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<EmergencyContactResponse> getEmergencyContactById(
            @PathVariable Integer contactId) {
        EmergencyContactResponse contact = userProfileService.getEmergencyContactById(contactId);
        return ResponseEntity.ok(contact);
    }

    @PutMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<EmergencyContactResponse> updateEmergencyContact(
            @PathVariable Integer contactId,
            @RequestBody CreateEmergencyContactRequest request) {
        EmergencyContactResponse updatedContact = userProfileService.updateEmergencyContact(contactId, request);
        return ResponseEntity.ok(updatedContact);
    }

    @DeleteMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<Void> deleteEmergencyContact(@PathVariable Integer contactId) {
        userProfileService.deleteEmergencyContact(contactId);
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
}
