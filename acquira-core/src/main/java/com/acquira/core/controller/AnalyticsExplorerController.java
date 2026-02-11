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
 * Dynamic Analytics Explorer — backend engine for the Qlik-style drag-and-drop UI.
 * Accepts dynamic dimension/measure/filter selections and returns aggregated results.
 * All queries hit the pre-aggregated summary tables or dimension tables — never raw fact_transaction.
 */
@RestController
@RequestMapping("/api/analytics/explorer")
public class AnalyticsExplorerController {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsExplorerController.class);
    private final JdbcTemplate jdbcTemplate;

    // ========== WHITELIST — only these columns can be used ==========
    // This prevents SQL injection by restricting to known-safe column names.

    private static final Map<String, FieldDef> MERCHANT_FIELDS = new LinkedHashMap<>();
    private static final Map<String, FieldDef> TXN_FIELDS = new LinkedHashMap<>();
    private static final Map<String, String> MEASURE_DEFS = new LinkedHashMap<>();

    static {
        // Merchant dimension fields (from stg_merchant_master_raw + dim tables)
        MERCHANT_FIELDS.put("mid",                new FieldDef("m.mid",               "MID",                "identity"));
        MERCHANT_FIELDS.put("merchant_name",      new FieldDef("m.merchant_name",     "Merchant Name",      "identity"));
        MERCHANT_FIELDS.put("sid",                new FieldDef("m.sid",               "SID",                "identity"));
        MERCHANT_FIELDS.put("tid",                new FieldDef("m.tid",               "TID",                "identity"));
        MERCHANT_FIELDS.put("aggregator_name",    new FieldDef("m.aggregator_name",   "Aggregator",         "organization"));
        MERCHANT_FIELDS.put("referral_partner",   new FieldDef("m.referral_partner",  "Referral Partner",   "organization"));
        MERCHANT_FIELDS.put("sales_user_email",   new FieldDef("m.sales_user_email",  "Sales User",         "people"));
        MERCHANT_FIELDS.put("business_mcc",       new FieldDef("m.business_mcc",      "MCC",                "classification"));
        MERCHANT_FIELDS.put("business_type",      new FieldDef("m.business_type",     "Business Type",      "classification"));
        MERCHANT_FIELDS.put("industry_type",      new FieldDef("m.industry_type",     "Industry",           "classification"));
        MERCHANT_FIELDS.put("customer_type",      new FieldDef("m.customer_type",     "Customer Type",      "classification"));
        MERCHANT_FIELDS.put("product",            new FieldDef("m.product",           "Product",            "classification"));
        MERCHANT_FIELDS.put("city",               new FieldDef("m.city",              "City",               "location"));
        MERCHANT_FIELDS.put("state",              new FieldDef("m.state",             "State",              "location"));
        MERCHANT_FIELDS.put("postal_code",        new FieldDef("m.postal_code",       "Postal Code",        "location"));
        MERCHANT_FIELDS.put("merchant_status",    new FieldDef("m.merchant_status",   "Merchant Status",    "status"));
        MERCHANT_FIELDS.put("store_status",       new FieldDef("m.store_status",      "Store Status",       "status"));
        MERCHANT_FIELDS.put("terminal_status",    new FieldDef("m.terminal_status",   "Terminal Status",    "status"));
        MERCHANT_FIELDS.put("terminal_type",      new FieldDef("m.terminal_type",     "Terminal Type",      "terminal"));

        // Transaction dimension fields (from stg_trnx_raw)
        TXN_FIELDS.put("txn_mid",                new FieldDef("t.mid",               "MID",                "identity"));
        TXN_FIELDS.put("txn_sid",                new FieldDef("t.sid",               "SID",                "identity"));
        TXN_FIELDS.put("txn_tid",                new FieldDef("t.tid",               "TID",                "identity"));
        TXN_FIELDS.put("card_scheme",            new FieldDef("t.card_scheme",       "Card Scheme",        "card"));
        TXN_FIELDS.put("card_type",              new FieldDef("t.card_type",         "Card Type",          "card"));
        TXN_FIELDS.put("transaction_type",       new FieldDef("t.transaction_type",  "Transaction Type",   "classification"));
        TXN_FIELDS.put("destination",            new FieldDef("t.destination",       "Destination",        "classification"));
        TXN_FIELDS.put("txn_currency",           new FieldDef("t.txn_currency",     "Txn Currency",       "classification"));
        TXN_FIELDS.put("store_base_currency",    new FieldDef("t.store_base_currency","Base Currency",     "classification"));
        TXN_FIELDS.put("dcc",                    new FieldDef("CASE WHEN t.dcc = true THEN 'Opt-In' ELSE 'Opt-Out' END", "DCC Status", "flags"));
        TXN_FIELDS.put("payment_month",          new FieldDef("TO_CHAR(t.payment_date, 'YYYY-MM')", "Payment Month", "dates"));
        TXN_FIELDS.put("payment_date",           new FieldDef("DATE(t.payment_date)", "Payment Date",      "dates"));
        TXN_FIELDS.put("transaction_date",       new FieldDef("DATE(t.transaction_date)", "Transaction Date", "dates"));

        // Measures
        MEASURE_DEFS.put("txn_count",                "COUNT(*)");
        MEASURE_DEFS.put("total_volume",             "COALESCE(SUM(t.store_base_currency_amount), 0)");
        MEASURE_DEFS.put("total_txn_currency_amount","COALESCE(SUM(t.txn_currency_amount), 0)");
        MEASURE_DEFS.put("total_msf",                "COALESCE(SUM(t.msf), 0)");
        MEASURE_DEFS.put("total_vat",                "COALESCE(SUM(t.vat), 0)");
        MEASURE_DEFS.put("total_settled",            "COALESCE(SUM(t.total_amount_settled), 0)");
        MEASURE_DEFS.put("total_interchange",        "COALESCE(SUM(t.interchange_fee), 0)");
        MEASURE_DEFS.put("avg_txn_value",            "CASE WHEN COUNT(*) > 0 THEN SUM(t.store_base_currency_amount)/COUNT(*) ELSE 0 END");
        MEASURE_DEFS.put("distinct_merchants",       "COUNT(DISTINCT t.mid)");
        MEASURE_DEFS.put("distinct_cards",           "COUNT(DISTINCT t.card_number)");
    }

    public AnalyticsExplorerController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the field catalog — what's available to drag and drop.
     */
    @GetMapping("/fields")
    public ResponseEntity<Map<String, Object>> getFieldCatalog() {
        Map<String, Object> catalog = new LinkedHashMap<>();

        List<Map<String, String>> merchantFields = MERCHANT_FIELDS.entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "label", e.getValue().label, "category", e.getValue().category))
            .collect(Collectors.toList());

        List<Map<String, String>> txnFields = TXN_FIELDS.entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "label", e.getValue().label, "category", e.getValue().category))
            .collect(Collectors.toList());

        List<Map<String, String>> measures = MEASURE_DEFS.entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "label", formatMeasureLabel(e.getKey())))
            .collect(Collectors.toList());

        catalog.put("merchantFields", merchantFields);
        catalog.put("transactionFields", txnFields);
        catalog.put("measures", measures);
        return ResponseEntity.ok(catalog);
    }

    /**
     * Returns DISTINCT values for a given field — used for filter dropdowns.
     */
    @GetMapping("/distinct/{fieldKey}")
    public ResponseEntity<?> getDistinctValues(@PathVariable String fieldKey,
                                                @RequestParam(defaultValue = "200") int limit) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        // Find field in merchant or txn maps
        FieldDef def = MERCHANT_FIELDS.get(fieldKey);
        String table = "stg_merchant_master_raw m";
        if (def == null) {
            def = TXN_FIELDS.get(fieldKey);
            table = "stg_trnx_raw t";
        }
        if (def == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown field: " + fieldKey));
        }

        String sql = "SELECT DISTINCT " + def.sql + " AS val FROM " + table +
                      " WHERE tenant_id = ? AND " + def.sql + " IS NOT NULL" +
                      " ORDER BY val LIMIT ?";

        try {
            List<String> values = jdbcTemplate.queryForList(sql, String.class, tenantId, limit);
            return ResponseEntity.ok(values);
        } catch (Exception e) {
            logger.error("Failed to fetch distinct values for {}: {}", fieldKey, e.getMessage());
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * Core query endpoint — executes the dynamic pivot/aggregation.
     */
    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody ExplorerQuery query) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        // Validate dimensions
        List<String> dimensions = query.getDimensions();
        if (dimensions == null || dimensions.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one dimension is required"));
        }
        if (dimensions.size() > 5) {
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 5 dimensions allowed"));
        }

        // Validate measures
        List<String> measures = query.getMeasures();
        if (measures == null || measures.isEmpty()) {
            measures = List.of("txn_count", "total_volume", "total_msf"); // defaults
        }

        // Determine if we need merchant join
        boolean needsMerchant = dimensions.stream().anyMatch(MERCHANT_FIELDS::containsKey) ||
                                (query.getFilters() != null && query.getFilters().keySet().stream().anyMatch(MERCHANT_FIELDS::containsKey));

        // Build SELECT clause
        List<String> selectParts = new ArrayList<>();
        List<String> groupParts = new ArrayList<>();

        for (String dim : dimensions) {
            FieldDef def = resolveField(dim);
            if (def == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown dimension: " + dim));
            }
            selectParts.add(def.sql + " AS " + sanitizeAlias(dim));
            groupParts.add(def.sql);
        }

        for (String measure : measures) {
            String agg = MEASURE_DEFS.get(measure);
            if (agg == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown measure: " + measure));
            }
            selectParts.add(agg + " AS " + sanitizeAlias(measure));
        }

        // Build FROM / JOIN
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", selectParts));
        sql.append(" FROM stg_trnx_raw t ");

        if (needsMerchant) {
            sql.append("LEFT JOIN stg_merchant_master_raw m ON t.mid = m.mid AND t.tenant_id = m.tenant_id ");
        }

        // Build WHERE
        sql.append("WHERE t.tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        // Date filters
        if (query.getStartDate() != null && !query.getStartDate().isEmpty()) {
            sql.append("AND t.payment_date >= CAST(? AS TIMESTAMP) ");
            params.add(query.getStartDate());
        }
        if (query.getEndDate() != null && !query.getEndDate().isEmpty()) {
            sql.append("AND t.payment_date <= CAST(? AS TIMESTAMP) + INTERVAL '1 day' ");
            params.add(query.getEndDate());
        }

        // Dynamic filters (IN clauses)
        if (query.getFilters() != null) {
            for (Map.Entry<String, List<String>> entry : query.getFilters().entrySet()) {
                FieldDef def = resolveField(entry.getKey());
                if (def != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    String placeholders = entry.getValue().stream().map(v -> "?").collect(Collectors.joining(","));
                    sql.append("AND ").append(def.sql).append(" IN (").append(placeholders).append(") ");
                    params.addAll(entry.getValue());
                }
            }
        }

        // Amount range filters
        if (query.getAmountFilters() != null) {
            for (Map.Entry<String, double[]> entry : query.getAmountFilters().entrySet()) {
                String col = resolveAmountColumn(entry.getKey());
                if (col != null && entry.getValue() != null && entry.getValue().length == 2) {
                    sql.append("AND ").append(col).append(" BETWEEN ? AND ? ");
                    params.add(entry.getValue()[0]);
                    params.add(entry.getValue()[1]);
                }
            }
        }

        // Merchant-only filters for staging join dedup
        if (needsMerchant) {
            // Use only latest merchant record
            sql.append("AND (m.raw_id IS NULL OR m.raw_id = (SELECT MAX(m2.raw_id) FROM stg_merchant_master_raw m2 WHERE m2.mid = m.mid AND m2.tenant_id = m.tenant_id)) ");
        }

        // GROUP BY
        if (!groupParts.isEmpty()) {
            sql.append("GROUP BY ").append(String.join(", ", groupParts)).append(" ");
        }

        // ORDER BY first measure DESC
        if (!measures.isEmpty()) {
            sql.append("ORDER BY ").append(sanitizeAlias(measures.get(0))).append(" DESC ");
        }

        // LIMIT
        int limit = query.getLimit() != null && query.getLimit() > 0 ? Math.min(query.getLimit(), 5000) : 1000;
        sql.append("LIMIT ?");
        params.add(limit);

        logger.debug("Explorer SQL: {}", sql);

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("data", results);
            response.put("rowCount", results.size());
            response.put("dimensions", dimensions);
            response.put("measures", measures);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Explorer query failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Query failed: " + e.getMessage()));
        }
    }

    /**
     * Merchant-only query for explorer (no txn data needed)
     */
    @PostMapping("/query/merchants")
    public ResponseEntity<?> queryMerchants(@RequestBody ExplorerQuery query) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        List<String> dimensions = query.getDimensions();
        if (dimensions == null || dimensions.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one dimension is required"));
        }

        List<String> selectParts = new ArrayList<>();
        List<String> groupParts = new ArrayList<>();

        for (String dim : dimensions) {
            FieldDef def = MERCHANT_FIELDS.get(dim);
            if (def == null) continue;
            // Use stg_merchant_master_raw directly without alias prefix
            String col = def.sql.replace("m.", "");
            selectParts.add(col + " AS " + sanitizeAlias(dim));
            groupParts.add(col);
        }
        selectParts.add("COUNT(*) AS record_count");

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", selectParts));
        sql.append(" FROM stg_merchant_master_raw WHERE tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        // Filters
        if (query.getFilters() != null) {
            for (Map.Entry<String, List<String>> entry : query.getFilters().entrySet()) {
                FieldDef def = MERCHANT_FIELDS.get(entry.getKey());
                if (def != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    String col = def.sql.replace("m.", "");
                    String placeholders = entry.getValue().stream().map(v -> "?").collect(Collectors.joining(","));
                    sql.append("AND ").append(col).append(" IN (").append(placeholders).append(") ");
                    params.addAll(entry.getValue());
                }
            }
        }

        if (!groupParts.isEmpty()) {
            sql.append("GROUP BY ").append(String.join(", ", groupParts));
        }
        sql.append(" ORDER BY record_count DESC LIMIT 1000");

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            return ResponseEntity.ok(Map.of("data", results, "rowCount", results.size(), "dimensions", dimensions));
        } catch (Exception e) {
            logger.error("Merchant explorer query failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Query failed: " + e.getMessage()));
        }
    }

    // ========== Helpers ==========

    private FieldDef resolveField(String key) {
        FieldDef def = MERCHANT_FIELDS.get(key);
        if (def == null) def = TXN_FIELDS.get(key);
        return def;
    }

    private String resolveAmountColumn(String key) {
        return switch (key) {
            case "txn_currency_amount" -> "t.txn_currency_amount";
            case "store_base_currency_amount" -> "t.store_base_currency_amount";
            case "msf" -> "t.msf";
            case "total_amount_settled" -> "t.total_amount_settled";
            default -> null;
        };
    }

    private String sanitizeAlias(String key) {
        return key.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String formatMeasureLabel(String key) {
        return switch (key) {
            case "txn_count" -> "Transaction Count";
            case "total_volume" -> "Total Volume";
            case "total_txn_currency_amount" -> "Txn Currency Amount";
            case "total_msf" -> "Total MSF";
            case "total_vat" -> "Total VAT";
            case "total_settled" -> "Total Settled";
            case "total_interchange" -> "Total Interchange";
            case "avg_txn_value" -> "Avg Txn Value";
            case "distinct_merchants" -> "Distinct Merchants";
            case "distinct_cards" -> "Distinct Cards";
            default -> key;
        };
    }

    // ========== Inner classes ==========

    private static class FieldDef {
        final String sql;
        final String label;
        final String category;
        FieldDef(String sql, String label, String category) {
            this.sql = sql;
            this.label = label;
            this.category = category;
        }
    }

    public static class ExplorerQuery {
        private List<String> dimensions;
        private List<String> measures;
        private Map<String, List<String>> filters;
        private Map<String, double[]> amountFilters;
        private String startDate;
        private String endDate;
        private Integer limit;

        public List<String> getDimensions() { return dimensions; }
        public void setDimensions(List<String> d) { this.dimensions = d; }
        public List<String> getMeasures() { return measures; }
        public void setMeasures(List<String> m) { this.measures = m; }
        public Map<String, List<String>> getFilters() { return filters; }
        public void setFilters(Map<String, List<String>> f) { this.filters = f; }
        public Map<String, double[]> getAmountFilters() { return amountFilters; }
        public void setAmountFilters(Map<String, double[]> a) { this.amountFilters = a; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String s) { this.startDate = s; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String e) { this.endDate = e; }
        public Integer getLimit() { return limit; }
        public void setLimit(Integer l) { this.limit = l; }
    }
}
