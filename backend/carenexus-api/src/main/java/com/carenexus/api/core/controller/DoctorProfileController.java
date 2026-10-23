package com.carenexus.api.core.controller;

import com.carenexus.api.core.dto.request.UpdateDoctorProfileRequest;
import com.carenexus.api.core.dto.response.DoctorProfileResponse;
import com.carenexus.api.core.service.DoctorProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/doctors/profile")
@RequiredArgsConstructor
public class DoctorProfileController {

    private final DoctorProfileService doctorProfileService;

    @GetMapping
    public ResponseEntity<DoctorProfileResponse> getMyProfile(
            @RequestAttribute("systemId") String systemId) {
        DoctorProfileResponse profile = doctorProfileService.getDoctorProfile(systemId);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/{doctorSystemId}")
    public ResponseEntity<DoctorProfileResponse> getDoctorProfile(@PathVariable String doctorSystemId) {
        DoctorProfileResponse profile = doctorProfileService.getDoctorProfileById(doctorSystemId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping
    public ResponseEntity<DoctorProfileResponse> updateMyProfile(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateDoctorProfileRequest request) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(systemId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    @PutMapping("/{doctorSystemId}")
    public ResponseEntity<DoctorProfileResponse> updateDoctorProfile(
            @PathVariable String doctorSystemId,
            @RequestBody UpdateDoctorProfileRequest request) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(doctorSystemId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/basic-info")
    public ResponseEntity<DoctorProfileResponse> updateMyBasicInfo(
            @RequestAttribute("systemId") String systemId,
            @RequestBody UpdateDoctorProfileRequest request) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(systemId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/specialization")
    public ResponseEntity<DoctorProfileResponse> updateMySpecialization(
            @RequestAttribute("systemId") String systemId,
            @RequestParam String specialization) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorSpecialization(systemId, specialization);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/specialization")
    public ResponseEntity<DoctorProfileResponse> updateDoctorSpecialization(
            @PathVariable String doctorSystemId,
            @RequestParam String specialization) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorSpecialization(doctorSystemId, specialization);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/bio")
    public ResponseEntity<DoctorProfileResponse> updateMyBio(
            @RequestAttribute("systemId") String systemId,
            @RequestBody String bio) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorBio(systemId, bio);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/bio")
    public ResponseEntity<DoctorProfileResponse> updateDoctorBio(
            @PathVariable String doctorSystemId,
            @RequestBody String bio) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorBio(doctorSystemId, bio);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/status/toggle")
    public ResponseEntity<DoctorProfileResponse> toggleMyStatus(
            @RequestAttribute("systemId") String systemId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.toggleDoctorStatus(systemId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/status/toggle")
    public ResponseEntity<DoctorProfileResponse> toggleDoctorStatus(@PathVariable String doctorSystemId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.toggleDoctorStatus(doctorSystemId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/status/activate")
    public ResponseEntity<DoctorProfileResponse> activateMyAccount(
            @RequestAttribute("systemId") String systemId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.activateDoctor(systemId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/status/activate")
    public ResponseEntity<DoctorProfileResponse> activateDoctorAccount(@PathVariable String doctorSystemId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.activateDoctor(doctorSystemId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/status/deactivate")
    public ResponseEntity<DoctorProfileResponse> deactivateMyAccount(
            @RequestAttribute("systemId") String systemId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.deactivateDoctor(systemId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorSystemId}/status/deactivate")
    public ResponseEntity<DoctorProfileResponse> deactivateDoctorAccount(@PathVariable String doctorSystemId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.deactivateDoctor(doctorSystemId);
        return ResponseEntity.ok(updatedProfile);
    }

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
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(specialization, isActive);
        return ResponseEntity.ok(doctors);
    }

    @GetMapping("/filter/active")
    public ResponseEntity<List<DoctorProfileResponse>> filterByActiveStatus(
            @RequestParam Boolean isActive) {
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(null, isActive);
        return ResponseEntity.ok(doctors);
    }

    @GetMapping("/available")
    public ResponseEntity<List<DoctorProfileResponse>> getAvailableDoctors() {
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(null, true);
        return ResponseEntity.ok(doctors);
    }

    @GetMapping("/unavailable")
    public ResponseEntity<List<DoctorProfileResponse>> getUnavailableDoctors() {
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(null, false);
        return ResponseEntity.ok(doctors);
    }

    @GetMapping("/public/{doctorSystemId}")
    public ResponseEntity<DoctorProfileResponse> getPublicDoctorProfile(@PathVariable String doctorSystemId) {
        DoctorProfileResponse profile = doctorProfileService.getDoctorProfileById(doctorSystemId);
        return ResponseEntity.ok(profile);
    }
}
