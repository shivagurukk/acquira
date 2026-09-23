package com.acquira.common.repository;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the Revenue Mix dashboard (/business/revenue-mix) — the finance
 * team's Destination × split P&amp;L matrix (their Excel layout, verbatim):
 * one row per DESTINATION (Local / International) × split value with
 * #transactions, volume and the fee waterfall in the sheet's column order:
 *
 *   MSF → ICF → Net Revenue → Scheme Fee → Net Margin → Net Spread
 *
 *   net revenue = MSF − interchange                (after-interchange gross)
 *   net margin  = total_net_revenue                (batch 4-leg: − SF − PG too)
 *   net spread  = net margin + attributed ancillary
 *
 * The split dimension is card type by default (COMMERCIAL is commercial
 * credit and folds into the Credit row; PREPAID is always its own row) or
 * card scheme — both whitelisted in the controller.
 *
 * Ancillary attribution (store level everywhere — dim_merchant.mcc is never
 * used):
 *  - DCC acquirer share is inherently FOREIGN-card income, so its window
 *    total attaches to the INTERNATIONAL destination row's spread.
 *  - Rental and opt-in FX have no destination/card-type dimension at all,
 *    so they join the spread on the TOTAL row only — pro-rating them across
 *    the matrix would invent precision the data doesn't have.
 *  - The MCC/Industry filter reaches DCC + rental through the fact rows'
 *    own store_id → dim_store.mcc; FX (merchant-day grain) is dropped when
 *    that filter is active because it can't be attributed.
 *
 * Percentages are computed here (x / volume * 100, null when volume is 0),
 * never in SQL — same convention as the Industry Analytics / Card Type pages.
 */
@Repository
public class RevenueMixRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** Normalized split keys — blank/NULL folds into 'UNSPECIFIED'. */
    private static final String DEST_EXPR = "UPPER(COALESCE(NULLIF(TRIM(s.destination),''),'UNSPECIFIED'))";
    private static final String CT_RAW = "UPPER(COALESCE(NULLIF(TRIM(s.card_type),''),'UNSPECIFIED'))";
    /**
     * Card-type split for THIS page (finance sheet convention): COMMERCIAL is
     * commercial CREDIT and reports inside the Credit row; PREPAID stays its
     * own row, never folded into Debit/Other.
     */
    private static final String CT_EXPR =
            "(CASE WHEN " + CT_RAW + " = 'COMMERCIAL' THEN 'CREDIT' ELSE " + CT_RAW + " END)";
    private static final String SCHEME_EXPR = "UPPER(COALESCE(NULLIF(TRIM(s.card_scheme),''),'UNSPECIFIED'))";

    private static String splitExpr(String dimension) {
        return "scheme".equals(dimension) ? SCHEME_EXPR : CT_EXPR;
    }

    private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    private static long lng(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(o.toString());
    }

    /** Fail closed: a null tenant must never silently widen a query to every tenant. */
    private static void requireTenant(Long tenantId) {
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved — refusing unscoped query");
    }

    private static boolean hasMerchantFilter(VolumeRevenueFilterDTO f) {
        return listNonEmpty(f.getPartnerList()) || listNonEmpty(f.getRmList())
                || listNonEmpty(f.getTeamLeaderList()) || listNonEmpty(f.getMidList())
                || (f.getMerchantName() != null && !f.getMerchantName().isBlank());
    }

    // ── Full drawer-filter surface over sum_daily_full (industryList never
    //    reaches here — the controller folds it into mccList). destination
    //    and cardType filters are honored: they narrow the matrix. ──
    private boolean appendCommonFilters(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        boolean needStore = listNonEmpty(filter.getSidList());

        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (listNonEmpty(filter.getMccList()))        sql.append("AND s.mcc IN (:mccs) ");
        if (needStore)                                sql.append("AND st.sid IN (:sids) ");
        if (listNonEmpty(filter.getChannelList()))    sql.append("AND s.channel IN (:channels) ");
        if (listNonEmpty(filter.getSchemeList()))     sql.append("AND s.card_scheme IN (:schemes) ");
        if (listNonEmpty(filter.getCardTypeList()))
            sql.append("AND ").append(CT_EXPR).append(" IN (:cardTypes) ");
        if (listNonEmpty(filter.getDestinationList()))
            sql.append("AND ").append(DEST_EXPR).append(" IN (:destinations) ");

        return needStore;
    }

    private void bindCommonParams(Query query, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMccList()))        query.setParameter("mccs", filter.getMccList());
        if (listNonEmpty(filter.getSidList()))        query.setParameter("sids", filter.getSidList());
        if (listNonEmpty(filter.getChannelList()))    query.setParameter("channels", filter.getChannelList());
        if (listNonEmpty(filter.getSchemeList()))     query.setParameter("schemes", filter.getSchemeList());
        if (listNonEmpty(filter.getCardTypeList()))
            // Compared against the folded CT_EXPR, so a drawer pick of
            // "Commercial" must translate to its folded bucket (CREDIT).
            query.setParameter("cardTypes",
                    filter.getCardTypeList().stream()
                            .map(c -> c == null ? "" : c.trim().toUpperCase())
                            .map(c -> "COMMERCIAL".equals(c) ? "CREDIT" : c)
                            .toList());
        if (listNonEmpty(filter.getDestinationList()))
            query.setParameter("destinations",
                    filter.getDestinationList().stream().map(d -> d == null ? "" : d.trim().toUpperCase()).toList());
    }

    private void appendJoins(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        // dim_merchant only when a merchant-level filter needs it.
        if (hasMerchantFilter(filter))
            sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (listNonEmpty(filter.getSidList()))
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
    }

    // ─────────────────────────────────────────────────────────────────
    // 0) Data bounds — MIN/MAX business_date in THIS page's backing table.
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getBounds(Long tenantId) {
        requireTenant(tenantId);
        Query query = entityManager.createNativeQuery(
                "SELECT MIN(s.business_date), MAX(s.business_date) FROM sum_daily_full s WHERE s.tenant_id = :tenantId");
        query.setParameter("tenantId", tenantId);

        Object[] r = (Object[]) query.getSingleResult();
        Map<String, Object> out = new HashMap<>();
        out.put("earliest", r[0] == null ? null : r[0].toString());
        out.put("latest", r[1] == null ? null : r[1].toString());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 1) The matrix — destination × split cells, destination roll-ups with
    //    attributed spread, grand total, prior-window growth
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getMatrix(VolumeRevenueFilterDTO filter, String dimension,
                                         Long tenantId, boolean fxEnabled) {
        requireTenant(tenantId);
        String split = splitExpr(dimension);
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfYear(1);
        long days = Math.max(ChronoUnit.DAYS.between(start, end), 1);
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(DEST_EXPR).append(" as dest, ");
        sql.append(split).append(" as split_value, ");
        sql.append("SUM(s.total_txns) as txn, ");
        sql.append("SUM(s.total_volume) as vol, ");
        sql.append("SUM(s.total_msf) as msf, ");
        sql.append("SUM(s.total_interchange) as icf, ");
        sql.append("SUM(s.total_scheme_fee) as sf, ");
        sql.append("SUM(s.total_ecom_fee) as pg, ");
        // Batch-computed 4-leg figure (MSF − ICF − SF − PG); never recomputed here.
        sql.append("SUM(s.total_net_revenue) as nm ");
        sql.append("FROM sum_daily_full s ");
        appendJoins(sql, filter);
        sql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        sql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY ").append(DEST_EXPR).append(", ").append(split).append(" ");
        sql.append("ORDER BY dest ASC, vol DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        // Prior window, aggregate only — for the tiles' "vs prior".
        StringBuilder prevSql = new StringBuilder();
        prevSql.append("SELECT SUM(s.total_volume), SUM(s.total_txns), SUM(s.total_msf), SUM(s.total_net_revenue) ");
        prevSql.append("FROM sum_daily_full s ");
        appendJoins(prevSql, filter);
        prevSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        prevSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(prevSql, filter);
        Query prevQuery = entityManager.createNativeQuery(prevSql.toString());
        prevQuery.setParameter("winStart", prevStart);
        prevQuery.setParameter("winEnd", prevEnd);
        prevQuery.setParameter("tenantId", tenantId);
        bindCommonParams(prevQuery, filter);
        Object[] prev = (Object[]) prevQuery.getSingleResult();
        BigDecimal pVol = bd(prev[0]), pMsf = bd(prev[2]), pNm = bd(prev[3]);
        long pTxn = lng(prev[1]);

        // Cells + destination roll-ups, preserving the sheet's shape.
        List<Map<String, Object>> cells = new ArrayList<>();
        Map<String, BigDecimal[]> destAgg = new LinkedHashMap<>(); // dest -> [vol,msf,icf,sf,pg,nm]
        Map<String, Long> destTxn = new LinkedHashMap<>();
        BigDecimal tVol = BigDecimal.ZERO, tMsf = BigDecimal.ZERO, tIcf = BigDecimal.ZERO,
                tSf = BigDecimal.ZERO, tPg = BigDecimal.ZERO, tNm = BigDecimal.ZERO;
        long tTxn = 0;
        for (Object[] r : rows) {
            String dest = String.valueOf(r[0]);
            String sv = String.valueOf(r[1]);
            long txn = lng(r[2]);
            BigDecimal vol = bd(r[3]), msf = bd(r[4]), icf = bd(r[5]), sf = bd(r[6]), pg = bd(r[7]), nm = bd(r[8]);

            cells.add(row(dest, sv, txn, vol, msf, icf, sf, pg, nm, null, null));

            BigDecimal[] agg = destAgg.computeIfAbsent(dest,
                    k -> new BigDecimal[]{ BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            agg[0] = agg[0].add(vol); agg[1] = agg[1].add(msf); agg[2] = agg[2].add(icf);
            agg[3] = agg[3].add(sf); agg[4] = agg[4].add(pg); agg[5] = agg[5].add(nm);
            destTxn.merge(dest, txn, Long::sum);

            tVol = tVol.add(vol); tMsf = tMsf.add(msf); tIcf = tIcf.add(icf);
            tSf = tSf.add(sf); tPg = tPg.add(pg); tNm = tNm.add(nm);
            tTxn += txn;
        }

        // Ancillary legs, store level (see class javadoc for attribution).
        BigDecimal dcc = fetchFactSum(filter, tenantId, start, end, "fact_dcc_revenue", "acquirer_share");
        BigDecimal rental = fetchFactSum(filter, tenantId, start, end, "fact_rental", "rental_amount");
        boolean fxAttributable = fxEnabled && !listNonEmpty(filter.getMccList());
        BigDecimal fx = fxAttributable ? fetchFxTotal(filter, tenantId, start, end) : BigDecimal.ZERO;

        List<Map<String, Object>> destinations = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : destAgg.entrySet()) {
            BigDecimal[] a = e.getValue();
            // DCC is foreign-card income — the International row carries it.
            boolean intl = "INTERNATIONAL".equals(e.getKey());
            BigDecimal anc = intl ? dcc : BigDecimal.ZERO;
            destinations.add(row(e.getKey(), null, destTxn.get(e.getKey()),
                    a[0], a[1], a[2], a[3], a[4], a[5], anc, a[5].add(anc)));
        }
        destinations.sort((a, b) -> bd(b.get("volume")).compareTo(bd(a.get("volume"))));

        BigDecimal tAnc = dcc.add(rental).add(fx);
        BigDecimal tSpread = tNm.add(tAnc);
        Map<String, Object> totals = row("ALL", null, tTxn, tVol, tMsf, tIcf, tSf, tPg, tNm, tAnc, tSpread);
        totals.put("dcc", dcc);
        totals.put("rental", rental);
        totals.put("fx", fx);
        boolean priorHasData = pVol.signum() > 0 || pTxn > 0;
        totals.put("volumeGrowthPct", priorHasData ? growth(tVol, pVol) : null);
        totals.put("msfGrowthPct", priorHasData ? growth(tMsf, pMsf) : null);
        totals.put("netMarginGrowthPct", priorHasData ? growth(tNm, pNm) : null);
        BigDecimal pAnc = fetchFactSum(filter, tenantId, prevStart, prevEnd, "fact_dcc_revenue", "acquirer_share")
                .add(fetchFactSum(filter, tenantId, prevStart, prevEnd, "fact_rental", "rental_amount"))
                .add(fxAttributable ? fetchFxTotal(filter, tenantId, prevStart, prevEnd) : BigDecimal.ZERO);
        totals.put("netSpreadGrowthPct", priorHasData ? growth(tSpread, pNm.add(pAnc)) : null);

        Map<String, Object> payload = new HashMap<>();
        payload.put("dimension", "scheme".equals(dimension) ? "scheme" : "cardType");
        payload.put("cells", cells);
        payload.put("destinations", destinations);
        payload.put("totals", totals);
        payload.put("start", start.toString());
        payload.put("end", end.toString());
        payload.put("priorStart", prevStart.toString());
        payload.put("priorEnd", prevEnd.toString());
        payload.put("priorWindowHasData", priorHasData);
        payload.put("basis", "SETTLEMENT");
        payload.put("fxEnabled", fxEnabled);
        payload.put("fxAttributable", fxAttributable);
        // With a tech-dimension filter active the (dimensionless) ancillary
        // can't follow the narrowed volume — flag it, don't mis-scale it.
        payload.put("ancillaryApproximate",
                listNonEmpty(filter.getChannelList()) || listNonEmpty(filter.getSchemeList())
                        || listNonEmpty(filter.getCardTypeList()) || listNonEmpty(filter.getDestinationList())
                        || listNonEmpty(filter.getSidList()));
        return payload;
    }

    /** One P&L row in the sheet's column order; ancillary/spread only where attributable. */
    private Map<String, Object> row(String dest, String splitValue, long txn, BigDecimal vol,
                                    BigDecimal msf, BigDecimal icf, BigDecimal sf, BigDecimal pg,
                                    BigDecimal nm, BigDecimal ancillary, BigDecimal spread) {
        BigDecimal nr = msf.subtract(icf); // after-interchange gross (sheet's "Net Revenue")
        Map<String, Object> m = new HashMap<>();
        m.put("destination", dest);
        m.put("splitValue", splitValue);
        m.put("txns", txn);
        m.put("volume", vol);
        m.put("msf", msf);
        m.put("icf", icf);
        m.put("netRevenue", nr);
        m.put("schemeFee", sf);
        m.put("pgFee", pg);
        m.put("netMargin", nm);
        m.put("ancillary", ancillary);
        m.put("netSpread", spread);
        boolean hasVol = vol.signum() > 0;
        m.put("msfPct", hasVol ? pct(msf, vol) : null);
        m.put("icfPct", hasVol ? pct(icf, vol) : null);
        m.put("netRevenuePct", hasVol ? pct(nr, vol) : null);
        m.put("schemeFeePct", hasVol ? pct(sf, vol) : null);
        m.put("netMarginPct", hasVol ? pct(nm, vol) : null);
        m.put("netSpreadPct", spread != null && hasVol ? pct(spread, vol) : null);
        return m;
    }

    private static double pct(BigDecimal x, BigDecimal vol) {
        return x.doubleValue() / vol.doubleValue() * 100.0;
    }

    private static double growth(BigDecimal curr, BigDecimal prev) {
        if (prev.signum() == 0) return curr.signum() > 0 ? 100.0 : 0.0;
        return (curr.doubleValue() - prev.doubleValue()) / Math.abs(prev.doubleValue()) * 100.0;
    }

    /**
     * Window total of one ancillary fact column, with merchant-scope filters
     * and the MCC/Industry filter applied through the fact row's own
     * store_id → dim_store.mcc (store level, never dim_merchant.mcc).
     */
    private BigDecimal fetchFactSum(VolumeRevenueFilterDTO filter, Long tenantId,
                                    LocalDate start, LocalDate end, String table, String column) {
        boolean merchFilter = hasMerchantFilter(filter);
        boolean mccFilter = listNonEmpty(filter.getMccList());
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COALESCE(SUM(f.").append(column).append("), 0) FROM ").append(table).append(" f ");
        if (mccFilter)
            sql.append("LEFT JOIN dim_store st ON st.store_id = f.store_id AND st.tenant_id = :tenantId ");
        if (merchFilter)
            sql.append("LEFT JOIN dim_merchant m ON m.merchant_id = f.merchant_id AND m.tenant_id = :tenantId ");
        sql.append("WHERE f.tenant_id = :tenantId AND f.payment_date BETWEEN :winStart AND :winEnd ");
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (mccFilter)                                sql.append("AND st.mcc IN (:mccs) ");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (mccFilter)                                query.setParameter("mccs", filter.getMccList());
        return bd(query.getSingleResult());
    }

    /** Window FX total from sum_daily_merchant (merchant-scope filters only, no MCC). */
    private BigDecimal fetchFxTotal(VolumeRevenueFilterDTO filter, Long tenantId,
                                    LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COALESCE(SUM(COALESCE(s.fx_revenue,0)), 0) FROM sum_daily_merchant s ");
        if (hasMerchantFilter(filter))
            sql.append("JOIN dim_merchant m ON m.merchant_id = s.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd AND s.tenant_id = :tenantId ");
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        return bd(query.getSingleResult());
    }

    // ─────────────────────────────────────────────────────────────────
    // 2) Monthly trend — one row per month × destination (frontend pivots)
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTrend(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfYear(1);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TO_CHAR(s.business_date, 'YYYY-MM') as month_label, ");
        sql.append(DEST_EXPR).append(" as dest, ");
        sql.append("SUM(s.total_volume) as vol, ");
        sql.append("SUM(s.total_txns) as txn, ");
        sql.append("SUM(s.total_msf) as msf, ");
        sql.append("SUM(s.total_interchange) as icf, ");
        sql.append("SUM(s.total_net_revenue) as nm ");
        sql.append("FROM sum_daily_full s ");
        appendJoins(sql, filter);
        sql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        sql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY TO_CHAR(s.business_date, 'YYYY-MM'), ").append(DEST_EXPR).append(" ");
        sql.append("ORDER BY month_label ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("month", r[0]);
            m.put("destination", r[1]);
            m.put("volume", bd(r[2]));
            m.put("txns", lng(r[3]));
            m.put("msf", bd(r[4]));
            m.put("icf", bd(r[5]));
            m.put("netMargin", bd(r[6]));
            out.add(m);
        }
        return out;
    }
}
