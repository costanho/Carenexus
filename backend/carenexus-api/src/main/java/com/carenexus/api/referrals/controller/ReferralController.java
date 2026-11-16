package com.carenexus.api.referrals.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.referrals.dto.request.AssignReferralSpecialistRequest;
import com.carenexus.api.referrals.dto.request.CreateReferralRequest;
import com.carenexus.api.referrals.dto.request.DeclineReferralRequest;
import com.carenexus.api.referrals.dto.request.ReferralOutcomeRequest;
import com.carenexus.api.referrals.dto.response.ReferralResponse;
import com.carenexus.api.referrals.service.ReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Referrals of patients to specialists. Every rule is enforced in {@link ReferralService}; the route is only
 * {@code authenticated()} in SecurityConfig.
 *
 * - POST  /api/referrals                              create (treating doctor; Idempotency-Key)
 * - GET   /api/referrals/{id}                         read one
 * - GET   /api/referrals/doctor/outgoing              referrals the calling doctor issued (paged)
 * - GET   /api/referrals/doctor/incoming              referrals addressed to the calling doctor (paged)
 * - GET   /api/referrals/patient/{patientSystemId}    a patient's referrals (paged)
 * - PATCH /api/referrals/{id}/specialist              assign or replace the specialist while PENDING
 * - PATCH /api/referrals/{id}/accept|decline|complete|cancel   workflow
 */
@RestController
@RequestMapping("/api/referrals")
@RequiredArgsConstructor
@Validated
@Tag(name = "Referrals", description = "Referrals of patients to platform or external specialists, with the "
        + "PENDING, ACCEPTED, DECLINED, COMPLETED, CANCELLED workflow")
public class ReferralController {

    static final String CREATE_SCOPE = "POST /api/referrals";

    private final ReferralService referralService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Create a referral",
            description = "DOCTOR with a care relationship to the patient (care team, appointment or consultation). "
                    + "The specialist is optional: a platform doctor (active, approved, not the caller), a specialist "
                    + "outside the platform, or none (assign later). Optional Idempotency-Key header: a replay returns "
                    + "200 with the first response and creates nothing. Audited (CREATE REFERRAL); publishes "
                    + "ReferralCreatedEvent; notifies the patient, caregivers who may view records and a named "
                    + "platform specialist (HIGH priority for EMERGENCY).")
    @StandardErrors
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Referral created"),
            @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key")
    })
    public ResponseEntity<ReferralResponse> create(
            @Parameter(description = "Optional key (8 to 64 of [A-Za-z0-9_-]) that makes a retry safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateReferralRequest request) {
        IdempotentResult<ReferralResponse> result = idempotencyService.execute(CREATE_SCOPE, idempotencyKey,
                ReferralResponse.class, () -> referralService.create(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one referral",
            description = "The referring doctor, the named platform specialist (any status), or anyone with patient "
                    + "access to referrals: the patient, a related doctor, an administrator, a caregiver whose grant "
                    + "allows viewing records. allowedActions is computed for the caller. Audited (READ REFERRAL).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The referral"),
            @ApiResponse(responseCode = "400", description = "id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not see this referral",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Referral not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ReferralResponse> get(@PathVariable Integer id) {
        return ResponseEntity.ok(referralService.get(id));
    }

    @GetMapping("/doctor/outgoing")
    @Operation(summary = "List referrals the calling doctor issued",
            description = "DOCTOR only; always the caller's own referrals, newest first. Optional status filter (one "
                    + "of PENDING, ACCEPTED, DECLINED, COMPLETED, CANCELLED, case-insensitive) and patientSystemId "
                    + "filter; blank means no filter. Audited: one READ REFERRAL row per patient on the page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of referrals"),
            @ApiResponse(responseCode = "400", description = "Invalid status, patientSystemId, page or size",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<ReferralResponse>> listOutgoing(
            @Parameter(description = "Status filter") @RequestParam(required = false) String status,
            @Parameter(description = "Patient filter", example = "PAT-00021")
            @RequestParam(required = false) String patientSystemId,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(referralService.listOutgoing(status, patientSystemId, page, size));
    }

    @GetMapping("/doctor/incoming")
    @Operation(summary = "List referrals addressed to the calling doctor",
            description = "DOCTOR only; referrals naming the caller as platform specialist, every status, newest "
                    + "first. A referral reassigned to another specialist leaves this list. Same filters as the "
                    + "outgoing list. Audited: one READ REFERRAL row per patient on the page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of referrals"),
            @ApiResponse(responseCode = "400", description = "Invalid status, patientSystemId, page or size",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<ReferralResponse>> listIncoming(
            @Parameter(description = "Status filter") @RequestParam(required = false) String status,
            @Parameter(description = "Patient filter", example = "PAT-00021")
            @RequestParam(required = false) String patientSystemId,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(referralService.listIncoming(status, patientSystemId, page, size));
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's referrals",
            description = "The patient, a related doctor, an administrator, or a caregiver whose active grant allows "
                    + "viewing records (or is FULL_ACCESS). Every status, newest first; allowedActions is empty for "
                    + "patients and caregivers. Audited (READ REFERRAL) when the page is not empty.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of referrals"),
            @ApiResponse(responseCode = "400", description = "Malformed patientSystemId, page or size",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not see this patient's referrals",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<ReferralResponse>> listForPatient(
            @Parameter(description = "Patient system id", example = "PAT-00021")
            @PathVariable @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
            String patientSystemId,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(referralService.listForPatient(patientSystemId, page, size));
    }

    @PatchMapping("/{id}/specialist")
    @Operation(summary = "Assign or replace the specialist of a pending referral",
            description = "The referring doctor or an ADMIN, while PENDING. Exactly one of specialistSystemId and "
                    + "externalSpecialistName; the specialist cannot be cleared. Assigning the current platform "
                    + "specialist again changes nothing. Audited (UPDATE REFERRAL); a newly assigned platform "
                    + "specialist is notified.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The referral after the change")
    public ResponseEntity<ReferralResponse> assignSpecialist(
            @PathVariable Integer id,
            @Valid @RequestBody AssignReferralSpecialistRequest request) {
        return ResponseEntity.ok(referralService.assignSpecialist(id, request));
    }

    @PatchMapping("/{id}/accept")
    @Operation(summary = "Accept a pending referral",
            description = "The named platform specialist, or (on the specialist's behalf, note required) an ADMIN or, "
                    + "when there is no platform specialist, the referring doctor. Already ACCEPTED: no-op. A platform "
                    + "specialist gains an active SPECIALIST care-team row (patient record access). Audited (UPDATE "
                    + "REFERRAL); publishes ReferralStatusChangedEvent; notifies the referring doctor and the patient.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The referral after the change")
    public ResponseEntity<ReferralResponse> accept(
            @PathVariable Integer id,
            @Valid @RequestBody(required = false) ReferralOutcomeRequest request) {
        return ResponseEntity.ok(referralService.accept(id, request));
    }

    @PatchMapping("/{id}/decline")
    @Operation(summary = "Decline a pending referral",
            description = "Same responders as accept; the reason is required for every caller and stored as the "
                    + "decline reason. Already DECLINED: no-op. Audited (UPDATE REFERRAL); publishes "
                    + "ReferralStatusChangedEvent; notifies the referring doctor and the patient.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The referral after the change")
    public ResponseEntity<ReferralResponse> decline(
            @PathVariable Integer id,
            @Valid @RequestBody DeclineReferralRequest request) {
        return ResponseEntity.ok(referralService.decline(id, request));
    }

    @PatchMapping("/{id}/complete")
    @Operation(summary = "Complete an accepted referral",
            description = "Same responders as accept (note required on the specialist's behalf); a note replaces the "
                    + "acceptance note. Already COMPLETED: no-op. Audited (UPDATE REFERRAL); publishes "
                    + "ReferralStatusChangedEvent; notifies the referring doctor and the patient.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The referral after the change")
    public ResponseEntity<ReferralResponse> complete(
            @PathVariable Integer id,
            @Valid @RequestBody(required = false) ReferralOutcomeRequest request) {
        return ResponseEntity.ok(referralService.complete(id, request));
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending referral",
            description = "The referring doctor only (not an administrator); no body. Already CANCELLED: no-op. "
                    + "Audited (UPDATE REFERRAL); publishes ReferralStatusChangedEvent; notifies the patient and a "
                    + "platform specialist.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The referral after the change")
    public ResponseEntity<ReferralResponse> cancel(@PathVariable Integer id) {
        return ResponseEntity.ok(referralService.cancel(id));
    }
}
