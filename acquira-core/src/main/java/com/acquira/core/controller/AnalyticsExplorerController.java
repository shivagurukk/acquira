package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.ExplorerMasterItem;
import com.acquira.common.model.ExplorerAlert;
import com.acquira.common.repository.ExplorerMasterItemRepository;
import com.acquira.common.repository.ExplorerAlertRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dynamic Analytics Explorer — backend engine for the drag-and-drop UI.
 *
 * PHASE 0 (warehouse repoint): queries the WAREHOUSE — fact_transaction joined
 * to dim_* on the resolved IDs — instead of the transient staging tables.
 *
 * PHASE 1 (semantic model + grain selection / aggregation pushdown):
 * Each field and measure now carries TWO expressions — one for the fact grain
 * (fact_transaction, alias t) and one for the pre-aggregated daily grain
 * (sum_daily_insight, alias s). The engine inspects the requested
 * dimensions/measures/filters and, when every one of them is serviceable from
 * the summary table, builds the query against sum_daily_insight — which is far
 * smaller and answers common breakdowns (volume/MSF/txns by scheme, card type,
 * destination, opt-in, month, merchant/store/terminal) much faster. It falls
 * back to fact_transaction only when something fact-only is needed: VAT,
 * settled, interchange, txn-currency amount, distinct cards, transaction_type,
 * txn/base currency, transaction_date, or any amount-range filter.
 *
 * Because sum_daily_insight is populated from the same fact rows (populateSummaryStep),
 * SUM over the summary equals SUM over the fact for the same grain — results reconcile.
 *
 * Security/perf:
 *  - Only whitelisted columns can be used; user input never becomes a SQL identifier.
 *  - Tenant scoped on the base table AND pushed into every dim join (dX.tenant_id = base.tenant_id), per P2-1.
 *  - Dim joins are added only when referenced. Date predicates let the partition pruner trim the base table.
 */
@RestController
@RequestMapping("/api/analytics/explorer")
// Gated to the Data Explorer grant. Note: PricingSimulator also calls /query and
// now requires the /explorer grant (same data sensitivity). The two existing
// method-level hasAnyRole('ADMIN','SUPER_ADMIN') annotations override this
// class-level gate for their endpoints.
@PreAuthorize("@menuAccess.canAccess('/explorer')")
public class AnalyticsExplorerController {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsExplorerController.class);
    private final JdbcTemplate jdbcTemplate;
    private final ExplorerMasterItemRepository masterRepo;
    private final ExplorerAlertRepository alertRepo;
    private final ObjectMapper objectMapper;
    /** Stamps the tenant's currency onto every money-bearing response. */
    private final CurrencyMeta currencyMeta;

    /** Which table/alias a dimension lives on — drives which dim joins we add. */
    private enum Src { TXN, MERCHANT, STORE, TERMINAL }
    /** Chosen base grain for a query. */
    private enum Grain { FACT, SUMMARY }

    private static final Map<String, FieldDef> MERCHANT_FIELDS = new LinkedHashMap<>();
    private static final Map<String, FieldDef> CONTEXT_FIELDS = new LinkedHashMap<>();
    private static final Map<String, MeasureDef> MEASURE_DEFS = new LinkedHashMap<>();
    // Aggregatable columns + functions for user-defined aggregation measures (Measure Studio).
    private static final Map<String, String> AGG_COLUMNS = new LinkedHashMap<>();       // key -> fact column expr (or "*")
    private static final Map<String, String> AGG_COLUMN_LABELS = new LinkedHashMap<>();
    private static final Map<String, String> AGG_COLUMN_KINDS = new LinkedHashMap<>();   // amount | id | rows
    private static final java.util.Set<String> AGG_FUNCS =
        java.util.Set.of("SUM", "AVG", "MIN", "MAX", "MEDIAN", "STDDEV", "P90", "P95", "COUNT", "COUNT_DISTINCT");
    private static final java.util.Set<String> FORMULA_FUNCS =
        java.util.Set.of("ABS", "ROUND", "COALESCE", "LEAST", "GREATEST");

    static {
        // ----- dim_merchant (dm) — identical expression on both grains (same join) -----
        MERCHANT_FIELDS.put("mid",              dim("dm.mid",              "MID",              "identity",       Src.MERCHANT));
        MERCHANT_FIELDS.put("merchant_name",    dim("dm.name",             "Merchant Name",   "identity",       Src.MERCHANT));
        // MCC is a STORE attribute (dim_store.mcc drives every rollup and the fee
        // engine); dim_merchant.mcc is only MAX() across stores. Read the store.
        MERCHANT_FIELDS.put("mcc",              dim("ds.mcc",              "MCC",             "classification", Src.STORE));
        MERCHANT_FIELDS.put("industry",         dim("dm.industry",         "Industry",        "classification", Src.MERCHANT));
        MERCHANT_FIELDS.put("city",             dim("dm.city",             "City",            "location",       Src.MERCHANT));
        MERCHANT_FIELDS.put("merchant_status",  dim("dm.status",           "Merchant Status", "status",         Src.MERCHANT));
        MERCHANT_FIELDS.put("referral_partner", dim("dm.referral_partner", "Referral Partner","organization",   Src.MERCHANT));
        MERCHANT_FIELDS.put("sales_user",       dim("dm.sales_email",      "Sales User",      "people",         Src.MERCHANT));
        MERCHANT_FIELDS.put("risk_level",       dim("dm.risk_level",       "Risk Level",      "classification", Src.MERCHANT));

        // ----- dim_store (ds) -----
        CONTEXT_FIELDS.put("sid",             dim("ds.sid",         "SID",            "identity", Src.STORE));
        CONTEXT_FIELDS.put("store_name",      dim("ds.name",        "Store Name",     "identity", Src.STORE));
        CONTEXT_FIELDS.put("store_city",      dim("ds.city",        "Store City",     "location", Src.STORE));
        CONTEXT_FIELDS.put("state",           dim("ds.state",       "State",          "location", Src.STORE));
        CONTEXT_FIELDS.put("postal_code",     dim("ds.postal_code", "Postal Code",    "location", Src.STORE));
        CONTEXT_FIELDS.put("store_status",    dim("ds.status",      "Store Status",   "status",   Src.STORE));
        // ----- dim_terminal (dt) -----
        CONTEXT_FIELDS.put("tid",             dim("dt.tid",         "TID",            "identity", Src.TERMINAL));
        CONTEXT_FIELDS.put("terminal_type",   dim("dt.type",        "Terminal Type",  "terminal", Src.TERMINAL));
        CONTEXT_FIELDS.put("terminal_status", dim("dt.status",      "Terminal Status","status",   Src.TERMINAL));

        // ----- transaction context: factSql + summarySql (null summarySql ⇒ fact-only) -----
        CONTEXT_FIELDS.put("card_scheme",         txn("t.card_scheme",        "s.card_scheme",  "Card Scheme",      "card"));
        CONTEXT_FIELDS.put("card_type",           txn("t.card_type",          "s.card_type",    "Card Type",        "card"));
        CONTEXT_FIELDS.put("destination",         txn("t.destination",        "s.destination",  "Destination",      "classification"));
        CONTEXT_FIELDS.put("dcc",                 txn("CASE WHEN t.dcc = true THEN 'Opt-In' ELSE 'Opt-Out' END",
                                                       "CASE WHEN s.is_opt_in = true THEN 'Opt-In' ELSE 'Opt-Out' END", "DCC Status", "flags"));
        CONTEXT_FIELDS.put("payment_month",       txn("TO_CHAR(t.payment_date, 'YYYY-MM')",
                                                       "TO_CHAR(s.business_date, 'YYYY-MM')", "Payment Month", "dates"));
        CONTEXT_FIELDS.put("payment_date",        txn("DATE(t.payment_date)", "s.business_date", "Payment Date",  "dates"));
        // fact-only context (no summary equivalent):
        CONTEXT_FIELDS.put("transaction_type",    txn("t.transaction_type",   null, "Transaction Type", "classification"));
        CONTEXT_FIELDS.put("txn_currency",        txn("t.txn_currency",       null, "Txn Currency",      "classification"));
        CONTEXT_FIELDS.put("store_base_currency", txn("t.store_base_currency",null, "Base Currency",     "classification"));
        CONTEXT_FIELDS.put("transaction_date",    txn("DATE(t.transaction_date)", null, "Transaction Date", "dates"));

        // ----- Measures: factSql + summarySql (null ⇒ fact-only) -----
        MEASURE_DEFS.put("txn_count",                 meas("COUNT(*)",                                       "COALESCE(SUM(s.total_txns), 0)"));
        MEASURE_DEFS.put("total_volume",              meas("COALESCE(SUM(t.store_base_currency_amount), 0)", "COALESCE(SUM(s.total_volume), 0)"));
        MEASURE_DEFS.put("total_msf",                 meas("COALESCE(SUM(t.msf), 0)",                        "COALESCE(SUM(s.total_msf), 0)"));
        MEASURE_DEFS.put("avg_txn_value",             meas("CASE WHEN COUNT(*) > 0 THEN SUM(t.store_base_currency_amount)/COUNT(*) ELSE 0 END",
                                                           "CASE WHEN SUM(s.total_txns) > 0 THEN SUM(s.total_volume)/SUM(s.total_txns) ELSE 0 END"));
        MEASURE_DEFS.put("distinct_merchants",        meas("COUNT(DISTINCT t.merchant_id)",                  "COUNT(DISTINCT s.merchant_id)"));
        // fact-only measures:
        MEASURE_DEFS.put("total_txn_currency_amount", meas("COALESCE(SUM(t.txn_currency_amount), 0)",        null));
        MEASURE_DEFS.put("total_vat",                 meas("COALESCE(SUM(t.vat), 0)",                        null));
        MEASURE_DEFS.put("total_settled",             meas("COALESCE(SUM(t.total_amount_settled), 0)",       null));
        MEASURE_DEFS.put("total_interchange",         meas("COALESCE(SUM(t.interchange_fee), 0)",            null));
        MEASURE_DEFS.put("distinct_cards",            meas("COUNT(DISTINCT t.card_number)",                  null));

        // ----- Derived financial measures (added) -----
        // net_revenue: fact-only — interchange & vat live only on the fact grain
        // (sum_daily_insight carries neither), so this cannot route to summary.
        MEASURE_DEFS.put("net_revenue",               meas("COALESCE(SUM(t.msf) - SUM(t.interchange_fee) - SUM(t.vat), 0)", null));
        // effective_msf_rate: MSF as basis points of volume. Both grains have msf+volume.
        MEASURE_DEFS.put("effective_msf_rate",        meas("CASE WHEN SUM(t.store_base_currency_amount) > 0 THEN SUM(t.msf)/SUM(t.store_base_currency_amount)*10000 ELSE 0 END",
                                                           "CASE WHEN SUM(s.total_volume) > 0 THEN SUM(s.total_msf)/SUM(s.total_volume)*10000 ELSE 0 END"));
        // avg_msf_per_txn: MSF per transaction. Both grains have msf + txn count.
        MEASURE_DEFS.put("avg_msf_per_txn",           meas("CASE WHEN COUNT(*) > 0 THEN SUM(t.msf)/COUNT(*) ELSE 0 END",
                                                           "CASE WHEN SUM(s.total_txns) > 0 THEN SUM(s.total_msf)/SUM(s.total_txns) ELSE 0 END"));
        // interchange_rate: interchange as basis points of volume. Fact-only.
        MEASURE_DEFS.put("interchange_rate",          meas("CASE WHEN SUM(t.store_base_currency_amount) > 0 THEN SUM(t.interchange_fee)/SUM(t.store_base_currency_amount)*10000 ELSE 0 END", null));
        // settlement_ratio: settled ÷ cardholder amount, as a percent. Fact-only.
        MEASURE_DEFS.put("settlement_ratio",          meas("CASE WHEN SUM(t.txn_currency_amount) > 0 THEN SUM(t.total_amount_settled)/SUM(t.txn_currency_amount)*100 ELSE 0 END", null));

        // ----- Aggregatable columns (fact grain) for the Measure Studio builder -----
        AGG_COLUMNS.put("rows", "*");                                  AGG_COLUMN_LABELS.put("rows", "Transaction Rows");      AGG_COLUMN_KINDS.put("rows", "rows");
        AGG_COLUMNS.put("store_base_currency_amount", "t.store_base_currency_amount"); AGG_COLUMN_LABELS.put("store_base_currency_amount", "Volume (settlement)"); AGG_COLUMN_KINDS.put("store_base_currency_amount", "amount");
        AGG_COLUMNS.put("txn_currency_amount", "t.txn_currency_amount"); AGG_COLUMN_LABELS.put("txn_currency_amount", "Txn Amount");   AGG_COLUMN_KINDS.put("txn_currency_amount", "amount");
        AGG_COLUMNS.put("msf", "t.msf");                              AGG_COLUMN_LABELS.put("msf", "MSF");                    AGG_COLUMN_KINDS.put("msf", "amount");
        AGG_COLUMNS.put("vat", "t.vat");                              AGG_COLUMN_LABELS.put("vat", "VAT");                    AGG_COLUMN_KINDS.put("vat", "amount");
        AGG_COLUMNS.put("interchange_fee", "t.interchange_fee");       AGG_COLUMN_LABELS.put("interchange_fee", "Interchange Fee"); AGG_COLUMN_KINDS.put("interchange_fee", "amount");
        AGG_COLUMNS.put("total_amount_settled", "t.total_amount_settled"); AGG_COLUMN_LABELS.put("total_amount_settled", "Settled Amount"); AGG_COLUMN_KINDS.put("total_amount_settled", "amount");
        AGG_COLUMNS.put("merchant_id", "t.merchant_id");               AGG_COLUMN_LABELS.put("merchant_id", "Merchant");       AGG_COLUMN_KINDS.put("merchant_id", "id");
        AGG_COLUMNS.put("store_id", "t.store_id");                    AGG_COLUMN_LABELS.put("store_id", "Store");             AGG_COLUMN_KINDS.put("store_id", "id");
        AGG_COLUMNS.put("terminal_id", "t.terminal_id");              AGG_COLUMN_LABELS.put("terminal_id", "Terminal");       AGG_COLUMN_KINDS.put("terminal_id", "id");
        AGG_COLUMNS.put("card_number", "t.card_number");              AGG_COLUMN_LABELS.put("card_number", "Card");           AGG_COLUMN_KINDS.put("card_number", "id");
    }

    public AnalyticsExplorerController(JdbcTemplate jdbcTemplate,
                                      ExplorerMasterItemRepository masterRepo,
                                      ExplorerAlertRepository alertRepo,
                                      ObjectMapper objectMapper,
                                      CurrencyMeta currencyMeta) {
        this.jdbcTemplate = jdbcTemplate;
        this.masterRepo = masterRepo;
        this.alertRepo = alertRepo;
        this.objectMapper = objectMapper;
        this.currencyMeta = currencyMeta;
    }

    // ═════════════════════════════════════════════════════════
    //  PHASE 4.x — Master items (governed, shareable definitions)
    // ═════════════════════════════════════════════════════════

    @GetMapping("/master-items")
    public ResponseEntity<?> listMasterItems() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(masterRepo.findByTenantIdOrderByItemTypeAscLabelAsc(tenantId));
    }

    @PostMapping("/master-items")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> createMasterItem(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        String type = String.valueOf(body.getOrDefault("itemType", "CALC"));
        String key = (String) body.get("itemKey");
        String label = (String) body.get("label");
        String definition = (String) body.get("definition");
        if (key == null || key.isBlank() || label == null || label.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "itemKey and label are required"));
        if ("CALC".equals(type)) {
            if (definition == null || definition.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "A CALC item needs a formula"));
            try { compileFormula(definition, new LinkedHashSet<>()); }
            catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid formula: " + ex.getMessage())); }
        }
        if ("AGG".equals(type)) {
            try {
                Map<String, Object> spec = objectMapper.readValue(definition == null ? "{}" : definition, new TypeReference<Map<String, Object>>() {});
                compileAgg(spec.get("column") == null ? null : spec.get("column").toString(),
                           spec.get("agg") == null ? null : spec.get("agg").toString());
                Object ff = spec.get("filterField");
                if (ff != null && resolveField(ff.toString()) == null)
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid aggregation: unknown condition field '" + ff + "'"));
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid aggregation: " + ex.getMessage()));
            } catch (Exception ex) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid aggregation definition"));
            }
        }
        if ("TIME".equals(type)) {
            try {
                Map<String, Object> spec = objectMapper.readValue(definition == null ? "{}" : definition, new TypeReference<Map<String, Object>>() {});
                if (spec.get("base") == null || spec.get("base").toString().isBlank())
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid time measure: base measure is required"));
            } catch (Exception ex) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid time measure definition"));
            }
        }
        ExplorerMasterItem mi = new ExplorerMasterItem();
        mi.setTenantId(tenantId);
        mi.setItemType(type);
        mi.setItemKey(key);
        mi.setLabel(label);
        mi.setDefinition(definition);
        mi.setDescription((String) body.get("description"));
        mi.setCreatedBy(currentUsername());
        try { return ResponseEntity.ok(masterRepo.save(mi)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "An item with that key already exists")); }
    }

    @DeleteMapping("/master-items/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> deleteMasterItem(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        return masterRepo.findById(id)
            .filter(x -> x.getTenantId().equals(tenantId))
            .map(x -> { masterRepo.delete(x); return ResponseEntity.ok(Map.of("message", "deleted")); })
            .orElse(ResponseEntity.notFound().build());
    }

    // ═════════════════════════════════════════════════════════
    //  PHASE 4.x — Threshold alerts (evaluated by ExplorerAlertScheduler)
    // ═════════════════════════════════════════════════════════

    @GetMapping("/alerts")
    public ResponseEntity<?> listAlerts() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(alertRepo.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }

    @PostMapping("/alerts")
    public ResponseEntity<?> createAlert(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (body.get("measureKey") == null || String.valueOf(body.get("measureKey")).isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "measureKey is required"));
        ExplorerAlert a = new ExplorerAlert();
        a.setTenantId(tenantId);
        a.setName(String.valueOf(body.getOrDefault("name", "Alert")));
        a.setMeasureKey((String) body.get("measureKey"));
        a.setCalcJson(asJson(body.get("calcMeasures")));
        a.setFilterJson(asJson(body.get("filters")));
        a.setWindowDays(body.get("windowDays") != null ? ((Number) body.get("windowDays")).intValue() : 1);
        a.setOperator(String.valueOf(body.getOrDefault("operator", ">")));
        a.setThreshold(body.get("threshold") != null ? ((Number) body.get("threshold")).doubleValue() : 0d);
        a.setSeverity(String.valueOf(body.getOrDefault("severity", "WARNING")));
        a.setRecipients((String) body.get("recipients"));
        a.setIsEnabled(body.get("isEnabled") != null ? (Boolean) body.get("isEnabled") : true);
        a.setCreatedBy(currentUsername());
        return ResponseEntity.ok(alertRepo.save(a));
    }

    @PutMapping("/alerts/{id}")
    public ResponseEntity<?> updateAlert(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        return alertRepo.findById(id)
            .filter(x -> x.getTenantId().equals(tenantId))
            .map(a -> {
                if (body.containsKey("name")) a.setName((String) body.get("name"));
                if (body.containsKey("operator")) a.setOperator((String) body.get("operator"));
                if (body.containsKey("threshold") && body.get("threshold") != null) a.setThreshold(((Number) body.get("threshold")).doubleValue());
                if (body.containsKey("windowDays") && body.get("windowDays") != null) a.setWindowDays(((Number) body.get("windowDays")).intValue());
                if (body.containsKey("severity")) a.setSeverity((String) body.get("severity"));
                if (body.containsKey("recipients")) a.setRecipients((String) body.get("recipients"));
                if (body.containsKey("isEnabled")) a.setIsEnabled((Boolean) body.get("isEnabled"));
                return ResponseEntity.ok(alertRepo.save(a));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<?> deleteAlert(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        return alertRepo.findById(id)
            .filter(x -> x.getTenantId().equals(tenantId))
            .map(x -> { alertRepo.delete(x); return ResponseEntity.ok(Map.of("message", "deleted")); })
            .orElse(ResponseEntity.notFound().build());
    }

    /** Evaluate an alert immediately (manual "test") — returns current value + breach flag. */
    @PostMapping("/alerts/{id}/run")
    public ResponseEntity<?> runAlertNow(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        return alertRepo.findById(id)
            .filter(x -> x.getTenantId().equals(tenantId))
            .map(a -> {
                try {
                    double v = evaluateAlert(a);
                    boolean breach = breaches(a.getOperator(), v, a.getThreshold());
                    a.setLastValue(v); a.setLastCheckedAt(LocalDateTime.now()); alertRepo.save(a);
                    return ResponseEntity.ok(Map.of("value", v, "breached", breach));
                } catch (Exception e) {
                    return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
                }
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ---- shared evaluation helpers (also used by ExplorerAlertScheduler) ----

    /** Run a query for the tenant currently in TenantContext and return the data rows. */
    public List<Map<String, Object>> runRaw(ExplorerQuery q) {
        ResponseEntity<?> resp = executeQuery(q);
        Object b = resp.getBody();
        if (b instanceof Map<?, ?> m && m.get("data") instanceof List<?> d) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d;
            return rows;
        }
        return List.of();
    }

    // ----- Time-intelligence: compare a base measure across two date windows -----
    // Runs the existing per-window aggregation for the current and prior windows, then
    // merges per dimension-tuple. Works for any base measure (base, agg, or calc).
    private ResponseEntity<?> executeWithTimeIntelligence(ExplorerQuery query) {
        List<TimeMeasure> tms = query.getTimeMeasures();
        if (query.getStartDate() == null || query.getStartDate().isEmpty()
                || query.getEndDate() == null || query.getEndDate().isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Time-intelligence measures need a date range"));
        LocalDate start, end;
        try {
            start = LocalDate.parse(query.getStartDate().substring(0, 10));
            end = LocalDate.parse(query.getEndDate().substring(0, 10));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date range for time intelligence"));
        }

        List<String> requested = (query.getMeasures() == null || query.getMeasures().isEmpty())
            ? List.of("txn_count", "total_volume", "total_msf") : query.getMeasures();

        Map<String, TimeMeasure> tmByKey = new LinkedHashMap<>();
        LinkedHashSet<String> baseNeeded = new LinkedHashSet<>();
        for (TimeMeasure tm : tms) {
            if (tm == null || tm.getKey() == null || tm.getBase() == null) continue;
            tmByKey.put(tm.getKey(), tm);
            baseNeeded.add(tm.getBase());
        }
        if (tmByKey.isEmpty()) return executeCore(query);

        // Current window: requested non-TI measures + the base measures TI needs.
        LinkedHashSet<String> curMeasures = new LinkedHashSet<>();
        for (String m : requested) if (!tmByKey.containsKey(m)) curMeasures.add(m);
        curMeasures.addAll(baseNeeded);
        if (curMeasures.isEmpty()) curMeasures.add("txn_count");

        ResponseEntity<?> curResp = executeCore(cloneForWindow(query, query.getStartDate(), query.getEndDate(), new ArrayList<>(curMeasures)));
        if (!curResp.getStatusCode().is2xxSuccessful()) return curResp;
        List<Map<String, Object>> curRows = extractData(curResp);

        List<String> dimAliases = (query.getDimensions() == null ? List.<String>of() : query.getDimensions())
            .stream().map(this::sanitizeAlias).collect(Collectors.toList());

        // One prior-window query per distinct comparison.
        Map<String, Map<String, Map<String, Object>>> priorIndex = new HashMap<>();
        java.util.Set<String> compares = new java.util.HashSet<>();
        for (TimeMeasure tm : tmByKey.values()) compares.add(tm.getComparison() == null ? "PREV" : tm.getComparison().toUpperCase());
        for (String cmp : compares) {
            String[] win = shiftWindow(cmp, start, end);
            if (win == null) continue;
            ResponseEntity<?> pr = executeCore(cloneForWindow(query, win[0], win[1], new ArrayList<>(baseNeeded)));
            if (!pr.getStatusCode().is2xxSuccessful()) continue;
            Map<String, Map<String, Object>> idx = new HashMap<>();
            for (Map<String, Object> r : extractData(pr)) idx.put(dimKey(r, dimAliases), r);
            priorIndex.put(cmp, idx);
        }

        // Compute each TI column on the current rows.
        for (Map<String, Object> row : curRows) {
            String key = dimKey(row, dimAliases);
            for (TimeMeasure tm : tmByKey.values()) {
                String cmp = tm.getComparison() == null ? "PREV" : tm.getComparison().toUpperCase();
                String mode = tm.getMode() == null ? "growth" : tm.getMode().toLowerCase();
                String baseAlias = sanitizeAlias(tm.getBase());
                double cur = toD(row.get(baseAlias));
                Map<String, Object> prior = priorIndex.getOrDefault(cmp, java.util.Collections.emptyMap()).get(key);
                Double priorVal = prior == null ? null : toDOrNull(prior.get(baseAlias));
                double pv = priorVal == null ? 0.0 : priorVal;
                Object out;
                switch (mode) {
                    case "prior": out = priorVal; break;
                    case "delta": out = cur - pv; break;
                    case "growth": default: out = (pv == 0.0) ? null : ((cur - pv) / Math.abs(pv)) * 100.0; break;
                }
                row.put(sanitizeAlias(tm.getKey()), out);
            }
        }

        // Output: dimensions + requested measures (drop base measures added only for TI).
        LinkedHashSet<String> outCols = new LinkedHashSet<>(dimAliases);
        for (String m : requested) outCols.add(sanitizeAlias(m));
        List<Map<String, Object>> outRows = new ArrayList<>();
        for (Map<String, Object> row : curRows) {
            Map<String, Object> o = new LinkedHashMap<>();
            for (String c : outCols) if (row.containsKey(c)) o.put(c, row.get(c));
            outRows.add(o);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", outRows);
        response.put("rowCount", outRows.size());
        response.put("dimensions", query.getDimensions());
        response.put("measures", requested);
        response.put("grain", "time-intelligence");
        return ResponseEntity.ok(currencyMeta.attach(response));
    }

    private ExplorerQuery cloneForWindow(ExplorerQuery q, String sd, String ed, List<String> measures) {
        ExplorerQuery c = new ExplorerQuery();
        c.setDimensions(q.getDimensions());
        c.setMeasures(measures);
        c.setFilters(q.getFilters());
        c.setAmountFilters(q.getAmountFilters());
        c.setCalcMeasures(q.getCalcMeasures());
        c.setAggMeasures(q.getAggMeasures());
        c.setLimit(q.getLimit());
        c.setStartDate(sd);
        c.setEndDate(ed);
        // timeMeasures intentionally left null to avoid recursion
        return c;
    }

    private String[] shiftWindow(String cmp, LocalDate start, LocalDate end) {
        switch (cmp == null ? "PREV" : cmp.toUpperCase()) {
            case "YOY": return new String[]{ start.minusYears(1).toString(), end.minusYears(1).toString() };
            case "MOM": return new String[]{ start.minusMonths(1).toString(), end.minusMonths(1).toString() };
            case "PREV": default: {
                long len = ChronoUnit.DAYS.between(start, end) + 1;
                return new String[]{ start.minusDays(len).toString(), end.minusDays(len).toString() };
            }
        }
    }

    private List<Map<String, Object>> extractData(ResponseEntity<?> resp) {
        Object b = resp.getBody();
        if (b instanceof Map<?, ?> m && m.get("data") instanceof List<?> d) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) d;
            return rows;
        }
        return new ArrayList<>();
    }

    private String dimKey(Map<String, Object> row, List<String> dimAliases) {
        StringBuilder sb = new StringBuilder();
        for (String a : dimAliases) { sb.append('\u0001').append(String.valueOf(row.get(a))); }
        return sb.toString();
    }

    private double toD(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    private Double toDOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return null; }
    }

    /** Evaluate a single-measure alert over its trailing window. Caller must have set TenantContext. */
    public double evaluateAlert(ExplorerAlert a) {
        ExplorerQuery q = new ExplorerQuery();
        q.setDimensions(List.of());
        q.setMeasures(List.of(a.getMeasureKey()));
        try {
            if (a.getCalcJson() != null && !a.getCalcJson().isBlank())
                applyCustomMeasures(q, objectMapper.readValue(a.getCalcJson(), new TypeReference<List<Object>>() {}));
            if (a.getFilterJson() != null && !a.getFilterJson().isBlank())
                q.setFilters(objectMapper.readValue(a.getFilterJson(), new TypeReference<Map<String, List<String>>>() {}));
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad alert config: " + e.getMessage());
        }
        int wd = a.getWindowDays() != null && a.getWindowDays() > 0 ? a.getWindowDays() : 1;
        LocalDate today = LocalDate.now();
        q.setStartDate(today.minusDays(wd - 1).toString());
        q.setEndDate(today.toString());
        q.setLimit(1);
        List<Map<String, Object>> data = runRaw(q);
        if (data.isEmpty()) return 0d;
        Object v = data.get(0).get(sanitizeAlias(a.getMeasureKey()));
        return v instanceof Number ? ((Number) v).doubleValue() : 0d;
    }

    /** Threshold comparison used by the alert evaluator. */
    public static boolean breaches(String op, double value, double threshold) {
        if (op == null) return false;
        switch (op) {
            case ">":  return value > threshold;
            case ">=": return value >= threshold;
            case "<":  return value < threshold;
            case "<=": return value <= threshold;
            case "==": case "=": return value == threshold;
            case "!=": return value != threshold;
            default:   return false;
        }
    }

    private String asJson(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s.isBlank() ? null : s;
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private String currentUsername() {
        try { return SecurityContextHolder.getContext().getAuthentication().getName(); }
        catch (Exception e) { return "system"; }
    }

    /** Returns the field catalog — what's available to drag and drop. */
    @GetMapping("/fields")
    public ResponseEntity<Map<String, Object>> getFieldCatalog() {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("merchantFields", MERCHANT_FIELDS.entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "label", e.getValue().label, "category", e.getValue().category))
            .collect(Collectors.toList()));
        catalog.put("transactionFields", CONTEXT_FIELDS.entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "label", e.getValue().label, "category", e.getValue().category))
            .collect(Collectors.toList()));
        catalog.put("measures", MEASURE_DEFS.keySet().stream()
            .map(k -> Map.of("key", k, "label", formatMeasureLabel(k)))
            .collect(Collectors.toList()));
        catalog.put("measureColumns", AGG_COLUMNS.keySet().stream()
            .map(k -> Map.of("key", k, "label", AGG_COLUMN_LABELS.getOrDefault(k, k), "kind", AGG_COLUMN_KINDS.getOrDefault(k, "amount")))
            .collect(Collectors.toList()));
        catalog.put("aggsByKind", Map.of(
            "amount", List.of("SUM", "AVG", "MIN", "MAX", "MEDIAN", "STDDEV", "P90", "P95"),
            "id", List.of("COUNT_DISTINCT"),
            "rows", List.of("COUNT")));
        return ResponseEntity.ok(catalog);
    }

    /**
     * DISTINCT values for a field (filter dropdowns), from the cheapest correct source:
     * the dim table for entity fields, the summary table for summary-capable txn fields,
     * the fact table only for fact-only txn fields. All tenant-scoped.
     */
    @GetMapping("/distinct/{fieldKey}")
    public ResponseEntity<?> getDistinctValues(@PathVariable String fieldKey,
                                                @RequestParam(defaultValue = "200") int limit) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        FieldDef def = resolveField(fieldKey);
        if (def == null) return ResponseEntity.badRequest().body(Map.of("error", "Unknown field: " + fieldKey));

        final String table;
        final String expr;
        switch (def.src) {
            case MERCHANT -> { table = "dim_merchant"; expr = stripAlias(def.factSql, "dm."); }
            case STORE    -> { table = "dim_store";    expr = stripAlias(def.factSql, "ds."); }
            case TERMINAL -> { table = "dim_terminal"; expr = stripAlias(def.factSql, "dt."); }
            default -> {
                if (def.summarySql != null) { table = "sum_daily_insight s"; expr = def.summarySql; } // smaller scan
                else                        { table = "fact_transaction t";  expr = def.factSql; }
            }
        }

        String sql = "SELECT DISTINCT " + expr + " AS val FROM " + table +
                     " WHERE tenant_id = ? AND " + expr + " IS NOT NULL" +
                     " ORDER BY val LIMIT ?";
        try {
            return ResponseEntity.ok(jdbcTemplate.queryForList(sql, String.class, tenantId, limit));
        } catch (Exception e) {
            logger.error("Failed to fetch distinct values for {}: {}", fieldKey, e.getMessage());
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /** Core query endpoint — chooses the grain, then executes the aggregation. */
    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody ExplorerQuery query) {
        if (query.getTimeMeasures() != null && !query.getTimeMeasures().isEmpty())
            return executeWithTimeIntelligence(query);
        return executeCore(query);
    }

    private ResponseEntity<?> executeCore(ExplorerQuery query) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        List<String> dimensions = query.getDimensions();
        if (dimensions == null || dimensions.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "At least one dimension is required"));
        if (dimensions.size() > 5)
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 5 dimensions allowed"));

        List<String> measures = (query.getMeasures() == null || query.getMeasures().isEmpty())
            ? List.of("txn_count", "total_volume", "total_msf") : query.getMeasures();

        // Compile any user-defined calculated measures (arithmetic over base measures).
        Map<String, String> calcSqlByKey = new LinkedHashMap<>();
        LinkedHashSet<String> referencedBase = new LinkedHashSet<>();
        if (query.getCalcMeasures() != null) {
            for (CalcMeasure cm : query.getCalcMeasures()) {
                if (cm == null || cm.getKey() == null || cm.getFormula() == null) continue;
                try {
                    calcSqlByKey.put(cm.getKey(), compileFormula(cm.getFormula(), referencedBase));
                } catch (IllegalArgumentException ex) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid formula for '" + cm.getKey() + "': " + ex.getMessage()));
                }
            }
        }

        // Compile any user-defined aggregation measures (column + aggregation, optional set-analysis
        // condition). Fact-grain only. Condition values are parameterized; field/agg/column whitelisted.
        Map<String, String> aggSqlByKey = new LinkedHashMap<>();
        Map<String, List<Object>> aggParamsByKey = new LinkedHashMap<>();
        EnumSet<Src> aggNeeded = EnumSet.noneOf(Src.class);
        if (query.getAggMeasures() != null) {
            for (AggMeasure am : query.getAggMeasures()) {
                if (am == null || am.getKey() == null) continue;
                try {
                    String condSql = null;
                    List<Object> condParams = new ArrayList<>();
                    if (am.getFilterField() != null && am.getFilterValues() != null && !am.getFilterValues().isEmpty()) {
                        FieldDef cf = resolveField(am.getFilterField());
                        if (cf == null) throw new IllegalArgumentException("unknown condition field '" + am.getFilterField() + "'");
                        if (cf.src != Src.TXN) aggNeeded.add(cf.src);
                        String ph = am.getFilterValues().stream().map(v -> "?").collect(Collectors.joining(", "));
                        condSql = exprFor(cf, Grain.FACT) + " IN (" + ph + ")";
                        condParams.addAll(am.getFilterValues());
                    }
                    aggSqlByKey.put(am.getKey(), compileAgg(am.getColumn(), am.getAgg(), condSql));
                    aggParamsByKey.put(am.getKey(), condParams);
                } catch (IllegalArgumentException ex) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid measure '" + am.getKey() + "': " + ex.getMessage()));
                }
            }
        }

        // Resolve dimensions.
        List<FieldDef> dimDefs = new ArrayList<>();
        for (String dim : dimensions) {
            FieldDef d = resolveField(dim);
            if (d == null) return ResponseEntity.badRequest().body(Map.of("error", "Unknown dimension: " + dim));
            dimDefs.add(d);
        }

        // Every requested measure must be a known base, calc, or aggregation measure.
        for (String m : measures) {
            if (!MEASURE_DEFS.containsKey(m) && !calcSqlByKey.containsKey(m) && !aggSqlByKey.containsKey(m))
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown measure: " + m));
        }

        // Base-grain measures needed = requested base + requested aggregations + base measures referenced by formulas.
        LinkedHashSet<String> baseMeasureKeys = new LinkedHashSet<>();
        for (String m : measures) if (MEASURE_DEFS.containsKey(m) || aggSqlByKey.containsKey(m)) baseMeasureKeys.add(m);
        baseMeasureKeys.addAll(referencedBase);
        if (baseMeasureKeys.isEmpty()) baseMeasureKeys.add("txn_count");
        List<String> baseMeasList = new ArrayList<>(baseMeasureKeys);
        List<MeasureDef> measDefs = baseMeasList.stream().filter(MEASURE_DEFS::containsKey).map(MEASURE_DEFS::get).collect(Collectors.toList());
        boolean hasAgg = baseMeasList.stream().anyMatch(aggSqlByKey::containsKey);
        List<FieldDef> filterDefs = new ArrayList<>();
        if (query.getFilters() != null) {
            for (String fk : query.getFilters().keySet()) {
                FieldDef d = resolveField(fk);
                if (d != null) filterDefs.add(d);
            }
        }

        // ----- Grain selection -----
        boolean hasAmountFilter = query.getAmountFilters() != null && !query.getAmountFilters().isEmpty();
        boolean canSummary = !hasAmountFilter
            && !hasAgg
            && dimDefs.stream().allMatch(d -> d.summarySql != null)
            && measDefs.stream().allMatch(m -> m.summarySql != null)
            && filterDefs.stream().allMatch(d -> d.summarySql != null);
        Grain grain = canSummary ? Grain.SUMMARY : Grain.FACT;

        String base = grain == Grain.SUMMARY ? "s" : "t";
        String baseTable = grain == Grain.SUMMARY ? "sum_daily_insight s" : "fact_transaction t";
        String dateCol = grain == Grain.SUMMARY ? "s.business_date" : "t.payment_date";

        // SELECT + GROUP BY
        List<String> selectParts = new ArrayList<>();
        List<String> groupParts = new ArrayList<>();
        EnumSet<Src> needed = EnumSet.noneOf(Src.class);
        for (int i = 0; i < dimensions.size(); i++) {
            FieldDef d = dimDefs.get(i);
            if (d.src != Src.TXN) needed.add(d.src);
            String expr = exprFor(d, grain);
            selectParts.add(expr + " AS " + sanitizeAlias(dimensions.get(i)));
            groupParts.add(expr);
        }
        List<Object> selectParams = new ArrayList<>();
        for (String mk : baseMeasList) {
            if (MEASURE_DEFS.containsKey(mk)) {
                selectParts.add(measureExpr(MEASURE_DEFS.get(mk), grain) + " AS " + sanitizeAlias(mk));
            } else {
                selectParts.add(aggSqlByKey.get(mk) + " AS " + sanitizeAlias(mk));
                List<Object> ap = aggParamsByKey.get(mk);
                if (ap != null) selectParams.addAll(ap);
            }
        }
        for (FieldDef d : filterDefs) if (d.src != Src.TXN) needed.add(d.src);
        needed.addAll(aggNeeded);

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", selectParts));
        sql.append(" FROM ").append(baseTable).append(" ");
        appendJoins(sql, base, needed);

        sql.append("WHERE ").append(base).append(".tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.addAll(selectParams);  // filtered-aggregation condition values appear first (SELECT clause)
        params.add(tenantId);

        // Date range — DATE compare on the summary grain, TIMESTAMP on the fact grain.
        if (query.getStartDate() != null && !query.getStartDate().isEmpty()) {
            sql.append("AND ").append(dateCol).append(grain == Grain.SUMMARY ? " >= CAST(? AS DATE) " : " >= CAST(? AS TIMESTAMP) ");
            params.add(query.getStartDate());
        }
        if (query.getEndDate() != null && !query.getEndDate().isEmpty()) {
            if (grain == Grain.SUMMARY) { sql.append("AND ").append(dateCol).append(" <= CAST(? AS DATE) "); }
            else { sql.append("AND ").append(dateCol).append(" < CAST(? AS TIMESTAMP) + INTERVAL '1 day' "); }
            params.add(query.getEndDate());
        }

        if (query.getFilters() != null) {
            for (Map.Entry<String, List<String>> entry : query.getFilters().entrySet()) {
                FieldDef d = resolveField(entry.getKey());
                if (d != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    String placeholders = entry.getValue().stream().map(v -> "?").collect(Collectors.joining(","));
                    sql.append("AND ").append(exprFor(d, grain)).append(" IN (").append(placeholders).append(") ");
                    params.addAll(entry.getValue());
                }
            }
        }

        // Amount filters force the FACT grain (above), so these always reference t.*
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

        if (!groupParts.isEmpty()) sql.append("GROUP BY ").append(String.join(", ", groupParts)).append(" ");

        int limit = query.getLimit() != null && query.getLimit() > 0 ? Math.min(query.getLimit(), 5000) : 1000;
        String orderAlias = sanitizeAlias(measures.get(0));

        // No calc measures → single aggregation. Otherwise wrap and compute the
        // calc expressions in an outer SELECT over the base aggregation.
        String finalSql;
        if (calcSqlByKey.isEmpty()) {
            finalSql = sql + " ORDER BY " + orderAlias + " DESC LIMIT ?";
        } else {
            List<String> outer = new ArrayList<>();
            for (int i = 0; i < dimensions.size(); i++) outer.add("base." + sanitizeAlias(dimensions.get(i)));
            for (String m : measures) {
                if (calcSqlByKey.containsKey(m)) outer.add("(" + calcSqlByKey.get(m) + ") AS " + sanitizeAlias(m));
                else outer.add("base." + sanitizeAlias(m));
            }
            finalSql = "SELECT " + String.join(", ", outer) + " FROM (" + sql + ") base ORDER BY " + orderAlias + " DESC LIMIT ?";
        }
        params.add(limit);

        logger.debug("Explorer SQL [{}{}]: {}", grain, calcSqlByKey.isEmpty() ? "" : "+calc", finalSql);

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(finalSql, params.toArray());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("data", results);
            response.put("rowCount", results.size());
            response.put("dimensions", dimensions);
            response.put("measures", measures);
            response.put("grain", grain.name().toLowerCase()); // "summary" | "fact" — observability
            return ResponseEntity.ok(currencyMeta.attach(response, tenantId));
        } catch (Exception e) {
            logger.error("Explorer query failed [{}]: {}", grain, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Query failed: " + e.getMessage()));
        }
    }

    /**
     * Merchant-only query — entity analysis straight off dim_merchant (incl. zero-txn
     * merchants). If transaction measures or non-merchant fields are involved, delegate
     * to the grain-aware fact/summary query so the numbers are real and reconcile.
     */
    @PostMapping("/query/merchants")
    public ResponseEntity<?> queryMerchants(@RequestBody ExplorerQuery query) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));

        List<String> dimensions = query.getDimensions();
        if (dimensions == null || dimensions.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "At least one dimension is required"));

        boolean merchantOnly = dimensions.stream().allMatch(MERCHANT_FIELDS::containsKey)
            && (query.getFilters() == null || query.getFilters().keySet().stream().allMatch(MERCHANT_FIELDS::containsKey));
        boolean wantsTxnMeasures = query.getMeasures() != null
            && query.getMeasures().stream().anyMatch(MEASURE_DEFS::containsKey);
        if (!merchantOnly || wantsTxnMeasures) return executeQuery(query);

        List<String> selectParts = new ArrayList<>();
        List<String> groupParts = new ArrayList<>();
        for (String dim : dimensions) {
            FieldDef def = MERCHANT_FIELDS.get(dim);
            if (def == null) continue;
            String col = stripAlias(def.factSql, "dm.");
            selectParts.add(col + " AS " + sanitizeAlias(dim));
            groupParts.add(col);
        }
        selectParts.add("COUNT(*) AS record_count");

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", selectParts));
        sql.append(" FROM dim_merchant WHERE tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (query.getFilters() != null) {
            for (Map.Entry<String, List<String>> entry : query.getFilters().entrySet()) {
                FieldDef def = MERCHANT_FIELDS.get(entry.getKey());
                if (def != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    String col = stripAlias(def.factSql, "dm.");
                    String placeholders = entry.getValue().stream().map(v -> "?").collect(Collectors.joining(","));
                    sql.append("AND ").append(col).append(" IN (").append(placeholders).append(") ");
                    params.addAll(entry.getValue());
                }
            }
        }
        if (!groupParts.isEmpty()) sql.append("GROUP BY ").append(String.join(", ", groupParts));
        sql.append(" ORDER BY record_count DESC LIMIT 1000");

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            // Same keys as before (Map.of was immutable, so it could not take the
            // currency block) plus "currency".
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("data", results);
            response.put("rowCount", results.size());
            response.put("dimensions", dimensions);
            return ResponseEntity.ok(currencyMeta.attach(response, tenantId));
        } catch (Exception e) {
            logger.error("Merchant explorer query failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Query failed: " + e.getMessage()));
        }
    }

    /**
     * ASSOCIATIVE STATE — the Qlik-style green/white/gray engine.
     *
     * Given the current selections (the same filters map the UI already holds),
     * returns for each requested field the state of every value:
     *   selected  (green) — currently chosen in that field
     *   possible  (white) — still reachable given selections in OTHER fields
     *   excluded  (gray)  — exists, but not reachable given the other fields' selections
     *
     * Rule of associativity: a field's OWN selection never restricts its own
     * possible set (selections within a field are OR; across fields they are AND).
     * So when scoring field F we apply every selection EXCEPT F's own.
     */
    @PostMapping("/associative")
    public ResponseEntity<?> associative(@RequestBody AssocRequest req) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        if (req.getFields() == null || req.getFields().isEmpty())
            return ResponseEntity.ok(Map.of("states", Collections.emptyMap()));

        Map<String, List<String>> selections = req.getFilters() == null ? Collections.emptyMap() : req.getFilters();
        List<String> fields = req.getFields().stream().distinct().limit(20).collect(Collectors.toList());

        Map<String, Object> states = new LinkedHashMap<>();
        for (String fk : fields) {
            FieldDef target = resolveField(fk);
            if (target == null) continue;

            List<String> selected = selections.getOrDefault(fk, Collections.emptyList());

            // Selections from OTHER fields only (drop this field's own selection).
            Map<String, List<String>> others = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : selections.entrySet()) {
                if (!e.getKey().equals(fk) && e.getValue() != null && !e.getValue().isEmpty()) {
                    others.put(e.getKey(), e.getValue());
                }
            }

            List<String> fullSet = distinctAssoc(tenantId, target, Collections.emptyMap(), req.getStartDate(), req.getEndDate());
            List<String> possibleSet = others.isEmpty()
                ? fullSet
                : distinctAssoc(tenantId, target, others, req.getStartDate(), req.getEndDate());

            Set<String> selSet = new HashSet<>(selected);
            Set<String> possSet = new HashSet<>(possibleSet);

            // Union of the full domain and any selected values (a selected value may
            // sit outside the current date window but should still show as selected).
            LinkedHashSet<String> all = new LinkedHashSet<>(fullSet);
            all.addAll(selected);

            List<Map<String, String>> values = new ArrayList<>();
            for (String v : all) {
                String state = selSet.contains(v) ? "selected" : (possSet.contains(v) ? "possible" : "excluded");
                values.add(Map.of("value", v == null ? "" : v, "state", state));
            }
            Map<String, Object> fieldState = new LinkedHashMap<>();
            fieldState.put("values", values);
            fieldState.put("selectedCount", selected.size());
            fieldState.put("possibleCount", possSet.size());
            states.put(fk, fieldState);
        }
        return ResponseEntity.ok(Map.of("states", states));
    }

    // ========== Helpers ==========

    private FieldDef resolveField(String key) {
        FieldDef def = MERCHANT_FIELDS.get(key);
        if (def == null) def = CONTEXT_FIELDS.get(key);
        return def;
    }

    private String exprFor(FieldDef d, Grain grain) {
        return grain == Grain.SUMMARY && d.summarySql != null ? d.summarySql : d.factSql;
    }

    private String measureExpr(MeasureDef m, Grain grain) {
        return grain == Grain.SUMMARY && m.summarySql != null ? m.summarySql : m.factSql;
    }

    private String stripAlias(String expr, String alias) { return expr.replace(alias, ""); }

    /** Appends the dim joins needed for the given base alias (t or s), tenant-scoped. */
    private void appendJoins(StringBuilder sql, String base, EnumSet<Src> needed) {
        if (needed.contains(Src.MERCHANT))
            sql.append("LEFT JOIN dim_merchant dm ON dm.merchant_id = ").append(base).append(".merchant_id AND dm.tenant_id = ").append(base).append(".tenant_id ");
        if (needed.contains(Src.STORE))
            sql.append("LEFT JOIN dim_store ds ON ds.store_id = ").append(base).append(".store_id AND ds.tenant_id = ").append(base).append(".tenant_id ");
        if (needed.contains(Src.TERMINAL))
            sql.append("LEFT JOIN dim_terminal dt ON dt.terminal_id = ").append(base).append(".terminal_id AND dt.tenant_id = ").append(base).append(".tenant_id ");
    }

    /**
     * DISTINCT values of {@code target}, constrained by {@code otherFilters} and the date
     * window. Used by the associative engine to compute the "possible" set. Picks the
     * summary grain when the target and all constraining fields are summary-capable.
     */
    private List<String> distinctAssoc(Long tenantId, FieldDef target,
                                       Map<String, List<String>> otherFilters,
                                       String startDate, String endDate) {
        boolean canSummary = target.summarySql != null;
        EnumSet<Src> needed = EnumSet.noneOf(Src.class);
        if (target.src != Src.TXN) needed.add(target.src);
        for (String k : otherFilters.keySet()) {
            FieldDef d = resolveField(k);
            if (d == null) continue;
            if (d.summarySql == null) canSummary = false;
            if (d.src != Src.TXN) needed.add(d.src);
        }
        Grain grain = canSummary ? Grain.SUMMARY : Grain.FACT;
        String b = grain == Grain.SUMMARY ? "s" : "t";
        String baseTable = grain == Grain.SUMMARY ? "sum_daily_insight s" : "fact_transaction t";
        String dateCol = grain == Grain.SUMMARY ? "s.business_date" : "t.payment_date";
        String tExpr = exprFor(target, grain);

        StringBuilder sql = new StringBuilder("SELECT DISTINCT ").append(tExpr).append(" AS val FROM ").append(baseTable).append(" ");
        appendJoins(sql, b, needed);
        sql.append("WHERE ").append(b).append(".tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (startDate != null && !startDate.isEmpty()) {
            sql.append("AND ").append(dateCol).append(grain == Grain.SUMMARY ? " >= CAST(? AS DATE) " : " >= CAST(? AS TIMESTAMP) ");
            params.add(startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            sql.append("AND ").append(dateCol).append(grain == Grain.SUMMARY ? " <= CAST(? AS DATE) " : " < CAST(? AS TIMESTAMP) + INTERVAL '1 day' ");
            params.add(endDate);
        }
        for (Map.Entry<String, List<String>> e : otherFilters.entrySet()) {
            FieldDef d = resolveField(e.getKey());
            if (d != null && e.getValue() != null && !e.getValue().isEmpty()) {
                String ph = e.getValue().stream().map(v -> "?").collect(Collectors.joining(","));
                sql.append("AND ").append(exprFor(d, grain)).append(" IN (").append(ph).append(") ");
                params.addAll(e.getValue());
            }
        }
        sql.append("AND ").append(tExpr).append(" IS NOT NULL ORDER BY val LIMIT 500");
        try {
            return jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray());
        } catch (Exception ex) {
            logger.error("Associative distinct failed: {}", ex.getMessage());
            return Collections.emptyList();
        }
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

    private String sanitizeAlias(String key) { return key.replaceAll("[^a-zA-Z0-9_]", "_"); }

    // ----- calculated-measure formula compiler (safe: only whitelisted base measures) -----
    // Compile a user-defined aggregation measure (column + aggregation) to fact-grain SQL.
    // Only whitelisted columns and aggregations are accepted; user text never reaches SQL.
    private String compileAgg(String column, String agg) { return compileAgg(column, agg, null); }

    private String compileAgg(String column, String agg, String condSql) {
        if (agg == null) throw new IllegalArgumentException("aggregation is required");
        String a = agg.trim().toUpperCase();
        if (!AGG_FUNCS.contains(a)) throw new IllegalArgumentException("unknown aggregation '" + agg + "'");
        if (a.equals("COUNT"))
            return condSql == null ? "COUNT(*)" : "COUNT(CASE WHEN " + condSql + " THEN 1 END)";
        String col = AGG_COLUMNS.get(column);
        if (col == null || col.equals("*")) throw new IllegalArgumentException("unknown column '" + column + "'");
        String x = condSql == null ? col : "CASE WHEN " + condSql + " THEN " + col + " END";
        switch (a) {
            case "SUM":            return "COALESCE(SUM(" + x + "), 0)";
            case "AVG":            return "AVG(" + x + ")";
            case "MIN":            return "MIN(" + x + ")";
            case "MAX":            return "MAX(" + x + ")";
            case "STDDEV":         return "STDDEV(" + x + ")";
            case "MEDIAN":         return "percentile_cont(0.5) WITHIN GROUP (ORDER BY " + x + ")";
            case "P90":            return "percentile_cont(0.9) WITHIN GROUP (ORDER BY " + x + ")";
            case "P95":            return "percentile_cont(0.95) WITHIN GROUP (ORDER BY " + x + ")";
            case "COUNT_DISTINCT": return "COUNT(DISTINCT " + x + ")";
            default: throw new IllegalArgumentException("unsupported aggregation '" + agg + "'");
        }
    }

    /**
     * Split a generic list of custom-measure definition maps (as stored in saved
     * views / report templates / alerts) into typed calc + agg measures on the
     * query. Calc entries carry a "formula"; agg entries carry "column"/"agg".
     */
    public void applyCustomMeasures(ExplorerQuery q, java.util.List<?> defs) {
        if (defs == null) return;
        java.util.List<CalcMeasure> calcs = new ArrayList<>();
        java.util.List<AggMeasure> aggs = new ArrayList<>();
        for (Object o : defs) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            String key = m.get("key") == null ? null : m.get("key").toString();
            String label = m.get("label") == null ? null : m.get("label").toString();
            Object formula = m.get("formula");
            Object column = m.get("column");
            if (formula != null && !formula.toString().isBlank()) {
                CalcMeasure c = new CalcMeasure(); c.setKey(key); c.setLabel(label); c.setFormula(formula.toString());
                calcs.add(c);
            } else if (column != null) {
                AggMeasure ag = new AggMeasure(); ag.setKey(key); ag.setLabel(label);
                ag.setColumn(column.toString());
                ag.setAgg(m.get("agg") == null ? null : m.get("agg").toString());
                Object ff = m.get("filterField");
                if (ff != null) ag.setFilterField(ff.toString());
                Object fv = m.get("filterValues");
                if (fv instanceof java.util.List) {
                    java.util.List<String> vals = new ArrayList<>();
                    for (Object o2 : (java.util.List<?>) fv) if (o2 != null) vals.add(o2.toString());
                    ag.setFilterValues(vals);
                }
                aggs.add(ag);
            }
        }
        if (!calcs.isEmpty()) q.setCalcMeasures(calcs);
        if (!aggs.isEmpty()) q.setAggMeasures(aggs);
    }

    private String compileFormula(String formula, java.util.Set<String> referenced) {
        java.util.List<String> toks = tokenizeFormula(formula);
        if (toks.isEmpty()) throw new IllegalArgumentException("empty formula");
        int[] pos = {0};
        String sql = parseExpr(toks, pos, referenced);
        if (pos[0] != toks.size()) throw new IllegalArgumentException("unexpected token '" + toks.get(pos[0]) + "'");
        return sql;
    }
    private java.util.List<String> tokenizeFormula(String f) {
        java.util.List<String> toks = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\s*([0-9]*\\.?[0-9]+|[A-Za-z_][A-Za-z0-9_]*|[-+*/(),])").matcher(f);
        int last = 0;
        while (m.find()) {
            if (!f.substring(last, m.start()).trim().isEmpty())
                throw new IllegalArgumentException("invalid input near '" + f.substring(last).trim() + "'");
            toks.add(m.group(1));
            last = m.end();
        }
        if (!f.substring(last).trim().isEmpty())
            throw new IllegalArgumentException("invalid input near '" + f.substring(last).trim() + "'");
        return toks;
    }
    private String parseExpr(java.util.List<String> t, int[] p, java.util.Set<String> ref) {
        String left = parseTerm(t, p, ref);
        while (p[0] < t.size() && (t.get(p[0]).equals("+") || t.get(p[0]).equals("-"))) {
            String op = t.get(p[0]++);
            left = "(" + left + " " + op + " " + parseTerm(t, p, ref) + ")";
        }
        return left;
    }
    private String parseTerm(java.util.List<String> t, int[] p, java.util.Set<String> ref) {
        String left = parseFactor(t, p, ref);
        while (p[0] < t.size() && (t.get(p[0]).equals("*") || t.get(p[0]).equals("/"))) {
            String op = t.get(p[0]++);
            String right = parseFactor(t, p, ref);
            left = op.equals("/") ? "(" + left + " / NULLIF(" + right + ", 0))" : "(" + left + " * " + right + ")";
        }
        return left;
    }
    private String parseFactor(java.util.List<String> t, int[] p, java.util.Set<String> ref) {
        if (p[0] >= t.size()) throw new IllegalArgumentException("unexpected end of formula");
        String tok = t.get(p[0]);
        if (tok.equals("(")) {
            p[0]++;
            String inner = parseExpr(t, p, ref);
            if (p[0] >= t.size() || !t.get(p[0]).equals(")")) throw new IllegalArgumentException("missing ')'");
            p[0]++;
            return "(" + inner + ")";
        }
        if (tok.matches("[0-9]*\\.?[0-9]+")) { p[0]++; return tok; }
        if (tok.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            // Function call: identifier immediately followed by '('
            if (p[0] + 1 < t.size() && t.get(p[0] + 1).equals("(")) {
                String fn = tok.toUpperCase();
                if (!FORMULA_FUNCS.contains(fn)) throw new IllegalArgumentException("unknown function '" + tok + "'");
                p[0] += 2; // consume name + '('
                java.util.List<String> args = new ArrayList<>();
                if (p[0] < t.size() && !t.get(p[0]).equals(")")) {
                    args.add(parseExpr(t, p, ref));
                    while (p[0] < t.size() && t.get(p[0]).equals(",")) { p[0]++; args.add(parseExpr(t, p, ref)); }
                }
                if (p[0] >= t.size() || !t.get(p[0]).equals(")")) throw new IllegalArgumentException("missing ')' in " + fn + "()");
                p[0]++;
                return emitFunc(fn, args);
            }
            if (!MEASURE_DEFS.containsKey(tok)) throw new IllegalArgumentException("unknown measure '" + tok + "'");
            ref.add(tok);
            p[0]++;
            return sanitizeAlias(tok);
        }
        throw new IllegalArgumentException("unexpected token '" + tok + "'");
    }

    // Emit SQL for a whitelisted formula function. Args are already-compiled SQL fragments.
    private String emitFunc(String fn, java.util.List<String> args) {
        switch (fn) {
            case "ABS":
                if (args.size() != 1) throw new IllegalArgumentException("ABS() takes 1 argument");
                return "ABS(" + args.get(0) + ")";
            case "ROUND":
                // CAST(... AS ...) not ::  — Hibernate mangles the `::` cast in native
                // queries (sends a single colon → syntax error). See TrendsController.
                if (args.size() == 1) return "ROUND(CAST(" + args.get(0) + " AS numeric))";
                if (args.size() == 2) return "ROUND(CAST(" + args.get(0) + " AS numeric), CAST(" + args.get(1) + " AS int))";
                throw new IllegalArgumentException("ROUND() takes 1 or 2 arguments");
            case "COALESCE":
                if (args.isEmpty()) throw new IllegalArgumentException("COALESCE() needs at least 1 argument");
                return "COALESCE(" + String.join(", ", args) + ")";
            case "LEAST":
                if (args.size() < 2) throw new IllegalArgumentException("LEAST() needs at least 2 arguments");
                return "LEAST(" + String.join(", ", args) + ")";
            case "GREATEST":
                if (args.size() < 2) throw new IllegalArgumentException("GREATEST() needs at least 2 arguments");
                return "GREATEST(" + String.join(", ", args) + ")";
            default:
                throw new IllegalArgumentException("unknown function '" + fn + "'");
        }
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
            case "net_revenue" -> "Net Margin";
            case "effective_msf_rate" -> "Effective MSF Rate (bps)";
            case "avg_msf_per_txn" -> "Avg MSF / Txn";
            case "interchange_rate" -> "Interchange Rate (bps)";
            case "settlement_ratio" -> "Settlement Ratio (%)";
            default -> key;
        };
    }

    // ----- field/measure factory helpers -----
    private static FieldDef dim(String sql, String label, String category, Src src) {
        return new FieldDef(sql, sql, label, category, src); // same expr on both grains
    }
    private static FieldDef txn(String factSql, String summarySql, String label, String category) {
        return new FieldDef(factSql, summarySql, label, category, Src.TXN);
    }
    private static MeasureDef meas(String factSql, String summarySql) {
        return new MeasureDef(factSql, summarySql);
    }

    // ========== Inner classes ==========

    private static class FieldDef {
        final String factSql;
        final String summarySql; // null ⇒ not available on the summary grain
        final String label;
        final String category;
        final Src src;
        FieldDef(String factSql, String summarySql, String label, String category, Src src) {
            this.factSql = factSql; this.summarySql = summarySql;
            this.label = label; this.category = category; this.src = src;
        }
    }

    private static class MeasureDef {
        final String factSql;
        final String summarySql; // null ⇒ fact-only
        MeasureDef(String factSql, String summarySql) { this.factSql = factSql; this.summarySql = summarySql; }
    }

    public static class ExplorerQuery {
        private List<String> dimensions;
        private List<String> measures;
        private Map<String, List<String>> filters;
        private Map<String, double[]> amountFilters;
        private String startDate;
        private String endDate;
        private Integer limit;
        private List<CalcMeasure> calcMeasures;
        private List<AggMeasure> aggMeasures;
        private List<TimeMeasure> timeMeasures;

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
        public List<CalcMeasure> getCalcMeasures() { return calcMeasures; }
        public void setCalcMeasures(List<CalcMeasure> c) { this.calcMeasures = c; }
        public List<AggMeasure> getAggMeasures() { return aggMeasures; }
        public void setAggMeasures(List<AggMeasure> a) { this.aggMeasures = a; }
        public List<TimeMeasure> getTimeMeasures() { return timeMeasures; }
        public void setTimeMeasures(List<TimeMeasure> t) { this.timeMeasures = t; }
    }

    /** A time-intelligence measure: compares a base measure across two date windows. */
    public static class TimeMeasure {
        private String key;
        private String label;
        private String base;        // measure key to compare (base, agg, or calc)
        private String comparison;  // YOY | MOM | PREV
        private String mode;        // growth | delta | prior
        public String getKey() { return key; }
        public void setKey(String k) { this.key = k; }
        public String getLabel() { return label; }
        public void setLabel(String l) { this.label = l; }
        public String getBase() { return base; }
        public void setBase(String b) { this.base = b; }
        public String getComparison() { return comparison; }
        public void setComparison(String c) { this.comparison = c; }
        public String getMode() { return mode; }
        public void setMode(String m) { this.mode = m; }
    }

    /** A user-defined aggregation measure: an aggregation over a whitelisted column. */
    public static class AggMeasure {
        private String key;
        private String label;
        private String column;
        private String agg;
        private String filterField;
        private List<String> filterValues;
        public String getKey() { return key; }
        public void setKey(String k) { this.key = k; }
        public String getLabel() { return label; }
        public void setLabel(String l) { this.label = l; }
        public String getColumn() { return column; }
        public void setColumn(String c) { this.column = c; }
        public String getAgg() { return agg; }
        public void setAgg(String a) { this.agg = a; }
        public String getFilterField() { return filterField; }
        public void setFilterField(String f) { this.filterField = f; }
        public List<String> getFilterValues() { return filterValues; }
        public void setFilterValues(List<String> v) { this.filterValues = v; }
    }

    /** A user-defined calculated measure: arithmetic over base measure keys. */
    public static class CalcMeasure {
        private String key;
        private String label;
        private String formula;
        public String getKey() { return key; }
        public void setKey(String k) { this.key = k; }
        public String getLabel() { return label; }
        public void setLabel(String l) { this.label = l; }
        public String getFormula() { return formula; }
        public void setFormula(String f) { this.formula = f; }
    }

    /** Request body for the associative-state endpoint. */
    public static class AssocRequest {
        private List<String> fields;
        private Map<String, List<String>> filters;
        private String startDate;
        private String endDate;

        public List<String> getFields() { return fields; }
        public void setFields(List<String> f) { this.fields = f; }
        public Map<String, List<String>> getFilters() { return filters; }
        public void setFilters(Map<String, List<String>> f) { this.filters = f; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String s) { this.startDate = s; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String e) { this.endDate = e; }
    }
}
