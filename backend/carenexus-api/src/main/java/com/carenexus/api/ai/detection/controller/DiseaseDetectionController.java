package com.carenexus.api.ai.detection.controller;

import com.carenexus.api.ai.detection.dto.request.ReviewDetectionRequest;
import com.carenexus.api.ai.detection.dto.response.DetectionRunResponse;
import com.carenexus.api.ai.detection.dto.response.DiseaseDetectionResponse;
import com.carenexus.api.ai.detection.service.DiseaseDetectionService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Disease detections (api/be-ai-detection.md). Detections are written only by the screening pipeline (automatic
 * triggers and the manual run); there is no create, update, delete or cross-patient list. Role and ownership checks
 * are made in {@link DiseaseDetectionService}.
 *
 * - GET  /api/disease-detections/patient/{patientSystemId}?reviewStatus=&page=&size=
 * - GET  /api/disease-detections/{detectionId}
 * - POST /api/disease-detections/{detectionId}/review
 * - POST /api/disease-detections/patient/{patientSystemId}/run   201 created / 200 otherwise
 */
@RestController
@RequestMapping("/api/disease-detections")
@RequiredArgsConstructor
@Tag(name = "Disease Detection", description = "Automated rule-based screening results about a patient, the doctor "
        + "review workflow and manual screening runs. A screening result is not a diagnosis.")
public class DiseaseDetectionController {

    private static final String PATIENT_ID_PATTERN = "^PAT-\\d{5,}$";

    private final DiseaseDetectionService detectionService;

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List one patient's screening results, newest first",
            description = "A related DOCTOR and an ADMIN see every detection (reviewStatus filters them). The patient "
                    + "(own id) and caregivers whose active grant includes records (AI_ALERTS) see CONFIRMED detections "
                    + "only, without recommendations or review notes; reviewStatus is ignored for them. Sorted by "
                    + "creation time descending, then id. Size is clamped to 1..100. A non-empty page writes a READ "
                    + "DISEASE_DETECTION audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of detections"),
            @ApiResponse(responseCode = "400", description = "Malformed patient id, unknown reviewStatus, or a page/size "
                    + "that is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not see this patient's screening results",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such patient (administrators only)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<DiseaseDetectionResponse>> listForPatient(
            @Parameter(description = "The patient", example = "PAT-00001", schema = @Schema(pattern = PATIENT_ID_PATTERN))
            @PathVariable("patientSystemId") String patientSystemId,
            @Parameter(description = "Only this review status (any case); omit for all. Honoured for doctors and "
                    + "administrators only",
                    schema = @Schema(allowableValues = {"PENDING_REVIEW", "CONFIRMED", "REJECTED"}))
            @RequestParam(value = "reviewStatus", required = false) String reviewStatus,
            @Parameter(description = "Zero-based page; a negative value is treated as 0")
            @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(detectionService.listForPatient(patientSystemId, reviewStatus, page, size));
    }

    @GetMapping("/{detectionId}")
    @Operation(summary = "Get one screening result",
            description = "Same visibility as the patient list: a related DOCTOR and an ADMIN see any detection; the "
                    + "patient and caregivers with records access see a CONFIRMED detection only (a pending or "
                    + "rejected one answers 404). Writes a READ DISEASE_DETECTION audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The detection"),
            @ApiResponse(responseCode = "400", description = "The detection id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not see this patient's screening results",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such detection, or not visible to the patient or caregiver",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DiseaseDetectionResponse> get(
            @Parameter(description = "Id of the detection", example = "31") @PathVariable("detectionId") Integer detectionId) {
        return ResponseEntity.ok(detectionService.get(detectionId));
    }

    @PostMapping("/{detectionId}/review")
    @Operation(summary = "Confirm or reject a screening result",
            description = "DOCTOR only, related to the detection's patient. PENDING_REVIEW becomes CONFIRMED or "
                    + "REJECTED; both are final, so any later review answers 409. A confirmed detection (and its "
                    + "alert) becomes visible to the patient and permitted caregivers. Does not acknowledge the alert "
                    + "and sends no notification. Writes an UPDATE DISEASE_DETECTION audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The reviewed detection"),
            @ApiResponse(responseCode = "400", description = "Invalid id or body (decision, notes)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor, or not part of the patient's care team",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No detection with this id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The detection has already been reviewed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DiseaseDetectionResponse> review(
            @Parameter(description = "Id of the detection", example = "31") @PathVariable("detectionId") Integer detectionId,
            @Valid @RequestBody ReviewDetectionRequest request) {
        return ResponseEntity.ok(detectionService.review(detectionId, request));
    }

    @PostMapping("/patient/{patientSystemId}/run")
    @Operation(summary = "Run a screening for one patient now",
            description = "DOCTOR only, related to the patient. Screens the patient's recent symptom logs (14 days) and "
                    + "latest readings (7 days) with the same pipeline as the automatic triggers. 201 with "
                    + "DETECTION_CREATED when a new detection was stored (its DISEASE_RISK alert goes to every related "
                    + "doctor); 200 with DETECTION_EXISTS, NO_FINDING or NO_DATA otherwise (nothing written). 503 "
                    + "PROVIDER_UNAVAILABLE when screening is switched off, failed or timed out. Audited as CREATE or "
                    + "READ DISEASE_DETECTION.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "DETECTION_EXISTS, NO_FINDING or NO_DATA"),
            @ApiResponse(responseCode = "201", description = "DETECTION_CREATED"),
            @ApiResponse(responseCode = "400", description = "Malformed patient id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor, or not part of the patient's care team",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Screening is switched off or could not be completed "
                    + "(code PROVIDER_UNAVAILABLE)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DetectionRunResponse> run(
            @Parameter(description = "The patient", example = "PAT-00001", schema = @Schema(pattern = PATIENT_ID_PATTERN))
            @PathVariable("patientSystemId") String patientSystemId) {
        DiseaseDetectionService.RunResult result = detectionService.run(patientSystemId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.response());
    }
}
