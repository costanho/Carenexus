package com.carenexus.api.billing.payment.controller;

import com.carenexus.api.billing.payment.PaymentService;
import com.carenexus.api.billing.payment.dto.BillingConfigResponse;
import com.carenexus.api.billing.payment.dto.InitiatePaymentRequest;
import com.carenexus.api.billing.payment.dto.PaymentResponse;
import com.carenexus.api.billing.payment.dto.SimulatePaymentRequest;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
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

import java.util.Set;

/**
 * Gateway payments of invoices for patients, caregivers, the issuing doctor (reads) and administrators.
 *
 * - POST /api/billing/invoices/{invoiceId}/payments   start a payment (Idempotency-Key, bucket payment-initiate)
 * - GET  /api/billing/payments/{id}                   one payment (the Pay dialog polls it)
 * - GET  /api/billing/payments?patientSystemId=&page=&size=
 * - GET  /api/billing/invoices/{id}/payments?page=&size=
 * - GET  /api/billing/config                          gateway configuration for the Pay dialog
 * - POST /api/billing/payments/{id}/simulate          dev only: settle a pending local payment
 */
@RestController
@RequestMapping("/api/billing")
@Validated
@RequiredArgsConstructor
@Tag(name = "Billing: payments", description = "Payment initiation through the payment gateway, payment reads and "
        + "the gateway configuration")
public class PaymentController {

    static final String INITIATE_SCOPE = "POST /api/billing/invoices/{invoiceId}/payments";
    static final String RATE_BUCKET = "payment-initiate";
    static final java.util.regex.Pattern KEY_PATTERN = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    static final String KEY_MESSAGE = "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'";

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final RateLimitGuard rateLimitGuard;
    private final AuthorizationService authorizationService;
    private final Validator validator;

    @PostMapping("/invoices/{invoiceId}/payments")
    @StandardErrors
    @Operation(summary = "Start a payment for an invoice",
            description = "PATIENT (own invoice), CAREGIVER with an active grant allowing billing management, or "
                    + "ADMIN. Patients and caregivers pay with CARD or MPESA only; CASH, BANK_TRANSFER and INSURANCE "
                    + "are administrator-only. The invoice must be SENT, PARTIALLY_PAID or OVERDUE; amount defaults "
                    + "to the outstanding balance and may not exceed what the invoice's pending payments leave free. "
                    + "The gateway is called once: PENDING (settled later by the gateway callback), COMPLETED (the "
                    + "invoice is updated at once) or FAILED; every result answers 201. Optional Idempotency-Key "
                    + "(replay answers 200 with the stored body). Rate limited (bucket payment-initiate). Audited as "
                    + "CREATE.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The payment was created"),
            @ApiResponse(responseCode = "200", description = "The Idempotency-Key was replayed"),
            @ApiResponse(responseCode = "429", description = "Too many payment attempts",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "The payment gateway is unavailable (PROVIDER_UNAVAILABLE)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PaymentResponse> initiatePayment(
            @PathVariable("invoiceId") Integer invoiceId,
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]; one per Pay dialog open")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody InitiatePaymentRequest request) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()
                && !KEY_PATTERN.matcher(idempotencyKey.trim()).matches()) {
            throw new BadRequestException(KEY_MESSAGE);
        }
        rateLimitGuard.check(RATE_BUCKET, authorizationService.currentSystemId());
        IdempotentResult<PaymentResponse> result = idempotencyService.execute(INITIATE_SCOPE, idempotencyKey,
                PaymentResponse.class, () -> paymentService.initiate(invoiceId, request, idempotencyKey));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/payments/{id}")
    @StandardErrors
    @Operation(summary = "Get one payment",
            description = "PATIENT (own), CAREGIVER with an active grant allowing billing, the DOCTOR who issued the "
                    + "payment's invoice, ADMIN. The Pay dialog polls this while the payment is PENDING. Audited as READ.")
    @ApiResponse(responseCode = "200", description = "The payment")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable("id") Integer id) {
        return ResponseEntity.ok(paymentService.get(id));
    }

    @GetMapping("/payments")
    @StandardErrors
    @Operation(summary = "List the payments of one patient",
            description = "PATIENT (own; patientSystemId optional), CAREGIVER with billing access, ADMIN "
                    + "(patientSystemId required). DOCTOR: 403. Every invoice and status, newest first. One READ audit "
                    + "row per patient.")
    @ApiResponse(responseCode = "200", description = "One page of payments")
    public ResponseEntity<PageResponse<PaymentResponse>> listPayments(
            @Parameter(description = "Patient; defaults to the caller for a PATIENT, required otherwise")
            @RequestParam(required = false)
            @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id") String patientSystemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(paymentService.listForPatient(patientSystemId, page, size));
    }

    @GetMapping("/invoices/{id}/payments")
    @StandardErrors
    @Operation(summary = "List the payments of one invoice",
            description = "Everyone who may read the invoice (patient, caregiver with billing access, issuing doctor, "
                    + "admin), with the same 403 and 404 rules. Every status including failed attempts, newest first. "
                    + "Writes the invoice READ audit row and one payment READ row.")
    @ApiResponse(responseCode = "200", description = "One page of payments (empty when the invoice has none)")
    public ResponseEntity<PageResponse<PaymentResponse>> listInvoicePayments(
            @PathVariable("id") Integer id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(paymentService.listForInvoice(id, page, size));
    }

    @GetMapping("/config")
    @Operation(summary = "Get the payment gateway configuration",
            description = "Any signed-in user. Active gateway, the methods it accepts, the online subset patients and "
                    + "caregivers may use, the default currency and whether the dev-only simulation is available. "
                    + "Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The billing configuration"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<BillingConfigResponse> getConfig() {
        return ResponseEntity.ok(paymentService.config());
    }

    @PostMapping("/payments/{id}/simulate")
    @StandardErrors
    @Operation(summary = "Settle a pending local payment (development only)",
            description = "Only with the local gateway and a developer profile (local, docker, test); 404 otherwise. "
                    + "PATIENT (own), CAREGIVER with billing management, ADMIN. Builds, signs and processes the local "
                    + "gateway callback for the payment, exactly like the webhook. Audited as UPDATE.")
    @ApiResponse(responseCode = "200", description = "The payment in its new state")
    public ResponseEntity<PaymentResponse> simulatePayment(
            @PathVariable("id") Integer id,
            @RequestBody SimulatePaymentRequest request) {
        // Availability is checked before the body is validated: outside development the path does not exist.
        paymentService.requireSimulationAvailable();
        Set<ConstraintViolation<SimulatePaymentRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return ResponseEntity.ok(paymentService.simulate(id, request));
    }
}
