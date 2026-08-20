package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.LocalDebitBankDashboardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local Debit Bank Dashboard (/business/local-debit-bank-dashboard) —
 * DOMESTIC DEBIT traffic split by issuing bank, resolved from the card BIN
 * via the tenant-uploaded ref_tenant_bin_bank list. Also hosts that list's
 * upload/list/delete endpoints: the BIN->bank mapping is dashboard-owned,
 * tenant-scoped reference data, deliberately separate from the global
 * ref_bin/ref_bin_range managed on the BIN Management screen (which this
 * page never consults).
 *
 * Upload contract (user decisions 2026-08-20):
 *  - CSV/Excel with columns BIN + BANK (header variants accepted).
 *  - 6- or 8-digit BINs; 8-digit are truncated to their 6-digit prefix
 *    (the feed only leaves the first 6 PAN digits clear, so 8 digits can
 *    never match anyway). Same-prefix collisions across DIFFERENT banks keep
 *    the first occurrence and are reported in the response.
 *  - Bank names come EXCLUSIVELY from this table; unmatched local-debit BINs
 *    render as the 'Other Banks' bucket, never dropped.
 */
@RestController
@RequestMapping("/api/business/local-debit-bank-dashboard")
// Menu-grant gate, same as every other business screen — the sidebar entry
// and this API are driven by the same sys_group_menu grant.
@PreAuthorize("@menuAccess.canAccess('/business/local-debit-bank-dashboard')")
public class LocalDebitBankDashboardController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LocalDebitBankDashboardController.class);

    @Autowired
    private LocalDebitBankDashboardRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

    private Long requireTenant() {
        Long tenantId = tenantService.getCurrentTenantId();
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved");
        return tenantId;
    }

    // Same resolveFilters convention as BusinessAnalyticsController —
    // duplicated locally (rather than shared) to keep this feature additive
    // and isolated from the existing controllers.
    private void resolveFilters(VolumeRevenueFilterDTO filters) {
        if (filters.getTeamLeaderList() != null && !filters.getTeamLeaderList().isEmpty()) {
            Long tenantId = tenantService.getCurrentTenantId();
            if (tenantId != null) {
                List<String> salesUserIds = salesTeamService.getSalesUserIdsByTeamLeadNames(tenantId,
                        filters.getTeamLeaderList());
                if (salesUserIds.isEmpty()) {
                    filters.setTeamLeaderList(java.util.Collections.singletonList("__NO_MATCH__"));
                } else {
                    filters.setTeamLeaderList(salesUserIds);
                }
            }
        }
    }

    /**
     * Server-side date defaults: without them a missing range scans every
     * partition back to 2024.
     */
    private static void defaultDates(VolumeRevenueFilterDTO f, int defaultDays) {
        if (f.getEndDate() == null) f.setEndDate(java.time.LocalDate.now());
        if (f.getStartDate() == null) f.setStartDate(f.getEndDate().minusDays(defaultDays));
    }

    // ─── dashboard reads ───────────────────────────────────────────────

    /** MIN/MAX business_date in sum_daily_local_debit_bin — the page anchors its presets here. */
    @GetMapping("/bounds")
    public Map<String, Object> getBounds() {
        return repository.getBounds(requireTenant());
    }

    @PostMapping("/kpis")
    public Map<String, Object> getKpis(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        return repository.getKpis(filters, requireTenant());
    }

    @PostMapping("/trend")
    public List<Map<String, Object>> getTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters, 365); // 12 months of monthly buckets
        return repository.getTrend(filters, requireTenant());
    }

    @PostMapping("/daily-trend")
    public List<Map<String, Object>> getDailyTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters, 30);
        return repository.getDailyTrend(filters, requireTenant());
    }

    @PostMapping("/top-merchants")
    public List<Map<String, Object>> getTopMerchants(@RequestBody VolumeRevenueFilterDTO filters,
                                                     @RequestParam(required = false) String bank,
                                                     @RequestParam(defaultValue = "25") int limit) {
        resolveFilters(filters);
        defaultDates(filters, 30);
        return repository.getTopMerchants(filters, requireTenant(), bank, Math.min(Math.max(limit, 1), 100));
    }

    /** Injection worklist: top unmatched local-debit BINs by volume. */
    @PostMapping("/unmatched-bins")
    public List<Map<String, Object>> getUnmatchedBins(@RequestBody VolumeRevenueFilterDTO filters,
                                                      @RequestParam(defaultValue = "50") int limit) {
        defaultDates(filters, 30);
        return repository.getUnmatchedBins(filters, requireTenant(), Math.min(Math.max(limit, 1), 500));
    }

    // ─── tenant BIN->bank list management ──────────────────────────────

    @GetMapping("/bins")
    public List<Map<String, Object>> listBins() {
        Long tenantId = requireTenant();
        return jdbcTemplate.queryForList(
                "SELECT bin, bank_name, source_file, loaded_at FROM ref_tenant_bin_bank " +
                "WHERE tenant_id = ? ORDER BY bank_name, bin", tenantId);
    }

    /**
     * Upload the tenant's BIN->bank list (CSV or Excel; columns BIN + BANK).
     * mode=REPLACE (default) clears the tenant's existing list first;
     * mode=APPEND upserts on top of it. The tenant always comes from the
     * session — never from the file.
     */
    @PostMapping("/bins")
    public ResponseEntity<Map<String, Object>> uploadBins(@RequestParam("file") MultipartFile file,
                                                          @RequestParam(defaultValue = "REPLACE") String mode)
            throws Exception {
        Long tenantId = requireTenant();
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String lower = name.toLowerCase();
        boolean excel = lower.endsWith(".xlsx") || lower.endsWith(".xls");

        List<String[]> rows;
        try {
            rows = excel ? readExcel(file) : readCsv(file);
        } catch (IllegalArgumentException e) {
            log.warn("Tenant BIN upload {} rejected: {}", name, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (rows.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File has no rows"));
        }

        Map<String, Integer> col = headerIndex(rows.get(0));
        Integer binIdx = firstOf(col, "BIN", "BINS", "BIN_NUMBER", "BIN6");
        Integer bankIdx = firstOf(col, "BANK", "BANK_NAME", "ISSUER", "ISSUER_NAME", "ISSUING_BANK");
        int dataStart = 1;
        if (binIdx == null || bankIdx == null) {
            // Headerless two-column file (BIN,BANK or BANK,BIN) — accept if
            // exactly one of the first two cells is a 6/8-digit number.
            String[] first = rows.get(0);
            String c0 = first.length > 0 ? first[0].trim().replaceFirst("\\.0+$", "") : "";
            String c1 = first.length > 1 ? first[1].trim().replaceFirst("\\.0+$", "") : "";
            if (c0.matches("\\d{6}|\\d{8}") && !c1.matches("\\d{6}|\\d{8}")) {
                binIdx = 0; bankIdx = 1; dataStart = 0;
            } else if (c1.matches("\\d{6}|\\d{8}") && !c0.matches("\\d{6}|\\d{8}")) {
                binIdx = 1; bankIdx = 0; dataStart = 0;
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Could not find BIN and BANK columns. Expected headers: BIN, BANK "
                                + "(variants BANK_NAME/ISSUER accepted), or a headerless 2-column BIN,BANK file."));
            }
        }

        // Parse: 6 digits kept as-is; 8 digits truncated to the 6-prefix.
        // First occurrence of a prefix wins; a later row mapping the SAME
        // prefix to a DIFFERENT bank is recorded as a collision (user rule:
        // same 6-prefix is almost always the same bank — assigning to either
        // is acceptable, but surface it for review).
        Map<String, String> byBin = new LinkedHashMap<>();
        List<String> rejects = new ArrayList<>();
        List<String> collisions = new ArrayList<>();
        int truncated = 0;
        for (int i = dataStart; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (rowEmpty(r)) continue;
            String bin = val(r, binIdx).replaceFirst("\\.0+$", "").trim();
            String bank = val(r, bankIdx).trim();
            if (!bin.matches("\\d{6}|\\d{8}")) {
                if (rejects.size() < 20) rejects.add("row " + (i + 1) + ": BIN '" + bin + "' is not 6 or 8 digits");
                continue;
            }
            if (bank.isEmpty()) {
                if (rejects.size() < 20) rejects.add("row " + (i + 1) + ": BIN " + bin + " has no bank name");
                continue;
            }
            if (bin.length() == 8) { bin = bin.substring(0, 6); truncated++; }
            String existing = byBin.get(bin);
            if (existing == null) {
                byBin.put(bin, bank);
            } else if (!existing.equalsIgnoreCase(bank) && collisions.size() < 20) {
                collisions.add("BIN prefix " + bin + ": kept '" + existing + "', ignored '" + bank + "'");
            }
        }
        if (byBin.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No valid BIN rows found",
                    "rejectSamples", rejects));
        }

        boolean replace = !"APPEND".equalsIgnoreCase(mode);
        if (replace) {
            jdbcTemplate.update("DELETE FROM ref_tenant_bin_bank WHERE tenant_id = ?", tenantId);
        }
        List<Object[]> batch = new ArrayList<>();
        for (Map.Entry<String, String> e : byBin.entrySet())
            batch.add(new Object[]{tenantId, e.getKey(), e.getValue(), name});
        jdbcTemplate.batchUpdate(
                "INSERT INTO ref_tenant_bin_bank (tenant_id, bin, bank_name, source_file, loaded_at) " +
                "VALUES (?,?,?,?,CURRENT_TIMESTAMP) " +
                "ON CONFLICT (tenant_id, bin) DO UPDATE SET bank_name=EXCLUDED.bank_name, " +
                "source_file=EXCLUDED.source_file, loaded_at=CURRENT_TIMESTAMP",
                batch);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", name);
        out.put("mode", replace ? "REPLACE" : "APPEND");
        out.put("loaded", byBin.size());
        out.put("truncatedFrom8Digits", truncated);
        out.put("rejected", rejects.size());
        if (!rejects.isEmpty()) out.put("rejectSamples", rejects);
        if (!collisions.isEmpty()) out.put("prefixCollisions", collisions);
        out.put("totalBins", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ref_tenant_bin_bank WHERE tenant_id = ?", Long.class, tenantId));
        out.put("distinctBanks", jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT bank_name) FROM ref_tenant_bin_bank WHERE tenant_id = ?", Long.class, tenantId));
        log.info("Tenant {} BIN list upload '{}': {} bins ({} banks), {} rejected, {} collisions",
                tenantId, name, byBin.size(), out.get("distinctBanks"), rejects.size(), collisions.size());
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/bins/{bin}")
    public ResponseEntity<Map<String, Object>> deleteBin(@PathVariable String bin) {
        Long tenantId = requireTenant();
        if (!bin.matches("\\d{6}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "BIN must be 6 digits"));
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM ref_tenant_bin_bank WHERE tenant_id = ? AND bin = ?", tenantId, bin);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @DeleteMapping("/bins")
    public ResponseEntity<Map<String, Object>> clearBins() {
        Long tenantId = requireTenant();
        int deleted = jdbcTemplate.update(
                "DELETE FROM ref_tenant_bin_bank WHERE tenant_id = ?", tenantId);
        log.info("Tenant {} BIN list cleared: {} rows", tenantId, deleted);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    // ─── parsing helpers (same shapes as BinManagementController) ──────

    private static List<String[]> readCsv(MultipartFile file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (var br = new java.io.BufferedReader(
                new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                // A newline-free binary file makes readLine() accumulate the
                // whole upload into one string — cap it instead of OOMing.
                if (line.length() > 200_000) {
                    throw new IllegalArgumentException(
                            "File does not look like a CSV (a single line exceeds 200KB — binary upload?)");
                }
                if (line.isBlank()) continue;
                rows.add(splitCsvLine(line));
            }
        }
        return rows;
    }

    /** Minimal CSV split with double-quote support (BIN files are simple grids). */
    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQ = !inQ;
            } else if (c == ',' && !inQ) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static List<String[]> readExcel(MultipartFile file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream())) {
            var sheet = wb.getSheetAt(0);
            var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                short last = row.getLastCellNum();
                if (last < 0) continue;
                String[] cells = new String[last];
                boolean any = false;
                for (int c = 0; c < last; c++) {
                    var cell = row.getCell(c);
                    cells[c] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    if (!cells[c].isEmpty()) any = true;
                }
                if (any) rows.add(cells);
            }
        }
        return rows;
    }

    private static Map<String, Integer> headerIndex(String[] header) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            String key = header[i] == null ? "" : header[i].trim().toUpperCase().replaceAll("[\\s-]+", "_");
            if (!key.isEmpty()) idx.putIfAbsent(key, i);
        }
        return idx;
    }

    private static Integer firstOf(Map<String, Integer> col, String... names) {
        for (String n : names) if (col.containsKey(n)) return col.get(n);
        return null;
    }

    private static String val(String[] row, Integer idx) {
        if (idx == null || idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    private static boolean rowEmpty(String[] row) {
        for (String c : row) if (c != null && !c.trim().isEmpty()) return false;
        return true;
    }
}
