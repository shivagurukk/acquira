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

    private static final Logger log = LoggerFactory.getLogger(PdfController.class);

    private final PlaywrightPdfService playwrightPdfService;
    private final MerchantRepository merchantRepository;
    private final CoreServiceClient coreClient;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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

    /** Get the folder for a given month: {reportsRoot}/{YYYY-MM} */
    private Path monthFolder(YearMonth ym) {
        return reportsRoot.resolve(ym.toString());
    }

    public PdfController(PlaywrightPdfService playwrightPdfService,
                         MerchantRepository merchantRepository,
                         CoreServiceClient coreClient,
                         org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.playwrightPdfService = playwrightPdfService;
        this.merchantRepository = merchantRepository;
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
            @RequestParam(required = false) Integer month) {
        if (!playwrightPdfService.isEngineReady()) {
            return ResponseEntity.ok(Map.of(
                "status", "PDF_ENGINE_NOT_READY",
                "message", "PDF engine failed to initialize. Playwright browsers may not be installed. "
                    + "Run: mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=install"
            ));
        }
        try {
            YearMonth targetMonth = resolveTargetMonth(year, month);
            Path folder = monthFolder(targetMonth);
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
                    folder.toString(), monthYear, targetMonth.toString());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", status.jobId);
            response.put("totalMerchants", merchants.size());
            response.put("targetFolder", folder.toString());
            response.put("targetMonth", targetMonth.toString());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
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
            Path folderPath = monthFolder(targetMonth);
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
            Path folderPath = monthFolder(targetMonth);
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
        Path folder = monthFolder(targetMonth);

        // Security: prevent path traversal — extract just the filename
        String safeName = Paths.get(file).getFileName().toString();
        if (!safeName.endsWith(".pdf")) {
            response.sendError(400, "Invalid file type");
            return;
        }

        Path filePath = folder.resolve(safeName);

        // Log resolved path for debugging
        log.info("Download request: file={}, resolved={}, exists={}", safeName, filePath, Files.exists(filePath));

        if (!Files.exists(filePath)) {
            // Provide diagnostic info in the error response
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
        Path folderPath = monthFolder(targetMonth);

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

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }

    /**
     * Resolve merchant name with multiple fallbacks:
     * 1. dim_merchant.name
     * 2. dim_merchant.mid (merchant ID code)
     * 3. Look up merchant_name from fact_transaction (joined via merchant_id)
     * 4. "Merchant {id}" as last resort
     */
    private String resolvemerchantName(Long merchantId) {
        try {
            var mOpt = merchantRepository.findById(merchantId);
            if (mOpt.isPresent()) {
                var m = mOpt.get();
                // Prefer name, then MID
                if (m.getName() != null && !m.getName().isBlank()) return m.getName();
                if (m.getMid() != null && !m.getMid().isBlank()) return m.getMid();
            }
        } catch (Exception e) {
            log.warn("Failed to lookup merchant {} from dim_merchant: {}", merchantId, e.getMessage());
        }

        // Fallback: query merchant_name from stg_trnx_raw via dim_merchant.mid
        try {
            String name = jdbcTemplate.queryForObject(
                "SELECT s.merchant_name FROM stg_trnx_raw s " +
                "JOIN dim_merchant m ON s.mid = m.mid AND s.tenant_id = m.tenant_id " +
                "WHERE m.merchant_id = ? AND s.merchant_name IS NOT NULL LIMIT 1",
                String.class, merchantId);
            if (name != null && !name.isBlank()) {
                // Also update dim_merchant.name so we don't need this fallback again
                try {
                    jdbcTemplate.update("UPDATE dim_merchant SET name = ? WHERE merchant_id = ? AND (name IS NULL OR name = '')", name, merchantId);
                } catch (Exception ignored) { }
                return name;
            }
        } catch (Exception e) {
            log.debug("No merchant_name found in stg_trnx_raw for merchant {}", merchantId);
        }

        return "Merchant " + merchantId;
    }
}
