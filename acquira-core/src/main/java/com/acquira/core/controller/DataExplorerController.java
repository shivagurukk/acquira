package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Data Explorer API — Powers the Qlik-style drag-and-drop analytics page.
 *
 * 1. GET /api/explorer/options  — returns all distinct filter values per column
 * 2. POST /api/explorer/query   — dynamic pivot/aggregate query based on user-selected dimensions + measures
 *
 * HISTORY REWORK (2026-07-13)
 * ---------------------------
 * Previously this controller pivoted the STAGING tables (stg_merchant_master_raw /
 * stg_trnx_raw), which are truncated on every upload — so the page could only ever
 * show the LAST upload. It now reads permanent, historical sources:
 *
 *   source=merchant     -> dim_merchant / dim_store / dim_terminal (the merchant
 *                          universe — full history, tenant-scoped joins).
 *   source=transaction  -> sum_daily_explorer (new daily pre-aggregate at the
 *                          explorer grain: day x merchant x store x terminal x
 *                          transaction_type x scheme x card_type x destination x
 *                          channel x txn_currency x is_opt_in, carrying BOTH
 *                          cardholder and settlement amount bases plus msf / vat /
 *                          settled / interchange / scheme_fee). Populated by
 *                          populateSummaryStep; V2026_07_13_01 migration.
 *
 * MEASURE SEMANTICS over the pre-aggregate:
 *   SUM   -> exact (SUM of daily sums == SUM over fact).
 *   COUNT -> SUM(total_txns) — the true transaction count, NOT a row count of
 *            pre-aggregated rows.
 *   AVG   -> SUM(col) / NULLIF(SUM(total_txns), 0) — transaction-weighted average,
 *            NOT an average over daily rows (which would be wrong).
 *   MIN/MAX are rejected: per-transaction min/max is not recoverable from a daily
 *   pre-aggregate; use the Transactions page for row-level extremes.
 *
 * Row-level fields (arn, rrn, card_number, auth_code) were removed from the
 * whitelist — an aggregating explorer has no correct use for them; the
 * Transactions page owns row grain.
 *
 * Endpoint paths and request/response shapes are unchanged; only whitelists and
 * the underlying tables moved. Tenant is scoped on the base table and pushed onto
 * every dim join (P2-1 pattern).
 */
@RestController
@RequestMapping("/api/explorer")
public class DataExplorerController {

    private static final Logger log = LoggerFactory.getLogger(DataExplorerController.class);

    private final JdbcTemplate jdbcTemplate;

    public DataExplorerController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private Long getTenantId() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw new RuntimeException("Tenant context is missing");
        return tenantId;
    }

    // ═══════════════════════════════════════════════════════════
    // WHITELIST — Only these columns can be queried (prevents SQL injection)
    // ═══════════════════════════════════════════════════════════

    // Merchant columns → dim_merchant m / dim_store st / dim_terminal t
    // (base FROM is dim_merchant; store/terminal joins added on demand).
    // NOTE vs the old staging whitelist: aggregator_name, business_type,
    // customer_type, product, date_of_onboarding were staging-only fields that
    // never landed in the dims — dropped. risk_level and sales_user_id gained.
    private static final Map<String, String> MERCHANT_COLUMNS = Map.ofEntries(
        Map.entry("mid", "m.mid"),
        Map.entry("merchant_name", "m.name"),
        Map.entry("sid", "st.sid"),
        Map.entry("tid", "t.tid"),
        Map.entry("referral_partner", "m.referral_partner"),
        Map.entry("sales_user_email", "m.sales_email"),
        Map.entry("sales_user_id", "m.sales_user_id"),
        Map.entry("business_mcc", "st.mcc"),
        Map.entry("industry_type", "m.industry"),
        Map.entry("risk_level", "m.risk_level"),
        Map.entry("city", "st.city"),
        Map.entry("state", "st.state"),
        Map.entry("postal_code", "st.postal_code"),
        Map.entry("merchant_status", "m.status"),
        Map.entry("store_status", "st.status"),
        Map.entry("terminal_status", "t.status"),
        Map.entry("terminal_type", "t.type"),
        Map.entry("created_date", "m.created_date")
    );

    // Transaction columns → sum_daily_explorer e (+ dims joined on demand for
    // human-readable mid/sid/tid/name).
    private static final Map<String, String> TXN_COLUMNS = Map.ofEntries(
        Map.entry("txn_mid", "m.mid"),
        Map.entry("txn_sid", "st.sid"),
        Map.entry("txn_tid", "t.tid"),
        Map.entry("merchant_name", "m.name"),
        Map.entry("transaction_type", "e.transaction_type"),
        Map.entry("card_scheme", "e.card_scheme"),
        Map.entry("card_type", "e.card_type"),
        Map.entry("destination", "e.destination"),
        Map.entry("channel", "e.channel"),
        Map.entry("txn_currency", "e.txn_currency"),
        Map.entry("store_base_currency", "e.store_base_currency"),
        Map.entry("dcc", "e.is_opt_in"),
        Map.entry("payment_date", "e.business_date")
    );

    // Measure key (unchanged frontend keys) → pre-aggregated SUM column.
    // "count" is handled specially (SUM(total_txns)).
    private static final Map<String, String> MEASURE_COLUMNS = Map.ofEntries(
        Map.entry("txn_currency_amount", "e.total_txn_currency_amount"),
        Map.entry("store_base_currency_amount", "e.total_base_volume"),
        Map.entry("msf", "e.total_msf"),
        Map.entry("vat", "e.total_vat"),
        Map.entry("total_amount_settled", "e.total_settled"),
        Map.entry("interchange_fee", "e.total_interchange"),
        Map.entry("scheme_fee", "e.total_scheme_fee")
    );

    // Allowed aggregations over a daily pre-aggregate. MIN/MAX are NOT valid
    // here (per-transaction extremes are unrecoverable) and are rejected.
    private static final Set<String> ALLOWED_AGGS = Set.of("SUM", "AVG", "COUNT");

    // ═══════════════════════════════════════════════════════════
    // 1. GET Filter Options — distinct values for each column
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/options")
    // Cached per tenant: this endpoint runs ~16 DISTINCT scans (8 of them over
    // sum_daily_explorer's full history) plus a MIN/MAX, on every Explorer
    // open. Values change only on ingest; batch completion evicts the cache.
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS,
            key = "'explorerOptions:' + T(com.acquira.common.config.TenantContext).getCurrentTenant()")
    public ResponseEntity<Map<String, Object>> getFilterOptions() {
        Long tenantId = getTenantId();
        Map<String, Object> result = new HashMap<>();

        // Merchant options — from the dims (full history, not last upload).
        Map<String, List<String>> merchantOpts = new HashMap<>();
        putDistinct(merchantOpts, "referral_partner",
            "SELECT DISTINCT referral_partner FROM dim_merchant WHERE tenant_id = ? AND referral_partner IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "sales_user_email",
            "SELECT DISTINCT sales_email FROM dim_merchant WHERE tenant_id = ? AND sales_email IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "sales_user_id",
            "SELECT DISTINCT sales_user_id FROM dim_merchant WHERE tenant_id = ? AND sales_user_id IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "business_mcc",
            "SELECT DISTINCT mcc FROM dim_store WHERE tenant_id = ? AND mcc IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "industry_type",
            "SELECT DISTINCT industry FROM dim_merchant WHERE tenant_id = ? AND industry IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "risk_level",
            "SELECT DISTINCT risk_level FROM dim_merchant WHERE tenant_id = ? AND risk_level IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "city",
            "SELECT DISTINCT city FROM dim_store WHERE tenant_id = ? AND city IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "state",
            "SELECT DISTINCT state FROM dim_store WHERE tenant_id = ? AND state IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "postal_code",
            "SELECT DISTINCT postal_code FROM dim_store WHERE tenant_id = ? AND postal_code IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "merchant_status",
            "SELECT DISTINCT status FROM dim_merchant WHERE tenant_id = ? AND status IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "store_status",
            "SELECT DISTINCT status FROM dim_store WHERE tenant_id = ? AND status IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "terminal_status",
            "SELECT DISTINCT status FROM dim_terminal WHERE tenant_id = ? AND status IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        putDistinct(merchantOpts, "terminal_type",
            "SELECT DISTINCT type FROM dim_terminal WHERE tenant_id = ? AND type IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        result.put("merchant", merchantOpts);

        // Transaction options — from the history table (all uploads ever).
        Map<String, List<String>> txnOpts = new HashMap<>();
        for (String key : List.of("transaction_type", "card_scheme", "card_type",
                "destination", "channel", "txn_currency", "store_base_currency", "dcc")) {
            String col = TXN_COLUMNS.get(key); // e.<col>
            String bare = col.substring(2);    // strip the "e." alias for the flat query
            putDistinct(txnOpts, key,
                "SELECT DISTINCT CAST(" + bare + " AS VARCHAR) FROM sum_daily_explorer " +
                "WHERE tenant_id = ? AND " + bare + " IS NOT NULL ORDER BY 1 LIMIT 500", tenantId);
        }
        result.put("transaction", txnOpts);

        // Data bounds so the UI can show the available history window.
        try {
            Map<String, Object> bounds = jdbcTemplate.queryForMap(
                "SELECT MIN(business_date) AS min_date, MAX(business_date) AS max_date " +
                "FROM sum_daily_explorer WHERE tenant_id = ?", tenantId);
            result.put("bounds", bounds);
        } catch (Exception e) {
            result.put("bounds", Map.of());
        }

        return ResponseEntity.ok(result);
    }

    private void putDistinct(Map<String, List<String>> target, String key, String sql, Long tenantId) {
        try {
            target.put(key, jdbcTemplate.queryForList(sql, String.class, tenantId));
        } catch (Exception e) {
            target.put(key, List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 2. POST Dynamic Query — Qlik-style pivot/aggregate
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody ExplorerQueryRequest request) {
        Long tenantId = getTenantId();

        try {
            String source = request.getSource(); // "merchant" or "transaction"
            boolean isTxn;
            Map<String, String> columnMap;

            if ("merchant".equals(source)) {
                columnMap = MERCHANT_COLUMNS;
                isTxn = false;
            } else if ("transaction".equals(source)) {
                columnMap = TXN_COLUMNS;
                isTxn = true;
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid source. Use 'merchant' or 'transaction'"));
            }

            List<String> selectParts = new ArrayList<>();
            List<String> groupByParts = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            Set<String> referenced = new HashSet<>(); // qualified columns, drives join pruning

            // Dimensions (GROUP BY columns)
            if (request.getDimensions() != null) {
                for (String dim : request.getDimensions()) {
                    String realCol = columnMap.get(dim);
                    if (realCol == null) {
                        return ResponseEntity.badRequest().body(Map.of("error", "Invalid dimension: " + dim));
                    }
                    referenced.add(realCol);
                    // Date dimensions — group by month (same behavior as before).
                    if (dim.contains("date")) {
                        selectParts.add("TO_CHAR(" + realCol + ", 'YYYY-MM') AS " + dim);
                        groupByParts.add("TO_CHAR(" + realCol + ", 'YYYY-MM')");
                    } else {
                        selectParts.add(realCol + " AS " + dim);
                        groupByParts.add(realCol);
                    }
                }
            }

            // Measures — transaction source only (pre-aggregated columns).
            if (isTxn && request.getMeasures() != null) {
                for (MeasureRequest mr : request.getMeasures()) {
                    String agg = mr.getAggregation() != null ? mr.getAggregation().toUpperCase() : "SUM";
                    if (!ALLOWED_AGGS.contains(agg)) {
                        return ResponseEntity.badRequest().body(Map.of("error",
                            "Invalid aggregation: " + agg + " (allowed over history data: SUM, AVG, COUNT; " +
                            "per-transaction MIN/MAX is only available on the Transactions page)"));
                    }
                    if ("count".equals(mr.getField()) || "COUNT".equals(agg)) {
                        // True transaction count — never a pre-aggregated row count.
                        selectParts.add("SUM(e.total_txns) AS record_count");
                    } else if (MEASURE_COLUMNS.containsKey(mr.getField())) {
                        String col = MEASURE_COLUMNS.get(mr.getField());
                        if ("AVG".equals(agg)) {
                            // Transaction-weighted average, not an average of daily rows.
                            selectParts.add("SUM(COALESCE(" + col + ", 0)) / NULLIF(SUM(e.total_txns), 0) AS "
                                    + mr.getField() + "_avg");
                        } else { // SUM
                            selectParts.add("SUM(COALESCE(" + col + ", 0)) AS " + mr.getField() + "_sum");
                        }
                    } else {
                        return ResponseEntity.badRequest().body(Map.of("error", "Invalid measure: " + mr.getField()));
                    }
                }
            } else if (!isTxn) {
                // For merchant source, always add count (of terminal-grain rows,
                // matching the old staging behavior where each row was a terminal).
                selectParts.add("COUNT(*) AS record_count");
            }

            if (selectParts.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "At least one dimension or measure is required"));
            }

            // WHERE — filters (whitelisted dims only, values always parameterised)
            StringBuilder where = new StringBuilder();
            if (request.getFilters() != null) {
                for (FilterCondition fc : request.getFilters()) {
                    String realCol = columnMap.get(fc.getField());
                    if (realCol == null) continue; // skip unknown filters
                    referenced.add(realCol);

                    if ("in".equals(fc.getOperator()) && fc.getValues() != null && !fc.getValues().isEmpty()) {
                        String placeholders = fc.getValues().stream().map(v -> "?").collect(Collectors.joining(","));
                        // Booleans (dcc/is_opt_in) arrive as strings — CAST the column for comparison.
                        where.append(" AND CAST(").append(realCol).append(" AS VARCHAR) IN (").append(placeholders).append(")");
                        params.addAll(fc.getValues());
                    } else if ("between".equals(fc.getOperator()) && fc.getFrom() != null && fc.getTo() != null) {
                        where.append(" AND ").append(realCol).append(" BETWEEN ?::date AND ?::date");
                        params.add(fc.getFrom());
                        params.add(fc.getTo());
                    } else if ("gte".equals(fc.getOperator()) && fc.getFrom() != null) {
                        where.append(" AND ").append(realCol).append(" >= ?::date");
                        params.add(fc.getFrom());
                    } else if ("lte".equals(fc.getOperator()) && fc.getTo() != null) {
                        where.append(" AND ").append(realCol).append(" <= ?::date");
                        params.add(fc.getTo());
                    } else if ("like".equals(fc.getOperator()) && fc.getValue() != null) {
                        where.append(" AND LOWER(CAST(").append(realCol).append(" AS VARCHAR)) LIKE LOWER(?)");
                        params.add("%" + fc.getValue() + "%");
                    }
                }
            }

            // FROM + joins (tenant pushed onto every join, added only when referenced)
            StringBuilder from = new StringBuilder();
            List<Object> headParams = new ArrayList<>();
            if (isTxn) {
                boolean needM  = referenced.stream().anyMatch(c -> c.startsWith("m."));
                boolean needSt = referenced.stream().anyMatch(c -> c.startsWith("st."));
                boolean needT  = referenced.stream().anyMatch(c -> c.startsWith("t."));
                from.append("FROM sum_daily_explorer e ");
                if (needM)  from.append("LEFT JOIN dim_merchant m ON e.merchant_id = m.merchant_id AND m.tenant_id = e.tenant_id ");
                if (needSt) from.append("LEFT JOIN dim_store st ON e.store_id = st.store_id AND st.tenant_id = e.tenant_id ");
                if (needT)  from.append("LEFT JOIN dim_terminal t ON e.terminal_id = t.terminal_id AND t.tenant_id = e.tenant_id ");
                from.append("WHERE e.tenant_id = ?");
            } else {
                boolean needSt = referenced.stream().anyMatch(c -> c.startsWith("st."));
                boolean needT  = referenced.stream().anyMatch(c -> c.startsWith("t."));
                if (needT) needSt = true; // terminal hangs off store
                from.append("FROM dim_merchant m ");
                if (needSt) from.append("LEFT JOIN dim_store st ON st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id ");
                if (needT)  from.append("LEFT JOIN dim_terminal t ON t.store_id = st.store_id AND t.tenant_id = m.tenant_id ");
                from.append("WHERE m.tenant_id = ?");
            }
            headParams.add(tenantId);
            headParams.addAll(params);

            String sql = "SELECT " + String.join(", ", selectParts) + " " + from + where;

            if (!groupByParts.isEmpty()) {
                sql += " GROUP BY " + String.join(", ", groupByParts);
                sql += " ORDER BY " + groupByParts.get(0);
            }

            sql += " LIMIT 5000"; // Safety cap

            log.info("Explorer query: {}", sql);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, headParams.toArray());

            Map<String, Object> response = new HashMap<>();
            response.put("data", rows);
            response.put("rowCount", rows.size());
            response.put("dimensions", request.getDimensions());
            response.put("measures", request.getMeasures());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Explorer query failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DTOs (unchanged — frontend contract preserved)
    // ═══════════════════════════════════════════════════════════

    public static class ExplorerQueryRequest {
        private String source; // "merchant" or "transaction"
        private List<String> dimensions;
        private List<MeasureRequest> measures;
        private List<FilterCondition> filters;
        // Getters & Setters
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public List<String> getDimensions() { return dimensions; }
        public void setDimensions(List<String> dimensions) { this.dimensions = dimensions; }
        public List<MeasureRequest> getMeasures() { return measures; }
        public void setMeasures(List<MeasureRequest> measures) { this.measures = measures; }
        public List<FilterCondition> getFilters() { return filters; }
        public void setFilters(List<FilterCondition> filters) { this.filters = filters; }
    }

    public static class MeasureRequest {
        private String field;       // "txn_currency_amount", "msf", "count", ...
        private String aggregation; // "SUM", "AVG", "COUNT"
        // Getters & Setters
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getAggregation() { return aggregation; }
        public void setAggregation(String aggregation) { this.aggregation = aggregation; }
    }

    public static class FilterCondition {
        private String field;        // whitelisted column key
        private String operator;     // "in", "between", "gte", "lte", "like"
        private List<String> values; // for "in"
        private String value;        // for "like"
        private String from;         // for "between", "gte"
        private String to;           // for "between", "lte"
        // Getters & Setters
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
    }
}
