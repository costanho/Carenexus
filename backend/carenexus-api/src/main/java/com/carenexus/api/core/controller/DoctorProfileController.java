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

    // Get My Profile
    @GetMapping
    public ResponseEntity<DoctorProfileResponse> getMyProfile(
            @RequestAttribute("userId") Integer userId) {
        DoctorProfileResponse profile = doctorProfileService.getDoctorProfile(userId);
        return ResponseEntity.ok(profile);
    }

    // Get Specific Doctor Profile
    @GetMapping("/{doctorId}")
    public ResponseEntity<DoctorProfileResponse> getDoctorProfile(@PathVariable Integer doctorId) {
        DoctorProfileResponse profile = doctorProfileService.getDoctorProfileById(doctorId);
        return ResponseEntity.ok(profile);
    }

    // Update My Profile (Full Update)
    @PutMapping
    public ResponseEntity<DoctorProfileResponse> updateMyProfile(
            @RequestAttribute("userId") Integer userId,
            @RequestBody UpdateDoctorProfileRequest request) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(userId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    // Update Specific Doctor Profile
    @PutMapping("/{doctorId}")
    public ResponseEntity<DoctorProfileResponse> updateDoctorProfile(
            @PathVariable Integer doctorId,
            @RequestBody UpdateDoctorProfileRequest request) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(doctorId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    // Update My Basic Information
    @PatchMapping("/basic-info")
    public ResponseEntity<DoctorProfileResponse> updateMyBasicInfo(
            @RequestAttribute("userId") Integer userId,
            @RequestBody UpdateDoctorProfileRequest request) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorProfile(userId, request);
        return ResponseEntity.ok(updatedProfile);
    }

    // Update My Specialization
    @PatchMapping("/specialization")
    public ResponseEntity<DoctorProfileResponse> updateMySpecialization(
            @RequestAttribute("userId") Integer userId,
            @RequestParam String specialization) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorSpecialization(userId, specialization);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorId}/specialization")
    public ResponseEntity<DoctorProfileResponse> updateDoctorSpecialization(
            @PathVariable Integer doctorId,
            @RequestParam String specialization) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorSpecialization(doctorId, specialization);
        return ResponseEntity.ok(updatedProfile);
    }

    // Update My Bio
    @PatchMapping("/bio")
    public ResponseEntity<DoctorProfileResponse> updateMyBio(
            @RequestAttribute("userId") Integer userId,
            @RequestBody String bio) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorBio(userId, bio);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorId}/bio")
    public ResponseEntity<DoctorProfileResponse> updateDoctorBio(
            @PathVariable Integer doctorId,
            @RequestBody String bio) {
        DoctorProfileResponse updatedProfile = doctorProfileService.updateDoctorBio(doctorId, bio);
        return ResponseEntity.ok(updatedProfile);
    }

    // Toggle My Active Status
    @PatchMapping("/status/toggle")
    public ResponseEntity<DoctorProfileResponse> toggleMyStatus(
            @RequestAttribute("userId") Integer userId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.toggleDoctorStatus(userId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorId}/status/toggle")
    public ResponseEntity<DoctorProfileResponse> toggleDoctorStatus(@PathVariable Integer doctorId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.toggleDoctorStatus(doctorId);
        return ResponseEntity.ok(updatedProfile);
    }

    // Activate My Account
    @PatchMapping("/status/activate")
    public ResponseEntity<DoctorProfileResponse> activateMyAccount(
            @RequestAttribute("userId") Integer userId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.activateDoctor(userId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorId}/status/activate")
    public ResponseEntity<DoctorProfileResponse> activateDoctorAccount(@PathVariable Integer doctorId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.activateDoctor(doctorId);
        return ResponseEntity.ok(updatedProfile);
    }

    // Deactivate My Account
    @PatchMapping("/status/deactivate")
    public ResponseEntity<DoctorProfileResponse> deactivateMyAccount(
            @RequestAttribute("userId") Integer userId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.deactivateDoctor(userId);
        return ResponseEntity.ok(updatedProfile);
    }

    @PatchMapping("/{doctorId}/status/deactivate")
    public ResponseEntity<DoctorProfileResponse> deactivateDoctorAccount(@PathVariable Integer doctorId) {
        DoctorProfileResponse updatedProfile = doctorProfileService.deactivateDoctor(doctorId);
        return ResponseEntity.ok(updatedProfile);
    }

    // Get All Active Doctors
    @GetMapping("/list/active")
    public ResponseEntity<List<DoctorProfileResponse>> getAllActiveDoctors() {
        List<DoctorProfileResponse> doctors = doctorProfileService.getAllActiveDoctorsProfiles();
        return ResponseEntity.ok(doctors);
    }

    // Search Doctors by Specialization
    @GetMapping("/search/specialization")
    public ResponseEntity<List<DoctorProfileResponse>> searchBySpecialization(
            @RequestParam String specialization) {
        List<DoctorProfileResponse> doctors = doctorProfileService.searchDoctorsBySpecialization(specialization);
        return ResponseEntity.ok(doctors);
    }

    // Search Doctors by Name/Email
    @GetMapping("/search/name")
    public ResponseEntity<List<DoctorProfileResponse>> searchByName(@RequestParam String query) {
        List<DoctorProfileResponse> doctors = doctorProfileService.searchDoctorsByName(query);
        return ResponseEntity.ok(doctors);
    }

    // Get Doctors by Specialization with Filter
    @GetMapping("/filter/specialization")
    public ResponseEntity<List<DoctorProfileResponse>> filterBySpecialization(
            @RequestParam String specialization,
            @RequestParam(required = false) Boolean isActive) {
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(specialization, isActive);
        return ResponseEntity.ok(doctors);
    }

    // Get Doctors by Active Status
    @GetMapping("/filter/active")
    public ResponseEntity<List<DoctorProfileResponse>> filterByActiveStatus(
            @RequestParam Boolean isActive) {
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(null, isActive);
        return ResponseEntity.ok(doctors);
    }

    // Get Available Doctors (Active Only)
    @GetMapping("/available")
    public ResponseEntity<List<DoctorProfileResponse>> getAvailableDoctors() {
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(null, true);
        return ResponseEntity.ok(doctors);
    }

    // Get Unavailable Doctors (Inactive Only)
    @GetMapping("/unavailable")
    public ResponseEntity<List<DoctorProfileResponse>> getUnavailableDoctors() {
        List<DoctorProfileResponse> doctors = doctorProfileService.getDoctorsBySpecializationAndActive(null, false);
        return ResponseEntity.ok(doctors);
    }

    // Public Endpoint - Get Doctor Info (without sensitive data)
    @GetMapping("/public/{doctorId}")
    public ResponseEntity<DoctorProfileResponse> getPublicDoctorProfile(@PathVariable Integer doctorId) {
        DoctorProfileResponse profile = doctorProfileService.getDoctorProfileById(doctorId);
        return ResponseEntity.ok(profile);
    }
}
