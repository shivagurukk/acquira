package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.SumDailyBank;
import com.acquira.common.repository.SumDailyBankRepository;
import com.acquira.common.repository.SumDailyMerchantRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    private final SumDailyBankRepository sumDailyBankRepository;
    private final SumDailyMerchantRepository sumDailyMerchantRepository;
    private final com.acquira.common.repository.MerchantRepository merchantRepository;
    private final com.acquira.common.repository.MerchantActivitySummaryRepository merchantActivitySummaryRepository;

    public AnalyticsService(SumDailyBankRepository sumDailyBankRepository,
            SumDailyMerchantRepository sumDailyMerchantRepository,
            com.acquira.common.repository.MerchantRepository merchantRepository,
            com.acquira.common.repository.MerchantActivitySummaryRepository merchantActivitySummaryRepository) {
        this.sumDailyBankRepository = sumDailyBankRepository;
        this.sumDailyMerchantRepository = sumDailyMerchantRepository;
        this.merchantRepository = merchantRepository;
        this.merchantActivitySummaryRepository = merchantActivitySummaryRepository;
    }

    public Map<String, Object> getExecutiveDashboard(LocalDate date) { // Date is usually "Today" or specific business
                                                                       // date
        if (date == null) {
            date = LocalDate.now();
        }

        Long tenantIdLong = TenantContext.getCurrentTenant();
        Long tenantId = tenantIdLong;

        if (tenantId == null) {
            throw new RuntimeException("No Tenant Context found");
        }

        Map<String, Object> response = new HashMap<>();

        // 1. Daily Snapshot (Current Day)
        // Since SumDailyBank is unique per day/tenant, list should have 0 or 1 item
        List<SumDailyBank> daily = sumDailyBankRepository.findByTenantIdAndBusinessDate(tenantId, date);
        SumDailyBank today = daily.isEmpty() ? new SumDailyBank() : daily.get(0);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("date", date);
        snapshot.put("totalVolume", today.getTotalVolume() != null ? today.getTotalVolume() : BigDecimal.ZERO);
        snapshot.put("totalTxns", today.getTotalTxns() != null ? today.getTotalTxns() : 0);
        snapshot.put("totalRevenue", today.getTotalNetRevenue() != null ? today.getTotalNetRevenue() : BigDecimal.ZERO);
        response.put("dailySnapshot", snapshot);

        // 2. Month To Date (MTD)
        LocalDate startOfMonth = date.withDayOfMonth(1);
        BigDecimal mtdVolume = sumDailyBankRepository.sumVolumeByTenantAndDateRange(tenantId, startOfMonth, date);
        BigDecimal mtdRevenue = sumDailyBankRepository.sumRevenueByTenantAndDateRange(tenantId, startOfMonth, date);
        Long mtdTxns = sumDailyBankRepository.sumTxnsByTenantAndDateRange(tenantId, startOfMonth, date);

        Map<String, Object> mtd = new HashMap<>();
        mtd.put("totalVolume", mtdVolume != null ? mtdVolume : BigDecimal.ZERO);
        mtd.put("totalTxns", mtdTxns != null ? mtdTxns : 0);
        mtd.put("totalRevenue", mtdRevenue != null ? mtdRevenue : BigDecimal.ZERO);
        response.put("mtdSnapshot", mtd);

        // 3. Trend (Last 30 Days)
        LocalDate thirtyDaysAgo = date.minusDays(30);
        List<SumDailyBank> trends = sumDailyBankRepository
                .findByTenantIdAndBusinessDateBetweenOrderByBusinessDateAsc(tenantId, thirtyDaysAgo, date);
        response.put("trends", trends);

        // 4. Active Merchants
        long activeMerchants = merchantRepository.countByTenantIdAndStatus(tenantId, "ACTIVE");
        response.put("activeMerchants", activeMerchants);

        // 5. Year-over-Year MTD volume (same month-to-date window, one year back).
        // Frontend derives the YoY % from mtdVolume vs this value. Reuses the existing
        // tenant-scoped range-sum query — no new repository method needed.
        LocalDate lastYearMonthStart = startOfMonth.minusYears(1);
        LocalDate lastYearAsOf = date.minusYears(1);
        BigDecimal mtdVolumeLastYear =
                sumDailyBankRepository.sumVolumeByTenantAndDateRange(tenantId, lastYearMonthStart, lastYearAsOf);
        response.put("mtdVolumeLastYear", mtdVolumeLastYear != null ? mtdVolumeLastYear : BigDecimal.ZERO);

        // 6. Active/Dormant merchant counts from merchant_activity_summary at its latest
        // snapshot date. This is a richer lifecycle signal than dim_merchant.status
        // (which is a static onboarding flag). Falls back to 0 when no snapshot exists
        // (e.g. calcBusinessMetricsStep hasn't run yet) rather than throwing.
        try {
            LocalDate latestCalc = merchantActivitySummaryRepository.findMaxCalcDate(tenantId);
            if (latestCalc != null) {
                long activeSnap = merchantActivitySummaryRepository
                        .countByTenantIdAndStatusAndCalcDate(tenantId, "ACTIVE", latestCalc);
                long dormantSnap = merchantActivitySummaryRepository
                        .countByTenantIdAndStatusAndCalcDate(tenantId, "DORMANT", latestCalc);
                response.put("activeMerchantsSnapshot", activeSnap);
                response.put("dormantMerchants", dormantSnap);
                response.put("activitySnapshotDate", latestCalc);
            } else {
                response.put("activeMerchantsSnapshot", 0L);
                response.put("dormantMerchants", 0L);
            }
        } catch (Exception e) {
            // Defensive: never let a lifecycle-count issue break the whole dashboard.
            response.put("activeMerchantsSnapshot", 0L);
            response.put("dormantMerchants", 0L);
        }

        return response;
    }
}
