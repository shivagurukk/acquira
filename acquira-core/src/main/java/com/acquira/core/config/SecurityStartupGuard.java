package com.acquira.core.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Enterprise Guard: Prevents production deployment with insecure defaults.
 *
 * Checks:
 *  - JWT secret must NOT be the built-in dev default when running with 'prod' profile.
 *  - JWT secret must be at least 32 characters.
 *  - Warns about other insecure defaults in prod.
 */
@Configuration
public class SecurityStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupGuard.class);
    private static final String DEFAULT_DEV_SECRET = "AcquiraDefaultDevKeyAtLeast32Chars!!";

    @Value("${jwt.secret:AcquiraDefaultDevKeyAtLeast32Chars!!}")
    private String jwtSecret;

    @Value("${external.api.key:}")
    private String externalApiKey;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    private final Environment env;

    public SecurityStartupGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    public void validateSecurityConfiguration() {
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");
        boolean hasErrors = false;

        // ── JWT Secret ──────────────────────────────────────────────
        if (DEFAULT_DEV_SECRET.equals(jwtSecret)) {
            if (isProd) {
                log.error("╔══════════════════════════════════════════════════════════╗");
                log.error("║  FATAL: Default JWT secret detected in PRODUCTION.      ║");
                log.error("║  Set JWT_SECRET_KEY env variable to a unique 256-bit     ║");
                log.error("║  random string before deploying.                        ║");
                log.error("║                                                          ║");
                log.error("║  Example: export JWT_SECRET_KEY=$(openssl rand -hex 32)  ║");
                log.error("╚══════════════════════════════════════════════════════════╝");
                throw new IllegalStateException(
                    "SECURITY: Cannot start in 'prod' profile with default JWT secret. " +
                    "Set the JWT_SECRET_KEY environment variable.");
            } else {
                log.warn("⚠ Using default JWT secret — acceptable for development only.");
            }
        }

        if (jwtSecret.length() < 32) {
            if (isProd) {
                throw new IllegalStateException(
                    "SECURITY: JWT secret must be at least 32 characters. Current length: " + jwtSecret.length());
            } else {
                log.warn("⚠ JWT secret is shorter than 32 characters. Use a longer key.");
            }
        }

        // ── External API Key ────────────────────────────────────────
        if (isProd && (externalApiKey == null || externalApiKey.isBlank())) {
            log.warn("⚠ No external.api.key configured — external report API will reject all requests.");
        }

        // ── Database Password ───────────────────────────────────────
        if (isProd && ("postgres".equals(dbPassword) || "CHANGE_ME".equals(dbPassword))) {
            log.error("╔══════════════════════════════════════════════════════════╗");
            log.error("║  FATAL: Default database password detected in PROD.     ║");
            log.error("║  Set DB_PASSWORD env variable to a secure password.     ║");
            log.error("╚══════════════════════════════════════════════════════════╝");
            throw new IllegalStateException(
                "SECURITY: Cannot start in 'prod' profile with default database password.");
        }

        // ── Success ─────────────────────────────────────────────────
        if (isProd) {
            log.info("✓ Security startup checks passed for production profile.");
        }
    }
}
