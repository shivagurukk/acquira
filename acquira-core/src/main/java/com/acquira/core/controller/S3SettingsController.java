package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.Tenant;
import com.acquira.common.model.TenantSetting;
import com.acquira.common.repository.TenantRepository;
import com.acquira.common.repository.TenantSettingRepository;
import com.acquira.core.service.S3EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for per-tenant S3 report storage settings.
 *
 * Settings are stored as encrypted key-value rows in tenant_setting:
 *   s3.enabled          BOOLEAN  ("true"/"false")
 *   s3.region           STRING
 *   s3.bucket           STRING
 *   s3.prefix           STRING
 *   s3.accessKeyId      STRING   (plain — not sensitive)
 *   s3.secretAccessKey  STRING   (AES-256 encrypted via S3EncryptionService)
 *
 * The secret key is NEVER returned to the frontend in plain text.
 */
@RestController
@RequestMapping("/api/admin/s3-settings")
@RequiredArgsConstructor
@Slf4j
public class S3SettingsController {

    private static final String KEY_ENABLED    = "s3.enabled";
    private static final String KEY_REGION     = "s3.region";
    private static final String KEY_BUCKET     = "s3.bucket";
    private static final String KEY_PREFIX     = "s3.prefix";
    private static final String KEY_ACCESS_KEY = "s3.accessKeyId";
    private static final String KEY_SECRET_KEY = "s3.secretAccessKey";  // stored encrypted
    private static final String SECRET_MASK    = "••••••••••••••••";

    private final TenantSettingRepository settingRepository;
    private final TenantRepository        tenantRepository;
    private final S3EncryptionService     encryptionService;

    /** ── GET current config for the active tenant ── */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().build();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled",          getBool(tenantId, KEY_ENABLED, false));
        result.put("s3Region",         getString(tenantId, KEY_REGION, "me-south-1"));
        result.put("s3Bucket",         getString(tenantId, KEY_BUCKET, ""));
        result.put("s3Prefix",         getString(tenantId, KEY_PREFIX, "reports"));
        result.put("s3AccessKeyId",    getString(tenantId, KEY_ACCESS_KEY, ""));
        // Never return the real secret — mask it if it exists
        boolean hasSecret = settingRepository.findByTenant_TenantIdAndKey(tenantId, KEY_SECRET_KEY).isPresent();
        result.put("s3SecretAccessKey", hasSecret ? SECRET_MASK : "");

        return ResponseEntity.ok(result);
    }

    /** ── SAVE / UPDATE config ── */
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().build();

        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new RuntimeException("Tenant not found: " + tenantId));

        upsert(tenant, KEY_ENABLED, String.valueOf(body.getOrDefault("enabled", false)), "BOOLEAN");
        upsert(tenant, KEY_REGION,  str(body, "s3Region",  "me-south-1"), "STRING");
        upsert(tenant, KEY_BUCKET,  str(body, "s3Bucket",  ""),           "STRING");
        upsert(tenant, KEY_PREFIX,  str(body, "s3Prefix",  "reports"),    "STRING");
        upsert(tenant, KEY_ACCESS_KEY, str(body, "s3AccessKeyId", ""),    "STRING");

        // Only update the secret if a new one was supplied (not the masked placeholder)
        String suppliedSecret = str(body, "s3SecretAccessKey", "");
        if (!suppliedSecret.isBlank() && !suppliedSecret.equals(SECRET_MASK)) {
            String encrypted = encryptionService.encrypt(suppliedSecret);
            upsert(tenant, KEY_SECRET_KEY, encrypted, "ENCRYPTED");
            log.info("[S3Settings] Secret key encrypted and saved for tenant {}", tenantId);
        }

        log.info("[S3Settings] Saved S3 config for tenant {} — enabled={}", tenantId, body.get("enabled"));
        return ResponseEntity.ok(Map.of("saved", true));
    }

    /** ── TEST connection without saving ── */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();

        String accessKeyId     = str(body, "s3AccessKeyId", "");
        String secretAccessKey = str(body, "s3SecretAccessKey", "");
        String bucket          = str(body, "s3Bucket", "");
        String region          = str(body, "s3Region", "me-south-1");
        String prefix          = str(body, "s3Prefix", "reports");

        // If caller sent the masked placeholder, fetch the real key from DB for test
        if (SECRET_MASK.equals(secretAccessKey) && tenantId != null) {
            secretAccessKey = getDecryptedSecret(tenantId);
        }

        if (accessKeyId.isBlank() || secretAccessKey.isBlank() || bucket.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Access key, secret, and bucket are required"));
        }

        try {
            encryptionService.testS3Connection(accessKeyId, secretAccessKey, bucket, region, prefix);
            log.info("[S3Settings] Connection test OK — bucket={} region={}", bucket, region);
            return ResponseEntity.ok(Map.of("success", true, "message", "Connection successful — bucket accessible"));
        } catch (Exception e) {
            log.warn("[S3Settings] Connection test FAILED — {}", e.getMessage());
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ── helpers ──

    private void upsert(Tenant tenant, String key, String value, String type) {
        TenantSetting s = settingRepository
            .findByTenant_TenantIdAndKey(tenant.getTenantId(), key)
            .orElseGet(() -> { TenantSetting n = new TenantSetting(); n.setTenant(tenant); n.setKey(key); return n; });
        s.setValue(value);
        s.setType(type);
        settingRepository.save(s);
    }

    private String getString(Long tenantId, String key, String def) {
        return settingRepository.findByTenant_TenantIdAndKey(tenantId, key)
            .map(TenantSetting::getValue).orElse(def);
    }

    private boolean getBool(Long tenantId, String key, boolean def) {
        return settingRepository.findByTenant_TenantIdAndKey(tenantId, key)
            .map(s -> Boolean.parseBoolean(s.getValue())).orElse(def);
    }

    private String getDecryptedSecret(Long tenantId) {
        return settingRepository.findByTenant_TenantIdAndKey(tenantId, KEY_SECRET_KEY)
            .map(s -> encryptionService.decrypt(s.getValue()))
            .orElse("");
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }
}
