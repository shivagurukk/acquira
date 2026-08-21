package com.acquira.controller;

import com.acquira.dto.MerchantInsightsDTO;
import com.acquira.service.MerchantInsightService;
import com.acquira.service.PlaywrightPdfService;
import com.acquira.service.PlaywrightPdfService.BatchJobStatus;
import com.acquira.config.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/business/insights")
public class MerchantInsightController {

    private final MerchantInsightService insightService;
    private final PlaywrightPdfService playwrightPdfService;
    private final com.acquira.service.EmailService emailService;
    private final com.acquira.repository.MerchantRepository merchantRepository;

    public MerchantInsightController(MerchantInsightService insightService,
            PlaywrightPdfService playwrightPdfService,
            com.acquira.service.EmailService emailService,
            com.acquira.repository.MerchantRepository merchantRepository) {
        this.insightService = insightService;
        this.playwrightPdfService = playwrightPdfService;
        this.emailService = emailService;
        this.merchantRepository = merchantRepository;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // SINGLE REPORT ENDPOINTS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/overview")
    public ResponseEntity<MerchantInsightsDTO> getInsights(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        if (merchantId == null)
            merchantId = 1L;
        YearMonth targetMonth = resolveTargetMonth(year, month);
        return ResponseEntity
                .ok(insightService.getInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue()));
    }

    @PostMapping("/generate/{merchantId}")
    public ResponseEntity<String> generateReport(@PathVariable Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            MerchantInsightsDTO data = insightService.getInsights(merchantId, targetMonth.getYear(),
                    targetMonth.getMonthValue());

            String merchantName = "Merchant " + merchantId;
            var mOpt = merchantRepository.findById(merchantId);
            if (mOpt.isPresent())
                merchantName = mOpt.get().getName();

            Long currentTenant = TenantContext.getCurrentTenant();
            String tenantFolder = currentTenant != null ? "tenant_" + currentTenant : "default";
            String folder = "reports/" + tenantFolder + "/" + targetMonth.toString();
            Files.createDirectories(Paths.get(folder));
            String filename = "Merchant_Insight_" + merchantId + "_" + targetMonth + ".pdf";
            Path path = Paths.get(folder, filename);

            byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchantName,
                    targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));
            Files.write(path, pdfBytes);

            return ResponseEntity.ok(path.toAbsolutePath().toString());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/pdf")
    public void downloadPdf(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        if (merchantId == null)
            merchantId = 1L;
        YearMonth targetMonth = resolveTargetMonth(year, month);

        MerchantInsightsDTO data = insightService.getInsights(merchantId, targetMonth.getYear(),
                targetMonth.getMonthValue());

        String merchantName = "MERCHANT " + merchantId;
        var mOpt = merchantRepository.findById(merchantId);
        if (mOpt.isPresent() && mOpt.get().getName() != null) {
            merchantName = mOpt.get().getName();
        }

        byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchantName,
                targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));

        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=Merchant_Insight_" + targetMonth + ".pdf");
        response.getOutputStream().write(pdfBytes);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // BATCH GENERATION — High-Performance Pipeline
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Starts async batch generation for all merchants.
     * Returns immediately with a jobId for progress tracking.
     *
     * Usage:
     * POST /api/business/insights/generate-all?year=2026&month=1
     * → Returns { jobId: "batch-xxxxx", ... }
     *
     * GET /api/business/insights/batch-status/{jobId}
     * → Returns { progressPercent: 45.2, completed: 9040, ... }
     */
    @PostMapping("/generate-all")
    public ResponseEntity<Map<String, Object>> generateAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false, defaultValue = "false") boolean sendEmail) {

        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);

            // Capture tenant context for propagation to worker threads
            Long currentTenant = TenantContext.getCurrentTenant();

            // Tenant-scoped report folder: reports/tenant_{id}/{YYYY-MM}
            String tenantFolder = currentTenant != null ? "tenant_" + currentTenant : "default";
            String folder = "reports/" + tenantFolder + "/" + targetMonth.toString();
            String monthYear = targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"));

            // Capture current user for SSE notifications
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            String username = (auth != null && auth.isAuthenticated()) ? auth.getName() : null;

            // Fetch merchant list (lightweight — just IDs and names)
            List<com.acquira.model.Merchant> merchants = merchantRepository.findAll();

            List<long[]> merchantIds = new ArrayList<>(merchants.size());
            List<String> merchantNames = new ArrayList<>(merchants.size());
            for (com.acquira.model.Merchant m : merchants) {
                merchantIds.add(new long[] { m.getMerchantId() });
                merchantNames.add(m.getName() != null ? m.getName() : "Merchant_" + m.getMerchantId());
            }

            // Start the pipeline — returns immediately
            BatchJobStatus status = playwrightPdfService.generateBatch(
                    merchantIds,
                    merchantNames,
                    (merchantId, idCtx) -> {
                        // Set tenant context in worker thread
                        if (currentTenant != null)
                            TenantContext.setCurrentTenant(currentTenant);
                        try {
                            return insightService.getInsights(merchantId, targetMonth.getYear(),
                                    targetMonth.getMonthValue());
                        } finally {
                            TenantContext.clear();
                        }
                    },
                    monthYear,
                    targetMonth.toString(),
                    sendEmail ? () -> emailService.onBatchComplete(targetMonth.toString(), folder) : null,
                    username);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", status.jobId);
            response.put("totalMerchants", merchants.size());
            response.put("targetFolder", Paths.get(folder).toAbsolutePath().toString());
            response.put("targetMonth", targetMonth.toString());
            response.put("message",
                    "Batch generation started — use /batch-status/" + status.jobId + " to track progress");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start batch: " + e.getMessage()));
        }
    }

    /**
     * Get progress of a batch generation job.
     */
    @GetMapping("/batch-status/{jobId}")
    public ResponseEntity<Map<String, Object>> getBatchStatus(@PathVariable String jobId) {
        BatchJobStatus status = playwrightPdfService.getJobStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status.toMap());
    }

    /**
     * List all active/recent batch jobs.
     */
    @GetMapping("/batch-jobs")
    public ResponseEntity<List<Map<String, Object>>> listBatchJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        playwrightPdfService.getActiveJobs().values().forEach(s -> jobs.add(s.toMap()));
        return ResponseEntity.ok(jobs);
    }

    /**
     * Cancel a running batch job.
     */
    @PostMapping("/batch-cancel/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelBatch(@PathVariable String jobId) {
        boolean cancelled = playwrightPdfService.cancelJob(jobId);
        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "cancelled", cancelled,
                "message", cancelled ? "Job cancellation requested" : "Job not found or already completed"));
    }

    /**
     * Get PDF engine statistics.
     */
    @GetMapping("/engine-stats")
    public ResponseEntity<Map<String, Object>> getEngineStats() {
        return ResponseEntity.ok(playwrightPdfService.getEngineStats());
    }

    /**
     * Check if reports already exist for a given month.
     */
    @GetMapping("/check-status")
    public ResponseEntity<Map<String, Object>> checkReportStatus(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        Map<String, Object> response = new HashMap<>();
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            Long currentTenant = TenantContext.getCurrentTenant();
            String tenantFolder = currentTenant != null ? "tenant_" + currentTenant : "default";
            String folder = "reports/" + tenantFolder + "/" + targetMonth.toString();
            Path folderPath = Paths.get(folder);

            int count = 0;
            if (Files.exists(folderPath)) {
                try (Stream<Path> files = Files.list(folderPath)) {
                    count = (int) files.filter(p -> p.toString().endsWith(".pdf")).count();
                }
            }

            response.put("exists", count > 0);
            response.put("count", count);
            response.put("targetMonth", targetMonth.toString());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // REPORT LIST & DOWNLOAD ENDPOINTS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * List generated reports for the active tenant and month.
     */
    @GetMapping("/list-reports")
    public ResponseEntity<Map<String, Object>> listReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            Long currentTenant = TenantContext.getCurrentTenant();
            String tenantFolder = currentTenant != null ? "tenant_" + currentTenant : "default";
            String folder = "reports/" + tenantFolder + "/" + targetMonth.toString();
            Path folderPath = Paths.get(folder);

            List<Map<String, Object>> reports = new ArrayList<>();
            if (Files.exists(folderPath)) {
                try (Stream<Path> files = Files.list(folderPath)) {
                    files.filter(p -> p.toString().endsWith(".pdf"))
                         .sorted()
                         .forEach(p -> {
                             Map<String, Object> report = new LinkedHashMap<>();
                             report.put("filename", p.getFileName().toString());
                             try { report.put("size", Files.size(p)); } catch (IOException e) { report.put("size", 0); }
                             report.put("downloadUrl", "/api/business/insights/download-report?file=" + p.getFileName() + "&year=" + targetMonth.getYear() + "&month=" + targetMonth.getMonthValue());
                             reports.add(report);
                         });
                }
            }

            return ResponseEntity.ok(Map.of("reports", reports, "count", reports.size(), "targetMonth", targetMonth.toString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Download a single report PDF.
     */
    @GetMapping("/download-report")
    public void downloadReport(
            @RequestParam String file,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        YearMonth targetMonth = resolveTargetMonth(year, month);
        Long currentTenant = TenantContext.getCurrentTenant();
        String tenantFolder = currentTenant != null ? "tenant_" + currentTenant : "default";
        Path filePath = Paths.get("reports", tenantFolder, targetMonth.toString(), file);

        if (!Files.exists(filePath)) {
            response.sendError(404, "Report not found");
            return;
        }

        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + file + "\"");
        response.setContentLengthLong(Files.size(filePath));
        Files.copy(filePath, response.getOutputStream());
    }

    /**
     * Download ALL reports as a ZIP file.
     */
    @GetMapping("/download-all-reports")
    public void downloadAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        YearMonth targetMonth = resolveTargetMonth(year, month);
        Long currentTenant = TenantContext.getCurrentTenant();
        String tenantFolder = currentTenant != null ? "tenant_" + currentTenant : "default";
        Path folderPath = Paths.get("reports", tenantFolder, targetMonth.toString());

        if (!Files.exists(folderPath)) {
            response.sendError(404, "No reports found");
            return;
        }

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"Merchant_Reports_" + tenantFolder + "_" + targetMonth + ".zip\"");

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream());
             Stream<Path> files = Files.list(folderPath)) {
            files.filter(p -> p.toString().endsWith(".pdf")).forEach(p -> {
                try {
                    zos.putNextEntry(new java.util.zip.ZipEntry(p.getFileName().toString()));
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to zip: " + p.getFileName(), e);
                }
            });
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) {
            return YearMonth.of(year, month);
        }
        return YearMonth.now().minusMonths(1);
    }
}
