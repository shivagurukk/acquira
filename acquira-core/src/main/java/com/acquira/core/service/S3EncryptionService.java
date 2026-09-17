package com.acquira.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Provides:
 *  1. AES-256-GCM encryption / decryption for S3 credentials stored in tenant_setting.
 *  2. Live S3 connection test (HeadBucket).
 *
 * Encryption key is derived from {@code app.encryption.key} (must be 32 chars / 256 bits).
 * Falls back to a dev key if not configured.
 */
@Service
@Slf4j
public class S3EncryptionService {

    private static final String AES_ALGO   = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN = 12;   // 96-bit IV recommended for GCM
    private static final int    GCM_TAG_LEN = 128;  // 128-bit auth tag

    private final SecretKeySpec aesKey;

    public S3EncryptionService(
            @Value("${app.encryption.key:AcquiraDefaultEncryptKey32Chars!!}") String rawKey) {
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("app.encryption.key must be at least 32 characters for AES-256");
        }
        // Use exactly 32 bytes
        byte[] key256 = new byte[32];
        System.arraycopy(keyBytes, 0, key256, 0, 32);
        this.aesKey = new SecretKeySpec(key256, "AES");
        log.info("[S3Encryption] AES-256-GCM key initialised");
    }

    /**
     * Encrypt a plain-text value.
     * Format returned: Base64(IV || ciphertext+tag)  — safe to store in VARCHAR.
     */
    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LEN, iv));

            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Prepend IV so we can decrypt later
            byte[] combined = new byte[GCM_IV_LEN + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LEN);
            System.arraycopy(cipherBytes, 0, combined, GCM_IV_LEN, cipherBytes.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a value previously encrypted by {@link #encrypt(String)}.
     */
    public String decrypt(String encryptedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);

            byte[] iv         = new byte[GCM_IV_LEN];
            byte[] cipherBytes = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(combined, GCM_IV_LEN, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LEN, iv));

            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed — key mismatch or corrupt data", e);
        }
    }

    /**
     * Perform a live S3 HeadBucket check.
     * Throws a descriptive RuntimeException if the bucket is unreachable.
     */
    public void testS3Connection(String accessKeyId, String secretAccessKey,
                                  String bucket, String region, String prefix) {
        AwsBasicCredentials creds = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        try (S3Client s3 = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .build()) {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.debug("[S3Test] HeadBucket OK: {}/{}", bucket, prefix);
        } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
            throw new RuntimeException("Bucket '" + bucket + "' does not exist in region " + region);
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new RuntimeException("Network error connecting to S3: " + e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("InvalidAccessKeyId")) {
                throw new RuntimeException("Invalid Access Key ID");
            }
            if (msg != null && msg.contains("SignatureDoesNotMatch")) {
                throw new RuntimeException("Invalid Secret Access Key");
            }
            if (msg != null && msg.contains("AccessDenied")) {
                throw new RuntimeException("Access denied — check IAM permissions for s3:HeadBucket on '" + bucket + "'");
            }
            throw new RuntimeException("S3 connection failed: " + msg);
        }
    }

    /**
     * Upload bytes to S3.
     * S3Client is created fresh per call to avoid sharing credentials across tenants.
     */
    public void uploadToS3(String accessKeyId, String secretAccessKey,
                            String bucket, String region,
                            String s3Key, byte[] content, String contentType) {
        AwsBasicCredentials creds = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        try (S3Client s3 = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .build()) {

            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .contentType(contentType)
                    .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(content)
            );
            log.debug("[S3Upload] Uploaded {} bytes to s3://{}/{}", content.length, bucket, s3Key);
        }
    }
}
