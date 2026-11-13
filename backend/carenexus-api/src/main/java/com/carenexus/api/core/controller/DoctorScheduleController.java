package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.core.dto.response.DoctorDashboardResponse;
import com.carenexus.api.core.dto.response.ScheduleEntryResponse;
import com.carenexus.api.core.service.DoctorScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The calling doctor's schedule (API contract be-doctor-schedule). Route security limits {@code /api/doctors/**} to
 * DOCTOR and ADMIN; every route is "self" (no doctor id in the path) and the service checks
 * {@code requireDoctorSelf(caller)}. An ADMIN has no doctor profile and gets 404 {@code Doctor profile not found}.
 * The per-doctor-id twins and the today / upcoming / past / date-range / status / patient lists were removed: use
 * {@code /window} or {@code GET /api/doctors/appointments/search}.
 */
@RestController
@RequestMapping("/api/doctors/schedule")
@RequiredArgsConstructor
@Tag(name = "Doctor schedule", description = "The calling doctor's schedule and home dashboard")
public class DoctorScheduleController {

    private final DoctorScheduleService doctorScheduleService;

    /** @deprecated use {@code GET /api/doctors/schedule/window} (paged) or {@code /api/doctors/appointments/search}. */
    @Deprecated
    @GetMapping
    @Operation(summary = "Get the whole schedule (deprecated array)", deprecated = true,
            description = "Deprecated: use GET /api/doctors/schedule/window or GET /api/doctors/appointments/search. "
                    + "The 200 most recent appointments of the calling doctor (every status, scheduledAt then "
                    + "appointmentId descending), each APPOINTMENT row followed by one CONSULTATION row per "
                    + "consultation of that appointment, oldest first. Audited as a list read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schedule rows (legacy layout)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not a doctor or an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Doctor profile not found (for example an administrator)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<ScheduleEntryResponse>> getMySchedule() {
        return ResponseEntity.ok(doctorScheduleService.getLegacySchedule());
    }

    @GetMapping("/window")
    @Operation(summary = "Get the schedule of a date-time window (paged)",
            description = "The calling doctor's appointments whose start lies in [from, to), every status, one "
                    + "APPOINTMENT row per appointment with its linked consultation, facility, contact phone, manual "
                    + "reminder time and needs-outcome flag. from and to are clinic wall-clock yyyy-MM-ddTHH:mm:ss. "
                    + "Sorted by scheduledAt then appointmentId (asc unless order=desc). Audited as a list read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of schedule rows"),
            @ApiResponse(responseCode = "400", description = "from or to missing or invalid, to not after from, invalid "
                    + "order, page or size",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not a doctor or an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Doctor profile not found (for example an administrator)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<ScheduleEntryResponse>> getScheduleWindow(
            @Parameter(description = "Inclusive lower bound on scheduledAt, clinic wall-clock", example = "2026-11-20T00:00:00",
                    required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Exclusive upper bound on scheduledAt, clinic wall-clock", example = "2026-11-21T00:00:00",
                    required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "asc (default) or desc") @RequestParam(required = false) String order,
            @Parameter(description = "Zero-based page; negative is read as 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(doctorScheduleService.getScheduleWindow(from, to, order, page, size));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Get the doctor home dashboard",
            description = "Profile of the calling doctor, counts from the database (totalAppointments, the true "
                    + "upcomingAppointments count, needsOutcomeCount, completed and pending consultations), today's "
                    + "appointments (clinic day, every status, newest first, at most 200) and the next 10 active "
                    + "appointments, both in the legacy layout. Audited as a list read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The dashboard"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not a doctor or an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Doctor profile not found (for example an administrator), "
                    + "or User not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DoctorDashboardResponse> getMyDashboard() {
        return ResponseEntity.ok(doctorScheduleService.getDashboard());
    }
}
