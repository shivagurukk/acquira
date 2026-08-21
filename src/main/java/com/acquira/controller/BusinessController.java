package com.acquira.controller;

import com.acquira.model.MerchantOpportunityScore;
import com.acquira.repository.MerchantActivitySummaryRepository;
import com.acquira.repository.MerchantOpportunityScoreRepository;
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
        private final com.acquira.repository.SumDailyBankRepository dailyBankRepository;

        public BusinessController(MerchantActivitySummaryRepository activityRepository,
                        MerchantOpportunityScoreRepository opportunityRepository,
                        com.acquira.repository.SumDailyBankRepository dailyBankRepository) {
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

        // 4. Opportunities
        @GetMapping("/opportunity")
        public ResponseEntity<List<MerchantOpportunityScore>> getOpportunities(
                        @RequestHeader("X-Tenant-Id") Long tenantId) {
                return ResponseEntity.ok(opportunityRepository.findByTenantIdOrderByScoreDesc(tenantId));
        }

        // Methods removed: getLifecycleSummary, getZeroSales, getSalesTrends (Feature
        // Cleanup)
}
