package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the Daily Dashboard Digest — one email per tenant per business day,
 * only once EVERY required feed for that day has landed.
 *
 * TIMER-DRIVEN BY DESIGN (no batch-job listener): a day's data often arrives
 * as several files minutes apart (transactions, then DCC, then rentals), so
 * "the moment a job finishes" is exactly the wrong moment to email. Instead a
 * 5-minute sweep
 *   1. DISCOVERS loaded days from ingest_day_coverage (recent days only —
 *      a 6-month backfill must not fire 180 emails),
 *   2. GATES each pending day on: transactions in fact, a DCC load covering
 *      the day, a rental load covering the day's month (each per-tenant
 *      toggleable), no ingest currently RUNNING, and a quiet period since the
 *      tenant's last run (debounces multi-file upload sessions),
 *   3. SENDS at most once — digest_dispatch's UNIQUE(tenant_id,business_date)
 *      is the idempotency guarantee, and attempts are capped so a dead SMTP
 *      config surfaces as FAILED on the admin screen instead of retrying
 *      forever.
 *
 * EmailService resolves the tenant's own SMTP config from TenantContext, so
 * the context is set explicitly around each send (scheduler threads have none).
 */
@Service
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    private static final int MAX_ATTEMPTS = 5;

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
            discover();
            process();
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
     */
    private void discover() {
        jdbc.update(
            "INSERT INTO digest_dispatch (tenant_id, business_date, status) "
            + "SELECT c.tenant_id, c.txn_date, 'PENDING' "
            + "FROM ingest_day_coverage c "
            + "JOIN digest_config g ON g.tenant_id = c.tenant_id AND g.enabled = TRUE "
            + "WHERE COALESCE(c.rows_fact, 0) > 0 "
            + "AND c.txn_date >= CURRENT_DATE - (g.backfill_window_days * INTERVAL '1 day') "
            + "AND c.txn_date <= CURRENT_DATE "
            + "ON CONFLICT (tenant_id, business_date) DO NOTHING");
    }

    // ── 2 + 3. Gate and send ────────────────────────────────────────────────

    private void process() {
        List<Map<String, Object>> pending = jdbc.queryForList(
            "SELECT p.id, p.tenant_id, p.business_date, p.attempts, "
            + "g.recipients, g.quiet_minutes, g.require_merchant, g.require_trx, g.require_dcc, "
            + "g.require_rental, g.send_not_before "
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
                String waitingOn = gate(tenantId, date, row);
                if (waitingOn != null) {
                    jdbc.update("UPDATE digest_dispatch SET waiting_on = ? WHERE id = ?",
                            waitingOn, dispatchId);
                    continue;
                }
                send(dispatchId, tenantId, date, (String) row.get("recipients"), attempts);
            } catch (Exception e) {
                log.warn("Digest for tenant {} date {} errored: {}", tenantId, date, e.toString());
                recordFailure(dispatchId, attempts, e.toString());
            }
        }
    }

    /** @return null when ready to send, else a short label of what it is waiting on. */
    private String gate(long tenantId, LocalDate date, Map<String, Object> cfg) {
        boolean requireMerchant = truthy(cfg.get("require_merchant"));
        boolean requireTrx = truthy(cfg.get("require_trx"));
        boolean requireDcc = truthy(cfg.get("require_dcc"));
        boolean requireRental = truthy(cfg.get("require_rental"));
        int quietMinutes = cfg.get("quiet_minutes") == null ? 15
                : ((Number) cfg.get("quiet_minutes")).intValue();

        List<String> waiting = new ArrayList<>();

        if (requireMerchant && !merchantPresent(tenantId, date)) waiting.add("MERCHANT");
        if (requireTrx && !trxPresent(tenantId, date)) waiting.add("TRX");
        if (requireDcc && !dccPresent(tenantId, date)) waiting.add("DCC");
        if (requireRental && !rentalPresent(tenantId, date)) waiting.add("RENTAL");

        if (!waiting.isEmpty()) return String.join("+", waiting);

        // Never email mid-upload. Stale RUNNING rows (a crashed pod never
        // closed its run) stop blocking after 6 hours.
        if (ingestRunning(tenantId)) {
            return "RUNNING";
        }

        // Quiet period: a multi-file session sends files minutes apart, so wait
        // for the dust to settle after the LAST completed run before emailing.
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

    // ── Admin tools (DigestController) ──────────────────────────────────────

    /**
     * Per-feed readiness for one tenant-day — powers the "check a date"
     * panel on /ops/daily-digest. Pure reads, no side effects.
     */
    public Map<String, Object> dayStatus(long tenantId, LocalDate date) {
        Map<String, Object> out = new HashMap<>();
        out.put("date", date.toString());
        out.put("merchant", merchantPresent(tenantId, date));
        out.put("trx", trxPresent(tenantId, date));
        out.put("dcc", dccPresent(tenantId, date));
        out.put("rental", rentalPresent(tenantId, date));
        out.put("running", ingestRunning(tenantId));
        List<Map<String, Object>> d = jdbc.queryForList(
                "SELECT status, waiting_on, attempts, sent_at, recipients_sent, error_message "
                + "FROM digest_dispatch WHERE tenant_id = ? AND business_date = ?",
                tenantId, date);
        out.put("dispatch", d.isEmpty() ? null : d.get(0));
        return out;
    }

    /**
     * Admin-triggered REAL send for one business day (typically yesterday),
     * bypassing the quiet period and scheduled send time — the admin is
     * looking at the readiness panel and has decided. Feed gates are bypassed
     * too: a partially-loaded day sends with what it has, clearly the admin's
     * call. Send-once still holds: a day already SENT is refused unless
     * {@code force} (an explicit resend).
     */
    public Map<String, Object> runNow(long tenantId, LocalDate date, boolean force) {
        jdbc.update("INSERT INTO digest_dispatch (tenant_id, business_date, status) "
                + "VALUES (?, ?, 'PENDING') ON CONFLICT (tenant_id, business_date) DO NOTHING",
                tenantId, date);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, status, attempts FROM digest_dispatch "
                + "WHERE tenant_id = ? AND business_date = ?", tenantId, date);
        long dispatchId = ((Number) row.get("id")).longValue();

        if ("SENT".equals(row.get("status")) && !force) {
            Map<String, Object> out = new HashMap<>();
            out.put("alreadySent", true);
            out.put("date", date.toString());
            return out;
        }

        String recipients = jdbc.query(
                "SELECT recipients FROM digest_config WHERE tenant_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId);
        // A manual run must never die on the attempts cap a dead SMTP built up.
        int attempts = Math.min(((Number) row.get("attempts")).intValue(), MAX_ATTEMPTS - 2);
        send(dispatchId, tenantId, date, recipients, attempts);

        Map<String, Object> result = jdbc.queryForMap(
                "SELECT status, sent_at, recipients_sent, error_message "
                + "FROM digest_dispatch WHERE id = ?", dispatchId);
        result.put("date", date.toString());
        return result;
    }

    /**
     * Tenant-local zone from the locale.timezone tenant_setting (the same
     * value Settings → Regional & Data writes); server zone when unset or
     * invalid — never fail a send over a bad timezone string.
     */
    private ZoneId tenantZone(long tenantId) {
        try {
            String tz = jdbc.query(
                    "SELECT setting_value FROM tenant_setting "
                    + "WHERE tenant_id = ? AND setting_key = 'locale.timezone'",
                    rs -> rs.next() ? rs.getString(1) : null, tenantId);
            if (tz != null && !tz.isBlank()) return ZoneId.of(tz.trim());
        } catch (Exception ignored) { /* fall through */ }
        return ZoneId.systemDefault();
    }

    private void send(long dispatchId, long tenantId, LocalDate date,
                      String recipients, int attempts) {
        List<String> to = parseRecipients(recipients);
        if (to.isEmpty()) {
            recordFailure(dispatchId, MAX_ATTEMPTS, "No recipients configured");
            return;
        }

        DigestContentService.DigestData data = content.build(tenantId, date);
        String subject = emailRenderer.subject(data);
        String html = emailRenderer.render(data);

        List<String> sentTo = new ArrayList<>();
        TenantContext.setCurrentTenant(tenantId);
        try {
            for (String addr : to) {
                // null attachment => plain HTML email through the tenant's SMTP.
                if (emailService.sendEmailWithAttachment(addr, subject, html, null, null, null)) {
                    sentTo.add(addr);
                }
            }
        } finally {
            TenantContext.clear();
        }

        if (sentTo.isEmpty()) {
            recordFailure(dispatchId, attempts, "SMTP delivery failed for all recipients");
            return;
        }
        jdbc.update(
            "UPDATE digest_dispatch SET status = 'SENT', sent_at = CURRENT_TIMESTAMP, "
            + "waiting_on = NULL, recipients_sent = ?, error_message = ?, attempts = attempts + 1 "
            + "WHERE id = ?",
            String.join(",", sentTo),
            sentTo.size() < to.size() ? "Some recipients failed: sent " + sentTo.size() + "/" + to.size() : null,
            dispatchId);
        log.info("[DIGEST] Sent daily digest for tenant {} date {} to {} recipient(s)",
                tenantId, date, sentTo.size());
    }

    private void recordFailure(long dispatchId, int attempts, String error) {
        boolean giveUp = attempts + 1 >= MAX_ATTEMPTS;
        jdbc.update(
            "UPDATE digest_dispatch SET attempts = attempts + 1, error_message = ?, "
            + "status = ?, waiting_on = NULL WHERE id = ?",
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

    private boolean exists(String sql, Object... params) {
        List<Integer> r = jdbc.query(sql, (rs, i) -> 1, params);
        return !r.isEmpty();
    }

    private static boolean truthy(Object o) {
        return o instanceof Boolean b ? b : o != null && "t".equalsIgnoreCase(o.toString());
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
