package com.acquira.controller;

import com.acquira.dto.MerchantInsightsDTO;
import com.acquira.service.MerchantInsightService;
import com.acquira.service.PdfGenerationService;
import com.acquira.config.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/business/insights")
@CrossOrigin(origins = "http://localhost:5173")
public class MerchantInsightController {

    private final MerchantInsightService insightService;
    private final PdfGenerationService pdfService;
    private final com.acquira.repository.MerchantRepository merchantRepository;

    public MerchantInsightController(MerchantInsightService insightService, PdfGenerationService pdfService,
            com.acquira.repository.MerchantRepository merchantRepository) {
        this.insightService = insightService;
        this.pdfService = pdfService;
        this.merchantRepository = merchantRepository;
    }

    @GetMapping("/check-status")
    public ResponseEntity<java.util.Map<String, Object>> checkReportStatus(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            String folder = "reports/" + targetMonth.toString();
            java.nio.file.Path folderPath = java.nio.file.Paths.get(folder);

            int count = 0;
            if (java.nio.file.Files.exists(folderPath)) {
                try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(folderPath)) {
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

    @GetMapping("/overview")
    public ResponseEntity<MerchantInsightsDTO> getInsights(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        // ... (existing logic)
        if (merchantId == null) {
            merchantId = 1L;
        }

        YearMonth targetMonth = resolveTargetMonth(year, month);
        return ResponseEntity
                .ok(insightService.getInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue()));
    }

    @PostMapping("/generate-all")
    public ResponseEntity<java.util.Map<String, Object>> generateAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        int successCount = 0;
        int failureCount = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();

        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            String folder = "reports/" + targetMonth.toString();
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(folder));

            java.util.List<com.acquira.model.Merchant> merchants = merchantRepository.findAll();

            for (com.acquira.model.Merchant m : merchants) {
                try {
                    MerchantInsightsDTO data = insightService.getInsights(m.getMerchantId(), targetMonth.getYear(),
                            targetMonth.getMonthValue());
                    String merchantName = m.getName() != null ? m.getName() : "Merchant_" + m.getMerchantId();
                    // Sanitize filename
                    String safeName = merchantName.replaceAll("[^a-zA-Z0-9.-]", "_");

                    String filename = "Insight_" + safeName + "_" + targetMonth + ".pdf";
                    java.nio.file.Path path = java.nio.file.Paths.get(folder, filename);

                    String password = m.getMid() != null ? m.getMid() : String.valueOf(m.getMerchantId());
                    byte[] pdfBytes = pdfService.generateMerchantInsightPdf(data, merchantName, targetMonth.toString(),
                            password);
                    java.nio.file.Files.write(path, pdfBytes);
                    successCount++;
                } catch (Exception e) {
                    failureCount++;
                    String errorMsg = "Merchant " + m.getMerchantId() + ": " + e.getMessage();
                    errors.add(errorMsg);
                    System.err.println(errorMsg);
                    e.printStackTrace();
                    // Continue to next
                }
            }

            response.put("generated", successCount);
            response.put("failed", failureCount);
            response.put("errors", errors);
            response.put("folder", java.nio.file.Paths.get(folder).toAbsolutePath().toString());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Critical Failure: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/generate/{merchantId}")
    public ResponseEntity<String> generateReport(@PathVariable Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            MerchantInsightsDTO data = insightService.getInsights(merchantId, targetMonth.getYear(),
                    targetMonth.getMonthValue());

            // ... (existing)
            // Better to fetch name now that we have repo
            String merchantName = "Merchant " + merchantId;
            var mOpt = merchantRepository.findById(merchantId);
            if (mOpt.isPresent())
                merchantName = mOpt.get().getName();

            String folder = "reports/" + targetMonth.toString();
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(folder));
            String filename = "Merchant_Insight_" + merchantId + "_" + targetMonth + ".pdf";
            java.nio.file.Path path = java.nio.file.Paths.get(folder, filename);

            String password = String.valueOf(merchantId);
            if (mOpt.isPresent() && mOpt.get().getMid() != null) {
                password = mOpt.get().getMid();
            }

            byte[] pdfBytes = pdfService.generateMerchantInsightPdf(data, merchantName, targetMonth.toString(),
                    password);
            java.nio.file.Files.write(path, pdfBytes);

            return ResponseEntity.ok(path.toAbsolutePath().toString());
        } catch (Exception e) {
            e.printStackTrace(); // Log the full error to console
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
            merchantId = 1L; // Demo default

        YearMonth targetMonth = resolveTargetMonth(year, month);

        MerchantInsightsDTO data = insightService.getInsights(merchantId, targetMonth.getYear(),
                targetMonth.getMonthValue());

        String monthStr = targetMonth.format(DateTimeFormatter.ofPattern("MMM yyyy"));
        // Ideally fetch Merchant Name from Service
        String merchantName = "MERCHANT " + merchantId;

        // Fetch Merchant to get MID (Password)
        String password = String.valueOf(merchantId);
        var mOpt = merchantRepository.findById(merchantId);
        if (mOpt.isPresent() && mOpt.get().getMid() != null) {
            password = mOpt.get().getMid();
            if (mOpt.get().getName() != null) {
                merchantName = mOpt.get().getName();
            }
        }

        byte[] pdfBytes = pdfService.generateMerchantInsightPdf(data, merchantName, monthStr,
                password);

        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=Merchant_Insight_" + monthStr + ".pdf");
        response.getOutputStream().write(pdfBytes);
    }

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) {
            return YearMonth.of(year, month);
        }
        // Default to Previous Month
        return YearMonth.now().minusMonths(1);
    }
}
