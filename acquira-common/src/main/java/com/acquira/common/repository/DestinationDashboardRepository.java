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
 * Backs the Domestic vs International Destination Dashboard
 * (/business/destination-dashboard). Every query splits on
 * `destination` in a single pass (CASE WHEN UPPER(destination)='DOMESTIC')
 * rather than running two separate queries per mode — Domestic,
 * International, and Compare are purely front-end rendering choices over
 * the same payload.
 *
 * Base table: sum_daily_insight (has destination, card_scheme, card_type,
 * channel, is_opt_in — see ACQUIRA_FEATURE_GUIDE.md §9.1). Note total_volume
 * here is CARDHOLDER currency, not settlement — international rows are a
 * mixed-currency sum. This mirrors how CrossFilterController/Explorer
 * already treat this table; the frontend must show the same caveat tooltip.
 *
 * Destination literal: existing code (VolumeRevenueRepository.getSummary)
 * already treats UPPER(COALESCE(destination,'')) <> 'DOMESTIC' as
 * international, so 'DOMESTIC' is the confirmed stored literal — reused here
 * rather than re-deriving it.
 */
@Repository
public class DestinationDashboardRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String DOM_PRED = "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC'";
    private static final String INTL_PRED = "UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC'";

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
     * Appends every drawer filter EXCEPT destination (that's the split
     * dimension itself here, never a narrowing predicate on this page).
     * Mirrors the fragment style used throughout VolumeRevenueRepository —
     * only emits a clause (and later binds a parameter) when the
     * corresponding list is non-empty.
     */
    private boolean appendCommonFilters(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());

        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
        if (needStore) {
            if (listNonEmpty(filter.getMccList())) sql.append("AND st.mcc IN (:mccs) ");
            if (listNonEmpty(filter.getSidList())) sql.append("AND st.sid IN (:sids) ");
        }
        if (listNonEmpty(filter.getChannelList()))  sql.append("AND s.channel IN (:channels) ");
        if (listNonEmpty(filter.getSchemeList()))   sql.append("AND s.card_scheme IN (:schemes) ");
        if (listNonEmpty(filter.getCardTypeList())) sql.append("AND s.card_type IN (:cardTypes) ");
        // Deliberately no destinationList clause — see class javadoc.

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
        if (listNonEmpty(filter.getCardTypeList()))   query.setParameter("cardTypes", filter.getCardTypeList());
    }

    // ─────────────────────────────────────────────────────────────────
    // 1) KPIs — current vs. prior window, split domestic/international
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getKpis(VolumeRevenueFilterDTO filter, Long tenantId) {
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.minusDays(30);

        long days = ChronoUnit.DAYS.between(start, end);
        if (days == 0) days = 1;

        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        // Domestic — current window
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_vol_curr, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txn_curr, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf_curr, ");
        sql.append("COUNT(DISTINCT CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(DOM_PRED).append(" THEN s.merchant_id END) as dom_merch_curr, ");
        // International — current window
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_vol_curr, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txn_curr, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf_curr, ");
        sql.append("COUNT(DISTINCT CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(INTL_PRED).append(" THEN s.merchant_id END) as intl_merch_curr, ");
        // DCC — international-only opt-in context (is_opt_in only meaningful for cross-border)
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :start AND :end AND ").append(INTL_PRED).append(" AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as intl_optin_vol_curr, ");
        // Domestic — prior window
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :prevStart AND :prevEnd AND ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_vol_prev, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :prevStart AND :prevEnd AND ").append(DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txn_prev, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :prevStart AND :prevEnd AND ").append(DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf_prev, ");
        // International — prior window
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :prevStart AND :prevEnd AND ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_vol_prev, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :prevStart AND :prevEnd AND ").append(INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txn_prev, ");
        sql.append("SUM(CASE WHEN s.business_date BETWEEN :prevStart AND :prevEnd AND ").append(INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf_prev ");

        sql.append("FROM sum_daily_insight s ");
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());
        if (needStore) sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");

        sql.append("WHERE s.business_date >= :prevStart AND s.business_date <= :end ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(sql, filter);

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("start", start);
        query.setParameter("end", end);
        query.setParameter("prevStart", prevStart);
        query.setParameter("prevEnd", prevEnd);
        if (tenantId != null) query.setParameter("tenantId", tenantId);
        bindCommonParams(query, filter);

        Map<String, Object> out = new HashMap<>();
        try {
            Object[] r = (Object[]) query.getSingleResult();
            BigDecimal domVolCurr = bd(r[0]);   long domTxnCurr = lng(r[1]);   BigDecimal domMsfCurr = bd(r[2]);   long domMerchCurr = lng(r[3]);
            BigDecimal intlVolCurr = bd(r[4]);  long intlTxnCurr = lng(r[5]);  BigDecimal intlMsfCurr = bd(r[6]);  long intlMerchCurr = lng(r[7]);
            BigDecimal intlOptInVolCurr = bd(r[8]);
            BigDecimal domVolPrev = bd(r[9]);   long domTxnPrev = lng(r[10]);  BigDecimal domMsfPrev = bd(r[11]);
            BigDecimal intlVolPrev = bd(r[12]); long intlTxnPrev = lng(r[13]); BigDecimal intlMsfPrev = bd(r[14]);

            out.put("domestic", kpiBlock(domVolCurr, domTxnCurr, domMsfCurr, domMerchCurr, domVolPrev, domTxnPrev, domMsfPrev));
            Map<String, Object> intlBlock = kpiBlock(intlVolCurr, intlTxnCurr, intlMsfCurr, intlMerchCurr, intlVolPrev, intlTxnPrev, intlMsfPrev);
            intlBlock.put("dccOptInVolume", intlOptInVolCurr);
            intlBlock.put("dccOptInRatePct", intlVolCurr.signum() > 0
                    ? intlOptInVolCurr.doubleValue() / intlVolCurr.doubleValue() * 100.0 : 0.0);
            intlBlock.put("dccMissedVolume", intlVolCurr.subtract(intlOptInVolCurr));
            out.put("international", intlBlock);

            BigDecimal totalVolCurr = domVolCurr.add(intlVolCurr);
            BigDecimal totalVolPrev = domVolPrev.add(intlVolPrev);
            out.put("domesticSharePct", totalVolCurr.signum() > 0 ? domVolCurr.doubleValue() / totalVolCurr.doubleValue() * 100.0 : 0.0);
            out.put("internationalSharePct", totalVolCurr.signum() > 0 ? intlVolCurr.doubleValue() / totalVolCurr.doubleValue() * 100.0 : 0.0);

            boolean priorHasData = domVolPrev.signum() > 0 || intlVolPrev.signum() > 0 || domTxnPrev > 0 || intlTxnPrev > 0;
            out.put("priorWindowHasData", priorHasData);
        } catch (Exception e) {
            out.put("domestic", kpiBlock(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO));
            out.put("international", kpiBlock(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO));
            out.put("domesticSharePct", 0.0);
            out.put("internationalSharePct", 0.0);
            out.put("priorWindowHasData", false);
        }
        out.put("priorStart", prevStart.toString());
        out.put("priorEnd", prevEnd.toString());
        out.put("start", start.toString());
        out.put("end", end.toString());
        return out;
    }

    private Map<String, Object> kpiBlock(BigDecimal volCurr, long txnCurr, BigDecimal msfCurr, long merchCurr,
                                          BigDecimal volPrev, long txnPrev, BigDecimal msfPrev) {
        Map<String, Object> b = new HashMap<>();
        b.put("volume", volCurr);
        b.put("volumeGrowthPct", growth(volCurr.doubleValue(), volPrev.doubleValue()));
        b.put("txns", txnCurr);
        b.put("txnsGrowthPct", growth(txnCurr, txnPrev));
        b.put("msf", msfCurr);
        b.put("msfGrowthPct", growth(msfCurr.doubleValue(), msfPrev.doubleValue()));
        b.put("activeMerchants", merchCurr);
        b.put("avgTicket", txnCurr > 0 ? volCurr.doubleValue() / txnCurr : 0.0);
        b.put("effectiveRateBps", volCurr.signum() > 0 ? msfCurr.doubleValue() / volCurr.doubleValue() * 10000.0 : 0.0);
        return b;
    }

    private double growth(double curr, double prev) {
        if (prev == 0) return curr > 0 ? 100.0 : 0.0;
        return (curr - prev) / prev * 100.0;
    }

    // ─────────────────────────────────────────────────────────────────
    // 2) Monthly trend — domestic + international columns in one row
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTrend(VolumeRevenueFilterDTO filter, Long tenantId) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TO_CHAR(s.business_date, 'YYYY-MM') as month_label, ");
        sql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_volume, ");
        sql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txns, ");
        sql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf, ");
        sql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_volume, ");
        sql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txns, ");
        sql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf ");
        sql.append("FROM sum_daily_insight s ");
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());
        if (needStore) sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");

        sql.append("WHERE 1=1 ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY TO_CHAR(s.business_date, 'YYYY-MM') ORDER BY month_label ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        if (tenantId != null) query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("month", r[0]);
            m.put("domVolume", bd(r[1])); m.put("domTxns", lng(r[2])); m.put("domMsf", bd(r[3]));
            m.put("intlVolume", bd(r[4])); m.put("intlTxns", lng(r[5])); m.put("intlMsf", bd(r[6]));
            out.add(m);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 3) Breakdown by scheme / cardType / channel / mcc — dom+intl split
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getBreakdown(VolumeRevenueFilterDTO filter, String dimension, Long tenantId) {
        String groupCol;
        boolean needStoreForGroup = false;
        switch (dimension) {
            case "scheme":   groupCol = "s.card_scheme"; break;
            case "cardType": groupCol = "s.card_type"; break;
            case "channel":  groupCol = "s.channel"; break;
            case "mcc":      groupCol = "st.mcc"; needStoreForGroup = true; break;
            default: throw new IllegalArgumentException("Unknown breakdown dimension: " + dimension);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(groupCol).append(" as dim_value, ");
        sql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_volume, ");
        sql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txns, ");
        sql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_volume, ");
        sql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txns ");
        sql.append("FROM sum_daily_insight s ");
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        boolean needStore = needStoreForGroup || listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());
        if (needStore) sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");

        sql.append("WHERE 1=1 ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY ").append(groupCol).append(" ");
        sql.append("HAVING ").append(groupCol).append(" IS NOT NULL ");
        sql.append("ORDER BY (SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) + ")
           .append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END)) DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        if (tenantId != null) query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("dimensionValue", r[0]);
            m.put("domVolume", bd(r[1])); m.put("domTxns", lng(r[2]));
            m.put("intlVolume", bd(r[3])); m.put("intlTxns", lng(r[4]));
            out.add(m);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 4) Top merchants — dom+intl split, ranked by total volume, with
    //    international-share % so the FE can also rank by intl-share
    //    (flags travel/FX/DCC-opportunity merchants).
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTopMerchants(VolumeRevenueFilterDTO filter, Long tenantId, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid as mid, m.name as merchant_name, ");
        sql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_volume, ");
        sql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf, ");
        sql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_volume, ");
        sql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf ");
        sql.append("FROM sum_daily_insight s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());
        if (needStore) sql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");

        sql.append("WHERE 1=1 ");
        if (tenantId != null) sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY m.mid, m.name ");
        sql.append("ORDER BY (SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) + ")
           .append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END)) DESC ");
        sql.append("LIMIT :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        if (tenantId != null) query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        bindCommonParams(query, filter);
        query.setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal domVol = bd(r[2]);
            BigDecimal intlVol = bd(r[4]);
            BigDecimal totalVol = domVol.add(intlVol);
            Map<String, Object> m = new HashMap<>();
            m.put("mid", r[0]);
            m.put("merchantName", r[1]);
            m.put("domVolume", domVol);
            m.put("domMsf", bd(r[3]));
            m.put("intlVolume", intlVol);
            m.put("intlMsf", bd(r[5]));
            m.put("totalVolume", totalVol);
            m.put("intlSharePct", totalVol.signum() > 0 ? intlVol.doubleValue() / totalVol.doubleValue() * 100.0 : 0.0);
            out.add(m);
        }
        return out;
    }
}
