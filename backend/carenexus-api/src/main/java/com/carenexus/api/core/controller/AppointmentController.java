package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.CreateAppointmentRequest;
import com.carenexus.api.core.dto.request.RescheduleAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.BookingRulesResponse;
import com.carenexus.api.core.dto.response.BusySlotResponse;
import com.carenexus.api.core.service.AppointmentService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Appointments (API contract be-appointments-core). Route security is {@code authenticated()}; every rule is a
 * service check in {@link AppointmentService}. The call endpoints under {@code /{appointmentId}/call} belong to the
 * calls module and {@code /doctor/{id}/slots} to the scheduling module.
 */
@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
@Tag(name = "Appointments", description = "Booking, listing, rescheduling and cancelling appointments")
public class AppointmentController {

    /** Idempotency scope of the booking endpoint. */
    static final String CREATE_SCOPE = "POST /api/appointments";
    /** Rate-limit bucket of the booking endpoint (per caller). */
    static final String BOOK_BUCKET = "appointment-book";

    private final AppointmentService appointmentService;
    private final AuthorizationService authorizationService;
    private final IdempotencyService idempotencyService;
    private final RateLimitGuard rateLimitGuard;

    @PostMapping
    @Operation(summary = "Book an appointment",
            description = "PATIENT for themselves (patientSystemId may be omitted); CAREGIVER with appointment "
                    + "management access to the patient; DOCTOR into their own schedule for a related patient; ADMIN "
                    + "for anyone. Accepts an Idempotency-Key header (a repeat with the same key returns the stored "
                    + "appointment with 200). Rate limited per caller (bucket appointment-book). Audited; publishes "
                    + "AppointmentBookedEvent, and for VIDEO prepares the call session.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The appointment was booked"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay: the appointment booked by the first request"),
            @ApiResponse(responseCode = "400", description = "Invalid field (VALIDATION_FAILED), slot rule, malformed "
                    + "Idempotency-Key, or no contact number for PHONE (CONTACT_PHONE_REQUIRED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not book for this patient or doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Patient, doctor or active facility not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Slot taken, patient busy, VIDEO_UNAVAILABLE or IDEMPOTENCY_IN_PROGRESS",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many bookings (code RATE_LIMITED, Retry-After header)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AppointmentResponse> createAppointment(
            @Valid @RequestBody CreateAppointmentRequest request,
            @Parameter(description = "Optional, 8 to 64 characters [A-Za-z0-9_-]; one key per booking form")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        rateLimitGuard.check(BOOK_BUCKET, authorizationService.currentSystemId());
        IdempotentResult<AppointmentResponse> result = idempotencyService.execute(CREATE_SCOPE, idempotencyKey,
                AppointmentResponse.class, () -> appointmentService.createAppointment(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping
    @Operation(summary = "List appointments (paged, filterable)",
            description = "PATIENT: own appointments (another patientSystemId is 403). CAREGIVER: patientSystemId is "
                    + "required and needs appointment view access. DOCTOR: only their own appointments (another "
                    + "doctorSystemId is 403). ADMIN: everything. from is inclusive and to exclusive, both clinic "
                    + "wall-clock yyyy-MM-ddTHH:mm:ss; status takes one or more comma-separated values. Sorted by "
                    + "scheduledAt then appointmentId, newest first unless order=asc. Audited as a list read.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of appointments")
    public ResponseEntity<PageResponse<AppointmentResponse>> listAppointments(
            @Parameter(description = "Patient filter", example = "PAT-00001")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid ID such as PAT-00001")
            String patientSystemId,
            @Parameter(description = "Doctor filter (a DOCTOR may only pass their own id)", example = "DOC-00001")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid ID such as DOC-00001")
            String doctorSystemId,
            @Parameter(description = "SCHEDULED, RESCHEDULED, COMPLETED, CANCELLED, NO_SHOW; comma-separated or repeated")
            @RequestParam(required = false) List<String> status,
            @Parameter(description = "Inclusive lower bound on scheduledAt, clinic wall-clock", example = "2026-11-20T00:00:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Exclusive upper bound on scheduledAt, clinic wall-clock", example = "2026-11-21T00:00:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "asc or desc (default)") @RequestParam(required = false) String order,
            @Parameter(description = "Zero-based page; negative is read as 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(appointmentService.listAppointments(new AppointmentService.ListQuery(
                patientSystemId, doctorSystemId, status, from, to, order, page, size)));
    }

    @GetMapping("/booking-rules")
    @Operation(summary = "Get the clinic booking rules",
            description = "Any signed-in user. Hours, slot size, durations and horizon the server enforces for "
                    + "patient-side bookings, plus whether video appointments can be booked. Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The booking rules"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<BookingRulesResponse> getBookingRules() {
        return ResponseEntity.ok(appointmentService.getBookingRules());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one appointment",
            description = "The appointment's doctor, an admin, the patient, a related doctor, or a caregiver who may "
                    + "view the patient's appointments. Audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The appointment")
    public ResponseEntity<AppointmentResponse> getAppointmentById(@PathVariable Integer id) {
        return ResponseEntity.ok(appointmentService.getAppointmentById(id));
    }

    /** @deprecated use {@code GET /api/appointments?patientSystemId=} (paged). */
    @Deprecated
    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's appointments (deprecated array)", deprecated = true,
            description = "Deprecated: use GET /api/appointments?patientSystemId=. The patient, a related doctor, a "
                    + "caregiver who may view appointments, or an admin. At most 200 rows, newest first. Audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "Up to 200 appointments, newest first")
    public ResponseEntity<List<AppointmentResponse>> getPatientAppointments(@PathVariable String patientSystemId) {
        return ResponseEntity.ok(appointmentService.getAppointmentsForPatientAsCaller(patientSystemId));
    }

    /** @deprecated use {@code GET /api/appointments?doctorSystemId=} (paged). */
    @Deprecated
    @GetMapping("/doctor/{doctorSystemId}")
    @Operation(summary = "List a doctor's appointments (deprecated array)", deprecated = true,
            description = "Deprecated: use GET /api/appointments. That doctor or an admin. At most 200 rows, newest "
                    + "first. Audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "Up to 200 appointments, newest first")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointments(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(appointmentService.getAppointmentsForDoctorAsCaller(doctorSystemId));
    }

    @GetMapping("/doctor/{doctorSystemId}/busy")
    @Operation(summary = "Get a doctor's busy times on one day",
            description = "Any signed-in user. Start, end and duration of the doctor's active appointments starting "
                    + "on that clinic day, ascending. No patient details. Not audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "Busy periods of the day")
    public ResponseEntity<List<BusySlotResponse>> getDoctorBusySlots(
            @PathVariable String doctorSystemId,
            @Parameter(description = "Clinic date yyyy-MM-dd", example = "2026-11-20")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(appointmentService.getDoctorBusySlots(doctorSystemId, date));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit an active appointment",
            description = "The patient, the appointment's doctor, a caregiver with appointment management access, or "
                    + "an admin. Partial: absent or null fields are unchanged. A new scheduledAt reschedules it "
                    + "(status RESCHEDULED, AppointmentRescheduledEvent). Patient, doctor and facility cannot change. "
                    + "Audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The updated appointment")
    public ResponseEntity<AppointmentResponse> updateAppointment(
            @PathVariable Integer id,
            @Valid @RequestBody CreateAppointmentRequest request) {
        return ResponseEntity.ok(appointmentService.updateAppointment(id, request));
    }

    @PatchMapping("/{id}/reschedule")
    @Operation(summary = "Move an active appointment to a new time",
            description = "Same callers and rules as PUT with scheduledAt and an optional durationMinutes. The old "
                    + "slot is free as soon as the call returns. Audited.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The rescheduled appointment")
    public ResponseEntity<AppointmentResponse> rescheduleAppointment(
            @PathVariable Integer id,
            @Valid @RequestBody RescheduleAppointmentRequest request) {
        return ResponseEntity.ok(appointmentService.rescheduleAppointment(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Set the outcome or cancel an appointment",
            description = "CANCELLED: same as DELETE (manage rule). COMPLETED or NO_SHOW: only the appointment's "
                    + "doctor or an admin, once the appointment has started; NO_SHOW is refused while a completed "
                    + "consultation exists. The current status is a no-op. Audited; publishes "
                    + "AppointmentStatusChangedEvent or AppointmentCancelledEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The appointment")
    public ResponseEntity<AppointmentResponse> updateAppointmentStatus(
            @PathVariable Integer id,
            @Parameter(description = "COMPLETED, NO_SHOW or CANCELLED") @RequestParam String status) {
        return ResponseEntity.ok(appointmentService.updateAppointmentStatus(id, status));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel an appointment",
            description = "Soft cancel (status CANCELLED, the row stays). The patient, the appointment's doctor, a "
                    + "caregiver with appointment management access, or an admin. Patients and caregivers only before "
                    + "the start. Refused while a completed consultation exists. Cancelling a cancelled appointment "
                    + "is a no-op. Audited; publishes AppointmentCancelledEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "204", description = "The appointment is cancelled")
    public ResponseEntity<Void> cancelAppointment(@PathVariable Integer id) {
        appointmentService.cancelAppointment(id);
        return ResponseEntity.noContent().build();
    }
}
