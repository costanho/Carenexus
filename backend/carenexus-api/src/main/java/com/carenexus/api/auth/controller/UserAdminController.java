package com.carenexus.api.auth.controller;

import com.carenexus.api.admin.audit.dto.AuditLogResponse;
import com.carenexus.api.admin.clinician.ClinicianVerificationService;
import com.carenexus.api.admin.clinician.dto.request.LicenceRequest;
import com.carenexus.api.admin.clinician.dto.response.AdminDoctorResponse;
import com.carenexus.api.admin.user.UserAdminService;
import com.carenexus.api.admin.user.dto.request.CreateAdminRequest;
import com.carenexus.api.admin.user.dto.request.ReasonRequest;
import com.carenexus.api.admin.user.dto.response.AdminUserActionResponse;
import com.carenexus.api.admin.user.dto.response.AdminUserDetailResponse;
import com.carenexus.api.admin.user.dto.response.AdminUserResponse;
import com.carenexus.api.common.annotation.RequireAdmin;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator account controls and clinician verification. ADMIN only: the route rule for {@code /api/admin/**}
 * answers 401/403 before this controller, and every service method checks the role again. Thin: the rules live in
 * {@link UserAdminService} and {@link ClinicianVerificationService}.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin: users and clinicians",
        description = "Administrator account controls (search, create administrators, activate, deactivate, force "
                + "sign-out, password-reset links, per-account audit) and clinician licence verification")
public class UserAdminController {

    static final String CREATE_SCOPE = "POST /api/admin/users";

    private final UserAdminService userAdminService;
    private final ClinicianVerificationService clinicianVerificationService;
    private final IdempotencyService idempotencyService;

    // ==================== USERS ====================

    @GetMapping("/users")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Search accounts",
            description = "ADMIN only. Paged, newest account first. q matches the full name, email and system id "
                    + "(case-insensitive, contains); role accepts PATIENT, DOCTOR, CAREGIVER, ADMIN (PROXY = CAREGIVER); "
                    + "active filters on the active flag. Not audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of admin user rows")
    public ResponseEntity<PageResponse<AdminUserResponse>> getAllUsers(
            @Parameter(description = "Free text, at most 100 characters")
            @RequestParam(required = false) @Size(max = 100, message = "Search text must be at most 100 characters") String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userAdminService.listUsers(q, role, active, page, size));
    }

    @GetMapping("/users/{systemId}")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Get one account with its status details",
            description = "ADMIN only. Active, inactive and closed accounts are readable. Not audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The account with its role profile")
    public ResponseEntity<AdminUserDetailResponse> getUser(@PathVariable String systemId) {
        return ResponseEntity.ok(userAdminService.getUser(systemId));
    }

    @PostMapping("/users")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Create an administrator with a temporary password",
            description = "ADMIN only. Creates an active ADMIN account whose password must be changed at the first "
                    + "sign-in; no email is sent. Optional Idempotency-Key: a repeat returns the first response with "
                    + "200. Audited USER_CREATE.")
    @ApiResponse(responseCode = "201", description = "Administrator created")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of an earlier create")
    @StandardErrors
    public ResponseEntity<AdminUserDetailResponse> createAdmin(
            @Parameter(description = "8 to 64 characters of letters, digits, '_' or '-'")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateAdminRequest request) {
        IdempotentResult<AdminUserDetailResponse> result = idempotencyService.execute(CREATE_SCOPE, idempotencyKey,
                AdminUserDetailResponse.class, () -> userAdminService.createAdmin(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @PostMapping("/users/{systemId}/activate")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Reactivate or re-open an account",
            description = "ADMIN only. Re-activates a deactivated account or re-opens one its owner closed; a "
                    + "suspended doctor is approved again. Refused for anonymised accounts (409 ACCOUNT_CLOSED) and "
                    + "for doctors that are pending or rejected (409 DOCTOR_NOT_VERIFIED). A repeat is a no-op. "
                    + "Audited USER_ACTIVATE; publishes UserReactivatedEvent. No request body.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The account after activation")
    public ResponseEntity<AdminUserActionResponse> activateUser(@PathVariable String systemId) {
        return ResponseEntity.ok(userAdminService.activate(systemId));
    }

    @PostMapping("/users/{systemId}/deactivate")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Deactivate an account and sign it out everywhere",
            description = "ADMIN only. Requires a reason (shown to administrators only). Not allowed on the caller's "
                    + "own account or the last active administrator. An approved doctor becomes SUSPENDED. A repeat "
                    + "on an inactive account is a no-op. Audited USER_DEACTIVATE; publishes UserDeactivatedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The account after deactivation")
    public ResponseEntity<AdminUserActionResponse> deactivateUser(@PathVariable String systemId,
                                                                  @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(userAdminService.deactivate(systemId, request.reason()));
    }

    @PostMapping("/users/{systemId}/sessions/revoke")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Sign an account out everywhere",
            description = "ADMIN only. Revokes every refresh token; the account stays active and an issued access "
                    + "token works until it expires. Safe to repeat. Audited SESSIONS_REVOKE. No request body.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The account after its sessions were ended")
    public ResponseEntity<AdminUserActionResponse> revokeSessions(@PathVariable String systemId) {
        return ResponseEntity.ok(userAdminService.revokeSessions(systemId));
    }

    @PostMapping("/users/{systemId}/password-reset")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Send the account a link to set a new password",
            description = "ADMIN only. Sends a reset link (an invitation when the account has no password yet); never "
                    + "sets or returns a password and does not end sessions. 409 for a closed account or one without "
                    + "an email address. No request body.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The account; the reset link was sent")
    public ResponseEntity<AdminUserActionResponse> requestPasswordReset(@PathVariable String systemId) {
        return ResponseEntity.ok(userAdminService.requestPasswordReset(systemId));
    }

    @GetMapping("/users/{systemId}/audit-logs")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "List audit rows concerning one account",
            description = "ADMIN only. Rows where the account is the actor, the target or the patient; newest first. "
                    + "The read is audited (READ AUDIT_LOG).")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of audit log rows")
    public ResponseEntity<PageResponse<AuditLogResponse>> getUserAuditLogs(
            @PathVariable String systemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userAdminService.userAuditLogs(systemId, page, size));
    }

    // ==================== CLINICIANS ====================

    @GetMapping("/doctors/pending")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "List clinicians waiting for verification",
            description = "ADMIN only. Doctors with verification status PENDING, oldest registration first (the "
                    + "same as GET /api/admin/doctors?status=PENDING). Not audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of admin doctor rows")
    public ResponseEntity<PageResponse<AdminDoctorResponse>> getPendingDoctors(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(clinicianVerificationService.listPending(page, size));
    }

    @GetMapping("/doctors")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "List clinicians by verification status",
            description = "ADMIN only. status: PENDING, APPROVED, REJECTED or SUSPENDED (any case); absent = all. "
                    + "Oldest registration first. Not audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of admin doctor rows")
    public ResponseEntity<PageResponse<AdminDoctorResponse>> getDoctors(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(clinicianVerificationService.listByStatus(status, page, size));
    }

    @PostMapping("/doctors/{systemId}/verify")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Approve a clinician",
            description = "ADMIN only. From PENDING, REJECTED or SUSPENDED; needs a licence number on file (409 "
                    + "LICENCE_REQUIRED) and an open account (409 ACCOUNT_CLOSED). Activates the account, emails and "
                    + "notifies the clinician. An approved clinician is returned unchanged. Audited DOCTOR_VERIFY.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The doctor after verification")
    public ResponseEntity<AdminDoctorResponse> verifyDoctor(@PathVariable String systemId) {
        return ResponseEntity.ok(clinicianVerificationService.verify(systemId));
    }

    @PostMapping("/doctors/{systemId}/reject")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Reject a pending clinician registration",
            description = "ADMIN only. Only from PENDING (409 otherwise); the reason is emailed to the clinician. A "
                    + "rejected clinician is returned unchanged. Audited DOCTOR_REJECT.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The doctor after rejection")
    public ResponseEntity<AdminDoctorResponse> rejectDoctor(@PathVariable String systemId,
                                                            @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(clinicianVerificationService.reject(systemId, request.reason()));
    }

    @PutMapping("/doctors/{systemId}/licence")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Enter or correct a clinician's licence details",
            description = "ADMIN only. Replaces licence number, licensing body, country and specialization; allowed "
                    + "in every verification status and never changes it. 409 when another doctor holds the licence "
                    + "number. Audited LICENCE_UPDATE.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The doctor with the corrected licence")
    public ResponseEntity<AdminDoctorResponse> updateLicence(@PathVariable String systemId,
                                                             @Valid @RequestBody LicenceRequest request) {
        return ResponseEntity.ok(clinicianVerificationService.updateLicence(systemId, request));
    }
}
