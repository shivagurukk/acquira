package com.acquira.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption used across modules for at-rest secret
 * storage (S3 creds in tenant_setting, integration_connection passwords, etc.).
 *
 * Lives in acquira-common so any module can depend on it without creating
 * acquira-batch -> acquira-core back-references.
 *
 * Format produced by {@link #encrypt(String)}:
 *   Base64( IV(12 bytes) || ciphertext+tag(N+16 bytes) )
 * Stored as a single VARCHAR column. {@link #decrypt(String)} expects the same.
 *
 * Key source: {@code app.encryption.key} property. Must be at least 32 bytes
 * (UTF-8). Anything shorter fails fast at startup so we don't silently accept
 * a weak key.
 *
 * NOTE on the dev default: the publicly-known fallback key exists only so the
 * build runs out-of-the-box on a dev box. In prod/uat/staging profiles the
 * constructor refuses it (fail-fast at startup), because this key protects
 * stored external-DB / S3 / SMTP credentials \u2014 anyone with repo access plus a
 * DB read could decrypt them all if a deploy forgot APP_ENCRYPTION_KEY.
 */
@Service
@Slf4j
public class CryptoService {

    private static final String AES_ALGO    = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN  = 12;   // 96-bit IV recommended for GCM
    private static final int    GCM_TAG_LEN = 128;  // 128-bit auth tag

    /** The dev-only fallback key that ships in the repo; never allowed in protected profiles. */
    public static final String DEV_DEFAULT_KEY = "AcquiraDefaultEncryptKey32Chars!!";

    /** Profiles in which the public dev key must never be used. */
    private static final java.util.Set<String> PROTECTED_PROFILES =
            java.util.Set.of("prod", "uat", "staging");

    /**
     * Sentinel prefix written on encrypted values so we can detect
     * already-encrypted vs plaintext input on a re-save (the Integration
     * UI sends the value back unchanged when the user didn't edit the
     * password). Without this we'd double-encrypt on every save.
     *
     * Format: "enc:v1:" + Base64(IV || ciphertext+tag)
     */
    public static final String ENC_PREFIX = "enc:v1:";

    private final SecretKeySpec aesKey;

    public CryptoService(
            @Value("${app.encryption.key:AcquiraDefaultEncryptKey32Chars!!}") String rawKey,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        assertKeyAllowedInProfile(rawKey, activeProfiles, "app.encryption.key");
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                "app.encryption.key must be at least 32 characters for AES-256");
        }
        byte[] key256 = new byte[32];
        System.arraycopy(keyBytes, 0, key256, 0, 32);
        this.aesKey = new SecretKeySpec(key256, "AES");
        log.info("[CryptoService] AES-256-GCM key initialised");
    }

    /** Test / non-Spring convenience: no profile guard (treated as dev). */
    public CryptoService(String rawKey) {
        this(rawKey, "");
    }

    /**
     * Fail-fast when a protected profile boots on the public repo key. Runs at
     * bean construction so EVERY module using this service (core, batch, pdf)
     * is covered, not only the one that hosts SecurityStartupGuard.
     */
    public static void assertKeyAllowedInProfile(String rawKey, String activeProfiles, String propertyName) {
        if (activeProfiles == null || activeProfiles.isBlank()) return;
        boolean isProtected = java.util.Arrays.stream(activeProfiles.split("\\s*,\\s*"))
                .map(String::toLowerCase)
                .anyMatch(PROTECTED_PROFILES::contains);
        if (isProtected && DEV_DEFAULT_KEY.equals(rawKey)) {
            throw new IllegalStateException(
                "SECURITY: " + propertyName + " is the public dev default in a protected profile ("
                + activeProfiles + "). Set the APP_ENCRYPTION_KEY environment variable to a unique "
                + "32+ character secret before deploying. Stored credentials encrypted with the dev "
                + "key must be re-encrypted after rotation.");
        }
    }

    /**
     * Encrypt a plaintext string. Returns a self-describing token starting
     * with {@link #ENC_PREFIX}. Pass-through (no double-encrypt) if the input
     * is already an encrypted token.
     */
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        if (isEncrypted(plainText)) return plainText;  // idempotent re-save
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LEN, iv));

            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[GCM_IV_LEN + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LEN);
            System.arraycopy(cipherBytes, 0, combined, GCM_IV_LEN, cipherBytes.length);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a token produced by {@link #encrypt(String)}. If the input does
     * NOT carry the ENC_PREFIX it's assumed to be plaintext (legacy data from
     * before encryption was wired in) and returned as-is, with a warning so
     * ops can find the rows that still need rotation.
     */
    public String decrypt(String token) {
        if (token == null) return null;
        if (!isEncrypted(token)) {
            log.warn("[CryptoService] decrypt() called on plaintext value \u2014 legacy data needs re-encryption");
            return token;
        }
        try {
            String b64 = token.substring(ENC_PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(b64);

            byte[] iv          = new byte[GCM_IV_LEN];
            byte[] cipherBytes = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(combined, GCM_IV_LEN, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LEN, iv));

            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed \u2014 key mismatch or corrupt data", e);
        }
    }

    /** True if the value looks like a token produced by {@link #encrypt(String)}. */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }
}
