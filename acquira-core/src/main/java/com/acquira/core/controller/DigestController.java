package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.service.AuditService;
import com.acquira.core.service.DigestContentService;
import com.acquira.core.service.DigestEmailService;
import com.acquira.core.service.DigestFeedCoverage;
import com.acquira.core.service.DigestScheduler;
import com.acquira.core.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Admin API behind /ops/daily-digest — configure the Daily Dashboard Digest,
 * watch its dispatch ledger, preview the email, test-send it, view and resend
 * the exact copy that went out, and release failed days for retry.
 *
 * Gated by menu assignment like the rest of the platform (Phase 1, I-9): a
 * group granted Operations → Daily Digest can use it, super-admins always
 * can. Every query is scoped to the caller's tenant.
 *
 * Every change and every send is written to audit_log with a descriptive
 * action (I-7) — the generic per-request row only said "PUT".
 *
 * Test-send and preview BYPASS the feed gate on purpose — an admin setting the
 * feature up needs to see the email now, not after tomorrow's files.
 */
@RestController
@RequestMapping("/api/ops/digest")
@PreAuthorize("@menuAccess.canAccess('/ops/daily-digest')")
public class DigestController {

    private static final Logger log = LoggerFactory.getLogger(DigestController.class);
    private static final Set<String> RESTATE_MODES = Set.of("OFF", "ALERT", "RESEND");

    private final JdbcTemplate jdbc;
    private final DigestContentService content;
    private final DigestEmailService renderer;
    private final EmailService emailService;
    private final DigestScheduler scheduler;
    private final AuditService auditService;

    public DigestController(JdbcTemplate jdbc, DigestContentService content,
                            DigestEmailService renderer, EmailService emailService,
                            DigestScheduler scheduler, AuditService auditService) {
        this.jdbc = jdbc;
        this.content = content;
        this.renderer = renderer;
        this.emailService = emailService;
        this.scheduler = scheduler;
        this.auditService = auditService;
    }

    private Long tenantId() {
        return TenantContext.getCurrentTenant();
    }

    private String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    /** Auditing must never break the admin's action. */
    private void audit(String action, String details) {
        try {
            auditService.log(action, details);
        } catch (Exception e) {
            log.warn("audit_log write failed for {} (non-fatal): {}", action, e.toString());
        }
    }

    // ── Config ──────────────────────────────────────────────────────────────

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();
        // The migration seeds a row per tenant; belt-and-braces for tenants
        // created after it ran.
        jdbc.update("INSERT INTO digest_config (tenant_id) VALUES (?) "
                + "ON CONFLICT (tenant_id) DO NOTHING", tid);
        Map<String, Object> cfg = jdbc.queryForMap(
                "SELECT tenant_id, enabled, recipients, quiet_minutes, require_merchant, "
                + "require_trx, require_dcc, require_rental, backfill_window_days, send_not_before, "
                + "subject_figures, allowed_domains, restate_mode, restate_threshold_pct, "
                + "updated_by, updated_at "
                + "FROM digest_config WHERE tenant_id = ?", tid);
        // TIME → "HH:mm" so the frontend's <input type="time"> takes it as-is.
        Object t = cfg.get("send_not_before");
        if (t != null) cfg.put("send_not_before", t.toString().substring(0, 5));
        return ResponseEntity.ok(cfg);
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> body) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String recipients = body.get("recipients") == null ? null : body.get("recipients").toString().trim();
        List<String> parsed = DigestScheduler.parseRecipients(recipients);
        if (enabled && parsed.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "At least one valid recipient email is required to enable the digest."));
        }

        // Allowed domains (I-7): blank = unrestricted. Once set, every saved
        // recipient must sit on the list — the confidential P&L stays in-house.
        String allowedDomains = body.get("allowedDomains") == null ? null
                : String.join(",", DigestScheduler.parseDomains(body.get("allowedDomains").toString()));
        if (allowedDomains != null && allowedDomains.isBlank()) allowedDomains = null;
        List<String> outside = DigestScheduler.recipientsOutsideDomains(parsed, allowedDomains);
        if (!outside.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Recipient(s) outside the allowed domains: " + String.join(", ", outside)));
        }

        int quiet = clamp(intOf(body.get("quietMinutes"), 15), 0, 240);
        int window = clamp(intOf(body.get("backfillWindowDays"), 3), 1, 14);

        // Scheduled send time — "HH:mm" or blank/null for "as soon as ready".
        java.sql.Time notBefore = null;
        Object rawTime = body.get("sendNotBefore");
        if (rawTime != null && !rawTime.toString().isBlank()) {
            try {
                notBefore = java.sql.Time.valueOf(java.time.LocalTime.parse(rawTime.toString().trim()));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Send time must be HH:mm (e.g. 08:00), or blank for as-soon-as-ready."));
            }
        }

        String restateMode = body.get("restateMode") == null ? "ALERT"
                : body.get("restateMode").toString().trim().toUpperCase(Locale.ROOT);
        if (!RESTATE_MODES.contains(restateMode)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Restatement mode must be OFF, ALERT or RESEND."));
        }
        BigDecimal threshold;
        try {
            threshold = body.get("restateThresholdPct") == null ? new BigDecimal("1.00")
                    : new BigDecimal(body.get("restateThresholdPct").toString().trim());
            if (threshold.signum() < 0 || threshold.compareTo(BigDecimal.valueOf(100)) > 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Restatement threshold must be a percentage between 0 and 100."));
        }

        Map<String, Object> before = jdbc.queryForMap(
                "SELECT enabled, recipients, allowed_domains, subject_figures, restate_mode "
                + "FROM digest_config WHERE tenant_id = ?", tid);

        // Transactions are the ONLY mandatory feed (decision 2026-09-10): a bank
        // that just loads transactions must be able to complete. DCC / rental /
        // merchant are optional and default OFF — an admin turns one on only if
        // the bank actually receives that feed.
        jdbc.update(
            "UPDATE digest_config SET enabled = ?, recipients = ?, quiet_minutes = ?, "
            + "require_merchant = ?, require_trx = TRUE, require_dcc = ?, require_rental = ?, "
            + "backfill_window_days = ?, send_not_before = ?, subject_figures = ?, allowed_domains = ?, "
            + "restate_mode = ?, restate_threshold_pct = ?, "
            + "updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ?",
            enabled, String.join(",", parsed), quiet,
            boolOf(body.get("requireMerchant"), false),
            boolOf(body.get("requireDcc"), false),
            boolOf(body.get("requireRental"), false),
            window, notBefore,
            boolOf(body.get("subjectFigures"), false), allowedDomains,
            restateMode, threshold,
            username(), tid);

        audit("DIGEST_CONFIG_CHANGED", String.format(
                "enabled %s→%s; recipients [%s]→[%s]; allowed domains [%s]→[%s]; figures in subject %s→%s; "
                + "restatement %s→%s (%s%%); send time %s; quiet %d min; window %d days",
                before.get("enabled"), enabled, nz(before.get("recipients")), String.join(",", parsed),
                nz(before.get("allowed_domains")), nz(allowedDomains),
                before.get("subject_figures"), boolOf(body.get("subjectFigures"), false),
                before.get("restate_mode"), restateMode, threshold.toPlainString(),
                notBefore == null ? "as soon as ready" : notBefore.toString().substring(0, 5), quiet, window));
        return getConfig();
    }

    // ── Dispatch ledger ─────────────────────────────────────────────────────

    @GetMapping("/dispatches")
    public ResponseEntity<List<Map<String, Object>>> dispatches(
            @RequestParam(defaultValue = "30") int limit) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();
        if (limit < 1 || limit > 200) limit = 30;
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT id, business_date, status, waiting_on, attempts, created_at, "
                + "sent_at, recipients_sent, recipients_failed, error_message, feeds_included, "
                + "restate_count, restated_at, (sent_html IS NOT NULL) AS has_copy "
                + "FROM digest_dispatch WHERE tenant_id = ? "
                + "ORDER BY business_date DESC LIMIT " + limit, tid));
    }

    /** The exact email that went out for one day (I-6): stored at send time, never re-rendered. */
    @GetMapping(value = "/dispatches/{id}/email", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> sentEmail(@PathVariable long id) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();
        Map<String, Object> copy = scheduler.sentCopy(tid, id);
        if (copy == null) {
            return ResponseEntity.status(404).body("<p style=\"font-family:sans-serif;color:#64748b;\">"
                    + "No stored copy for this day (it was sent before copies were kept).</p>");
        }
        return ResponseEntity.ok("<!-- Subject: " + copy.get("sent_subject") + " | sent " + copy.get("sent_at")
                + " to " + copy.get("recipients_sent") + " -->\n" + copy.get("sent_html"));
    }

    /** Re-deliver the stored copy — to the configured recipients, or to the addresses given. */
    @PostMapping("/dispatches/{id}/resend")
    public ResponseEntity<Map<String, Object>> resendCopy(@PathVariable long id,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();
        List<String> to = DigestScheduler.parseRecipients(
                body == null || body.get("recipients") == null ? null : body.get("recipients").toString());
        ResponseEntity<Map<String, Object>> blocked = enforceDomains(tid, to);
        if (blocked != null) return blocked;
        Map<String, Object> result = scheduler.resendCopy(tid, id, to);
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result);
        audit("DIGEST_RESEND_COPY", "Resent stored digest copy for " + result.get("date")
                + " to " + result.get("sent") + "/" + result.get("of") + " recipient(s)"
                + (to.isEmpty() ? "" : " [" + String.join(",", to) + "]"));
        return ResponseEntity.ok(result);
    }

    /** Release every FAILED day back to PENDING so the next sweep retries it (I-10). */
    @PostMapping("/retry-failed")
    public ResponseEntity<Map<String, Object>> retryFailed() {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();
        int n = scheduler.retryFailed(tid);
        audit("DIGEST_RETRY_FAILED", "Released " + n + " failed digest day(s) for retry");
        return ResponseEntity.ok(Map.of("released", n));
    }

    // ── Run for a specific day ──────────────────────────────────────────────

    /**
     * Feed readiness for one business day (defaults to yesterday) — what the
     * gate sees, plus any existing dispatch row. Read-only.
     */
    @GetMapping("/day-status")
    public ResponseEntity<Map<String, Object>> dayStatus(@RequestParam(required = false) String date) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();
        LocalDate d = date != null && !date.isBlank()
                ? LocalDate.parse(date.trim()) : LocalDate.now().minusDays(1);
        return ResponseEntity.ok(scheduler.dayStatus(tid, d));
    }

    /**
     * Send the REAL digest for one business day now — bypasses quiet period,
     * scheduled send time, the not-today rule and feed gates (the admin has
     * the readiness panel in front of them). Send-once still holds: an
     * already-SENT day returns alreadySent:true unless force=true (explicit
     * resend, which goes out labelled as a restatement).
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> body) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();
        String date = body == null || body.get("date") == null ? null : body.get("date").toString();
        LocalDate d = date != null && !date.isBlank()
                ? LocalDate.parse(date.trim()) : LocalDate.now().minusDays(1);
        if (d.isAfter(LocalDate.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot run the digest for a future date."));
        }
        boolean force = body != null && boolOf(body.get("force"), false);
        Map<String, Object> result = scheduler.runNow(tid, d, force);
        if (!Boolean.TRUE.equals(result.get("alreadySent")) && !Boolean.TRUE.equals(result.get("inProgress"))) {
            audit("DIGEST_MANUAL_SEND", String.format("Manual digest send for %s%s → %s (%s)",
                    d, force ? " (forced resend)" : "", result.get("status"),
                    nz(result.get("recipients_sent"))));
        }
        return ResponseEntity.ok(result);
    }

    // ── Preview & test-send (gate-bypassing, latest loaded day) ─────────────

    private LocalDate latestLoadedDate(Long tid) {
        return jdbc.query(
                "SELECT MAX(txn_date) d FROM ingest_day_coverage "
                + "WHERE tenant_id = ? AND COALESCE(rows_fact, rows_summary, 0) > 0",
                rs -> rs.next() && rs.getDate("d") != null ? rs.getDate("d").toLocalDate() : null,
                tid);
    }

    private DigestContentService.DigestData buildFor(Long tid, LocalDate d) {
        DigestFeedCoverage cov = scheduler.coverageFor(tid, d);
        return content.build(tid, d, cov);
    }

    @GetMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(@RequestParam(required = false) String date) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();
        LocalDate d = date != null && !date.isBlank()
                ? LocalDate.parse(date.trim()) : latestLoadedDate(tid);
        if (d == null) {
            return ResponseEntity.ok("<p style=\"font-family:sans-serif;color:#64748b;\">"
                    + "No loaded business dates yet — upload data first.</p>");
        }
        return ResponseEntity.ok(renderer.render(buildFor(tid, d)));
    }

    @PostMapping("/test-send")
    public ResponseEntity<Map<String, Object>> testSend(@RequestBody(required = false) Map<String, Object> body) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();

        String override = body == null || body.get("recipients") == null
                ? null : body.get("recipients").toString();
        List<String> to = DigestScheduler.parseRecipients(override);
        Map<String, Object> cfg = jdbc.queryForMap(
                "SELECT recipients, subject_figures, allowed_domains FROM digest_config WHERE tenant_id = ?", tid);
        if (to.isEmpty()) {
            to = DigestScheduler.parseRecipients((String) cfg.get("recipients"));
        }
        if (to.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No recipients: pass some or save them in the config first."));
        }
        ResponseEntity<Map<String, Object>> blocked = enforceDomains(tid, to);
        if (blocked != null) return blocked;

        LocalDate d = latestLoadedDate(tid);
        if (d == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No loaded business dates yet — upload data first."));
        }

        DigestContentService.DigestData data = buildFor(tid, d);
        String html = renderer.render(data);
        String subject = "[TEST] " + renderer.subject(data, Boolean.TRUE.equals(cfg.get("subject_figures")));

        int sent = 0;
        for (String addr : to) {
            if (emailService.sendEmailWithAttachment(addr, subject, html, null, null, null)) sent++;
        }
        audit("DIGEST_TEST_SEND", "Test digest for " + d + " sent to " + sent + "/" + to.size()
                + " [" + String.join(",", to) + "]");
        if (sent == 0) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "SMTP delivery failed for every recipient — check SMTP Settings."));
        }
        return ResponseEntity.ok(Map.of("sent", sent, "of", to.size(), "date", d.toString()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** 400 when any address is outside the tenant's allowed domains; null when fine. */
    private ResponseEntity<Map<String, Object>> enforceDomains(Long tid, List<String> to) {
        if (to == null || to.isEmpty()) return null;
        String allowed = jdbc.query("SELECT allowed_domains FROM digest_config WHERE tenant_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, tid);
        List<String> outside = DigestScheduler.recipientsOutsideDomains(to, allowed);
        if (outside.isEmpty()) return null;
        Map<String, Object> err = new HashMap<>();
        err.put("error", "Recipient(s) outside the allowed domains: " + String.join(", ", outside));
        return ResponseEntity.badRequest().body(err);
    }

    private static String nz(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int intOf(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? dflt : Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static boolean boolOf(Object o, boolean dflt) {
        return o == null ? dflt : Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(o.toString());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
