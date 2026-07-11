package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Revenue-efficiency & DCC KPIs for the Business Dashboard.
 *
 * These metrics were "trapped" — the underlying columns are already batch-written
 * but no endpoint surfaced them:
 *   - Effective MSF rate (bps)  = total_msf / total_volume * 10000
 *   - Net take rate     (bps)   = total_net_revenue / total_volume * 10000  (bank grain only)
 *   - Average ticket size       = total_volume / total_txns
 *   - DCC opt-in rate           = dcc_optin_volume / dcc_eligible_volume
 *   - DCC penetration           = dcc_eligible_volume / total_volume
 *   - Missed-DCC volume         = dcc_eligible_volume - dcc_optin_volume
 *
 * Data-sourcing (per project rules):
 *   - Rate/ticket metrics: sum_daily_bank when no dimensional filters are set
 *     (has interchange/scheme/VAT → net take rate is meaningful). When filters
 *     are set we fall back to sum_daily_insight (dimensional columns exist there;
 *     MSF & volume are real, so MSF-bps stays correct — but net-revenue columns
 *     are absent, so net take rate is returned as null in the filtered case).
 *   - DCC metrics: ALWAYS sum_daily_merchant — it is the only summary table
 *     carrying the dcc_* columns. Dimensional card-level filters (scheme/cardType/
 *     destination/channel) are not honoured for DCC because that grain has no such
 *     columns; merchant/store/partner/RM filters ARE honoured via dim joins.
 *
 * Additive & isolated: new controller, its own native queries, touches no existing
 * controller or repository. Read-only. Tenant is scoped on the base table and pushed
 * onto every dim join.
 */
@RestController
@RequestMapping("/api/business/revenue-kpis")
public class RevenueKpiController {

    @PersistenceContext
    private EntityManager entityManager;

    @PostMapping
    public ResponseEntity<Map<String, Object>> getRevenueKpis(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestBody(required = false) VolumeRevenueFilterDTO filter) {

        if (tenantId == null) tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new VolumeRevenueFilterDTO();

        LocalDate endDate = filter.getEndDate();
        LocalDate startDate = filter.getStartDate();
        // Default window: month-to-date of the latest available data. If no
        // endDate given, use max business_date present for the tenant (bank grain).
        if (endDate == null) {
            Object m = single(entityManager
                    .createNativeQuery("SELECT MAX(business_date) FROM sum_daily_bank WHERE tenant_id = :tid")
                    .setParameter("tid", tenantId));
            endDate = (m instanceof java.sql.Date) ? ((java.sql.Date) m).toLocalDate()
                    : (m instanceof LocalDate ? (LocalDate) m : LocalDate.now());
        }
        if (startDate == null) startDate = endDate.withDayOfMonth(1);
        if (startDate.isAfter(endDate)) startDate = endDate;

        boolean filtered = !isFilterEmpty(filter);

        Map<String, Object> response = new HashMap<>();
        response.put("startDate", startDate);
        response.put("endDate", endDate);
        response.put("filtersApplied", filtered);

        // ── Rate / ticket block ──
        BigDecimal volume, msf, txns, netRev = null;
        if (!filtered) {
            Object[] r = (Object[]) single(entityManager.createNativeQuery(
                    "SELECT COALESCE(SUM(total_volume),0), COALESCE(SUM(total_msf),0), " +
                    "       COALESCE(SUM(total_txns),0), COALESCE(SUM(total_net_revenue),0) " +
                    "FROM sum_daily_bank WHERE tenant_id = :tid AND business_date BETWEEN :s AND :e")
                    .setParameter("tid", tenantId).setParameter("s", startDate).setParameter("e", endDate));
            volume = bd(r[0]); msf = bd(r[1]); txns = bd(r[2]); netRev = bd(r[3]);
            response.put("rateSource", "sum_daily_bank");
        } else {
            Object[] r = (Object[]) single(buildInsightRateQuery(tenantId, startDate, endDate, filter));
            volume = bd(r[0]); msf = bd(r[1]); txns = bd(r[2]);
            response.put("rateSource", "sum_daily_insight");
        }

        response.put("totalVolume", volume);
        response.put("totalMsf", msf);
        response.put("totalTxns", txns.longValue());
        response.put("msfRateBps", bps(msf, volume));              // effective MSF rate
        response.put("avgTicket", ratio(volume, txns));            // average ticket size
        response.put("netTakeRateBps", filtered ? null : bps(netRev, volume)); // bank-grain only
        response.put("netRevenue", filtered ? null : netRev);

        // ── DCC block (sum_daily_merchant) ──
        Object[] d = (Object[]) single(buildDccQuery(tenantId, startDate, endDate, filter));
        BigDecimal dccEligibleVol = bd(d[0]);
        BigDecimal dccOptinVol    = bd(d[1]);
        BigDecimal dccOptoutVol   = bd(d[2]);
        BigDecimal dccTotalVol    = bd(d[3]);
        long dccEligibleCnt       = bd(d[4]).longValue();
        long dccOptinCnt          = bd(d[5]).longValue();

        BigDecimal missedDccVol = dccEligibleVol.subtract(dccOptinVol);
        if (missedDccVol.signum() < 0) missedDccVol = BigDecimal.ZERO;

        response.put("dccEligibleVolume", dccEligibleVol);
        response.put("dccOptinVolume",    dccOptinVol);
        response.put("dccOptoutVolume",   dccOptoutVol);
        response.put("dccMissedVolume",   missedDccVol);
        response.put("dccEligibleCount",  dccEligibleCnt);
        response.put("dccOptinCount",     dccOptinCnt);
        response.put("dccOptinRatePct",   pct(dccOptinVol, dccEligibleVol));   // opt-in of eligible
        response.put("dccPenetrationPct", pct(dccEligibleVol, dccTotalVol));   // eligible of total
        response.put("dccSourceBaseVolume", dccTotalVol);

        return ResponseEntity.ok(response);
    }

    /* Filtered rate metrics off sum_daily_insight (MSF & volume real; no net rev). */
    private Query buildInsightRateQuery(Long tid, LocalDate s, LocalDate e, VolumeRevenueFilterDTO f) {
        boolean needMerchant = listNonEmpty(f.getPartnerList()) || listNonEmpty(f.getRmList())
                || listNonEmpty(f.getTeamLeaderList())
                || (f.getMerchantName() != null && !f.getMerchantName().isBlank())
                || listNonEmpty(f.getMidList())
                || f.getOpenDateStart() != null || f.getOpenDateEnd() != null;
        boolean needStore = listNonEmpty(f.getMccList()) || listNonEmpty(f.getSidList())
                || listNonEmpty(f.getIndustryList());

        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(s.total_volume),0), COALESCE(SUM(s.total_msf),0), " +
                "       COALESCE(SUM(s.total_txns),0) FROM sum_daily_insight s ");
        if (needMerchant) sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore)    sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id ");
        sql.append("WHERE s.tenant_id = :tid AND s.business_date BETWEEN :s AND :e ");
        appendInsightFilters(sql, f);

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("tid", tid).setParameter("s", s).setParameter("e", e);
        bindInsightFilters(q, f);
        return q;
    }

    /* DCC metrics off sum_daily_merchant. Honours merchant/store/partner/RM/team
       filters; ignores card-level filters (no such columns at this grain). */
    private Query buildDccQuery(Long tid, LocalDate s, LocalDate e, VolumeRevenueFilterDTO f) {
        boolean needMerchant = listNonEmpty(f.getPartnerList()) || listNonEmpty(f.getRmList())
                || listNonEmpty(f.getTeamLeaderList())
                || (f.getMerchantName() != null && !f.getMerchantName().isBlank())
                || listNonEmpty(f.getMidList())
                || f.getOpenDateStart() != null || f.getOpenDateEnd() != null;
        boolean needStore = listNonEmpty(f.getMccList()) || listNonEmpty(f.getSidList())
                || listNonEmpty(f.getIndustryList());

        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(s.dcc_eligible_volume),0), COALESCE(SUM(s.dcc_optin_volume),0), " +
                "       COALESCE(SUM(s.dcc_optout_volume),0), " +
                "       COALESCE(SUM(COALESCE(s.total_base_volume, s.total_volume)),0), " +
                "       COALESCE(SUM(s.dcc_eligible_count),0), COALESCE(SUM(s.dcc_optin_count),0) " +
                "FROM sum_daily_merchant s ");
        if (needMerchant || needStore)
            sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        if (needStore)
            sql.append("LEFT JOIN dim_store st ON st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id ");
        sql.append("WHERE s.tenant_id = :tid AND s.business_date BETWEEN :s AND :e ");
        if (listNonEmpty(f.getPartnerList()))    sql.append("  AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(f.getRmList()))         sql.append("  AND m.sales_email IN (:rms) ");
        if (listNonEmpty(f.getTeamLeaderList())) sql.append("  AND m.sales_user_id IN (:teamLeaders) ");
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank())
                                                 sql.append("  AND m.name ILIKE :merchName ");
        if (listNonEmpty(f.getMidList()))        sql.append("  AND m.mid IN (:mids) ");
        if (f.getOpenDateStart() != null)        sql.append("  AND CAST(m.created_date AS DATE) >= :openStart ");
        if (f.getOpenDateEnd() != null)          sql.append("  AND CAST(m.created_date AS DATE) <= :openEnd ");
        if (listNonEmpty(f.getMccList()))        sql.append("  AND st.mcc IN (:mccs) ");
        if (listNonEmpty(f.getSidList()))        sql.append("  AND st.sid IN (:sids) ");
        if (listNonEmpty(f.getIndustryList()))   sql.append("  AND st.mcc IN (SELECT mcc FROM ref_mcc_category WHERE category IN (:industries)) ");

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("tid", tid).setParameter("s", s).setParameter("e", e);
        if (listNonEmpty(f.getPartnerList()))    q.setParameter("partners",    f.getPartnerList());
        if (listNonEmpty(f.getRmList()))         q.setParameter("rms",         f.getRmList());
        if (listNonEmpty(f.getTeamLeaderList())) q.setParameter("teamLeaders", f.getTeamLeaderList());
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank())
                                                 q.setParameter("merchName",   "%" + f.getMerchantName() + "%");
        if (listNonEmpty(f.getMidList()))        q.setParameter("mids",        f.getMidList());
        if (f.getOpenDateStart() != null)        q.setParameter("openStart",   f.getOpenDateStart());
        if (f.getOpenDateEnd() != null)          q.setParameter("openEnd",     f.getOpenDateEnd());
        if (listNonEmpty(f.getMccList()))        q.setParameter("mccs",        f.getMccList());
        if (listNonEmpty(f.getSidList()))        q.setParameter("sids",        f.getSidList());
        if (listNonEmpty(f.getIndustryList()))   q.setParameter("industries",  f.getIndustryList());
        return q;
    }

    private static void appendInsightFilters(StringBuilder sql, VolumeRevenueFilterDTO f) {
        if (listNonEmpty(f.getPartnerList()))    sql.append("  AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(f.getRmList()))         sql.append("  AND m.sales_email IN (:rms) ");
        if (listNonEmpty(f.getTeamLeaderList())) sql.append("  AND m.sales_user_id IN (:teamLeaders) ");
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank())
                                                 sql.append("  AND m.name ILIKE :merchName ");
        if (listNonEmpty(f.getMidList()))        sql.append("  AND m.mid IN (:mids) ");
        if (f.getOpenDateStart() != null)        sql.append("  AND CAST(m.created_date AS DATE) >= :openStart ");
        if (f.getOpenDateEnd() != null)          sql.append("  AND CAST(m.created_date AS DATE) <= :openEnd ");
        if (listNonEmpty(f.getMccList()))        sql.append("  AND st.mcc IN (:mccs) ");
        if (listNonEmpty(f.getSidList()))        sql.append("  AND st.sid IN (:sids) ");
        if (listNonEmpty(f.getIndustryList()))   sql.append("  AND st.mcc IN (SELECT mcc FROM ref_mcc_category WHERE category IN (:industries)) ");
        if (listNonEmpty(f.getSchemeList()))     sql.append("  AND s.card_scheme IN (:schemes) ");
        if (listNonEmpty(f.getCardTypeList()))   sql.append("  AND s.card_type IN (:cardTypes) ");
        if (listNonEmpty(f.getDestinationList()))sql.append("  AND s.destination IN (:destinations) ");
        if (listNonEmpty(f.getChannelList()))    sql.append("  AND s.channel IN (:channels) ");
    }

    private static void bindInsightFilters(Query q, VolumeRevenueFilterDTO f) {
        if (listNonEmpty(f.getPartnerList()))    q.setParameter("partners",     f.getPartnerList());
        if (listNonEmpty(f.getRmList()))         q.setParameter("rms",          f.getRmList());
        if (listNonEmpty(f.getTeamLeaderList())) q.setParameter("teamLeaders",  f.getTeamLeaderList());
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank())
                                                 q.setParameter("merchName",    "%" + f.getMerchantName() + "%");
        if (listNonEmpty(f.getMidList()))        q.setParameter("mids",         f.getMidList());
        if (f.getOpenDateStart() != null)        q.setParameter("openStart",    f.getOpenDateStart());
        if (f.getOpenDateEnd() != null)          q.setParameter("openEnd",      f.getOpenDateEnd());
        if (listNonEmpty(f.getMccList()))        q.setParameter("mccs",         f.getMccList());
        if (listNonEmpty(f.getSidList()))        q.setParameter("sids",         f.getSidList());
        if (listNonEmpty(f.getIndustryList()))   q.setParameter("industries",   f.getIndustryList());
        if (listNonEmpty(f.getSchemeList()))     q.setParameter("schemes",      f.getSchemeList());
        if (listNonEmpty(f.getCardTypeList()))   q.setParameter("cardTypes",    f.getCardTypeList());
        if (listNonEmpty(f.getDestinationList()))q.setParameter("destinations", f.getDestinationList());
        if (listNonEmpty(f.getChannelList()))    q.setParameter("channels",     f.getChannelList());
    }

    // ── helpers ──
    private static Object single(Query q) {
        List<?> l = q.getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }

    private static boolean isFilterEmpty(VolumeRevenueFilterDTO f) {
        return !listNonEmpty(f.getPartnerList()) && !listNonEmpty(f.getRmList())
                && !listNonEmpty(f.getTeamLeaderList()) && !listNonEmpty(f.getMidList())
                && !listNonEmpty(f.getSidList()) && !listNonEmpty(f.getMccList())
                && !listNonEmpty(f.getIndustryList())
                && f.getOpenDateStart() == null && f.getOpenDateEnd() == null
                && !listNonEmpty(f.getSchemeList()) && !listNonEmpty(f.getCardTypeList())
                && !listNonEmpty(f.getDestinationList()) && !listNonEmpty(f.getChannelList())
                && (f.getMerchantName() == null || f.getMerchantName().isBlank());
    }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        return new BigDecimal(o.toString());
    }

    /** basis points = numerator/denominator * 10000, 1 dp. Null-safe → 0 when denom 0. */
    private static BigDecimal bps(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() == 0) return BigDecimal.ZERO;
        return num.multiply(BigDecimal.valueOf(10000)).divide(den, 1, RoundingMode.HALF_UP);
    }

    /** percentage = numerator/denominator * 100, 1 dp. */
    private static BigDecimal pct(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() == 0) return BigDecimal.ZERO;
        return num.multiply(BigDecimal.valueOf(100)).divide(den, 1, RoundingMode.HALF_UP);
    }

    /** simple ratio, 2 dp. */
    private static BigDecimal ratio(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() == 0) return BigDecimal.ZERO;
        return num.divide(den, 2, RoundingMode.HALF_UP);
    }
}
