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
         * The previous GET endpoint silently dropped every drawer field except
         * startDate/endDate — i.e. the filter UI did nothing.
         *
         * Implementation:
         *  - Volume / count metrics are computed against sum_daily_insight (which
         *    has all the dimensional columns we need) joined to dim_merchant /
         *    dim_store as needed.
         *  - Merchant counts (active / dormant / new / zero-sales) are derived
         *    from the same filtered set: a merchant is "active" if it has volume
         *    in the date range, "dormant" if it had volume in the previous period
         *    but not now, "new" if its created_date falls in the range,
         *    "zero-sales" if it's in the filtered universe but had no volume.
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

                // Build the filtered sum_daily_insight aggregation. Volume/count come
                // from this single query — cheaper than 3 separate queries.
                Map<String, BigDecimal> volByPeriod = new HashMap<>();
                Map<String, Long>       cntByPeriod = new HashMap<>();
                volByPeriod.put("daily", BigDecimal.ZERO); cntByPeriod.put("daily", 0L);
                volByPeriod.put("mtd",   BigDecimal.ZERO); cntByPeriod.put("mtd",   0L);
                volByPeriod.put("ytd",   BigDecimal.ZERO); cntByPeriod.put("ytd",   0L);

                StringBuilder sql = new StringBuilder();
                sql.append("SELECT ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :dailyStart AND :endDate THEN s.total_volume ELSE 0 END) AS daily_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :dailyStart AND :endDate THEN s.total_txns   ELSE 0 END) AS daily_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :mtdStart   AND :endDate THEN s.total_volume ELSE 0 END) AS mtd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :mtdStart   AND :endDate THEN s.total_txns   ELSE 0 END) AS mtd_cnt, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :ytdStart   AND :endDate THEN s.total_volume ELSE 0 END) AS ytd_vol, ");
                sql.append("  SUM(CASE WHEN s.business_date BETWEEN :ytdStart   AND :endDate THEN s.total_txns   ELSE 0 END) AS ytd_cnt ");
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
                sql.append("  AND s.business_date <= :endDate ");
                sql.append("  AND s.business_date >= :ytdStart ");
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
                q.setParameter("tid",        tenantId);
                q.setParameter("dailyStart", dailyStart);
                q.setParameter("mtdStart",   mtdStart);
                q.setParameter("ytdStart",   ytdStart);
                q.setParameter("endDate",    effectiveDate);
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

                Object[] row = (Object[]) q.getSingleResult();
                BigDecimal dailyVol = toBigDecimal(row[0]);
                long       dailyCnt = toLong(row[1]);
                BigDecimal mtdVol   = toBigDecimal(row[2]);
                long       mtdCnt   = toLong(row[3]);
                BigDecimal ytdVol   = toBigDecimal(row[4]);
                long       ytdCnt   = toLong(row[5]);

                // Merchant counts — distinct merchants in the filtered universe.
                // For "active" we count merchants with daily-window volume.
                // For "dormant" / "new" / "zero" we use activity_summary as before
                // (those signals are tenant-wide, not filter-scoped). When filters
                // are set, those numbers may exceed filtered volume universe — we
                // accept that approximation and document it in the response.
                String activeSql =
                                "SELECT COUNT(DISTINCT s.merchant_id) FROM sum_daily_insight s " +
                                (needMerchant ? "JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id " : "") +
                                (needStore    ? "LEFT JOIN dim_store st ON s.store_id = st.store_id AND st.tenant_id = s.tenant_id " : "") +
                                "WHERE s.tenant_id = :tid " +
                                "  AND s.business_date BETWEEN :dailyStart AND :endDate " +
                                "  AND s.total_volume > 0 " +
                                (listNonEmpty(filter.getPartnerList())    ? "  AND m.referral_partner IN (:partners) " : "") +
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
                jakarta.persistence.Query aq = entityManager.createNativeQuery(activeSql);
                aq.setParameter("tid", tenantId);
                aq.setParameter("dailyStart", dailyStart);
                aq.setParameter("endDate", effectiveDate);
                if (listNonEmpty(filter.getPartnerList()))    aq.setParameter("partners",     filter.getPartnerList());
                if (listNonEmpty(filter.getRmList()))         aq.setParameter("rms",          filter.getRmList());
                if (listNonEmpty(filter.getTeamLeaderList())) aq.setParameter("teamLeaders",  filter.getTeamLeaderList());
                if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
                                                              aq.setParameter("merchName",    "%" + filter.getMerchantName() + "%");
                if (listNonEmpty(filter.getMidList()))        aq.setParameter("mids",         filter.getMidList());
                if (listNonEmpty(filter.getMccList()))        aq.setParameter("mccs",         filter.getMccList());
                if (listNonEmpty(filter.getSidList()))        aq.setParameter("sids",         filter.getSidList());
                if (listNonEmpty(filter.getSchemeList()))     aq.setParameter("schemes",      filter.getSchemeList());
                if (listNonEmpty(filter.getCardTypeList()))   aq.setParameter("cardTypes",    filter.getCardTypeList());
                if (listNonEmpty(filter.getDestinationList()))aq.setParameter("destinations", filter.getDestinationList());
                if (listNonEmpty(filter.getChannelList()))    aq.setParameter("channels",     filter.getChannelList());
                long activeCount = ((Number) aq.getSingleResult()).longValue();

                // Dormant / new / zero — keep tenant-wide for now. When filters are
                // set these numbers represent the tenant-wide universe, not the
                // filtered slice, and the frontend can label them accordingly.
                long dormantCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "DORMANT", effectiveDate);
                long onboardedCount = activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ONBOARDED", effectiveDate);

                Map<String, Object> response = new HashMap<>();
                response.put("dailyVolume",       dailyVol);
                response.put("dailyCount",        dailyCnt);
                response.put("mtdVolume",         mtdVol);
                response.put("mtdCount",          mtdCnt);
                response.put("ytdVolume",         ytdVol);
                response.put("ytdCount",          ytdCnt);
                response.put("transactionCount",  mtdCnt);
                response.put("transactionValue",  mtdVol);
                response.put("activeMerchants",   activeCount);
                response.put("newMerchants",      onboardedCount);
                response.put("dormantMerchants",  dormantCount);
                response.put("zeroSalesMerchants", 0);
                response.put("effectiveDate",     effectiveDate);
                response.put("filtersApplied",    !isFilterEmpty(filter));

                return ResponseEntity.ok(response);
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
