package com.carenexus.api.account.mfa.service;

import com.carenexus.api.account.mfa.crypto.Base32;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Time-based one-time passwords, RFC 6238: HmacSHA1, 6 digits, 30-second steps, T0 = 0.
 *
 * A code is accepted for the current step and one step either side (clock drift of the phone). Replay protection:
 * a code is accepted only for a step greater than the last step accepted for the account, so the same code (or an
 * older one) never works twice. The caller stores the returned step.
 */
@Service
@RequiredArgsConstructor
public class TotpService {

    public static final String ISSUER = "CareNexus";
    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    /** Steps accepted on either side of the current one. */
    public static final int WINDOW = 1;
    /** 160-bit secrets (32 Base32 characters), the size RFC 4226 recommends for HmacSHA1. */
    public static final int SECRET_BYTES = 20;

    private static final Pattern CODE = Pattern.compile("^\\d{6}$");
    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;

    /** A new random secret. */
    public byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** The 30-second step of the current time. */
    public long currentStep() {
        return Math.floorDiv(clock.millis(), PERIOD_SECONDS * 1000L);
    }

    /**
     * Checks a 6-digit code against the current step and one step either side, skipping steps at or before
     * {@code lastUsedStep}. All candidate steps are computed, so the time taken does not depend on which matched.
     *
     * @param secret       raw secret
     * @param code         6 ASCII digits as typed (anything else never matches)
     * @param lastUsedStep last step accepted for the account, or null when none was
     * @return the matched step (store it as the new last used step), or empty when the code is wrong or replayed
     */
    public OptionalLong verify(byte[] secret, String code, Long lastUsedStep) {
        if (secret == null || code == null || !CODE.matcher(code).matches()) {
            return OptionalLong.empty();
        }
        byte[] typed = code.getBytes(StandardCharsets.US_ASCII);
        long current = currentStep();
        long matched = Long.MIN_VALUE;
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            byte[] expected = codeAt(secret, step, DIGITS).getBytes(StandardCharsets.US_ASCII);
            boolean equal = MessageDigest.isEqual(expected, typed);
            boolean fresh = lastUsedStep == null || step > lastUsedStep;
            if (equal && fresh && matched == Long.MIN_VALUE) {
                matched = step;
            }
        }
        return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    /**
     * The URI an authenticator app reads from the QR code:
     * {@code otpauth://totp/CareNexus:<email>?secret=..&issuer=CareNexus&algorithm=SHA1&digits=6&period=30}.
     *
     * @param accountLabel the account's email (the system id when the account has none)
     */
    public String otpauthUri(String accountLabel, String base32Secret) {
        return "otpauth://totp/" + ISSUER + ":" + urlEncode(accountLabel == null ? "" : accountLabel)
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(ISSUER)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** Base32 form of a secret, as shown for manual entry. */
    public String toBase32(byte[] secret) {
        return Base32.encode(secret);
    }

    /**
     * RFC 6238 / RFC 4226 code for one step: HMAC-SHA1 over the big-endian step counter, dynamic truncation,
     * modulo 10^digits, left-padded with zeros.
     */
    public static String codeAt(byte[] secret, long step, int digits) {
        if (digits < 1 || digits >= POWERS.length) {
            throw new IllegalArgumentException("digits must be between 1 and " + (POWERS.length - 1));
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % POWERS[digits];
            String text = Integer.toString(otp);
            StringBuilder padded = new StringBuilder(digits);
            for (int i = text.length(); i < digits; i++) {
                padded.append('0');
            }
            return padded.append(text).toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
