package com.carenexus.api.careteam.controller;

import com.carenexus.api.careteam.dto.request.AssignCareTeamMemberRequest;
import com.carenexus.api.careteam.dto.request.UpdateCareTeamMemberRequest;
import com.carenexus.api.careteam.dto.response.CareTeamMemberResponse;
import com.carenexus.api.careteam.service.CareTeamService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Care team management.
 *
 * - GET    /api/patients/{patientSystemId}/care-team               a patient's care team (assigned + derived)
 * - GET    /api/patients/me/care-team                              the signed-in patient's own care team
 * - POST   /api/patients/{patientSystemId}/care-team               assign a member (Idempotency-Key)
 * - PATCH  /api/patients/{patientSystemId}/care-team/{careTeamId}  change role and/or primary
 * - DELETE /api/patients/{patientSystemId}/care-team/{careTeamId}  unassign (soft)
 * - GET    /api/doctors/care-team                                  the calling doctor's assignments (paged)
 *
 * Authorization and audit happen in {@link CareTeamService}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Care team", description = "Who looks after a patient: assigned doctors and external providers, the "
        + "primary member, and doctors related through appointments or consultations")
public class CareTeamController {

    static final String ASSIGN_SCOPE_PREFIX = "POST /api/patients/";
    static final String ASSIGN_SCOPE_SUFFIX = "/care-team";
    /** idempotency_keys.scope holds at most 160 characters (IdempotencyService). */
    static final int MAX_SCOPE_LENGTH = 160;

    private final CareTeamService careTeamService;
    private final IdempotencyService idempotencyService;

    @GetMapping("/api/patients/me/care-team")
    @Operation(summary = "List my care team",
            description = "PATIENT only. The caller's active assigned members (primary first, then oldest "
                    + "assignment) followed by doctors related only through a non-cancelled appointment or a "
                    + "consultation (source DERIVED, by name). Plain array, at most 100 assigned plus 100 derived "
                    + "rows. Audited as one READ CARE_TEAM row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The care team (possibly empty)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<CareTeamMemberResponse>> listMine() {
        return ResponseEntity.ok(careTeamService.listMine());
    }

    @GetMapping("/api/patients/{patientSystemId}/care-team")
    @Operation(summary = "List a patient's care team",
            description = "The patient (own id), a doctor related to the patient, a caregiver with any active grant "
                    + "(read-only), or an admin. Active assigned members (primary first, then oldest assignment) "
                    + "followed by doctors related only through a non-cancelled appointment or a consultation "
                    + "(source DERIVED, by name). Plain array, at most 100 assigned plus 100 derived rows. Audited as "
                    + "one READ CARE_TEAM row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The care team (possibly empty)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No access to this patient (other patient, unrelated "
                    + "doctor, caregiver without an active grant)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Patient not found (admins only)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<CareTeamMemberResponse>> listForPatient(
            @Parameter(description = "The patient's system id", example = "PAT-00001") @PathVariable String patientSystemId) {
        return ResponseEntity.ok(careTeamService.listForPatient(patientSystemId));
    }

    @PostMapping("/api/patients/{patientSystemId}/care-team")
    @Operation(summary = "Assign a care team member",
            description = "A doctor related to the patient, or an admin. Either a CareNexus doctor (providerSystemId: "
                    + "an active, approved doctor, role DOCTOR or SPECIALIST) or an external provider by name (any of "
                    + "DOCTOR, NURSE, LAB, PHARMACY, SPECIALIST, THERAPIST). isPrimary true replaces the previous "
                    + "primary. A doctor account gains access to the patient's data. Notifies the patient (SYSTEM, "
                    + "access change). Audited (CREATE CARE_TEAM, plus UPDATE for a demoted primary). Optional "
                    + "Idempotency-Key: a replay returns 200 with the first response and writes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Member assigned"),
            @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key"),
            @ApiResponse(responseCode = "400", description = "Invalid body (code VALIDATION_FAILED), malformed "
                    + "Idempotency-Key, or a business rule (provider, role, facility)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Patient, caregiver, or a doctor not related to the patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Patient not found (admins only)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The provider is already on the care team, or the same "
                    + "Idempotency-Key is still being processed (code IDEMPOTENCY_IN_PROGRESS)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CareTeamMemberResponse> assign(
            @Parameter(description = "The patient's system id", example = "PAT-00001") @PathVariable String patientSystemId,
            @Parameter(in = ParameterIn.HEADER, name = IdempotencyService.HEADER,
                    description = "Optional, 8 to 64 characters of letters, digits, '_' or '-'")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AssignCareTeamMemberRequest request) {
        String scope = ASSIGN_SCOPE_PREFIX + patientSystemId + ASSIGN_SCOPE_SUFFIX;
        if (scope.length() > MAX_SCOPE_LENGTH) {
            // No real system id is this long: let the service refuse it (403 / 404) without storing a key.
            return ResponseEntity.status(HttpStatus.CREATED).body(careTeamService.assign(patientSystemId, request));
        }
        IdempotentResult<CareTeamMemberResponse> result = idempotencyService.execute(scope, idempotencyKey,
                CareTeamMemberResponse.class, () -> careTeamService.assign(patientSystemId, request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @PatchMapping("/api/patients/{patientSystemId}/care-team/{careTeamId}")
    @Operation(summary = "Change a care team member's role or primary flag",
            description = "A doctor related to the patient or an admin may change role and isPrimary; the patient "
                    + "(own id) may only set or clear isPrimary. isPrimary true replaces the previous primary in the "
                    + "same transaction. A request that changes nothing returns the current state. Audited (UPDATE "
                    + "CARE_TEAM per changed member). No notification.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The member after the change"),
            @ApiResponse(responseCode = "400", description = "Invalid role (code VALIDATION_FAILED), malformed or empty "
                    + "body, or a role a doctor account cannot hold",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caregiver, another patient, an unrelated doctor, or a "
                    + "patient changing the role",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No active member with this id for this patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CareTeamMemberResponse> update(
            @Parameter(description = "The patient's system id", example = "PAT-00001") @PathVariable String patientSystemId,
            @Parameter(description = "The member's careTeamId", example = "57") @PathVariable Integer careTeamId,
            @Valid @RequestBody UpdateCareTeamMemberRequest request) {
        return ResponseEntity.ok(careTeamService.update(patientSystemId, careTeamId, request));
    }

    @DeleteMapping("/api/patients/{patientSystemId}/care-team/{careTeamId}")
    @Operation(summary = "Remove a care team member",
            description = "The patient (own id), the doctor who is the member, or an admin. Soft delete (inactive, "
                    + "unassigned time recorded); repeating it returns 204 again. A removed doctor keeps access only "
                    + "through appointments or consultations. Audited (DELETE CARE_TEAM). No notification.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Member removed (or already removed)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caregiver, another patient, or a doctor who is not the member",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No member with this id for this patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> remove(
            @Parameter(description = "The patient's system id", example = "PAT-00001") @PathVariable String patientSystemId,
            @Parameter(description = "The member's careTeamId", example = "57") @PathVariable Integer careTeamId) {
        careTeamService.remove(patientSystemId, careTeamId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/doctors/care-team")
    @Operation(summary = "List my care team assignments",
            description = "DOCTOR only (an admin gets 403). The caller's active assignments, newest first, in the "
                    + "standard page envelope; size is clamped to 1..100. Audited as one READ CARE_TEAM row per "
                    + "patient on the page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of assignments"),
            @ApiResponse(responseCode = "400", description = "page or size is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<CareTeamMemberResponse>> listMyAssignments(
            @Parameter(description = "Zero-based page; a negative value is treated as 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(careTeamService.listForDoctor(page, size));
    }
}
