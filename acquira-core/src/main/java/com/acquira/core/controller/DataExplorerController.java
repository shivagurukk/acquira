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

    // Merchant columns → from stg_merchant_master_raw
    private static final Map<String, String> MERCHANT_COLUMNS = Map.ofEntries(
        Map.entry("mid", "mid"),
        Map.entry("merchant_name", "merchant_name"),
        Map.entry("sid", "sid"),
        Map.entry("tid", "tid"),
        Map.entry("aggregator_name", "aggregator_name"),
        Map.entry("referral_partner", "referral_partner"),
        Map.entry("sales_user_email", "sales_user_email"),
        Map.entry("business_mcc", "business_mcc"),
        Map.entry("business_type", "business_type"),
        Map.entry("industry_type", "industry_type"),
        Map.entry("customer_type", "customer_type"),
        Map.entry("product", "product"),
        Map.entry("city", "city"),
        Map.entry("state", "state"),
        Map.entry("postal_code", "postal_code"),
        Map.entry("merchant_status", "merchant_status"),
        Map.entry("store_status", "store_status"),
        Map.entry("terminal_status", "terminal_status"),
        Map.entry("terminal_type", "terminal_type"),
        Map.entry("date_of_onboarding", "date_of_onboarding"),
        Map.entry("created_date", "created_date")
    );

    // Transaction columns → from stg_trnx_raw
    private static final Map<String, String> TXN_COLUMNS = Map.ofEntries(
        Map.entry("txn_mid", "mid"),
        Map.entry("txn_sid", "sid"),
        Map.entry("txn_tid", "tid"),
        Map.entry("arn", "arn"),
        Map.entry("rrn_number", "rrn_number"),
        Map.entry("card_scheme", "card_scheme"),
        Map.entry("card_type", "card_type"),
        Map.entry("card_number", "card_number"),
        Map.entry("transaction_type", "transaction_type"),
        Map.entry("destination", "destination"),
        Map.entry("txn_currency", "txn_currency"),
        Map.entry("store_base_currency", "store_base_currency"),
        Map.entry("dcc", "dcc"),
        Map.entry("payment_date", "payment_date"),
        Map.entry("transaction_date", "transaction_date")
    );

    // Allowed measures
    private static final Set<String> ALLOWED_MEASURES = Set.of(
        "txn_currency_amount", "store_base_currency_amount", "msf", "vat",
        "total_amount_settled", "interchange_fee", "count"
    );

    // Allowed aggregations
    private static final Set<String> ALLOWED_AGGS = Set.of(
        "SUM", "AVG", "MIN", "MAX", "COUNT"
    );

    // ═══════════════════════════════════════════════════════════
    // 1. GET Filter Options — distinct values for each column
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/options")
    public ResponseEntity<Map<String, Object>> getFilterOptions() {
        Long tenantId = getTenantId();
        Map<String, Object> result = new HashMap<>();

        // Merchant options
        Map<String, List<String>> merchantOpts = new HashMap<>();
        for (String key : List.of("aggregator_name", "referral_partner", "sales_user_email",
                "business_mcc", "business_type", "industry_type", "customer_type", "product",
                "city", "state", "postal_code", "merchant_status", "store_status",
                "terminal_status", "terminal_type")) {
            String col = MERCHANT_COLUMNS.get(key);
            try {
                List<String> vals = jdbcTemplate.queryForList(
                    "SELECT DISTINCT " + col + " FROM stg_merchant_master_raw " +
                    "WHERE tenant_id = ? AND " + col + " IS NOT NULL ORDER BY " + col + " LIMIT 500",
                    String.class, tenantId);
                merchantOpts.put(key, vals);
            } catch (Exception e) {
                merchantOpts.put(key, List.of());
            }
        }
        result.put("merchant", merchantOpts);

        // Transaction options
        Map<String, List<String>> txnOpts = new HashMap<>();
        for (String key : List.of("card_scheme", "card_type", "transaction_type",
                "destination", "txn_currency", "store_base_currency", "dcc")) {
            String col = TXN_COLUMNS.get(key);
            try {
                List<String> vals = jdbcTemplate.queryForList(
                    "SELECT DISTINCT CAST(" + col + " AS VARCHAR) FROM stg_trnx_raw " +
                    "WHERE tenant_id = ? AND " + col + " IS NOT NULL ORDER BY 1 LIMIT 500",
                    String.class, tenantId);
                txnOpts.put(key, vals);
            } catch (Exception e) {
                txnOpts.put(key, List.of());
            }
        }
        result.put("transaction", txnOpts);

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════
    // 2. POST Dynamic Query — Qlik-style pivot/aggregate
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody ExplorerQueryRequest request) {
        Long tenantId = getTenantId();

        try {
            // Validate all requested fields are whitelisted
            String source = request.getSource(); // "merchant" or "transaction"
            Map<String, String> columnMap;
            String tableName;

            if ("merchant".equals(source)) {
                columnMap = MERCHANT_COLUMNS;
                tableName = "stg_merchant_master_raw";
            } else if ("transaction".equals(source)) {
                columnMap = TXN_COLUMNS;
                tableName = "stg_trnx_raw";
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid source. Use 'merchant' or 'transaction'"));
            }

            // === Build SELECT ===
            List<String> selectParts = new ArrayList<>();
            List<String> groupByParts = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            // Dimensions (GROUP BY columns)
            if (request.getDimensions() != null) {
                for (String dim : request.getDimensions()) {
                    String realCol = columnMap.get(dim);
                    if (realCol == null) {
                        return ResponseEntity.badRequest().body(Map.of("error", "Invalid dimension: " + dim));
                    }
                    // Handle date dimensions — group by month
                    if (dim.contains("date") || dim.equals("date_of_onboarding") || dim.equals("created_date")) {
                        selectParts.add("TO_CHAR(" + realCol + ", 'YYYY-MM') AS " + dim);
                        groupByParts.add("TO_CHAR(" + realCol + ", 'YYYY-MM')");
                    } else {
                        selectParts.add(realCol + " AS " + dim);
                        groupByParts.add(realCol);
                    }
                }
            }

            // Measures (aggregations) — only for transaction source
            if (request.getMeasures() != null && "transaction".equals(source)) {
                for (MeasureRequest m : request.getMeasures()) {
                    String agg = m.getAggregation() != null ? m.getAggregation().toUpperCase() : "SUM";
                    if (!ALLOWED_AGGS.contains(agg)) {
                        return ResponseEntity.badRequest().body(Map.of("error", "Invalid aggregation: " + agg));
                    }
                    if ("count".equals(m.getField())) {
                        selectParts.add("COUNT(*) AS record_count");
                    } else if (ALLOWED_MEASURES.contains(m.getField())) {
                        selectParts.add(agg + "(COALESCE(" + m.getField() + ", 0)) AS " + m.getField() + "_" + agg.toLowerCase());
                    } else {
                        return ResponseEntity.badRequest().body(Map.of("error", "Invalid measure: " + m.getField()));
                    }
                }
            } else if ("merchant".equals(source)) {
                // For merchant source, always add count
                selectParts.add("COUNT(*) AS record_count");
            }

            if (selectParts.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "At least one dimension or measure is required"));
            }

            // === Build WHERE ===
            StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
            params.add(tenantId);

            if (request.getFilters() != null) {
                for (FilterCondition fc : request.getFilters()) {
                    String realCol;
                    if ("merchant".equals(source)) {
                        realCol = MERCHANT_COLUMNS.get(fc.getField());
                    } else {
                        realCol = TXN_COLUMNS.get(fc.getField());
                    }
                    if (realCol == null) continue; // skip unknown filters

                    if ("in".equals(fc.getOperator()) && fc.getValues() != null && !fc.getValues().isEmpty()) {
                        String placeholders = fc.getValues().stream().map(v -> "?").collect(Collectors.joining(","));
                        where.append(" AND ").append(realCol).append(" IN (").append(placeholders).append(")");
                        params.addAll(fc.getValues());
                    } else if ("between".equals(fc.getOperator()) && fc.getFrom() != null && fc.getTo() != null) {
                        where.append(" AND ").append(realCol).append(" BETWEEN ?::timestamp AND ?::timestamp");
                        params.add(fc.getFrom());
                        params.add(fc.getTo());
                    } else if ("gte".equals(fc.getOperator()) && fc.getFrom() != null) {
                        where.append(" AND ").append(realCol).append(" >= ?");
                        params.add(Double.parseDouble(fc.getFrom()));
                    } else if ("lte".equals(fc.getOperator()) && fc.getTo() != null) {
                        where.append(" AND ").append(realCol).append(" <= ?");
                        params.add(Double.parseDouble(fc.getTo()));
                    } else if ("like".equals(fc.getOperator()) && fc.getValue() != null) {
                        where.append(" AND LOWER(").append(realCol).append(") LIKE LOWER(?)");
                        params.add("%" + fc.getValue() + "%");
                    }
                }
            }

            // === Assemble SQL ===
            String sql = "SELECT " + String.join(", ", selectParts) +
                         " FROM " + tableName + where;

            if (!groupByParts.isEmpty()) {
                sql += " GROUP BY " + String.join(", ", groupByParts);
                sql += " ORDER BY " + groupByParts.get(0);
            }

            sql += " LIMIT 5000"; // Safety cap

            log.info("Explorer query: {}", sql);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());

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
    // DTOs
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
        private String field;       // "txn_currency_amount", "msf", "count"
        private String aggregation; // "SUM", "AVG", "COUNT", "MIN", "MAX"
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
