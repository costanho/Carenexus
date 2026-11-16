package com.carenexus.api.scheduling.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.scheduling.dto.request.CreateTimeOffRequest;
import com.carenexus.api.scheduling.dto.request.UpdateDoctorAvailabilityRequest;
import com.carenexus.api.scheduling.dto.response.DoctorAvailabilityResponse;
import com.carenexus.api.scheduling.dto.response.TimeOffResponse;
import com.carenexus.api.scheduling.service.DoctorAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling doctor's working hours and time off. Route rule: {@code /api/doctors/**} is open to DOCTOR and ADMIN;
 * the service then refuses ADMIN (an administrator has no hours of their own). No doctor id is taken from the path
 * or body: every endpoint acts on the caller.
 *
 * - GET    /api/doctors/availability                 weekly template
 * - PUT    /api/doctors/availability                 replace the whole weekly template
 * - GET    /api/doctors/availability/time-off        paged time-off ranges (soonest first)
 * - POST   /api/doctors/availability/time-off        add a range (Idempotency-Key)
 * - DELETE /api/doctors/availability/time-off/{id}   remove a range
 */
@RestController
@RequestMapping("/api/doctors/availability")
@RequiredArgsConstructor
@Tag(name = "Doctor availability", description = "A doctor's weekly working hours and time off (doctor self only)")
public class DoctorAvailabilityController {

    static final String TIME_OFF_SCOPE = "POST /api/doctors/availability/time-off";

    private final DoctorAvailabilityService availabilityService;
    private final IdempotencyService idempotencyService;

    @GetMapping
    @Operation(summary = "Get my weekly working hours",
            description = "DOCTOR only (own template). A doctor without stored blocks gets source CLINIC_HOURS, "
                    + "slotMinutes 30 and no blocks (patients then book against clinic hours). No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The weekly template"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DoctorAvailabilityResponse> getAvailability() {
        return ResponseEntity.ok(availabilityService.getMyAvailability());
    }

    @PutMapping
    @Operation(summary = "Replace my weekly working hours",
            description = "DOCTOR only (own template). Deletes every stored block and stores the given ones with the "
                    + "given slot length, in one transaction; an empty list removes the template. Blocks must lie on "
                    + "5-minute boundaries inside clinic hours, last at least one slot and not overlap on a weekday. "
                    + "Existing appointments are never changed. Writes one audit row (AVAILABILITY_UPDATE / AVAILABILITY).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The template as stored"),
            @ApiResponse(responseCode = "400", description = "Malformed body or a rule failed (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DoctorAvailabilityResponse> replaceAvailability(
            @Valid @RequestBody UpdateDoctorAvailabilityRequest request) {
        return ResponseEntity.ok(availabilityService.replaceMyAvailability(request));
    }

    @GetMapping("/time-off")
    @Operation(summary = "List my time off",
            description = "DOCTOR only (own rows). Paged, soonest first (startsAt, then timeOffId). Without "
                    + "includePast only ranges that have not ended are returned. conflictingAppointments counts the "
                    + "active appointments inside each range. No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of time-off ranges"),
            @ApiResponse(responseCode = "400", description = "A query parameter could not be parsed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<TimeOffResponse>> listTimeOff(
            @Parameter(description = "true returns past ranges too; default only running and upcoming ones")
            @RequestParam(defaultValue = "false") boolean includePast,
            @Parameter(description = "Zero-based page; negative becomes 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(availabilityService.listMyTimeOff(includePast, page, size));
    }

    @PostMapping("/time-off")
    @Operation(summary = "Add time off",
            description = "DOCTOR only (for the caller). From now on slots inside the range are not available and "
                    + "patient-side bookings overlapping it are refused; appointments already inside it stay booked "
                    + "and are counted in conflictingAppointments. Accepts an Idempotency-Key: a repeat returns the "
                    + "stored response with 200 and creates nothing. Writes one audit row (TIME_OFF_CREATE / AVAILABILITY).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The range was added"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay: the response of the first call"),
            @ApiResponse(responseCode = "400", description = "Malformed body, malformed Idempotency-Key, or a rule "
                    + "failed (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The same Idempotency-Key is still being processed "
                    + "(code IDEMPOTENCY_IN_PROGRESS)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<TimeOffResponse> createTimeOff(
            @Parameter(description = "Optional duplicate-submit key: 8 to 64 characters of letters, digits, '_' or '-'")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTimeOffRequest request) {
        IdempotentResult<TimeOffResponse> result = idempotencyService.execute(TIME_OFF_SCOPE, idempotencyKey,
                TimeOffResponse.class, () -> availabilityService.createTimeOff(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @DeleteMapping("/time-off/{id}")
    @Operation(summary = "Remove time off",
            description = "DOCTOR only, own rows (another doctor's row is reported as not found). Hard delete; the "
                    + "slots of the range become bookable again at once. Writes one audit row (TIME_OFF_DELETE / AVAILABILITY).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The range was removed"),
            @ApiResponse(responseCode = "400", description = "The id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such range of the caller",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> deleteTimeOff(
            @Parameter(description = "timeOffId of the range", example = "7") @PathVariable Integer id) {
        availabilityService.deleteTimeOff(id);
        return ResponseEntity.noContent().build();
    }
}
