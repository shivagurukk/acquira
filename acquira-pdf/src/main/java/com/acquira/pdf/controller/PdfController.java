package com.acquira.pdf.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.repository.MerchantRepository;
import com.acquira.pdf.service.PlaywrightPdfService;
import com.acquira.pdf.service.PlaywrightPdfService.BatchJobStatus;
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
public class PdfController {

    private final PlaywrightPdfService playwrightPdfService;
    private final MerchantRepository merchantRepository;
    private final CoreServiceClient coreClient;

    public PdfController(PlaywrightPdfService playwrightPdfService,
                         MerchantRepository merchantRepository,
                         CoreServiceClient coreClient) {
        this.playwrightPdfService = playwrightPdfService;
        this.merchantRepository = merchantRepository;
        this.coreClient = coreClient;
    }

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

    @PostMapping("/generate-all")
    public ResponseEntity<Map<String, Object>> generateAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            String folder = "reports/" + targetMonth.toString();
            String monthYear = targetMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"));
            Long currentTenant = TenantContext.getCurrentTenant();

            List<com.acquira.common.model.Merchant> merchants = merchantRepository.findAll();
            List<long[]> merchantIds = new ArrayList<>(merchants.size());
            List<String> merchantNames = new ArrayList<>(merchants.size());
            for (var m : merchants) {
                merchantIds.add(new long[]{m.getMerchantId()});
                merchantNames.add(m.getName() != null ? m.getName() : "Merchant_" + m.getMerchantId());
            }

            BatchJobStatus status = playwrightPdfService.generateBatch(
                    merchantIds, merchantNames,
                    (mid, ctx) -> {
                        if (currentTenant != null) TenantContext.setCurrentTenant(currentTenant);
                        try {
                            return coreClient.fetchInsights(mid, targetMonth.getYear(), targetMonth.getMonthValue());
                        } finally {
                            TenantContext.clear();
                        }
                    },
                    folder, monthYear, targetMonth.toString());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", status.jobId);
            response.put("totalMerchants", merchants.size());
            response.put("targetFolder", Paths.get(folder).toAbsolutePath().toString());
            response.put("targetMonth", targetMonth.toString());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

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

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }
}
