package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.model.Merchant;
import com.acquira.common.repository.MerchantRepository;
import com.acquira.common.service.MerchantInsightService;
import com.acquira.core.service.TenantService;
import com.acquira.pdf.service.PlaywrightPdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Email Manager API ({@code /api/email}) — backs the StatementEmails page
 * (/business/emails).
 *
 * Responsibilities:
 *  - stats / logs: read from the email_queue table (tenant-scoped, per month).
 *  - send-bulk / send: generate the merchant statement PDF(s) and ENQUEUE a row
 *    into email_queue. EmailQueueProcessor then delivers asynchronously via the
 *    tenant's active SMTP config, with retry handling.
 *
 * This controller does NOT send mail directly — all delivery goes through the
 * queue so there is a single, encryption-aware, retryable send path.
 *
 * Note: the PDF batch endpoints under /api/business/insights (PdfController)
 * remain the heavy bulk-generation engine; this controller is the lightweight
 * statement-email front the Email Manager page talks to.
 */
@RestController
@RequestMapping("/api/email")
@RequiredArgsConstructor
@Slf4j
public class EmailController {

    private final JdbcTemplate jdbc;
    private final TenantService tenantService;
    private final MerchantRepository merchantRepository;
    private final MerchantInsightService merchantInsightService;
    private final PlaywrightPdfService playwrightPdfService;

    private Long tenantId() {
        Long t = tenantService.getCurrentTenantId();
        if (t == null) throw new IllegalStateException("No tenant context for the current user");
        return t;
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    /**
     * Delivery counts for a month: { sent, failed, pending, total }.
     * month is "YYYY-MM"; matched against email_queue.statement_month.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(@RequestParam(required = false) String month) {
        Long tid = tenantId();
        Map<String, Object> stats = new LinkedHashMap<>();
        long sent = 0, failed = 0, pending = 0;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM email_queue " +
                "WHERE tenant_id = ? AND (? IS NULL OR statement_month = ?) " +
                "GROUP BY status",
                tid, month, month);
            for (Map<String, Object> r : rows) {
                String st = String.valueOf(r.get("status"));
                long cnt = ((Number) r.get("cnt")).longValue();
                switch (st) {
                    case "SENT" -> sent = cnt;
                    case "FAILED" -> failed = cnt;
                    case "PENDING" -> pending = cnt;
                    default -> { /* ignore unknown */ }
                }
            }
        } catch (Exception e) {
            log.warn("[EMAIL] stats query failed: {}", e.getMessage());
        }
        stats.put("sent", sent);
        stats.put("failed", failed);
        stats.put("pending", pending);
        stats.put("total", sent + failed + pending);
        return ResponseEntity.ok(stats);
    }

    // ── Logs ─────────────────────────────────────────────────────────────────

    /**
     * Paginated email_queue rows for a month, newest first. Shape matches what
     * the StatementEmails page expects: { content: [ {id, merchantId,
     * merchantName, recipientEmail, status, sentAt, errorMessage} ], page, size,
     * total }.
     */
    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getLogs(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long tid = tenantId();
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page, 0) * safeSize;

        List<Map<String, Object>> content = new ArrayList<>();
        long total = 0;
        try {
            total = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_queue WHERE tenant_id = ? AND (? IS NULL OR statement_month = ?)",
                Long.class, tid, month, month)).orElse(0L);

            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, merchant_id, merchant_name, recipient, subject, status, " +
                "retry_count, error_message, created_at, sent_at " +
                "FROM email_queue WHERE tenant_id = ? AND (? IS NULL OR statement_month = ?) " +
                "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                tid, month, month, safeSize, offset);

            for (Map<String, Object> r : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", r.get("id"));
                item.put("merchantId", r.get("merchant_id"));
                item.put("merchantName", r.get("merchant_name"));
                item.put("recipientEmail", r.get("recipient"));
                item.put("subject", r.get("subject"));
                item.put("status", r.get("status"));
                item.put("retryCount", r.get("retry_count"));
                item.put("errorMessage", r.get("error_message"));
                // sentAt drives the "Time" column; fall back to created_at if unsent.
                item.put("sentAt", r.get("sent_at") != null ? r.get("sent_at") : r.get("created_at"));
                content.add(item);
            }
        } catch (Exception e) {
            log.warn("[EMAIL] logs query failed: {}", e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("page", page);
        body.put("size", safeSize);
        body.put("total", total);
        return ResponseEntity.ok(body);
    }

    // ── Send single merchant ─────────────────────────────────────────────────

    /**
     * Generate the statement PDF for one merchant and enqueue it for delivery.
     * Used by the page's per-row "Retry" action.
     */
    @PostMapping("/send/{merchantId}")
    public ResponseEntity<?> sendOne(@PathVariable Long merchantId,
                                     @RequestParam(required = false) String month) {
        Long tid = tenantId();
        YearMonth ym = parseMonth(month);
        try {
            int queued = enqueueForMerchant(tid, merchantId, ym);
            if (queued == 0) {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "SKIPPED",
                    "message", "Merchant has no contact email, or PDF could not be generated"));
            }
            return ResponseEntity.ok(Map.of("status", "QUEUED", "merchantId", merchantId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[EMAIL] send/{} failed: {}", merchantId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Send bulk ────────────────────────────────────────────────────────────

    /**
     * Enqueue statement emails for ALL merchants of the tenant for a month.
     * Runs asynchronously: returns immediately, generation + enqueue happens on
     * a background thread. Progress is visible via the stats/logs endpoints.
     */
    @PostMapping("/send-bulk")
    public ResponseEntity<?> sendBulk(@RequestParam(required = false) String month) {
        Long tid = tenantId();
        YearMonth ym = parseMonth(month);
        List<Merchant> merchants = merchantRepository.findAll();
        log.info("[EMAIL] Bulk enqueue starting — tenant={} month={} merchants={}",
                tid, ym, merchants.size());
        // Hand off to a background thread; the snapshot of ids is taken now.
        // A plain daemon thread is used rather than @Async because sendBulk and
        // the worker live in the same bean — a self-invoked @Async method would
        // bypass the proxy and run synchronously, hanging the HTTP request
        // through thousands of PDF generations. (PdfController's batch path
        // uses raw threads the same way.)
        List<Long> ids = merchants.stream().map(Merchant::getMerchantId).toList();
        Thread worker = new Thread(() -> enqueueBulk(tid, ids, ym), "email-bulk-enqueue");
        worker.setDaemon(true);
        worker.start();
        return ResponseEntity.ok(Map.of(
            "status", "STARTED",
            "month", ym.toString(),
            "totalMerchants", merchants.size(),
            "message", "Statement emails are being generated and queued. Check logs for progress."));
    }

    /**
     * Background worker: generate + enqueue a statement email for each merchant.
     * Runs on a daemon thread spawned by {@link #sendBulk}.
     */
    private void enqueueBulk(Long tenantId, List<Long> merchantIds, YearMonth ym) {
        // Background threads do not inherit TenantContext — set it for any
        // tenant-scoped query inside the loop, and always clear it after.
        TenantContext.setCurrentTenant(tenantId);
        int queued = 0, skipped = 0;
        try {
            for (Long mid : merchantIds) {
                try {
                    if (enqueueForMerchant(tenantId, mid, ym) > 0) queued++;
                    else skipped++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("[EMAIL] Bulk: merchant {} skipped: {}", mid, e.getMessage());
                }
            }
            log.info("[EMAIL] Bulk enqueue complete — tenant={} month={} queued={} skipped={}",
                    tenantId, ym, queued, skipped);
        } finally {
            TenantContext.clear();
        }
    }

    // ── Batch status (compatibility shim) ────────────────────────────────────

    /**
     * The StatementEmails page polls this after a bulk send. This controller
     * enqueues rather than running a PdfController batch job, so there is no
     * batch job id; report aggregate queue status for the month instead.
     */
    @GetMapping("/batch-status/{jobId}")
    public ResponseEntity<Map<String, Object>> batchStatus(@PathVariable String jobId,
                                                           @RequestParam(required = false) String month) {
        Long tid = tenantId();
        long pending = 0;
        try {
            pending = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_queue WHERE tenant_id = ? AND status = 'PENDING' " +
                "AND (? IS NULL OR statement_month = ?)",
                Long.class, tid, month, month)).orElse(0L);
        } catch (Exception e) {
            log.debug("[EMAIL] batch-status query failed: {}", e.getMessage());
        }
        // "RUNNING" while rows are still PENDING, else "COMPLETED".
        String status = pending > 0 ? "RUNNING" : "COMPLETED";
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", status, "pending", pending));
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Generate a merchant's statement PDF for the month and insert an
     * email_queue row. Returns 1 if enqueued, 0 if skipped (no email / no PDF).
     */
    private int enqueueForMerchant(Long tenantId, Long merchantId, YearMonth ym) {
        Merchant merchant = merchantRepository.findById(merchantId).orElse(null);
        if (merchant == null) {
            throw new IllegalArgumentException("Merchant not found: " + merchantId);
        }
        if (!Objects.equals(merchant.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("Merchant " + merchantId + " does not belong to this tenant");
        }
        String email = merchant.getContactEmail();
        String merchantName = merchant.getName() != null ? merchant.getName() : ("Merchant " + merchantId);
        if (email == null || email.isBlank()) {
            log.debug("[EMAIL] Merchant {} ({}) has no contact email — skipped", merchantName, merchantId);
            return 0;
        }

        // Generate the statement PDF and write it to disk; the queue row stores
        // the path and EmailQueueProcessor attaches the file at send time.
        byte[] pdf;
        try {
            MerchantInsightsDTO dto = merchantInsightService.getInsights(
                    merchantId, ym.getYear(), ym.getMonthValue());
            if (dto == null) {
                log.warn("[EMAIL] No insight data for merchant {} ({})", merchantName, merchantId);
                return 0;
            }
            String monthYear = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    + " " + ym.getYear();
            pdf = playwrightPdfService.generatePdf(dto, merchantName, monthYear);
        } catch (Exception e) {
            log.error("[EMAIL] PDF generation failed for merchant {} ({}): {}",
                    merchantName, merchantId, e.getMessage());
            return 0;
        }
        if (pdf == null || pdf.length == 0) return 0;

        Path pdfPath;
        try {
            Path dir = Paths.get("reports", "statement-emails", ym.toString());
            Files.createDirectories(dir);
            String safeName = merchantName.replaceAll("[^a-zA-Z0-9.\\-]", "_");
            pdfPath = dir.resolve("Statement_" + safeName + "_" + ym + ".pdf").toAbsolutePath();
            Files.write(pdfPath, pdf);
        } catch (Exception e) {
            log.error("[EMAIL] Could not write statement PDF for merchant {}: {}", merchantId, e.getMessage());
            return 0;
        }

        String subject = "Your " + ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + ym.getYear() + " Statement";
        String body = "Dear " + merchantName + ",\n\n"
                + "Please find your monthly performance statement attached.\n\n"
                + "Best regards";

        jdbc.update(
            "INSERT INTO email_queue " +
            "(tenant_id, merchant_id, merchant_name, recipient, subject, body, is_html, " +
            " attachment_path, statement_month, status, retry_count, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, FALSE, ?, ?, 'PENDING', 0, NOW())",
            tenantId, merchantId, merchantName, email, subject, body,
            pdfPath.toString(), ym.toString());

        log.info("[EMAIL] Queued statement for merchant {} ({}) to {} for {}",
                merchantName, merchantId, email, ym);
        return 1;
    }

    /** Parse "YYYY-MM"; default to the previous calendar month if absent/invalid. */
    private YearMonth parseMonth(String month) {
        if (month != null && !month.isBlank()) {
            try {
                return YearMonth.parse(month.trim());
            } catch (Exception e) {
                log.debug("[EMAIL] Unparseable month '{}' — defaulting to last month", month);
            }
        }
        return YearMonth.now().minusMonths(1);
    }
}
