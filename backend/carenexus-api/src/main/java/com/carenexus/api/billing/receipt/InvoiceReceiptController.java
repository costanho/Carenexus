package com.carenexus.api.billing.receipt;

import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Printable invoice / receipt.
 *
 * - GET /api/billing/invoices/{id}/receipt   self-contained text/html document, as an attachment (patient: own issued
 *                                            invoices; caregiver with BILLING; issuing doctor; admin)
 *
 * Errors stay the JSON envelope whatever the Accept header says ({@link ReceiptErrorAdvice}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Billing", description = "Printable invoice and receipt documents")
public class InvoiceReceiptController {

    static final MediaType TEXT_HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    static final String CACHE_CONTROL = "private, no-store";
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; sandbox";

    private final InvoiceReceiptService receiptService;

    @GetMapping("/api/billing/invoices/{id}/receipt")
    @Operation(summary = "Printable invoice or receipt",
            description = "Everyone who may read the invoice (same rules, 403 and 404 answers as GET "
                    + "/api/billing/invoices/{id}): PATIENT (own, issued only), CAREGIVER with an active grant "
                    + "allowing billing (issued only), the issuing DOCTOR (any status), ADMIN. Returns one "
                    + "self-contained, script-free HTML document (heading 'Receipt' when the invoice is PAID, "
                    + "'Invoice' otherwise) as an attachment; it is rendered on every request and never stored. "
                    + "Lists the COMPLETED and REFUNDED payments of the invoice. Fetch it with the bearer token and "
                    + "print it from the client. Side effect: one READ / INVOICE audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document",
                    headers = {
                            @Header(name = "Content-Disposition",
                                    description = "attachment; filename=\"receipt-<invoiceNo>.html\" or "
                                            + "\"invoice-<invoiceNo>.html\""),
                            @Header(name = "Cache-Control", description = CACHE_CONTROL),
                            @Header(name = "Content-Security-Policy", description = CONTENT_SECURITY_POLICY)
                    },
                    content = @Content(mediaType = "text/html", schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = "The id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not read this invoice",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown invoice, or never issued (patients and caregivers)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<byte[]> getReceipt(@Parameter(description = "Invoice id") @PathVariable("id") Integer id) {
        InvoiceReceiptService.ReceiptDocument document = receiptService.render(id);
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .contentLength(document.body().length)
                .body(document.body());
    }
}
