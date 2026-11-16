package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.CreateDependentRequest;
import com.carenexus.api.core.dto.request.InviteDependentRequest;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.dto.response.CaregiverDependentActivityPageResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentActivityResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.service.CaregiverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Caregiver (proxy) side of caregiver access. Role CAREGIVER only (route rule and controller check); the caregiver is
 * always the caller from the JWT.
 *
 * - GET   /api/caregiver/dependents                       active dependents (array, cap 200)
 * - GET   /api/caregiver/dependents/activity              appointments + consultations the grants allow (window, paged)
 * - POST  /api/caregiver/dependents                       create a dependent patient account + grant (atomic, 201)
 * - PATCH /api/caregiver/dependents/{accessId}            narrow own grant only (403 on any widening)
 * - POST  /api/caregiver/dependents/{accessId}/invite     invite a managed dependent to sign in
 *
 * The patient side (and the deprecated /api/patients/me/caregivers routes) is in PatientCaregiverAccessController.
 * Communication permissions (canMessageCareTeam, canJoinCalls) are never raised by a caregiver: createDependent
 * stores them as unapproved requests and the caregiver PATCH rejects turning them on.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Caregiver", description = "A caregiver's dependents: grants, activity feed, adding and inviting dependents")
public class CaregiverController {

    static final String CREATE_SCOPE = "POST /api/caregiver/dependents";
    private static final Set<String> ACTIVITY_PARAMETERS = Set.of("from", "to", "page", "size");

    private final CaregiverService caregiverService;
    private final AuthorizationService authorizationService;
    private final IdempotencyService idempotencyService;

    @GetMapping("/api/caregiver/dependents")
    @Operation(summary = "List the caller's active dependents",
            description = "CAREGIVER only. The caller's ACTIVE grants (expired and revoked grants are left out), oldest "
                    + "grant first, at most 200. Health fields are filled only when the grant gives records. "
                    + "Audited as a PROFILE list read per dependent.")
    @ApiResponse(responseCode = "200", description = "The grants",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = CaregiverDependentResponse.class))))
    @StandardErrors
    public ResponseEntity<List<CaregiverDependentResponse>> getDependents(
            @RequestAttribute("systemId") String systemId) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        return ResponseEntity.ok(caregiverService.getDependentsForCaregiver(systemId));
    }

    @GetMapping("/api/caregiver/dependents/activity")
    @Operation(summary = "List appointments and consultations of the caller's dependents",
            description = "CAREGIVER only. Appointments of dependents whose active grant gives appointments (filtered on "
                    + "scheduledAt) and finished consultations of those whose grant gives consultations (filtered on "
                    + "createdAt), newest first; other dependents are left out. With any of from, to, page, size the "
                    + "response is CaregiverDependentActivityPageResponse (window default: clinic today minus 90 days, "
                    + "open end). Without parameters the deprecated legacy form is returned: the default window and at "
                    + "most 200 rows per list. Audited as APPOINTMENT and CONSULTATION list reads.")
    @ApiResponse(responseCode = "200", description = "Paged form, or the legacy form when no parameter is sent",
            content = @Content(schema = @Schema(oneOf = {CaregiverDependentActivityPageResponse.class,
                    CaregiverDependentActivityResponse.class})))
    @StandardErrors
    public ResponseEntity<Object> getDependentActivity(
            @RequestAttribute("systemId") String systemId,
            @Parameter(description = "Window start, clinic date YYYY-MM-DD (inclusive); default today minus 90 days")
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Window end, clinic date YYYY-MM-DD (inclusive); default open")
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Zero-based page, shared by both lists (default 0)")
            @RequestParam(value = "page", required = false) Integer page,
            @Parameter(description = "Page size, clamped to 1..100 (default 20)")
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        boolean paged = request.getParameterMap().keySet().stream().anyMatch(ACTIVITY_PARAMETERS::contains);
        if (!paged) {
            return ResponseEntity.ok(caregiverService.getActivityForDependents(systemId));
        }
        return ResponseEntity.ok(caregiverService.getActivityPage(systemId, from, to, page, size));
    }

    @PostMapping("/api/caregiver/dependents")
    @Operation(summary = "Create a dependent patient account with the caller's grant",
            description = "CAREGIVER with a caregiver profile. Creates the patient account, the patients row and the "
                    + "caller's grant (authorised by the caller) in one transaction; any failure leaves no row. With "
                    + "email and password the patient can sign in; without both the account is managed (cannot sign "
                    + "in until invited) and guardianAttested must be true. A phone another account uses is stored as "
                    + "the contact phone. Accepts Idempotency-Key: a repeat within 24 h returns the stored response "
                    + "with 200. Publishes UserRegisteredEvent and CaregiverGrantRecordedEvent(GRANTED); audited "
                    + "CREATE DEPENDENT_ACCESS.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(schema = @Schema(implementation = CaregiverDependentResponse.class))),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of an earlier create",
                    content = @Content(schema = @Schema(implementation = CaregiverDependentResponse.class)))
    })
    @StandardErrors
    public ResponseEntity<CaregiverDependentResponse> createDependent(
            @RequestAttribute("systemId") String systemId,
            @Parameter(description = "Optional key (8-64 characters [A-Za-z0-9_-]) that makes retries safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateDependentRequest request) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        IdempotentResult<CaregiverDependentResponse> result = idempotencyService.execute(CREATE_SCOPE, idempotencyKey,
                CaregiverDependentResponse.class, () -> caregiverService.createDependent(systemId, request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @PatchMapping("/api/caregiver/dependents/{accessId}")
    @Operation(summary = "Narrow the caller's own grant",
            description = "CAREGIVER holding the grant, while it is active. The caregiver may only reduce access: any "
                    + "change that grants an effective permission that was not granted before, turning a communication "
                    + "flag on, a later expiry or removing the expiry is refused with 403. Flags are normalised to the "
                    + "level. Publishes CaregiverGrantRecordedEvent(NARROWED) when something changed; audited UPDATE "
                    + "DEPENDENT_ACCESS.")
    @ApiResponse(responseCode = "200", description = "The grant after the change",
            content = @Content(schema = @Schema(implementation = CaregiverDependentResponse.class)))
    @StandardErrors
    public ResponseEntity<CaregiverDependentResponse> updateDependentAccess(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId,
            @Valid @RequestBody UpdateDependentAccessRequest request) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        return ResponseEntity.ok(caregiverService.updateDependentAccess(systemId, accessId, request));
    }

    @PostMapping("/api/caregiver/dependents/{accessId}/invite")
    @Operation(summary = "Invite a managed dependent to sign in",
            description = "CAREGIVER who holds the grant and created the dependent, while the grant is active and the "
                    + "patient's account has no password. Stores the email on the patient's account and has the "
                    + "account module mail a set-password link (AdminPasswordResetRequestedEvent). Repeatable: re-sends "
                    + "the link and may correct the email. Audited as account event DEPENDENT_INVITE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invitation sent; the grant with the stored email",
                    content = @Content(schema = @Schema(implementation = CaregiverDependentResponse.class))),
            @ApiResponse(responseCode = "409", description = "The person already has sign-in details, or the email "
                    + "is used by another account",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @StandardErrors
    public ResponseEntity<CaregiverDependentResponse> inviteDependent(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId,
            @Valid @RequestBody InviteDependentRequest request) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        return ResponseEntity.ok(caregiverService.inviteDependent(systemId, accessId, request));
    }
}
