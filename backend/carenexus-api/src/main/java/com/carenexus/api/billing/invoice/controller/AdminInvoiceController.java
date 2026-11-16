package com.carenexus.api.billing.invoice.controller;

import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
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
 * Administrator invoice list (ADMIN only: route rule {@code /api/admin/**} plus the service check).
 *
 * - GET /api/admin/billing/invoices?status=&patientSystemId=&page=&size=   every invoice, every status
 *
 * The detail panel uses GET /api/billing/invoices/{id}.
 */
@RestController
@RequestMapping("/api/admin/billing")
@Validated
@RequiredArgsConstructor
@Tag(name = "Admin: billing", description = "Invoice overview for administrators")
public class AdminInvoiceController {

    private final InvoiceService invoiceService;

    @GetMapping("/invoices")
    @Operation(summary = "List every invoice",
            description = "ADMIN only. Every status; optional status (one or a comma-separated list) and patient "
                    + "filters. Newest first. One READ audit row per patient on the page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of invoices"),
            @ApiResponse(responseCode = "400", description = "Invalid patientSystemId or unknown status",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<InvoiceResponse>> listAllInvoices(
            @Parameter(description = "One status or a comma-separated list, case-insensitive")
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String patientSystemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(invoiceService.listAll(status, patientSystemId, page, size));
    }
}
