package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.core.service.DigestContentService;
import com.acquira.core.service.DigestEmailService;
import com.acquira.core.service.DigestScheduler;
import com.acquira.core.service.EmailService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin API behind /ops/daily-digest — configure the Daily Dashboard Digest,
 * watch its dispatch ledger, preview the email, and test-send it.
 *
 * Gated like IngestTrustController (the improvement audit's ungated-controller
 * lesson): admins only, and every query is scoped to the caller's tenant.
 * Test-send and preview BYPASS the feed gate on purpose — an admin setting the
 * feature up needs to see the email now, not after tomorrow's files.
 */
@RestController
@RequestMapping("/api/ops/digest")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class DigestController {

    private final JdbcTemplate jdbc;
    private final DigestContentService content;
    private final DigestEmailService renderer;
    private final EmailService emailService;
    private final DigestScheduler scheduler;

    public DigestController(JdbcTemplate jdbc, DigestContentService content,
                            DigestEmailService renderer, EmailService emailService,
                            DigestScheduler scheduler) {
        this.jdbc = jdbc;
        this.content = content;
        this.renderer = renderer;
        this.emailService = emailService;
        this.scheduler = scheduler;
    }

    private Long tenantId() {
        return TenantContext.getCurrentTenant();
    }

    private String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
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

        jdbc.update(
            "UPDATE digest_config SET enabled = ?, recipients = ?, quiet_minutes = ?, "
            + "require_merchant = ?, require_trx = ?, require_dcc = ?, require_rental = ?, "
            + "backfill_window_days = ?, send_not_before = ?, "
            + "updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ?",
            enabled, String.join(",", parsed), quiet,
            boolOf(body.get("requireMerchant"), true),
            boolOf(body.get("requireTrx"), true),
            boolOf(body.get("requireDcc"), true),
            boolOf(body.get("requireRental"), true),
            window, notBefore, username(), tid);
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
                + "sent_at, recipients_sent, error_message "
                + "FROM digest_dispatch WHERE tenant_id = ? "
                + "ORDER BY business_date DESC LIMIT " + limit, tid));
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
     * scheduled send time and feed gates (the admin has the readiness panel in
     * front of them). Send-once still holds: an already-SENT day returns
     * alreadySent:true unless force=true (explicit resend).
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
        return ResponseEntity.ok(scheduler.runNow(tid, d, force));
    }

    // ── Preview & test-send (gate-bypassing, latest loaded day) ─────────────

    private LocalDate latestLoadedDate(Long tid) {
        return jdbc.query(
                "SELECT MAX(txn_date) d FROM ingest_day_coverage "
                + "WHERE tenant_id = ? AND COALESCE(rows_fact, rows_summary, 0) > 0",
                rs -> rs.next() && rs.getDate("d") != null ? rs.getDate("d").toLocalDate() : null,
                tid);
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
        return ResponseEntity.ok(renderer.render(content.build(tid, d)));
    }

    @PostMapping("/test-send")
    public ResponseEntity<Map<String, Object>> testSend(@RequestBody(required = false) Map<String, Object> body) {
        Long tid = tenantId();
        if (tid == null) return ResponseEntity.status(403).build();

        String override = body == null || body.get("recipients") == null
                ? null : body.get("recipients").toString();
        List<String> to = DigestScheduler.parseRecipients(override);
        if (to.isEmpty()) {
            String cfgRecipients = jdbc.query(
                    "SELECT recipients FROM digest_config WHERE tenant_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, tid);
            to = DigestScheduler.parseRecipients(cfgRecipients);
        }
        if (to.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No recipients: pass some or save them in the config first."));
        }

        LocalDate d = latestLoadedDate(tid);
        if (d == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No loaded business dates yet — upload data first."));
        }

        DigestContentService.DigestData data = content.build(tid, d);
        String html = renderer.render(data);
        String subject = "[TEST] " + renderer.subject(data);

        int sent = 0;
        for (String addr : to) {
            if (emailService.sendEmailWithAttachment(addr, subject, html, null, null, null)) sent++;
        }
        if (sent == 0) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "SMTP delivery failed for every recipient — check SMTP Settings."));
        }
        return ResponseEntity.ok(Map.of("sent", sent, "of", to.size(), "date", d.toString()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

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
