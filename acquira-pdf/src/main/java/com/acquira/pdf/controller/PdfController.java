package com.acquira.pdf.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.repository.MerchantRepository;
import com.acquira.pdf.service.PlaywrightPdfService;
import com.acquira.pdf.service.PlaywrightPdfService.BatchJobStatus;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.acquira.common.model.Merchant;
import com.acquira.common.model.Tenant;
import com.acquira.common.repository.TenantRepository;
import com.acquira.common.service.MerchantInsightService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/business/insights")
public class PdfController {

    private static final Logger log = LoggerFactory.getLogger(PdfController.class);

    private final PlaywrightPdfService playwrightPdfService;
    private final MerchantRepository merchantRepository;
    private final TenantRepository tenantRepository;
    private final MerchantInsightService merchantInsightService;
    private final CoreServiceClient coreClient;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** Optional: only available if spring-boot-starter-mail is on classpath */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.mail.javamail.JavaMailSender javaMailSender;

    /** Configurable reports root — defaults to ./reports (relative to CWD).
     *  On RHEL, set pdf.reports.dir=/opt/acquira/reports in application.properties */
    @org.springframework.beans.factory.annotation.Value("${pdf.reports.dir:reports}")
    private String reportsBaseDir;

    /**
     * Absolute, normalized path to reports root.
     * Resolved once at startup so every method uses the same directory
     * regardless of how the JVM CWD changes at runtime.
     */
    private Path reportsRoot;

    @PostConstruct
    void initReportsRoot() {
        reportsRoot = Paths.get(reportsBaseDir).toAbsolutePath().normalize();
        log.info("PDF reports directory resolved to: {}", reportsRoot);
        try {
            Files.createDirectories(reportsRoot);
        } catch (IOException e) {
            log.warn("Could not create reports directory {}: {}", reportsRoot, e.getMessage());
        }
    }

    /** Get the folder for a given month: {reportsRoot}/{bankShortCode}/{YYYY-MM} */
    private Path monthFolder(YearMonth ym) {
        return monthFolder(ym, null);
    }

    /** Tenant-aware folder: {reportsRoot}/{bankShortCode}/{YYYY-MM} */
    private Path monthFolder(YearMonth ym, String bankShortCode) {
        if (bankShortCode != null && !bankShortCode.isBlank()) {
            return reportsRoot.resolve(bankShortCode).resolve(ym.toString());
        }
        // Fallback: resolve from current tenant context
        String code = resolveBankShortCode();
        if (code != null) {
            return reportsRoot.resolve(code).resolve(ym.toString());
        }
        return reportsRoot.resolve(ym.toString());
    }

    /** Resolve bankShortCode from TenantContext */
    private String resolveBankShortCode() {
        try {
            Long tenantId = TenantContext.getCurrentTenant();
            if (tenantId != null) {
                return tenantRepository.findById(tenantId)
                    .map(Tenant::getBankShortCode)
                    .orElse(null);
            }
        } catch (Exception e) {
            log.debug("Could not resolve bankShortCode: {}", e.getMessage());
        }
        return null;
    }

    public PdfController(PlaywrightPdfService playwrightPdfService,
                         MerchantRepository merchantRepository,
                         TenantRepository tenantRepository,
                         MerchantInsightService merchantInsightService,
                         CoreServiceClient coreClient,
                         org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.playwrightPdfService = playwrightPdfService;
        this.merchantRepository = merchantRepository;
        this.tenantRepository = tenantRepository;
        this.merchantInsightService = merchantInsightService;
        this.coreClient = coreClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ─── Single PDF (on-the-fly via Playwright) ────────────────────────

    @GetMapping("/pdf")
    public void downloadPdf(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        if (merchantId == null) merchantId = 1L;
        YearMonth targetMonth = resolveTargetMonth(year, month);

        MerchantInsightsDTO data = coreClient.fetchInsights(merchantId,
                targetMonth.getYear(), targetMonth.getMonthValue());

        String merchantName = resolvemerchantName(merchantId);

        byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchantName,
                targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));

        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"Merchant_Insight_" + targetMonth + ".pdf\"");
        response.setContentLengthLong(pdfBytes.length);
        response.getOutputStream().write(pdfBytes);
        response.getOutputStream().flush();
    }

    // ─── Batch Generation ──────────────────────────────────────────────

    @PostMapping("/generate-all")
    public ResponseEntity<Map<String, Object>> generateAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "false") boolean sendEmail) {
        if (!playwrightPdfService.isEngineReady()) {
            return ResponseEntity.ok(Map.of(
                "status", "PDF_ENGINE_NOT_READY",
                "message", "PDF engine failed to initialize. Playwright browsers may not be installed. "
                    + "Run: mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=install"
            ));
        }
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            Long currentTenant = TenantContext.getCurrentTenant();

            // Resolve tenant for folder structure
            String bankShortCode = resolveBankShortCode();
            Path folder = monthFolder(targetMonth, bankShortCode);
            String monthYear = targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"));

            List<Merchant> merchants = merchantRepository.findAll();
            List<long[]> merchantIds = new ArrayList<>(merchants.size());
            List<String> merchantNames = new ArrayList<>(merchants.size());
            Map<Long, String> merchantEmailMap = new HashMap<>();
            for (var m : merchants) {
                merchantIds.add(new long[]{m.getMerchantId()});
                String name = m.getName() != null ? m.getName() : "Merchant_" + m.getMerchantId();
                merchantNames.add(name);
                if (sendEmail && m.getContactEmail() != null && !m.getContactEmail().isBlank()) {
                    merchantEmailMap.put(m.getMerchantId(), m.getContactEmail());
                }
            }

            // ━━━ BULK PRE-FETCH: 6 queries for ALL merchants ━━━
            log.info("[BATCH] Starting bulk pre-fetch for {} merchants (month: {})", merchants.size(), targetMonth);
            List<Long> midList = merchants.stream().map(Merchant::getMerchantId).collect(Collectors.toList());
            Map<Long, MerchantInsightsDTO> bulkData;
            try {
                if (currentTenant != null) TenantContext.setCurrentTenant(currentTenant);
                bulkData = merchantInsightService.getBulkInsights(
                    midList, targetMonth.getYear(), targetMonth.getMonthValue());
            } finally {
                TenantContext.clear();
            }
            log.info("[BATCH] Bulk pre-fetch complete: {} DTOs ready", bulkData.size());

            // Data fetcher uses pre-computed map (zero DB queries per merchant)
            BatchJobStatus status = playwrightPdfService.generateBatch(
                    merchantIds, merchantNames,
                    (mid, ctx) -> {
                        MerchantInsightsDTO dto = bulkData.get(mid);
                        if (dto != null) return dto;
                        // Fallback to single fetch if bulk missed this merchant
                        if (currentTenant != null) TenantContext.setCurrentTenant(currentTenant);
                        try {
                            return coreClient.fetchInsights(mid, targetMonth.getYear(), targetMonth.getMonthValue());
                        } finally {
                            TenantContext.clear();
                        }
                    },
                    folder.toString(), monthYear, targetMonth.toString());

            // ━━━ POST-BATCH EMAIL SENDING (async) ━━━
            if (sendEmail && !merchantEmailMap.isEmpty()) {
                final String batchJobId = status.jobId;
                final Path emailFolder = folder;
                final String emailMonthYear = monthYear;
                Thread emailThread = new Thread(() -> {
                    // Wait for batch to complete
                    BatchJobStatus jobStatus = playwrightPdfService.getJobStatus(batchJobId);
                    while (jobStatus != null && !"COMPLETED".equals(jobStatus.phase)
                            && !"FAILED".equals(jobStatus.phase) && !"CANCELLED".equals(jobStatus.phase)) {
                        try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                        jobStatus = playwrightPdfService.getJobStatus(batchJobId);
                    }
                    if (jobStatus == null || !"COMPLETED".equals(jobStatus.phase)) {
                        log.warn("[EMAIL] Batch did not complete successfully, skipping email send");
                        return;
                    }
                    log.info("[EMAIL] Batch complete, sending {} emails...", merchantEmailMap.size());
                    int sent = 0, failed = 0;
                    for (var entry : merchantEmailMap.entrySet()) {
                        Long mid = entry.getKey();
                        String email = entry.getValue();
                        String mName = merchants.stream()
                            .filter(m -> m.getMerchantId().equals(mid))
                            .map(Merchant::getName)
                            .findFirst().orElse("Merchant");
                        String safeName = mName.replaceAll("[^a-zA-Z0-9.\\-]", "_");
                        Path pdfFile = emailFolder.resolve("Insight_" + safeName + "_" + targetMonth + ".pdf");
                        if (!Files.exists(pdfFile)) {
                            log.warn("[EMAIL] PDF not found for {}: {}", mName, pdfFile);
                            failed++;
                            continue;
                        }
                        try {
                            sendReportEmail(email, mName, emailMonthYear, pdfFile);
                            sent++;
                        } catch (Exception e) {
                            log.error("[EMAIL] Failed to send to {} ({}): {}", mName, email, e.getMessage());
                            failed++;
                        }
                    }
                    log.info("[EMAIL] Email sending complete: {} sent, {} failed", sent, failed);
                }, "pdf-email-sender");
                emailThread.setDaemon(true);
                emailThread.start();
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", status.jobId);
            response.put("totalMerchants", merchants.size());
            response.put("bulkPreFetched", bulkData.size());
            response.put("targetFolder", folder.toString());
            response.put("targetMonth", targetMonth.toString());
            response.put("sendEmail", sendEmail);
            response.put("emailRecipients", merchantEmailMap.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Send insight report PDF to merchant via email.
     * Uses reflection to call EmailService if spring-boot-starter-mail is on classpath.
     * Falls back to console logging if not configured.
     */
    private void sendReportEmail(String toEmail, String merchantName, String monthYear, Path pdfFile) {
        try {
            if (javaMailSender == null) {
                // Fallback: use JdbcTemplate to log email to a table for external processing
                try {
                    jdbcTemplate.update(
                        "INSERT INTO email_queue (recipient, subject, body, attachment_path, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 'PENDING', NOW())",
                        toEmail,
                        "Your Business Insight Report — " + monthYear,
                        "Dear " + merchantName + ",\n\nPlease find your monthly business insight report attached.\n\nBest regards,\nAFS NEXUS",
                        pdfFile.toString()
                    );
                    log.info("[EMAIL] Queued email for {} to {}", merchantName, toEmail);
                } catch (Exception e) {
                    // If email_queue table doesn't exist, just log
                    log.info("[EMAIL] Would send to {} ({}): Report for {} — PDF: {}",
                        merchantName, toEmail, monthYear, pdfFile.getFileName());
                }
                return;
            }

            // JavaMailSender is available — send directly with PDF attachment
            var message = javaMailSender.createMimeMessage();
            var helper = new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(toEmail);
            helper.setSubject("Your Business Insight Report — " + monthYear);
            helper.setText(
                "<div style='font-family:Arial,sans-serif;max-width:600px;margin:0 auto'>"
                + "<h2 style='color:#0F2042'>AFS NEXUS — Monthly Business Insight</h2>"
                + "<p>Dear " + merchantName + ",</p>"
                + "<p>Please find your <strong>" + monthYear + "</strong> business insight report attached to this email.</p>"
                + "<p>This report includes your sales performance, customer analytics, payment insights, and actionable recommendations.</p>"
                + "<p style='color:#6B7280;font-size:12px;margin-top:30px'>This is an automated report from AFS NEXUS. Do not reply to this email.</p>"
                + "</div>", true);
            helper.addAttachment(pdfFile.getFileName().toString(),
                new org.springframework.core.io.FileSystemResource(pdfFile.toFile()));
            javaMailSender.send(message);
            log.info("[EMAIL] Sent report to {} ({})", merchantName, toEmail);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send to {}: {}", toEmail, e.getMessage());
        }
    }

    // ─── Batch Monitoring ──────────────────────────────────────────────

    @GetMapping("/batch-status/{jobId}")
    public ResponseEntity<Map<String, Object>> getBatchStatus(@PathVariable String jobId) {
        BatchJobStatus status = playwrightPdfService.getJobStatus(jobId);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(status.toMap());
    }

    @GetMapping("/batch-jobs")
    public ResponseEntity<List<Map<String, Object>>> listBatchJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        playwrightPdfService.getActiveJobs().values().forEach(s -> jobs.add(s.toMap()));
        return ResponseEntity.ok(jobs);
    }

    @PostMapping("/batch-cancel/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelBatch(@PathVariable String jobId) {
        boolean cancelled = playwrightPdfService.cancelJob(jobId);
        return ResponseEntity.ok(Map.of("jobId", jobId, "cancelled", cancelled));
    }

    @GetMapping("/engine-stats")
    public ResponseEntity<Map<String, Object>> getEngineStats() {
        return ResponseEntity.ok(playwrightPdfService.getEngineStats());
    }

    // ─── Report Status & Listing ───────────────────────────────────────

    @GetMapping("/check-status")
    public ResponseEntity<Map<String, Object>> checkReportStatus(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        Map<String, Object> response = new HashMap<>();
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            Path folderPath = resolveReportFolder(targetMonth);
            int count = 0;
            if (Files.exists(folderPath)) {
                try (Stream<Path> files = Files.list(folderPath)) {
                    count = (int) files.filter(p -> p.toString().endsWith(".pdf")).count();
                }
            }
            response.put("exists", count > 0);
            response.put("count", count);
            response.put("targetMonth", targetMonth.toString());
            response.put("resolvedPath", folderPath.toString());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/list-reports")
    public ResponseEntity<Map<String, Object>> listReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            Path folderPath = resolveReportFolder(targetMonth);
            List<Map<String, Object>> reports = new ArrayList<>();

            if (Files.exists(folderPath)) {
                try (Stream<Path> files = Files.list(folderPath)) {
                    files.filter(p -> p.toString().endsWith(".pdf"))
                         .sorted()
                         .forEach(p -> {
                             Map<String, Object> entry = new LinkedHashMap<>();
                             entry.put("filename", p.getFileName().toString());
                             try {
                                 entry.put("size", Files.size(p));
                                 entry.put("createdAt", Files.getLastModifiedTime(p).toString());
                             } catch (IOException ignored) {
                                 entry.put("size", 0);
                             }
                             entry.put("downloadUrl", "/api/business/insights/download-report?file="
                                 + p.getFileName().toString()
                                 + "&year=" + targetMonth.getYear()
                                 + "&month=" + targetMonth.getMonthValue());
                             reports.add(entry);
                         });
                }
            }
            return ResponseEntity.ok(Map.of(
                "targetMonth", targetMonth.toString(),
                "count", reports.size(),
                "reports", reports,
                "resolvedPath", folderPath.toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Download Pre-generated Reports ────────────────────────────────

    @GetMapping("/download-report")
    public void downloadReport(
            @RequestParam String file,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        YearMonth targetMonth = resolveTargetMonth(year, month);

        // Security: prevent path traversal — extract just the filename
        String safeName = Paths.get(file).getFileName().toString();
        if (!safeName.endsWith(".pdf")) {
            response.sendError(400, "Invalid file type");
            return;
        }

        // Try tenant folder first, then legacy flat folder
        Path filePath = resolveReportFile(safeName, targetMonth);

        // Log resolved path for debugging
        log.info("Download request: file={}, resolved={}, exists={}", safeName, filePath, filePath != null && Files.exists(filePath));

        if (filePath == null || !Files.exists(filePath)) {
            Path folder = monthFolder(targetMonth);
            boolean folderExists = Files.exists(folder);
            long pdfCount = 0;
            if (folderExists) {
                try (Stream<Path> fs = Files.list(folder)) {
                    pdfCount = fs.filter(p -> p.toString().endsWith(".pdf")).count();
                }
            }
            log.warn("Download 404: {} | folder={} exists={} pdfCount={}",
                    safeName, folder, folderExists, pdfCount);
            response.sendError(404, "Report not found: " + safeName
                    + " (folder: " + folder + ", exists: " + folderExists
                    + ", pdfs in folder: " + pdfCount + ")");
            return;
        }

        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        response.setContentLengthLong(Files.size(filePath));
        Files.copy(filePath, response.getOutputStream());
        response.getOutputStream().flush();
    }

    @GetMapping("/download-all-reports")
    public void downloadAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        YearMonth targetMonth = resolveTargetMonth(year, month);
        Path folderPath = resolveReportFolder(targetMonth);

        if (!Files.exists(folderPath)) {
            response.sendError(404, "No reports found for " + targetMonth);
            return;
        }

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
            "attachment; filename=\"Merchant_Reports_" + targetMonth + ".zip\"");

        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(response.getOutputStream());
             Stream<Path> files = Files.list(folderPath)) {
            files.filter(p -> p.toString().endsWith(".pdf")).forEach(p -> {
                try {
                    zos.putNextEntry(new java.util.zip.ZipEntry(p.getFileName().toString()));
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // ─── Overview endpoint (data only, no PDF) ─────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<MerchantInsightsDTO> getInsights(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        if (merchantId == null) merchantId = 1L;
        YearMonth targetMonth = resolveTargetMonth(year, month);
        return ResponseEntity.ok(
                coreClient.fetchInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue()));
    }

    // ─── Generate single merchant report to disk ───────────────────────

    @PostMapping("/generate/{merchantId}")
    public ResponseEntity<Map<String, Object>> generateReport(
            @PathVariable Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        if (!playwrightPdfService.isEngineReady()) {
            return ResponseEntity.ok(Map.of("status", "PDF_ENGINE_NOT_READY"));
        }
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            Path folder = monthFolder(targetMonth);
            Files.createDirectories(folder);

            MerchantInsightsDTO data = coreClient.fetchInsights(merchantId,
                    targetMonth.getYear(), targetMonth.getMonthValue());

            String merchantName = resolvemerchantName(merchantId);

            byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchantName,
                    targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));

            String safeName = merchantName.replaceAll("[^a-zA-Z0-9.\\-]", "_");
            String filename = "Insight_" + safeName + "_" + targetMonth + ".pdf";
            Path outPath = folder.resolve(filename);
            Files.write(outPath, pdfBytes);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "filename", filename,
                    "size", pdfBytes.length,
                    "path", outPath.toString(),
                    "downloadUrl", "/api/business/insights/download-report?file="
                            + filename + "&year=" + targetMonth.getYear()
                            + "&month=" + targetMonth.getMonthValue()
            ));
        } catch (Exception e) {
            log.error("Failed to generate report for merchant {}", merchantId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Resolve the report file path — tries tenant folder first, then legacy flat folder.
     * This ensures backward compatibility with reports generated before tenant folders.
     */
    private Path resolveReportFile(String filename, YearMonth targetMonth) {
        // 1. Try tenant-aware path: reports/{bankShortCode}/{YYYY-MM}/file.pdf
        Path tenantPath = monthFolder(targetMonth).resolve(filename);
        if (Files.exists(tenantPath)) return tenantPath;

        // 2. Try legacy flat path: reports/{YYYY-MM}/file.pdf
        Path legacyPath = reportsRoot.resolve(targetMonth.toString()).resolve(filename);
        if (Files.exists(legacyPath)) return legacyPath;

        return tenantPath; // return the expected path (for error reporting)
    }

    /**
     * Resolve the report folder — tries tenant folder first, falls back to legacy.
     * Returns the folder that actually exists and contains PDFs.
     */
    private Path resolveReportFolder(YearMonth targetMonth) {
        Path tenantFolder = monthFolder(targetMonth);
        if (Files.exists(tenantFolder)) return tenantFolder;

        Path legacyFolder = reportsRoot.resolve(targetMonth.toString());
        if (Files.exists(legacyFolder)) return legacyFolder;

        return tenantFolder; // return the expected path
    }

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }

    /**
     * Resolve merchant name with multiple fallbacks:
     * 1. dim_merchant.name (preferred — clean business name)
     * 2. Look up merchant_name from stg_trnx_raw (raw file data)
     * 3. Look up merchant_name from fact_transaction
     * 4. dim_merchant.mid (numeric ID — last resort before generic)
     * 5. "Merchant {id}" as absolute fallback
     *
     * If a name is found via fallback, it's persisted back to dim_merchant.name
     * so subsequent lookups are instant.
     */
    private String resolvemerchantName(Long merchantId) {
        String mid = null;
        try {
            var mOpt = merchantRepository.findById(merchantId);
            if (mOpt.isPresent()) {
                var m = mOpt.get();
                mid = m.getMid();
                if (m.getName() != null && !m.getName().isBlank()) {
                    log.debug("Merchant {} name from dim_merchant: {}", merchantId, m.getName());
                    return m.getName();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to lookup merchant {} from dim_merchant: {}", merchantId, e.getMessage());
        }

        // Fallback 1: stg_trnx_raw directly by MID (simplest, most reliable)
        String resolvedName = null;
        if (mid != null) {
            resolvedName = tryQueryMerchantName(
                    "SELECT merchant_name FROM stg_trnx_raw " +
                    "WHERE mid = ? AND merchant_name IS NOT NULL " +
                    "AND TRIM(merchant_name) <> '' LIMIT 1",
                    mid, "stg_trnx_raw (direct mid)");
            if (resolvedName != null) { persistMerchantName(merchantId, resolvedName); return resolvedName; }
        }

        // Fallback 2: stg_trnx_raw.merchant_name via dim_merchant join
        resolvedName = tryQueryMerchantName(
                "SELECT s.merchant_name FROM stg_trnx_raw s " +
                "JOIN dim_merchant m ON s.mid = m.mid " +
                "WHERE m.merchant_id = ? AND s.merchant_name IS NOT NULL " +
                "AND TRIM(s.merchant_name) <> '' LIMIT 1",
                merchantId, "stg_trnx_raw (join)");
        if (resolvedName != null) { persistMerchantName(merchantId, resolvedName); return resolvedName; }

        // Fallback 3: stg_merchant_master_raw (merchant master file)
        if (mid != null) {
            resolvedName = tryQueryMerchantName(
                    "SELECT merchant_name FROM stg_merchant_master_raw " +
                    "WHERE mid = ? AND merchant_name IS NOT NULL " +
                    "AND TRIM(merchant_name) <> '' LIMIT 1",
                    mid, "stg_merchant_master_raw");
            if (resolvedName != null) { persistMerchantName(merchantId, resolvedName); return resolvedName; }
        }

        // Fallback 4: Use MID as name (better than generic)
        if (mid != null && !mid.isBlank()) {
            log.warn("No merchant name found for merchantId={}, using MID: {}", merchantId, mid);
            return mid;
        }

        log.warn("No merchant name found for merchantId={}, using generic fallback", merchantId);
        return "Merchant " + merchantId;
    }

    /** Safe query that returns null on any error (missing column, empty result, etc.) */
    private String tryQueryMerchantName(String sql, Object param, String source) {
        try {
            String name = jdbcTemplate.queryForObject(sql, String.class, param);
            if (name != null && !name.isBlank()) {
                log.info("Resolved merchant name from {}: '{}'", source, name.trim());
                return name.trim();
            }
        } catch (Exception e) {
            log.debug("No merchant_name from {} for param {}: {}", source, param, e.getMessage());
        }
        return null;
    }

    /** Persist resolved name back to dim_merchant for future lookups */
    private void persistMerchantName(Long merchantId, String name) {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE dim_merchant SET name = ? WHERE merchant_id = ? AND (name IS NULL OR TRIM(name) = '')",
                    name, merchantId);
            if (updated > 0) log.info("Persisted merchant name '{}' to dim_merchant for id={}", name, merchantId);
        } catch (Exception e) {
            log.debug("Could not persist merchant name: {}", e.getMessage());
        }
    }
}
