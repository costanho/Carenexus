package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.core.dto.request.CreateMedicalRecordRequest;
import com.carenexus.api.core.dto.response.MedicalRecordResponse;
import com.carenexus.api.core.service.MedicalRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Legacy doctor view of medical records: only the root remains (an array of the calling doctor's records).
 * New clients use GET /api/medical-records?doctorSystemId=&lt;caller&gt;; the per-type, per-patient, by-id and stats
 * routes that used to live here had no callers and were removed.
 *
 * @deprecated use GET /api/medical-records
 */
@Deprecated
@RestController
@RequestMapping("/api/doctors/medical-records")
@RequiredArgsConstructor
@Validated
@Tag(name = "Medical records", description = "Clinical records filed by doctors or created when a consultation is completed")
public class DoctorMedicalRecordController {

    private final MedicalRecordService medicalRecordService;

    @Deprecated
    @GetMapping
    @StandardErrors
    @Operation(summary = "List the calling doctor's medical records (legacy array)", deprecated = true,
            description = "DOCTOR (an administrator gets an empty array). Records the caller authored, optionally "
                    + "narrowed by type and patient; bare array, newest first, at most 200 rows. Use "
                    + "GET /api/medical-records instead. Audited (READ per patient).")
    @ApiResponse(responseCode = "200", description = "The medical record list (JSON array)")
    public ResponseEntity<List<MedicalRecordResponse>> getMyMedicalRecords(
            @Parameter(description = "One record type (case-insensitive)")
            @RequestParam(required = false)
            @Pattern(regexp = "^\\s*(CONSULT|LAB|IMAGING|PRESCRIPTION|REFERRAL|DISCHARGE|NOTE|OPERATION)?\\s*$",
                    flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = CreateMedicalRecordRequest.RECORD_TYPE_MESSAGE) String type,
            @Parameter(description = "Patient system id")
            @RequestParam(required = false)
            @Pattern(regexp = "^((PAT|DOC|CGV|ADM)-\\d{5,})?$", message = "must be a valid system id") String patientSystemId) {
        return ResponseEntity.ok(medicalRecordService.getCallerDoctorRecords(type, patientSystemId));
    }
}
