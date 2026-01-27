package com.acquira.controller;

import com.acquira.model.MerchantActivitySummary;
import com.acquira.model.MerchantOpportunityScore;
import com.acquira.repository.MerchantActivitySummaryRepository;
import com.acquira.repository.MerchantOpportunityScoreRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/business")
@CrossOrigin(origins = "http://localhost:5173")
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

        // 2. Lifecycle Stats
        @GetMapping("/lifecycle/summary")
        public ResponseEntity<Map<String, Long>> getLifecycleSummary(@RequestHeader("X-Tenant-Id") Long tenantId,
                        @RequestParam(required = false) LocalDate endDate) {

                LocalDate effectiveDate = endDate;
                if (effectiveDate == null) {
                        effectiveDate = activityRepository.findMaxCalcDate(tenantId);
                        if (effectiveDate == null)
                                effectiveDate = LocalDate.now();
                }

                Map<String, Long> summary = new HashMap<>();
                summary.put("ACTIVE",
                                activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ACTIVE",
                                                effectiveDate));
                summary.put("DORMANT",
                                activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "DORMANT",
                                                effectiveDate));
                summary.put("ONBOARDED",
                                activityRepository.countByTenantIdAndStatusAndCalcDate(tenantId, "ONBOARDED",
                                                effectiveDate));
                return ResponseEntity.ok(summary);
        }

        // 3. Zero Sales List
        @GetMapping("/zero-sales")
        public ResponseEntity<Page<MerchantActivitySummary>> getZeroSales(@RequestHeader("X-Tenant-Id") Long tenantId,
                        @RequestParam(defaultValue = "30") int days, Pageable pageable) {
                if (days == 7) {
                        return ResponseEntity.ok(activityRepository.findZeroSales7Days(tenantId, pageable));
                }
                return ResponseEntity.ok(activityRepository.findZeroSales30Days(tenantId, pageable));
        }

        // 4. Opportunities
        @GetMapping("/opportunity")
        public ResponseEntity<List<MerchantOpportunityScore>> getOpportunities(
                        @RequestHeader("X-Tenant-Id") Long tenantId) {
                return ResponseEntity.ok(opportunityRepository.findByTenantIdOrderByScoreDesc(tenantId));
        }

        // 5. Sales Trends (Mock for now, ideally queries sum_daily_bank)
        @GetMapping("/dashboard/trends/{mode}")
        public ResponseEntity<List<Map<String, Object>>> getSalesTrends(@RequestHeader("X-Tenant-Id") Long tenantId,
                        @PathVariable String mode,
                        @RequestParam(required = false) LocalDate startDate,
                        @RequestParam(required = false) LocalDate endDate) {

                LocalDate end = (endDate != null) ? endDate : LocalDate.now();
                LocalDate start = (startDate != null) ? startDate : end.minusDays(30);

                List<com.acquira.model.SumDailyBank> dailyStats = dailyBankRepository
                                .findByTenantIdAndBusinessDateBetweenOrderByBusinessDateAsc(tenantId, start, end);

                List<Map<String, Object>> result = dailyStats.stream().map(stats -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("date", stats.getBusinessDate());
                        map.put("count", stats.getTotalTxns());
                        map.put("value", stats.getTotalVolume());
                        return map;
                }).toList();

                return ResponseEntity.ok(result);
        }
}
