package com.acquira.core.service;

import com.acquira.common.ingest.WorkingWeekResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the ingestion ledger into alerts.
 *
 * Emits into the EXISTING alert_history table rather than building new delivery
 * plumbing, so the notification bell, the alerts screen and anything already
 * consuming alerts pick these up with no further work.
 *
 * DEDUPE: one alert per (tenant, rule, day). Without it a 30-minute schedule
 * would raise the same NO_DATA alert 48 times and an operator would learn to
 * ignore the bell — the failure mode that makes alerting worse than nothing.
 */
@Service
public class IngestAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestAlertScheduler.class);

    private static final String RULE_NO_DATA        = "INGEST_NO_DATA";
    private static final String RULE_RUN_FAILED     = "INGEST_RUN_FAILED";
    private static final String RULE_SLA_BREACH     = "INGEST_SLA_BREACH";
    private static final String RULE_RECON_GAP      = "INGEST_RECON_GAP";
    private static final String RULE_DESTRUCTIVE    = "INGEST_DESTRUCTIVE_REPLACE";
    private static final String RULE_FEE_COVERAGE   = "INGEST_FEE_COVERAGE_DROP";
    private static final String RULE_VOLUME_ANOMALY = "INGEST_VOLUME_ANOMALY";
    private static final String RULE_DUPLICATE_FILE = "INGEST_DUPLICATE_FILE";

    private final JdbcTemplate jdbc;
    private final WorkingWeekResolver workingWeek;

    @Value("${acquira.ingest.alerts.enabled:true}")
    private boolean enabled;

    public IngestAlertScheduler(JdbcTemplate jdbc, WorkingWeekResolver workingWeek) {
        this.jdbc = jdbc;
        this.workingWeek = workingWeek;
    }

    @Scheduled(fixedDelayString = "${acquira.ingest.alerts.interval-ms:1800000}",
               initialDelayString = "${acquira.ingest.alerts.initial-delay-ms:120000}")
    public void scan() {
        if (!enabled) return;
        try {
            noData();
            runFailures();
            slaBreaches();
            reconGaps();
            destructiveReplaces();
            feeCoverageDrops();
            volumeAnomalies();
            duplicateFiles();
        } catch (Exception e) {
            log.warn("Ingest alert scan failed (will retry on the next tick): {}", e.toString());
        }
    }

    // ── Rules ───────────────────────────────────────────────────────────────

    /**
     * A monitored tenant whose latest data is older than the last working day.
     *
     * The working-week check is the whole reason WorkingWeekResolver exists: a
     * Bahraini tenant is not late on a Friday, and raising NO_DATA every weekend
     * would train people to dismiss the alert.
     */
    private void noData() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
            "SELECT e.tenant_id, t.institution_id, " +
            "  (SELECT MAX(c.txn_date) FROM ingest_day_coverage c " +
            // COALESCE to rows_summary so migration-backfilled history counts as
            // data — otherwise every tenant fires NO_DATA on the first scan after
            // deployment, and an alert that is wrong on day one is never trusted.
            "     WHERE c.tenant_id = e.tenant_id " +
            "       AND COALESCE(c.rows_fact, c.rows_summary, 0) > 0) AS latest " +
            "FROM ingest_expectation e JOIN tenant t ON t.tenant_id = e.tenant_id " +
            "WHERE e.enabled = TRUE AND e.expected_daily = TRUE");

        for (Map<String, Object> t : tenants) {
            Long tenantId = ((Number) t.get("tenant_id")).longValue();
            java.sql.Date latest = (java.sql.Date) t.get("latest");

            LocalDate expected = LocalDate.now().minusDays(1);
            int guard = 0;
            while (!workingWeek.isWorkingDay(tenantId, expected) && guard++ < 10) {
                expected = expected.minusDays(1);
            }

            if (latest == null || latest.toLocalDate().isBefore(expected)) {
                raise(tenantId, RULE_NO_DATA, "HIGH", String.format(
                    "No transaction data for %s. Latest loaded day is %s, expected %s.",
                    t.get("institution_id"), latest == null ? "none" : latest.toString(), expected),
                    null);
            }
        }
    }

    private void runFailures() {
        for (Map<String, Object> r : sinceLastTick(
                "SELECT id, tenant_id, file_name, error_message FROM ingest_run " +
                "WHERE status = 'FAILED' AND ended_at > CURRENT_TIMESTAMP - INTERVAL '24 hours' " +
                "AND acknowledged_at IS NULL")) {
            raise(asLong(r.get("tenant_id")), RULE_RUN_FAILED, "HIGH", String.format(
                "Ingest run %s failed (%s): %s", r.get("id"),
                r.get("file_name") == null ? "no file" : r.get("file_name"),
                truncate((String) r.get("error_message"), 300)), null);
        }
    }

    private void slaBreaches() {
        for (Map<String, Object> r : sinceLastTick(
                "SELECT r.id, r.tenant_id, r.duration_ms, e.sla_minutes, r.job_name " +
                "FROM ingest_run r JOIN ingest_expectation e ON e.tenant_id = r.tenant_id " +
                "WHERE e.enabled = TRUE AND r.duration_ms IS NOT NULL " +
                "AND r.duration_ms > e.sla_minutes * 60000 " +
                "AND r.ended_at > CURRENT_TIMESTAMP - INTERVAL '24 hours' AND r.acknowledged_at IS NULL")) {
            long mins = asLong(r.get("duration_ms")) / 60000;
            raise(asLong(r.get("tenant_id")), RULE_SLA_BREACH, "MEDIUM", String.format(
                "Ingest run %s (%s) took %d min, over the %s min SLA.",
                r.get("id"), r.get("job_name"), mins, r.get("sla_minutes")),
                (double) mins);
        }
    }

    private void reconGaps() {
        for (Map<String, Object> r : sinceLastTick(
                "SELECT id, tenant_id, recon_detail FROM ingest_run " +
                "WHERE recon_status = 'GAP' AND ended_at > CURRENT_TIMESTAMP - INTERVAL '24 hours' " +
                "AND acknowledged_at IS NULL")) {
            raise(asLong(r.get("tenant_id")), RULE_RECON_GAP, "HIGH", String.format(
                "Ingest run %s did not reconcile: %s",
                r.get("id"), truncate((String) r.get("recon_detail"), 400)), null);
        }
    }

    /** REPLACE that destroyed materially more than it wrote — see P0-3. */
    private void destructiveReplaces() {
        for (Map<String, Object> r : sinceLastTick(
                "SELECT id, tenant_id, fact_rows_deleted, rows_facted, file_name FROM ingest_run " +
                "WHERE load_mode = 'REPLACE' AND fact_rows_deleted IS NOT NULL " +
                "AND fact_rows_deleted > COALESCE(rows_facted,0) * 1.5 " +
                "AND ended_at > CURRENT_TIMESTAMP - INTERVAL '24 hours' AND acknowledged_at IS NULL")) {
            raise(asLong(r.get("tenant_id")), RULE_DESTRUCTIVE, "CRITICAL", String.format(
                "Run %s (%s) deleted %s existing row(s) and wrote back only %s. "
                + "A partial-day file replaced a fuller day.",
                r.get("id"), r.get("file_name"), r.get("fact_rows_deleted"), r.get("rows_facted")),
                null);
        }
    }

    /** Fees priced on almost nothing — the dead-rate-card signature. */
    private void feeCoverageDrops() {
        for (Map<String, Object> r : sinceLastTick(
                "SELECT r.id, r.tenant_id, r.fee_priced_pct, e.fee_coverage_pct FROM ingest_run r " +
                "JOIN ingest_expectation e ON e.tenant_id = r.tenant_id " +
                "WHERE e.enabled = TRUE AND r.fee_priced_pct IS NOT NULL " +
                "AND r.fee_priced_pct < e.fee_coverage_pct " +
                "AND r.ended_at > CURRENT_TIMESTAMP - INTERVAL '24 hours' AND r.acknowledged_at IS NULL")) {
            raise(asLong(r.get("tenant_id")), RULE_FEE_COVERAGE, "HIGH", String.format(
                "Run %s priced only %s%% of rows with a non-zero MSF (floor %s%%). "
                + "A rate card is likely missing, expired, or not matching.",
                r.get("id"), r.get("fee_priced_pct"), r.get("fee_coverage_pct")),
                r.get("fee_priced_pct") == null ? null : ((Number) r.get("fee_priced_pct")).doubleValue());
        }
    }

    /**
     * Yesterday's volume against the same weekday over the previous 8 weeks.
     *
     * Same-weekday rather than a flat average: retail volume has a strong weekly
     * shape, so comparing a Sunday to a rolling all-days mean flags every Sunday.
     */
    private void volumeAnomalies() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "WITH latest AS (" +
            "  SELECT c.tenant_id, MAX(c.txn_date) AS d FROM ingest_day_coverage c " +
            "  JOIN ingest_expectation e ON e.tenant_id = c.tenant_id AND e.enabled = TRUE " +
            // rows_fact ONLY here, deliberately: the baseline compares like with
            // like. Mixing a fact count against a migration-backfilled summary
            // count would manufacture anomalies. Pre-ledger days simply do not
            // participate until they are re-ingested.
            "  WHERE COALESCE(c.rows_fact,0) > 0 GROUP BY c.tenant_id) " +
            "SELECT l.tenant_id, l.d AS txn_date, cur.rows_fact AS today_rows, " +
            "  (SELECT AVG(h.rows_fact) FROM ingest_day_coverage h " +
            "     WHERE h.tenant_id = l.tenant_id AND h.txn_date < l.d " +
            "       AND h.txn_date >= l.d - INTERVAL '56 days' " +
            "       AND EXTRACT(DOW FROM h.txn_date) = EXTRACT(DOW FROM l.d) " +
            "       AND COALESCE(h.rows_fact,0) > 0) AS baseline_rows, " +
            "  e.variance_pct " +
            "FROM latest l " +
            "JOIN ingest_day_coverage cur ON cur.tenant_id = l.tenant_id AND cur.txn_date = l.d " +
            "JOIN ingest_expectation e ON e.tenant_id = l.tenant_id");

        for (Map<String, Object> r : rows) {
            Object baseObj = r.get("baseline_rows");
            if (baseObj == null) continue;                 // no history yet — say nothing
            double baseline = ((Number) baseObj).doubleValue();
            if (baseline <= 0) continue;
            double today = r.get("today_rows") == null ? 0 : ((Number) r.get("today_rows")).doubleValue();
            int variancePct = r.get("variance_pct") == null ? 40 : ((Number) r.get("variance_pct")).intValue();

            double deltaPct = (today - baseline) / baseline * 100.0;
            if (Math.abs(deltaPct) >= variancePct) {
                raise(asLong(r.get("tenant_id")), RULE_VOLUME_ANOMALY, "MEDIUM", String.format(
                    "%s volume is %.0f%% %s the same weekday's 8-week baseline (%.0f vs %.0f rows).",
                    r.get("txn_date"), Math.abs(deltaPct), deltaPct < 0 ? "below" : "above",
                    today, baseline),
                    deltaPct);
            }
        }
    }

    /** Same file content loaded twice for one tenant. */
    private void duplicateFiles() {
        for (Map<String, Object> r : sinceLastTick(
                "SELECT r.id, r.tenant_id, r.file_name, r.file_sha256, " +
                "  (SELECT COUNT(*) FROM ingest_run p WHERE p.tenant_id = r.tenant_id " +
                "     AND p.file_sha256 = r.file_sha256 AND p.id < r.id) AS prior " +
                "FROM ingest_run r WHERE r.file_sha256 IS NOT NULL " +
                "AND r.started_at > CURRENT_TIMESTAMP - INTERVAL '24 hours' AND r.acknowledged_at IS NULL")) {
            if (asLong(r.get("prior")) > 0) {
                raise(asLong(r.get("tenant_id")), RULE_DUPLICATE_FILE, "LOW", String.format(
                    "File %s has been loaded before for this tenant (identical content hash). "
                    + "Run %s may be a duplicate.", r.get("file_name"), r.get("id")), null);
            }
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private List<Map<String, Object>> sinceLastTick(String sql) {
        try {
            return jdbc.queryForList(sql);
        } catch (Exception e) {
            log.warn("Ingest alert query failed (non-fatal): {}", e.toString());
            return new ArrayList<>();
        }
    }

    /**
     * Writes one alert, at most once per tenant per rule per day.
     *
     * alert_history.tenant_id is an INT FK to tenant, so a tenant that has been
     * deleted mid-scan would fail the insert; that is caught and logged rather
     * than aborting the whole scan and losing every other alert in the batch.
     */
    private void raise(Long tenantId, String rule, String severity, String message, Double metric) {
        if (tenantId == null) return;
        try {
            Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_history WHERE tenant_id = ? AND rule_name = ? " +
                "AND triggered_at::date = CURRENT_DATE AND message = ?",
                Integer.class, tenantId.intValue(), rule, message);
            if (existing != null && existing > 0) return;

            jdbc.update(
                "INSERT INTO alert_history (tenant_id, rule_name, severity, message, metric_value, " +
                "acknowledged, triggered_at) VALUES (?,?,?,?,?,FALSE,CURRENT_TIMESTAMP)",
                tenantId.intValue(), rule, severity, message, metric);
            log.info("[INGEST-ALERT] {} tenant={} {}", rule, tenantId, message);
        } catch (Exception e) {
            log.warn("Could not raise {} for tenant {} (non-fatal): {}", rule, tenantId, e.toString());
        }
    }

    private static Long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(no detail)";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
