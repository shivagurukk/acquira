package com.acquira.core.controller;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.service.MerchantInsightService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Stream;

/**
 * Stub controller for /api/business/insights endpoints.
 * Only loads when acquira-pdf module is NOT on the classpath.
 * When acquira-pdf is included as a dependency, its PdfController
 * handles these endpoints with real PDF generation.
 */
@RestController
@RequestMapping("/api/business/insights")
@ConditionalOnMissingClass("com.acquira.pdf.controller.PdfController")
@PreAuthorize("@menuAccess.canAccess('/business/report-manager')")
public class MerchantInsightController {

    private final MerchantInsightService insightService;

    public MerchantInsightController(MerchantInsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping("/overview")
    public ResponseEntity<MerchantInsightsDTO> getInsights(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        if (merchantId == null) merchantId = 1L;
        // SECURITY: resolve the caller's tenant and require the merchant to
        // belong to it. Previously any user could pass any merchantId and read
        // another tenant's data (IDOR).
        Long tenantId = com.acquira.common.config.TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        YearMonth targetMonth = resolveTargetMonth(year, month);
        try {
            return ResponseEntity.ok(insightService.getInsights(
                    merchantId, targetMonth.getYear(), targetMonth.getMonthValue(), tenantId));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).build();
        }
    }

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

    @PostMapping("/generate-all")
    public ResponseEntity<Map<String, Object>> generateAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return ResponseEntity.ok(Map.of(
                "message", "PDF generation is available in the PDF module. Add acquira-pdf dependency to enable.",
                "status", "PDF_MODULE_NOT_LOADED"
        ));
    }

    @PostMapping("/generate/{merchantId}")
    public ResponseEntity<String> generateReport(@PathVariable Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return ResponseEntity.ok("PDF generation is available in the PDF module.");
    }

    @GetMapping("/pdf")
    public ResponseEntity<String> downloadPdf(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return ResponseEntity.ok("PDF generation is available in the PDF module.");
    }

    @GetMapping("/batch-status/{jobId}")
    public ResponseEntity<Map<String, Object>> getBatchStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(Map.of("status", "PDF_MODULE_NOT_LOADED"));
    }

    @GetMapping("/batch-jobs")
    public ResponseEntity<List<Map<String, Object>>> listBatchJobs() {
        return ResponseEntity.ok(Collections.emptyList());
    }

    @PostMapping("/batch-cancel/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelBatch(@PathVariable String jobId) {
        return ResponseEntity.ok(Map.of("cancelled", false, "message", "PDF module not loaded"));
    }

    @GetMapping("/engine-stats")
    public ResponseEntity<Map<String, Object>> getEngineStats() {
        return ResponseEntity.ok(Map.of("status", "PDF_MODULE_NOT_LOADED"));
    }

    @GetMapping("/list-reports")
    public ResponseEntity<Map<String, Object>> listReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return ResponseEntity.ok(Map.of("count", 0, "reports", Collections.emptyList(), "targetMonth", resolveTargetMonth(year, month).toString()));
    }

    @GetMapping("/download-report")
    public ResponseEntity<String> downloadReport(@RequestParam String file) {
        return ResponseEntity.ok("PDF module not loaded");
    }

    @GetMapping("/download-all-reports")
    public ResponseEntity<String> downloadAllReports() {
        return ResponseEntity.ok("PDF module not loaded");
    }

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }
}
