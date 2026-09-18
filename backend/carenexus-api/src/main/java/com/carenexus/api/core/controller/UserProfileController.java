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
            @RequestAttribute("userId") Integer userId) {
        UserProfileResponse profile = userProfileService.getUserProfile(userId);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable Integer userId) {
        UserProfileResponse profile = userProfileService.getUserProfile(userId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            @RequestAttribute("userId") Integer userId,
            @RequestBody UpdateUserProfileRequest request) {
        UserProfileResponse updatedProfile = userProfileService.updateUserProfile(userId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    @PutMapping("/user/{userId}")
    public ResponseEntity<UserProfileResponse> updateUserProfile(
            @PathVariable Integer userId,
            @RequestBody UpdateUserProfileRequest request) {
        UserProfileResponse updatedProfile = userProfileService.updateUserProfile(userId, request);
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
            @RequestAttribute("userId") Integer userId) {
        UserProfileResponse profile = userProfileService.getUserProfile(userId);
        if (profile.getPatientId() == null) {
            throw new RuntimeException("Only patients can have emergency contacts");
        }
        List<EmergencyContactResponse> contacts = userProfileService.getPatientEmergencyContacts(profile.getPatientId());
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/emergency-contacts/patient/{patientId}")
    public ResponseEntity<List<EmergencyContactResponse>> getPatientEmergencyContacts(
            @PathVariable Integer patientId) {
        List<EmergencyContactResponse> contacts = userProfileService.getPatientEmergencyContacts(patientId);
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
            @RequestAttribute("userId") Integer userId) {
        UserSettingsResponse settings = userProfileService.getUserSettings(userId);
        return ResponseEntity.ok(settings);
    }

    @GetMapping("/settings/user/{userId}")
    public ResponseEntity<UserSettingsResponse> getUserSettings(@PathVariable Integer userId) {
        UserSettingsResponse settings = userProfileService.getUserSettings(userId);
        return ResponseEntity.ok(settings);
    }

    @PutMapping("/settings")
    public ResponseEntity<UserSettingsResponse> updateMySettings(
            @RequestAttribute("userId") Integer userId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PutMapping("/settings/user/{userId}")
    public ResponseEntity<UserSettingsResponse> updateUserSettings(
            @PathVariable Integer userId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/notifications")
    public ResponseEntity<UserSettingsResponse> updateNotificationSettings(
            @RequestAttribute("userId") Integer userId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/privacy")
    public ResponseEntity<UserSettingsResponse> updatePrivacySettings(
            @RequestAttribute("userId") Integer userId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/appearance")
    public ResponseEntity<UserSettingsResponse> updateAppearanceSettings(
            @RequestAttribute("userId") Integer userId,
            @RequestBody UpdateUserSettingsRequest request) {
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/language")
    public ResponseEntity<UserSettingsResponse> updateLanguageSettings(
            @RequestAttribute("userId") Integer userId,
            @RequestParam String language) {
        UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                .language(language)
                .build();
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/timezone")
    public ResponseEntity<UserSettingsResponse> updateTimezoneSettings(
            @RequestAttribute("userId") Integer userId,
            @RequestParam String timezone) {
        UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                .timezone(timezone)
                .build();
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/theme")
    public ResponseEntity<UserSettingsResponse> updateThemeSettings(
            @RequestAttribute("userId") Integer userId,
            @RequestParam String theme) {
        UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                .theme(theme)
                .build();
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }

    @PatchMapping("/settings/two-factor")
    public ResponseEntity<UserSettingsResponse> toggleTwoFactorAuthentication(
            @RequestAttribute("userId") Integer userId,
            @RequestParam Boolean enabled) {
        UpdateUserSettingsRequest request = UpdateUserSettingsRequest.builder()
                .twoFactorEnabled(enabled)
                .build();
        UserSettingsResponse updatedSettings = userProfileService.updateUserSettings(userId, request);
        return ResponseEntity.ok(updatedSettings);
    }
}
