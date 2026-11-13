package com.carenexus.api.account.mfa.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Random values of the MFA module and their stored hashes:
 * <ul>
 *   <li>challenge tokens: 256 random bits as 43 URL-safe Base64 characters; stored as SHA-256 hex;</li>
 *   <li>recovery codes: {@code XXXXX-XXXXX} from a 31-character alphabet without I, L, O, 0, 1; stored as the
 *       SHA-256 hex of the normalised form (upper case, no hyphen).</li>
 * </ul>
 */
public final class MfaTokens {

    /** Characters of a recovery code: no I, L, O, 0, 1, so codes read aloud or written down are unambiguous. */
    public static final String RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_HALF = 5;
    private static final Pattern RECOVERY_INPUT = Pattern.compile("^[A-Za-z0-9]{5}-?[A-Za-z0-9]{5}$");
    private static final int CHALLENGE_TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private MfaTokens() {
    }

    /** A new challenge token (43 URL-safe Base64 characters, no padding). */
    public static String newChallengeToken() {
        byte[] bytes = new byte[CHALLENGE_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the UTF-8 text, lower-case hex (64 characters). */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** {@link #RECOVERY_CODE_COUNT} distinct new recovery codes in display form {@code XXXXX-XXXXX}. */
    public static List<String> newRecoveryCodes() {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < RECOVERY_CODE_COUNT) {
            StringBuilder code = new StringBuilder(RECOVERY_HALF * 2 + 1);
            for (int i = 0; i < RECOVERY_HALF * 2; i++) {
                if (i == RECOVERY_HALF) {
                    code.append('-');
                }
                code.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            codes.add(code.toString());
        }
        return new ArrayList<>(codes);
    }

    /**
     * Normalised form of a recovery code as typed: trimmed, upper case, hyphen removed. Null when the input does
     * not have the shape of a recovery code.
     */
    public static String normaliseRecoveryCode(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (!RECOVERY_INPUT.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed.replace("-", "").toUpperCase(Locale.ROOT);
    }

    /** Stored hash of a recovery code (display or typed form); null when the input is not a recovery code. */
    public static String recoveryCodeHash(String input) {
        String normalised = normaliseRecoveryCode(input);
        return normalised == null ? null : sha256Hex(normalised);
    }
}
