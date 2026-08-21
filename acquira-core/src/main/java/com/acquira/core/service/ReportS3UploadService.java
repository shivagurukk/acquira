package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.TenantSetting;
import com.acquira.common.repository.TenantSettingRepository;
import com.acquira.common.service.S3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AWS S3 implementation of {@link S3Uploader}.
 *
 * Uploads generated PDFs to S3 after merchant emails are sent successfully.
 * Reads per-tenant S3 settings from the tenant_setting table.
 * AES-256-GCM encrypted credentials are decrypted at runtime via S3EncryptionService.
 *
 * Lives in acquira-core (not acquira-common) because it depends on the
 * AWS SDK and S3EncryptionService which pull in heavy compile-time dependencies.
 * PdfController references only the S3Uploader interface (in acquira-common),
 * avoiding a circular acquira-pdf → acquira-core dependency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportS3UploadService implements S3Uploader {

    private static final String KEY_ENABLED    = "s3.enabled";
    private static final String KEY_REGION     = "s3.region";
    private static final String KEY_BUCKET     = "s3.bucket";
    private static final String KEY_PREFIX     = "s3.prefix";
    private static final String KEY_ACCESS_KEY = "s3.accessKeyId";
    private static final String KEY_SECRET_KEY = "s3.secretAccessKey";

    private final TenantSettingRepository settingRepo;
    private final S3EncryptionService     encryptionService;

    /**
     * Upload a PDF to S3 if S3 is enabled for the given tenant.
     * Returns true (success) when S3 is disabled — callers treat disabled as OK.
     */
    @Override
    public boolean uploadIfEnabled(Long tenantId, Path pdfPath, String bankCode, String yearMonth) {
        if (tenantId == null) return true;

        boolean enabled = getBool(tenantId, KEY_ENABLED, false);
        if (!enabled) {
            log.debug("[S3Upload] S3 disabled for tenant {} — skipping {}", tenantId, pdfPath.getFileName());
            return true;
        }

        String region    = getString(tenantId, KEY_REGION,     "me-south-1");
        String bucket    = getString(tenantId, KEY_BUCKET,     "");
        String prefix    = getString(tenantId, KEY_PREFIX,     "reports");
        String accessKey = getString(tenantId, KEY_ACCESS_KEY, "");
        String encrypted = getString(tenantId, KEY_SECRET_KEY, "");

        if (bucket.isBlank() || accessKey.isBlank() || encrypted.isBlank()) {
            log.warn("[S3Upload] S3 enabled for tenant {} but credentials/bucket incomplete — skipping", tenantId);
            return false;
        }

        String secretKey;
        try {
            secretKey = encryptionService.decrypt(encrypted);
        } catch (Exception e) {
            log.error("[S3Upload] Failed to decrypt S3 secret key for tenant {}: {}", tenantId, e.getMessage());
            return false;
        }

        String filename = pdfPath.getFileName().toString();
        String s3Key    = buildS3Key(prefix, bankCode, yearMonth, filename);

        try {
            byte[] pdfBytes = Files.readAllBytes(pdfPath);
            encryptionService.uploadToS3(accessKey, secretKey, bucket, region, s3Key, pdfBytes, "application/pdf");
            log.info("[S3Upload] Uploaded s3://{}/{} (tenant:{} {}KB)", bucket, s3Key, tenantId, pdfBytes.length / 1024);
            return true;
        } catch (Exception e) {
            log.error("[S3Upload] Failed to upload {} for tenant {}: {}", filename, tenantId, e.getMessage());
            return false;
        }
    }

    /** Convenience wrapper — restores tenant context around the upload call */
    public void uploadAfterEmail(Long tenantId, Path pdfPath, String bankCode, String yearMonth) {
        try {
            if (tenantId != null) TenantContext.setCurrentTenant(tenantId);
            boolean ok = uploadIfEnabled(tenantId, pdfPath, bankCode, yearMonth);
            if (!ok) {
                log.warn("[S3Upload] Upload skipped/failed for {} tenant:{}", pdfPath.getFileName(), tenantId);
            }
        } finally {
            TenantContext.clear();
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private String getString(Long tenantId, String key, String def) {
        return settingRepo.findByTenant_TenantIdAndKey(tenantId, key)
            .map(TenantSetting::getValue).orElse(def);
    }

    private boolean getBool(Long tenantId, String key, boolean def) {
        return settingRepo.findByTenant_TenantIdAndKey(tenantId, key)
            .map(s -> Boolean.parseBoolean(s.getValue())).orElse(def);
    }

    private static String buildS3Key(String prefix, String bankCode, String yearMonth, String filename) {
        String p = (prefix == null || prefix.isBlank()) ? "reports" : prefix.replaceAll("^/|/$", "");
        return (bankCode != null && !bankCode.isBlank())
            ? p + "/" + bankCode + "/" + yearMonth + "/" + filename
            : p + "/" + yearMonth + "/" + filename;
    }
}
