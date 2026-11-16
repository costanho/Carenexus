package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.core.dto.request.RescheduleAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.AppointmentStatsResponse;
import com.carenexus.api.core.dto.response.ScheduleEntryResponse;
import com.carenexus.api.core.service.AppointmentService;
import com.carenexus.api.core.service.DoctorScheduleService;
import com.carenexus.api.core.service.DoctorScheduleService.ManualReminderResponse;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The calling doctor's appointments (API contract be-doctor-schedule). Route security limits
 * {@code /api/doctors/**} to DOCTOR and ADMIN; every route is "self" and starts with
 * {@code requireDoctorSelf(caller)}. Status changes delegate to {@link AppointmentService}, which owns the
 * appointment rules. The per-doctor-id twins, the per-status lists, the patient list and
 * {@code PATCH /{id}/status} were removed: use {@code /search} or {@code PATCH /api/appointments/{id}/status}.
 */
@RestController
@RequestMapping("/api/doctors/appointments")
@RequiredArgsConstructor
@Tag(name = "Doctor appointments", description = "The calling doctor's appointment lists, counts, outcomes and reminders")
public class DoctorAppointmentController {

    /** Rate-limit bucket of the manual reminder (per caller). */
    static final String REMINDER_BUCKET = "reminder-manual";

    private final AppointmentService appointmentService;
    private final DoctorScheduleService doctorScheduleService;
    private final RateLimitGuard rateLimitGuard;

    /** @deprecated use {@code GET /api/doctors/appointments/search} (paged). */
    @Deprecated
    @GetMapping
    @Operation(summary = "List my appointments (deprecated array)", deprecated = true,
            description = "Deprecated: use GET /api/doctors/appointments/search. At most 200 appointments of the "
                    + "calling doctor, newest first, every status. An administrator gets an empty array. Audited as a "
                    + "list read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Up to 200 appointments, newest first"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not a doctor or an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments() {
        return ResponseEntity.ok(doctorScheduleService.getLegacyAppointments());
    }

    @GetMapping("/search")
    @Operation(summary = "Search my appointments (paged, filterable)",
            description = "The calling doctor's appointments as APPOINTMENT schedule rows. status takes one or more "
                    + "comma-separated or repeated values; patientSystemId is a plain filter on the doctor's own rows; "
                    + "from (inclusive) and to (exclusive) are clinic wall-clock yyyy-MM-ddTHH:mm:ss. Sorted by "
                    + "scheduledAt then appointmentId, newest first unless order=asc. An administrator gets an empty "
                    + "page. Audited as a list read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of appointment rows"),
            @ApiResponse(responseCode = "400", description = "Invalid status, patientSystemId (VALIDATION_FAILED), "
                    + "from, to, order, page or size, or to not after from",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not a doctor or an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<ScheduleEntryResponse>> searchAppointments(
            @Parameter(description = "SCHEDULED, RESCHEDULED, COMPLETED, CANCELLED, NO_SHOW; comma-separated or repeated")
            @RequestParam(required = false) List<String> status,
            @Parameter(description = "Patient filter", example = "PAT-00001")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid ID such as PAT-00001")
            String patientSystemId,
            @Parameter(description = "Inclusive lower bound on scheduledAt, clinic wall-clock", example = "2026-11-20T00:00:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Exclusive upper bound on scheduledAt, clinic wall-clock", example = "2026-11-21T00:00:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "asc or desc (default)") @RequestParam(required = false) String order,
            @Parameter(description = "Zero-based page; negative is read as 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(doctorScheduleService.searchAppointments(status, patientSystemId, from, to, order,
                page, size));
    }

    @GetMapping("/needs-outcome")
    @Operation(summary = "List my appointments that need an outcome (paged)",
            description = "The calling doctor's SCHEDULED or RESCHEDULED appointments whose end (start + duration) "
                    + "passed more than appointments.needs-outcome-after-minutes (system setting) ago, most recently "
                    + "started first. totalElements is the badge count. An administrator gets an empty page. Audited "
                    + "as a list read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of appointment rows, every row needsOutcome = true"),
            @ApiResponse(responseCode = "400", description = "Invalid patientSystemId (VALIDATION_FAILED), page or size",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not a doctor or an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<ScheduleEntryResponse>> getNeedsOutcome(
            @Parameter(description = "Patient filter", example = "PAT-00001")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid ID such as PAT-00001")
            String patientSystemId,
            @Parameter(description = "Zero-based page; negative is read as 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(doctorScheduleService.getNeedsOutcome(patientSystemId, page, size));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get my appointment counts",
            description = "Counts per status from one COUNT ... GROUP BY query, the upcoming count (scheduledCount), "
                    + "completion and no-show rates (0 to 100, not rounded) and the needs-outcome count. An "
                    + "administrator gets zeros. Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The counts"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not a doctor or an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AppointmentStatsResponse> getMyAppointmentStats() {
        return ResponseEntity.ok(doctorScheduleService.getStats());
    }

    @PatchMapping("/{appointmentId}/mark-completed")
    @Operation(summary = "Mark an appointment as completed",
            description = "The appointment's doctor (or an administrator), once the appointment has started. Setting "
                    + "the status it already has is a no-op. Rules and messages of PATCH /api/appointments/{id}/status.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The appointment in its new state")
    public ResponseEntity<AppointmentResponse> markAppointmentCompleted(@PathVariable Integer appointmentId) {
        doctorScheduleService.requireDoctorCaller();
        return ResponseEntity.ok(appointmentService.updateAppointmentStatus(appointmentId, AppointmentService.COMPLETED));
    }

    @PatchMapping("/{appointmentId}/mark-cancelled")
    @Operation(summary = "Cancel an appointment",
            description = "The appointment's doctor (or an administrator), before or after the start; refused while a "
                    + "completed consultation exists. Cancelling a cancelled appointment is a no-op.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The appointment in its new state")
    public ResponseEntity<AppointmentResponse> markAppointmentCancelled(@PathVariable Integer appointmentId) {
        doctorScheduleService.requireDoctorCaller();
        return ResponseEntity.ok(appointmentService.updateAppointmentStatus(appointmentId, AppointmentService.CANCELLED));
    }

    @PatchMapping("/{appointmentId}/mark-no-show")
    @Operation(summary = "Mark an appointment as no-show",
            description = "The appointment's doctor (or an administrator), once the appointment has started; refused "
                    + "while a completed consultation exists. Setting the status it already has is a no-op.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The appointment in its new state")
    public ResponseEntity<AppointmentResponse> markAppointmentNoShow(@PathVariable Integer appointmentId) {
        doctorScheduleService.requireDoctorCaller();
        return ResponseEntity.ok(appointmentService.updateAppointmentStatus(appointmentId, AppointmentService.NO_SHOW));
    }

    /** @deprecated use {@code PATCH /api/appointments/{id}/reschedule}. */
    @Deprecated
    @PatchMapping("/{appointmentId}/mark-rescheduled")
    @Operation(summary = "Move an appointment to a new time (deprecated)", deprecated = true,
            description = "Deprecated: use PATCH /api/appointments/{id}/reschedule, which this route duplicates. Body "
                    + "{scheduledAt, durationMinutes?}; the status becomes RESCHEDULED when the start really changes.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The appointment in its new state")
    public ResponseEntity<AppointmentResponse> markAppointmentRescheduled(
            @PathVariable Integer appointmentId,
            @Valid @RequestBody RescheduleAppointmentRequest request) {
        doctorScheduleService.requireDoctorCaller();
        return ResponseEntity.ok(appointmentService.rescheduleAppointment(appointmentId, request));
    }

    @PostMapping("/{appointmentId}/send-reminder")
    @Operation(summary = "Send a reminder to the patient",
            description = "Only the appointment's doctor, for a SCHEDULED or RESCHEDULED appointment that has not "
                    + "started. Sends one in-app reminder (category APPOINTMENT_REMINDER) to the patient and the "
                    + "caregivers who may see the patient's appointments; at most one per appointment and clinic clock "
                    + "hour (a repeat answers alreadySent = true and sends nothing). Rate limited per caller (bucket "
                    + "reminder-manual, every call counts). Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reminder sent, or already sent in this clock hour"),
            @ApiResponse(responseCode = "400", description = "appointmentId is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not the appointment's doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Appointment not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Appointment not active, or already started",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many reminders (code RATE_LIMITED, Retry-After header)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ManualReminderResponse> sendAppointmentReminder(@PathVariable Integer appointmentId) {
        String callerId = doctorScheduleService.requireDoctorCaller();
        rateLimitGuard.check(REMINDER_BUCKET, callerId);
        return ResponseEntity.ok(doctorScheduleService.sendManualReminder(appointmentId));
    }
}
