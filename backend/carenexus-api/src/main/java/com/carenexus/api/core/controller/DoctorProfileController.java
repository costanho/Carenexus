package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.core.dto.request.UpdateDoctorProfileRequest;
import com.carenexus.api.core.dto.response.DoctorProfileResponse;
import com.carenexus.api.core.service.DoctorProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Doctor profiles and the doctor directory. Authorization lives in {@link DoctorProfileService}:
 * - own profile (no path id): the calling DOCTOR;
 * - /{doctorSystemId}: read by that doctor or an ADMIN, write by an ADMIN only;
 * - directory, list/active and public/{id}: any signed-in user, public fields of listed doctors only.
 * Activation and clinician verification are administrator actions of the admin API, not of this controller.
 * The literal path /directory wins over /{doctorSystemId} because Spring prefers the more specific mapping.
 */
@RestController
@RequestMapping("/api/doctors/profile")
@RequiredArgsConstructor
@Validated
@Tag(name = "Doctor profile and directory", description = "A doctor's own profile, administrator edits, and the "
        + "directory of verified, active doctors")
public class DoctorProfileController {

    private final DoctorProfileService doctorProfileService;

    // ==================== SELF ====================

    @GetMapping
    @Operation(summary = "Get my doctor profile",
            description = "DOCTOR only. Full view with counters (patients, appointments, consultations, "
                    + "prescriptions, medical records). Not audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The doctor profile")
    public ResponseEntity<DoctorProfileResponse> getMyProfile() {
        return ResponseEntity.ok(doctorProfileService.getMyProfile());
    }

    @PutMapping
    @Operation(summary = "Update my doctor profile",
            description = "DOCTOR only. Partial update: only supplied fields change. The sign-in email cannot be "
                    + "changed here (use the email-change flow), the licence number only by an administrator, and "
                    + "activation never. Audited as PROFILE_UPDATE / DOCTOR_PROFILE.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The updated doctor profile")
    public ResponseEntity<DoctorProfileResponse> updateMyProfile(@Valid @RequestBody UpdateDoctorProfileRequest request) {
        return ResponseEntity.ok(doctorProfileService.updateMyProfile(request));
    }

    // ==================== DIRECTORY (any signed-in user) ====================

    @GetMapping("/directory")
    @Operation(summary = "Search the doctor directory (paged)",
            description = "Any signed-in user. Only verified (APPROVED), active doctors with an active account. "
                    + "Ordered by last name, first name and doctor id. Public fields only. Not audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of doctor profile rows")
    public ResponseEntity<PageResponse<DoctorProfileResponse>> getDirectory(
            @Parameter(description = "Case-insensitive 'contains' on first name, last name, full name or specialization")
            @RequestParam(required = false)
            @Size(max = 100, message = "Search text must be at most 100 characters") String query,
            @Parameter(description = "Case-insensitive 'contains' on specialization")
            @RequestParam(required = false)
            @Size(max = 150, message = "Specialization must be at most 150 characters") String specialization,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(doctorProfileService.getDirectory(query, specialization, page, size));
    }

    /**
     * @deprecated use GET /api/doctors/profile/directory.
     */
    @Deprecated
    @GetMapping("/list/active")
    @Operation(summary = "List listed doctors as an array (deprecated)", deprecated = true,
            description = "Any signed-in user. The first 200 rows of the directory without filters, as a bare array "
                    + "in the public view. Use /directory instead.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The doctor profile list (JSON array)")
    public ResponseEntity<List<DoctorProfileResponse>> getAllActiveDoctors() {
        return ResponseEntity.ok(doctorProfileService.getAllActiveDoctorsProfiles());
    }

    @GetMapping("/public/{doctorSystemId}")
    @Operation(summary = "Get a doctor's public profile",
            description = "Any signed-in user. A listed doctor: public view with isActive true. A doctor who is not "
                    + "listed (pending, rejected, suspended, deactivated or closed): name only with isActive false, "
                    + "meaning not bookable. 404 only when no such doctor exists.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The doctor profile")
    public ResponseEntity<DoctorProfileResponse> getPublicDoctorProfile(
            @Parameter(description = "Doctor system id", example = "DOC-00002") @PathVariable String doctorSystemId) {
        return ResponseEntity.ok(doctorProfileService.getPublicDoctorProfile(doctorSystemId));
    }

    // ==================== BY ID (self or admin read, admin write) ====================

    @GetMapping("/{doctorSystemId}")
    @Operation(summary = "Get a doctor's full profile",
            description = "That DOCTOR or an ADMIN. Full view with counters. Not audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The doctor profile")
    public ResponseEntity<DoctorProfileResponse> getDoctorProfile(
            @Parameter(description = "Doctor system id", example = "DOC-00002") @PathVariable String doctorSystemId) {
        return ResponseEntity.ok(doctorProfileService.getDoctorProfileById(doctorSystemId));
    }

    @PutMapping("/{doctorSystemId}")
    @Operation(summary = "Update a doctor's profile (administrator)",
            description = "ADMIN only. Partial update as PUT /api/doctors/profile; a different licence number is "
                    + "applied (409 when another doctor holds it). The email cannot be changed here. Audited as "
                    + "PROFILE_UPDATE / DOCTOR_PROFILE.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The updated doctor profile")
    public ResponseEntity<DoctorProfileResponse> updateDoctorProfile(
            @Parameter(description = "Doctor system id", example = "DOC-00002") @PathVariable String doctorSystemId,
            @Valid @RequestBody UpdateDoctorProfileRequest request) {
        return ResponseEntity.ok(doctorProfileService.updateDoctorProfile(doctorSystemId, request, true));
    }
}
