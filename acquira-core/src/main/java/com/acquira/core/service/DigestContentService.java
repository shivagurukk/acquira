package com.acquira.core.service;

import com.acquira.common.service.NetSpreadSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the numbers for one tenant's Daily Dashboard Digest email.
 *
 * Reads the SAME summary layer the executive screens read (sum_daily_merchant
 * via NetSpreadSql, sum_daily_insight for the mix) so the email can never
 * disagree with the dashboards. Plain JdbcTemplate with an explicit tenantId
 * parameter — this runs on a scheduler thread with no request TenantContext,
 * and every query here is tenant-scoped by hand for exactly that reason.
 */
@Service
public class DigestContentService {

    private final JdbcTemplate jdbc;

    public DigestContentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One merchant line in a top/mover/silent list. */
    public record MerchantLine(String name, String mid, BigDecimal volume,
                               BigDecimal baseline, Double deltaPct) {}

    /** One day of the 14-day trend strip. */
    public record DayPoint(LocalDate date, BigDecimal volume, BigDecimal spread) {}

    /** One loss-making merchant: negative MTD Net Spread (same rule as the Loss-Making screen). */
    public record LossLine(String name, String mid, BigDecimal volume, BigDecimal spread) {}

    /** Everything the email renderer needs, already aggregated. */
    public static class DigestData {
        public LocalDate businessDate;
        public String institution;
        public String currency;

        public Map<String, BigDecimal> totals = new LinkedHashMap<>();     // cnt/vol/msf/icf/sf/pg/nm/dcc/rental/fx/spread
        public Map<String, BigDecimal> prevWeek = new LinkedHashMap<>();   // same keys, same weekday last week
        public Map<String, BigDecimal> mtdAvg = new LinkedHashMap<>();     // same keys, MTD daily average
        public Map<String, BigDecimal> mtdTotals = new LinkedHashMap<>();  // same keys, MTD cumulative
        public int mtdDays;

        /** Last 14 calendar days ending on the business date (loaded days only). */
        public List<DayPoint> trend14 = new ArrayList<>();

        /** Merchants with negative MTD Net Spread (exclusion list applied); count + worst 3. */
        public int lossCount;
        public List<LossLine> lossTop = new ArrayList<>();

        /** Day's DCC opt-in picture (from the transaction feed's DCC flags). */
        public BigDecimal dccEligibleVol = BigDecimal.ZERO;
        public BigDecimal dccOptinVol = BigDecimal.ZERO;
        public long dccEligibleCnt;
        public long dccOptinCnt;

        /** Tenant opted into ECOM FX income (netspread.fx_enabled) — see NetSpreadSql. */
        public boolean fxEnabled;

        /**
         * Which feeds the figures cover (Phase 1, I-1). The renderer prints a
         * completeness banner from it and shows "not loaded" instead of 0.000
         * for an absent feed, so a zero is never mistaken for a missing file.
         */
        public DigestFeedCoverage coverage = DigestFeedCoverage.none();

        /** 0 = original digest; n = the n-th restatement of an already-sent day (I-2). */
        public int restateNo;
        /** When the original digest went out; shown on a restatement banner. */
        public java.time.LocalDateTime originalSentAt;

        public List<MerchantLine> topMerchants = new ArrayList<>();
        public List<MerchantLine> gainers = new ArrayList<>();
        public List<MerchantLine> decliners = new ArrayList<>();
        public List<MerchantLine> silent = new ArrayList<>();

        public List<Map<String, Object>> schemeMix = new ArrayList<>();    // label/vol/cnt
        public List<Map<String, Object>> cardTypeMix = new ArrayList<>();
        public BigDecimal domesticVol = BigDecimal.ZERO;
        public BigDecimal internationalVol = BigDecimal.ZERO;
    }

    /**
     * FX income is an opt-in leg of the spread (tenant_setting
     * {@code netspread.fx_enabled}), so the totals query is built per tenant:
     * flag on → spread includes FX, exactly as the executive endpoints read it.
     * Building this as a constant with {@link NetSpreadSql#sumSpread} is what
     * made the emailed Net Spread disagree with the dashboards for FX tenants.
     */
    private static String totalsSelect(boolean fxEnabled) {
        return "SELECT COALESCE(SUM(s.total_txns),0) cnt, "
            + "COALESCE(SUM(s.total_base_volume),0) vol, "
            + "COALESCE(SUM(s.total_msf),0) msf, "
            + "COALESCE(SUM(s.total_interchange),0) icf, "
            + "COALESCE(SUM(s.total_scheme_fee),0) sf, "
            + "COALESCE(SUM(s.total_ecom_fee),0) pg, "
            + NetSpreadSql.sumMargin("s") + " nm, "
            + "COALESCE(SUM(s.dcc_acquirer),0) dcc, "
            + "COALESCE(SUM(s.rental_amount),0) rental, "
            + "COALESCE(SUM(" + NetSpreadSql.fx("s") + "),0) fx, "
            + (fxEnabled ? NetSpreadSql.sumSpreadWithFx("s") : NetSpreadSql.sumSpread("s")) + " spread "
            + "FROM sum_daily_merchant s WHERE s.tenant_id = ? AND s.business_date ";
    }

    private static final String[] TOTAL_KEYS =
            {"cnt", "vol", "msf", "icf", "sf", "pg", "nm", "dcc", "rental", "fx", "spread"};

    public DigestData build(Long tenantId, LocalDate date) {
        return build(tenantId, date, DigestFeedCoverage.none());
    }

    /**
     * @param coverage the feed picture the gate saw for this tenant-day
     *                 (DigestScheduler.coverageFor); carried through to the
     *                 email so it can say what its figures include.
     */
    public DigestData build(Long tenantId, LocalDate date, DigestFeedCoverage coverage) {
        DigestData d = new DigestData();
        d.businessDate = date;
        d.coverage = coverage == null ? DigestFeedCoverage.none() : coverage;

        Map<String, Object> tenant = jdbc.queryForMap(
                // bank_name is the display name; institution_id is a logical code
                // ("BANK001", sometimes a bare number) and must never headline the email.
                "SELECT COALESCE(NULLIF(TRIM(bank_name), ''), NULLIF(TRIM(bank_short_code), ''), "
                + "institution_id, 'Tenant ' || tenant_id) AS institution, "
                + "COALESCE(base_currency, 'AED') AS ccy FROM tenant WHERE tenant_id = ?", tenantId);
        d.institution = (String) tenant.get("institution");
        d.currency = (String) tenant.get("ccy");
        d.fxEnabled = NetSpreadSql.fxEnabled(jdbc, tenantId);

        d.totals = totalsFor(tenantId, d.fxEnabled, "= ?", date);
        d.prevWeek = totalsFor(tenantId, d.fxEnabled, "= ?", date.minusWeeks(1));

        LocalDate monthStart = date.withDayOfMonth(1);
        Map<String, BigDecimal> mtd = totalsFor(tenantId, d.fxEnabled, "BETWEEN ? AND ?", monthStart, date);
        Integer days = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT business_date) FROM sum_daily_merchant "
                + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? AND COALESCE(total_txns,0) > 0",
                Integer.class, tenantId, monthStart, date);
        d.mtdDays = days == null ? 0 : days;
        d.mtdTotals = mtd;
        if (d.mtdDays > 0) {
            BigDecimal n = BigDecimal.valueOf(d.mtdDays);
            mtd.forEach((k, v) -> d.mtdAvg.put(k, v.divide(n, 4, java.math.RoundingMode.HALF_UP)));
        }

        topMerchants(tenantId, date, d);
        movers(tenantId, date, d);
        mix(tenantId, date, d);
        trend(tenantId, date, d);
        dccOptIn(tenantId, date, d);
        lossMaking(tenantId, monthStart, date, d);
        return d;
    }

    /** Loaded days of the last 14, for the trend strip (bar per day). */
    private void trend(Long tenantId, LocalDate date, DigestData d) {
        d.trend14 = jdbc.query(
                "SELECT s.business_date, COALESCE(SUM(s.total_base_volume),0) vol, "
                + (d.fxEnabled ? NetSpreadSql.sumSpreadWithFx("s") : NetSpreadSql.sumSpread("s")) + " spread "
                + "FROM sum_daily_merchant s WHERE s.tenant_id = ? AND s.business_date BETWEEN ? AND ? "
                + "GROUP BY s.business_date ORDER BY s.business_date",
                (rs, i) -> new DayPoint(rs.getObject("business_date", LocalDate.class),
                        rs.getBigDecimal("vol"), rs.getBigDecimal("spread")),
                tenantId, date.minusDays(13), date);
    }

    /** Day's DCC opt-in counters — summarised daily from the transaction feed's DCC flags. */
    private void dccOptIn(Long tenantId, LocalDate date, DigestData d) {
        jdbc.query(
                "SELECT COALESCE(SUM(dcc_eligible_volume),0) ev, COALESCE(SUM(dcc_optin_volume),0) ov, "
                + "COALESCE(SUM(dcc_eligible_count),0) ec, COALESCE(SUM(dcc_optin_count),0) oc "
                + "FROM sum_daily_merchant WHERE tenant_id = ? AND business_date = ?",
                rs -> {
                    d.dccEligibleVol = rs.getBigDecimal("ev");
                    d.dccOptinVol = rs.getBigDecimal("ov");
                    d.dccEligibleCnt = rs.getLong("ec");
                    d.dccOptinCnt = rs.getLong("oc");
                }, tenantId, date);
    }

    /**
     * Merchants whose MTD Net Spread is negative — the Loss-Making screen's rule
     * (spread, not margin), honouring the same 'lossmaking.excluded_mids'
     * tenant_setting so the email never re-surfaces a bank's own by-design-loss
     * MIDs that the screen hides.
     */
    private void lossMaking(Long tenantId, LocalDate monthStart, LocalDate date, DigestData d) {
        String spread = d.fxEnabled ? NetSpreadSql.sumSpreadWithFx("s") : NetSpreadSql.sumSpread("s");
        List<LossLine> all = jdbc.query(
                "SELECT m.name, m.mid, SUM(COALESCE(s.total_base_volume,0)) vol, " + spread + " spread "
                + "FROM sum_daily_merchant s JOIN dim_merchant m ON m.merchant_id = s.merchant_id "
                + "WHERE s.tenant_id = ? AND s.business_date BETWEEN ? AND ? "
                + "GROUP BY m.merchant_id, m.name, m.mid "
                + "HAVING " + spread + " < 0 ORDER BY spread ASC",
                (rs, i) -> new LossLine(rs.getString("name"), rs.getString("mid"),
                        rs.getBigDecimal("vol"), rs.getBigDecimal("spread")),
                tenantId, monthStart, date);
        java.util.Set<String> excluded = new java.util.HashSet<>();
        jdbc.queryForList(
                "SELECT setting_value FROM tenant_setting WHERE tenant_id = ? AND setting_key = 'lossmaking.excluded_mids'",
                String.class, tenantId).stream()
            .filter(v -> v != null)
            .flatMap(v -> java.util.Arrays.stream(v.split(",")))
            .map(String::trim).filter(s -> !s.isEmpty())
            .forEach(excluded::add);
        all = all.stream().filter(l -> !excluded.contains(l.mid())).toList();
        d.lossCount = all.size();
        d.lossTop = all.stream().limit(3).toList();
    }

    private Map<String, BigDecimal> totalsFor(Long tenantId, boolean fxEnabled,
                                              String datePredicate, Object... dates) {
        Object[] params = new Object[1 + dates.length];
        params[0] = tenantId;
        System.arraycopy(dates, 0, params, 1, dates.length);
        return jdbc.query(totalsSelect(fxEnabled) + datePredicate, rs -> {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            if (rs.next()) {
                for (String k : TOTAL_KEYS) {
                    BigDecimal v = rs.getBigDecimal(k);
                    m.put(k, v == null ? BigDecimal.ZERO : v);
                }
            } else {
                for (String k : TOTAL_KEYS) m.put(k, BigDecimal.ZERO);
            }
            return m;
        }, params);
    }

    private void topMerchants(Long tenantId, LocalDate date, DigestData d) {
        d.topMerchants = jdbc.query(
                "SELECT m.name, m.mid, SUM(COALESCE(s.total_base_volume,0)) vol "
                + "FROM sum_daily_merchant s JOIN dim_merchant m ON m.merchant_id = s.merchant_id "
                + "WHERE s.tenant_id = ? AND s.business_date = ? "
                + "GROUP BY m.merchant_id, m.name, m.mid "
                + "HAVING SUM(COALESCE(s.total_base_volume,0)) > 0 "
                + "ORDER BY vol DESC LIMIT 5",
                (rs, i) -> new MerchantLine(rs.getString("name"), rs.getString("mid"),
                        rs.getBigDecimal("vol"), null, null),
                tenantId, date);
    }

    /**
     * Movers vs the merchant's average over the previous 4 SAME weekdays —
     * same-weekday baseline for the same reason IngestAlertScheduler uses one:
     * retail volume has a strong weekly shape.
     */
    private void movers(Long tenantId, LocalDate date, DigestData d) {
        LocalDate w1 = date.minusWeeks(1), w2 = date.minusWeeks(2),
                  w3 = date.minusWeeks(3), w4 = date.minusWeeks(4);

        String cte =
                "WITH today AS (SELECT s.merchant_id, SUM(COALESCE(s.total_base_volume,0)) vol "
                + "  FROM sum_daily_merchant s WHERE s.tenant_id = ? AND s.business_date = ? "
                + "  GROUP BY s.merchant_id), "
                + "base AS (SELECT x.merchant_id, AVG(x.day_vol) avol FROM ("
                + "  SELECT s.merchant_id, s.business_date, SUM(COALESCE(s.total_base_volume,0)) day_vol "
                + "  FROM sum_daily_merchant s WHERE s.tenant_id = ? AND s.business_date IN (?,?,?,?) "
                + "  GROUP BY s.merchant_id, s.business_date) x GROUP BY x.merchant_id) ";

        // Gainers / decliners: baseline must be material or every tiny merchant
        // doubling from nothing tops the list.
        String moverSql = cte
                + "SELECT m.name, m.mid, COALESCE(t.vol,0) vol, b.avol, "
                + "  (COALESCE(t.vol,0) - b.avol) / b.avol * 100 AS pct "
                + "FROM base b LEFT JOIN today t ON t.merchant_id = b.merchant_id "
                + "JOIN dim_merchant m ON m.merchant_id = b.merchant_id "
                + "WHERE b.avol > 0 AND COALESCE(t.vol,0) > 0 "
                + "ORDER BY pct %s LIMIT 5";

        Object[] p = {tenantId, date, tenantId, w1, w2, w3, w4};
        d.gainers = jdbc.query(String.format(moverSql, "DESC"), this::moverRow, p);
        d.decliners = jdbc.query(String.format(moverSql, "ASC"), this::moverRow, p);
        d.gainers.removeIf(l -> l.deltaPct() == null || l.deltaPct() < 20);
        d.decliners.removeIf(l -> l.deltaPct() == null || l.deltaPct() > -20);

        d.silent = jdbc.query(cte
                + "SELECT m.name, m.mid, b.avol FROM base b "
                + "LEFT JOIN today t ON t.merchant_id = b.merchant_id "
                + "JOIN dim_merchant m ON m.merchant_id = b.merchant_id "
                + "WHERE b.avol > 0 AND COALESCE(t.vol,0) = 0 "
                + "ORDER BY b.avol DESC LIMIT 5",
                (rs, i) -> new MerchantLine(rs.getString("name"), rs.getString("mid"),
                        BigDecimal.ZERO, rs.getBigDecimal("avol"), -100.0),
                p);
    }

    private MerchantLine moverRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        BigDecimal pct = rs.getBigDecimal("pct");
        return new MerchantLine(rs.getString("name"), rs.getString("mid"),
                rs.getBigDecimal("vol"), rs.getBigDecimal("avol"),
                pct == null ? null : pct.doubleValue());
    }

    private void mix(Long tenantId, LocalDate date, DigestData d) {
        d.schemeMix = mixQuery(tenantId, date, "COALESCE(NULLIF(TRIM(card_scheme),''),'Unknown')");
        d.cardTypeMix = mixQuery(tenantId, date, "COALESCE(NULLIF(TRIM(card_type),''),'Unknown')");
        jdbc.query(
                "SELECT CASE WHEN UPPER(COALESCE(destination,'')) = 'DOMESTIC' THEN 'DOM' ELSE 'INTL' END k, "
                + "COALESCE(SUM(total_volume),0) vol FROM sum_daily_insight "
                + "WHERE tenant_id = ? AND business_date = ? GROUP BY 1",
                rs -> {
                    if ("DOM".equals(rs.getString("k"))) d.domesticVol = rs.getBigDecimal("vol");
                    else d.internationalVol = d.internationalVol.add(rs.getBigDecimal("vol"));
                }, tenantId, date);
    }

    private List<Map<String, Object>> mixQuery(Long tenantId, LocalDate date, String labelExpr) {
        return jdbc.query(
                "SELECT " + labelExpr + " AS label, COALESCE(SUM(total_volume),0) vol, "
                + "COALESCE(SUM(total_txns),0) cnt FROM sum_daily_insight "
                + "WHERE tenant_id = ? AND business_date = ? GROUP BY 1 ORDER BY vol DESC LIMIT 8",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", rs.getString("label"));
                    m.put("vol", rs.getBigDecimal("vol"));
                    m.put("cnt", rs.getLong("cnt"));
                    return m;
                }, tenantId, date);
    }
}
