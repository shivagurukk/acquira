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
import org.springframework.web.multipart.MultipartFile;

import com.acquira.common.model.Merchant;
import com.acquira.common.model.Tenant;
import com.acquira.common.repository.TenantRepository;
import com.acquira.common.service.MerchantInsightService;
import com.acquira.common.service.ReportStorageService;
import com.acquira.common.service.S3Uploader;

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
    private final ReportStorageService reportStorageService;

    /** Optional: only available if spring-boot-starter-mail is on classpath */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.mail.javamail.JavaMailSender javaMailSender;

    /**
     * S3 upload service — injected via S3Uploader interface (acquira-common).
     * Implementation (ReportS3UploadService) lives in acquira-core.
     * @Autowired(required=false) keeps pdf module compilable standalone.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private S3Uploader reportS3UploadService;

    @org.springframework.beans.factory.annotation.Value("${pdf.reports.dir:reports}")
    private String reportsBaseDir;

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

    private Path monthFolder(YearMonth ym) {
        return monthFolder(ym, null);
    }

    private Path monthFolder(YearMonth ym, String bankShortCode) {
        if (bankShortCode != null && !bankShortCode.isBlank()) {
            return reportsRoot.resolve(bankShortCode).resolve(ym.toString());
        }
        String code = resolveBankShortCode();
        if (code != null) {
            return reportsRoot.resolve(code).resolve(ym.toString());
        }
        return reportsRoot.resolve(ym.toString());
    }

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
                         org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                         ReportStorageService reportStorageService) {
        this.playwrightPdfService   = playwrightPdfService;
        this.merchantRepository     = merchantRepository;
        this.tenantRepository       = tenantRepository;
        this.merchantInsightService = merchantInsightService;
        this.coreClient             = coreClient;
        this.jdbcTemplate           = jdbcTemplate;
        this.reportStorageService   = reportStorageService;
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
    //
    //  sendEmail | sendS3 | Behaviour
    //  ----------|--------|--------------------------------------------------
    //  false     | false  | Generate PDFs to local disk only
    //  false     | true   | Generate PDFs → upload ALL to S3 after batch done
    //  true      | false  | Generate PDFs → send email only, no S3
    //  true      | true   | Generate PDFs → send email → upload to S3 per PDF
    //                       (S3 upload only happens after successful email)
    //
    //  Merchant selection:
    //    - If `merchantIds` is NOT provided (or empty) → runs for ALL merchants
    //    - If `merchantIds` IS provided → runs ONLY for those merchant IDs
    //      (missing IDs are logged and skipped; batch proceeds with valid ones)
    //
    //  Examples:
    //    /generate-all?year=2026&month=3                                → all, local
    //    /generate-all?year=2026&month=3&sendEmail=true&sendS3=true     → all, email+S3
    //    /generate-all?year=2026&month=3&merchantIds=1,5,12             → 3 merchants, local
    //    /generate-all?year=2026&month=3&merchantIds=1,5&sendEmail=true → 2 merchants, email only
    // ─────────────────────────────────────────────────────────────────────

    @PostMapping("/generate-all")
    public ResponseEntity<Map<String, Object>> generateAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "false") boolean sendEmail,
            @RequestParam(defaultValue = "false") boolean sendS3,
            @RequestParam(required = false) List<Long> merchantIds) {

        if (!playwrightPdfService.isEngineReady()) {
            return ResponseEntity.ok(Map.of(
                "status", "PDF_ENGINE_NOT_READY",
                "message", "PDF engine failed to initialize. Playwright browsers may not be installed. "
                    + "Run: mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=install"
            ));
        }

        // S3 requires the uploader service to be available
        final boolean s3Requested = sendS3 && reportS3UploadService != null;
        if (sendS3 && reportS3UploadService == null) {
            log.warn("[BATCH] S3 upload requested but S3Uploader service is not available — S3 will be skipped");
        }

        // Detect selective mode
        final boolean selective = merchantIds != null && !merchantIds.isEmpty();

        try {
            YearMonth targetMonth  = resolveTargetMonth(year, month);
            Long currentTenant     = TenantContext.getCurrentTenant();
            String bankShortCode   = resolveBankShortCode();
            Path   folder          = monthFolder(targetMonth, bankShortCode);
            String monthYear       = targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"));

            // ── Resolve the merchant list based on selective vs all ──
            List<Merchant> merchants;
            List<Long> missingIds = Collections.emptyList();

            if (selective) {
                // Deduplicate requested IDs, preserve caller order for logs
                List<Long> uniqueRequested = merchantIds.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());

                merchants = merchantRepository.findAllById(uniqueRequested);

                // Tenant guard: a caller must never resolve another tenant's merchant by ID.
                if (currentTenant != null) {
                    merchants = merchants.stream()
                            .filter(m -> currentTenant.equals(m.getTenantId()))
                            .collect(Collectors.toList());
                }

                Set<Long> foundIds = merchants.stream()
                        .map(Merchant::getMerchantId)
                        .collect(Collectors.toSet());
                missingIds = uniqueRequested.stream()
                        .filter(id -> !foundIds.contains(id))
                        .collect(Collectors.toList());

                if (!missingIds.isEmpty()) {
                    log.warn("[BATCH] Requested merchant IDs not found (will be skipped): {}", missingIds);
                }

                if (merchants.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "status",  "NO_VALID_MERCHANTS",
                        "message", "None of the requested merchantIds were found.",
                        "requestedIds", uniqueRequested,
                        "missingIds",   missingIds
                    ));
                }

                log.info("[BATCH] Selective mode — {} of {} requested merchants resolved",
                        merchants.size(), uniqueRequested.size());
            } else {
                // Tenant-scoped ALL + PDF generate-flag filter. Only merchants whose
                // generate_report_flag = 1 are loaded (filtered in the DB, so flagged-off
                // merchants never reach memory). Set a merchant's flag to 0 to exclude it.
                merchants = (currentTenant != null)
                        ? merchantRepository.findByTenantIdAndGenerateReportFlag(currentTenant, 1)
                        : merchantRepository.findAll();
                log.info("[BATCH] Full mode — running for {} merchants with generate_report_flag=1 (tenant={})",
                        merchants.size(), currentTenant);
            }

            List<long[]>   batchMerchantIds = new ArrayList<>(merchants.size());
            List<String>   merchantNames    = new ArrayList<>(merchants.size());
            Map<Long, String> merchantEmailMap = new HashMap<>();

            for (Merchant m : merchants) {
                batchMerchantIds.add(new long[]{m.getMerchantId()});
                String name = m.getName() != null ? m.getName() : "Merchant_" + m.getMerchantId();
                merchantNames.add(name);
                if (sendEmail && m.getContactEmail() != null && !m.getContactEmail().isBlank()) {
                    merchantEmailMap.put(m.getMerchantId(), m.getContactEmail());
                }
            }

            log.info("[BATCH] Starting bulk pre-fetch for {} merchants (month:{} email:{} s3:{} selective:{})",
                merchants.size(), targetMonth, sendEmail, s3Requested, selective);

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

            final Long capturedTenant    = currentTenant;
            final String capturedBankCode = bankShortCode;
            final String capturedYearMonth = targetMonth.toString();
            final List<Merchant> capturedMerchants = merchants; // snapshot for post-batch thread

            BatchJobStatus status = playwrightPdfService.generateBatch(
                    batchMerchantIds, merchantNames,
                    (mid, ctx) -> {
                        MerchantInsightsDTO dto = bulkData.get(mid);
                        if (dto != null) return dto;
                        try {
                            if (capturedTenant != null) TenantContext.setCurrentTenant(capturedTenant);
                            return coreClient.fetchInsights(mid, targetMonth.getYear(), targetMonth.getMonthValue());
                        } finally {
                            TenantContext.clear();
                        }
                    },
                    folder.toString(), monthYear, capturedYearMonth);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            //  POST-BATCH ASYNC THREAD
            //
            //  Case 1: sendEmail=false, sendS3=true
            //    → Wait for batch → upload ALL PDFs to S3 directly
            //
            //  Case 2: sendEmail=true, sendS3=true
            //    → Wait for batch → for each merchant:
            //        send email → if email OK → upload that PDF to S3
            //
            //  Case 3: sendEmail=true, sendS3=false
            //    → Wait for batch → send emails only
            //
            //  Case 4: sendEmail=false, sendS3=false
            //    → No post-batch thread needed — PDFs already on local disk
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            final boolean needsPostThread = sendEmail || s3Requested;

            if (needsPostThread) {
                final String  batchJobId    = status.jobId;
                final Path    batchFolder   = folder;
                final String  batchMonthYear = monthYear;
                final Long    threadTenantId = currentTenant;

                Thread postBatchThread = new Thread(() -> {
                    if (threadTenantId != null) TenantContext.setCurrentTenant(threadTenantId);
                    try {
                        // ── Wait for PDF generation to fully complete ──
                        BatchJobStatus jobStatus = playwrightPdfService.getJobStatus(batchJobId);
                        while (jobStatus != null
                                && !"COMPLETED".equals(jobStatus.phase)
                                && !"FAILED".equals(jobStatus.phase)
                                && !"CANCELLED".equals(jobStatus.phase)) {
                            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                            jobStatus = playwrightPdfService.getJobStatus(batchJobId);
                        }

                        if (jobStatus == null || !"COMPLETED".equals(jobStatus.phase)) {
                            log.warn("[POST-BATCH] Batch did not complete (phase={}) — skipping email/S3",
                                jobStatus != null ? jobStatus.phase : "null");
                            return;
                        }

                        log.info("[POST-BATCH] Batch complete. sendEmail={} s3={}", sendEmail, s3Requested);

                        // ── CASE 1: S3 only (no email) ──────────────────────────────
                        if (!sendEmail && s3Requested) {
                            log.info("[S3-ONLY] Uploading {} PDFs to S3...", capturedMerchants.size());
                            int s3Ok = 0, s3Fail = 0;

                            for (Merchant m : capturedMerchants) {
                                String mName    = m.getName() != null ? m.getName() : "Merchant_" + m.getMerchantId();
                                String safeName = mName.replaceAll("[^a-zA-Z0-9.\\-]", "_");
                                Path   pdfFile  = batchFolder.resolve("Insight_" + safeName + "_" + capturedYearMonth + ".pdf");

                                if (!Files.exists(pdfFile)) {
                                    log.warn("[S3-ONLY] PDF not found, skipping: {}", pdfFile.getFileName());
                                    s3Fail++;
                                    continue;
                                }

                                try {
                                    boolean ok = reportS3UploadService.uploadIfEnabled(
                                        threadTenantId, pdfFile, capturedBankCode, capturedYearMonth);
                                    if (ok) {
                                        s3Ok++;
                                        log.info("[S3-ONLY] Uploaded: {}", pdfFile.getFileName());
                                    } else {
                                        s3Fail++;
                                        log.warn("[S3-ONLY] Upload skipped/failed: {}", pdfFile.getFileName());
                                    }
                                } catch (Exception e) {
                                    s3Fail++;
                                    log.error("[S3-ONLY] Upload error for {}: {}", pdfFile.getFileName(), e.getMessage());
                                }
                            }

                            log.info("[S3-ONLY] Done — uploaded:{} failed:{}", s3Ok, s3Fail);
                            return;
                        }

                        // ── CASE 2 & 3: Email (with optional S3) ────────────────────
                        if (sendEmail) {
                            log.info("[EMAIL] Sending {} emails (s3={})", merchantEmailMap.size(), s3Requested);
                            int emailSent = 0, emailFailed = 0, s3Ok = 0, s3Skipped = 0, s3Fail = 0;

                            for (Map.Entry<Long, String> entry : merchantEmailMap.entrySet()) {
                                Long   mid   = entry.getKey();
                                String email = entry.getValue();
                                String mName = capturedMerchants.stream()
                                    .filter(m -> m.getMerchantId().equals(mid))
                                    .map(Merchant::getName)
                                    .findFirst().orElse("Merchant");

                                String safeName = mName.replaceAll("[^a-zA-Z0-9.\\-]", "_");
                                Path   pdfFile  = batchFolder.resolve("Insight_" + safeName + "_" + capturedYearMonth + ".pdf");

                                if (!Files.exists(pdfFile)) {
                                    log.warn("[EMAIL] PDF not found for {}: {}", mName, pdfFile);
                                    emailFailed++;
                                    continue;
                                }

                                // Send email first
                                boolean emailOk = false;
                                try {
                                    sendReportEmail(email, mName, batchMonthYear, pdfFile);
                                    emailSent++;
                                    emailOk = true;
                                    log.info("[EMAIL] Sent to {} ({})", mName, email);
                                } catch (Exception e) {
                                    log.error("[EMAIL] Failed to send to {} ({}): {}", mName, email, e.getMessage());
                                    emailFailed++;
                                }

                                // Upload to S3 only if email was sent AND s3 is enabled (Case 2)
                                // If email failed, skip S3 for this merchant (Case 3 n/a, Case 2 guard)
                                if (s3Requested) {
                                    if (emailOk) {
                                        // Case 2: email=true, s3=true → upload after successful email
                                        try {
                                            boolean uploaded = reportS3UploadService.uploadIfEnabled(
                                                threadTenantId, pdfFile, capturedBankCode, capturedYearMonth);
                                            if (uploaded) {
                                                s3Ok++;
                                                log.info("[S3] Uploaded after email: {}", pdfFile.getFileName());
                                            } else {
                                                s3Skipped++;
                                            }
                                        } catch (Exception e) {
                                            s3Fail++;
                                            log.error("[S3] Upload failed for {}: {}", pdfFile.getFileName(), e.getMessage());
                                        }
                                    } else {
                                        // Email failed → skip S3 for this merchant
                                        s3Skipped++;
                                        log.debug("[S3] Skipping S3 for {} — email failed", mName);
                                    }
                                }
                                // Case 3: email=true, s3=false → nothing more to do after email
                            }

                            log.info("[POST-BATCH] Done — email sent:{} failed:{} | S3 ok:{} skipped:{} failed:{}",
                                emailSent, emailFailed, s3Ok, s3Skipped, s3Fail);
                        }

                    } finally {
                        TenantContext.clear();
                    }
                }, "pdf-post-batch");
                postBatchThread.setDaemon(true);
                postBatchThread.start();
            } else {
                // Case 4: email=false, s3=false — PDFs written to disk, nothing else to do
                log.info("[BATCH] Local-only mode — PDFs will be saved to: {}", folder);
            }

            logBatchRun(status.jobId, currentTenant, targetMonth.toString(), merchants.size(), "STARTED");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId",           status.jobId);
            response.put("totalMerchants",  merchants.size());
            response.put("bulkPreFetched",  bulkData.size());
            response.put("targetFolder",    folder.toString());
            response.put("targetMonth",     targetMonth.toString());
            response.put("sendEmail",       sendEmail);
            response.put("sendS3",          s3Requested);
            response.put("emailRecipients", merchantEmailMap.size());
            response.put("mode",            resolveMode(sendEmail, s3Requested, selective));
            response.put("selective",       selective);
            if (selective) {
                response.put("processedMerchantIds", midList);
                response.put("missingMerchantIds",   missingIds);
            }
            response.put("storageType",     reportStorageService.getStorageInfo());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[BATCH] Unexpected error", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Human-readable description of the current batch mode.
     * Returned in the API response so the UI can display it clearly.
     * Appends "_SELECTIVE" when only a subset of merchants was requested.
     */
    private String resolveMode(boolean sendEmail, boolean sendS3, boolean selective) {
        String base;
        if (!sendEmail && !sendS3)      base = "LOCAL_ONLY";
        else if (!sendEmail && sendS3)  base = "S3_ONLY";
        else if ( sendEmail && !sendS3) base = "EMAIL_ONLY";
        else                            base = "EMAIL_AND_S3";
        return selective ? base + "_SELECTIVE" : base;
    }

    // ─── Email helper ──────────────────────────────────────────────────

    private void sendReportEmail(String toEmail, String merchantName, String monthYear, Path pdfFile) {
        // Enqueue into email_queue rather than sending inline. EmailQueueProcessor
        // (polls every 60s) picks the row up, builds the sender from the tenant's
        // active email_smtp_config (AES-decrypted password) and delivers it with
        // retry handling. This keeps ALL outbound mail on one encryption-aware,
        // per-tenant path and means a slow SMTP server can't stall PDF batches.
        //
        // tenant_id is tagged from TenantContext so the processor resolves THIS
        // tenant's SMTP config (not just "any active config"). is_html=false
        // because the body below is plain text.
        Long tenantId = null;
        try {
            tenantId = TenantContext.getCurrentTenant();
        } catch (Exception ignored) { /* no tenant context - leave null */ }

        String subject = "Your Business Insight Report — " + monthYear;
        String body = "Dear " + merchantName + ",\n\n"
                + "Please find your monthly business insight report attached.\n\n"
                + "Best regards,\nAFS NEXUS";
        try {
            jdbcTemplate.update(
                "INSERT INTO email_queue " +
                "(tenant_id, recipient, subject, body, is_html, attachment_path, status, retry_count, created_at) " +
                "VALUES (?, ?, ?, ?, FALSE, ?, 'PENDING', 0, NOW())",
                tenantId, toEmail, subject, body, pdfFile.toString());
            log.info("[EMAIL] Queued report for {} to {} (tenant={})", merchantName, toEmail, tenantId);
        } catch (Exception e) {
            // Re-thrown so the post-batch loop records this merchant as failed.
            throw new RuntimeException("Failed to queue email for " + merchantName + ": " + e.getMessage(), e);
        }
    }

    // ─── Generate by MID — one / all / file (tenant-scoped) ──────────────
    //
    // One entry point for the three ways a user picks WHO to generate for, always
    // scoped to the caller's tenant:
    //   • ALL  → omit mid/mids/file (or scope=ALL): every merchant in the tenant
    //   • ONE  → mid=<bank MID>
    //   • LIST → mids=<MID>,<MID>,...        (repeatable or comma-separated)
    //   • FILE → multipart 'file' (CSV/TXT: one MID per line, or a column headed "MID")
    //
    // MIDs are the bank-assigned codes (dim_merchant.mid), NOT internal IDs. Unmatched
    // MIDs are reported back; matched ones go through the same batch pipeline as
    // /generate-all (identical email/S3 behaviour).
    //
    //   POST /generate-by-mid   (multipart/form-data)
    //   params: year, month, sendEmail, sendS3, scope, mid, mids, file
    // ─────────────────────────────────────────────
    @PostMapping(value = "/generate-by-mid", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> generateByMid(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "false") boolean sendEmail,
            @RequestParam(defaultValue = "false") boolean sendS3,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String mid,
            @RequestParam(required = false) List<String> mids,
            @RequestParam(required = false) MultipartFile file) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            return ResponseEntity.status(403).body(Map.of(
                "status", "NO_TENANT", "message", "Tenant context is required."));
        }

        boolean wantsAll = "ALL".equalsIgnoreCase(scope)
                || ((scope == null || scope.isBlank())
                    && (mid == null || mid.isBlank())
                    && (mids == null || mids.isEmpty())
                    && (file == null || file.isEmpty()));

        if (wantsAll) {
            // Delegate to the batch path; its ALL branch is tenant-scoped.
            return generateAllReports(year, month, sendEmail, sendS3, null);
        }

        // Gather requested MID strings from single + list + file
        LinkedHashSet<String> midSet = new LinkedHashSet<>();
        if (mid != null && !mid.isBlank()) midSet.add(mid.trim());
        if (mids != null) {
            for (String s : mids) if (s != null && !s.isBlank()) midSet.add(s.trim());
        }
        if (file != null && !file.isEmpty()) {
            try {
                midSet.addAll(parseMidsFromFile(file));
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "BAD_FILE", "message", ex.getMessage()));
            } catch (Exception ex) {
                log.warn("[BATCH][by-mid] Failed to parse MID file: {}", ex.getMessage());
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "BAD_FILE", "message", "Could not read the uploaded MID file."));
            }
        }

        if (midSet.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "NO_MIDS",
                "message", "No MIDs supplied. Provide 'mid', 'mids', a 'file', or scope=ALL."));
        }

        // Resolve MID -> merchant WITHIN this tenant
        List<String> requestedMids = new ArrayList<>(midSet);
        List<Merchant> matched = merchantRepository.findByTenantIdAndMidIn(tenantId, requestedMids);

        Set<String> foundMids = matched.stream()
                .map(Merchant::getMid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> unmatchedMids = requestedMids.stream()
                .filter(x -> !foundMids.contains(x))
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "NO_VALID_MIDS",
                "message", "None of the supplied MIDs were found for this tenant.",
                "requestedMids", requestedMids,
                "unmatchedMids", unmatchedMids));
        }
        if (!unmatchedMids.isEmpty()) {
            log.warn("[BATCH][by-mid] {} of {} MIDs matched for tenant {}; unmatched: {}",
                matched.size(), requestedMids.size(), tenantId, unmatchedMids);
        }

        List<Long> resolvedIds = matched.stream()
                .map(Merchant::getMerchantId)
                .collect(Collectors.toList());

        // Hand resolved internal IDs to the existing batch pipeline (selective mode,
        // also tenant-guarded), then enrich the response with MID resolution info.
        ResponseEntity<Map<String, Object>> resp =
                generateAllReports(year, month, sendEmail, sendS3, resolvedIds);

        Map<String, Object> body = (resp.getBody() != null)
                ? new HashMap<>(resp.getBody()) : new HashMap<>();
        body.put("requestedMidCount", requestedMids.size());
        body.put("matchedMidCount", matched.size());
        body.put("unmatchedMids", unmatchedMids);
        return ResponseEntity.status(resp.getStatusCode()).body(body);
    }

    /**
     * Parse bank MIDs from an uploaded CSV/TSV/TXT file. Accepts a single MID per
     * line, or a delimited file with a column headed "MID" (case-insensitive).
     * Quotes/whitespace are stripped. Excel (.xlsx/.xls) is rejected — export to CSV.
     */
    private List<String> parseMidsFromFile(MultipartFile file) {
        String name = (file.getOriginalFilename() == null) ? "" : file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            throw new IllegalArgumentException(
                "Excel files are not supported for MID upload — please save as CSV or TXT (one MID per line).");
        }

        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            throw new RuntimeException("read failed: " + e.getMessage(), e);
        }
        if (lines.isEmpty()) return Collections.emptyList();
        lines.set(0, lines.get(0).replace("\uFEFF", "")); // strip UTF-8 BOM

        // Detect a header row with a MID column
        String[] header = lines.get(0).split("[,;\\t]");
        int midCol = -1;
        for (int i = 0; i < header.length; i++) {
            String h = cleanCell(header[i]).toLowerCase();
            if (h.equals("mid") || h.equals("merchant id") || h.equals("merchant_id") || h.equals("merchantid")) {
                midCol = i; break;
            }
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        int startRow = (midCol >= 0) ? 1 : 0;
        for (int r = startRow; r < lines.size(); r++) {
            String[] cells = lines.get(r).split("[,;\\t]");
            if (midCol >= 0) {
                if (midCol < cells.length) {
                    String v = cleanCell(cells[midCol]);
                    if (!v.isEmpty()) out.add(v);
                }
            } else {
                // No header: take every non-empty token (single-column lists and pasted
                // CSVs both work). Non-MID tokens simply end up reported as unmatched.
                for (String c : cells) {
                    String v = cleanCell(c);
                    if (!v.isEmpty() && !v.equalsIgnoreCase("mid")) out.add(v);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String cleanCell(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("^\"|\"$", "").trim();
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
        Map<String, Object> stats = new LinkedHashMap<>(playwrightPdfService.getEngineStats());
        stats.put("storageType",        reportStorageService.getStorageInfo());
        stats.put("reportsRoot",        reportsRoot.toString());
        stats.put("s3ServiceAvailable", reportS3UploadService != null);
        return ResponseEntity.ok(stats);
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
            response.put("exists",       count > 0);
            response.put("count",        count);
            response.put("targetMonth",  targetMonth.toString());
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
                    files.filter(p -> p.toString().endsWith(".pdf")).sorted().forEach(p -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("filename", p.getFileName().toString());
                        try {
                            entry.put("size",      Files.size(p));
                            entry.put("createdAt", Files.getLastModifiedTime(p).toString());
                        } catch (IOException ignored) {
                            entry.put("size", 0);
                        }
                        entry.put("downloadUrl", "/api/business/insights/download-report?file="
                            + p.getFileName().toString()
                            + "&year="  + targetMonth.getYear()
                            + "&month=" + targetMonth.getMonthValue());
                        reports.add(entry);
                    });
                }
            }
            return ResponseEntity.ok(Map.of(
                "targetMonth",  targetMonth.toString(),
                "count",        reports.size(),
                "reports",      reports,
                "resolvedPath", folderPath.toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Download ──────────────────────────────────────────────────────

    @GetMapping("/download-report")
    public void downloadReport(
            @RequestParam String file,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        YearMonth targetMonth = resolveTargetMonth(year, month);
        String safeName = Paths.get(file).getFileName().toString();
        if (!safeName.endsWith(".pdf")) {
            response.sendError(400, "Invalid file type");
            return;
        }

        Path filePath = resolveReportFile(safeName, targetMonth);
        log.info("Download: file={} resolved={} exists={}", safeName, filePath, filePath != null && Files.exists(filePath));

        if (filePath == null || !Files.exists(filePath)) {
            Path folder = monthFolder(targetMonth);
            boolean folderExists = Files.exists(folder);
            long pdfCount = 0;
            if (folderExists) {
                try (Stream<Path> fs = Files.list(folder)) {
                    pdfCount = fs.filter(p -> p.toString().endsWith(".pdf")).count();
                }
            }
            log.warn("Download 404: {} | folder={} exists={} pdfs={}", safeName, folder, folderExists, pdfCount);
            response.sendError(404, "Report not found: " + safeName);
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

    // ─── Overview (data only, no PDF) ─────────────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<MerchantInsightsDTO> getInsights(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        if (merchantId == null) merchantId = 1L;
        // SECURITY: scope to the caller's tenant and require the merchant to
        // belong to it. Previously any authenticated user could pass an
        // arbitrary merchantId and read another tenant's insight data (IDOR).
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        YearMonth targetMonth = resolveTargetMonth(year, month);
        try {
            return ResponseEntity.ok(
                    coreClient.fetchInsights(merchantId, targetMonth.getYear(),
                            targetMonth.getMonthValue(), tenantId));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).build();
        }
    }

    // ─── Generate single merchant report to disk ───────────────────────

    @PostMapping("/generate/{merchantId}")
    public ResponseEntity<Map<String, Object>> generateReport(
            @PathVariable Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "false") boolean force) {

        if (!playwrightPdfService.isEngineReady()) {
            return ResponseEntity.ok(Map.of("status", "PDF_ENGINE_NOT_READY"));
        }
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            Path folder = monthFolder(targetMonth);
            Files.createDirectories(folder);

            String merchantName = resolvemerchantName(merchantId);
            String safeName  = merchantName.replaceAll("[^a-zA-Z0-9.\\-]", "_");
            String filename  = "Insight_" + safeName + "_" + targetMonth + ".pdf";
            Path   outPath   = folder.resolve(filename);

            if (!force && Files.exists(outPath) && Files.size(outPath) > 0) {
                log.info("Report cached for merchant {} ({}): {}", merchantId, merchantName, outPath);
                return ResponseEntity.ok(Map.of(
                    "status",      "CACHED",
                    "filename",    filename,
                    "size",        Files.size(outPath),
                    "path",        outPath.toString(),
                    "downloadUrl", "/api/business/insights/download-report?file="
                            + filename + "&year=" + targetMonth.getYear()
                            + "&month=" + targetMonth.getMonthValue()
                ));
            }

            MerchantInsightsDTO data = coreClient.fetchInsights(merchantId,
                    targetMonth.getYear(), targetMonth.getMonthValue());

            byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchantName,
                    targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));

            Files.write(outPath, pdfBytes);

            return ResponseEntity.ok(Map.of(
                    "status",      "SUCCESS",
                    "filename",    filename,
                    "size",        pdfBytes.length,
                    "path",        outPath.toString(),
                    "downloadUrl", "/api/business/insights/download-report?file="
                            + filename + "&year=" + targetMonth.getYear()
                            + "&month=" + targetMonth.getMonthValue()
            ));
        } catch (Exception e) {
            log.error("Failed to generate report for merchant {}", merchantId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Private helpers ───────────────────────────────────────────────

    private Path resolveReportFile(String filename, YearMonth targetMonth) {
        Path tenantPath = monthFolder(targetMonth).resolve(filename);
        if (Files.exists(tenantPath)) return tenantPath;
        Path legacyPath = reportsRoot.resolve(targetMonth.toString()).resolve(filename);
        if (Files.exists(legacyPath)) return legacyPath;
        return tenantPath;
    }

    private Path resolveReportFolder(YearMonth targetMonth) {
        Path tenantFolder = monthFolder(targetMonth);
        if (Files.exists(tenantFolder)) return tenantFolder;
        Path legacyFolder = reportsRoot.resolve(targetMonth.toString());
        if (Files.exists(legacyFolder)) return legacyFolder;
        return tenantFolder;
    }

    private void logBatchRun(String jobId, Long tenantId, String targetMonth, int merchantCount, String status) {
        try {
            jdbcTemplate.update(
                "INSERT INTO pdf_batch_log (job_id, tenant_id, target_month, merchant_count, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, NOW())",
                jobId, tenantId, targetMonth, merchantCount, status);
        } catch (Exception e) {
            log.debug("[BATCH-LOG] Could not log batch run: {}", e.getMessage());
        }
    }

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        // Default to the PREVIOUS completed month — NOT the current (incomplete) month.
        // getBulkInsights anchors on this month and builds the 12-month trend ENDING
        // here (trendStart = endOfMonth.minusMonths(12)), so anchoring on last month
        // yields a report covering the previous 12 completed months.
        return YearMonth.now().minusMonths(1);
    }

    private String resolvemerchantName(Long merchantId) {
        String mid = null;
        try {
            var mOpt = merchantRepository.findById(merchantId);
            if (mOpt.isPresent()) {
                var m = mOpt.get();
                mid = m.getMid();
                if (m.getName() != null && !m.getName().isBlank()) return m.getName();
            }
        } catch (Exception e) {
            log.warn("Failed to lookup merchant {}: {}", merchantId, e.getMessage());
        }

        String resolved;
        if (mid != null) {
            resolved = tryQueryMerchantName(
                "SELECT merchant_name FROM stg_trnx_raw WHERE mid = ? AND merchant_name IS NOT NULL AND TRIM(merchant_name) <> '' LIMIT 1",
                mid, "stg_trnx_raw (direct)");
            if (resolved != null) { persistMerchantName(merchantId, resolved); return resolved; }
        }

        resolved = tryQueryMerchantName(
            "SELECT s.merchant_name FROM stg_trnx_raw s JOIN dim_merchant m ON s.mid = m.mid " +
            "WHERE m.merchant_id = ? AND s.merchant_name IS NOT NULL AND TRIM(s.merchant_name) <> '' LIMIT 1",
            merchantId, "stg_trnx_raw (join)");
        if (resolved != null) { persistMerchantName(merchantId, resolved); return resolved; }

        if (mid != null) {
            resolved = tryQueryMerchantName(
                "SELECT merchant_name FROM stg_merchant_master_raw WHERE mid = ? AND merchant_name IS NOT NULL AND TRIM(merchant_name) <> '' LIMIT 1",
                mid, "stg_merchant_master_raw");
            if (resolved != null) { persistMerchantName(merchantId, resolved); return resolved; }
        }

        if (mid != null && !mid.isBlank()) return mid;
        return "Merchant " + merchantId;
    }

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

    private void persistMerchantName(Long merchantId, String name) {
        try {
            int updated = jdbcTemplate.update(
                "UPDATE dim_merchant SET name = ? WHERE merchant_id = ? AND (name IS NULL OR TRIM(name) = '')",
                name, merchantId);
            if (updated > 0) log.info("Persisted merchant name '{}' for id={}", name, merchantId);
        } catch (Exception e) {
            log.debug("Could not persist merchant name: {}", e.getMessage());
        }
    }
}
