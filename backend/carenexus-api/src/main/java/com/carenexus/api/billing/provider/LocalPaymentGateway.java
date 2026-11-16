package com.carenexus.api.billing.provider;

import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.provider.gateway.LocalWebhookSigner;
import com.carenexus.api.billing.provider.gateway.MalformedWebhookPayloadException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The built-in gateway ({@code carenexus.billing.provider=local}). It never contacts anything outside the process
 * and moves no money:
 * <ul>
 *   <li>CASH, BANK_TRANSFER and INSURANCE complete at once ({@code COMPLETED}, next action NONE);</li>
 *   <li>MPESA answers {@code PENDING} with STK_PUSH and CARD answers {@code PENDING} with AWAIT_CONFIRMATION. Both
 *       settle through the same webhook path a real gateway uses: a JSON callback signed with HMAC-SHA256 under
 *       {@code carenexus.billing.local.webhook-secret} ({@link LocalWebhookSigner}), sent by the dev-only simulation
 *       or by any tool that knows the secret.</li>
 * </ul>
 * Without a webhook secret the gateway cannot verify a callback, so it is not configured: the webhook answers 404
 * and only the methods that settle at once are offered. Refunds always succeed (nothing to return) and accept any
 * reference, including the free-text reference of a manual payment.
 */
public class LocalPaymentGateway implements PaymentGateway {

    public static final String NAME = BillingProperties.PROVIDER_LOCAL;

    /** Methods that complete at initiation. */
    static final Set<String> IMMEDIATE_METHODS = Set.of(PaymentMethod.TYPE_CASH, PaymentMethod.TYPE_BANK_TRANSFER,
            PaymentMethod.TYPE_INSURANCE);

    static final int MAX_EVENT_ID = 100;
    static final int MAX_REFERENCE = 255;
    static final int MAX_TRANSACTION_ID = 255;
    static final int MAX_FAILURE_REASON = 500;

    private final BillingProperties properties;
    private final ObjectMapper objectMapper;

    public LocalPaymentGateway(BillingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return properties.getLocal() != null && properties.getLocal().hasWebhookSecret();
    }

    /** Every method in the fixed order of {@link PaymentMethod#TYPES}; without a secret only the immediate ones. */
    @Override
    public Set<String> supportedMethods() {
        boolean configured = isConfigured();
        Set<String> methods = new LinkedHashSet<>();
        for (String type : PaymentMethod.TYPES) {
            if (configured || IMMEDIATE_METHODS.contains(type)) {
                methods.add(type);
            }
        }
        return Collections.unmodifiableSet(methods);
    }

    @Override
    public InitiateResult initiate(InitiateRequest request) {
        if (request == null || request.method() == null || request.amount() == null || request.amount().signum() <= 0) {
            throw new PaymentGatewayException("Local gateway: incomplete payment request", false);
        }
        String method = request.method().trim().toUpperCase(Locale.ROOT);
        if (!supportedMethods().contains(method)) {
            throw new PaymentGatewayException("Local gateway: method not available", false);
        }
        String reference = "local_pay_" + randomHex();
        if (IMMEDIATE_METHODS.contains(method)) {
            return new InitiateResult(reference, STATUS_COMPLETED, Payment.NEXT_ACTION_NONE, null, null);
        }
        String nextAction = PaymentMethod.TYPE_MPESA.equals(method)
                ? Payment.NEXT_ACTION_STK_PUSH
                : Payment.NEXT_ACTION_AWAIT_CONFIRMATION;
        return new InitiateResult(reference, STATUS_PENDING, nextAction, null, null);
    }

    @Override
    public RefundResult refund(String providerReference, BigDecimal amount, String currency, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentGatewayException("Local gateway: refund amount must be positive", false);
        }
        return new RefundResult(providerReference, STATUS_COMPLETED);
    }

    @Override
    public String tokenize(String method, Map<String, String> details) {
        return "local_pm_" + randomHex();
    }

    @Override
    public WebhookEvent parseWebhook(String rawBody, Map<String, String> headers) {
        String body = rawBody == null ? "" : rawBody;
        String signature = headers == null ? null : headers.get(LocalWebhookSigner.HEADER_LOWER);
        String secret = isConfigured() ? properties.getLocal().getWebhookSecret() : null;
        if (!LocalWebhookSigner.verify(secret, body, signature)) {
            throw new InvalidWebhookSignatureException();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new MalformedWebhookPayloadException();
        }
        if (root == null || !root.isObject()) {
            throw new MalformedWebhookPayloadException();
        }
        String eventType = text(root, "eventType", 100);
        String providerReference = text(root, "providerReference", MAX_REFERENCE);
        if (eventType == null || providerReference == null) {
            throw new MalformedWebhookPayloadException();
        }
        String eventId = text(root, "eventId", MAX_EVENT_ID);
        if (eventId == null) {
            eventId = LocalWebhookSigner.sha256Hex(body);
        }
        String transactionId = text(root, "transactionId", MAX_TRANSACTION_ID);
        String failureReason = text(root, "failureReason", MAX_FAILURE_REASON);
        return new WebhookEvent(eventId, eventType.toUpperCase(Locale.ROOT), providerReference, transactionId,
                failureReason);
    }

    /** Trimmed text of a field; null when absent, null or blank; malformed when not a string or too long. */
    private static String text(JsonNode root, String field, int maxLength) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new MalformedWebhookPayloadException();
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new MalformedWebhookPayloadException();
        }
        return value;
    }

    private static String randomHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
