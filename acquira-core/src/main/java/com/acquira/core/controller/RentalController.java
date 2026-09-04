package com.acquira.core.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rentals screen (/business/rentals) — terminal/store/merchant rental charges
 * from the DEDICATED rental feed (fact_rental), kept fully separate from the
 * transaction pipeline.
 *
 * The available levels are tenant-driven: CMM-format tenants receive rentals
 * at store (SID) level only, AMS-format tenants at merchant/store/terminal
 * (MID/SID/TID). The level of each charge was DERIVED at ingest from which
 * ids the feed row carried — see RentalJobConfig.
 *
 * Exceptions (REJECTED id combinations, UNMATCHED ids the dims don't know)
 * are read from stg_rental_raw, which keeps the latest load's rows per tenant.
 */
@RestController
@RequestMapping("/api/business/rentals")
// Menu-grant gate, same as every other business screen — the sidebar entry
// and this API are driven by the same sys_group_menu grant.
@PreAuthorize("@menuAccess.canAccess('/business/rentals')")
public class RentalController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

    private static final List<String> LEVELS = List.of("MERCHANT", "STORE", "TERMINAL");

    private Long requireTenant() {
        Long tenantId = tenantService.getCurrentTenantId();
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved");
        return tenantId;
    }

    /** CMM => store level only; AMS => all three levels. */
    private List<String> levelsForTenant(Long tenantId) {
        String inputFormat;
        try {
            inputFormat = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(input_format,'CMM') FROM tenant WHERE tenant_id = ?",
                    String.class, tenantId);
        } catch (Exception e) {
            inputFormat = "CMM";
        }
        return "AMS".equalsIgnoreCase(inputFormat) ? LEVELS : List.of("STORE");
    }

    /** Default window: current month to date. */
    private LocalDate[] window(LocalDate from, LocalDate to) {
        LocalDate end = (to != null) ? to : LocalDate.now();
        LocalDate start = (from != null) ? from : end.withDayOfMonth(1);
        return new LocalDate[]{start, end};
    }

    private String requireLevel(String level) {
        String l = level == null ? "" : level.trim().toUpperCase();
        if (!LEVELS.contains(l))
            throw new IllegalArgumentException("level must be one of " + LEVELS);
        return l;
    }

    // ─── overview ──────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public Map<String, Object> overview(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to) {
        Long tenantId = requireTenant();
        LocalDate[] w = window(from, to);

        List<Map<String, Object>> perLevel = jdbcTemplate.queryForList(
            "SELECT level, COUNT(*) AS charge_count, COALESCE(SUM(rental_amount),0) AS total_amount, "
            + "COUNT(DISTINCT COALESCE(terminal_id, store_id, merchant_id)) AS entity_count "
            + "FROM fact_rental WHERE tenant_id = ? AND payment_date BETWEEN ? AND ? "
            + "GROUP BY level", tenantId, w[0], w[1]);

        Map<String, Object> exceptions = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected, "
            + "COUNT(*) FILTER (WHERE status = 'UNMATCHED') AS unmatched, "
            + "COUNT(*) FILTER (WHERE status = 'DUPLICATE') AS duplicate "
            + "FROM stg_rental_raw WHERE tenant_id = ?", tenantId);

        Map<String, Object> lastLoad = jdbcTemplate.queryForMap(
            "SELECT MAX(load_time) AS last_load_time, COUNT(*) AS staged_rows "
            + "FROM stg_rental_raw WHERE tenant_id = ?", tenantId);

        Map<String, Object> bounds = jdbcTemplate.queryForMap(
            "SELECT MIN(payment_date) AS min_date, MAX(payment_date) AS max_date "
            + "FROM fact_rental WHERE tenant_id = ?", tenantId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("levels", levelsForTenant(tenantId));
        out.put("from", w[0].toString());
        out.put("to", w[1].toString());
        out.put("perLevel", perLevel);
        out.put("exceptions", exceptions);
        out.put("lastLoad", lastLoad);
        out.put("bounds", bounds);
        return out;
    }

    // ─── charge list per level ─────────────────────────────────────────────

    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestParam String level,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long tenantId = requireTenant();
        String l = requireLevel(level);
        LocalDate[] w = window(from, to);
        int pageSize = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page, 0) * pageSize;

        List<Object> params = new ArrayList<>(List.of(tenantId, w[0], w[1], l));
        String searchClause = "";
        if (search != null && !search.isBlank()) {
            searchClause = " AND (f.mid ILIKE ? OR f.sid ILIKE ? OR f.tid ILIKE ? "
                    + "OR dm.name ILIKE ? OR ds.name ILIKE ?)";
            String like = "%" + search.trim() + "%";
            for (int i = 0; i < 5; i++) params.add(like);
        }

        String baseSql =
            "FROM fact_rental f "
            + "LEFT JOIN dim_merchant dm ON dm.merchant_id = f.merchant_id "
            + "LEFT JOIN dim_store ds ON ds.store_id = f.store_id "
            + "LEFT JOIN dim_terminal dt ON dt.terminal_id = f.terminal_id "
            + "WHERE f.tenant_id = ? AND f.payment_date BETWEEN ? AND ? AND f.level = ?"
            + searchClause;

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + baseSql, Long.class, params.toArray());
        java.math.BigDecimal sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(f.rental_amount),0) " + baseSql, java.math.BigDecimal.class, params.toArray());

        params.add(pageSize);
        params.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT f.rental_id, f.level, f.mid, f.sid, f.tid, "
            + "dm.name AS merchant_name, ds.name AS store_name, dt.device_number AS terminal_device, "
            + "f.rental_amount, f.payment_date "
            + baseSql
            + " ORDER BY f.payment_date DESC, f.rental_id DESC LIMIT ? OFFSET ?",
            params.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("totalAmount", sum);
        out.put("page", page);
        out.put("size", pageSize);
        return out;
    }

    // ─── exceptions from the latest load ───────────────────────────────────

    @GetMapping("/exceptions")
    public List<Map<String, Object>> exceptions() {
        Long tenantId = requireTenant();
        return jdbcTemplate.queryForList(
            "SELECT raw_id, status, error_message, mid, sid, tid, level, rental_amount, payment_date, load_time "
            + "FROM stg_rental_raw WHERE tenant_id = ? AND status IN ('REJECTED','UNMATCHED','DUPLICATE') "
            + "ORDER BY status, raw_id LIMIT 500", tenantId);
    }

    // ─── CSV export ────────────────────────────────────────────────────────

    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @RequestParam String level,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to) {
        Long tenantId = requireTenant();
        String l = requireLevel(level);
        LocalDate[] w = window(from, to);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT f.mid, f.sid, f.tid, dm.name AS merchant_name, ds.name AS store_name, "
            + "f.rental_amount, f.payment_date "
            + "FROM fact_rental f "
            + "LEFT JOIN dim_merchant dm ON dm.merchant_id = f.merchant_id "
            + "LEFT JOIN dim_store ds ON ds.store_id = f.store_id "
            + "WHERE f.tenant_id = ? AND f.payment_date BETWEEN ? AND ? AND f.level = ? "
            + "ORDER BY f.payment_date DESC, f.rental_id DESC LIMIT 100000",
            tenantId, w[0], w[1], l);

        StringBuilder csv = new StringBuilder("MID,SID,TID,Merchant Name,Store Name,Rental Amount,Payment Date\n");
        for (Map<String, Object> r : rows) {
            csv.append(csvCell(r.get("mid"))).append(',')
               .append(csvCell(r.get("sid"))).append(',')
               .append(csvCell(r.get("tid"))).append(',')
               .append(csvCell(r.get("merchant_name"))).append(',')
               .append(csvCell(r.get("store_name"))).append(',')
               .append(csvCell(r.get("rental_amount"))).append(',')
               .append(csvCell(r.get("payment_date"))).append('\n');
        }

        byte[] body = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition",
                        "attachment; filename=rentals_" + l.toLowerCase() + "_" + w[0] + "_" + w[1] + ".csv")
                .body(body);
    }

    private static String csvCell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
