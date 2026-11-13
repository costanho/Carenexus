package com.carenexus.api.reminders.controller;

import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.reminders.dto.request.CreateReminderRequest;
import com.carenexus.api.reminders.dto.request.UpdateReminderRequest;
import com.carenexus.api.reminders.dto.response.ReminderResponse;
import com.carenexus.api.reminders.service.ReminderService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Reminders addressed to a patient. Every rule is enforced in {@link ReminderService}; identity comes from the
 * access token only. Reminders addressed to a doctor (result review, the doctor's copy of a care-plan review) are
 * managed by events and answer 404 here.
 *
 * <pre>
 * GET    /api/reminders/patient/{patientSystemId}?active=&amp;page=&amp;size=   patient access PROFILE
 * POST   /api/reminders                    the patient, a treating doctor, a caregiver with MANAGE_CARE_PLAN; Idempotency-Key
 * PATCH  /api/reminders/{id}               patient access MANAGE_CARE_PLAN; automatic reminders: isActive only
 * DELETE /api/reminders/{id}               manual only; the patient, an admin, or the doctor or caregiver who created it
 * </pre>
 */
@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
@Validated
@Tag(name = "Reminders", description = "Manual and automatic reminders addressed to a patient: list, create, edit, "
        + "pause, resume and delete. Due reminders become in-app notifications (and email, SMS or push when the "
        + "recipient enabled them).")
public class ReminderController {

    static final String CREATE_SCOPE = "POST /api/reminders";

    private final ReminderService reminderService;
    private final IdempotencyService idempotencyService;

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's reminders",
            description = "The patient, a related doctor, a caregiver with any active grant, or an admin. Manual and "
                    + "automatic reminders whose recipient is the patient; active first, then the soonest nextDueAt, "
                    + "then the newest id. active=true returns active reminders only, active=false paused and ended "
                    + "ones only. canDelete is computed for the caller. Side effect: audit READ REMINDER.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of reminders")
    public ResponseEntity<PageResponse<ReminderResponse>> listForPatient(
            @PathVariable
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
            String patientSystemId,
            @Parameter(description = "true = active only; false = paused and ended only; absent = all")
            @RequestParam(required = false) Boolean active,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(reminderService.listForPatient(patientSystemId, active, page, size));
    }

    @PostMapping
    @Operation(summary = "Create a manual reminder for a patient",
            description = "The patient for themself, a caregiver whose active grant gives MANAGE_CARE_PLAN, or a "
                    + "treating doctor; admins are refused. The reminder starts active, is addressed to the patient "
                    + "and fires at reminderTime (clinic time) on the days its frequency gives. At most 50 active "
                    + "manual reminders per patient (409). Optional Idempotency-Key: a repeat returns the stored "
                    + "reminder with 200 and creates nothing. Side effect: audit CREATE REMINDER; no notification is "
                    + "sent at creation.")
    @StandardErrors
    @ApiResponse(responseCode = "201", description = "Reminder created")
    @ApiResponse(responseCode = "200", description = "Idempotent replay: the reminder created by the first request")
    public ResponseEntity<ReminderResponse> create(
            @Valid @RequestBody CreateReminderRequest request,
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]; one key per form open")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        IdempotentResult<ReminderResponse> result = idempotencyService.execute(
                CREATE_SCOPE, idempotencyKey, ReminderResponse.class, () -> reminderService.create(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit, pause or resume a reminder",
            description = "The patient, a related doctor, a caregiver whose grant gives MANAGE_CARE_PLAN, or an "
                    + "admin. {\"isActive\": false} pauses, {\"isActive\": true} resumes. Automatic reminders accept "
                    + "only isActive (409 otherwise). A schedule change or a resume recomputes nextDueAt as the "
                    + "first occurrence at or after now; an ended reminder can only be resumed together with a new "
                    + "schedule (409). Side effect when something changed: audit UPDATE REMINDER.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The reminder after the change")
    public ResponseEntity<ReminderResponse> update(@PathVariable Integer id,
                                                   @Valid @RequestBody UpdateReminderRequest request) {
        return ResponseEntity.ok(reminderService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a manual reminder",
            description = "The patient, an admin, or the doctor or caregiver who created it while they still have "
                    + "MANAGE_CARE_PLAN access to the patient. Automatic reminders cannot be deleted, only paused "
                    + "(409). Notifications already sent stay. Side effect: audit DELETE REMINDER.")
    @StandardErrors
    @ApiResponse(responseCode = "204", description = "Reminder deleted")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        reminderService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
