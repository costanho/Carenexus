package com.carenexus.api.billing.claim.controller;

import com.carenexus.api.billing.claim.InsuranceClaimService;
import com.carenexus.api.billing.claim.dto.AppealClaimRequest;
import com.carenexus.api.billing.claim.dto.InsuranceClaimResponse;
import com.carenexus.api.billing.claim.dto.SubmitClaimRequest;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Insurance claims for patients, caregivers and administrators. Doctors get 403 on every endpoint.
 *
 * - POST /api/billing/invoices/{invoiceId}/claims          submit (patient, caregiver with MANAGE_BILLING, admin; Idempotency-Key)
 * - GET  /api/billing/claims/{claimId}                     one claim (patient, caregiver with BILLING, admin)
 * - GET  /api/billing/claims?patientSystemId=&status=&invoiceId=&page=&size=   claims of one patient
 * - POST /api/billing/claims/{claimId}/appeal              appeal a REJECTED or PARTIAL decision
 */
@RestController
@RequestMapping("/api/billing")
@Validated
@RequiredArgsConstructor
@Tag(name = "Billing: insurance claims", description = "Submit, track and appeal insurance claims")
public class InsuranceClaimController {

    static final String SUBMIT_SCOPE = "POST /api/billing/invoices/{invoiceId}/claims";

    private final InsuranceClaimService claimService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/invoices/{invoiceId}/claims")
    @StandardErrors
    @Operation(summary = "Submit an insurance claim for an invoice",
            description = "The invoice's PATIENT, a CAREGIVER with MANAGE_BILLING, or an ADMIN; DOCTOR 403. The invoice "
                    + "must be SENT, PARTIALLY_PAID or OVERDUE with no open claim; claimAmount defaults to and must "
                    + "not exceed the outstanding balance; documentIds must be documents of the invoice's patient. "
                    + "Publishes InsuranceClaimStatusChangedEvent and notifies administrators. Optional "
                    + "Idempotency-Key (replay answers 200 with the stored body). Audited as CREATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The claim was submitted"),
            @ApiResponse(responseCode = "200", description = "The Idempotency-Key was replayed"),
            @ApiResponse(responseCode = "503", description = "The claims provider cannot be reached",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<InsuranceClaimResponse> submitClaim(
            @PathVariable("invoiceId") Integer invoiceId,
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody SubmitClaimRequest request) {
        IdempotentResult<InsuranceClaimResponse> result = idempotencyService.execute(SUBMIT_SCOPE, idempotencyKey,
                InsuranceClaimResponse.class, () -> claimService.submit(invoiceId, request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/claims/{claimId}")
    @StandardErrors
    @Operation(summary = "Get one insurance claim",
            description = "The claim's PATIENT, a CAREGIVER with BILLING, or an ADMIN (also the admin detail read); "
                    + "DOCTOR 403. Audited as READ.")
    @ApiResponse(responseCode = "200", description = "The claim")
    public ResponseEntity<InsuranceClaimResponse> getClaim(@PathVariable("claimId") Integer claimId) {
        return ResponseEntity.ok(claimService.get(claimId));
    }

    @GetMapping("/claims")
    @StandardErrors
    @Operation(summary = "List the insurance claims of one patient",
            description = "PATIENT (own; patientSystemId optional), CAREGIVER with BILLING, ADMIN (patientSystemId "
                    + "required); DOCTOR 403. Newest submission first. One READ audit row for the patient.")
    @ApiResponse(responseCode = "200", description = "One page of claims")
    public ResponseEntity<PageResponse<InsuranceClaimResponse>> listClaims(
            @Parameter(description = "Patient; defaults to the caller for a PATIENT, required otherwise")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String patientSystemId,
            @Parameter(description = "One status or a comma-separated list, case-insensitive")
            @RequestParam(required = false) String status,
            @Parameter(description = "Claims of one invoice")
            @RequestParam(required = false) Integer invoiceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(claimService.listForPatient(patientSystemId, status, invoiceId, page, size));
    }

    @PostMapping("/claims/{claimId}/appeal")
    @StandardErrors
    @Operation(summary = "Appeal a rejected or partly approved claim",
            description = "The claim's PATIENT, a CAREGIVER with MANAGE_BILLING, or an ADMIN; DOCTOR 403. REJECTED or "
                    + "PARTIAL becomes APPEALED; already APPEALED is a no-op. Refused while another claim of the "
                    + "invoice is open. Publishes InsuranceClaimStatusChangedEvent and notifies administrators. "
                    + "Audited as UPDATE.")
    @ApiResponse(responseCode = "200", description = "The appealed claim")
    public ResponseEntity<InsuranceClaimResponse> appealClaim(@PathVariable("claimId") Integer claimId,
                                                              @Valid @RequestBody AppealClaimRequest request) {
        return ResponseEntity.ok(claimService.appeal(claimId, request.getReason()));
    }
}
