package com.acquira.core.controller;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.service.MerchantInsightService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/business/insights")
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
        YearMonth targetMonth = resolveTargetMonth(year, month);
        return ResponseEntity.ok(insightService.getInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue()));
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
                "message", "PDF generation is available in the PDF module. Start acquira-pdf to use this feature.",
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

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }
}
