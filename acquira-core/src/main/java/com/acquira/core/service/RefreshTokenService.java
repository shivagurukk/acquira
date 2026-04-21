package com.acquira.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * #14: DB-backed Refresh Token management.
 * Tracks all issued refresh tokens. On rotation, old token is revoked.
 * Stolen tokens (reuse after rotation) trigger full revocation for that user.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private final JdbcTemplate jdbc;

    public RefreshTokenService(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    /** Store a newly issued refresh token */
    public void storeToken(String username, String rawToken, LocalDateTime expiresAt, String userAgent, String ipAddress) {
        String hash = hashToken(rawToken);
        try {
            jdbc.update(
                "INSERT INTO refresh_token (username, token_hash, expires_at, user_agent, ip_address) VALUES (?, ?, ?, ?, ?)",
                username, hash, expiresAt, truncate(userAgent, 500), truncate(ipAddress, 50));
        } catch (Exception e) {
            log.warn("Failed to store refresh token for {}: {}", username, e.getMessage());
        }
    }

    /**
     * Rotate: revoke old token, store new one.
     * Returns false if old token was already revoked (potential theft).
     */
    public boolean rotateToken(String username, String oldRawToken, String newRawToken, LocalDateTime newExpiresAt,
                               String userAgent, String ipAddress) {
        String oldHash = hashToken(oldRawToken);
        String newHash = hashToken(newRawToken);

        try {
            // Check if old token exists and is not revoked
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE token_hash = ? AND revoked = FALSE AND expires_at > NOW()",
                Integer.class, oldHash);

            if (count == null || count == 0) {
                // Token reuse detected! Revoke ALL tokens for this user (stolen token scenario)
                log.warn("[SECURITY] Refresh token reuse detected for user '{}'. Revoking all sessions.", username);
                revokeAllForUser(username);
                return false;
            }

            // Revoke old token and link to new one
            jdbc.update(
                "UPDATE refresh_token SET revoked = TRUE, replaced_by = ? WHERE token_hash = ?",
                newHash, oldHash);

            // Store new token
            storeToken(username, newRawToken, newExpiresAt, userAgent, ipAddress);
            return true;

        } catch (Exception e) {
            log.error("Error rotating refresh token for {}: {}", username, e.getMessage());
            return false;
        }
    }

    /** Revoke all refresh tokens for a user (logout all devices) */
    public int revokeAllForUser(String username) {
        try {
            return jdbc.update(
                "UPDATE refresh_token SET revoked = TRUE WHERE username = ? AND revoked = FALSE",
                username);
        } catch (Exception e) {
            log.warn("Failed to revoke tokens for {}: {}", username, e.getMessage());
            return 0;
        }
    }

    /** Check if a token is valid (exists, not revoked, not expired) */
    public boolean isTokenValid(String rawToken) {
        String hash = hashToken(rawToken);
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE token_hash = ? AND revoked = FALSE AND expires_at > NOW()",
                Integer.class, hash);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Cleanup expired tokens (runs daily) */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupExpiredTokens() {
        try {
            int deleted = jdbc.update("DELETE FROM refresh_token WHERE expires_at < NOW() - INTERVAL '7 days'");
            if (deleted > 0) log.info("Cleaned up {} expired refresh tokens", deleted);
        } catch (Exception e) {
            log.debug("Refresh token cleanup skipped: {}", e.getMessage());
        }
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
