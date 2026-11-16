package com.carenexus.api.ai.alert.controller;

import com.carenexus.api.ai.alert.dto.response.AiAlertResponse;
import com.carenexus.api.ai.alert.dto.response.UnacknowledgedAlertCountResponse;
import com.carenexus.api.ai.alert.service.AiAlertInboxService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI alert inbox (api/be-ai-core.md). Alerts are raised only by {@code AiAlertService.raise}; there is no create,
 * single-alert, resolve or delete endpoint. Role and ownership checks are made in {@link AiAlertInboxService}.
 *
 * - GET  /api/ai-alerts?acknowledged=&priority=&type=&patientSystemId=&page=&size=
 * - GET  /api/ai-alerts/unacknowledged-count
 * - POST /api/ai-alerts/{alertId}/acknowledge   204
 * - GET  /api/ai-alerts/patient/{patientSystemId}?page=&size=
 */
@RestController
@RequestMapping("/api/ai-alerts")
@RequiredArgsConstructor
@Tag(name = "AI Alerts", description = "Automated rule-based screening alerts about patients: clinician inbox, "
        + "acknowledgement and the per-patient list. Not a diagnosis.")
public class AiAlertController {

    private static final String PATIENT_ID_PATTERN = "^PAT-\\d{5,}$";

    private final AiAlertInboxService inboxService;

    @GetMapping
    @Operation(summary = "List the clinician's alerts, newest first",
            description = "DOCTOR: alerts addressed to them or about a patient they are related to (care team, "
                    + "appointment or consultation). ADMIN: every alert. PATIENT and CAREGIVER: 403. Filters combine "
                    + "with AND; patientSystemId narrows the caller's visible set and never widens it. Sorted by "
                    + "creation time descending, then alert id. Size is clamped to 1..100. A non-empty page writes one "
                    + "READ AI_ALERT audit row per patient on the page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of alerts"),
            @ApiResponse(responseCode = "400", description = "Invalid filter value or a page/size that is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is a patient or caregiver",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<AiAlertResponse>> list(
            @Parameter(description = "true = only acknowledged, false = only unacknowledged; omit for both",
                    schema = @Schema(type = "boolean"))
            @RequestParam(value = "acknowledged", required = false) String acknowledged,
            @Parameter(description = "Only this priority (any case); omit for all",
                    schema = @Schema(allowableValues = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}))
            @RequestParam(value = "priority", required = false) String priority,
            @Parameter(description = "Only this alert type (any case); omit for all",
                    schema = @Schema(allowableValues = {"CRITICAL_VITALS", "DISEASE_RISK", "MEDICATION_INTERACTION",
                            "MISSED_MEDICATION", "FOLLOW_UP_DUE", "ANOMALY_DETECTED", "CRITICAL_RESULT"}))
            @RequestParam(value = "type", required = false) String type,
            @Parameter(description = "Only alerts about this patient", example = "PAT-00001",
                    schema = @Schema(pattern = PATIENT_ID_PATTERN))
            @RequestParam(value = "patientSystemId", required = false) String patientSystemId,
            @Parameter(description = "Zero-based page; a negative value is treated as 0")
            @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(inboxService.list(acknowledged, priority, type, patientSystemId, page, size));
    }

    @GetMapping("/unacknowledged-count")
    @Operation(summary = "Count the unacknowledged alerts the caller can see",
            description = "DOCTOR: unacknowledged alerts visible to them (same rule as the list). ADMIN: every "
                    + "unacknowledged alert. PATIENT and CAREGIVER: 403. Polled by the web badge; not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The badge count"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is a patient or caregiver",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UnacknowledgedAlertCountResponse> unacknowledgedCount() {
        return ResponseEntity.ok(inboxService.unacknowledgedCount());
    }

    @PostMapping("/{alertId}/acknowledge")
    @Operation(summary = "Acknowledge an alert",
            description = "DOCTOR only, for an alert addressed to them or about a patient they are related to. "
                    + "Acknowledgement is per alert: it is acknowledged for every doctor. Idempotent: a repeat keeps "
                    + "the first acknowledger and time. The first acknowledgement writes an UPDATE AI_ALERT audit row; "
                    + "a denied doctor produces an ACCESS_DENIED row. Does not mark the doctor's notification read.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The alert is acknowledged"),
            @ApiResponse(responseCode = "400", description = "The alert id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor, or not part of the patient's care team",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No alert with this id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> acknowledge(
            @Parameter(description = "Id of the alert", example = "412") @PathVariable("alertId") Integer alertId) {
        inboxService.acknowledge(alertId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List one patient's alerts, newest first",
            description = "The patient (own id) and caregivers whose active grant includes records (AI_ALERTS) see "
                    + "only missed-medication and follow-up alerts and alerts whose screening result a doctor "
                    + "confirmed, without the clinician suggestion. A related DOCTOR and an ADMIN see every alert of "
                    + "the patient. Size is clamped to 1..100. A non-empty page writes a READ AI_ALERT audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of alerts"),
            @ApiResponse(responseCode = "400", description = "Malformed patient id, or a page/size that is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not see this patient's alerts",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such patient (administrators only)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<AiAlertResponse>> listForPatient(
            @Parameter(description = "The patient", example = "PAT-00001", schema = @Schema(pattern = PATIENT_ID_PATTERN))
            @PathVariable("patientSystemId") String patientSystemId,
            @Parameter(description = "Zero-based page; a negative value is treated as 0")
            @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(inboxService.listForPatient(patientSystemId, page, size));
    }
}
