package com.carenexus.api.careplans.controller;

import com.carenexus.api.careplans.dto.request.CarePlanReviewRequest;
import com.carenexus.api.careplans.dto.request.CarePlanStatusRequest;
import com.carenexus.api.careplans.dto.request.CreateCarePlanRequest;
import com.carenexus.api.careplans.dto.request.GoalProgressRequest;
import com.carenexus.api.careplans.dto.request.UpdateCarePlanRequest;
import com.carenexus.api.careplans.dto.response.CarePlanResponse;
import com.carenexus.api.careplans.service.CarePlanService;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Care plans. Every rule is enforced in {@link CarePlanService}; identity comes from the access token only.
 *
 * <pre>
 * POST  /api/care-plans                          treating doctor; Idempotency-Key
 * GET   /api/care-plans/{id}                     patient access CARE_PLANS
 * GET   /api/care-plans/patient/{patientSystemId}?status=&amp;page=&amp;size=
 * GET   /api/care-plans/doctor?status=&amp;patientSystemId=&amp;page=&amp;size=   the calling doctor's own plans
 * PUT   /api/care-plans/{id}                     author doctor; not when COMPLETED or DISCONTINUED
 * PATCH /api/care-plans/{id}/status              author doctor
 * PATCH /api/care-plans/{id}/review              author doctor
 * PATCH /api/care-plans/{id}/goals/{goalId}      patient, caregiver with MANAGE_CARE_PLAN, author doctor
 * </pre>
 */
@RestController
@RequestMapping("/api/care-plans")
@RequiredArgsConstructor
@Validated
@Tag(name = "Care plans", description = "Doctor-authored care plans with goals, interventions, review dates and "
        + "status; goal progress by the patient and permitted caregivers")
public class CarePlanController {

    static final String CREATE_SCOPE = "POST /api/care-plans";

    private static final String PLAN_STATUS_PATTERN = "^\\s*(ACTIVE|ON_HOLD|COMPLETED|DISCONTINUED)?\\s*$";
    private static final String PLAN_STATUS_MESSAGE = "must be one of ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED";
    private static final String OPTIONAL_SYSTEM_ID = "^\\s*$|^(PAT|DOC|CGV|ADM)-\\d{5,}$";

    private final CarePlanService carePlanService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Create a care plan",
            description = "DOCTOR with a care relationship to the patient (care team, appointment or consultation); "
                    + "the caller is the author. The plan starts ACTIVE and its goals OPEN. targetDate, reviewDate "
                    + "and goal target dates must not be before startDate; at most 20 goals and 20 interventions. "
                    + "Optional Idempotency-Key: a repeat returns the stored plan with 200 and changes nothing. "
                    + "Side effects: audit CREATE CARE_PLAN, CarePlanChangedEvent(CREATED), a notification to the "
                    + "patient and caregivers holding CARE_PLANS.")
    @StandardErrors
    @ApiResponse(responseCode = "201", description = "Plan created")
    @ApiResponse(responseCode = "200", description = "Idempotent replay: the plan created by the first request")
    public ResponseEntity<CarePlanResponse> create(
            @Valid @RequestBody CreateCarePlanRequest request,
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]; one key per form open")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        IdempotentResult<CarePlanResponse> result = idempotencyService.execute(
                CREATE_SCOPE, idempotencyKey, CarePlanResponse.class, () -> carePlanService.create(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/doctor")
    @Operation(summary = "List the calling doctor's care plans",
            description = "DOCTOR only; always the caller's own plans, newest first (createdAt, then planId). "
                    + "Optional status and patientSystemId filters. Side effect: one audit READ row per patient on "
                    + "the page.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of plans")
    public ResponseEntity<PageResponse<CarePlanResponse>> listForDoctor(
            @Parameter(description = "ACTIVE, ON_HOLD, COMPLETED or DISCONTINUED (case-insensitive); blank = all")
            @RequestParam(required = false)
            @Pattern(regexp = PLAN_STATUS_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE, message = PLAN_STATUS_MESSAGE)
            String status,
            @Parameter(description = "Only plans of this patient; blank = all patients")
            @RequestParam(required = false)
            @Pattern(regexp = OPTIONAL_SYSTEM_ID, message = "must be a valid system id")
            String patientSystemId,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(carePlanService.listForDoctor(status, patientSystemId, page, size));
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's care plans",
            description = "The patient, a related doctor, a caregiver whose grant gives CARE_PLANS (FULL_ACCESS, "
                    + "can_view_records or can_manage_care_plan) or an admin. Every plan of the patient whoever "
                    + "authored it, newest first (createdAt, then planId). Side effect: audit READ CARE_PLAN.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of plans")
    public ResponseEntity<PageResponse<CarePlanResponse>> listForPatient(
            @PathVariable
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
            String patientSystemId,
            @Parameter(description = "ACTIVE, ON_HOLD, COMPLETED or DISCONTINUED (case-insensitive); blank = all")
            @RequestParam(required = false)
            @Pattern(regexp = PLAN_STATUS_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE, message = PLAN_STATUS_MESSAGE)
            String status,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(carePlanService.listForPatient(patientSystemId, status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a care plan",
            description = "The patient, a related doctor, a caregiver whose grant gives CARE_PLANS, or an admin. "
                    + "404 for a missing plan, 403 for an existing plan the caller may not see. "
                    + "Side effect: audit READ CARE_PLAN.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The plan with its goals and interventions")
    public ResponseEntity<CarePlanResponse> get(@PathVariable Integer id) {
        return ResponseEntity.ok(carePlanService.get(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace the editable content of a care plan",
            description = "The author doctor only, while the plan is ACTIVE or ON_HOLD (409 otherwise). Full "
                    + "replacement: goals sent with goalId keep their status and progress note, goals left out are "
                    + "removed, interventions are replaced as a whole. Side effects: audit UPDATE CARE_PLAN, "
                    + "CarePlanChangedEvent(UPDATED), a notification to the patient circle (at most one per day).")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The updated plan")
    public ResponseEntity<CarePlanResponse> update(@PathVariable Integer id,
                                                   @Valid @RequestBody UpdateCarePlanRequest request) {
        return ResponseEntity.ok(carePlanService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change the status of a care plan",
            description = "The author doctor only. ACTIVE and ON_HOLD interchange; COMPLETED and DISCONTINUED are "
                    + "terminal (409 for any change away from them). The current status is a no-op. Side effects on "
                    + "a real change: audit UPDATE CARE_PLAN, CarePlanChangedEvent(STATUS_CHANGED), a notification "
                    + "to the patient circle.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The plan with its current status")
    public ResponseEntity<CarePlanResponse> changeStatus(@PathVariable Integer id,
                                                         @Valid @RequestBody CarePlanStatusRequest request) {
        return ResponseEntity.ok(carePlanService.changeStatus(id, request));
    }

    @PatchMapping("/{id}/review")
    @Operation(summary = "Record a review of a care plan",
            description = "The author doctor only, while the plan is ACTIVE or ON_HOLD (409 otherwise). Stamps "
                    + "lastReviewedAt, stores the note and sets the next review date (null clears it; otherwise "
                    + "today or later and not before startDate). Side effects: audit UPDATE CARE_PLAN, "
                    + "CarePlanChangedEvent(REVIEWED), a notification to the patient circle (at most one per day).")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The reviewed plan")
    public ResponseEntity<CarePlanResponse> review(@PathVariable Integer id,
                                                   @Valid @RequestBody CarePlanReviewRequest request) {
        return ResponseEntity.ok(carePlanService.review(id, request));
    }

    @PatchMapping("/{id}/goals/{goalId}")
    @Operation(summary = "Record progress on a care-plan goal",
            description = "The patient, a caregiver whose grant gives MANAGE_CARE_PLAN (FULL_ACCESS, or "
                    + "can_manage_care_plan and not VIEW_ONLY), or the author doctor; admins are refused. Only the "
                    + "author doctor may set DROPPED or change a dropped goal. Not allowed on a COMPLETED or "
                    + "DISCONTINUED plan (409). Side effect when something changed: audit UPDATE CARE_PLAN. No event "
                    + "and no notification.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The whole plan with the updated goal")
    public ResponseEntity<CarePlanResponse> updateGoal(@PathVariable Integer id,
                                                       @PathVariable Integer goalId,
                                                       @Valid @RequestBody GoalProgressRequest request) {
        return ResponseEntity.ok(carePlanService.updateGoal(id, goalId, request));
    }
}
