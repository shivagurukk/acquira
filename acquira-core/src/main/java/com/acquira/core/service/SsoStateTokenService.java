package com.acquira.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * #7: DB-backed SSO state tokens — survive restarts, work across pods.
 *
 * <p>Pentest 2026-10-04 (C1/L5): the controller had drifted back to an in-memory
 * map (per pod, lost on restart) and this service was unused. It is now the only
 * state store, and each state is BOUND to the tenant whose SSO config minted the
 * authorize URL, so the callback exchanges the code against the same
 * client/secret/IdP. The binding is carried inside the state value itself —
 * {@code <uuid>.<tenantId>.<hmac>} — and authenticated with an HMAC, so no schema
 * change is needed and a caller cannot re-point a state at another tenant.
 *
 * <p>Fail closed: if the table is unreachable the state is rejected. The old
 * "lenient fallback" accepted any state when the table was missing, which made
 * the CSRF check optional.
 */
@Service
public class SsoStateTokenService {

    private static final Logger log = LoggerFactory.getLogger(SsoStateTokenService.class);
    private static final int STATE_TTL_MINUTES = 10;
    private static final int MAC_HEX_CHARS = 32; // 128 bits of a SHA-256 MAC; fits VARCHAR(100)

    private final JdbcTemplate jdbc;
    private final byte[] macKey;

    public SsoStateTokenService(JdbcTemplate jdbcTemplate,
                                @Value("${jwt.secret:AcquiraDefaultDevKeyAtLeast32Chars!!}") String jwtSecret) {
        this.jdbc = jdbcTemplate;
        this.macKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    /** Result of consuming a state: valid or not, and the tenant it was bound to (null = platform config). */
    public record Consumed(boolean valid, Long tenantId) {
        static final Consumed INVALID = new Consumed(false, null);
    }

    /** Generate and store a new state token bound to {@code tenantId} (nullable). */
    public String generateState(Long tenantId) {
        String body = UUID.randomUUID() + "." + (tenantId == null ? "0" : tenantId.toString());
        String state = body + "." + mac(body);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(STATE_TTL_MINUTES);
        try {
            jdbc.update(
                "INSERT INTO sso_state_token (state_token, expires_at) VALUES (?, ?)",
                state, expiresAt);
        } catch (Exception e) {
            // Without the row the callback will reject this state — SSO is
            // unavailable rather than unauthenticated. Surface it loudly.
            log.error("Failed to store SSO state token; SSO logins will fail until sso_state_token is reachable: {}",
                    e.getMessage());
        }
        return state;
    }

    /** Validate (integrity + one-time use + expiry) and consume a state token. */
    public Consumed validateAndConsume(String state) {
        if (state == null || state.isBlank()) return Consumed.INVALID;
        int lastDot = state.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == state.length() - 1) return Consumed.INVALID;
        String body = state.substring(0, lastDot);
        String presentedMac = state.substring(lastDot + 1);
        if (!MessageDigest.isEqual(mac(body).getBytes(StandardCharsets.UTF_8),
                                   presentedMac.getBytes(StandardCharsets.UTF_8))) {
            log.warn("[SSO] State token failed integrity check");
            return Consumed.INVALID;
        }
        Long tenantId;
        try {
            long t = Long.parseLong(body.substring(body.indexOf('.') + 1));
            tenantId = t == 0 ? null : t;
        } catch (Exception e) {
            return Consumed.INVALID;
        }
        try {
            int updated = jdbc.update(
                "UPDATE sso_state_token SET used = TRUE WHERE state_token = ? AND used = FALSE AND expires_at > NOW()",
                state);
            return updated > 0 ? new Consumed(true, tenantId) : Consumed.INVALID;
        } catch (Exception e) {
            log.error("[SSO] State validation failed (table unreachable) — rejecting: {}", e.getMessage());
            return Consumed.INVALID;
        }
    }

    private String mac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            String hex = HexFormat.of().formatHex(mac.doFinal(("sso-state-v1|" + body).getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, MAC_HEX_CHARS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute SSO state MAC", e);
        }
    }

    /** Cleanup expired state tokens (runs hourly) */
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "core-sso-state-cleanup", lockAtLeastFor = "PT1M")
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
