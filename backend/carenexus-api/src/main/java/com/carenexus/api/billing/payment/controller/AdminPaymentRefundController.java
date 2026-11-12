package com.carenexus.api.billing.payment.controller;

import com.carenexus.api.billing.payment.PaymentRefundService;
import com.carenexus.api.billing.payment.dto.PaymentResponse;
import com.carenexus.api.billing.payment.dto.RefundPaymentRequest;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.StandardErrors;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator refunds of completed payments (ADMIN only through the /api/admin/** URL gate and the service).
 *
 * - POST /api/admin/billing/payments/{id}/refund   refund in full or in part (Idempotency-Key)
 */
@RestController
@RequestMapping("/api/admin/billing/payments")
@RequiredArgsConstructor
@Tag(name = "Admin: billing payments", description = "Refunds of completed payments")
public class AdminPaymentRefundController {

    static final String REFUND_SCOPE = "POST /api/admin/billing/payments/{id}/refund";

    private final PaymentRefundService refundService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/{id}/refund")
    @StandardErrors
    @Operation(summary = "Refund a completed payment",
            description = "ADMIN only. Refunds a COMPLETED, non-insurance payment linked to an invoice, in full "
                    + "(amount omitted) or in part. Lowers the invoice's amount paid, asks the gateway to return the "
                    + "money when it handled the payment, raises refundedAmount (status REFUNDED once nothing is left) "
                    + "and publishes the REFUNDED payment event, all in one transaction. Optional Idempotency-Key "
                    + "(replay answers 200 with the stored body). Audited as UPDATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The refunded payment (also for a replayed key)"),
            @ApiResponse(responseCode = "503", description = "The gateway refund failed; nothing changed "
                    + "(PROVIDER_UNAVAILABLE)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PaymentResponse> refundPayment(
            @PathVariable("id") Integer id,
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]; one per Refund dialog open")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RefundPaymentRequest request) {
        IdempotentResult<PaymentResponse> result = idempotencyService.execute(REFUND_SCOPE, idempotencyKey,
                PaymentResponse.class, () -> refundService.refund(id, request, idempotencyKey));
        return ResponseEntity.ok(result.body());
    }
}
