package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import com.acquira.core.service.DigestFeedCoverage.FeedState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sends the Daily Dashboard Digest — one email per tenant per business day,
 * only once EVERY required feed for that day has landed.
 *
 * TIMER-DRIVEN BY DESIGN (no batch-job listener): a day's data often arrives
 * as several files minutes apart (transactions, then DCC, then rentals), so
 * "the moment a job finishes" is exactly the wrong moment to email. Instead a
 * 5-minute sweep
 *   1. DISCOVERS loaded days from ingest_day_coverage (recent days only —
 *      a 6-month backfill must not fire 180 emails — and only days strictly
 *      BEFORE the tenant-local today: an intraday file must never produce a
 *      part-day digest; Phase 1 finding I-2),
 *   2. GATES each pending day on: transactions in fact, a DCC load covering
 *      the day, a rental load covering the day's month (each per-tenant
 *      toggleable), no ingest currently RUNNING, and a quiet period since the
 *      tenant's last run (debounces multi-file upload sessions),
 *   3. CLAIMS the row (PENDING → SENDING, atomically) so a second app pod can
 *      never send the same day (I-12), then
 *   4. SENDS at most once — digest_dispatch's UNIQUE(tenant_id,business_date)
 *      is the idempotency guarantee, and attempts are capped so a dead SMTP
 *      config surfaces as FAILED on the admin screen instead of retrying
 *      forever. The rendered subject and HTML are stored on the row so the
 *      exact email an executive received can be viewed and resent (I-6).
 *   5. RETRIES only the recipients that failed on a SENT day (I-11), and
 *   6. WATCHES sent days for a material change in the loaded data — a
 *      re-ingest or correction after the email went out — and, per tenant
 *      setting, raises an alert or sends a clearly-labelled restatement (I-2).
 *
 * EmailService resolves the tenant's own SMTP config from TenantContext, so
 * the context is set explicitly around each send (scheduler threads have none).
 */
@Service
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    static final int MAX_ATTEMPTS = 5;
    /** A SENDING row older than this was claimed by a pod that died mid-send. */
    static final int STALE_CLAIM_MINUTES = 30;

    static final String RESTATE_OFF = "OFF";
    static final String RESTATE_ALERT = "ALERT";
    static final String RESTATE_RESEND = "RESEND";
    static final String RULE_RESTATEMENT = "DIGEST_RESTATEMENT";

    /** Job names whose completed runs prove a feed was processed. */
    private static final String DCC_JOBS = "('dccLoadJob','dbPullDccJob')";
    private static final String RENTAL_JOBS = "('rentalLoadJob','dbPullRentalJob')";
    private static final String MERCHANT_JOBS = "('merchantMasterJob','dbPullMerchantJob')";

    private final JdbcTemplate jdbc;
    private final DigestContentService content;
    private final DigestEmailService emailRenderer;
    private final EmailService emailService;

    @Value("${acquira.digest.enabled:true}")
    private boolean enabled;

    public DigestScheduler(JdbcTemplate jdbc, DigestContentService content,
                           DigestEmailService emailRenderer, EmailService emailService) {
        this.jdbc = jdbc;
        this.content = content;
        this.emailRenderer = emailRenderer;
        this.emailService = emailService;
    }

    @Scheduled(fixedDelayString = "${acquira.digest.interval-ms:300000}",
               initialDelayString = "${acquira.digest.initial-delay-ms:90000}")
    public void tick() {
        if (!enabled) return;
        try {
            releaseStaleClaims();
            discover();
            process();
            retryPartial();
            restatements();
        } catch (Exception e) {
            log.warn("Digest sweep failed (will retry on the next tick): {}", e.toString());
        }
    }

    // ── 1. Discovery ────────────────────────────────────────────────────────

    /**
     * A candidate is a recent tenant-day with transaction fact rows. rows_fact
     * only (not the migration-backfilled rows_summary) — the digest is about
     * data that just LANDED, and pre-ledger history must not email on deploy.
     * The recency window also caps how far back a re-ingest can email.
     *
     * Only days strictly before the TENANT-LOCAL today are candidates. The
     * current day is by definition incomplete — an intraday file would have
     * produced a part-day digest after the quiet period, and because a day
     * sends once, the later files were silently absorbed. An admin can still
     * send today by hand from the readiness panel.
     */
    private void discover() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT tenant_id, backfill_window_days FROM digest_config WHERE enabled = TRUE");
        for (Map<String, Object> t : tenants) {
            long tenantId = ((Number) t.get("tenant_id")).longValue();
            int window = t.get("backfill_window_days") == null ? 3
                    : ((Number) t.get("backfill_window_days")).intValue();
            LocalDate today = LocalDate.now(tenantZone(tenantId));
            jdbc.update(
                "INSERT INTO digest_dispatch (tenant_id, business_date, status) "
                + "SELECT c.tenant_id, c.txn_date, 'PENDING' "
                + "FROM ingest_day_coverage c "
                + "WHERE c.tenant_id = ? AND COALESCE(c.rows_fact, 0) > 0 "
                + "AND c.txn_date >= ? AND c.txn_date < ? "
                + "ON CONFLICT (tenant_id, business_date) DO NOTHING",
                tenantId, today.minusDays(window), today);
        }
    }

    /** A pod that died between claim and send must not hold the day forever. */
    private void releaseStaleClaims() {
        int n = jdbc.update(
                "UPDATE digest_dispatch SET status = 'PENDING', claimed_at = NULL "
                + "WHERE status = 'SENDING' AND claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 minute')",
                STALE_CLAIM_MINUTES);
        if (n > 0) log.warn("[DIGEST] Released {} stale SENDING claim(s)", n);
    }

    // ── 2 + 3 + 4. Gate, claim and send ─────────────────────────────────────

    private void process() {
        List<Map<String, Object>> pending = jdbc.queryForList(
            "SELECT p.id, p.tenant_id, p.business_date, p.attempts, "
            + "g.recipients, g.quiet_minutes, g.require_merchant, g.require_trx, g.require_dcc, "
            + "g.require_rental, g.send_not_before, g.subject_figures "
            + "FROM digest_dispatch p "
            + "JOIN digest_config g ON g.tenant_id = p.tenant_id "
            + "WHERE p.status = 'PENDING' AND g.enabled = TRUE "
            + "ORDER BY p.tenant_id, p.business_date");

        for (Map<String, Object> row : pending) {
            long dispatchId = ((Number) row.get("id")).longValue();
            long tenantId = ((Number) row.get("tenant_id")).longValue();
            LocalDate date = ((java.sql.Date) row.get("business_date")).toLocalDate();
            int attempts = ((Number) row.get("attempts")).intValue();

            try {
                DigestFeedCoverage cov = feedCoverage(tenantId, date, row);
                String waitingOn = gate(tenantId, date, row, cov);
                if (waitingOn != null) {
                    jdbc.update("UPDATE digest_dispatch SET waiting_on = ? WHERE id = ?",
                            waitingOn, dispatchId);
                    continue;
                }
                // Atomic claim: whoever flips PENDING → SENDING owns the send.
                if (!claim(dispatchId, "PENDING")) continue;
                send(dispatchId, tenantId, date, (String) row.get("recipients"), attempts,
                        cov, truthy(row.get("subject_figures")), 0);
            } catch (Exception e) {
                log.warn("Digest for tenant {} date {} errored: {}", tenantId, date, e.toString());
                recordFailure(dispatchId, attempts, e.toString());
            }
        }
    }

    /**
     * @return null when ready to send, else a short label of what it is waiting on.
     *         Checked in the order a reader would act on: an incomplete day,
     *         missing feeds, a running ingest, the quiet period, the send time.
     */
    String gate(long tenantId, LocalDate date, Map<String, Object> cfg, DigestFeedCoverage cov) {
        // The business day is not over in the bank's own zone: nothing to send.
        if (!date.isBefore(LocalDate.now(tenantZone(tenantId)))) return "TODAY";

        String missing = cov.missingRequired();
        if (!missing.isEmpty()) return missing;

        // Never email mid-upload. Stale RUNNING rows (a crashed pod never
        // closed its run) stop blocking after 6 hours.
        if (cov.ingestRunning()) return "RUNNING";

        // Quiet period: a multi-file session sends files minutes apart, so wait
        // for the dust to settle after the LAST completed run before emailing.
        int quietMinutes = cfg.get("quiet_minutes") == null ? 15
                : ((Number) cfg.get("quiet_minutes")).intValue();
        if (exists(
                "SELECT 1 FROM ingest_run WHERE tenant_id = ? AND ended_at IS NOT NULL "
                + "AND ended_at > CURRENT_TIMESTAMP - (? * INTERVAL '1 minute') LIMIT 1",
                tenantId, quietMinutes)) {
            return "QUIET";
        }

        // Scheduled send time: a fully-ready day is still HELD until the
        // tenant-local wall clock passes send_not_before, so "send at 08:00"
        // gives one predictable morning email. Checked LAST so waiting_on
        // reports feed problems first (the more actionable reason).
        Object notBefore = cfg.get("send_not_before");
        if (notBefore != null) {
            LocalTime gateTime = notBefore instanceof java.sql.Time t
                    ? t.toLocalTime() : LocalTime.parse(notBefore.toString());
            if (LocalTime.now(tenantZone(tenantId)).isBefore(gateTime)) {
                return "SCHEDULE";
            }
        }

        return null;
    }

    /**
     * Per-feed required/present state for one tenant-day, plus what the
     * coverage ledger says was loaded. Shared by the gate, the readiness
     * panel and the email itself, so all three tell the same story.
     */
    DigestFeedCoverage feedCoverage(long tenantId, LocalDate date, Map<String, Object> cfg) {
        List<FeedState> feeds = new ArrayList<>();
        feeds.add(new FeedState(DigestFeedCoverage.MERCHANT, "Merchant master",
                truthy(cfg.get("require_merchant")), merchantPresent(tenantId, date)));
        feeds.add(new FeedState(DigestFeedCoverage.TRX, "Transactions",
                truthy(cfg.get("require_trx")), trxPresent(tenantId, date)));
        feeds.add(new FeedState(DigestFeedCoverage.DCC, "DCC revenue",
                truthy(cfg.get("require_dcc")), dccPresent(tenantId, date)));
        feeds.add(new FeedState(DigestFeedCoverage.RENTAL, "Rentals",
                truthy(cfg.get("require_rental")), rentalPresent(tenantId, date)));

        Map<String, Object> loaded = coverageRow(tenantId, date);
        Long rows = loaded == null || loaded.get("rows_fact") == null ? null
                : ((Number) loaded.get("rows_fact")).longValue();
        Timestamp at = loaded == null ? null : (Timestamp) loaded.get("last_loaded_at");
        return new DigestFeedCoverage(feeds, rows, at, ingestRunning(tenantId));
    }

    /** rows_fact / gross_amount / last_loaded_at for the day, or null when unknown. */
    Map<String, Object> coverageRow(long tenantId, LocalDate date) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT rows_fact, gross_amount, last_loaded_at FROM ingest_day_coverage "
                + "WHERE tenant_id = ? AND txn_date = ?", tenantId, date);
        return r.isEmpty() ? null : r.get(0);
    }

    /** PENDING → SENDING (or SENT → SENDING for a restatement); true if this call won the row. */
    boolean claim(long dispatchId, String fromStatus) {
        return jdbc.update(
                "UPDATE digest_dispatch SET status = 'SENDING', claimed_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND status = ?", dispatchId, fromStatus) == 1;
    }

    // ── Feed presence checks (shared by the sweep gate and dayStatus) ───────

    /**
     * Merchant master: an occasional upsert feed, not a daily one — presence
     * means the tenant's dimension is populated at all (ever loaded), or a
     * merchant load completed after the business day. A tenant that has ever
     * loaded merchants passes immediately; a brand-new tenant waits.
     */
    private boolean merchantPresent(long tenantId, LocalDate date) {
        return exists(
                "SELECT 1 FROM dim_merchant WHERE tenant_id = ? LIMIT 1", tenantId)
            || exists(
                "SELECT 1 FROM ingest_run WHERE tenant_id = ? AND status = 'COMPLETED' "
                + "AND job_name IN " + MERCHANT_JOBS + " AND ended_at >= ? LIMIT 1",
                tenantId, date);
    }

    private boolean trxPresent(long tenantId, LocalDate date) {
        return exists(
                "SELECT 1 FROM ingest_day_coverage WHERE tenant_id = ? AND txn_date = ? "
                + "AND COALESCE(rows_fact, 0) > 0", tenantId, date);
    }

    /**
     * DCC: either revenue rows exist for the day, or a DCC load COMPLETED
     * after the business day — a legitimately-zero-DCC day must not block
     * the digest forever, but an unprocessed feed must.
     */
    private boolean dccPresent(long tenantId, LocalDate date) {
        return exists(
                "SELECT 1 FROM fact_dcc_revenue WHERE tenant_id = ? AND payment_date = ? LIMIT 1",
                tenantId, date)
            || exists(
                "SELECT 1 FROM ingest_run WHERE tenant_id = ? AND status = 'COMPLETED' "
                + "AND job_name IN " + DCC_JOBS + " AND ended_at >= ? LIMIT 1",
                tenantId, date);
    }

    /**
     * Rentals bill monthly, so a rental load covering the day's MONTH
     * satisfies every day in it.
     */
    private boolean rentalPresent(long tenantId, LocalDate date) {
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate monthEnd = date.withDayOfMonth(date.lengthOfMonth());
        return exists(
                "SELECT 1 FROM fact_rental WHERE tenant_id = ? "
                + "AND payment_date BETWEEN ? AND ? LIMIT 1",
                tenantId, monthStart, monthEnd)
            || exists(
                "SELECT 1 FROM ingest_run WHERE tenant_id = ? AND status = 'COMPLETED' "
                + "AND job_name IN " + RENTAL_JOBS + " AND ended_at >= ? LIMIT 1",
                tenantId, monthStart);
    }

    private boolean ingestRunning(long tenantId) {
        return exists(
                "SELECT 1 FROM ingest_run WHERE tenant_id = ? AND status = 'RUNNING' "
                + "AND started_at > CURRENT_TIMESTAMP - INTERVAL '6 hours' LIMIT 1", tenantId);
    }

    // ── 5. Per-recipient retry ──────────────────────────────────────────────

    /**
     * A SENT day where some recipients failed is re-delivered — the STORED
     * copy, to the failed addresses only — until every recipient has it or
     * the attempts cap is reached. Nobody receives the email twice.
     */
    private void retryPartial() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.id, d.tenant_id, d.business_date, d.attempts, d.recipients_sent, "
                + "d.recipients_failed, d.sent_subject, d.sent_html "
                + "FROM digest_dispatch d JOIN digest_config g ON g.tenant_id = d.tenant_id "
                + "WHERE d.status = 'SENT' AND g.enabled = TRUE AND d.sent_html IS NOT NULL "
                + "AND COALESCE(d.recipients_failed, '') <> '' AND d.attempts < ?", MAX_ATTEMPTS);
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            long tenantId = ((Number) r.get("tenant_id")).longValue();
            List<String> failed = parseRecipients((String) r.get("recipients_failed"));
            if (failed.isEmpty()) continue;
            try {
                Delivery d = deliver(tenantId, failed, (String) r.get("sent_subject"), (String) r.get("sent_html"));
                List<String> sentTo = parseRecipients((String) r.get("recipients_sent"));
                sentTo.addAll(d.sent());
                jdbc.update(
                        "UPDATE digest_dispatch SET recipients_sent = ?, recipients_failed = ?, "
                        + "attempts = attempts + 1, error_message = ? WHERE id = ?",
                        String.join(",", new LinkedHashSet<>(sentTo)),
                        d.failed().isEmpty() ? null : String.join(",", d.failed()),
                        d.failed().isEmpty() ? null
                                : "Some recipients still failing: " + String.join(",", d.failed()),
                        id);
                if (!d.sent().isEmpty()) {
                    log.info("[DIGEST] Retried tenant {} date {}: reached {} more recipient(s)",
                            tenantId, r.get("business_date"), d.sent().size());
                }
            } catch (Exception e) {
                log.warn("Digest recipient retry for dispatch {} errored: {}", id, e.toString());
            }
        }
    }

    // ── 6. Restatements ─────────────────────────────────────────────────────

    /**
     * A sent day whose loaded data has since moved materially (re-ingest,
     * correction, late file) is either alerted on or re-sent as a labelled
     * restatement, per tenant. The snapshot taken at send time
     * (sent_rows_fact / sent_gross_amount) is what "moved" is measured
     * against; it is refreshed after each alert or resend so one change
     * produces one action, not one per sweep.
     */
    private void restatements() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.id, d.tenant_id, d.business_date, d.attempts, d.sent_at, "
                + "d.sent_rows_fact, d.sent_gross_amount, d.restate_count, "
                + "g.recipients, g.restate_mode, g.restate_threshold_pct, g.subject_figures, "
                + "g.require_merchant, g.require_trx, g.require_dcc, g.require_rental, "
                + "c.rows_fact AS now_rows, c.gross_amount AS now_gross "
                + "FROM digest_dispatch d "
                + "JOIN digest_config g ON g.tenant_id = d.tenant_id "
                + "JOIN ingest_day_coverage c ON c.tenant_id = d.tenant_id AND c.txn_date = d.business_date "
                + "WHERE d.status = 'SENT' AND g.enabled = TRUE AND d.sent_rows_fact IS NOT NULL "
                + "AND COALESCE(g.restate_mode, 'ALERT') <> 'OFF' "
                + "AND d.business_date >= CURRENT_DATE - (g.backfill_window_days * INTERVAL '1 day')");

        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            long tenantId = ((Number) r.get("tenant_id")).longValue();
            LocalDate date = ((java.sql.Date) r.get("business_date")).toLocalDate();
            Long oldRows = asLong(r.get("sent_rows_fact"));
            Long newRows = asLong(r.get("now_rows"));
            BigDecimal oldGross = asDecimal(r.get("sent_gross_amount"));
            BigDecimal newGross = asDecimal(r.get("now_gross"));
            BigDecimal threshold = asDecimal(r.get("restate_threshold_pct"));
            if (threshold == null) threshold = BigDecimal.ONE;

            if (!materialChange(oldRows, oldGross, newRows, newGross, threshold)) continue;

            String mode = r.get("restate_mode") == null ? RESTATE_ALERT
                    : r.get("restate_mode").toString().trim().toUpperCase(Locale.ROOT);
            int restateNo = (r.get("restate_count") == null ? 0 : ((Number) r.get("restate_count")).intValue()) + 1;
            String change = describeChange(oldRows, oldGross, newRows, newGross);

            try {
                if (RESTATE_RESEND.equals(mode)) {
                    DigestFeedCoverage cov = feedCoverage(tenantId, date, r);
                    if (!cov.missingRequired().isEmpty() || cov.ingestRunning()) continue; // still loading
                    if (!claim(id, "SENT")) continue;
                    send(id, tenantId, date, (String) r.get("recipients"),
                            ((Number) r.get("attempts")).intValue(), cov,
                            truthy(r.get("subject_figures")), restateNo);
                } else {
                    raiseAlert(tenantId, String.format(
                            "Daily Digest for %s was sent, then its data changed: %s. "
                            + "Review and resend from Operations > Daily Digest.", date, change),
                            pctChange(oldGross, newGross));
                    jdbc.update(
                            "UPDATE digest_dispatch SET sent_rows_fact = ?, sent_gross_amount = ?, "
                            + "restate_count = ?, restated_at = CURRENT_TIMESTAMP, error_message = ? WHERE id = ?",
                            newRows, newGross, restateNo,
                            truncate("Data changed after send (" + change + ") — alert raised, not resent"), id);
                }
            } catch (Exception e) {
                log.warn("Digest restatement for tenant {} date {} errored: {}", tenantId, date, e.toString());
            }
        }
    }

    /**
     * Material = the day's gross amount moved by at least {@code thresholdPct}
     * percent; when the ledger has no gross amount, any change in the fact row
     * count counts.
     */
    static boolean materialChange(Long oldRows, BigDecimal oldGross, Long newRows, BigDecimal newGross,
                                  BigDecimal thresholdPct) {
        if (oldGross != null && newGross != null) {
            Double pct = pctChange(oldGross, newGross);
            return pct != null && pct >= thresholdPct.doubleValue();
        }
        return !Objects.equals(oldRows, newRows);
    }

    /** Absolute % move from old to new gross; null when either is unknown. */
    static Double pctChange(BigDecimal oldGross, BigDecimal newGross) {
        if (oldGross == null || newGross == null) return null;
        BigDecimal base = oldGross.abs().max(BigDecimal.ONE);
        return newGross.subtract(oldGross).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(base, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String describeChange(Long oldRows, BigDecimal oldGross, Long newRows, BigDecimal newGross) {
        StringBuilder s = new StringBuilder();
        s.append("rows ").append(oldRows == null ? "?" : oldRows).append(" → ").append(newRows == null ? "?" : newRows);
        Double pct = pctChange(oldGross, newGross);
        if (pct != null) {
            s.append(", gross ").append(oldGross.setScale(2, RoundingMode.HALF_UP))
             .append(" → ").append(newGross.setScale(2, RoundingMode.HALF_UP))
             .append(String.format(" (%.2f%%)", pct));
        }
        return s.toString();
    }

    /**
     * alert_history is behind FORCE ROW LEVEL SECURITY, so the tenant must be
     * in context for both the dedupe read and the insert (same lesson as
     * IngestAlertScheduler.raise).
     */
    private void raiseAlert(long tenantId, String message, Double metric) {
        Long previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantId);
        try {
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM alert_history WHERE tenant_id = ? AND rule_name = ? "
                    + "AND triggered_at::date = CURRENT_DATE AND message = ?",
                    Integer.class, (int) tenantId, RULE_RESTATEMENT, message);
            if (existing != null && existing > 0) return;
            jdbc.update(
                    "INSERT INTO alert_history (tenant_id, rule_name, severity, message, metric_value, "
                    + "acknowledged, triggered_at) VALUES (?,?,?,?,?,FALSE,CURRENT_TIMESTAMP)",
                    (int) tenantId, RULE_RESTATEMENT, "MEDIUM", message, metric);
            log.info("[DIGEST] {} tenant={} {}", RULE_RESTATEMENT, tenantId, message);
        } catch (Exception e) {
            log.warn("Could not raise {} for tenant {} (non-fatal): {}", RULE_RESTATEMENT, tenantId, e.toString());
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.setCurrentTenant(previous);
        }
    }

    // ── Admin tools (DigestController) ──────────────────────────────────────

    private Map<String, Object> configRow(long tenantId) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT recipients, quiet_minutes, require_merchant, require_trx, require_dcc, "
                + "require_rental, send_not_before, subject_figures, allowed_domains "
                + "FROM digest_config WHERE tenant_id = ?", tenantId);
        return r.isEmpty() ? new HashMap<>() : r.get(0);
    }

    /** The feed picture the gate and the email see for one tenant-day. */
    public DigestFeedCoverage coverageFor(long tenantId, LocalDate date) {
        return feedCoverage(tenantId, date, configRow(tenantId));
    }

    /**
     * Per-feed readiness for one tenant-day — powers the "check a date"
     * panel on /ops/daily-digest. Pure reads, no side effects.
     */
    public Map<String, Object> dayStatus(long tenantId, LocalDate date) {
        Map<String, Object> cfg = configRow(tenantId);
        DigestFeedCoverage cov = feedCoverage(tenantId, date, cfg);
        Map<String, Object> out = new HashMap<>();
        out.put("date", date.toString());
        out.put("merchant", cov.present(DigestFeedCoverage.MERCHANT));
        out.put("trx", cov.present(DigestFeedCoverage.TRX));
        out.put("dcc", cov.present(DigestFeedCoverage.DCC));
        out.put("rental", cov.present(DigestFeedCoverage.RENTAL));
        out.put("running", cov.ingestRunning());
        out.put("today", !date.isBefore(LocalDate.now(tenantZone(tenantId))));
        Map<String, Boolean> required = new LinkedHashMap<>();
        for (FeedState f : cov.feeds()) required.put(f.key().toLowerCase(Locale.ROOT), f.required());
        out.put("required", required);
        out.put("rowsLoaded", cov.rowsFact());
        out.put("lastLoadedAt", cov.lastLoadedAt() == null ? null : cov.lastLoadedAt().toString());
        List<Map<String, Object>> d = jdbc.queryForList(
                "SELECT status, waiting_on, attempts, sent_at, recipients_sent, recipients_failed, "
                + "error_message, restate_count, restated_at, feeds_included, "
                + "(sent_html IS NOT NULL) AS has_copy "
                + "FROM digest_dispatch WHERE tenant_id = ? AND business_date = ?",
                tenantId, date);
        out.put("dispatch", d.isEmpty() ? null : d.get(0));
        return out;
    }

    /**
     * Admin-triggered REAL send for one business day (typically yesterday),
     * bypassing the quiet period, scheduled send time and the "not today"
     * rule — the admin is looking at the readiness panel and has decided.
     * Feed gates are bypassed too: a partially-loaded day sends with what it
     * has, and the email SAYS which feeds it covers. Send-once still holds: a
     * day already SENT is refused unless {@code force} (an explicit resend).
     */
    public Map<String, Object> runNow(long tenantId, LocalDate date, boolean force) {
        jdbc.update("INSERT INTO digest_dispatch (tenant_id, business_date, status) "
                + "VALUES (?, ?, 'PENDING') ON CONFLICT (tenant_id, business_date) DO NOTHING",
                tenantId, date);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, status, attempts, restate_count FROM digest_dispatch "
                + "WHERE tenant_id = ? AND business_date = ?", tenantId, date);
        long dispatchId = ((Number) row.get("id")).longValue();
        String status = (String) row.get("status");

        Map<String, Object> out = new HashMap<>();
        out.put("date", date.toString());
        if ("SENDING".equals(status)) {
            out.put("inProgress", true);
            return out;
        }
        if ("SENT".equals(status) && !force) {
            out.put("alreadySent", true);
            return out;
        }

        Map<String, Object> cfg = configRow(tenantId);
        DigestFeedCoverage cov = feedCoverage(tenantId, date, cfg);
        // A forced resend of a SENT day is a restatement by definition.
        int restateNo = "SENT".equals(status)
                ? (row.get("restate_count") == null ? 0 : ((Number) row.get("restate_count")).intValue()) + 1 : 0;
        // A manual run must never die on the attempts cap a dead SMTP built up.
        int attempts = Math.min(((Number) row.get("attempts")).intValue(), MAX_ATTEMPTS - 2);
        if (claim(dispatchId, status)) {
            send(dispatchId, tenantId, date, (String) cfg.get("recipients"), attempts, cov,
                    truthy(cfg.get("subject_figures")), restateNo);
        }

        Map<String, Object> result = jdbc.queryForMap(
                "SELECT status, sent_at, recipients_sent, recipients_failed, error_message, restate_count "
                + "FROM digest_dispatch WHERE id = ?", dispatchId);
        result.put("date", date.toString());
        return result;
    }

    /** Subject + HTML exactly as sent for one dispatch row (tenant-scoped), or null. */
    public Map<String, Object> sentCopy(long tenantId, long dispatchId) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT business_date, sent_subject, sent_html, sent_at, recipients_sent "
                + "FROM digest_dispatch WHERE id = ? AND tenant_id = ? AND sent_html IS NOT NULL",
                dispatchId, tenantId);
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * Re-deliver the STORED copy of a sent digest — byte-for-byte what went
     * out, not a re-render of today's data — to the configured recipients or
     * an explicit list. The ledger row is annotated, never rewritten.
     */
    public Map<String, Object> resendCopy(long tenantId, long dispatchId, List<String> recipients) {
        Map<String, Object> copy = sentCopy(tenantId, dispatchId);
        Map<String, Object> out = new HashMap<>();
        if (copy == null) {
            out.put("error", "No stored copy for this dispatch (sent before copies were kept).");
            return out;
        }
        List<String> to = recipients == null || recipients.isEmpty()
                ? parseRecipients((String) configRow(tenantId).get("recipients")) : recipients;
        if (to.isEmpty()) {
            out.put("error", "No recipients configured.");
            return out;
        }
        Delivery d = deliver(tenantId, to, (String) copy.get("sent_subject"), (String) copy.get("sent_html"));
        jdbc.update("UPDATE digest_dispatch SET error_message = ? WHERE id = ?",
                truncate("Copy resent " + LocalDate.now() + " to " + String.join(",", d.sent())
                        + (d.failed().isEmpty() ? "" : "; failed: " + String.join(",", d.failed()))),
                dispatchId);
        out.put("date", copy.get("business_date").toString());
        out.put("sent", d.sent().size());
        out.put("of", to.size());
        out.put("failed", d.failed());
        return out;
    }

    /**
     * FAILED days go back to PENDING with a clean attempt counter — called by
     * the admin's "Retry failed" button and automatically when SMTP settings
     * are saved (the usual cause of a FAILED streak). Returns rows released.
     */
    public int retryFailed(long tenantId) {
        int n = jdbc.update(
                "UPDATE digest_dispatch SET status = 'PENDING', attempts = 0, waiting_on = NULL, "
                + "error_message = NULL WHERE tenant_id = ? AND status = 'FAILED'", tenantId);
        if (n > 0) log.info("[DIGEST] Released {} FAILED day(s) for tenant {} to retry", n, tenantId);
        return n;
    }

    /**
     * Tenant-local zone from the locale.timezone tenant_setting (the same
     * value Settings → Regional & Data writes); server zone when unset or
     * invalid — never fail a send over a bad timezone string.
     */
    protected ZoneId tenantZone(long tenantId) {
        try {
            String tz = jdbc.query(
                    "SELECT setting_value FROM tenant_setting "
                    + "WHERE tenant_id = ? AND setting_key = 'locale.timezone'",
                    rs -> rs.next() ? rs.getString(1) : null, tenantId);
            if (tz != null && !tz.isBlank()) return ZoneId.of(tz.trim());
        } catch (Exception ignored) { /* fall through */ }
        return ZoneId.systemDefault();
    }

    // ── Send ────────────────────────────────────────────────────────────────

    /** Which addresses took the email and which did not. */
    record Delivery(List<String> sent, List<String> failed) {}

    private Delivery deliver(long tenantId, List<String> to, String subject, String html) {
        List<String> sent = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        TenantContext.setCurrentTenant(tenantId);
        try {
            for (String addr : to) {
                // null attachment => plain HTML email through the tenant's SMTP.
                if (emailService.sendEmailWithAttachment(addr, subject, html, null, null, null)) sent.add(addr);
                else failed.add(addr);
            }
        } finally {
            TenantContext.clear();
        }
        return new Delivery(sent, failed);
    }

    /**
     * Render, deliver and record. The row is expected to be SENDING (claimed).
     * {@code restateNo} > 0 marks a restatement: the subject and a banner say
     * so, and on total failure the row returns to SENT (the original email
     * did go out) rather than to the PENDING/FAILED path.
     */
    private void send(long dispatchId, long tenantId, LocalDate date, String recipients,
                      int attempts, DigestFeedCoverage cov, boolean subjectFigures, int restateNo) {
        List<String> to = parseRecipients(recipients);
        if (to.isEmpty()) {
            recordFailure(dispatchId, MAX_ATTEMPTS, "No recipients configured");
            return;
        }

        Timestamp originalSentAt = restateNo > 0 ? jdbc.query(
                "SELECT sent_at FROM digest_dispatch WHERE id = ?",
                rs -> rs.next() ? rs.getTimestamp(1) : null, dispatchId) : null;

        DigestContentService.DigestData data = content.build(tenantId, date, cov);
        data.restateNo = restateNo;
        data.originalSentAt = originalSentAt == null ? null : originalSentAt.toLocalDateTime();
        String subject = emailRenderer.subject(data, subjectFigures);
        String html = emailRenderer.render(data);

        Delivery d = deliver(tenantId, to, subject, html);

        if (d.sent().isEmpty()) {
            if (restateNo > 0) {
                jdbc.update("UPDATE digest_dispatch SET status = 'SENT', claimed_at = NULL, "
                        + "attempts = attempts + 1, error_message = ? WHERE id = ?",
                        truncate("Restatement delivery failed for all recipients"), dispatchId);
            } else {
                recordFailure(dispatchId, attempts, "SMTP delivery failed for all recipients");
            }
            return;
        }

        Map<String, Object> loaded = coverageRow(tenantId, date);
        Long rowsFact = loaded == null ? null : asLong(loaded.get("rows_fact"));
        BigDecimal gross = loaded == null ? null : asDecimal(loaded.get("gross_amount"));

        jdbc.update(
            "UPDATE digest_dispatch SET status = 'SENT', sent_at = CURRENT_TIMESTAMP, claimed_at = NULL, "
            + "waiting_on = NULL, recipients_sent = ?, recipients_failed = ?, error_message = ?, "
            + "attempts = attempts + 1, sent_subject = ?, sent_html = ?, feeds_included = ?, "
            + "sent_rows_fact = ?, sent_gross_amount = ?, "
            + "restate_count = ?, restated_at = CASE WHEN ? > 0 THEN CURRENT_TIMESTAMP ELSE restated_at END "
            + "WHERE id = ?",
            String.join(",", d.sent()),
            d.failed().isEmpty() ? null : String.join(",", d.failed()),
            d.failed().isEmpty() ? null
                    : "Some recipients failed: sent " + d.sent().size() + "/" + to.size() + " (will retry)",
            subject, html, cov.included(), rowsFact, gross, restateNo, restateNo,
            dispatchId);
        log.info("[DIGEST] Sent {}daily digest for tenant {} date {} to {} recipient(s)",
                restateNo > 0 ? "RESTATED " : "", tenantId, date, d.sent().size());
    }

    private void recordFailure(long dispatchId, int attempts, String error) {
        boolean giveUp = attempts + 1 >= MAX_ATTEMPTS;
        jdbc.update(
            "UPDATE digest_dispatch SET attempts = attempts + 1, error_message = ?, "
            + "status = ?, waiting_on = NULL, claimed_at = NULL WHERE id = ?",
            truncate(error), giveUp ? "FAILED" : "PENDING", dispatchId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    public static List<String> parseRecipients(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,;\\s]+")) {
            String p = part.trim();
            if (!p.isEmpty() && p.contains("@") && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /** "bank.com, @ops.bank.com" → {bank.com, ops.bank.com}; empty when unrestricted. */
    public static Set<String> parseDomains(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String part : raw.split("[,;\\s]+")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            while (p.startsWith("@")) p = p.substring(1);
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * Recipients whose domain is not on the tenant's allowed list. An empty
     * list means unrestricted. A subdomain of an allowed domain is allowed
     * (ops.bank.com under bank.com).
     */
    public static List<String> recipientsOutsideDomains(List<String> recipients, String allowedDomains) {
        Set<String> allowed = parseDomains(allowedDomains);
        List<String> outside = new ArrayList<>();
        if (allowed.isEmpty() || recipients == null) return outside;
        for (String r : recipients) {
            int at = r.lastIndexOf('@');
            String domain = at < 0 ? "" : r.substring(at + 1).trim().toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String a : allowed) {
                if (domain.equals(a) || domain.endsWith("." + a)) { ok = true; break; }
            }
            if (!ok) outside.add(r);
        }
        return outside;
    }

    protected boolean exists(String sql, Object... params) {
        List<Integer> r = jdbc.query(sql, (rs, i) -> 1, params);
        return !r.isEmpty();
    }

    private static boolean truthy(Object o) {
        return o instanceof Boolean b ? b : o != null && "t".equalsIgnoreCase(o.toString());
    }

    private static Long asLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static BigDecimal asDecimal(Object o) {
        if (o == null) return null;
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
