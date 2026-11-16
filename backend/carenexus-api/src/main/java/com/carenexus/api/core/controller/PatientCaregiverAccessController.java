package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.GrantCaregiverAccessRequest;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The patient's own caregiver grants ("My caregivers"). The patient is always the caller from the JWT.
 *
 * - GET    /api/patients/me/caregiver-access             every unrevoked grant (active and expired), newest first
 * - POST   /api/patients/me/caregiver-access             grant an existing caregiver account access (201)
 * - PATCH  /api/patients/me/caregiver-access/{accessId}  change permissions / level / expiry; turning on
 *                                                        messaging or joining calls approves it
 * - DELETE /api/patients/me/caregiver-access/{accessId}  revoke (204, idempotent); ADMIN may revoke any grant
 *
 * Deprecated duplicates (kept with their shapes while native or e2e callers may exist; same service methods):
 * - GET    /api/patients/me/caregivers             ACTIVE grants only, newest first, cap 200
 * - PATCH  /api/patients/me/caregivers/{accessId}  as the PATCH above; ADMIN may change any grant (never approves)
 * - DELETE /api/patients/me/caregivers/{accessId}  as the DELETE above
 *
 * Caregivers can never raise their own access; they use PATCH /api/caregiver/dependents/{accessId} (narrow only).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Patient caregiver access", description = "The patient's caregiver grants: list, grant, change, revoke")
public class PatientCaregiverAccessController {

    static final String BASE = "/api/patients/me/caregiver-access";
    static final String LEGACY_BASE = "/api/patients/me/caregivers";
    static final String GRANT_SCOPE = "POST " + BASE;

    private final CaregiverService caregiverService;
    private final AuthorizationService authorizationService;
    private final IdempotencyService idempotencyService;

    @GetMapping(BASE)
    @Operation(summary = "List the caller's caregiver grants",
            description = "PATIENT only. Every grant of the caller that is not revoked (ACTIVE and EXPIRED), newest "
                    + "first, at most 200. Audited as a PROFILE list read.")
    @ApiResponse(responseCode = "200", description = "The grants",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = CaregiverDependentResponse.class))))
    @StandardErrors
    public ResponseEntity<List<CaregiverDependentResponse>> list(@RequestAttribute("systemId") String systemId) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return ResponseEntity.ok(caregiverService.getCaregiverAccessForPatient(systemId));
    }

    @PostMapping(BASE)
    @Operation(summary = "Grant an existing caregiver account access",
            description = "PATIENT only. Grants the active caregiver account with that email access; communication "
                    + "flags sent true are approved at once. A revoked grant for the same caregiver is reused. Flags "
                    + "are normalised to the level. Accepts Idempotency-Key (repeat within 24 h: stored response, "
                    + "200). Publishes CaregiverGrantRecordedEvent(GRANTED); audited CREATE DEPENDENT_ACCESS.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Granted",
                    content = @Content(schema = @Schema(implementation = CaregiverDependentResponse.class))),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of an earlier grant",
                    content = @Content(schema = @Schema(implementation = CaregiverDependentResponse.class)))
    })
    @StandardErrors
    public ResponseEntity<CaregiverDependentResponse> grant(
            @RequestAttribute("systemId") String systemId,
            @Parameter(description = "Optional key (8-64 characters [A-Za-z0-9_-]) that makes retries safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) GrantCaregiverAccessRequest request) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        IdempotentResult<CaregiverDependentResponse> result = idempotencyService.execute(GRANT_SCOPE, idempotencyKey,
                CaregiverDependentResponse.class, () -> caregiverService.grantCaregiverAccessAsPatient(systemId, request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @PatchMapping(BASE + "/{accessId}")
    @Operation(summary = "Change a caregiver's grant",
            description = "PATIENT only, on an unrevoked grant of their own. Absent fields are unchanged; expiresAt \"\" "
                    + "removes the expiry, a later value extends (also an expired grant); a communication flag sent "
                    + "true is approved. Flags are normalised to the level. Publishes one CaregiverGrantRecordedEvent "
                    + "(COMMUNICATION_APPROVED, EXTENDED or PERMISSIONS_CHANGED) when something changed; audited "
                    + "UPDATE DEPENDENT_ACCESS.")
    @ApiResponse(responseCode = "200", description = "The grant after the change",
            content = @Content(schema = @Schema(implementation = CaregiverDependentResponse.class)))
    @StandardErrors
    public ResponseEntity<CaregiverDependentResponse> update(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId,
            @Valid @RequestBody(required = false) UpdateDependentAccessRequest request) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return ResponseEntity.ok(caregiverService.updateCaregiverAccessAsPatient(systemId, false, accessId, request));
    }

    @DeleteMapping(BASE + "/{accessId}")
    @Operation(summary = "Revoke a caregiver's grant",
            description = "The PATIENT who owns the grant, or an ADMIN (any grant). Idempotent (a revoked grant answers "
                    + "204 again). Clears both communication approvals; the caregiver is removed from the patient's "
                    + "conversations and calls. Publishes CaregiverGrantRecordedEvent(REVOKED) on the first revoke; "
                    + "audited DELETE DEPENDENT_ACCESS.")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @StandardErrors
    public ResponseEntity<Void> revoke(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.ADMIN);
        caregiverService.revokeCaregiverAccessAsPatient(systemId, authorizationService.isAdmin(), accessId);
        return ResponseEntity.noContent().build();
    }

    // ==================== DEPRECATED DUPLICATES ====================

    /** @deprecated use GET /api/patients/me/caregiver-access. */
    @Deprecated
    @GetMapping(LEGACY_BASE)
    @Operation(summary = "List the caller's active caregiver grants (deprecated)", deprecated = true,
            description = "PATIENT only. The caller's ACTIVE grants, newest first, at most 200. Use GET " + BASE + ".")
    @ApiResponse(responseCode = "200", description = "The grants",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = CaregiverDependentResponse.class))))
    @StandardErrors
    public ResponseEntity<List<CaregiverDependentResponse>> getMyCaregivers(
            @RequestAttribute("systemId") String systemId) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return ResponseEntity.ok(caregiverService.getCaregiversForPatient(systemId));
    }

    /** @deprecated use PATCH /api/patients/me/caregiver-access/{accessId}. */
    @Deprecated
    @PatchMapping(LEGACY_BASE + "/{accessId}")
    @Operation(summary = "Change a caregiver's grant (deprecated)", deprecated = true,
            description = "PATIENT (own grant) or ADMIN (any grant). Same body, response and errors as PATCH " + BASE
                    + "/{accessId}; an ADMIN change never approves a communication flag (true stores a pending request).")
    @ApiResponse(responseCode = "200", description = "The grant after the change",
            content = @Content(schema = @Schema(implementation = CaregiverDependentResponse.class)))
    @StandardErrors
    public ResponseEntity<CaregiverDependentResponse> updateMyCaregiverAccess(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId,
            @Valid @RequestBody(required = false) UpdateDependentAccessRequest request) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.ADMIN);
        return ResponseEntity.ok(caregiverService.updateCaregiverAccessAsPatient(
                systemId, authorizationService.isAdmin(), accessId, request));
    }

    /** @deprecated use DELETE /api/patients/me/caregiver-access/{accessId}. */
    @Deprecated
    @DeleteMapping(LEGACY_BASE + "/{accessId}")
    @Operation(summary = "Revoke a caregiver's grant (deprecated)", deprecated = true,
            description = "Identical to DELETE " + BASE + "/{accessId}.")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @StandardErrors
    public ResponseEntity<Void> revokeMyCaregiverAccess(
            @RequestAttribute("systemId") String systemId,
            @PathVariable Integer accessId) {
        return revoke(systemId, accessId);
    }
}
