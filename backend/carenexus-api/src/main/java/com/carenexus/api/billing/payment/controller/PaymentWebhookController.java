package com.carenexus.api.billing.payment.controller;

import com.carenexus.api.billing.payment.PaymentWebhookService;
import com.carenexus.api.billing.payment.dto.WebhookAckResponse;
import com.carenexus.api.billing.provider.InvalidWebhookSignatureException;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.carenexus.api.billing.provider.gateway.MalformedWebhookPayloadException;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Payment gateway callback (public path, permitted in SecurityConfig; authenticated by a signature over the raw
 * body). The body is read raw, at most 64 KB, so the gateway verifies the exact bytes. Neither the body nor the
 * headers are ever logged or stored; a bearer token sent by mistake is ignored.
 * <p>
 * Check order: provider (404), size (400), signature (401), parse (400).
 */
@Slf4j
@RestController
@RequestMapping("/api/integrations/payments")
@RequiredArgsConstructor
@Tag(name = "Billing: payment gateway callback", description = "Signed callbacks of the payment gateway")
public class PaymentWebhookController {

    static final int MAX_BODY_BYTES = 64 * 1024;
    static final String NOT_FOUND = "Not found";
    static final String TOO_LARGE = "Webhook body is too large";
    static final String INVALID_SIGNATURE = "Invalid webhook signature";
    static final String MALFORMED = "Malformed webhook payload";

    private final PaymentGateway paymentGateway;
    private final PaymentWebhookService webhookService;

    @PostMapping(value = "/{provider}/webhook", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(summary = "Receive a payment gateway callback",
            description = "Called by the payment gateway, not by users: no bearer token. {provider} must be the "
                    + "active gateway (local: header X-CareNexus-Signature: sha256=<hex HMAC-SHA256 of the raw body>). "
                    + "Idempotent by event id; a payment that is no longer PENDING never changes. Answers "
                    + "{\"received\": true} for every correctly signed, parseable event, whatever its outcome.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The event was received"),
            @ApiResponse(responseCode = "400", description = "Body too large or not the gateway's event format",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid signature",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "The provider is not the active, configured gateway",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<WebhookAckResponse> receive(@PathVariable("provider") String provider,
                                                      HttpServletRequest request) {
        if (!paymentGateway.name().equals(provider) || !paymentGateway.isConfigured()) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        String body = readBody(request);
        try {
            webhookService.receive(body, lowerCasedHeaders(request));
        } catch (InvalidWebhookSignatureException e) {
            log.warn("Payment webhook rejected: invalid signature (provider {})", provider);
            throw new ApiException(HttpStatus.UNAUTHORIZED, null, INVALID_SIGNATURE);
        } catch (MalformedWebhookPayloadException e) {
            log.warn("Payment webhook rejected: malformed payload (provider {})", provider);
            throw new BadRequestException(MALFORMED);
        }
        return ResponseEntity.ok(WebhookAckResponse.RECEIVED);
    }

    /** The raw UTF-8 body, read up to one byte past the limit so a larger body is refused without buffering it. */
    private static String readBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new BadRequestException(TOO_LARGE);
        }
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new BadRequestException(MALFORMED);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new BadRequestException(TOO_LARGE);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, String> lowerCasedHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.putIfAbsent(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }
}
