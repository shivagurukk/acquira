package com.acquira.pdf.controller;

import com.acquira.common.model.Tenant;
import com.acquira.common.repository.MerchantRepository;
import com.acquira.common.repository.TenantRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * External API for 3rd-party systems to download merchant PDF reports.
 *
 * Authentication: API Key based (header: X-API-Key)
 * Base path: /api/external/reports
 *
 * Endpoints:
 *   GET  /api/external/reports/list?year=2026&month=1&tenantCode=ACQ
 *   GET  /api/external/reports/download?file=Insight_Coffee_Shop_2026-01.pdf&year=2026&month=1&tenantCode=ACQ
 *   GET  /api/external/reports/download-all?year=2026&month=1&tenantCode=ACQ
 *   GET  /api/external/reports/merchant/{mid}?year=2026&month=1&tenantCode=ACQ
 *   GET  /api/external/reports/status?year=2026&month=1&tenantCode=ACQ
 */
@RestController
@RequestMapping("/api/external/reports")
public class ExternalReportApiController {

    private static final Logger log = LoggerFactory.getLogger(ExternalReportApiController.class);

    private final TenantRepository tenantRepository;
    private final MerchantRepository merchantRepository;

    @Value("${pdf.reports.dir:reports}")
    private String reportsBaseDir;

    @Value("${external.api.key:}")
    private String configuredApiKey;

    public ExternalReportApiController(TenantRepository tenantRepository,
                                       MerchantRepository merchantRepository) {
        this.tenantRepository = tenantRepository;
        this.merchantRepository = merchantRepository;
    }

    // ─── API Key Validation ────────────────────────────────────────────

    private boolean validateApiKey(String apiKey) {
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            log.warn("[EXT-API] No external.api.key configured — rejecting all external requests");
            return false;
        }
        return configuredApiKey.equals(apiKey);
    }

    /**
     * Validate that the tenantCode actually exists and is active.
     * Prevents enumeration of tenant codes by returning same error for invalid/missing.
     */
    private boolean validateTenantAccess(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) return true; // no tenant filter = OK
        try {
            return tenantRepository.findAll().stream()
                .anyMatch(t -> tenantCode.equalsIgnoreCase(t.getBankShortCode()));
        } catch (Exception e) {
            log.warn("[EXT-API] Tenant validation failed for code '{}': {}", tenantCode, e.getMessage());
            return false;
        }
    }

    private Path getReportsRoot() {
        return Paths.get(reportsBaseDir).toAbsolutePath().normalize();
    }

    /**
     * Resolve folder: reports/{tenantCode}/{YYYY-MM}, fallback to reports/{YYYY-MM}
     */
    private Path resolveFolder(YearMonth ym, String tenantCode) {
        Path root = getReportsRoot();
        if (tenantCode != null && !tenantCode.isBlank()) {
            Path tenantPath = root.resolve(tenantCode).resolve(ym.toString());
            if (Files.exists(tenantPath)) return tenantPath;
        }
        // Fallback to flat folder
        Path flatPath = root.resolve(ym.toString());
        if (Files.exists(flatPath)) return flatPath;
        // Return tenant path as expected location
        if (tenantCode != null && !tenantCode.isBlank()) {
            return root.resolve(tenantCode).resolve(ym.toString());
        }
        return flatPath;
    }

    // ─── List Available Reports ────────────────────────────────────────

    @GetMapping("/list")
    public ResponseEntity<?> listReports(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String tenantCode) {

        if (!validateApiKey(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing API key"));
        }
        if (!validateTenantAccess(tenantCode)) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid tenant code"));
        }

        YearMonth targetMonth = resolveMonth(year, month);
        Path folder = resolveFolder(targetMonth, tenantCode);

        List<Map<String, Object>> reports = new ArrayList<>();
        if (Files.exists(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(p -> p.toString().endsWith(".pdf"))
                     .sorted()
                     .forEach(p -> {
                         Map<String, Object> entry = new LinkedHashMap<>();
                         entry.put("filename", p.getFileName().toString());
                         try {
                             entry.put("sizeBytes", Files.size(p));
                             entry.put("lastModified", Files.getLastModifiedTime(p).toString());
                         } catch (IOException ignored) {}
                         entry.put("downloadUrl", "/api/external/reports/download?file="
                             + p.getFileName().toString()
                             + "&year=" + targetMonth.getYear()
                             + "&month=" + targetMonth.getMonthValue()
                             + (tenantCode != null ? "&tenantCode=" + tenantCode : ""));
                         reports.add(entry);
                     });
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("targetMonth", targetMonth.toString());
        response.put("tenantCode", tenantCode);
        response.put("count", reports.size());
        response.put("reports", reports);
        return ResponseEntity.ok(response);
    }

    // ─── Download Single Report ────────────────────────────────────────

    @GetMapping("/download")
    public void downloadReport(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestParam String file,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String tenantCode,
            HttpServletResponse response) throws IOException {

        if (!validateApiKey(apiKey)) {
            response.sendError(401, "Invalid or missing API key");
            return;
        }
        if (!validateTenantAccess(tenantCode)) {
            response.sendError(403, "Invalid tenant code");
            return;
        }

        YearMonth targetMonth = resolveMonth(year, month);

        // Security: strip path traversal and validate filename
        String safeName = Paths.get(file).getFileName().toString();
        if (!safeName.endsWith(".pdf") || !safeName.matches("^[a-zA-Z0-9_\\-\\. ]+\\.pdf$")) {
            response.sendError(400, "Invalid filename — only alphanumeric characters, spaces, hyphens, underscores, dots allowed with .pdf extension");
            return;
        }

        Path folder = resolveFolder(targetMonth, tenantCode);
        Path filePath = folder.resolve(safeName);

        if (!Files.exists(filePath)) {
            log.warn("[EXT-API] Download 404: {} in {}", safeName, folder);
            response.sendError(404, "Report not found: " + safeName);
            return;
        }

        log.info("[EXT-API] Serving report: {} ({} bytes)", safeName, Files.size(filePath));
        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + safeName + "\"");
        response.setContentLengthLong(Files.size(filePath));
        Files.copy(filePath, response.getOutputStream());
        response.getOutputStream().flush();
    }

    // ─── Download All Reports as ZIP ───────────────────────────────────

    @GetMapping("/download-all")
    public void downloadAll(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String tenantCode,
            HttpServletResponse response) throws IOException {

        if (!validateApiKey(apiKey)) {
            response.sendError(401, "Invalid or missing API key");
            return;
        }
        if (!validateTenantAccess(tenantCode)) {
            response.sendError(403, "Invalid tenant code");
            return;
        }

        YearMonth targetMonth = resolveMonth(year, month);
        Path folder = resolveFolder(targetMonth, tenantCode);

        if (!Files.exists(folder)) {
            response.sendError(404, "No reports found for " + targetMonth);
            return;
        }

        log.info("[EXT-API] Serving all reports as ZIP for {}", targetMonth);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
            "attachment; filename=\"Reports_" + (tenantCode != null ? tenantCode + "_" : "") + targetMonth + ".zip\"");

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream());
             Stream<Path> files = Files.list(folder)) {
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

    // ─── Download by Merchant MID ──────────────────────────────────────

    @GetMapping("/merchant/{mid}")
    public void downloadByMid(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @PathVariable String mid,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String tenantCode,
            HttpServletResponse response) throws IOException {

        if (!validateApiKey(apiKey)) {
            response.sendError(401, "Invalid or missing API key");
            return;
        }
        if (!validateTenantAccess(tenantCode)) {
            response.sendError(403, "Invalid tenant code");
            return;
        }

        YearMonth targetMonth = resolveMonth(year, month);
        Path folder = resolveFolder(targetMonth, tenantCode);

        if (!Files.exists(folder)) {
            response.sendError(404, "No reports folder for " + targetMonth);
            return;
        }

        // Look up merchant name from MID to find the PDF
        String merchantName = null;
        try {
            var merchant = merchantRepository.findAll().stream()
                .filter(m -> mid.equals(m.getMid()) || mid.equals(String.valueOf(m.getMerchantId())))
                .findFirst().orElse(null);
            if (merchant != null && merchant.getName() != null) {
                merchantName = merchant.getName();
            }
        } catch (Exception e) {
            log.debug("[EXT-API] Could not resolve merchant name for MID {}: {}", mid, e.getMessage());
        }

        // Search for matching PDF in the folder
        Path matchedFile = null;
        try (Stream<Path> files = Files.list(folder)) {
            String searchMid = mid;
            String searchName = merchantName != null
                ? merchantName.replaceAll("[^a-zA-Z0-9.\\-]", "_")
                : null;

            matchedFile = files.filter(p -> p.toString().endsWith(".pdf"))
                .filter(p -> {
                    String fn = p.getFileName().toString();
                    if (searchName != null && fn.contains(searchName)) return true;
                    return fn.contains(searchMid);
                })
                .findFirst().orElse(null);
        }

        if (matchedFile == null || !Files.exists(matchedFile)) {
            response.sendError(404, "No report found for MID: " + mid + " in " + targetMonth);
            return;
        }

        log.info("[EXT-API] Serving report for MID {}: {}", mid, matchedFile.getFileName());
        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + matchedFile.getFileName() + "\"");
        response.setContentLengthLong(Files.size(matchedFile));
        Files.copy(matchedFile, response.getOutputStream());
        response.getOutputStream().flush();
    }

    // ─── Report Status ─────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String tenantCode) {

        if (!validateApiKey(apiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or missing API key"));
        }
        if (!validateTenantAccess(tenantCode)) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid tenant code"));
        }

        YearMonth targetMonth = resolveMonth(year, month);
        Path folder = resolveFolder(targetMonth, tenantCode);

        int count = 0;
        long totalSizeBytes = 0;
        if (Files.exists(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                var pdfList = files.filter(p -> p.toString().endsWith(".pdf")).toList();
                count = pdfList.size();
                for (Path p : pdfList) {
                    try { totalSizeBytes += Files.size(p); } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetMonth", targetMonth.toString());
        result.put("tenantCode", tenantCode);
        result.put("reportCount", count);
        result.put("totalSizeBytes", totalSizeBytes);
        result.put("totalSizeMB", String.format("%.1f", totalSizeBytes / 1024.0 / 1024.0));
        result.put("available", count > 0);
        return ResponseEntity.ok(result);
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private YearMonth resolveMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }
}
