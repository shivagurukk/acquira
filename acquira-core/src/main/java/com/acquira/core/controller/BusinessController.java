package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.model.MerchantOpportunityScore;
import com.acquira.common.repository.MerchantActivitySummaryRepository;
import com.acquira.common.repository.MerchantOpportunityScoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/business")
public class BusinessController {

        private final MerchantActivitySummaryRepository activityRepository;
        private final MerchantOpportunityScoreRepository opportunityRepository;
        private final com.acquira.common.repository.SumDailyBankRepository dailyBankRepository;

        @PersistenceContext
        private EntityManager entityManager;

        public BusinessController(MerchantActivitySummaryRepository activityRepository,
                        MerchantOpportunityScoreRepository opportunityRepository,
                        com.acquira.common.repository.SumDailyBankRepository dailyBankRepository) {
                this.activityRepository = activityRepository;
                this.opportunityRepository = opportunityRepository;
                this.dailyBankRepository = dailyBankRepository;
        }

        // 1. Dashboard KPIs (Simplistic aggregation for now)
        @GetMapping("/dashboard/kpis")
        public ResponseEntity<Map<String, Object>> getDashboardKpis(@RequestHeader("X-Tenant-Id") Long tenantId,
                        @RequestParam(required = false) LocalDate endDate) {

                // Determine effective date: User provided OR Max available
                LocalDate effectiveDate = endDate;
                if (effectiveDate == null) {
                        effectiveDate = activityRepository.findMaxCalcDate(tenantId);
                        if (effectiveDate == null)
                                effectiveDate = LocalDate.now(); // Fallback if no data
                }

                // 1. Merchant Counts (Snapshot at effectiveDate)
                long activeCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ACTIVE",
                                effectiveDate);
                long dormantCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "DORMANT",
                                effectiveDate);
                long onboardedCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ONBOARDED",
                                effectiveDate);

                // 2. Transaction Metrics (Daily, MTD, YTD)
                // Daily
                java.math.BigDecimal dailyVol = dailyBankRepository.sumVolumeByTenantAndDateRange(tenantId,
                                effectiveDate,
                                effectiveDate);
                Long dailyCnt = dailyBankRepository.sumTxnsByTenantAndDateRange(tenantId, effectiveDate, effectiveDate);

                // MTD
                LocalDate mtdStart = effectiveDate.withDayOfMonth(1);
                java.math.BigDecimal mtdVol = dailyBankRepository.sumVolumeByTenantAndDateRange(tenantId, mtdStart,
                                effectiveDate);
                Long mtdCnt = dailyBankRepository.sumTxnsByTenantAndDateRange(tenantId, mtdStart, effectiveDate);

                // YTD
                LocalDate ytdStart = effectiveDate.withDayOfYear(1);
                java.math.BigDecimal ytdVol = dailyBankRepository.sumVolumeByTenantAndDateRange(tenantId, ytdStart,
                                effectiveDate);
                Long ytdCnt = dailyBankRepository.sumTxnsByTenantAndDateRange(tenantId, ytdStart, effectiveDate);

                Map<String, Object> response = new HashMap<>();

                // Structure for Frontend
                response.put("dailyVolume", dailyVol != null ? dailyVol : java.math.BigDecimal.ZERO);
                response.put("dailyCount", dailyCnt != null ? dailyCnt : 0);

                response.put("mtdVolume", mtdVol != null ? mtdVol : java.math.BigDecimal.ZERO);
                response.put("mtdCount", mtdCnt != null ? mtdCnt : 0);

                response.put("ytdVolume", ytdVol != null ? ytdVol : java.math.BigDecimal.ZERO);
                response.put("ytdCount", ytdCnt != null ? ytdCnt : 0);

                // Backward compat / Summary tiles
                response.put("transactionCount", mtdCnt != null ? mtdCnt : 0); // Default to MTD for main tile
                response.put("transactionValue", mtdVol != null ? mtdVol : java.math.BigDecimal.ZERO);

                response.put("activeMerchants", activeCount);
                response.put("newMerchants", onboardedCount);
                response.put("dormantMerchants", dormantCount);
                response.put("zeroSalesMerchants", 0);
                response.put("effectiveDate", effectiveDate);

                return ResponseEntity.ok(response);
        }

        /**
         * Filtered dashboard KPIs. Same response shape as GET /dashboard/kpis but
         * accepts the full VolumeRevenueFilterDTO body so the BusinessFilters drawer
         * (partner / RM / MCC / team-leader / merchant-name / MID / SID / scheme /
         * card-type / destination / channel) actually narrows the numbers.
         *
         * Enhancements over the original:
         *  - Period-over-period comparison windows computed in the SAME single
         *    scan (extra CASE branches, no extra query): prev daily window
         *    (same-length window immediately preceding), prev MTD pace (prior
         *    month, day 1 → same day-of-month), prev YTD (prior year, Jan 1 →
         *    same day-of-year). Returned as prev* keys so the UI can render
         *    delta chips.
         *  - The scan lower bound is LEAST(prevDailyStart, prevMtdStart,
         *    prevYtdStart) instead of the old hard `>= ytdStart` clamp, which
         *    silently truncated custom startDate ranges that began before Jan 1.
         *  - zeroSalesMerchants is now real: merchants in the filtered
         *    dim_merchant universe with NO volume in the daily window. Card-level
         *    filters (scheme/cardType/destination/channel) are intentionally not
         *    applied to the zero-sales universe — a merchant with zero rows has
         *    no card dimensions to filter on.
         */
        @PostMapping("/dashboard/kpis-filtered")
        public ResponseEntity<Map<String, Object>> getDashboardKpisFiltered(
                        @RequestHeader("X-Tenant-Id") Long tenantId,
                        @RequestBody(required = false) VolumeRevenueFilterDTO filter) {

                if (filter == null) filter = new VolumeRevenueFilterDTO();

                // Effective date: end of range if specified, else max in data, else today.
                LocalDate effectiveDate = filter.getEndDate();
                if (effectiveDate == null) {
                        effectiveDate = activityRepository.findMaxCalcDate(tenantId);
                        if (effectiveDate == null) effectiveDate = LocalDate.now();
                }
                LocalDate startDate = filter.getStartDate();
                LocalDate mtdStart = effectiveDate.withDayOfMonth(1);
                LocalDate ytdStart = effectiveDate.withDayOfYear(1);
                LocalDate dailyStart = (startDate != null) ? startDate : effectiveDate;
                if (dailyStart.isAfter(effectiveDate)) dailyStart = effectiveDate;

                // Comparison windows.
                long dailyLen = ChronoUnit.DAYS.between(dailyStart, effectiveDate) + 1;
                LocalDate prevDailyEnd   = dailyStart.minusDays(1);
                LocalDate prevDailyStart = prevDailyEnd.minusDays(dailyLen - 1);
                LocalDate prevMtdStart   = mtdStart.minusMonths(1);
                LocalDate prevMtdEnd     = effectiveDate.minusMonths(1);
                LocalDate prevYtdStart   = ytdStart.minusYears(1);
                LocalDate prevYtdEnd     = effectiveDate.minusYears(1);

                // Scan lower bound: earliest of every window we aggregate over.
                // (Fixes the old `>= ytdStart` clamp that truncated custom ranges
                // starting before Jan 1.)
                LocalDate scanStart = prevYtdStart;
                if (prevDailyStart.isBefore(scanStart)) scanStart = prevDailyStart;
                if (prevMtdStart.isBefore(scanStart))   scanStart = prevMtdStart;

                StringBuilder sql = new StringBuilder();
                sql.append("SELECT ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :dailyStart AND :endDate THEN s.total_volume ELSE 0 END) AS daily_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :dailyStart AND :endDate THEN s.total_txns   ELSE 0 END) AS daily_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :mtdStart   AND :endDate THEN s.total_volume ELSE 0 END) AS mtd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :mtdStart   AND :endDate THEN s.total_txns   ELSE 0 END) AS mtd_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :ytdStart   AND :endDate THEN s.total_volume ELSE 0 END) AS ytd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :ytdStart   AND :endDate THEN s.total_txns   ELSE 0 END) AS ytd_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevDailyStart AND :prevDailyEnd THEN s.total_volume ELSE 0 END) AS prev_daily_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevDailyStart AND :prevDailyEnd THEN s.total_txns   ELSE 0 END) AS prev_daily_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevMtdStart   AND :prevMtdEnd   THEN s.total_volume ELSE 0 END) AS prev_mtd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevMtdStart   AND :prevMtdEnd   THEN s.total_txns   ELSE 0 END) AS prev_mtd_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevYtdStart   AND :prevYtdEnd   THEN s.total_volume ELSE 0 END) AS prev_ytd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :prevYtdStart   AND :prevYtdEnd   THEN s.total_txns   ELSE 0 END) AS prev_ytd_cnt ");
                sql.append("FROM sum_daily_insight s ");

                boolean needMerchant =
                                listNonEmpty(filter.getPartnerList())   ||
                                listNonEmpty(filter.getRmList())        ||
                                listNonEmpty(filter.getTeamLeaderList())||
                                (filter.getMerchantName() != null && !filter.getMerchantName().isBlank()) ||
                                listNonEmpty(filter.getMidList());
                boolean needStore =
                                listNonEmpty(filter.getMccList()) ||
                                listNonEmpty(filter.getSidList());

                if (needMerchant) sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id ");
                if (needStore)    sql.append("LEFT JOIN dim_store st ON s.store_id = st.store_id ");

                sql.append("WHERE s.tenant_id = :tid ");
                sql.append("  AND s.business_date BETWEEN :scanStart AND :endDate ");
                if (needMerchant) sql.append("  AND m.tenant_id = :tid ");
                if (needStore)    sql.append("  AND st.tenant_id = :tid ");

                if (listNonEmpty(filter.getPartnerList()))    sql.append("  AND m.referral_partner IN (:partners) ");
                if (listNonEmpty(filter.getRmList()))         sql.append("  AND m.sales_email IN (:rms) ");
                if (listNonEmpty(filter.getTeamLeaderList())) sql.append("  AND m.sales_user_id IN (:teamLeaders) ");
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              sql.append("  AND m.name ILIKE :merchName ");
                if (listNonEmpty(filter.getMidList()))        sql.append("  AND m.mid IN (:mids) ");
                if (listNonEmpty(filter.getMccList()))        sql.append("  AND st.mcc IN (:mccs) ");
                if (listNonEmpty(filter.getSidList()))        sql.append("  AND st.sid IN (:sids) ");
                if (listNonEmpty(filter.getSchemeList()))     sql.append("  AND s.card_scheme IN (:schemes) ");
                if (listNonEmpty(filter.getCardTypeList()))   sql.append("  AND s.card_type IN (:cardTypes) ");
                if (listNonEmpty(filter.getDestinationList()))sql.append("  AND s.destination IN (:destinations) ");
                if (listNonEmpty(filter.getChannelList()))    sql.append("  AND s.channel IN (:channels) ");

                jakarta.persistence.Query q = entityManager.createNativeQuery(sql.toString());
                q.setParameter("tid",            tenantId);
                q.setParameter("dailyStart",     dailyStart);
                q.setParameter("mtdStart",       mtdStart);
                q.setParameter("ytdStart",       ytdStart);
                q.setParameter("endDate",        effectiveDate);
                q.setParameter("scanStart",      scanStart);
                q.setParameter("prevDailyStart", prevDailyStart);
                q.setParameter("prevDailyEnd",   prevDailyEnd);
                q.setParameter("prevMtdStart",   prevMtdStart);
                q.setParameter("prevMtdEnd",     prevMtdEnd);
                q.setParameter("prevYtdStart",   prevYtdStart);
                q.setParameter("prevYtdEnd",     prevYtdEnd);
                bindFilterParams(q, filter);

                Object[] row = (Object[]) q.getSingleResult();
                BigDecimal dailyVol     = toBigDecimal(row[0]);
                long       dailyCnt     = toLong(row[1]);
                BigDecimal mtdVol       = toBigDecimal(row[2]);
                long       mtdCnt       = toLong(row[3]);
                BigDecimal ytdVol       = toBigDecimal(row[4]);
                long       ytdCnt       = toLong(row[5]);
                BigDecimal prevDailyVol = toBigDecimal(row[6]);
                long       prevDailyCnt = toLong(row[7]);
                BigDecimal prevMtdVol   = toBigDecimal(row[8]);
                long       prevMtdCnt   = toLong(row[9]);
                BigDecimal prevYtdVol   = toBigDecimal(row[10]);
                long       prevYtdCnt   = toLong(row[11]);

                // Merchant counts — distinct merchants in the filtered universe.
                // For "active" we count merchants with daily-window volume.
                String activeSql =
                                "SELECT COUNT(DISTINCT s.merchant_id) FROM sum_daily_insight s " +
                                (needMerchant ? "JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id " : "") +
                                (needStore    ? "LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id " : "") +
                                "WHERE s.tenant_id = :tid " +
                                "  AND s.business_date BETWEEN :dailyStart AND :endDate " +
                                "  AND s.total_volume > 0 " +
                                filterFragment(filter);
                jakarta.persistence.Query aq = entityManager.createNativeQuery(activeSql);
                aq.setParameter("tid", tenantId);
                aq.setParameter("dailyStart", dailyStart);
                aq.setParameter("endDate", effectiveDate);
                bindFilterParams(aq, filter);
                long activeCount = ((Number) aq.getSingleResult()).longValue();

                // Zero-sales — merchants in the filtered dim_merchant universe with
                // no volume rows in the daily window. Store-level filters applied via
                // EXISTS on dim_store; card-level filters are not applicable (a
                // merchant with zero insight rows has no card dimensions).
                StringBuilder zsql = new StringBuilder();
                zsql.append("SELECT COUNT(*) FROM dim_merchant m ");
                zsql.append("WHERE m.tenant_id = :tid ");
                if (listNonEmpty(filter.getPartnerList()))    zsql.append("  AND m.referral_partner IN (:partners) ");
                if (listNonEmpty(filter.getRmList()))         zsql.append("  AND m.sales_email IN (:rms) ");
                if (listNonEmpty(filter.getTeamLeaderList())) zsql.append("  AND m.sales_user_id IN (:teamLeaders) ");
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              zsql.append("  AND m.name ILIKE :merchName ");
                if (listNonEmpty(filter.getMidList()))        zsql.append("  AND m.mid IN (:mids) ");
                if (listNonEmpty(filter.getMccList()) || listNonEmpty(filter.getSidList())) {
                        zsql.append("  AND EXISTS (SELECT 1 FROM dim_store st ");
                        zsql.append("       WHERE st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id ");
                        if (listNonEmpty(filter.getMccList())) zsql.append("       AND st.mcc IN (:mccs) ");
                        if (listNonEmpty(filter.getSidList())) zsql.append("       AND st.sid IN (:sids) ");
                        zsql.append("  ) ");
                }
                zsql.append("  AND NOT EXISTS (SELECT 1 FROM sum_daily_insight s ");
                zsql.append("       WHERE s.tenant_id = m.tenant_id AND s.merchant_id = m.merchant_id ");
                zsql.append("         AND s.business_date BETWEEN :dailyStart AND :endDate ");
                zsql.append("         AND s.total_volume > 0) ");

                jakarta.persistence.Query zq = entityManager.createNativeQuery(zsql.toString());
                zq.setParameter("tid", tenantId);
                zq.setParameter("dailyStart", dailyStart);
                zq.setParameter("endDate", effectiveDate);
                if (listNonEmpty(filter.getPartnerList()))    zq.setParameter("partners",    filter.getPartnerList());
                if (listNonEmpty(filter.getRmList()))         zq.setParameter("rms",         filter.getRmList());
                if (listNonEmpty(filter.getTeamLeaderList())) zq.setParameter("teamLeaders", filter.getTeamLeaderList());
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              zq.setParameter("merchName",   "%" + filter.getMerchantName() + "%");
                if (listNonEmpty(filter.getMidList()))        zq.setParameter("mids",        filter.getMidList());
                if (listNonEmpty(filter.getMccList()))        zq.setParameter("mccs",        filter.getMccList());
                if (listNonEmpty(filter.getSidList()))        zq.setParameter("sids",        filter.getSidList());
                long zeroSalesCount = ((Number) zq.getSingleResult()).longValue();

                // Dormant / new — kept tenant-wide (activity snapshots aren't
                // filter-scoped). The frontend badges these tiles as tenant-wide
                // whenever filtersApplied is true.
                long dormantCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "DORMANT", effectiveDate);
                long onboardedCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ONBOARDED", effectiveDate);

                Map<String, Object> response = new HashMap<>();
                response.put("dailyVolume",       dailyVol);
                response.put("dailyCount",        dailyCnt);
                response.put("mtdVolume",         mtdVol);
                response.put("mtdCount",          mtdCnt);
                response.put("ytdVolume",         ytdVol);
                response.put("ytdCount",          ytdCnt);
                response.put("prevDailyVolume",   prevDailyVol);
                response.put("prevDailyCount",    prevDailyCnt);
                response.put("prevMtdVolume",     prevMtdVol);
                response.put("prevMtdCount",      prevMtdCnt);
                response.put("prevYtdVolume",     prevYtdVol);
                response.put("prevYtdCount",      prevYtdCnt);
                response.put("transactionCount",  mtdCnt);
                response.put("transactionValue",  mtdVol);
                response.put("activeMerchants",   activeCount);
                response.put("newMerchants",      onboardedCount);
                response.put("dormantMerchants",  dormantCount);
                response.put("zeroSalesMerchants", zeroSalesCount);
                response.put("effectiveDate",     effectiveDate);
                response.put("filtersApplied",    !isFilterEmpty(filter));

                return ResponseEntity.ok(response);
        }

        /** WHERE-fragment for the filterable columns (used by the active-count query). */
        private static String filterFragment(VolumeRevenueFilterDTO filter) {
                return  (listNonEmpty(filter.getPartnerList())    ? "  AND m.referral_partner IN (:partners) " : "") +
                        (listNonEmpty(filter.getRmList())         ? "  AND m.sales_email IN (:rms) " : "") +
                        (listNonEmpty(filter.getTeamLeaderList()) ? "  AND m.sales_user_id IN (:teamLeaders) " : "") +
                        ((filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                                  ? "  AND m.name ILIKE :merchName " : "") +
                        (listNonEmpty(filter.getMidList())        ? "  AND m.mid IN (:mids) " : "") +
                        (listNonEmpty(filter.getMccList())        ? "  AND st.mcc IN (:mccs) " : "") +
                        (listNonEmpty(filter.getSidList())        ? "  AND st.sid IN (:sids) " : "") +
                        (listNonEmpty(filter.getSchemeList())     ? "  AND s.card_scheme IN (:schemes) " : "") +
                        (listNonEmpty(filter.getCardTypeList())   ? "  AND s.card_type IN (:cardTypes) " : "") +
                        (listNonEmpty(filter.getDestinationList())? "  AND s.destination IN (:destinations) " : "") +
                        (listNonEmpty(filter.getChannelList())    ? "  AND s.channel IN (:channels) " : "");
        }

        /** Bind every filter parameter that filterFragment / the main query emitted. */
        private static void bindFilterParams(jakarta.persistence.Query q, VolumeRevenueFilterDTO filter) {
                if (listNonEmpty(filter.getPartnerList()))    q.setParameter("partners",     filter.getPartnerList());
                if (listNonEmpty(filter.getRmList()))         q.setParameter("rms",          filter.getRmList());
                if (listNonEmpty(filter.getTeamLeaderList())) q.setParameter("teamLeaders",  filter.getTeamLeaderList());
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              q.setParameter("merchName",    "%" + filter.getMerchantName() + "%");
                if (listNonEmpty(filter.getMidList()))        q.setParameter("mids",         filter.getMidList());
                if (listNonEmpty(filter.getMccList()))        q.setParameter("mccs",         filter.getMccList());
                if (listNonEmpty(filter.getSidList()))        q.setParameter("sids",         filter.getSidList());
                if (listNonEmpty(filter.getSchemeList()))     q.setParameter("schemes",      filter.getSchemeList());
                if (listNonEmpty(filter.getCardTypeList()))   q.setParameter("cardTypes",    filter.getCardTypeList());
                if (listNonEmpty(filter.getDestinationList()))q.setParameter("destinations", filter.getDestinationList());
                if (listNonEmpty(filter.getChannelList()))    q.setParameter("channels",     filter.getChannelList());
        }

        private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }

        private static boolean isFilterEmpty(VolumeRevenueFilterDTO f) {
                return !listNonEmpty(f.getPartnerList()) && !listNonEmpty(f.getRmList())
                                && !listNonEmpty(f.getTeamLeaderList()) && !listNonEmpty(f.getMidList())
                                && !listNonEmpty(f.getSidList()) && !listNonEmpty(f.getMccList())
                                && !listNonEmpty(f.getSchemeList()) && !listNonEmpty(f.getCardTypeList())
                                && !listNonEmpty(f.getDestinationList()) && !listNonEmpty(f.getChannelList())
                                && (f.getMerchantName() == null || f.getMerchantName().isBlank());
        }

        private static BigDecimal toBigDecimal(Object o) {
                if (o == null) return BigDecimal.ZERO;
                if (o instanceof BigDecimal) return (BigDecimal) o;
                return new BigDecimal(o.toString());
        }

        private static long toLong(Object o) {
                if (o == null) return 0L;
                if (o instanceof Number) return ((Number) o).longValue();
                return Long.parseLong(o.toString());
        }

        // 4. Opportunities
        @GetMapping("/opportunity")
        public ResponseEntity<List<MerchantOpportunityScore>> getOpportunities(
                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
                // Fall back to the thread-local tenant context if the header is
                // absent, instead of returning HTTP 400 (which the frontend showed
                // as a silent empty screen).
                if (tenantId == null) {
                        tenantId = com.acquira.common.config.TenantContext.getCurrentTenant();
                }
                if (tenantId == null) {
                        return ResponseEntity.status(403).build();
                }
                // Use findLatestByTenant: ONE row per merchant (most recent
                // calc_date), not every historical dated snapshot. The old
                // findByTenantIdOrderByScoreDesc returned ~(merchants x dates)
                // rows — hundreds of thousands — which both duplicated every
                // merchant and produced a response large enough to hang the grid.
                return ResponseEntity.ok(opportunityRepository.findLatestByTenant(tenantId));
        }

        // Methods removed: getLifecycleSummary, getZeroSales, getSalesTrends (Feature
        // Cleanup)
}
