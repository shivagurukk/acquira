package com.acquira.service;

import com.acquira.dto.DailyMerchantDashboardDTO;
import com.acquira.model.SumDailyMerchant;
import com.acquira.model.SumMonthlyMerchantMetrics;
import com.acquira.repository.SumDailyMerchantRepository;
import com.acquira.repository.SumMonthlyMerchantMetricsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantDashboardService {

    @Autowired
    private SumDailyMerchantRepository dailyRepo;

    @Autowired
    private SumMonthlyMerchantMetricsRepository monthlyMetricsRepo;

    @Autowired
    private com.acquira.repository.MerchantRepository merchantRepo;

    @Transactional(readOnly = true)
    public List<DailyMerchantDashboardDTO> getDailyDashboard(Integer tenantId, int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate startDate = ym.atDay(1);
        LocalDate endDate = ym.atEndOfMonth();
        String monthYearKey = ym.toString(); // YYYY-MM
        List<DailyMerchantDashboardDTO> dashboard = new ArrayList<>();

        // 1. Fetch Daily Data
        List<SumDailyMerchant> dailyData = dailyRepo.findByTenantIdAndDateRange(tenantId, startDate, endDate);

        // 2. Fetch Pre-calculated Metrics (if they exist)
        // Optimization: Fetch all for month at once? Repo method needed.
        // For now, let's group daily data and fetch metrics per merchant (or optimize
        // later)

        Map<Long, List<SumDailyMerchant>> groupedByMerchant = dailyData.stream()
                .collect(Collectors.groupingBy(SumDailyMerchant::getMerchantId));

        // 3. Fetch Merchant Details (Bulk)
        Set<Long> merchantIds = groupedByMerchant.keySet();
        List<com.acquira.model.Merchant> merchants = merchantRepo.findAllById(merchantIds);
        Map<Long, com.acquira.model.Merchant> merchantMap = merchants.stream()
                .collect(Collectors.toMap(com.acquira.model.Merchant::getMerchantId, m -> m));

        for (Map.Entry<Long, List<SumDailyMerchant>> entry : groupedByMerchant.entrySet()) {
            Long merchantId = entry.getKey();
            List<SumDailyMerchant> merchantDaily = entry.getValue();
            com.acquira.model.Merchant mDetails = merchantMap.get(merchantId);

            DailyMerchantDashboardDTO dto = new DailyMerchantDashboardDTO();
            dto.setMerchantId(merchantId);

            if (mDetails != null) {
                dto.setMerchantName(mDetails.getName());
                dto.setMid(mDetails.getMid());
            } else {
                dto.setMerchantName("Unknown Merchant");
                dto.setMid("ID-" + merchantId);
            }

            // Map Daily Volumes
            Map<Integer, BigDecimal> dailyMap = new HashMap<>();
            List<BigDecimal> sparkline = new ArrayList<>();

            // Initialize with Zeros for all days
            for (int i = 1; i <= ym.lengthOfMonth(); i++) {
                dailyMap.put(i, BigDecimal.ZERO);
                sparkline.add(BigDecimal.ZERO);
            }

            BigDecimal currentTotal = BigDecimal.ZERO;

            for (SumDailyMerchant d : merchantDaily) {
                int day = d.getBusinessDate().getDayOfMonth();
                BigDecimal vol = d.getTotalVolume() != null ? d.getTotalVolume() : BigDecimal.ZERO;
                dailyMap.put(day, vol);
                sparkline.set(day - 1, vol);
                currentTotal = currentTotal.add(vol);
            }

            dto.setDailyVolumes(dailyMap);
            dto.setSparklineData(sparkline);
            dto.setTotalVolume(currentTotal);

            // 4. Fetch Advanced Metrics Record
            Optional<SumMonthlyMerchantMetrics> metricsOpt = monthlyMetricsRepo.findByMerchantAndMonth(tenantId,
                    merchantId, monthYearKey);

            if (metricsOpt.isPresent()) {
                SumMonthlyMerchantMetrics m = metricsOpt.get();
                dto.setVolatilityIndex(m.getVolatilityIndex());
                dto.setStabilityLabel(m.getStabilityLabel());
                dto.setBehaviorTag(m.getBehaviorTag());
                dto.setSmartComment(m.getSmartComment());
                dto.setAvgDailyVolume(m.getAvgDailyVolume());
                dto.setMaxDailyVolume(m.getMaxDailyVolume());
                dto.setWeeklyHealth(Arrays.asList(
                        m.getWeek1Health(), m.getWeek2Health(), m.getWeek3Health(), m.getWeek4Health(),
                        m.getWeek5Health()));
            } else {
                dto.setStabilityLabel("Pending");
            }

            dashboard.add(dto);
        }

        return dashboard;
    }
}
