package com.carenexus.api.billing.method.controller;

import com.carenexus.api.billing.method.PaymentMethodService;
import com.carenexus.api.billing.method.dto.CreatePaymentMethodRequest;
import com.carenexus.api.billing.method.dto.PaymentMethodResponse;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Saved payment methods (masked: last four and label only).
 *
 * - GET    /api/billing/payment-methods                              the calling patient's methods
 * - POST   /api/billing/payment-methods                              save one (patient; Idempotency-Key)
 * - PATCH  /api/billing/payment-methods/{id}/default                 make one the default (patient)
 * - DELETE /api/billing/payment-methods/{id}                         remove one (patient; soft delete)
 * - GET    /api/billing/payment-methods/patient/{patientSystemId}    one patient's methods, read-only
 *                                                                    (the patient, caregiver with MANAGE_BILLING, admin)
 */
@RestController
@RequestMapping("/api/billing/payment-methods")
@Validated
@RequiredArgsConstructor
@Tag(name = "Billing: payment methods", description = "Saved payment methods of patients (masked)")
public class PaymentMethodController {

    static final String CREATE_SCOPE = "POST /api/billing/payment-methods";

    private final PaymentMethodService paymentMethodService;
    private final IdempotencyService idempotencyService;

    @GetMapping
    @StandardErrors
    @Operation(summary = "List the calling patient's saved payment methods",
            description = "PATIENT only (caregivers and administrators use /patient/{patientSystemId}). Active methods "
                    + "only, the default first, then newest first; at most 10. Masked. Audited as READ when not empty.")
    @ApiResponse(responseCode = "200", description = "The methods (possibly empty)",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PaymentMethodResponse.class))))
    public ResponseEntity<List<PaymentMethodResponse>> listMine() {
        return ResponseEntity.ok(paymentMethodService.listMine());
    }

    @PostMapping
    @StandardErrors
    @Operation(summary = "Save a payment method",
            description = "PATIENT only; the method belongs to the caller. CARD needs lastFour (provider optional), "
                    + "MPESA needs mpesaPhone, INSURANCE needs insurerName and policyNumber (memberNumber optional). "
                    + "A full card number, CVV, expiry or token is never accepted. The first method, or one saved with "
                    + "makeDefault true, becomes the default. 409 for an instrument already saved or above 10 methods. "
                    + "Optional Idempotency-Key header (replay answers 200 with the stored body). Audited as CREATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The method was saved"),
            @ApiResponse(responseCode = "200", description = "The Idempotency-Key was replayed: the stored response")
    })
    public ResponseEntity<PaymentMethodResponse> create(
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentMethodRequest request) {
        IdempotentResult<PaymentMethodResponse> result = idempotencyService.execute(CREATE_SCOPE, idempotencyKey,
                PaymentMethodResponse.class, () -> paymentMethodService.create(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @PatchMapping("/{id}/default")
    @StandardErrors
    @Operation(summary = "Make a saved payment method the default",
            description = "PATIENT only, own methods. The previous default loses the flag in the same transaction; "
                    + "already the default is a no-op. Any request body is ignored. Unknown, removed or another "
                    + "patient's method: 404. Audited as UPDATE.")
    @ApiResponse(responseCode = "200", description = "The method, now the default")
    public ResponseEntity<PaymentMethodResponse> makeDefault(@PathVariable("id") Integer id) {
        return ResponseEntity.ok(paymentMethodService.makeDefault(id));
    }

    @DeleteMapping("/{id}")
    @StandardErrors
    @Operation(summary = "Remove a saved payment method",
            description = "PATIENT only, own methods. Soft delete: payments that used it keep referencing it. When it "
                    + "was the default, the newest remaining method becomes the default. A repeated delete answers "
                    + "404. Audited as DELETE.")
    @ApiResponse(responseCode = "204", description = "Removed")
    public ResponseEntity<Void> delete(@PathVariable("id") Integer id) {
        paymentMethodService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List one patient's saved payment methods (read-only)",
            description = "The PATIENT (own id only), a CAREGIVER whose active grant allows managing billing "
                    + "(canManageBilling and not VIEW_ONLY, or FULL_ACCESS), or an ADMIN (any id; an unknown id has no "
                    + "methods). DOCTOR: 403. Same order and masking as the patient's own list. Audited as READ when "
                    + "not empty.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The methods (possibly empty)",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = PaymentMethodResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Malformed patientSystemId",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Doctor, another patient, or a caregiver without an active "
                    + "grant allowing billing management",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<PaymentMethodResponse>> listForPatient(
            @PathVariable("patientSystemId")
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String patientSystemId) {
        return ResponseEntity.ok(paymentMethodService.listForPatient(patientSystemId));
    }
}
