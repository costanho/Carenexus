package com.carenexus.api.billing.invoice.controller;

import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.invoice.dto.UninvoicedConsultationResponse;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling doctor's billing lists (DOCTOR only; an ADMIN passes the route rule and is refused by the service).
 *
 * - GET /api/doctors/billing/invoices?status=&patientSystemId=&consultationId=&appointmentId=&page=&size=
 *   invoices the caller issued, every status including DRAFT
 * - GET /api/doctors/billing/uninvoiced?page=&size=
 *   the caller's COMPLETED consultations that have no non-cancelled invoice, with the suggested fee
 */
@RestController
@RequestMapping("/api/doctors/billing")
@Validated
@RequiredArgsConstructor
@Tag(name = "Billing: doctor", description = "Invoices issued by the calling doctor and visits still to invoice")
public class DoctorInvoiceController {

    private final InvoiceService invoiceService;

    @GetMapping("/invoices")
    @Operation(summary = "List the invoices the calling doctor issued",
            description = "DOCTOR only. Every status including DRAFT; patientSystemId, consultationId and appointmentId "
                    + "are plain filters (lookup by consultation: consultationId=<id>&size=1). Newest first. One READ "
                    + "audit row per patient on the page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of invoices"),
            @ApiResponse(responseCode = "400", description = "Invalid patientSystemId or unknown status",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<InvoiceResponse>> listIssuedInvoices(
            @Parameter(description = "One status or a comma-separated list, case-insensitive")
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String patientSystemId,
            @RequestParam(required = false) Integer consultationId,
            @RequestParam(required = false) Integer appointmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(invoiceService.listIssuedByCaller(status, patientSystemId, consultationId,
                appointmentId, page, size));
    }

    @GetMapping("/uninvoiced")
    @Operation(summary = "List the calling doctor's completed consultations without an invoice",
            description = "DOCTOR only. COMPLETED consultations with no invoice in a status other than CANCELLED (by "
                    + "consultation or by its appointment), newest completion first, with the patient's name and the "
                    + "fee the invoice would be generated with. Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of consultations"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a doctor",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<UninvoicedConsultationResponse>> listUninvoiced(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(invoiceService.listUninvoiced(page, size));
    }
}
