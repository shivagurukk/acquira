package com.acquira.pdf.controller;

import com.acquira.common.repository.MerchantRepository;
import com.acquira.common.security.ApiKeyPrincipal;
import com.acquira.common.security.ApiScopes;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.*;
import java.util.stream.Stream;

/**
 * External API for 3rd-party systems to download merchant PDF reports.
 *
 * Authentication: X-API-Key, handled centrally by {@code ApiKeyAuthFilter} (acquira-core),
 * which runs ahead of this controller on {@code /api/external/**}. The filter resolves the
 * key to a tenant, sets {@code TenantContext}, and stashes an {@link ApiKeyPrincipal} on the
 * request. This controller no longer authenticates keys itself — it reads the principal and
 * asserts the {@code read:reports} scope.
 *
 * ── Tenant isolation ──────────────────────────────────────────────────────
 * The key IS the tenant boundary. {@code principal.getTenantCode()} (bankShortCode) is the
 * effective tenant; a client-supplied {@code tenantCode} may only match it (the filter already
 * rejects mismatches / widening). Folder layout: reports/{tenantCode}/{YYYY-MM}, fallback
 * reports/{YYYY-MM}. The static break-glass key path (all-tenant) is also handled by the filter.
 *
 * Endpoints:
 *   GET  /api/external/reports/list?year=2026&month=1
 *   GET  /api/external/reports/download?file=...&year=2026&month=1
 *   GET  /api/external/reports/download-all?year=2026&month=1
 *   GET  /api/external/reports/merchant/{mid}?year=2026&month=1
 *   GET  /api/external/reports/status?year=2026&month=1
 */
@RestController
@RequestMapping("/api/external/reports")
public class ExternalReportApiController {

    private static final Logger log = LoggerFactory.getLogger(ExternalReportApiController.class);

    private final MerchantRepository merchantRepository;

    @Value("${pdf.reports.dir:reports}")
    private String reportsBaseDir;

    public ExternalReportApiController(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    // ─── Principal + scope resolution ──────────────────────────────────

    /** Pull the filter-set principal; the filter guarantees it is present on authenticated requests. */
    private ApiKeyPrincipal principal(HttpServletRequest request) {
        Object p = request.getAttribute(ApiKeyPrincipal.ATTR);
        return (p instanceof ApiKeyPrincipal principal) ? principal : null;
    }

    private Path getReportsRoot() {
        return Paths.get(reportsBaseDir).toAbsolutePath().normalize();
    }

    /**
     * Resolve folder: reports/{tenantCode}/{YYYY-MM}, fallback to reports/{YYYY-MM}.
     * tenantCode comes from the key's own tenant (validated bankShortCode), so it cannot
     * contain path-traversal sequences.
     */
    private Path resolveFolder(YearMonth ym, String tenantCode) {
        Path root = getReportsRoot();
        if (tenantCode != null && !tenantCode.isBlank()) {
            Path tenantPath = root.resolve(tenantCode).resolve(ym.toString()).normalize();
            if (tenantPath.startsWith(root)) return tenantPath;
        }
        return root.resolve(ym.toString()).normalize();
    }

    // ─── List Available Reports ────────────────────────────────────────

    @GetMapping("/list")
    public ResponseEntity<?> listReports(
            HttpServletRequest request,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        ApiScopes.require(request, ApiScopes.READ_REPORTS);
        String effectiveTenant = principal(request).getTenantCode();

        YearMonth targetMonth = resolveMonth(year, month);
        Path folder = resolveFolder(targetMonth, effectiveTenant);

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
                             + "&month=" + targetMonth.getMonthValue());
                         reports.add(entry);
                     });
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(Map.of("error", "Could not list reports"));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("targetMonth", targetMonth.toString());
        response.put("tenantCode", effectiveTenant);
        response.put("count", reports.size());
        response.put("reports", reports);
        return ResponseEntity.ok(response);
    }

    // ─── Download Single Report ────────────────────────────────────────

    @GetMapping("/download")
    public void downloadReport(
            HttpServletRequest request,
            @RequestParam String file,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        try {
            ApiScopes.require(request, ApiScopes.READ_REPORTS);
        } catch (ApiScopes.InsufficientScopeException e) {
            response.sendError(403, e.getMessage());
            return;
        }
        String effectiveTenant = principal(request).getTenantCode();

        YearMonth targetMonth = resolveMonth(year, month);

        // Security: strip path traversal and validate filename
        String safeName = Paths.get(file).getFileName().toString();
        if (!safeName.endsWith(".pdf") || !safeName.matches("^[a-zA-Z0-9_\\-\\. ]+\\.pdf$")) {
            response.sendError(400, "Invalid filename — only alphanumeric characters, spaces, hyphens, underscores, dots allowed with .pdf extension");
            return;
        }

        Path folder = resolveFolder(targetMonth, effectiveTenant);
        Path filePath = folder.resolve(safeName).normalize();
        // Defence in depth: the resolved file must stay under the intended folder.
        if (!filePath.startsWith(folder)) {
            response.sendError(400, "Invalid filename");
            return;
        }

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
            HttpServletRequest request,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        try {
            ApiScopes.require(request, ApiScopes.READ_REPORTS);
        } catch (ApiScopes.InsufficientScopeException e) {
            response.sendError(403, e.getMessage());
            return;
        }
        String effectiveTenant = principal(request).getTenantCode();

        YearMonth targetMonth = resolveMonth(year, month);
        Path folder = resolveFolder(targetMonth, effectiveTenant);

        if (!Files.exists(folder)) {
            response.sendError(404, "No reports found for " + targetMonth);
            return;
        }

        log.info("[EXT-API] Serving all reports as ZIP for {} (tenant {})", targetMonth, effectiveTenant);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
            "attachment; filename=\"Reports_" + (effectiveTenant != null ? effectiveTenant + "_" : "") + targetMonth + ".zip\"");

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
            HttpServletRequest request,
            @PathVariable String mid,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            HttpServletResponse response) throws IOException {

        try {
            ApiScopes.require(request, ApiScopes.READ_REPORTS);
        } catch (ApiScopes.InsufficientScopeException e) {
            response.sendError(403, e.getMessage());
            return;
        }
        ApiKeyPrincipal p = principal(request);
        String effectiveTenant = p.getTenantCode();
        Long scopeTenantId = p.getTenantId();

        YearMonth targetMonth = resolveMonth(year, month);
        Path folder = resolveFolder(targetMonth, effectiveTenant);

        if (!Files.exists(folder)) {
            response.sendError(404, "No reports folder for " + targetMonth);
            return;
        }

        // Look up merchant name from MID to find the PDF — scoped to the key's tenant.
        String merchantName = null;
        try {
            var merchant = merchantRepository.findAll().stream()
                .filter(m -> scopeTenantId == null || scopeTenantId.equals(m.getTenantId()))
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

            matchedFile = files.filter(pp -> pp.toString().endsWith(".pdf"))
                .filter(pp -> {
                    String fn = pp.getFileName().toString();
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
            HttpServletRequest request,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        ApiScopes.require(request, ApiScopes.READ_REPORTS);
        String effectiveTenant = principal(request).getTenantCode();

        YearMonth targetMonth = resolveMonth(year, month);
        Path folder = resolveFolder(targetMonth, effectiveTenant);

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
                return ResponseEntity.internalServerError().body(Map.of("error", "Could not read report folder"));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetMonth", targetMonth.toString());
        result.put("tenantCode", effectiveTenant);
        result.put("reportCount", count);
        result.put("totalSizeBytes", totalSizeBytes);
        result.put("totalSizeMB", String.format("%.1f", totalSizeBytes / 1024.0 / 1024.0));
        result.put("available", count > 0);
        return ResponseEntity.ok(result);
    }

    // ─── Scope error mapping (for the ResponseEntity-returning endpoints) ──

    @ExceptionHandler(ApiScopes.InsufficientScopeException.class)
    public ResponseEntity<?> handleScope(ApiScopes.InsufficientScopeException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage(), "status", 403));
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private YearMonth resolveMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }
}
