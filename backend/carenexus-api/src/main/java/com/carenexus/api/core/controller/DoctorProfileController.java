package com.carenexus.api.core.controller;

import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.UpdateDoctorProfileRequest;
import com.carenexus.api.core.dto.response.DoctorProfileResponse;
import com.carenexus.api.core.service.DoctorProfileService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Doctor profiles.
 *
 * - Self endpoints (no {doctorSystemId}): the calling DOCTOR's own profile.
 * - /{doctorSystemId} reads: that doctor or an ADMIN. Writes on /{doctorSystemId}/...: ADMIN only.
 * - Activation (/{doctorSystemId}/status/activate|deactivate|toggle) is the clinician verification path: ADMIN only.
 *   Doctors cannot (re)activate themselves.
 * - Directory (list/active, search/*, filter/* with isActive=true, available, public/{id}): any authenticated user,
 *   only verified/active doctors, public fields only. Listing inactive/pending doctors is ADMIN only.
 */
@RestController
@RequestMapping("/api/doctors/profile")
@RequiredArgsConstructor
public class DoctorProfileController {

    private final DoctorProfileService doctorProfileService;
    private final AuthorizationService authorizationService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    // ==================== SELF ====================

    @GetMapping
    public ResponseEntity<DoctorProfileResponse> getMyProfile(
            @RequestAttribute("systemId") String systemId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        DoctorProfileResponse profile = doctorProfileService.getDoctorProfile(systemId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping
    public ResponseEntity<DoctorProfileResponse> updateMyProfile(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateDoctorProfileRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(systemId, request, false);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/basic-info")
    public ResponseEntity<DoctorProfileResponse> updateMyBasicInfo(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateDoctorProfileRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(systemId, request, false);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/specialization")
    public ResponseEntity<DoctorProfileResponse> updateMySpecialization(
            @RequestAttribute("systemId") String systemId,
            @RequestParam String specialization) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorSpecialization(systemId, specialization);
        return ResponseEntity.ok(updatedProfile);
    }

    /**
     * Body: {"bio": "..."} (JSON) or the bio as plain text.
     */
    @PatchMapping("/bio")
    public ResponseEntity<DoctorProfileResponse> updateMyBio(
            @RequestAttribute("systemId") String systemId,
            @RequestBody(required = false) String body) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorBio(systemId, parseBio(body));
        return ResponseEntity.ok(updatedProfile);
    }

    // ==================== BY ID (self or admin read, admin write) ====================

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<DoctorProfileResponse> getDoctorProfile(@PathVariable String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        DoctorProfileResponse profile = doctorProfileService.getDoctorProfileById(doctorSystemId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/{doctorSystemId}")
    public ResponseEntity<DoctorProfileResponse> updateDoctorProfile(
            @PathVariable String doctorSystemId,
            @RequestBody UpdateDoctorProfileRequest request) {
        authorizationService.requireAdmin();
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(doctorSystemId, request, true);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/specialization")
    public ResponseEntity<DoctorProfileResponse> updateDoctorSpecialization(
            @PathVariable String doctorSystemId,
            @RequestParam String specialization) {
        authorizationService.requireAdmin();
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorSpecialization(doctorSystemId, specialization);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/bio")
    public ResponseEntity<DoctorProfileResponse> updateDoctorBio(
            @PathVariable String doctorSystemId,
            @RequestBody(required = false) String body) {
        authorizationService.requireAdmin();
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorBio(doctorSystemId, parseBio(body));
        return ResponseEntity.ok(updatedProfile);
    }

    // ==================== ACTIVATION / VERIFICATION (ADMIN) ====================

    @PatchMapping("/{doctorSystemId}/status/toggle")
    public ResponseEntity<DoctorProfileResponse> toggleDoctorStatus(@PathVariable String doctorSystemId,
                                                                    HttpServletRequest httpRequest) {
        authorizationService.requireAdmin();
        DoctorProfileResponse updatedProfile = doctorProfileService.toggleDoctorStatus(doctorSystemId);
        if (!Boolean.TRUE.equals(updatedProfile.getIsActive())) {
            authService.logout(doctorSystemId, httpRequest.getRemoteAddr());
        }
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/status/activate")
    public ResponseEntity<DoctorProfileResponse> activateDoctorAccount(@PathVariable String doctorSystemId) {
        authorizationService.requireAdmin();
        DoctorProfileResponse updatedProfile = doctorProfileService.activateDoctor(doctorSystemId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/status/deactivate")
    public ResponseEntity<DoctorProfileResponse> deactivateDoctorAccount(@PathVariable String doctorSystemId,
                                                                         HttpServletRequest httpRequest) {
        authorizationService.requireAdmin();
        DoctorProfileResponse updatedProfile = doctorProfileService.deactivateDoctor(doctorSystemId);
        authService.logout(doctorSystemId, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(updatedProfile);
    }

    // ==================== DIRECTORY ====================

    @GetMapping("/list/active")
    public ResponseEntity<List<DoctorProfileResponse>> getAllActiveDoctors() {
        List<DoctorProfileResponse> doctors = doctorProfileService.getAllActiveDoctorsProfiles();
        return ResponseEntity.ok(doctors);
    }

    @GetMapping("/search/specialization")
    public ResponseEntity<List<DoctorProfileResponse>> searchBySpecialization(
            @RequestParam String specialization) {
        List<DoctorProfileResponse> doctors = doctorProfileService.searchDoctorsBySpecialization(specialization);
        return ResponseEntity.ok(doctors);
    }

    @GetMapping("/search/name")
    public ResponseEntity<List<DoctorProfileResponse>> searchByName(@RequestParam String query) {
        List<DoctorProfileResponse> doctors = doctorProfileService.searchDoctorsByName(query);
        return ResponseEntity.ok(doctors);
    }

    @GetMapping("/filter/specialization")
    public ResponseEntity<List<DoctorProfileResponse>> filterBySpecialization(
            @RequestParam String specialization,
            @RequestParam(required = false) Boolean isActive) {
        if (Boolean.TRUE.equals(isActive)) {
            return ResponseEntity.ok(doctorProfileService.getActiveDoctorsBySpecialization(specialization));
        }
        // All or inactive doctors include pending clinicians: admin only
        authorizationService.requireAdmin();
        return ResponseEntity.ok(doctorProfileService.getDoctorsBySpecializationAndActive(specialization, isActive));
    }

    @GetMapping("/filter/active")
    public ResponseEntity<List<DoctorProfileResponse>> filterByActiveStatus(
            @RequestParam Boolean isActive) {
        if (Boolean.TRUE.equals(isActive)) {
            return ResponseEntity.ok(doctorProfileService.getAllActiveDoctorsProfiles());
        }
        authorizationService.requireAdmin();
        return ResponseEntity.ok(doctorProfileService.getDoctorsBySpecializationAndActive(null, isActive));
    }

    @GetMapping("/available")
    public ResponseEntity<List<DoctorProfileResponse>> getAvailableDoctors() {
        return ResponseEntity.ok(doctorProfileService.getAllActiveDoctorsProfiles());
    }

    @GetMapping("/unavailable")
    public ResponseEntity<List<DoctorProfileResponse>> getUnavailableDoctors() {
        authorizationService.requireAdmin();
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(null, false);
        return ResponseEntity.ok(doctors);
    }

    @GetMapping("/public/{doctorSystemId}")
    public ResponseEntity<DoctorProfileResponse> getPublicDoctorProfile(@PathVariable String doctorSystemId) {
        DoctorProfileResponse profile = doctorProfileService.getPublicDoctorProfile(doctorSystemId);
        return ResponseEntity.ok(profile);
    }

    // ==================== HELPERS ====================

    /**
     * Accepts {"bio": "..."}, a JSON string literal, or plain text, so JSON clients do not store quoted text.
     */
    private String parseBio(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("\"")) {
            try {
                JsonNode node = objectMapper.readTree(trimmed);
                if (node.isTextual()) {
                    return node.asText();
                }
                if (node.isObject()) {
                    JsonNode bio = node.get("bio");
                    return bio == null || bio.isNull() ? null : bio.asText();
                }
            } catch (JsonProcessingException e) {
                // not JSON: treat as plain text
            }
        }
        return body;
    }
}
