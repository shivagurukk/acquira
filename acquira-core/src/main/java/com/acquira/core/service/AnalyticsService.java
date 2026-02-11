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

    public AnalyticsService(SumDailyBankRepository sumDailyBankRepository,
            SumDailyMerchantRepository sumDailyMerchantRepository,
            com.acquira.common.repository.MerchantRepository merchantRepository) {
        this.sumDailyBankRepository = sumDailyBankRepository;
        this.sumDailyMerchantRepository = sumDailyMerchantRepository;
        this.merchantRepository = merchantRepository;
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

        return response;
    }
}
