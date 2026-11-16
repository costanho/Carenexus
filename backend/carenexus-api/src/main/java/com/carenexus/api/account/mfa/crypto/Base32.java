package com.carenexus.api.account.mfa.crypto;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * RFC 4648 Base32 (alphabet A-Z, 2-7) without padding, the encoding authenticator apps expect for TOTP secrets.
 */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32() {
    }

    /** Upper-case Base32 without padding (a 20-byte secret gives 32 characters). */
    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return out.toString();
    }

    /**
     * Decodes Base32, ignoring case, spaces, hyphens and trailing padding.
     *
     * @throws IllegalArgumentException for any other character
     */
    public static byte[] decode(String text) {
        String clean = text == null ? "" : text.replaceAll("[\\s-]", "").replaceAll("=+$", "")
                .toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int value;
            if (c >= 'A' && c <= 'Z') {
                value = c - 'A';
            } else if (c >= '2' && c <= '7') {
                value = c - '2' + 26;
            } else {
                throw new IllegalArgumentException("Not a Base32 character at position " + i);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
