package com.acquira.common.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Stateless AES-256-GCM helper for property-level secret encryption.
 *
 * Produces / consumes EXACTLY the same token format as
 * {@link com.acquira.common.service.CryptoService}:
 *
 *     "enc:v1:" + Base64( IV(12 bytes) || ciphertext+tag(N+16 bytes) )
 *
 * so a value encrypted by the standalone {@code SecretEncryptorTool} can be
 * decrypted by the running app, and vice-versa.
 *
 * It is deliberately pure-JDK and static (no Spring, no Lombok) because it has
 * to run in two places that have no application context:
 *   1. the {@code SecretEncryptorTool} CLI, and
 *   2. the {@code SecretsEnvironmentPostProcessor}, which runs before the
 *      Spring container is built.
 *
 * The master key is supplied by the caller (env var / property) — this class
 * never owns or defaults it.
 */
public final class SecretCrypto {

    public static final String ENC_PREFIX = "enc:v1:";

    private static final String AES_ALGO    = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN  = 12;
    private static final int    GCM_TAG_LEN = 128;

    private SecretCrypto() { }

    /** True if the value is one of our {@value #ENC_PREFIX} tokens. */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    /**
     * Turn a raw key string into a 32-byte AES key (first 32 bytes of the
     * UTF-8 encoding). Mirrors CryptoService so keys are interchangeable.
     */
    private static SecretKeySpec toKey(String rawKey) {
        if (rawKey == null) {
            throw new IllegalArgumentException("Encryption key is null. Set APP_ENCRYPTION_KEY (or app.encryption.key).");
        }
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("Encryption key must be at least 32 characters for AES-256 (got " + keyBytes.length + ").");
        }
        byte[] key256 = new byte[32];
        System.arraycopy(keyBytes, 0, key256, 0, 32);
        return new SecretKeySpec(key256, "AES");
    }

    /** Encrypt plaintext → {@value #ENC_PREFIX} token. Idempotent: an already-encrypted token is returned unchanged. */
    public static String encrypt(String plainText, String rawKey) {
        if (plainText == null) return null;
        if (isEncrypted(plainText)) return plainText;
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, toKey(rawKey), new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ct = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[GCM_IV_LEN + ct.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LEN);
            System.arraycopy(ct, 0, combined, GCM_IV_LEN, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /** Decrypt a {@value #ENC_PREFIX} token. A non-token (plaintext) value is returned as-is. */
    public static String decrypt(String token, String rawKey) {
        if (token == null) return null;
        if (!isEncrypted(token)) return token;
        try {
            byte[] combined = Base64.getDecoder().decode(token.substring(ENC_PREFIX.length()));
            byte[] iv = new byte[GCM_IV_LEN];
            byte[] ct = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(combined, GCM_IV_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, toKey(rawKey), new GCMParameterSpec(GCM_TAG_LEN, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed — key mismatch or corrupt token", e);
        }
    }
}
