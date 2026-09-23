package com.acquira.core.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily housekeeping for the enterprise-API surface.
 *
 * 1. apikey.expiring webhooks — fired per tenant when an active key's expiry
 *    lands exactly 7, 3, or 1 day(s) out. The exact-day match is what makes
 *    the daily run idempotent-enough without a "notified" flag: each key
 *    fires at most three times, once per threshold day.
 * 2. api_idempotency retention — replay records are only meaningful for the
 *    retry horizon of a client; 24h is the conventional window.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyExpiryScheduler {

    private final JdbcTemplate jdbc;
    private final WebhookDispatcher dispatcher;

    @Scheduled(cron = "0 15 6 * * *")
    public void notifyExpiringKeys() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT key_id, tenant_id, name, key_prefix, expires_at, " +
                "  EXTRACT(DAY FROM (expires_at - CURRENT_TIMESTAMP))::int AS days_left " +
                "FROM api_key WHERE is_active = true AND expires_at IS NOT NULL " +
                "AND EXTRACT(DAY FROM (expires_at - CURRENT_TIMESTAMP))::int IN (1, 3, 7)");
        for (Map<String, Object> r : rows) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("keyId", r.get("key_id"));
            data.put("name", r.get("name"));
            data.put("keyPrefix", r.get("key_prefix"));
            data.put("expiresAt", String.valueOf(r.get("expires_at")));
            data.put("daysLeft", r.get("days_left"));
            int sent = dispatcher.dispatch(((Number) r.get("tenant_id")).longValue(),
                    WebhookEvents.API_KEY_EXPIRING, data);
            if (sent > 0) {
                log.info("[API-KEY] expiry warning webhook queued for key {} ({} day(s) left)",
                        r.get("key_prefix"), r.get("days_left"));
            }
        }
    }

    @Scheduled(cron = "0 45 6 * * *")
    public void purgeIdempotencyRecords() {
        int n = jdbc.update("DELETE FROM api_idempotency WHERE created_at < NOW() - INTERVAL '24 hours'");
        if (n > 0) log.info("[API] purged {} expired idempotency record(s)", n);
    }
}
