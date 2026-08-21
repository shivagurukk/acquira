package com.acquira.common.service;

import com.acquira.common.model.SumDailyMerchant;
import com.acquira.common.model.SumMonthlyMerchantMetrics;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class MerchantMetricCalculator {

    public SumMonthlyMerchantMetrics calculateMetrics(List<SumDailyMerchant> dailyRecords, Integer tenantId,
            Long merchantId, String monthYear) {
        SumMonthlyMerchantMetrics metrics = new SumMonthlyMerchantMetrics();
        metrics.setTenantId(tenantId);
        metrics.setMerchantId(merchantId);
        metrics.setMonthYear(monthYear);

        if (dailyRecords == null || dailyRecords.isEmpty()) {
            return metrics;
        }

        // Basic Stats
        BigDecimal totalVol = BigDecimal.ZERO;
        BigDecimal maxVol = BigDecimal.ZERO;
        BigDecimal minVol = dailyRecords.get(0).getTotalVolume();

        List<BigDecimal> volumes = new ArrayList<>();

        for (SumDailyMerchant rec : dailyRecords) {
            BigDecimal v = rec.getTotalVolume() != null ? rec.getTotalVolume() : BigDecimal.ZERO;
            totalVol = totalVol.add(v);
            volumes.add(v);
            if (v.compareTo(maxVol) > 0)
                maxVol = v;
            if (v.compareTo(minVol) < 0)
                minVol = v;
        }

        BigDecimal days = new BigDecimal(dailyRecords.size());
        BigDecimal avgVol = totalVol.divide(days, 2, RoundingMode.HALF_UP);

        metrics.setTotalVolume(totalVol);
        metrics.setMaxDailyVolume(maxVol);
        metrics.setMinDailyVolume(minVol);
        metrics.setAvgDailyVolume(avgVol);

        // Volatility (Standard Deviation)
        BigDecimal volatility = calculateStandardDeviation(volumes, avgVol);
        metrics.setVolatilityIndex(volatility);

        // Stability Label
        // Heuristic: If StdDev > 50% of Avg, it's Unstable. If > 20%, Fluctuating. Else
        // Stable.
        BigDecimal volatilityRatio = avgVol.compareTo(BigDecimal.ZERO) > 0
                ? volatility.divide(avgVol, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        if (volatilityRatio.compareTo(new BigDecimal("0.50")) > 0) {
            metrics.setStabilityLabel("Unstable");
        } else if (volatilityRatio.compareTo(new BigDecimal("0.20")) > 0) {
            metrics.setStabilityLabel("Fluctuating");
        } else {
            metrics.setStabilityLabel("Stable");
        }

        // Behavior Tag logic
        metrics.setBehaviorTag(determineBehaviorTag(dailyRecords, totalVol));

        // Weekly Health
        calculateWeeklyHealth(metrics, dailyRecords, avgVol);

        // Smart Comment
        metrics.setSmartComment(generateSmartComment(metrics, dailyRecords));

        return metrics;
    }

    private BigDecimal calculateStandardDeviation(List<BigDecimal> volumes, BigDecimal mean) {
        if (volumes.isEmpty())
            return BigDecimal.ZERO;

        BigDecimal sumSquaredDiff = BigDecimal.ZERO;
        for (BigDecimal v : volumes) {
            BigDecimal diff = v.subtract(mean);
            sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff));
        }

        BigDecimal variance = sumSquaredDiff.divide(new BigDecimal(volumes.size()), 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private String determineBehaviorTag(List<SumDailyMerchant> records, BigDecimal totalVol) {
        // Weekend Heavy Check
        BigDecimal weekendVol = BigDecimal.ZERO;
        BigDecimal weekdayVol = BigDecimal.ZERO;

        for (SumDailyMerchant r : records) {
            DayOfWeek day = r.getBusinessDate().getDayOfWeek();
            BigDecimal v = r.getTotalVolume() != null ? r.getTotalVolume() : BigDecimal.ZERO;
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
                weekendVol = weekendVol.add(v);
            } else {
                weekdayVol = weekdayVol.add(v);
            }
        }

        // If > 40% volume is on weekends (which are ~28% of the week), it's Weekend
        // Heavy
        if (totalVol.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal weekendRatio = weekendVol.divide(totalVol, 2, RoundingMode.HALF_UP);
            if (weekendRatio.compareTo(new BigDecimal("0.40")) > 0)
                return "Weekend Heavy";
        }

        // End of Month Spikes (last 5 days > 40% of volume)
        // ... logic simplification for brevity ...

        return "Steady Performer"; // Default
    }

    private void calculateWeeklyHealth(SumMonthlyMerchantMetrics metrics, List<SumDailyMerchant> records,
            BigDecimal dailyAvg) {
        // Week 1: Day 1-7
        metrics.setWeek1Health(assessWeek(records, 1, 7, dailyAvg));
        metrics.setWeek2Health(assessWeek(records, 8, 14, dailyAvg));
        metrics.setWeek3Health(assessWeek(records, 15, 21, dailyAvg));
        metrics.setWeek4Health(assessWeek(records, 22, 28, dailyAvg));
        metrics.setWeek5Health(assessWeek(records, 29, 31, dailyAvg));
    }

    private String assessWeek(List<SumDailyMerchant> records, int startDay, int endDay, BigDecimal targetDailyAvg) {
        BigDecimal weekVol = BigDecimal.ZERO;
        int count = 0;

        for (SumDailyMerchant r : records) {
            int day = r.getBusinessDate().getDayOfMonth();
            if (day >= startDay && day <= endDay) {
                weekVol = weekVol.add(r.getTotalVolume() != null ? r.getTotalVolume() : BigDecimal.ZERO);
                count++;
            }
        }

        if (count == 0)
            return "Grey"; // No data

        BigDecimal weekAvg = weekVol.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP);

        // Green if > 90% of Month Avg, Yellow > 60%, Red otherwise
        if (weekAvg.compareTo(targetDailyAvg.multiply(new BigDecimal("0.9"))) > 0)
            return "Green";
        if (weekAvg.compareTo(targetDailyAvg.multiply(new BigDecimal("0.6"))) > 0)
            return "Yellow";
        return "Red";
    }

    private String generateSmartComment(SumMonthlyMerchantMetrics m, List<SumDailyMerchant> records) {
        if ("Unstable".equals(m.getStabilityLabel())) {
            return "Highly volatile volume detected. Recommend risk review.";
        }
        if ("Weekend Heavy".equals(m.getBehaviorTag())) {
            return "Strong weekend performance. Consider weekend-specific promotions.";
        }
        if ("Red".equals(m.getWeek1Health()) && "Green".equals(m.getWeek4Health())) {
            return "Recovered well after a slow start to the month.";
        }
        return "Consistent performance observed.";
    }
}
