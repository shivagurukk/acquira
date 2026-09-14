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
 * Backs the Industry Analytics screen (/business/industry-analytics) — the
 * full acquiring P&amp;L split by INDUSTRY (MCC sector), fee waterfall in the
 * house column order:
 *
 *   MSF → ICF → Net Revenue → Scheme Fee → Net Margin → Net Spread
 *
 *   net revenue = MSF − interchange                 (after-interchange gross)
 *   net margin  = total_net_revenue                 (batch 4-leg: − SF − PG too)
 *   net spread  = net margin + DCC acquirer + rental (+ opt-in FX on totals)
 *
 * "Industry" is ref_mcc_category.category over the STORE's MCC — everywhere.
 * Fee lines group on sum_daily_full.mcc (which IS dim_store.mcc), and the
 * ancillary legs are attributed by joining fact_dcc_revenue / fact_rental
 * through their own store_id to dim_store.mcc. dim_merchant.mcc is never
 * used for attribution: store level is the single vocabulary, so a merchant
 * whose stores span sectors has each store's revenue in that store's sector.
 * An ancillary row without a resolvable store folds into 'MIS', same as an
 * unmapped MCC.
 *
 * FX income has no store dimension (it is maintained at merchant-day grain
 * by AncillarySql), so it joins the spread on the TOTALS row only — and is
 * dropped entirely when an MCC/Industry filter is active, because it cannot
 * be attributed to the filtered sectors.
 *
 * Percentages are computed here (x / volume * 100, null when volume is 0),
 * never in SQL — same convention as CardTypeDashboardRepository.
 */
@Repository
public class IndustryAnalyticsRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** Industry key over the store MCC carried on sum_daily_full. */
    private static final String IND_EXPR = "COALESCE(rc.category, 'MIS')";

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

    // ── Filter surface over sum_daily_full (industryList never reaches here —
    //    the controller has already folded it into mccList). ──
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
            sql.append("AND UPPER(COALESCE(NULLIF(TRIM(s.card_type),''),'UNSPECIFIED')) IN (:cardTypes) ");
        if (listNonEmpty(filter.getDestinationList()))
            sql.append("AND UPPER(COALESCE(s.destination,'')) IN (:destinations) ");

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
            query.setParameter("cardTypes",
                    filter.getCardTypeList().stream().map(c -> c == null ? "" : c.trim().toUpperCase()).toList());
        if (listNonEmpty(filter.getDestinationList()))
            query.setParameter("destinations",
                    filter.getDestinationList().stream().map(d -> d == null ? "" : d.toUpperCase()).toList());
    }

    private void appendJoins(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        // dim_merchant only when a merchant-level filter needs it — the group
        // key is the store MCC, so an unfiltered scan never pays the join.
        if (hasMerchantFilter(filter))
            sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (listNonEmpty(filter.getSidList()))
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        sql.append("LEFT JOIN ref_mcc_category rc ON rc.mcc = s.mcc ");
    }

    /** A tech-dimension filter makes the (dimensionless) ancillary legs approximate. */
    private static boolean techFiltered(VolumeRevenueFilterDTO f) {
        return listNonEmpty(f.getChannelList()) || listNonEmpty(f.getSchemeList())
                || listNonEmpty(f.getCardTypeList()) || listNonEmpty(f.getDestinationList())
                || listNonEmpty(f.getSidList());
    }

    // ─────────────────────────────────────────────────────────────────
    // 0) Data bounds — MIN/MAX business_date in THIS page's backing table,
    //    so the date presets anchor on dates the screen can actually render.
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
    // 1) Industry rows + totals + prior-window growth — one payload
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getIndustryRows(VolumeRevenueFilterDTO filter, Long tenantId, boolean fxEnabled) {
        requireTenant(tenantId);
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfYear(1);
        long days = Math.max(ChronoUnit.DAYS.between(start, end), 1);
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days);

        // A) Fee stack by store-MCC sector over sum_daily_full.
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(IND_EXPR).append(" as industry, ");
        sql.append("SUM(s.total_volume) as vol, ");
        sql.append("SUM(s.total_txns) as txn, ");
        sql.append("COUNT(DISTINCT s.merchant_id) as merch, ");
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
        sql.append("GROUP BY ").append(IND_EXPR).append(" ");
        sql.append("ORDER BY vol DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        // A') Prior window, aggregate only (vol/txn/msf/margin) — the tiles'
        //     "vs prior" needs one row, not the whole split.
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

        // B) Ancillary (DCC + rental) by STORE-MCC sector from the facts.
        Map<String, BigDecimal> ancByIndustry = fetchAncillaryByStoreSector(filter, tenantId, start, end);
        BigDecimal pAnc = fetchAncillaryByStoreSector(filter, tenantId, prevStart, prevEnd)
                .values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // C) FX — totals only, and only when no MCC/Industry filter is active
        //    (it has no store dimension to attribute through).
        boolean fxAttributable = fxEnabled && !listNonEmpty(filter.getMccList());
        BigDecimal tFx = fxAttributable ? fetchFxTotal(filter, tenantId, start, end) : BigDecimal.ZERO;
        BigDecimal pFx = fxAttributable ? fetchFxTotal(filter, tenantId, prevStart, prevEnd) : BigDecimal.ZERO;

        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal tVol = BigDecimal.ZERO, tMsf = BigDecimal.ZERO, tIcf = BigDecimal.ZERO,
                tSf = BigDecimal.ZERO, tPg = BigDecimal.ZERO, tNm = BigDecimal.ZERO, tAnc = BigDecimal.ZERO;
        long tTxn = 0;
        for (Object[] r : rows) {
            String industry = String.valueOf(r[0]);
            BigDecimal vol = bd(r[1]);
            long txn = lng(r[2]);
            long merch = lng(r[3]);
            BigDecimal msf = bd(r[4]), icf = bd(r[5]), sf = bd(r[6]), pg = bd(r[7]), nm = bd(r[8]);
            BigDecimal ancillary = ancByIndustry.remove(industry);
            if (ancillary == null) ancillary = BigDecimal.ZERO;

            out.add(row(industry, vol, txn, merch, msf, icf, sf, pg, nm, ancillary, nm.add(ancillary)));
            tVol = tVol.add(vol); tTxn += txn; tMsf = tMsf.add(msf); tIcf = tIcf.add(icf);
            tSf = tSf.add(sf); tPg = tPg.add(pg); tNm = tNm.add(nm); tAnc = tAnc.add(ancillary);
        }
        // Sectors with ancillary income but zero processed volume in the window
        // (e.g. rental-only stores) still deserve a row — spread must not vanish.
        for (Map.Entry<String, BigDecimal> e : ancByIndustry.entrySet()) {
            if (e.getValue().signum() == 0) continue;
            out.add(row(e.getKey(), BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, e.getValue(), e.getValue()));
            tAnc = tAnc.add(e.getValue());
        }

        BigDecimal tSpread = tNm.add(tAnc).add(tFx);
        Map<String, Object> totals = row("ALL", tVol, tTxn, 0, tMsf, tIcf, tSf, tPg, tNm, tAnc, tSpread);
        totals.remove("activeMerchants");
        totals.put("fx", tFx);
        boolean priorHasData = pVol.signum() > 0 || pTxn > 0;
        totals.put("volumeGrowthPct", priorHasData ? growth(tVol, pVol) : null);
        totals.put("msfGrowthPct", priorHasData ? growth(tMsf, pMsf) : null);
        totals.put("netMarginGrowthPct", priorHasData ? growth(tNm, pNm) : null);
        totals.put("netSpreadGrowthPct", priorHasData
                ? growth(tSpread, pNm.add(pAnc).add(pFx)) : null);

        Map<String, Object> payload = new HashMap<>();
        payload.put("rows", out);
        payload.put("totals", totals);
        payload.put("start", start.toString());
        payload.put("end", end.toString());
        payload.put("priorStart", prevStart.toString());
        payload.put("priorEnd", prevEnd.toString());
        payload.put("priorWindowHasData", priorHasData);
        payload.put("basis", "SETTLEMENT");
        payload.put("fxEnabled", fxEnabled);
        payload.put("fxAttributable", fxAttributable);
        // DCC/rental have no scheme/channel/card-type dimension — with such a
        // filter active the ancillary can't follow the narrowed volume.
        payload.put("ancillaryApproximate", techFiltered(filter));
        return payload;
    }

    private Map<String, Object> row(String industry, BigDecimal vol, long txn, long merch,
                                    BigDecimal msf, BigDecimal icf, BigDecimal sf, BigDecimal pg,
                                    BigDecimal nm, BigDecimal ancillary, BigDecimal spread) {
        BigDecimal nr = msf.subtract(icf); // after-interchange gross ("Net Revenue")
        Map<String, Object> m = new HashMap<>();
        m.put("industry", industry);
        m.put("volume", vol);
        m.put("txns", txn);
        m.put("activeMerchants", merch);
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
        m.put("pgFeePct", hasVol ? pct(pg, vol) : null);
        m.put("netMarginPct", hasVol ? pct(nm, vol) : null);
        m.put("netSpreadPct", hasVol ? pct(spread, vol) : null);
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
     * DCC acquirer share + rental keyed by the STORE's MCC sector — read from
     * fact_dcc_revenue / fact_rental (both small feed tables, tenant+date
     * indexed) through their own store_id. A row whose store can't be
     * resolved (MID-level rentals, missing SID match) folds into 'MIS'
     * rather than borrowing the merchant's MCC. dcc_merchant is never
     * included (merchant's money).
     */
    private Map<String, BigDecimal> fetchAncillaryByStoreSector(VolumeRevenueFilterDTO filter, Long tenantId,
                                                                LocalDate start, LocalDate end) {
        boolean merchFilter = hasMerchantFilter(filter);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COALESCE(rc.category, 'MIS') as industry, SUM(x.amt) as anc ");
        sql.append("FROM ( ");
        sql.append("  SELECT f.store_id, f.merchant_id, f.acquirer_share as amt ");
        sql.append("  FROM fact_dcc_revenue f ");
        sql.append("  WHERE f.tenant_id = :tenantId AND f.payment_date BETWEEN :winStart AND :winEnd ");
        sql.append("  UNION ALL ");
        sql.append("  SELECT f.store_id, f.merchant_id, f.rental_amount ");
        sql.append("  FROM fact_rental f ");
        sql.append("  WHERE f.tenant_id = :tenantId AND f.payment_date BETWEEN :winStart AND :winEnd ");
        sql.append(") x ");
        sql.append("LEFT JOIN dim_store st ON st.store_id = x.store_id AND st.tenant_id = :tenantId ");
        sql.append("LEFT JOIN ref_mcc_category rc ON rc.mcc = st.mcc ");
        if (merchFilter)
            sql.append("LEFT JOIN dim_merchant m ON m.merchant_id = x.merchant_id AND m.tenant_id = :tenantId ");
        sql.append("WHERE 1=1 ");
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        // Industry picks arrive as MCC codes — store-level here too.
        if (listNonEmpty(filter.getMccList()))        sql.append("AND st.mcc IN (:mccs) ");
        sql.append("GROUP BY COALESCE(rc.category, 'MIS')");

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
        if (listNonEmpty(filter.getMccList()))        query.setParameter("mccs", filter.getMccList());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Object[] r : rows) out.put(String.valueOf(r[0]), bd(r[1]));
        return out;
    }

    /** Window FX total from sum_daily_merchant (merchant-scope filters only, no MCC). */
    private BigDecimal fetchFxTotal(VolumeRevenueFilterDTO filter, Long tenantId,
                                    LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COALESCE(SUM(COALESCE(s.fx_revenue,0)), 0) FROM sum_daily_merchant s ");
        boolean merchFilter = hasMerchantFilter(filter);
        if (merchFilter)
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
    // 2) Monthly trend — P&L lines per month (no industry split)
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTrend(VolumeRevenueFilterDTO filter, Long tenantId, boolean fxEnabled) {
        requireTenant(tenantId);
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.withDayOfYear(1);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TO_CHAR(s.business_date, 'YYYY-MM') as month_label, ");
        sql.append("SUM(s.total_volume) as vol, ");
        sql.append("SUM(s.total_txns) as txn, ");
        sql.append("SUM(s.total_msf) as msf, ");
        sql.append("SUM(s.total_interchange) as icf, ");
        sql.append("SUM(s.total_scheme_fee) as sf, ");
        sql.append("SUM(s.total_net_revenue) as nm ");
        sql.append("FROM sum_daily_full s ");
        appendJoins(sql, filter);
        sql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        sql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY TO_CHAR(s.business_date, 'YYYY-MM') ");
        sql.append("ORDER BY month_label ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("winStart", start);
        query.setParameter("winEnd", end);
        query.setParameter("tenantId", tenantId);
        bindCommonParams(query, filter);

        // Monthly ancillary (store-sector facts) + FX totals, so the trend can
        // carry net spread too. FX follows the same attribution rule as rows.
        Map<String, BigDecimal> ancByMonth = fetchAncillaryByMonth(filter, tenantId, start, end);
        boolean fxAttributable = fxEnabled && !listNonEmpty(filter.getMccList());
        Map<String, BigDecimal> fxByMonth = fxAttributable
                ? fetchFxByMonth(filter, tenantId, start, end) : Map.of();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String month = String.valueOf(r[0]);
            BigDecimal msf = bd(r[3]), icf = bd(r[4]), nm = bd(r[6]);
            BigDecimal spread = nm.add(ancByMonth.getOrDefault(month, BigDecimal.ZERO))
                    .add(fxByMonth.getOrDefault(month, BigDecimal.ZERO));
            Map<String, Object> m = new HashMap<>();
            m.put("month", month);
            m.put("volume", bd(r[1]));
            m.put("txns", lng(r[2]));
            m.put("msf", msf);
            m.put("icf", icf);
            m.put("netRevenue", msf.subtract(icf));
            m.put("schemeFee", bd(r[5]));
            m.put("netMargin", nm);
            m.put("netSpread", spread);
            out.add(m);
        }
        return out;
    }

    /** Monthly DCC+rental totals from the facts (same filter surface as the sector query). */
    private Map<String, BigDecimal> fetchAncillaryByMonth(VolumeRevenueFilterDTO filter, Long tenantId,
                                                          LocalDate start, LocalDate end) {
        boolean merchFilter = hasMerchantFilter(filter);
        boolean mccFilter = listNonEmpty(filter.getMccList());
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TO_CHAR(x.payment_date, 'YYYY-MM') as month_label, SUM(x.amt) as anc ");
        sql.append("FROM ( ");
        sql.append("  SELECT f.payment_date, f.store_id, f.merchant_id, f.acquirer_share as amt ");
        sql.append("  FROM fact_dcc_revenue f ");
        sql.append("  WHERE f.tenant_id = :tenantId AND f.payment_date BETWEEN :winStart AND :winEnd ");
        sql.append("  UNION ALL ");
        sql.append("  SELECT f.payment_date, f.store_id, f.merchant_id, f.rental_amount ");
        sql.append("  FROM fact_rental f ");
        sql.append("  WHERE f.tenant_id = :tenantId AND f.payment_date BETWEEN :winStart AND :winEnd ");
        sql.append(") x ");
        if (mccFilter)
            sql.append("LEFT JOIN dim_store st ON st.store_id = x.store_id AND st.tenant_id = :tenantId ");
        if (merchFilter)
            sql.append("LEFT JOIN dim_merchant m ON m.merchant_id = x.merchant_id AND m.tenant_id = :tenantId ");
        sql.append("WHERE 1=1 ");
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (mccFilter)                                sql.append("AND st.mcc IN (:mccs) ");
        sql.append("GROUP BY TO_CHAR(x.payment_date, 'YYYY-MM')");

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

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object[] r : rows) out.put(String.valueOf(r[0]), bd(r[1]));
        return out;
    }

    /** Monthly FX totals from sum_daily_merchant (merchant-scope filters only). */
    private Map<String, BigDecimal> fetchFxByMonth(VolumeRevenueFilterDTO filter, Long tenantId,
                                                   LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TO_CHAR(s.business_date, 'YYYY-MM') as month_label, ");
        sql.append("COALESCE(SUM(COALESCE(s.fx_revenue,0)), 0) as fx ");
        sql.append("FROM sum_daily_merchant s ");
        if (hasMerchantFilter(filter))
            sql.append("JOIN dim_merchant m ON m.merchant_id = s.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd AND s.tenant_id = :tenantId ");
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        sql.append("GROUP BY TO_CHAR(s.business_date, 'YYYY-MM')");

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

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object[] r : rows) out.put(String.valueOf(r[0]), bd(r[1]));
        return out;
    }
}
