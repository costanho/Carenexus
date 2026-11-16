package com.carenexus.api.billing.method.controller;

import com.carenexus.api.billing.method.AdminPaymentService;
import com.carenexus.api.billing.method.dto.AdminPaymentResponse;
import com.carenexus.api.billing.method.dto.ManualPaymentRequest;
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
 * Administrator payment back office (ADMIN only: route rule {@code /api/admin/**} plus the service check).
 *
 * - GET  /api/admin/billing/payments?status=&method=&patientSystemId=&page=&size=   every payment
 * - POST /api/admin/billing/invoices/{id}/payments/manual                          record cash or a bank transfer
 */
@RestController
@RequestMapping("/api/admin/billing")
@Validated
@RequiredArgsConstructor
@Tag(name = "Admin: payments", description = "Payment overview and manual payments for administrators")
public class AdminPaymentController {

    /** Scope prefix; the concrete invoice id is appended so a key reused on another invoice never replays. */
    static final String MANUAL_SCOPE_PREFIX = "POST /api/admin/billing/invoices/";
    static final String MANUAL_SCOPE_SUFFIX = "/payments/manual";

    private final AdminPaymentService adminPaymentService;
    private final IdempotencyService idempotencyService;

    @GetMapping("/payments")
    @Operation(summary = "List every payment",
            description = "ADMIN only. Every patient and status; optional status and method filters (one value or a "
                    + "comma-separated list, case-insensitive) and a patient filter. Newest first. One READ audit row "
                    + "per patient on the page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of payments"),
            @ApiResponse(responseCode = "400", description = "Invalid patientSystemId, unknown status or unknown method",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<AdminPaymentResponse>> listPayments(
            @Parameter(description = "PENDING, COMPLETED, FAILED, REFUNDED, CANCELLED; one or a comma-separated list")
            @RequestParam(required = false) String status,
            @Parameter(description = "CARD, INSURANCE, CASH, MPESA, BANK_TRANSFER; one or a comma-separated list")
            @RequestParam(required = false) String method,
            @Parameter(description = "Billed patient (plain filter, no existence check)")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String patientSystemId,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminPaymentService.list(status, method, patientSystemId, page, size));
    }

    @PostMapping("/invoices/{id}/payments/manual")
    @StandardErrors
    @Operation(summary = "Record a cash or bank-transfer payment",
            description = "ADMIN only. Creates a COMPLETED payment (gateway MANUAL, the invoice's currency, paidAt = "
                    + "now), applies it to the invoice (PARTIALLY_PAID or PAID) and publishes PaymentRecordedEvent, in "
                    + "one transaction. reference is required for BANK_TRANSFER. 409 when the invoice cannot be paid "
                    + "or the amount is above the outstanding balance. Optional Idempotency-Key header scoped to this "
                    + "invoice (replay answers 200 with the stored body, no second payment). Audited as CREATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The payment was recorded"),
            @ApiResponse(responseCode = "200", description = "The Idempotency-Key was replayed: the stored response")
    })
    public ResponseEntity<AdminPaymentResponse> recordManualPayment(
            @PathVariable("id") Integer id,
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ManualPaymentRequest request) {
        String scope = MANUAL_SCOPE_PREFIX + id + MANUAL_SCOPE_SUFFIX;
        IdempotentResult<AdminPaymentResponse> result = idempotencyService.execute(scope, idempotencyKey,
                AdminPaymentResponse.class, () -> adminPaymentService.recordManual(id, request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }
}
