package com.acquira.controller;

import com.acquira.dto.MerchantInsightsDTO;
import com.acquira.service.MerchantInsightService;
import com.acquira.service.PlaywrightPdfService; // Updated
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/business/insights")
@CrossOrigin(origins = "http://localhost:5173")
public class MerchantInsightController {

    private final MerchantInsightService insightService;
    private final PlaywrightPdfService playwrightPdfService; // Updated
    private final com.acquira.repository.MerchantRepository merchantRepository;

    public MerchantInsightController(MerchantInsightService insightService, PlaywrightPdfService playwrightPdfService,
            com.acquira.repository.MerchantRepository merchantRepository) {
        this.insightService = insightService;
        this.playwrightPdfService = playwrightPdfService;
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
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        java.util.List<String> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            String folder = "reports/" + targetMonth.toString();
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(folder));

            java.util.List<com.acquira.model.Merchant> merchants = merchantRepository.findAll();

            // Capture tenant context from request thread to propagate to worker threads
            Long currentTenant = TenantContext.getCurrentTenant();

            // Phase 1: Fetch all DTO data sequentially (DB queries share connection pool)
            long dataStart = System.currentTimeMillis();
            java.util.List<Object[]> workItems = new java.util.ArrayList<>();
            for (com.acquira.model.Merchant m : merchants) {
                try {
                    MerchantInsightsDTO data = insightService.getInsights(m.getMerchantId(), targetMonth.getYear(),
                            targetMonth.getMonthValue());
                    String merchantName = m.getName() != null ? m.getName() : "Merchant_" + m.getMerchantId();
                    workItems.add(new Object[]{m, data, merchantName});
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    errors.add("Merchant " + m.getMerchantId() + " (data): " + e.getMessage());
                }
            }
            long dataTime = System.currentTimeMillis() - dataStart;

            // Phase 2: Generate PDFs in parallel (4 browsers = 4 concurrent renders)
            long pdfStart = System.currentTimeMillis();
            ExecutorService executor = Executors.newFixedThreadPool(4);
            java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();

            for (Object[] item : workItems) {
                com.acquira.model.Merchant m = (com.acquira.model.Merchant) item[0];
                MerchantInsightsDTO data = (MerchantInsightsDTO) item[1];
                String merchantName = (String) item[2];

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    // Propagate tenant context to worker thread
                    if (currentTenant != null) {
                        TenantContext.setCurrentTenant(currentTenant);
                    }
                    try {
                        String safeName = merchantName.replaceAll("[^a-zA-Z0-9.-]", "_");
                        String filename = "Insight_" + safeName + "_" + targetMonth + ".pdf";
                        java.nio.file.Path path = java.nio.file.Paths.get(folder, filename);

                        byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchantName, targetMonth.toString());
                        java.nio.file.Files.write(path, pdfBytes);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                        String errorMsg = "Merchant " + m.getMerchantId() + " (pdf): " + e.getMessage();
                        errors.add(errorMsg);
                        System.err.println(errorMsg);
                    } finally {
                        TenantContext.clear();
                    }
                }, executor);
                futures.add(future);
            }

            // Wait for all PDFs to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();
            long pdfTime = System.currentTimeMillis() - pdfStart;

            response.put("generated", successCount.get());
            response.put("failed", failureCount.get());
            response.put("errors", errors);
            response.put("folder", java.nio.file.Paths.get(folder).toAbsolutePath().toString());
            response.put("dataFetchMs", dataTime);
            response.put("pdfGenerationMs", pdfTime);
            response.put("avgPdfMs", workItems.isEmpty() ? 0 : pdfTime / workItems.size());

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

            byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchantName, targetMonth.toString());
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

        byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchantName, monthStr);

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
