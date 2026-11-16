package com.carenexus.api.scheduling.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.scheduling.dto.response.DoctorSlotsResponse;
import com.carenexus.api.scheduling.service.DoctorAvailabilityService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Free start times of a doctor for slot pickers (patient booking, proxy booking, reschedule panels).
 *
 * - GET /api/appointments/doctor/{doctorSystemId}/slots?date=&durationMinutes=&excludeAppointmentId=
 *
 * Any signed-in user (route rule {@code /api/appointments/**}: authenticated). This is the only mapping of this path;
 * AppointmentController maps its siblings ({@code /doctor/{id}}, {@code /schedule}, {@code /busy}).
 */
@RestController
@RequestMapping("/api/appointments/doctor")
@RequiredArgsConstructor
@Tag(name = "Appointments", description = "Doctor slots for booking and rescheduling")
public class DoctorSlotController {

    private final DoctorAvailabilityService availabilityService;

    @GetMapping("/{doctorSystemId}/slots")
    @Operation(summary = "List a doctor's bookable start times on one day",
            description = "Any signed-in user. Start times inside the doctor's working hours (template, or clinic hours "
                    + "when the doctor has none) for the clinic day, each flagged available or not: past, beyond the "
                    + "booking horizon, inside time off or overlapping an active appointment means not available "
                    + "(the reason is not disclosed). A past or far-future date is not an error. excludeAppointmentId "
                    + "is honoured only for an appointment of this doctor whose patient's appointments the caller may "
                    + "manage, and silently ignored otherwise. No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The day's start times"),
            @ApiResponse(responseCode = "400", description = "date missing or not yyyy-MM-dd, a parameter that is not "
                    + "an integer, or durationMinutes not 5 to 240 in steps of 5 (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No doctor with this system id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DoctorSlotsResponse> getSlots(
            @Parameter(description = "The doctor", example = "DOC-00002") @PathVariable String doctorSystemId,
            @Parameter(description = "Clinic calendar day, yyyy-MM-dd", example = "2026-11-12", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Appointment length: 5 to 240 in steps of 5; default the day's slot length")
            @RequestParam(required = false) Integer durationMinutes,
            @Parameter(description = "Appointment being rescheduled: ignored when computing taken times")
            @RequestParam(required = false) Integer excludeAppointmentId) {
        return ResponseEntity.ok(availabilityService.getSlots(doctorSystemId, date, durationMinutes, excludeAppointmentId));
    }
}
