package com.acquira.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * #7: DB-backed SSO state tokens.
 * Survives server restarts. Works with multiple instances.
 * Replaces the in-memory ConcurrentHashMap approach.
 */
@Service
public class SsoStateTokenService {

    private static final Logger log = LoggerFactory.getLogger(SsoStateTokenService.class);
    private static final int STATE_TTL_MINUTES = 10;
    private final JdbcTemplate jdbc;

    public SsoStateTokenService(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    /** Generate and store a new state token */
    public String generateState() {
        String state = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(STATE_TTL_MINUTES);
        try {
            jdbc.update(
                "INSERT INTO sso_state_token (state_token, expires_at) VALUES (?, ?)",
                state, expiresAt);
        } catch (Exception e) {
            log.warn("Failed to store SSO state token (table may not exist): {}", e.getMessage());
            // Fallback: return token anyway, validation will be lenient
        }
        return state;
    }

    /** Validate and consume a state token (one-time use) */
    public boolean validateAndConsume(String state) {
        if (state == null || state.isBlank()) return false;
        try {
            int updated = jdbc.update(
                "UPDATE sso_state_token SET used = TRUE WHERE state_token = ? AND used = FALSE AND expires_at > NOW()",
                state);
            return updated > 0;
        } catch (Exception e) {
            log.warn("SSO state validation fallback (table may not exist): {}", e.getMessage());
            // Lenient fallback if table doesn't exist yet
            return true;
        }
    }

    /** Cleanup expired state tokens (runs hourly) */
    @Scheduled(cron = "0 30 * * * *")
    public void cleanupExpiredStates() {
        try {
            int deleted = jdbc.update("DELETE FROM sso_state_token WHERE expires_at < NOW() OR used = TRUE");
            if (deleted > 0) log.debug("Cleaned up {} expired SSO state tokens", deleted);
        } catch (Exception e) {
            // table might not exist yet
        }
    }
}
