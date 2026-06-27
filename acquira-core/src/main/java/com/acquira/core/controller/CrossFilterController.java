package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Click-to-cross-filter analytics. Backs the Interactive Explorer page.
 *
 * Source: sum_daily_insight — a pre-aggregated cross-tab carrying
 * card_scheme × card_type × destination × channel per merchant/store/terminal/date
 * (+ dim_store.mcc via join). Because it is pre-aggregated it stays fast on every
 * click, unlike scanning fact_transaction.
 *
 * Cross-filter semantics (self-exclusion): each dimension's breakdown applies every
 * ACTIVE filter EXCEPT its own — so selecting a scheme filters the channel/MCC/etc.
 * widgets while the scheme widget still lists all schemes (selected one highlighted).
 * Totals + timeline apply ALL active filters.
 *
 * NOTE: total_volume here is the cardholder-currency amount (sum_daily_insight stores
 * txn_currency_amount). Transactions and MSF are exact; "amount" is directional. The UI
 * defaults to Transactions for that reason.
 */
@RestController
@RequestMapping("/api/cross-filter")
@RequiredArgsConstructor
public class CrossFilterController {

    private final JdbcTemplate jdbcTemplate;

    private static final List<String> DIMS = List.of("scheme", "channel", "cardType", "destination", "mcc");
    private static final Map<String, String> DIM_COL = Map.of(
        "scheme", "i.card_scheme",
        "channel", "i.channel",
        "cardType", "i.card_type",
        "destination", "i.destination",
        "mcc", "COALESCE(s.mcc, 'UNKNOWN')"
    );

    private Long tenant() {
        Long t = TenantContext.getCurrentTenant();
        if (t == null) throw new RuntimeException("No tenant context");
        return t;
    }

    @GetMapping
    public ResponseEntity<?> crossFilter(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String schemes,
            @RequestParam(required = false) String channels,
            @RequestParam(required = false) String cardTypes,
            @RequestParam(required = false) String destinations,
            @RequestParam(required = false) String mccs) {

        Long tenantId = tenant();
        boolean hasRange = notBlank(dateFrom) && notBlank(dateTo);

        Map<String, List<String>> filters = new LinkedHashMap<>();
        filters.put("scheme", csv(schemes));
        filters.put("channel", csv(channels));
        filters.put("cardType", csv(cardTypes));
        filters.put("destination", csv(destinations));
        filters.put("mcc", csv(mccs));

        Map<String, Object> dims = new LinkedHashMap<>();
        for (String d : DIMS) {
            dims.put(d, breakdown(tenantId, d, filters, hasRange, dateFrom, dateTo));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dimensions", dims);
        resp.put("timeline", timeline(tenantId, filters, hasRange, dateFrom, dateTo));
        resp.put("totals", totals(tenantId, filters, hasRange, dateFrom, dateTo));
        return ResponseEntity.ok(resp);
    }

    // ── breakdown for one dimension (excludes that dimension's own filter) ──
    private List<Map<String, Object>> breakdown(Long tenantId, String dim, Map<String, List<String>> filters,
            boolean hasRange, String dateFrom, String dateTo) {
        String col = DIM_COL.get(dim);
        List<Object> params = new ArrayList<>();
        String where = buildWhere(dim, filters, hasRange, dateFrom, dateTo, tenantId, params);
        boolean needStore = dim.equals("mcc") || !filters.get("mcc").isEmpty();
        String join = "FROM sum_daily_insight i "
            + (needStore ? "LEFT JOIN dim_store s ON s.store_id = i.store_id AND s.tenant_id = i.tenant_id " : "");
        String sql = "SELECT " + col + " AS value, "
            + "COALESCE(SUM(i.total_volume),0) AS volume, COALESCE(SUM(i.total_txns),0) AS txns, COALESCE(SUM(i.total_msf),0) AS msf "
            + join + where + " GROUP BY " + col + " ORDER BY txns DESC"
            + (dim.equals("mcc") ? " LIMIT 15" : "");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        for (Map<String, Object> r : rows) {
            if (r.get("value") == null || String.valueOf(r.get("value")).isBlank()) r.put("value", "UNKNOWN");
        }
        return rows;
    }

    private List<Map<String, Object>> timeline(Long tenantId, Map<String, List<String>> filters,
            boolean hasRange, String dateFrom, String dateTo) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(null, filters, hasRange, dateFrom, dateTo, tenantId, params);
        boolean needStore = !filters.get("mcc").isEmpty();
        String join = "FROM sum_daily_insight i "
            + (needStore ? "LEFT JOIN dim_store s ON s.store_id = i.store_id AND s.tenant_id = i.tenant_id " : "");
        String sql = "SELECT i.business_date AS date, COALESCE(SUM(i.total_volume),0) AS volume, "
            + "COALESCE(SUM(i.total_txns),0) AS txns, COALESCE(SUM(i.total_msf),0) AS msf "
            + join + where + " GROUP BY i.business_date ORDER BY i.business_date";
        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    private Map<String, Object> totals(Long tenantId, Map<String, List<String>> filters,
            boolean hasRange, String dateFrom, String dateTo) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(null, filters, hasRange, dateFrom, dateTo, tenantId, params);
        boolean needStore = !filters.get("mcc").isEmpty();
        String join = "FROM sum_daily_insight i "
            + (needStore ? "LEFT JOIN dim_store s ON s.store_id = i.store_id AND s.tenant_id = i.tenant_id " : "");
        String sql = "SELECT COALESCE(SUM(i.total_volume),0) AS volume, COALESCE(SUM(i.total_txns),0) AS txns, "
            + "COALESCE(SUM(i.total_msf),0) AS msf, COUNT(DISTINCT i.merchant_id) AS merchants "
            + join + where;
        return jdbcTemplate.queryForMap(sql, params.toArray());
    }

    /** WHERE applying tenant + range + all active filters except {@code excludeDim} (null = none). */
    private String buildWhere(String excludeDim, Map<String, List<String>> filters,
            boolean hasRange, String dateFrom, String dateTo, Long tenantId, List<Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE i.tenant_id = ? ");
        params.add(tenantId);
        if (hasRange) {
            sb.append(" AND i.business_date BETWEEN ?::date AND ?::date ");
            params.add(dateFrom);
            params.add(dateTo);
        }
        for (Map.Entry<String, List<String>> e : filters.entrySet()) {
            if (e.getKey().equals(excludeDim)) continue;
            List<String> vals = e.getValue();
            if (vals.isEmpty()) continue;
            sb.append(" AND ").append(DIM_COL.get(e.getKey())).append(" IN (")
              .append(String.join(",", Collections.nCopies(vals.size(), "?"))).append(") ");
            params.addAll(vals);
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) { String v = p.trim(); if (!v.isEmpty()) out.add(v); }
        return out;
    }
}
