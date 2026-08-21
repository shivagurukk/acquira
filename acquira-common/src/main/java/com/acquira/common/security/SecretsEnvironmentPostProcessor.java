package com.acquira.common.security;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves application secrets at startup based on a SINGLE flag, before the
 * Spring context (and therefore the datasource / JWT beans) is created.
 *
 * ── The one flag ────────────────────────────────────────────────────────────
 *   acquira.secrets.provider = PLAIN | ENCRYPTED | AWS      (default PLAIN)
 *
 *   PLAIN      values are used verbatim (local/dev).
 *   ENCRYPTED  values stored as "enc:v1:..." tokens in the properties file are
 *              decrypted with the master key (APP_ENCRYPTION_KEY env var, or
 *              app.encryption.key). Produce the tokens with SecretEncryptorTool.
 *   AWS        values are pulled from AWS Secrets Manager (a single JSON secret)
 *              and override whatever is in the file.
 *
 * ── Which properties are treated as secrets ─────────────────────────────────
 *   acquira.secrets.keys = spring.datasource.password,jwt.secret   (default)
 *   (comma-separated list; add app.encryption.key etc. as needed)
 *
 * ── AWS settings (only used when provider=AWS) ───────────────────────────────
 *   acquira.secrets.aws.secret-id = acquira/prod/secrets   (required)
 *   acquira.secrets.aws.region    = me-south-1             (optional; else default chain)
 *   acquira.secrets.aws.map.<propertyKey> = <jsonField>    (optional; default = propertyKey)
 *
 * Resolved values are injected as the highest-precedence property source, so
 * they win over the raw file values without mutating any file on disk.
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Same dev fallback as CryptoService so dev tokens interoperate out-of-the-box. */
    private static final String DEV_FALLBACK_KEY = "AcquiraDefaultEncryptKey32Chars!!";
    private static final String DEFAULT_KEYS = "spring.datasource.password,jwt.secret";

    private final Log log;

    public SecretsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SecretsEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        // Run late, after ConfigData (application*.properties) is loaded so the
        // raw values and settings are visible to us.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String provider = env.getProperty("acquira.secrets.provider", "PLAIN").trim().toUpperCase();
        String keysCsv = env.getProperty("acquira.secrets.keys", DEFAULT_KEYS);
        String[] keys = keysCsv.split("\\s*,\\s*");

        Map<String, Object> resolved = new LinkedHashMap<>();

        switch (provider) {
            case "ENCRYPTED":
                resolveEncrypted(env, keys, resolved);
                break;
            case "AWS":
                resolveAws(env, keys, resolved);
                break;
            case "PLAIN":
            default:
                resolvePlain(env, keys);
                break;
        }

        if (!resolved.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("acquiraResolvedSecrets", resolved));
            log.info("[Secrets] provider=" + provider + " resolved " + resolved.size() + " secret(s): " + resolved.keySet());
        } else {
            log.info("[Secrets] provider=" + provider + " — no overrides applied");
        }
    }

    // ── PLAIN ────────────────────────────────────────────────────────────────
    private void resolvePlain(ConfigurableEnvironment env, String[] keys) {
        for (String key : keys) {
            String val = env.getProperty(key);
            if (SecretCrypto.isEncrypted(val)) {
                log.warn("[Secrets] '" + key + "' looks encrypted (enc:v1:) but provider=PLAIN — "
                        + "it will be used as-is. Set acquira.secrets.provider=ENCRYPTED to decrypt it.");
            }
        }
    }

    // ── ENCRYPTED (file) ───────────────────────────────────────────────────────
    private void resolveEncrypted(ConfigurableEnvironment env, String[] keys, Map<String, Object> resolved) {
        String masterKey = masterKey(env);
        for (String key : keys) {
            String val = env.getProperty(key);
            if (val == null) continue;
            if (SecretCrypto.isEncrypted(val)) {
                try {
                    resolved.put(key, SecretCrypto.decrypt(val, masterKey));
                } catch (Exception e) {
                    // Fail fast: a misconfigured key would otherwise surface as a
                    // confusing DB auth error much later.
                    throw new IllegalStateException("[Secrets] failed to decrypt '" + key
                            + "'. Check APP_ENCRYPTION_KEY matches the key used to encrypt it.", e);
                }
            } else {
                log.warn("[Secrets] provider=ENCRYPTED but '" + key + "' is not an enc:v1: token — using it as plaintext. "
                        + "Encrypt it with SecretEncryptorTool.");
            }
        }
    }

    // ── AWS Secrets Manager ──────────────────────────────────────────────────
    private void resolveAws(ConfigurableEnvironment env, String[] keys, Map<String, Object> resolved) {
        String secretId = env.getProperty("acquira.secrets.aws.secret-id");
        String region = env.getProperty("acquira.secrets.aws.region");
        if (secretId == null || secretId.isBlank()) {
            throw new IllegalStateException("[Secrets] provider=AWS but acquira.secrets.aws.secret-id is not set.");
        }

        AwsSecretsManagerResolver aws = new AwsSecretsManagerResolver();
        if (!aws.sdkAvailable()) {
            throw new IllegalStateException("[Secrets] provider=AWS but the AWS SDK is not on the classpath. "
                    + "Add dependency software.amazon.awssdk:secretsmanager (version from the AWS BOM) to acquira-core.");
        }

        Map<String, String> secretMap;
        try {
            secretMap = aws.fetch(region, secretId);
        } catch (Exception e) {
            throw new IllegalStateException("[Secrets] failed to fetch '" + secretId + "' from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }

        for (String key : keys) {
            String field = env.getProperty("acquira.secrets.aws.map." + key, key);
            String val = secretMap.get(field);
            if (val != null) {
                resolved.put(key, val);
            } else {
                log.warn("[Secrets] AWS secret '" + secretId + "' has no field '" + field + "' for property '" + key
                        + "' — leaving existing value.");
            }
        }
    }

    private String masterKey(ConfigurableEnvironment env) {
        String key = env.getProperty("APP_ENCRYPTION_KEY");
        if (key == null || key.isBlank()) key = env.getProperty("app.encryption.key");
        if (key == null || key.isBlank()) {
            log.warn("[Secrets] APP_ENCRYPTION_KEY / app.encryption.key not set — falling back to the dev default key. "
                    + "Set a real 32+ char key in production.");
            key = DEV_FALLBACK_KEY;
        }
        return key;
    }
}
