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
 * Routing: two backing tables.
 *
 * Fast/accurate path — sum_daily_merchant_destination (tenant_id,
 * business_date, merchant_id, destination in ('DOMESTIC','INTERNATIONAL'),
 * total_txns, total_volume, total_msf, total_interchange, total_scheme_fee,
 * total_ecom_fee, total_net_revenue). total_volume here is SETTLEMENT
 * currency, so international sums are a single-currency, board-accurate
 * number. Used by getKpis/getTrend/getTopMerchants whenever no dimensional
 * filter (channel/scheme/cardType/mcc/sid) is set; merchant-level filters
 * (partner/rm/teamLeader/mid/industry/merchantName) still work via the
 * dim_merchant join. DCC KPIs come from a companion query on
 * sum_daily_merchant (dcc_optin_volume / dcc_eligible_volume, both
 * international-only and settlement currency).
 *
 * Dimensional fallback — sum_daily_insight (has destination, card_scheme,
 * card_type, channel, is_opt_in — see ACQUIRA_FEATURE_GUIDE.md §9.1). Note
 * total_volume here is CARDHOLDER currency, not settlement — international
 * rows are a mixed-currency sum. This mirrors how
 * CrossFilterController/Explorer already treat this table. getKpis reports
 * which basis produced the payload via "basis": "SETTLEMENT" |
 * "CARDHOLDER_MIXED" so the frontend can show the mixed-currency caveat
 * only when it actually applies. getBreakdown always uses this table
 * (its dimensions don't exist on the destination pre-aggregate).
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

    // sum_daily_merchant_destination stores exactly two literals at this grain.
    private static final String DEST_DOM_PRED = "s.destination = 'DOMESTIC'";
    private static final String DEST_INTL_PRED = "s.destination = 'INTERNATIONAL'";

    private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }

    /** Dimensional filters only exist on sum_daily_insight; when any is set we must fall back to it (cardholder-currency basis). */
    private static boolean needsInsightFallback(VolumeRevenueFilterDTO f) {
        return listNonEmpty(f.getChannelList()) || listNonEmpty(f.getSchemeList())
            || listNonEmpty(f.getCardTypeList()) || listNonEmpty(f.getMccList())
            || listNonEmpty(f.getSidList());
    }

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

    /** Fail closed: a null tenant must never silently widen a query to every tenant. */
    private static void requireTenant(Long tenantId) {
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved — refusing unscoped query");
    }

    // ─────────────────────────────────────────────────────────────────
    // 0) Data bounds — MIN/MAX business_date in THIS page's backing table.
    //
    // The shared /business/data-bounds endpoint is fact_transaction-anchored
    // (insight fallback), and those tables can cover a different range than
    // sum_daily_merchant_destination — anchoring the page's presets on the
    // shared bounds made "This year" span months this table has no rows for
    // (the 2-month YTD symptom). Same pattern as CardTypeDashboardRepository.
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getBounds(Long tenantId) {
        requireTenant(tenantId);
        Query query = entityManager.createNativeQuery(
                "SELECT MIN(s.business_date), MAX(s.business_date) FROM sum_daily_merchant_destination s WHERE s.tenant_id = :tenantId");
        query.setParameter("tenantId", tenantId);

        Object[] r = (Object[]) query.getSingleResult();
        Map<String, Object> out = new HashMap<>();
        out.put("earliest", r[0] == null ? null : r[0].toString());
        out.put("latest", r[1] == null ? null : r[1].toString());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 1) KPIs — current vs. prior window, split domestic/international
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getKpis(VolumeRevenueFilterDTO filter, Long tenantId) {
        return needsInsightFallback(filter)
                ? getKpisFromInsight(filter, tenantId)
                : getKpisFromDestination(filter, tenantId);
    }

    /** Settlement-currency fast path against sum_daily_merchant_destination. */
    private Map<String, Object> getKpisFromDestination(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.minusDays(30);

        long days = ChronoUnit.DAYS.between(start, end);
        if (days == 0) days = 1;

        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days);

        // Current and prior windows run as two SEPARATE scans instead of one
        // combined prevStart→end scan. The combined form scanned ~2× the
        // selected window (and for YTD, current-year + an equal-length slice
        // of last year across two partitions) even though the prior side only
        // needs 6 of the 18 aggregates — this was the dominant cost of the
        // "This year"/"Last year" presets.
        StringBuilder currSql = new StringBuilder();
        currSql.append("SELECT ");
        currSql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_vol, ");
        currSql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txn, ");
        currSql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf, ");
        currSql.append("COUNT(DISTINCT CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.merchant_id END) as dom_merch, ");
        currSql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_vol, ");
        currSql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txn, ");
        currSql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf, ");
        currSql.append("COUNT(DISTINCT CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.merchant_id END) as intl_merch, ");
        // Real-fee extras — only available on this table, additive to the payload
        currSql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_interchange ELSE 0 END) as dom_ic, ");
        currSql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_net_revenue ELSE 0 END) as dom_nr, ");
        currSql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_interchange ELSE 0 END) as intl_ic, ");
        currSql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_net_revenue ELSE 0 END) as intl_nr ");
        currSql.append("FROM sum_daily_merchant_destination s ");
        // LEFT: merchant_id can be NULL on this table (unmatched-merchant fact rows);
        // only merchant-level filters can be set here (routing excludes mcc/sid).
        currSql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        currSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        currSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(currSql, filter);

        StringBuilder prevSql = new StringBuilder();
        prevSql.append("SELECT ");
        prevSql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_vol, ");
        prevSql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txn, ");
        prevSql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf, ");
        prevSql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_vol, ");
        prevSql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txn, ");
        prevSql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf ");
        prevSql.append("FROM sum_daily_merchant_destination s ");
        prevSql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        prevSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        prevSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(prevSql, filter);

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

        Map<String, Object> out = new HashMap<>();
        {
            Object[] r = (Object[]) currQuery.getSingleResult();
            Object[] p = (Object[]) prevQuery.getSingleResult();
            BigDecimal domVolCurr = bd(r[0]);   long domTxnCurr = lng(r[1]);   BigDecimal domMsfCurr = bd(r[2]);   long domMerchCurr = lng(r[3]);
            BigDecimal intlVolCurr = bd(r[4]);  long intlTxnCurr = lng(r[5]);  BigDecimal intlMsfCurr = bd(r[6]);  long intlMerchCurr = lng(r[7]);
            BigDecimal domIcCurr = bd(r[8]);    BigDecimal domNrCurr = bd(r[9]);
            BigDecimal intlIcCurr = bd(r[10]);  BigDecimal intlNrCurr = bd(r[11]);
            BigDecimal domVolPrev = bd(p[0]);   long domTxnPrev = lng(p[1]);   BigDecimal domMsfPrev = bd(p[2]);
            BigDecimal intlVolPrev = bd(p[3]);  long intlTxnPrev = lng(p[4]);  BigDecimal intlMsfPrev = bd(p[5]);

            Map<String, Object> domBlock = kpiBlock(domVolCurr, domTxnCurr, domMsfCurr, domMerchCurr, domVolPrev, domTxnPrev, domMsfPrev);
            domBlock.put("interchange", domIcCurr);
            domBlock.put("netRevenue", domNrCurr);
            out.put("domestic", domBlock);

            Map<String, Object> intlBlock = kpiBlock(intlVolCurr, intlTxnCurr, intlMsfCurr, intlMerchCurr, intlVolPrev, intlTxnPrev, intlMsfPrev);
            intlBlock.put("interchange", intlIcCurr);
            intlBlock.put("netRevenue", intlNrCurr);

            // DCC KPIs live on sum_daily_merchant (settlement currency,
            // international-only by construction) — small companion query.
            BigDecimal[] dcc = fetchDccVolumes(filter, tenantId, start, end);
            BigDecimal optIn = dcc[0];
            BigDecimal eligible = dcc[1];
            intlBlock.put("dccOptInVolume", optIn);
            intlBlock.put("dccOptInRatePct", eligible.signum() > 0
                    ? optIn.doubleValue() / eligible.doubleValue() * 100.0 : 0.0);
            intlBlock.put("dccMissedVolume", eligible.subtract(optIn));
            out.put("international", intlBlock);

            BigDecimal totalVolCurr = domVolCurr.add(intlVolCurr);
            out.put("domesticSharePct", totalVolCurr.signum() > 0 ? domVolCurr.doubleValue() / totalVolCurr.doubleValue() * 100.0 : 0.0);
            out.put("internationalSharePct", totalVolCurr.signum() > 0 ? intlVolCurr.doubleValue() / totalVolCurr.doubleValue() * 100.0 : 0.0);

            boolean priorHasData = domVolPrev.signum() > 0 || intlVolPrev.signum() > 0 || domTxnPrev > 0 || intlTxnPrev > 0;
            out.put("priorWindowHasData", priorHasData);
        }
        out.put("priorStart", prevStart.toString());
        out.put("priorEnd", prevEnd.toString());
        out.put("start", start.toString());
        out.put("end", end.toString());
        out.put("basis", "SETTLEMENT");
        return out;
    }

    /**
     * SUM(dcc_optin_volume), SUM(dcc_eligible_volume) from sum_daily_merchant
     * for the current window, honoring the merchant-level filters. Both
     * columns are international-only by construction and settlement currency.
     */
    private BigDecimal[] fetchDccVolumes(VolumeRevenueFilterDTO filter, Long tenantId, LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT SUM(s.dcc_optin_volume) as optin_vol, SUM(s.dcc_eligible_volume) as eligible_vol ");
        sql.append("FROM sum_daily_merchant s ");
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("WHERE s.tenant_id = :tenantId ");
        sql.append("AND s.business_date BETWEEN :start AND :end ");
        appendCommonFilters(sql, filter); // only merchant-level clauses can fire on this path

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        query.setParameter("start", start);
        query.setParameter("end", end);
        bindCommonParams(query, filter);

        Object[] r = (Object[]) query.getSingleResult();
        return new BigDecimal[] { bd(r[0]), bd(r[1]) };
    }

    /** Cardholder-currency fallback against sum_daily_insight (dimensional filters). */
    private Map<String, Object> getKpisFromInsight(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.minusDays(30);

        long days = ChronoUnit.DAYS.between(start, end);
        if (days == 0) days = 1;

        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days);

        // Two windowed scans instead of one prevStart→end scan — same
        // rationale as the destination fast path above.
        boolean needStore = listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList());

        StringBuilder currSql = new StringBuilder();
        currSql.append("SELECT ");
        currSql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_vol, ");
        currSql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txn, ");
        currSql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf, ");
        currSql.append("COUNT(DISTINCT CASE WHEN ").append(DOM_PRED).append(" THEN s.merchant_id END) as dom_merch, ");
        currSql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_vol, ");
        currSql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txn, ");
        currSql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf, ");
        currSql.append("COUNT(DISTINCT CASE WHEN ").append(INTL_PRED).append(" THEN s.merchant_id END) as intl_merch, ");
        // DCC — international-only opt-in context (is_opt_in only meaningful for cross-border)
        currSql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) as intl_optin_vol ");
        currSql.append("FROM sum_daily_insight s ");
        currSql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) currSql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        currSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        currSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(currSql, filter);

        StringBuilder prevSql = new StringBuilder();
        prevSql.append("SELECT ");
        prevSql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_vol, ");
        prevSql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txn, ");
        prevSql.append("SUM(CASE WHEN ").append(DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf, ");
        prevSql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_vol, ");
        prevSql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txn, ");
        prevSql.append("SUM(CASE WHEN ").append(INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf ");
        prevSql.append("FROM sum_daily_insight s ");
        prevSql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore) prevSql.append("JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        prevSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        prevSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(prevSql, filter);

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

        // No defensive catch here: a failed query must surface as a 500 so the
        // screen shows a real error state — a silently-zero dashboard on a
        // revenue screen reads as "no business", which is worse than an error.
        Map<String, Object> out = new HashMap<>();
        {
            Object[] r = (Object[]) currQuery.getSingleResult();
            Object[] p = (Object[]) prevQuery.getSingleResult();
            BigDecimal domVolCurr = bd(r[0]);   long domTxnCurr = lng(r[1]);   BigDecimal domMsfCurr = bd(r[2]);   long domMerchCurr = lng(r[3]);
            BigDecimal intlVolCurr = bd(r[4]);  long intlTxnCurr = lng(r[5]);  BigDecimal intlMsfCurr = bd(r[6]);  long intlMerchCurr = lng(r[7]);
            BigDecimal intlOptInVolCurr = bd(r[8]);
            BigDecimal domVolPrev = bd(p[0]);   long domTxnPrev = lng(p[1]);   BigDecimal domMsfPrev = bd(p[2]);
            BigDecimal intlVolPrev = bd(p[3]);  long intlTxnPrev = lng(p[4]);  BigDecimal intlMsfPrev = bd(p[5]);

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
        }
        out.put("priorStart", prevStart.toString());
        out.put("priorEnd", prevEnd.toString());
        out.put("start", start.toString());
        out.put("end", end.toString());
        out.put("basis", "CARDHOLDER_MIXED");
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
        return needsInsightFallback(filter)
                ? getTrendFromInsight(filter, tenantId)
                : getTrendFromDestination(filter, tenantId);
    }

    /** Settlement-currency fast path against sum_daily_merchant_destination. */
    private List<Map<String, Object>> getTrendFromDestination(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TO_CHAR(s.business_date, 'YYYY-MM') as month_label, ");
        sql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_volume, ");
        sql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_txns ELSE 0 END) as dom_txns, ");
        sql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf, ");
        sql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_volume, ");
        sql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_txns ELSE 0 END) as intl_txns, ");
        sql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf ");
        sql.append("FROM sum_daily_merchant_destination s ");
        // LEFT: merchant_id can be NULL (unmatched-merchant fact rows).
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");

        sql.append("WHERE 1=1 ");
        sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY TO_CHAR(s.business_date, 'YYYY-MM') ORDER BY month_label ASC");

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
            m.put("domVolume", bd(r[1])); m.put("domTxns", lng(r[2])); m.put("domMsf", bd(r[3]));
            m.put("intlVolume", bd(r[4])); m.put("intlTxns", lng(r[5])); m.put("intlMsf", bd(r[6]));
            out.add(m);
        }
        return out;
    }

    /** Cardholder-currency fallback against sum_daily_insight (dimensional filters). */
    private List<Map<String, Object>> getTrendFromInsight(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
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
        // Always sum_daily_insight: the breakdown dimensions (scheme/cardType/channel/mcc) don't exist on sum_daily_merchant_destination.
        requireTenant(tenantId);
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

        // Rows arrive sorted by total volume DESC. Cap the payload at the top
        // values and fold the tail into one "Other" row — unbounded MCC lists
        // (hundreds of values) produced an unreadable multi-thousand-pixel
        // chart on the frontend; the fold keeps the stacked totals exact.
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal otherDomVol = BigDecimal.ZERO, otherIntlVol = BigDecimal.ZERO;
        long otherDomTxn = 0L, otherIntlTxn = 0L;
        boolean hasOther = false;
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            if (i < BREAKDOWN_TOP_N) {
                Map<String, Object> m = new HashMap<>();
                m.put("dimensionValue", r[0]);
                m.put("domVolume", bd(r[1])); m.put("domTxns", lng(r[2]));
                m.put("intlVolume", bd(r[3])); m.put("intlTxns", lng(r[4]));
                out.add(m);
            } else {
                hasOther = true;
                otherDomVol = otherDomVol.add(bd(r[1]));  otherDomTxn += lng(r[2]);
                otherIntlVol = otherIntlVol.add(bd(r[3])); otherIntlTxn += lng(r[4]);
            }
        }
        if (hasOther) {
            Map<String, Object> m = new HashMap<>();
            m.put("dimensionValue", "Other");
            m.put("domVolume", otherDomVol); m.put("domTxns", otherDomTxn);
            m.put("intlVolume", otherIntlVol); m.put("intlTxns", otherIntlTxn);
            out.add(m);
        }
        return out;
    }

    /** Max distinct dimension values a breakdown returns before folding into "Other". */
    private static final int BREAKDOWN_TOP_N = 25;

    // ─────────────────────────────────────────────────────────────────
    // 4) Top merchants — dom+intl split, ranked by total volume, with
    //    international-share % so the FE can also rank by intl-share
    //    (flags travel/FX/DCC-opportunity merchants).
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTopMerchants(VolumeRevenueFilterDTO filter, Long tenantId, int limit) {
        return needsInsightFallback(filter)
                ? getTopMerchantsFromInsight(filter, tenantId, limit)
                : getTopMerchantsFromDestination(filter, tenantId, limit);
    }

    /** Settlement-currency fast path against sum_daily_merchant_destination. */
    private List<Map<String, Object>> getTopMerchantsFromDestination(VolumeRevenueFilterDTO filter, Long tenantId, int limit) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid as mid, m.name as merchant_name, ");
        sql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_volume ELSE 0 END) as dom_volume, ");
        sql.append("SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_msf ELSE 0 END) as dom_msf, ");
        sql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_volume ELSE 0 END) as intl_volume, ");
        sql.append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_msf ELSE 0 END) as intl_msf, ");
        // Fee stack + margin across BOTH destinations — the table reads as a
        // per-merchant P&L line. total_net_revenue is the batch-computed
        // MSF − interchange − scheme fee − ecom fee; never recomputed here.
        sql.append("SUM(s.total_interchange) as icf, ");
        sql.append("SUM(s.total_scheme_fee) as sf, ");
        sql.append("SUM(s.total_ecom_fee) as pg, ");
        sql.append("SUM(s.total_net_revenue) as nm ");
        sql.append("FROM sum_daily_merchant_destination s ");
        // INNER, same as the insight variant — a merchant ranking has no row for NULL merchant_id.
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");

        sql.append("WHERE 1=1 ");
        sql.append("AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY m.mid, m.name ");
        sql.append("ORDER BY (SUM(CASE WHEN ").append(DEST_DOM_PRED).append(" THEN s.total_volume ELSE 0 END) + ")
           .append("SUM(CASE WHEN ").append(DEST_INTL_PRED).append(" THEN s.total_volume ELSE 0 END)) DESC ");
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
            BigDecimal domVol = bd(r[2]);
            BigDecimal intlVol = bd(r[4]);
            BigDecimal totalVol = domVol.add(intlVol);
            BigDecimal nm = bd(r[9]);
            Map<String, Object> m = new HashMap<>();
            m.put("mid", r[0]);
            m.put("merchantName", r[1]);
            m.put("domVolume", domVol);
            m.put("domMsf", bd(r[3]));
            m.put("intlVolume", intlVol);
            m.put("intlMsf", bd(r[5]));
            m.put("icf", bd(r[6]));
            m.put("sf", bd(r[7]));
            m.put("pg", bd(r[8]));
            m.put("netMargin", nm);
            // Margin % is undefined without volume — null, never a fake 0.00.
            m.put("marginPct", totalVol.signum() > 0
                    ? nm.doubleValue() / totalVol.doubleValue() * 100.0 : null);
            m.put("totalVolume", totalVol);
            m.put("intlSharePct", totalVol.signum() > 0 ? intlVol.doubleValue() / totalVol.doubleValue() * 100.0 : 0.0);
            out.add(m);
        }
        return out;
    }

    /** Cardholder-currency fallback against sum_daily_insight (dimensional filters). */
    private List<Map<String, Object>> getTopMerchantsFromInsight(VolumeRevenueFilterDTO filter, Long tenantId, int limit) {
        requireTenant(tenantId);
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
            // sum_daily_insight carries no fee columns — nulls, so the UI shows
            // an honest dash instead of a fabricated zero margin.
            m.put("icf", null);
            m.put("sf", null);
            m.put("pg", null);
            m.put("netMargin", null);
            m.put("marginPct", null);
            m.put("totalVolume", totalVol);
            m.put("intlSharePct", totalVol.signum() > 0 ? intlVol.doubleValue() / totalVol.doubleValue() * 100.0 : 0.0);
            out.add(m);
        }
        return out;
    }
}
