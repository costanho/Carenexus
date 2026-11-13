package com.carenexus.api.billing.claim.controller;

import com.carenexus.api.billing.claim.InsuranceClaimService;
import com.carenexus.api.billing.claim.dto.AdjudicateClaimRequest;
import com.carenexus.api.billing.claim.dto.InsuranceClaimResponse;
import com.carenexus.api.billing.claim.dto.VoidInvoiceRequest;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator claim queue, adjudication and invoice void (ADMIN only: route rule {@code /api/admin/**} plus the
 * service check).
 *
 * - GET   /api/admin/billing/claims?status=&patientSystemId=&invoiceId=&page=&size=   every claim
 * - PATCH /api/admin/billing/claims/{claimId}                                         adjudicate
 * - POST  /api/admin/billing/invoices/{id}/void                                       void an unpaid invoice
 */
@RestController
@RequestMapping("/api/admin/billing")
@Validated
@RequiredArgsConstructor
@Tag(name = "Admin: insurance claims", description = "Claim review queue, adjudication and invoice void")
public class AdminClaimController {

    private final InsuranceClaimService claimService;

    @GetMapping("/claims")
    @StandardErrors
    @Operation(summary = "List every insurance claim",
            description = "ADMIN only. Optional status (one or a comma-separated list; the review queue is "
                    + "SUBMITTED,PENDING,APPEALED), patient and invoice filters. Newest submission first. One READ "
                    + "audit row per patient on the page.")
    @ApiResponse(responseCode = "200", description = "One page of claims")
    public ResponseEntity<PageResponse<InsuranceClaimResponse>> listAllClaims(
            @Parameter(description = "One status or a comma-separated list, case-insensitive")
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String patientSystemId,
            @RequestParam(required = false) Integer invoiceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(claimService.listAll(status, patientSystemId, invoiceId, page, size));
    }

    @PatchMapping("/claims/{claimId}")
    @StandardErrors
    @Operation(summary = "Adjudicate an insurance claim",
            description = "ADMIN only. SUBMITTED or APPEALED to PENDING; PENDING to APPROVED, PARTIAL or REJECTED. "
                    + "APPROVED and PARTIAL settle the newly approved amount onto the invoice with a COMPLETED "
                    + "INSURANCE payment in the same transaction and publish PaymentRecordedEvent; every status change "
                    + "publishes InsuranceClaimStatusChangedEvent. Same status: no-op. Audited as UPDATE.")
    @ApiResponse(responseCode = "200", description = "The claim in its new status (or unchanged for a no-op)")
    public ResponseEntity<InsuranceClaimResponse> adjudicateClaim(@PathVariable("claimId") Integer claimId,
                                                                  @Valid @RequestBody AdjudicateClaimRequest request) {
        return ResponseEntity.ok(claimService.adjudicate(claimId, request));
    }

    @PostMapping("/invoices/{id}/void")
    @StandardErrors
    @Operation(summary = "Void an invoice that has received no payment",
            description = "ADMIN only. DRAFT, SENT or OVERDUE with nothing paid becomes CANCELLED with the reason; "
                    + "already CANCELLED is a no-op; 409 when the invoice has payments or is REFUNDED. Publishes "
                    + "InvoiceStatusChangedEvent; audited as UPDATE / INVOICE. Claims of the invoice are not changed.")
    @ApiResponse(responseCode = "200", description = "The cancelled invoice")
    public ResponseEntity<InvoiceResponse> voidInvoice(@PathVariable("id") Integer id,
                                                       @Valid @RequestBody VoidInvoiceRequest request) {
        return ResponseEntity.ok(claimService.voidInvoice(id, request.getReason()));
    }
}
