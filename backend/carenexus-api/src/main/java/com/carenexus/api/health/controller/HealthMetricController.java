package com.carenexus.api.health.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.health.dto.request.RecordHealthMetricsRequest;
import com.carenexus.api.health.dto.response.HealthMetricResponse;
import com.carenexus.api.health.dto.response.HealthMetricTrendResponse;
import com.carenexus.api.health.dto.response.LatestHealthMetricsResponse;
import com.carenexus.api.health.dto.response.RecordHealthMetricsResponse;
import com.carenexus.api.health.service.HealthMetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Health readings (vitals). Every route needs a bearer token; who may do what is decided in
 * {@link HealthMetricService} (patient self, related doctor, caregiver with the matching grant; administrators read
 * only).
 *
 * - POST   /api/health-metrics                                   record a measurement session
 * - GET    /api/health-metrics/patient/{patientSystemId}         paged history
 * - GET    /api/health-metrics/patient/{patientSystemId}/latest  latest reading per type plus derived BMI
 * - GET    /api/health-metrics/patient/{patientSystemId}/trend   daily aggregation of one type
 * - DELETE /api/health-metrics/{metricId}                        remove a manual reading
 */
@RestController
@RequestMapping("/api/health-metrics")
@RequiredArgsConstructor
@Validated
@Tag(name = "Health metrics", description = "Vital signs and other health readings: manual entry, history, latest "
        + "values with derived BMI, daily trends")
public class HealthMetricController {

    static final String SCOPE_RECORD = "POST /api/health-metrics";

    private final HealthMetricService healthMetricService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Record a measurement session",
            description = "PATIENT (self), a DOCTOR related to the patient, or a CAREGIVER whose grant allows recording "
                    + "health readings (FULL_ACCESS, or can_manage_care_plan and not VIEW_ONLY); ADMIN is refused. "
                    + "Stores 1 to 10 readings in one transaction with one readingGroupId, source MANUAL and the caller "
                    + "as recorder; severity is computed by the server. Publishes one HealthMetricRecordedEvent and "
                    + "writes one CREATE / HEALTH_METRIC audit row per reading. Optional Idempotency-Key header: a "
                    + "repeat answers 200 with the stored body.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The stored readings"),
            @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key"),
            @ApiResponse(responseCode = "400", description = "Field or rule validation failed (code VALIDATION_FAILED), "
                    + "malformed body, or malformed Idempotency-Key",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not record readings for this patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The same Idempotency-Key is still being processed "
                    + "(code IDEMPOTENCY_IN_PROGRESS)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RecordHealthMetricsResponse> record(
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]; one key per form submission")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RecordHealthMetricsRequest request) {
        IdempotentResult<RecordHealthMetricsResponse> result = idempotencyService.execute(SCOPE_RECORD, idempotencyKey,
                RecordHealthMetricsResponse.class, () -> healthMetricService.record(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's health readings",
            description = "Read access to the patient (patient self, related doctor, caregiver with can_view_records or "
                    + "FULL_ACCESS, ADMIN). Newest first (recordedAt, then metricId, descending). Filters: type (one "
                    + "type or a comma list), from / to (clinic days, inclusive). Audited (READ / HEALTH_METRIC).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of readings"),
            @ApiResponse(responseCode = "400", description = "Unknown type, from after to, or a malformed parameter",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not view this patient's health readings",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<HealthMetricResponse>> history(
            @Parameter(description = "Patient system id", example = "PAT-00001") @PathVariable String patientSystemId,
            @Parameter(description = "One type or several separated by commas, case-insensitive", example = "BP_SYSTOLIC,BP_DIASTOLIC")
            @RequestParam(required = false) String type,
            @Parameter(description = "First clinic day (YYYY-MM-DD), inclusive")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last clinic day (YYYY-MM-DD), inclusive")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Zero-based page; negative values become 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(healthMetricService.history(patientSystemId, type, from, to, page, size));
    }

    @GetMapping("/patient/{patientSystemId}/latest")
    @Operation(summary = "Get the latest reading of each type",
            description = "Read access to the patient. At most one reading per type (greatest recordedAt, then "
                    + "metricId) in the canonical type order, plus the BMI derived from the latest WEIGHT and HEIGHT "
                    + "(null unless both exist). Audited (READ / HEALTH_METRIC).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest readings and derived BMI"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not view this patient's health readings",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LatestHealthMetricsResponse> latest(
            @Parameter(description = "Patient system id", example = "PAT-00001") @PathVariable String patientSystemId) {
        return ResponseEntity.ok(healthMetricService.latest(patientSystemId));
    }

    @GetMapping("/patient/{patientSystemId}/trend")
    @Operation(summary = "Get the daily trend of one metric type",
            description = "Read access to the patient. One point per clinic day with readings over the last `days` "
                    + "days (today included): mean (two decimals), min, max, count and whether any reading was "
                    + "abnormal. Days without readings are omitted. Audited (one READ / HEALTH_METRIC row).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The trend"),
            @ApiResponse(responseCode = "400", description = "Missing or unknown type, or days outside 1..366",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not view this patient's health readings",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<HealthMetricTrendResponse> trend(
            @Parameter(description = "Patient system id", example = "PAT-00001") @PathVariable String patientSystemId,
            @Parameter(description = "Exactly one metric type, case-insensitive", example = "HEART_RATE")
            @RequestParam String type,
            @Parameter(description = "Window length in clinic days, 1 to 366") @RequestParam(defaultValue = "30")
            @Min(1) @Max(HealthMetricService.MAX_TREND_DAYS) int days) {
        return ResponseEntity.ok(healthMetricService.trend(patientSystemId, type, days));
    }

    @DeleteMapping("/{metricId}")
    @Operation(summary = "Remove a manually entered reading",
            description = "The patient, or the doctor or caregiver who recorded the reading while they still hold "
                    + "write access; nobody else (ADMIN included). MANUAL readings only. Removing one blood-pressure "
                    + "reading also removes its partner of the same reading group. Audited (DELETE / HEALTH_METRIC "
                    + "per removed row). Checks run in the order 404, 403, 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed"),
            @ApiResponse(responseCode = "400", description = "metricId is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Neither the patient nor the recorder, or the recorder lost write access",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such reading",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The reading did not come from manual entry",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Reading id", example = "412") @PathVariable Integer metricId) {
        healthMetricService.delete(metricId);
        return ResponseEntity.noContent().build();
    }
}
