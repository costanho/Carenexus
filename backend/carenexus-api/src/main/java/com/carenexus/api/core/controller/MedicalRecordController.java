package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.core.dto.request.CreateMedicalRecordRequest;
import com.carenexus.api.core.dto.request.UpdateMedicalRecordRequest;
import com.carenexus.api.core.dto.response.MedicalRecordResponse;
import com.carenexus.api.core.service.MedicalRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Medical records. Every access rule is enforced in {@link MedicalRecordService}.
 *
 * - POST   /api/medical-records                       file a record (DOCTOR; Idempotency-Key)
 * - GET    /api/medical-records?patientSystemId=&doctorSystemId=&type=&consultationId=&q=&page=&size=   paged list
 * - GET    /api/medical-records/{id}                   one record
 * - PUT    /api/medical-records/{id}                   partial update (author or admin)
 * - DELETE /api/medical-records/{id}                   soft delete (author or admin)
 * - GET    /api/medical-records/patient/{id}, /doctor/{id}   deprecated arrays (capped at 200)
 */
@RestController
@RequestMapping("/api/medical-records")
@RequiredArgsConstructor
@Validated
@Tag(name = "Medical records", description = "Clinical records filed by doctors or created when a consultation is completed")
public class MedicalRecordController {

    static final String CREATE_SCOPE = "POST /api/medical-records";
    private static final String SYSTEM_ID_OR_BLANK = "^((PAT|DOC|CGV|ADM)-\\d{5,})?$";
    private static final String TYPE_OR_BLANK =
            "^\\s*(CONSULT|LAB|IMAGING|PRESCRIPTION|REFERRAL|DISCHARGE|NOTE|OPERATION)?\\s*$";

    private final MedicalRecordService medicalRecordService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "File a medical record",
            description = "DOCTOR only: an active clinician with a care relationship to the patient; consultationId, "
                    + "when given, must be a consultation of this patient. The author is always the caller "
                    + "(doctorSystemId in the body is ignored). ICD codes are checked with the terminology provider: "
                    + "well-formed codes outside the catalogue are stored as unverified. At most one live CONSULT "
                    + "record per consultation. Audited (CREATE). With an Idempotency-Key a repeat returns the stored "
                    + "response with 200 and creates nothing.",
            parameters = @Parameter(name = IdempotencyService.HEADER, in = ParameterIn.HEADER, required = false,
                    description = "8 to 64 characters of [A-Za-z0-9_-]; one key per form open",
                    schema = @Schema(type = "string")))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The record was filed"),
            @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key"),
            @ApiResponse(responseCode = "400", description = "Invalid body (code VALIDATION_FAILED), malformed body or key, "
                    + "or the consultation belongs to another patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not a doctor, inactive clinician, or no care relationship",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Patient, consultation or doctor profile not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The consultation already has a CONSULT record, or the same "
                    + "Idempotency-Key is still being processed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicalRecordResponse> createRecord(
            @Valid @RequestBody CreateMedicalRecordRequest request,
            @Parameter(hidden = true) @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        IdempotentResult<MedicalRecordResponse> result = idempotencyService.execute(
                CREATE_SCOPE, idempotencyKey, MedicalRecordResponse.class,
                () -> medicalRecordService.createRecord(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping
    @StandardErrors
    @Operation(summary = "List medical records (paged, newest first)",
            description = "PATIENT: own records (patientSystemId defaults to the caller). CAREGIVER: patientSystemId "
                    + "required, grant must allow records. DOCTOR: doctorSystemId must be the caller (own records); "
                    + "with only patientSystemId, that patient's records from every author (care relationship "
                    + "required); with neither, own records. ADMIN: everything. Filters combine with AND; deleted "
                    + "records are never listed. Sorted createdAt then recordId, descending. Audited (READ per patient).")
    @ApiResponse(responseCode = "200", description = "One page of medical record rows")
    public ResponseEntity<PageResponse<MedicalRecordResponse>> searchRecords(
            @Parameter(description = "Patient system id")
            @RequestParam(required = false)
            @Pattern(regexp = SYSTEM_ID_OR_BLANK, message = "must be a valid system id") String patientSystemId,
            @Parameter(description = "Author doctor's system id")
            @RequestParam(required = false)
            @Pattern(regexp = SYSTEM_ID_OR_BLANK, message = "must be a valid system id") String doctorSystemId,
            @Parameter(description = "One record type (case-insensitive)")
            @RequestParam(required = false)
            @Pattern(regexp = TYPE_OR_BLANK, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = CreateMedicalRecordRequest.RECORD_TYPE_MESSAGE) String type,
            @Parameter(description = "Consultation id (pure filter)")
            @RequestParam(required = false)
            @Positive(message = "must be a positive number") Integer consultationId,
            @Parameter(description = "Case-insensitive text in the title or in an ICD code or its description")
            @RequestParam(required = false)
            @Size(max = 100, message = "must be at most 100 characters") String q,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(medicalRecordService.searchRecords(
                patientSystemId, doctorSystemId, type, consultationId, q, page, size));
    }

    @GetMapping("/{id}")
    @StandardErrors
    @Operation(summary = "Get one medical record",
            description = "The authoring doctor, or anyone who may read the patient's records (the patient, a related "
                    + "doctor, a caregiver whose grant allows records, an administrator). A deleted record answers "
                    + "404. Audited (READ).")
    @ApiResponse(responseCode = "200", description = "The medical record")
    public ResponseEntity<MedicalRecordResponse> getRecordById(@PathVariable Integer id) {
        return ResponseEntity.ok(medicalRecordService.getRecordById(id));
    }

    @PutMapping("/{id}")
    @StandardErrors
    @Operation(summary = "Update a medical record",
            description = "The authoring doctor or an administrator. Partial update: absent or null fields are "
                    + "unchanged, a blank optional text clears it, icdCodes replaces the whole list ([] removes all). "
                    + "Patient, author, consultation and source never change. A deleted record answers 404. "
                    + "Audited (UPDATE).")
    @ApiResponse(responseCode = "200", description = "The updated medical record")
    public ResponseEntity<MedicalRecordResponse> updateRecord(
            @PathVariable Integer id,
            @Valid @RequestBody UpdateMedicalRecordRequest request) {
        return ResponseEntity.ok(medicalRecordService.updateRecord(id, request));
    }

    @DeleteMapping("/{id}")
    @StandardErrors
    @Operation(summary = "Delete a medical record (soft delete)",
            description = "The authoring doctor or an administrator. The row is kept and marked deleted; from then "
                    + "on it answers 404 everywhere (a repeated DELETE is 404). Audited (DELETE).")
    @ApiResponse(responseCode = "204", description = "The record was deleted")
    public ResponseEntity<Void> softDeleteRecord(@PathVariable Integer id) {
        medicalRecordService.softDeleteRecord(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @deprecated use GET /api/medical-records?patientSystemId= (kept while the native screens and e2e call it)
     */
    @Deprecated
    @GetMapping("/patient/{patientSystemId}")
    @StandardErrors
    @Operation(summary = "List a patient's medical records (legacy array)", deprecated = true,
            description = "Anyone who may read the patient's records. Bare array, newest first, at most 200 rows. "
                    + "Use GET /api/medical-records instead. Audited (READ).")
    @ApiResponse(responseCode = "200", description = "The medical record list (JSON array)")
    public ResponseEntity<List<MedicalRecordResponse>> getPatientRecords(@PathVariable String patientSystemId) {
        return ResponseEntity.ok(medicalRecordService.getPatientRecords(patientSystemId));
    }

    /**
     * @deprecated use GET /api/medical-records?doctorSystemId= (kept while the native screens and e2e call it)
     */
    @Deprecated
    @GetMapping("/doctor/{doctorSystemId}")
    @StandardErrors
    @Operation(summary = "List records a doctor authored (legacy array)", deprecated = true,
            description = "That doctor or an administrator. Bare array, newest first, at most 200 rows. Use "
                    + "GET /api/medical-records instead. Audited (READ per patient).")
    @ApiResponse(responseCode = "200", description = "The medical record list (JSON array)")
    public ResponseEntity<List<MedicalRecordResponse>> getDoctorRecords(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(medicalRecordService.getDoctorRecords(doctorSystemId));
    }
}
