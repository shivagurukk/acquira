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
import java.util.List;
import java.util.Map;

/**
 * Backs the Card Type Dashboard (/business/card-type-dashboard) — the
 * CREDIT / DEBIT / PREPAID / … replica of the Destination Dashboard, with
 * card_type as the split dimension instead of destination.
 *
 * Unlike DestinationDashboardRepository (which routes between a fast
 * settlement pre-aggregate and a cardholder-currency fallback), every query
 * here reads ONE table: sum_daily_full. It is the only summary that carries
 * card_type AND settlement-currency volume AND the real fee stack
 * (total_interchange / total_scheme_fee / total_ecom_fee / total_net_revenue)
 * at once — so the payload basis is always "SETTLEMENT" and no
 * needsInsightFallback() routing exists on this page.
 *
 * card_type is N-valued (CREDIT / DEBIT / PREPAID / COMMERCIAL / blank …),
 * not binary, so instead of fixed dom/intl CASE columns the queries GROUP BY
 * a normalized card_type expression and return one row/block per type; the
 * frontend renders the segments dynamically. Blank/NULL rows are kept as
 * 'UNSPECIFIED' so volume never silently disappears from the totals.
 *
 * cardTypeList in VolumeRevenueFilterDTO is intentionally ignored — card
 * type is the split dimension itself here, never a narrowing filter (same
 * convention as destinationList on the Destination Dashboard). destinationList
 * IS honored here (sum_daily_full carries destination), and mcc filters hit
 * s.mcc directly — dim_store is only joined for sid filters.
 */
@Repository
public class CardTypeDashboardRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** Normalized split key — trims, uppercases, and folds blank/NULL into 'UNSPECIFIED'. */
    private static final String CT_EXPR = "UPPER(COALESCE(NULLIF(TRIM(s.card_type),''),'UNSPECIFIED'))";

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

    /**
     * Appends every drawer filter EXCEPT card type (that's the split
     * dimension itself on this page). Mirrors
     * DestinationDashboardRepository.appendCommonFilters, adjusted for
     * sum_daily_full: mcc lives on s directly; dim_store is only needed
     * for sid; destination is a plain narrowing predicate here.
     */
    private boolean appendCommonFilters(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        boolean needStore = listNonEmpty(filter.getSidList());

        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (listNonEmpty(filter.getMccList()))        sql.append("AND s.mcc IN (:mccs) ");
        if (needStore)                                sql.append("AND st.sid IN (:sids) ");
        if (listNonEmpty(filter.getChannelList()))    sql.append("AND s.channel IN (:channels) ");
        if (listNonEmpty(filter.getSchemeList()))     sql.append("AND s.card_scheme IN (:schemes) ");
        if (listNonEmpty(filter.getDestinationList())) sql.append("AND UPPER(COALESCE(s.destination,'')) IN (:destinations) ");
        // Deliberately no cardTypeList clause — see class javadoc.

        return needStore;
    }

    private void bindCommonParams(Query query, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getIndustryList()))   query.setParameter("industries", filter.getIndustryList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (listNonEmpty(filter.getMccList()))        query.setParameter("mccs", filter.getMccList());
        if (listNonEmpty(filter.getSidList()))        query.setParameter("sids", filter.getSidList());
        if (listNonEmpty(filter.getChannelList()))    query.setParameter("channels", filter.getChannelList());
        if (listNonEmpty(filter.getSchemeList()))     query.setParameter("schemes", filter.getSchemeList());
        if (listNonEmpty(filter.getDestinationList()))
            query.setParameter("destinations",
                    filter.getDestinationList().stream().map(d -> d == null ? "" : d.toUpperCase()).toList());
    }

    /** Fail closed: a null tenant must never silently widen a query to every tenant. */
    private static void requireTenant(Long tenantId) {
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved — refusing unscoped query");
    }

    private void appendJoins(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        // LEFT: merchant_id can be NULL on sum_daily_full (unmatched-merchant fact rows).
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (listNonEmpty(filter.getSidList()))
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
    }

    // ─────────────────────────────────────────────────────────────────
    // 0) Data bounds — MIN/MAX business_date in THIS page's backing table.
    //
    // The shared /business/data-bounds endpoint is anchored on
    // fact_transaction (with a sum_daily_insight fallback), and those tables
    // can cover a different date range than sum_daily_full — the summary
    // survives a fact-table prune/reload, so its data can extend well past
    // the fact max. Anchoring the page's default window on the shared bounds
    // therefore opens the screen on a range with no rows in it. This endpoint
    // lets the page anchor on the dates it can actually render.
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
    // 1) KPIs — current vs. prior window, one block per card type
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getKpis(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.minusDays(30);

        long days = ChronoUnit.DAYS.between(start, end);
        if (days == 0) days = 1;

        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days);

        // Current and prior windows run as two SEPARATE scans instead of one
        // combined prevStart→end scan. The combined form scanned ~2× the
        // selected window (for YTD: this year + an equal slice of last year
        // across two partitions) even though the prior side only needs
        // vol/txn/msf — this was the dominant cost of "This year"/"Last year".
        StringBuilder currSql = new StringBuilder();
        currSql.append("SELECT ").append(CT_EXPR).append(" as ct, ");
        currSql.append("SUM(s.total_volume) as vol, ");
        currSql.append("SUM(s.total_txns) as txn, ");
        currSql.append("SUM(s.total_msf) as msf, ");
        currSql.append("COUNT(DISTINCT s.merchant_id) as merch, ");
        currSql.append("SUM(s.total_interchange) as ic, ");
        currSql.append("SUM(s.total_net_revenue) as nr ");
        currSql.append("FROM sum_daily_full s ");
        appendJoins(currSql, filter);
        currSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        currSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(currSql, filter);
        currSql.append("GROUP BY ").append(CT_EXPR).append(" ");
        currSql.append("ORDER BY vol DESC");

        StringBuilder prevSql = new StringBuilder();
        prevSql.append("SELECT ").append(CT_EXPR).append(" as ct, ");
        prevSql.append("SUM(s.total_volume) as vol, ");
        prevSql.append("SUM(s.total_txns) as txn, ");
        prevSql.append("SUM(s.total_msf) as msf ");
        prevSql.append("FROM sum_daily_full s ");
        appendJoins(prevSql, filter);
        prevSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        prevSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(prevSql, filter);
        prevSql.append("GROUP BY ").append(CT_EXPR);

        Query currQuery = entityManager.createNativeQuery(currSql.toString());
        currQuery.setParameter("winStart", start);
        currQuery.setParameter("winEnd", end);
        currQuery.setParameter("tenantId", tenantId);
        bindCommonParams(currQuery, filter);

        Query prevQuery = entityManager.createNativeQuery(prevSql.toString());
        prevQuery.setParameter("winStart", prevStart);
        prevQuery.setParameter("winEnd", prevEnd);
        prevQuery.setParameter("tenantId", tenantId);
        bindCommonParams(prevQuery, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = currQuery.getResultList();
        @SuppressWarnings("unchecked")
        List<Object[]> prevRows = prevQuery.getResultList();

        // Prior-window aggregates keyed by card type. A type that only has
        // prior-window volume (present last period, gone this period) is not
        // rendered as a block — same behavior as the old combined query's
        // rows would have had vol_curr = 0, which sorted last and rendered
        // as an empty segment; dropping it entirely is the cleaner read.
        Map<String, Object[]> prevByType = new HashMap<>();
        for (Object[] p : prevRows) prevByType.put(String.valueOf(p[0]), p);

        BigDecimal totalVolCurr = BigDecimal.ZERO;
        boolean priorHasData = false;
        for (Object[] p : prevRows)
            priorHasData = priorHasData || bd(p[1]).signum() > 0 || lng(p[2]) > 0;
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal volCurr = bd(r[1]); long txnCurr = lng(r[2]); BigDecimal msfCurr = bd(r[3]); long merchCurr = lng(r[4]);
            BigDecimal icCurr = bd(r[5]); BigDecimal nrCurr = bd(r[6]);
            Object[] p = prevByType.get(String.valueOf(r[0]));
            BigDecimal volPrev = p != null ? bd(p[1]) : BigDecimal.ZERO;
            long txnPrev = p != null ? lng(p[2]) : 0L;
            BigDecimal msfPrev = p != null ? bd(p[3]) : BigDecimal.ZERO;

            Map<String, Object> b = new HashMap<>();
            b.put("cardType", r[0]);
            b.put("volume", volCurr);
            b.put("volumeGrowthPct", growth(volCurr.doubleValue(), volPrev.doubleValue()));
            b.put("txns", txnCurr);
            b.put("txnsGrowthPct", growth(txnCurr, txnPrev));
            b.put("msf", msfCurr);
            b.put("msfGrowthPct", growth(msfCurr.doubleValue(), msfPrev.doubleValue()));
            b.put("activeMerchants", merchCurr);
            b.put("avgTicket", txnCurr > 0 ? volCurr.doubleValue() / txnCurr : 0.0);
            b.put("effectiveRateBps", volCurr.signum() > 0 ? msfCurr.doubleValue() / volCurr.doubleValue() * 10000.0 : 0.0);
            b.put("interchange", icCurr);
            b.put("netRevenue", nrCurr);
            blocks.add(b);

            totalVolCurr = totalVolCurr.add(volCurr);
            priorHasData = priorHasData || volPrev.signum() > 0 || txnPrev > 0;
        }
        // Share % once totals are known
        for (Map<String, Object> b : blocks) {
            BigDecimal v = (BigDecimal) b.get("volume");
            b.put("sharePct", totalVolCurr.signum() > 0 ? v.doubleValue() / totalVolCurr.doubleValue() * 100.0 : 0.0);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("cardTypes", blocks);
        out.put("totalVolume", totalVolCurr);
        out.put("priorWindowHasData", priorHasData);
        out.put("priorStart", prevStart.toString());
        out.put("priorEnd", prevEnd.toString());
        out.put("start", start.toString());
        out.put("end", end.toString());
        out.put("basis", "SETTLEMENT");
        return out;
    }

    private double growth(double curr, double prev) {
        if (prev == 0) return curr > 0 ? 100.0 : 0.0;
        return (curr - prev) / prev * 100.0;
    }

    // ─────────────────────────────────────────────────────────────────
    // 2) Monthly trend — one row per month × card type (frontend pivots)
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTrend(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TO_CHAR(s.business_date, 'YYYY-MM') as month_label, ");
        sql.append(CT_EXPR).append(" as ct, ");
        sql.append("SUM(s.total_volume) as volume, ");
        sql.append("SUM(s.total_txns) as txns, ");
        sql.append("SUM(s.total_msf) as msf ");
        sql.append("FROM sum_daily_full s ");
        appendJoins(sql, filter);

        sql.append("WHERE 1=1 ");
        sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY TO_CHAR(s.business_date, 'YYYY-MM'), ").append(CT_EXPR).append(" ");
        sql.append("ORDER BY month_label ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("month", r[0]);
            m.put("cardType", r[1]);
            m.put("volume", bd(r[2]));
            m.put("txns", lng(r[3]));
            m.put("msf", bd(r[4]));
            out.add(m);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 3) Breakdown by scheme / destination / channel / mcc — × card type
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getBreakdown(VolumeRevenueFilterDTO filter, String dimension, Long tenantId) {
        requireTenant(tenantId);
        String groupCol;
        switch (dimension) {
            case "scheme":      groupCol = "s.card_scheme"; break;
            case "destination": groupCol = "s.destination"; break;
            case "channel":     groupCol = "s.channel"; break;
            case "mcc":         groupCol = "s.mcc"; break;
            default: throw new IllegalArgumentException("Unknown breakdown dimension: " + dimension);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(groupCol).append(" as dim_value, ");
        sql.append(CT_EXPR).append(" as ct, ");
        sql.append("SUM(s.total_volume) as volume, ");
        sql.append("SUM(s.total_txns) as txns ");
        sql.append("FROM sum_daily_full s ");
        appendJoins(sql, filter);

        sql.append("WHERE 1=1 ");
        sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY ").append(groupCol).append(", ").append(CT_EXPR).append(" ");
        sql.append("HAVING ").append(groupCol).append(" IS NOT NULL ");
        sql.append("ORDER BY SUM(s.total_volume) DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        // Cap the payload at the top dimension values by total volume and fold
        // the remainder into a single "Other" bucket. Unbounded MCC lists (a
        // few hundred values × N card types) produced a multi-thousand-bar
        // chart on the frontend — the tail is unreadable there anyway, and
        // the fold keeps the stacked totals exact.
        Map<String, BigDecimal> dimTotals = new java.util.LinkedHashMap<>();
        for (Object[] r : rows) {
            String dim = String.valueOf(r[0]);
            dimTotals.merge(dim, bd(r[2]), BigDecimal::add);
        }
        java.util.Set<String> topDims = dimTotals.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(BREAKDOWN_TOP_N)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> otherByType = new java.util.LinkedHashMap<>();
        for (Object[] r : rows) {
            String dim = String.valueOf(r[0]);
            if (topDims.contains(dim)) {
                Map<String, Object> m = new HashMap<>();
                m.put("dimensionValue", r[0]);
                m.put("cardType", r[1]);
                m.put("volume", bd(r[2]));
                m.put("txns", lng(r[3]));
                out.add(m);
            } else {
                String ct = String.valueOf(r[1]);
                Map<String, Object> m = otherByType.computeIfAbsent(ct, k -> {
                    Map<String, Object> x = new HashMap<>();
                    x.put("dimensionValue", "Other");
                    x.put("cardType", k);
                    x.put("volume", BigDecimal.ZERO);
                    x.put("txns", 0L);
                    return x;
                });
                m.put("volume", ((BigDecimal) m.get("volume")).add(bd(r[2])));
                m.put("txns", (Long) m.get("txns") + lng(r[3]));
            }
        }
        out.addAll(otherByType.values());
        return out;
    }

    /** Max distinct dimension values a breakdown returns before folding into "Other". */
    private static final int BREAKDOWN_TOP_N = 25;

    // ─────────────────────────────────────────────────────────────────
    // 4) Top merchants — per-type volume columns + full fee stack,
    //    ranked by total volume. CREDIT/DEBIT/PREPAID get fixed columns
    //    (the grid needs a stable shape); every other value folds into
    //    otherVolume so the row total is always exact.
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTopMerchants(VolumeRevenueFilterDTO filter, Long tenantId, int limit) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid as mid, m.name as merchant_name, ");
        sql.append("SUM(CASE WHEN ").append(CT_EXPR).append(" = 'CREDIT' THEN s.total_volume ELSE 0 END) as credit_vol, ");
        sql.append("SUM(CASE WHEN ").append(CT_EXPR).append(" = 'DEBIT' THEN s.total_volume ELSE 0 END) as debit_vol, ");
        sql.append("SUM(CASE WHEN ").append(CT_EXPR).append(" = 'PREPAID' THEN s.total_volume ELSE 0 END) as prepaid_vol, ");
        sql.append("SUM(CASE WHEN ").append(CT_EXPR).append(" NOT IN ('CREDIT','DEBIT','PREPAID') THEN s.total_volume ELSE 0 END) as other_vol, ");
        sql.append("SUM(s.total_msf) as msf, ");
        // Fee stack + margin across ALL card types — the table reads as a
        // per-merchant P&L line. total_net_revenue is the batch-computed
        // MSF − interchange − scheme fee − ecom fee; never recomputed here.
        sql.append("SUM(s.total_interchange) as icf, ");
        sql.append("SUM(s.total_scheme_fee) as sf, ");
        sql.append("SUM(s.total_ecom_fee) as pg, ");
        sql.append("SUM(s.total_net_revenue) as nm ");
        sql.append("FROM sum_daily_full s ");
        // INNER — a merchant ranking has no row for NULL merchant_id.
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (listNonEmpty(filter.getSidList()))
            sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");

        sql.append("WHERE 1=1 ");
        sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY m.mid, m.name ");
        sql.append("ORDER BY SUM(s.total_volume) DESC ");
        sql.append("LIMIT :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        bindCommonParams(query, filter);
        query.setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal creditVol = bd(r[2]);
            BigDecimal debitVol = bd(r[3]);
            BigDecimal prepaidVol = bd(r[4]);
            BigDecimal otherVol = bd(r[5]);
            BigDecimal totalVol = creditVol.add(debitVol).add(prepaidVol).add(otherVol);
            BigDecimal nm = bd(r[10]);
            Map<String, Object> m = new HashMap<>();
            m.put("mid", r[0]);
            m.put("merchantName", r[1]);
            m.put("creditVolume", creditVol);
            m.put("debitVolume", debitVol);
            m.put("prepaidVolume", prepaidVol);
            m.put("otherVolume", otherVol);
            m.put("msf", bd(r[6]));
            m.put("icf", bd(r[7]));
            m.put("sf", bd(r[8]));
            m.put("pg", bd(r[9]));
            m.put("netMargin", nm);
            // Margin % is undefined without volume — null, never a fake 0.00.
            m.put("marginPct", totalVol.signum() > 0
                    ? nm.doubleValue() / totalVol.doubleValue() * 100.0 : null);
            m.put("totalVolume", totalVol);
            m.put("creditSharePct", totalVol.signum() > 0 ? creditVol.doubleValue() / totalVol.doubleValue() * 100.0 : 0.0);
            out.add(m);
        }
        return out;
    }
}
