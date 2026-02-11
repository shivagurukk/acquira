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
    private final com.acquira.repository.MerchantRepository merchantRepository;

    public MerchantInsightController(MerchantInsightService insightService,
                                     PlaywrightPdfService playwrightPdfService,
                                     com.acquira.repository.MerchantRepository merchantRepository) {
        this.insightService = insightService;
        this.playwrightPdfService = playwrightPdfService;
        this.merchantRepository = merchantRepository;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  SINGLE REPORT ENDPOINTS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/overview")
    public ResponseEntity<MerchantInsightsDTO> getInsights(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        if (merchantId == null) merchantId = 1L;
        YearMonth targetMonth = resolveTargetMonth(year, month);
        return ResponseEntity.ok(insightService.getInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue()));
    }

    @PostMapping("/generate/{merchantId}")
    public ResponseEntity<String> generateReport(@PathVariable Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            MerchantInsightsDTO data = insightService.getInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue());

            String merchantName = "Merchant " + merchantId;
            var mOpt = merchantRepository.findById(merchantId);
            if (mOpt.isPresent()) merchantName = mOpt.get().getName();

            String folder = "reports/" + targetMonth.toString();
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

        if (merchantId == null) merchantId = 1L;
        YearMonth targetMonth = resolveTargetMonth(year, month);

        MerchantInsightsDTO data = insightService.getInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue());

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
    //  BATCH GENERATION — High-Performance Pipeline
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Starts async batch generation for all merchants.
     * Returns immediately with a jobId for progress tracking.
     *
     * Usage:
     *   POST /api/business/insights/generate-all?year=2026&month=1
     *   → Returns { jobId: "batch-xxxxx", ... }
     *
     *   GET /api/business/insights/batch-status/{jobId}
     *   → Returns { progressPercent: 45.2, completed: 9040, ... }
     */
    @PostMapping("/generate-all")
    public ResponseEntity<Map<String, Object>> generateAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            String folder = "reports/" + targetMonth.toString();
            String monthYear = targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"));

            // Capture tenant context for propagation to worker threads
            Long currentTenant = TenantContext.getCurrentTenant();

            // Fetch merchant list (lightweight — just IDs and names)
            List<com.acquira.model.Merchant> merchants = merchantRepository.findAll();

            List<long[]> merchantIds = new ArrayList<>(merchants.size());
            List<String> merchantNames = new ArrayList<>(merchants.size());
            for (com.acquira.model.Merchant m : merchants) {
                merchantIds.add(new long[]{ m.getMerchantId() });
                merchantNames.add(m.getName() != null ? m.getName() : "Merchant_" + m.getMerchantId());
            }

            // Start the pipeline — returns immediately
            BatchJobStatus status = playwrightPdfService.generateBatch(
                    merchantIds,
                    merchantNames,
                    (merchantId, idCtx) -> {
                        // Set tenant context in worker thread
                        if (currentTenant != null) TenantContext.setCurrentTenant(currentTenant);
                        try {
                            return insightService.getInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue());
                        } finally {
                            TenantContext.clear();
                        }
                    },
                    folder,
                    monthYear,
                    targetMonth.toString()
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", status.jobId);
            response.put("totalMerchants", merchants.size());
            response.put("targetFolder", Paths.get(folder).toAbsolutePath().toString());
            response.put("targetMonth", targetMonth.toString());
            response.put("message", "Batch generation started — use /batch-status/" + status.jobId + " to track progress");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to start batch: " + e.getMessage()));
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
                "message", cancelled ? "Job cancellation requested" : "Job not found or already completed"
        ));
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
            String folder = "reports/" + targetMonth.toString();
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

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) {
            return YearMonth.of(year, month);
        }
        return YearMonth.now().minusMonths(1);
    }
}
