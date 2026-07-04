package com.acquira.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prunes old rows from {@code api_request_log} so the external-API audit trail
 * doesn't grow without bound.
 *
 * Global (not per-tenant): a single bounded DELETE of rows older than the
 * configured retention window. Runs on a fixed delay (default daily), with a
 * batch cap so a large backlog is trimmed incrementally across runs rather than
 * locking the table in one huge statement.
 *
 * Safe under replicas: 1 (the deliberate single-replica architecture) — no
 * coordination needed. Retention window + cadence are configurable:
 *   api.request-log.retention-days   (default 90)
 *   api.request-log.cleanup-interval-ms (default 24h)
 *   api.request-log.cleanup-batch-size  (default 50000)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiRequestLogRetentionScheduler {

    private final JdbcTemplate jdbcTemplate;

    @Value("${api.request-log.retention-days:90}")
    private int retentionDays;

    @Value("${api.request-log.cleanup-batch-size:50000}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${api.request-log.cleanup-interval-ms:86400000}",
               initialDelayString = "${api.request-log.cleanup-initial-ms:300000}")
    public void purgeOldRows() {
        if (retentionDays <= 0) return; // retention disabled
        try {
            // Bounded delete: trim up to batchSize of the oldest expired rows per run.
            // ctid-based subselect keeps the statement index-light and cap-friendly on
            // the (created_at) index. Repeats on the next tick until the backlog clears.
            int deleted = jdbcTemplate.update(
                "DELETE FROM api_request_log WHERE ctid IN (" +
                "  SELECT ctid FROM api_request_log " +
                "  WHERE created_at < NOW() - (? * INTERVAL '1 day') " +
                "  ORDER BY created_at ASC LIMIT ?)",
                retentionDays, batchSize);
            if (deleted > 0) {
                log.info("[api-log-retention] pruned {} api_request_log row(s) older than {} days",
                        deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("[api-log-retention] cleanup skipped: {}", e.toString());
        }
    }
}
