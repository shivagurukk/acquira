package com.acquira.core.controller;

import com.acquira.common.security.ApiKeyPrincipal;
import com.acquira.common.security.ApiScopes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * External Data API (v1) — read-only JSON products for 3rd-party integrators.
 *
 * Auth: X-API-Key, resolved by ApiKeyAuthFilter into an ApiKeyPrincipal + TenantContext.
 * The tenant is taken from the KEY (principal.getTenantId()), never a request param, and
 * is pushed onto every base query + carried onto every dim join.
 *
 * Data sourcing follows the platform rules:
 *   - bank-level unfiltered KPIs/trends → sum_daily_bank
 *   - dimension-filtered (scheme/type) → sum_daily_insight (interchange/scheme/VAT are 0 there)
 *   - per-merchant volume → sum_daily_merchant.total_base_volume (settlement, single-currency)
 *   - raw transaction rows → fact_transaction (date range required + capped)
 *
 * Base path: /api/v1
 */
@RestController
@RequestMapping("/api/v1")
public class ExternalDataApiController {

    private static final Logger log = LoggerFactory.getLogger(ExternalDataApiController.class);

    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_TXN_WINDOW_DAYS = 92;   // protects the ~18B-row fact table

    private final JdbcTemplate jdbc;

    public ExternalDataApiController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ═══════════════════════════════════════════════════════════
    //  MERCHANTS  — dim_merchant (+ store count)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/merchants")
    public ResponseEntity<?> listMerchants(HttpServletRequest request,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        ApiScopes.require(request, ApiScopes.READ_MERCHANTS);
        Long tenantId = tenantId(request);
        int lim = clampSize(size);
        int offset = Math.max(0, page) * lim;

        StringBuilder where = new StringBuilder("WHERE m.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) { where.append(" AND UPPER(m.status) = UPPER(?)"); args.add(status); }
        if (search != null && !search.isBlank()) {
            where.append(" AND (m.name ILIKE ? OR m.mid ILIKE ?)");
            args.add("%" + search + "%"); args.add("%" + search + "%");
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dim_merchant m " + where, Long.class, args.toArray());

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(lim); rowArgs.add(offset);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.merchant_id, m.mid, m.name, m.status, m.mcc, m.city, m.risk_level, " +
                "  (SELECT COUNT(*) FROM dim_store s WHERE s.merchant_id = m.merchant_id AND s.tenant_id = m.tenant_id) AS store_count " +
                "FROM dim_merchant m " + where + " ORDER BY m.name ASC LIMIT ? OFFSET ?",
                rowArgs.toArray());

        return ResponseEntity.ok(page(rows, total, page, lim));
    }

    @GetMapping("/merchants/{mid}/summary")
    public ResponseEntity<?> merchantSummary(HttpServletRequest request,
                                             @PathVariable String mid,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate) {
        ApiScopes.require(request, ApiScopes.READ_MERCHANTS);
        Long tenantId = tenantId(request);
        LocalDate[] range = defaultMonthRange(startDate, endDate);

        // Resolve merchant within the key's tenant.
        List<Map<String, Object>> mm = jdbc.queryForList(
                "SELECT merchant_id, mid, name, status FROM dim_merchant " +
                "WHERE tenant_id = ? AND (mid = ? OR CAST(merchant_id AS TEXT) = ?) LIMIT 1",
                tenantId, mid, mid);
        if (mm.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Merchant not found: " + mid));
        Map<String, Object> merchant = mm.get(0);
        Long merchantId = ((Number) merchant.get("merchant_id")).longValue();

        // Per-merchant settlement volume from sum_daily_merchant (total_base_volume — single currency).
        Map<String, Object> agg = jdbc.queryForMap(
                "SELECT COALESCE(SUM(total_txns),0) AS txns, " +
                "  COALESCE(SUM(total_base_volume),0) AS volume, " +
                "  COALESCE(SUM(total_msf),0) AS msf, " +
                "  COALESCE(SUM(total_interchange),0) AS interchange " +
                "FROM sum_daily_merchant " +
                "WHERE tenant_id = ? AND merchant_id = ? AND business_date BETWEEN ? AND ?",
                tenantId, merchantId, range[0], range[1]);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchant", merchant);
        out.put("startDate", range[0].toString());
        out.put("endDate", range[1].toString());
        out.put("currency", "BASE"); // settlement / store base currency
        out.put("totals", agg);
        return ResponseEntity.ok(out);
    }

    // ═══════════════════════════════════════════════════════════
    //  TRANSACTIONS  — fact_transaction (date range required + capped)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/transactions")
    public ResponseEntity<?> listTransactions(HttpServletRequest request,
                                              @RequestParam String startDate,
                                              @RequestParam String endDate,
                                              @RequestParam(required = false) String mid,
                                              @RequestParam(required = false) String sid,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "100") int size) {
        ApiScopes.require(request, ApiScopes.READ_TRANSACTIONS);
        Long tenantId = tenantId(request);

        LocalDate from, to;
        try { from = LocalDate.parse(startDate); to = LocalDate.parse(endDate); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "startDate/endDate must be YYYY-MM-DD")); }
        if (to.isBefore(from)) return ResponseEntity.badRequest().body(Map.of("error", "endDate must be on/after startDate"));
        if (ChronoUnit.DAYS.between(from, to) > MAX_TXN_WINDOW_DAYS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Date window too large — max " + MAX_TXN_WINDOW_DAYS + " days for /transactions"));
        }

        int lim = clampSize(size);
        int offset = Math.max(0, page) * lim;

        // JdbcTemplate binds ? positionally by their order IN THE SQL STRING. The JOIN
        // clauses appear BEFORE the WHERE clause in the assembled SQL, so their bind
        // values must be supplied first. Build join args separately and prepend them.
        List<Object> joinArgs = new ArrayList<>();
        String joins = "";
        if (mid != null && !mid.isBlank()) {
            joins += " JOIN dim_merchant m ON m.merchant_id = f.merchant_id AND m.tenant_id = f.tenant_id AND m.mid = ?";
            joinArgs.add(mid);
        }
        if (sid != null && !sid.isBlank()) {
            joins += " JOIN dim_store s ON s.store_id = f.store_id AND s.tenant_id = f.tenant_id AND s.sid = ?";
            joinArgs.add(sid);
        }

        String where = "WHERE f.tenant_id = ? AND f.payment_date >= ? AND f.payment_date < (?::date + INTERVAL '1 day')";

        // Final positional order: [join args...] then [where args...] then LIMIT/OFFSET.
        List<Object> rowArgs = new ArrayList<>(joinArgs);
        rowArgs.add(tenantId);
        rowArgs.add(java.sql.Date.valueOf(from));
        rowArgs.add(java.sql.Date.valueOf(to));
        rowArgs.add(lim);
        rowArgs.add(offset);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT f.transaction_id, f.merchant_id, f.store_id, f.terminal_id, f.arn, f.rrn_number, " +
                "  f.auth_code, f.payment_date, f.transaction_date, f.transaction_type, f.card_scheme, f.card_type, " +
                "  f.dcc, f.txn_currency, f.txn_currency_amount, f.store_base_currency, f.store_base_currency_amount, " +
                "  f.msf, f.vat, f.total_amount_settled, f.interchange_fee, f.destination " +
                "FROM fact_transaction f" + joins + " " + where +
                " ORDER BY f.payment_date DESC, f.transaction_id DESC LIMIT ? OFFSET ?",
                rowArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", from.toString());
        out.put("endDate", to.toString());
        out.put("page", page);
        out.put("size", lim);
        out.put("count", rows.size());
        out.put("transactions", rows);
        return ResponseEntity.ok(out);
    }

    // ═══════════════════════════════════════════════════════════
    //  ANALYTICS
    // ═══════════════════════════════════════════════════════════

    /** Volume trend. groupBy=day|month → sum_daily_bank (unfiltered); groupBy=scheme → sum_daily_insight. */
    @GetMapping("/analytics/volume")
    public ResponseEntity<?> analyticsVolume(HttpServletRequest request,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate,
                                             @RequestParam(defaultValue = "day") String groupBy) {
        ApiScopes.require(request, ApiScopes.READ_ANALYTICS);
        Long tenantId = tenantId(request);
        LocalDate[] range = defaultMonthRange(startDate, endDate);
        String gb = groupBy == null ? "day" : groupBy.toLowerCase();

        List<Map<String, Object>> series;
        switch (gb) {
            case "scheme" -> series = jdbc.queryForList(
                    "SELECT card_scheme AS label, SUM(total_txns) AS txns, SUM(total_volume) AS volume, SUM(total_msf) AS msf " +
                    "FROM sum_daily_insight WHERE tenant_id = ? AND business_date BETWEEN ? AND ? " +
                    "GROUP BY card_scheme ORDER BY volume DESC",
                    tenantId, range[0], range[1]);
            case "month" -> series = jdbc.queryForList(
                    "SELECT TO_CHAR(business_date, 'YYYY-MM') AS label, SUM(total_txns) AS txns, " +
                    "  SUM(total_volume) AS volume, SUM(total_msf) AS msf, SUM(total_net_revenue) AS net_revenue " +
                    "FROM sum_daily_bank WHERE tenant_id = ? AND business_date BETWEEN ? AND ? " +
                    "GROUP BY TO_CHAR(business_date, 'YYYY-MM') ORDER BY label",
                    tenantId, range[0], range[1]);
            default -> series = jdbc.queryForList(
                    "SELECT business_date AS label, total_txns AS txns, total_volume AS volume, " +
                    "  total_msf AS msf, total_net_revenue AS net_revenue " +
                    "FROM sum_daily_bank WHERE tenant_id = ? AND business_date BETWEEN ? AND ? " +
                    "ORDER BY business_date",
                    tenantId, range[0], range[1]);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", range[0].toString());
        out.put("endDate", range[1].toString());
        out.put("groupBy", gb);
        out.put("series", series);
        return ResponseEntity.ok(out);
    }

    /** Scheme × card-type breakdown — sum_daily_insight. */
    @GetMapping("/analytics/scheme-breakdown")
    public ResponseEntity<?> schemeBreakdown(HttpServletRequest request,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate) {
        ApiScopes.require(request, ApiScopes.READ_ANALYTICS);
        Long tenantId = tenantId(request);
        LocalDate[] range = defaultMonthRange(startDate, endDate);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT card_scheme, card_type, SUM(total_txns) AS txns, SUM(total_volume) AS volume, SUM(total_msf) AS msf " +
                "FROM sum_daily_insight WHERE tenant_id = ? AND business_date BETWEEN ? AND ? " +
                "GROUP BY card_scheme, card_type ORDER BY volume DESC",
                tenantId, range[0], range[1]);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", range[0].toString());
        out.put("endDate", range[1].toString());
        out.put("breakdown", rows);
        return ResponseEntity.ok(out);
    }

    // ═══════════════════════════════════════════════════════════
    //  FINANCE  — sum_daily_bank (interchange/scheme/VAT/net_revenue accurate here)
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/finance/summary")
    public ResponseEntity<?> financeSummary(HttpServletRequest request,
                                            @RequestParam(required = false) String startDate,
                                            @RequestParam(required = false) String endDate) {
        ApiScopes.require(request, ApiScopes.READ_FINANCE);
        Long tenantId = tenantId(request);
        LocalDate[] range = defaultMonthRange(startDate, endDate);

        Map<String, Object> agg = jdbc.queryForMap(
                "SELECT COALESCE(SUM(total_txns),0) AS txns, " +
                "  COALESCE(SUM(total_volume),0) AS volume, " +
                "  COALESCE(SUM(total_msf),0) AS msf, " +
                "  COALESCE(SUM(total_interchange),0) AS interchange, " +
                "  COALESCE(SUM(total_scheme_fee),0) AS scheme_fee, " +
                "  COALESCE(SUM(total_vat),0) AS vat, " +
                "  COALESCE(SUM(total_net_revenue),0) AS net_revenue " +
                "FROM sum_daily_bank WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
                tenantId, range[0], range[1]);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", range[0].toString());
        out.put("endDate", range[1].toString());
        out.put("totals", agg);
        return ResponseEntity.ok(out);
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private Long tenantId(HttpServletRequest request) {
        ApiKeyPrincipal p = (ApiKeyPrincipal) request.getAttribute(ApiKeyPrincipal.ATTR);
        return p != null ? p.getTenantId() : null;
    }

    private int clampSize(int size) {
        if (size <= 0) return 50;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /** Default to the current month-to-date when no range supplied. */
    private LocalDate[] defaultMonthRange(String startDate, String endDate) {
        LocalDate from, to;
        try { from = startDate != null && !startDate.isBlank() ? LocalDate.parse(startDate) : LocalDate.now().withDayOfMonth(1); }
        catch (Exception e) { from = LocalDate.now().withDayOfMonth(1); }
        try { to = endDate != null && !endDate.isBlank() ? LocalDate.parse(endDate) : LocalDate.now(); }
        catch (Exception e) { to = LocalDate.now(); }
        if (to.isBefore(from)) to = from;
        return new LocalDate[]{ from, to };
    }

    private Map<String, Object> page(List<Map<String, Object>> rows, Long total, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page);
        out.put("size", size);
        out.put("totalElements", total != null ? total : rows.size());
        out.put("count", rows.size());
        out.put("content", rows);
        return out;
    }

    // ═══════════════════════════════════════════════════════════
    //  Error mapping — scope failures → 403
    // ═══════════════════════════════════════════════════════════

    @ExceptionHandler(ApiScopes.InsufficientScopeException.class)
    public ResponseEntity<?> handleScope(ApiScopes.InsufficientScopeException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage(), "status", 403));
    }
}
