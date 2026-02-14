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

    /** Configurable reports root — defaults to ./reports (relative to CWD).
     *  On RHEL, set pdf.reports.dir=/opt/acquira/reports in application.properties */
    @org.springframework.beans.factory.annotation.Value("${pdf.reports.dir:reports}")
    private String reportsBaseDir;

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
        if (!playwrightPdfService.isEngineReady()) {
            return ResponseEntity.ok(Map.of(
                "status", "PDF_ENGINE_NOT_READY",
                "message", "PDF engine failed to initialize. Playwright browsers may not be installed. Run: mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=install"
            ));
        }
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            String folder = reportsBaseDir + "/" + targetMonth.toString();
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
            String folder = reportsBaseDir + "/" + targetMonth.toString();
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

    /**
     * List all generated PDF reports for a given month.
     */
    @GetMapping("/list-reports")
    public ResponseEntity<Map<String, Object>> listReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            String folder = reportsBaseDir + "/" + targetMonth.toString();
            Path folderPath = Paths.get(folder);
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
                                 + p.getFileName().toString() + "&year=" + targetMonth.getYear() 
                                 + "&month=" + targetMonth.getMonthValue());
                             reports.add(entry);
                         });
                }
            }
            return ResponseEntity.ok(Map.of(
                "targetMonth", targetMonth.toString(),
                "count", reports.size(),
                "reports", reports
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Download a specific generated PDF report.
     */
    @GetMapping("/download-report")
    public void downloadReport(
            @RequestParam String file,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        YearMonth targetMonth = resolveTargetMonth(year, month);
        String folder = reportsBaseDir + "/" + targetMonth.toString();

        // Security: prevent path traversal
        String safeName = Paths.get(file).getFileName().toString();
        if (!safeName.endsWith(".pdf")) {
            response.sendError(400, "Invalid file type");
            return;
        }

        Path filePath = Paths.get(folder, safeName);
        if (!Files.exists(filePath)) {
            response.sendError(404, "Report not found");
            return;
        }

        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=" + safeName);
        response.setContentLengthLong(Files.size(filePath));
        Files.copy(filePath, response.getOutputStream());
    }

    /**
     * Download ALL reports for a month as a ZIP file.
     */
    @GetMapping("/download-all-reports")
    public void downloadAllReports(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        YearMonth targetMonth = resolveTargetMonth(year, month);
        String folder = reportsBaseDir + "/" + targetMonth.toString();
        Path folderPath = Paths.get(folder);

        if (!Files.exists(folderPath)) {
            response.sendError(404, "No reports found for " + targetMonth);
            return;
        }

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", 
            "attachment; filename=Merchant_Reports_" + targetMonth + ".zip");

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream());
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

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }
}
