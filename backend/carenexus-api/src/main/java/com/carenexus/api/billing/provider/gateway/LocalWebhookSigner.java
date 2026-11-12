package com.carenexus.api.billing.provider.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Signature of the local gateway's callback: header {@value #HEADER} with the value
 * {@code sha256=<lower-case hex of HMAC-SHA256(key = webhook secret, message = the exact raw body bytes)>}.
 * Used by {@code LocalPaymentGateway} to verify a callback and by the dev-only simulation to sign one.
 * There is no timestamp: a replayed body is harmless because events are idempotent by event id.
 */
public final class LocalWebhookSigner {

    /** Header carrying the signature. */
    public static final String HEADER = "X-CareNexus-Signature";

    /** Lower-cased header name, as handed to {@code parseWebhook}. */
    public static final String HEADER_LOWER = HEADER.toLowerCase(Locale.ROOT);

    static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int SIGNATURE_HEX_LENGTH = 64;

    private LocalWebhookSigner() {
    }

    /** {@code sha256=<hex>} for this body. */
    public static String sign(String secret, String rawBody) {
        return PREFIX + HexFormat.of().formatHex(hmac(secret, rawBody));
    }

    /**
     * Constant-time check of a header value against the body. False for a missing, malformed or wrong signature,
     * and when there is no secret.
     */
    public static boolean verify(String secret, String rawBody, String headerValue) {
        if (secret == null || secret.isBlank() || headerValue == null) {
            return false;
        }
        String value = headerValue.trim();
        if (!value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return false;
        }
        String hex = value.substring(PREFIX.length());
        if (hex.length() != SIGNATURE_HEX_LENGTH) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, rawBody == null ? "" : rawBody), presented);
    }

    /** Lower-case hex SHA-256 of the body: the event id of a callback that does not carry one. */
    public static String sha256Hex(String rawBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((rawBody == null ? "" : rawBody).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] hmac(String secret, String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
