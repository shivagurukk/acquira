package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Transaction Trends Hub backing controller.
 *
 * Serves the two rollup endpoints the /trends/hub page calls:
 *   POST /api/trends/monthly  → one row per month for the selected year/preset
 *   POST /api/trends/daily    → one row per business_date within a month
 *
 * These endpoints previously did NOT exist — the TransactionTrendsHub page POSTed
 * to /trends/monthly and /trends/daily, got 404, swallowed it in its catch block,
 * and rendered "No data for selected period". This controller supplies them.
 *
 * DATA SOURCE: sum_daily_insight (per project data-sourcing rules). It carries the
 * dimensional columns the page's drawer filters need — MCC (via dim_store), RM
 * (dim_merchant.sales_email), MID (dim_merchant.mid) and is_opt_in — none of which
 * exist at the bank grain. Volume is cardholder-currency here (consistent with the
 * Interactive Explorer / Insight Hub, which also read this table). The level-3
 * merchant drill on the page is served separately by /api/finance/profitability
 * (settlement grain) — the two levels intentionally use different currencies, same
 * as elsewhere in the app.
 *
 * OPT-IN VOLUME = SUM(total_volume) WHERE is_opt_in = TRUE, surfaced as opt_in_volume.
 *
 * Additive & isolated: brand-new controller, native read-only queries, touches
 * nothing else. Tenant scoped on the base table and pushed onto every dim join.
 */
@RestController
@RequestMapping("/api/trends")
@PreAuthorize("@menuAccess.canAccess('/trends/hub')")
public class TrendsController {

    @PersistenceContext
    private EntityManager entityManager;

    /** Stamps the tenant's currency onto every money-bearing response. */
    @org.springframework.beans.factory.annotation.Autowired
    private CurrencyMeta currencyMeta;

    private Long resolveTenant(Long headerTenant) {
        // SECURITY: the raw X-Tenant-Id header is attacker-controlled; use only the
        // filter-validated TenantContext (JwtRequestFilter rejects spoofed headers).
        return TenantContext.getCurrentTenant();
    }

    // ── DTO mirroring the page's filter payload (all optional) ──
    public static class TrendFilter {
        private String datePreset;      // CURRENT_YEAR | PREVIOUS_YEAR | CUSTOM
        private String dateFrom;        // yyyy-MM-dd (CUSTOM)
        private String dateTo;          // yyyy-MM-dd (CUSTOM)
        private Integer year;           // used when preset is year-based
        private Integer month;          // only on /daily
        private List<String> mcc;
        private List<String> rm;
        private List<String> mid;
        private String optStatus;       // ALL | OPT_IN | OPT_OUT

        public String getDatePreset() { return datePreset; }
        public void setDatePreset(String v) { this.datePreset = v; }
        public String getDateFrom() { return dateFrom; }
        public void setDateFrom(String v) { this.dateFrom = v; }
        public String getDateTo() { return dateTo; }
        public void setDateTo(String v) { this.dateTo = v; }
        public Integer getYear() { return year; }
        public void setYear(Integer v) { this.year = v; }
        public Integer getMonth() { return month; }
        public void setMonth(Integer v) { this.month = v; }
        public List<String> getMcc() { return mcc; }
        public void setMcc(List<String> v) { this.mcc = v; }
        public List<String> getRm() { return rm; }
        public void setRm(List<String> v) { this.rm = v; }
        public List<String> getMid() { return mid; }
        public void setMid(List<String> v) { this.mid = v; }
        public String getOptStatus() { return optStatus; }
        public void setOptStatus(String v) { this.optStatus = v; }
    }

    /**
     * Monthly rollup for the selected window.
     * Returns rows: { month_num, month_name, year, count, volume, msf, opt_in_volume }
     * ordered by month ascending.
     */
    @PostMapping("/monthly")
    public ResponseEntity<?> monthly(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody(required = false) TrendFilter filter) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new TrendFilter();

        LocalDate[] range = resolveRange(filter, null);
        LocalDate start = range[0], end = range[1];

        List<Object> params = new ArrayList<>();
        boolean needStore    = listNonEmpty(filter.getMcc());
        boolean needMerchant = listNonEmpty(filter.getRm()) || listNonEmpty(filter.getMid());

        StringBuilder sql = new StringBuilder();
        // Use CAST(... AS int) not ::int — Hibernate's native-query parser mangles
        // the `::` Postgres cast (treats one colon as a named-param marker), sending
        // `:int` to the DB → "syntax error at or near :". This 500'd /trends/monthly.
        sql.append("SELECT CAST(EXTRACT(YEAR FROM s.business_date) AS int) AS yr, ");
        sql.append("       CAST(EXTRACT(MONTH FROM s.business_date) AS int) AS mo, ");
        sql.append("       COALESCE(SUM(s.total_txns),0) AS cnt, ");
        sql.append("       COALESCE(SUM(s.total_volume),0) AS vol, ");
        sql.append("       COALESCE(SUM(s.total_msf),0) AS msf, ");
        sql.append("       COALESCE(SUM(CASE WHEN s.is_opt_in THEN s.total_volume ELSE 0 END),0) AS optin_vol ");
        sql.append("FROM sum_daily_insight s ");
        if (needMerchant) sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore)    sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        sql.append("WHERE s.tenant_id = ? AND s.business_date >= ? AND s.business_date <= ? ");
        params.add(tenantId); params.add(start); params.add(end);
        appendFilters(sql, params, filter);
        sql.append("GROUP BY yr, mo ORDER BY yr, mo");

        Query q = entityManager.createNativeQuery(sql.toString());
        bind(q, params);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            int yr = ((Number) r[0]).intValue();
            int mo = ((Number) r[1]).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year", yr);
            m.put("month_num", mo);
            m.put("month_name", monthName(mo));
            m.put("count", ((Number) r[2]).longValue());
            m.put("volume", bd(r[3]));
            m.put("msf", bd(r[4]));
            m.put("opt_in_volume", bd(r[5]));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Daily rollup for one month.
     * Body carries month + year (page sends both). Returns rows:
     * { date (yyyy-MM-dd), count, volume, msf, opt_in_volume } ordered by date.
     */
    @PostMapping("/daily")
    public ResponseEntity<?> daily(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody(required = false) TrendFilter filter) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new TrendFilter();
        if (filter.getMonth() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "month is required for daily trend"));
        }

        LocalDate[] range = resolveRange(filter, filter.getMonth());
        LocalDate start = range[0], end = range[1];

        List<Object> params = new ArrayList<>();
        boolean needStore    = listNonEmpty(filter.getMcc());
        boolean needMerchant = listNonEmpty(filter.getRm()) || listNonEmpty(filter.getMid());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT s.business_date AS d, ");
        sql.append("       COALESCE(SUM(s.total_txns),0) AS cnt, ");
        sql.append("       COALESCE(SUM(s.total_volume),0) AS vol, ");
        sql.append("       COALESCE(SUM(s.total_msf),0) AS msf, ");
        sql.append("       COALESCE(SUM(CASE WHEN s.is_opt_in THEN s.total_volume ELSE 0 END),0) AS optin_vol ");
        sql.append("FROM sum_daily_insight s ");
        if (needMerchant) sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore)    sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        sql.append("WHERE s.tenant_id = ? AND s.business_date >= ? AND s.business_date <= ? ");
        params.add(tenantId); params.add(start); params.add(end);
        appendFilters(sql, params, filter);
        sql.append("GROUP BY s.business_date ORDER BY s.business_date");

        Query q = entityManager.createNativeQuery(sql.toString());
        bind(q, params);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", toDateStr(r[0]));
            m.put("count", ((Number) r[1]).longValue());
            m.put("volume", bd(r[2]));
            m.put("msf", bd(r[3]));
            m.put("opt_in_volume", bd(r[4]));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Merchant rollup for ONE business date — the page's level-3 drill.
     * <p>
     * This replaces the page's previous call to /api/finance/profitability,
     * which (a) read sum_daily_merchant — settlement grain — so merchant rows
     * never summed to the sum_daily_insight day row above them, (b) accepted
     * none of the page's filters, and (c) returned an unordered, silently
     * truncated 100 rows. Reading the SAME table with the SAME filter builder
     * makes children reconcile with their parent by construction.
     * <p>
     * Body carries dateFrom = dateTo = the drilled date. Returns
     * { totalMerchants, rows: [{ mid, name, count, volume, msf, opt_in_volume }] }
     * with rows = top 100 by volume desc.
     */
    @PostMapping("/merchants")
    public ResponseEntity<?> merchants(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenant,
            @RequestBody(required = false) TrendFilter filter) {

        Long tenantId = resolveTenant(headerTenant);
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new TrendFilter();
        LocalDate day = parse(filter.getDateFrom());
        if (day == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dateFrom (yyyy-MM-dd) is required for merchant trend"));
        }

        List<Object> params = new ArrayList<>();
        boolean needStore = listNonEmpty(filter.getMcc());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid, m.name, ");
        sql.append("       COALESCE(SUM(s.total_txns),0) AS cnt, ");
        sql.append("       COALESCE(SUM(s.total_volume),0) AS vol, ");
        sql.append("       COALESCE(SUM(s.total_msf),0) AS msf, ");
        sql.append("       COALESCE(SUM(CASE WHEN s.is_opt_in THEN s.total_volume ELSE 0 END),0) AS optin_vol ");
        sql.append("FROM sum_daily_insight s ");
        // dim_merchant is always needed here (name/mid are selected).
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        sql.append("WHERE s.tenant_id = ? AND s.business_date = ? ");
        params.add(tenantId); params.add(day);
        appendFilters(sql, params, filter);
        sql.append("GROUP BY m.mid, m.name ORDER BY vol DESC LIMIT 100");

        Query q = entityManager.createNativeQuery(sql.toString());
        bind(q, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        // Total distinct merchants for the day under the same filters, so the UI
        // can say "top 100 of N" instead of implying completeness.
        StringBuilder cSql = new StringBuilder();
        List<Object> cParams = new ArrayList<>();
        cSql.append("SELECT COUNT(DISTINCT s.merchant_id) FROM sum_daily_insight s ");
        cSql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) cSql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        cSql.append("WHERE s.tenant_id = ? AND s.business_date = ? ");
        cParams.add(tenantId); cParams.add(day);
        appendFilters(cSql, cParams, filter);
        Query cq = entityManager.createNativeQuery(cSql.toString());
        bind(cq, cParams);
        long totalMerchants = ((Number) cq.getSingleResult()).longValue();

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mid", r[0]);
            m.put("name", r[1]);
            m.put("count", ((Number) r[2]).longValue());
            m.put("volume", bd(r[3]));
            m.put("msf", bd(r[4]));
            m.put("opt_in_volume", bd(r[5]));
            out.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalMerchants", totalMerchants);
        resp.put("rows", out);
        return ResponseEntity.ok(currencyMeta.attach(resp, tenantId));
    }

    // ── Range resolution ──
    // For /monthly: honours datePreset (CURRENT_YEAR / PREVIOUS_YEAR / CUSTOM),
    //   else falls back to the whole of filter.year (or current year).
    // For /daily: monthOverride is set → clamp to that month within the resolved year.
    private LocalDate[] resolveRange(TrendFilter f, Integer monthOverride) {
        String preset = f.getDatePreset();
        int yr = f.getYear() != null ? f.getYear() : LocalDate.now().getYear();

        if (monthOverride == null && "CUSTOM".equalsIgnoreCase(preset)
                && f.getDateFrom() != null && f.getDateTo() != null) {
            LocalDate from = parse(f.getDateFrom());
            LocalDate to   = parse(f.getDateTo());
            if (from != null && to != null && !to.isBefore(from)) return new LocalDate[]{from, to};
        }
        if ("PREVIOUS_YEAR".equalsIgnoreCase(preset)) {
            yr = LocalDate.now().getYear() - 1;
        } else if ("CURRENT_YEAR".equalsIgnoreCase(preset)) {
            yr = LocalDate.now().getYear();
        }

        if (monthOverride != null) {
            int mo = Math.max(1, Math.min(12, monthOverride));
            LocalDate start = LocalDate.of(yr, mo, 1);
            LocalDate end = start.plusMonths(1).minusDays(1);
            // CUSTOM range: intersect the month window with [dateFrom, dateTo].
            // Without this, drilling a month inside a custom range (e.g. Mar 5–20)
            // listed the WHOLE calendar month, so the children summed to more
            // than their parent row.
            if ("CUSTOM".equalsIgnoreCase(preset)) {
                LocalDate from = parse(f.getDateFrom());
                LocalDate to = parse(f.getDateTo());
                if (from != null && from.isAfter(start)) start = from;
                if (to != null && to.isBefore(end)) end = to;
            }
            return new LocalDate[]{start, end};
        }
        return new LocalDate[]{ LocalDate.of(yr, 1, 1), LocalDate.of(yr, 12, 31) };
    }

    // ── Dimensional filter fragments (parameterised via positional ? binds) ──
    private void appendFilters(StringBuilder sql, List<Object> params, TrendFilter f) {
        if (listNonEmpty(f.getMid())) {
            sql.append("AND m.mid IN (").append(ph(f.getMid().size())).append(") ");
            params.addAll(f.getMid());
        }
        if (listNonEmpty(f.getRm())) {
            sql.append("AND m.sales_email IN (").append(ph(f.getRm().size())).append(") ");
            params.addAll(f.getRm());
        }
        if (listNonEmpty(f.getMcc())) {
            sql.append("AND st.mcc IN (").append(ph(f.getMcc().size())).append(") ");
            params.addAll(f.getMcc());
        }
        if ("OPT_IN".equalsIgnoreCase(f.getOptStatus())) {
            sql.append("AND s.is_opt_in = TRUE ");
        } else if ("OPT_OUT".equalsIgnoreCase(f.getOptStatus())) {
            sql.append("AND (s.is_opt_in = FALSE OR s.is_opt_in IS NULL) ");
        }
    }

    private static void bind(Query q, List<Object> params) {
        for (int i = 0; i < params.size(); i++) q.setParameter(i + 1, params.get(i));
    }

    private static String ph(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i > 0) b.append(','); b.append('?'); }
        return b.toString();
    }

    private static boolean listNonEmpty(List<?> l) {
        // Treat a lone "ALL" sentinel as no filter (matches InsightController convention).
        return l != null && !l.isEmpty() && !l.contains("ALL");
    }

    private static String monthName(int mo) {
        if (mo < 1 || mo > 12) return String.valueOf(mo);
        return Month.of(mo).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }

    private static String toDateStr(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date) return o.toString();
        if (o instanceof LocalDate) return o.toString();
        if (o instanceof java.sql.Timestamp) return ((java.sql.Timestamp) o).toLocalDateTime().toLocalDate().toString();
        String s = o.toString();
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
