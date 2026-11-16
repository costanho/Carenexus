package com.carenexus.api.account.mfa.crypto;

import com.carenexus.api.account.mfa.config.MfaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts TOTP secrets at rest with AES-256-GCM, key {@code carenexus.auth.mfa-encryption-key} (Base64 of 32
 * bytes).
 *
 * Stored form: {@code v1:<Base64(iv || ciphertext || tag)>} with a random 12-byte IV and a 128-bit tag. The account's
 * system id is bound as additional authenticated data, so a secret copied onto another account's row does not
 * decrypt. A missing or malformed key fails startup.
 */
@Component
public class MfaSecretCipher {

    static final String PREFIX = "v1:";
    static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    @Autowired
    public MfaSecretCipher(MfaProperties properties) {
        this(parseKey(properties.getMfaEncryptionKey()));
    }

    MfaSecretCipher(byte[] rawKey) {
        if (rawKey == null || rawKey.length != KEY_BYTES) {
            throw new IllegalStateException("MFA encryption key must be " + KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(rawKey.clone(), "AES");
    }

    /**
     * Decodes the configured key. Throws {@link IllegalStateException} (startup failure) with a hint when it is
     * missing, not Base64 or not 32 bytes long.
     */
    public static byte[] parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("carenexus.auth.mfa-encryption-key is not set; set AUTH_MFA_ENCRYPTION_KEY "
                    + "to Base64 of 32 random bytes (for example `openssl rand -base64 32`)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("carenexus.auth.mfa-encryption-key is not valid Base64; set "
                    + "AUTH_MFA_ENCRYPTION_KEY to Base64 of 32 random bytes");
        }
        if (raw.length != KEY_BYTES) {
            Arrays.fill(raw, (byte) 0);
            throw new IllegalStateException("carenexus.auth.mfa-encryption-key must decode to " + KEY_BYTES
                    + " bytes; set AUTH_MFA_ENCRYPTION_KEY to Base64 of 32 random bytes");
        }
        return raw;
    }

    /** Encrypts {@code secret} for the account {@code systemId}. */
    public String encrypt(byte[] secret, String systemId) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(systemId));
            byte[] encrypted = cipher.doFinal(secret);
            ByteBuffer out = ByteBuffer.allocate(iv.length + encrypted.length);
            out.put(iv).put(encrypted);
            return PREFIX + Base64.getEncoder().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the MFA secret", e);
        }
    }

    /**
     * Decrypts a value written by {@link #encrypt} for the same account. Throws {@link IllegalStateException} when
     * the value was tampered with, belongs to another account or was written with another key.
     */
    public byte[] decrypt(String stored, String systemId) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unsupported MFA secret format");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (data.length <= IV_BYTES) {
                throw new IllegalStateException("Unsupported MFA secret format");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            cipher.updateAAD(aad(systemId));
            return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Never include the value or the cause text: both can carry key material fragments.
            throw new IllegalStateException("Could not decrypt the MFA secret (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static byte[] aad(String systemId) {
        return ("carenexus-mfa:" + (systemId == null ? "" : systemId)).getBytes(StandardCharsets.UTF_8);
    }
}
