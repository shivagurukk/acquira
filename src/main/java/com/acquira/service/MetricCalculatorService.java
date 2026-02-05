package com.acquira.service;

import com.acquira.model.MerchantDailyMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MetricCalculatorService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Compute Metrics for a Merchant based on their Daily Volume Map.
     * 
     * @param merchantId
     * @param mid
     * @param merchantName
     * @param dailyVolumes Map<DayOfMonth, Volume> (e.g., 1 -> 500.0)
     * @param reportDate   The 1st of the month being processed.
     * @param sourceType   Origin (DB_PULL / FILE_UPLOAD)
     * @return Fully populated Metric Entity
     */
    public MerchantDailyMetrics computeMetrics(String merchantId, String mid, String merchantName,
            Map<Integer, Double> dailyVolumes, LocalDate reportDate,
            MerchantDailyMetrics.SourceType sourceType) {

        MerchantDailyMetrics metrics = new MerchantDailyMetrics();
        metrics.setReportDate(reportDate);
        metrics.setMerchantId(merchantId);
        metrics.setMid(mid);
        metrics.setMerchantName(merchantName);
        metrics.setSourceType(sourceType);

        // 1. Calculate Core Volumes
        double totalVolume = dailyVolumes.values().stream().mapToDouble(Double::doubleValue).sum();
        metrics.setTotalMtd(totalVolume);

        int todayDay = LocalDate.now().getDayOfMonth();
        // Note: For historical reports, "Today" might need to be the last day of that
        // month.
        // Assuming we are running this for the current month.

        Double todayVol = dailyVolumes.getOrDefault(todayDay, 0.0);
        Double yesterdayVol = dailyVolumes.getOrDefault(todayDay - 1, 0.0);

        metrics.setTodayVolume(todayVol);
        metrics.setYesterdayVolume(yesterdayVol);

        // 2. Trend Calculation
        if (yesterdayVol > 0) {
            double trend = ((todayVol - yesterdayVol) / yesterdayVol) * 100;
            metrics.setTrendPct(trend);
        } else {
            metrics.setTrendPct(todayVol > 0 ? 100.0 : 0.0);
        }

        // 3. Volatility (Std Dev of active days)
        List<Double> activeValues = dailyVolumes.values().stream().filter(v -> v > 0).collect(Collectors.toList());
        metrics.setVolatility(calculateVolatility(activeValues));

        // 4. Avg 7 Day
        metrics.setAvg7Day(calculateAvg7Day(dailyVolumes, todayDay));

        // 5. Risk Score
        // 5. Risk Score
        metrics.setRiskScore(calculateRiskScore(metrics.getVolatility(), metrics.getTrendPct(),
                metrics.getTodayVolume(), metrics.getAvg7Day()));

        // 6. UI Status
        metrics.setUiStatus(determineUiStatus(metrics.getRiskScore()));

        // 7. Serialize JSONs
        try {
            metrics.setDailyVolumesJson(objectMapper.writeValueAsString(dailyVolumes));

            // Sparkline: Simple array of values sorted by day
            List<Double> sparkline = new ArrayList<>();
            for (int i = 1; i <= 31; i++) {
                sparkline.add(dailyVolumes.getOrDefault(i, 0.0));
            }
            metrics.setSparklineDataJson(objectMapper.writeValueAsString(sparkline));

        } catch (JsonProcessingException e) {
            log.error("JSON Error", e);
        }

        return metrics;
    }

    private String calculateVolatility(List<Double> values) {
        if (values.size() < 2)
            return "Low";

        double mean = values.stream().mapToDouble(val -> val).average().orElse(0.0);
        double variance = values.stream().mapToDouble(val -> Math.pow(val - mean, 2)).sum() / values.size();
        double stdDev = Math.sqrt(variance);
        double cv = mean > 0 ? stdDev / mean : 0;

        if (cv > 1.5)
            return "High";
        if (cv > 0.8)
            return "Medium";
        return "Low";
    }

    private Double calculateAvg7Day(Map<Integer, Double> volumes, int currentDay) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < 7; i++) {
            int d = currentDay - i;
            if (d > 0) {
                sum += volumes.getOrDefault(d, 0.0);
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    private Integer calculateRiskScore(String volatility, Double trend, Double todayVol, Double avg7Day) {
        int score = 10;

        // 1. Volatility Impact
        if ("High".equals(volatility))
            score += 30;
        if ("Medium".equals(volatility))
            score += 15;

        // 2. Trend Impact (Crash)
        if (trend < -50)
            score += 25;

        // 3. Anomaly Detection Rules
        boolean isSpike = (todayVol > 3 * avg7Day) && (todayVol > 1000);
        boolean isFlatline = (todayVol == 0) && (avg7Day > 500);

        if (isSpike)
            score = 90; // Immediate Risk Alert for Spikes
        if (isFlatline)
            score += 40; // High concern

        return Math.min(100, Math.max(0, score));
    }

    private String determineUiStatus(Integer score) {
        if (score >= 80)
            return "Risk"; // Red
        if (score >= 50)
            return "Watch"; // Yellow
        if (score >= 1)
            return "Stable"; // Green
        return "Unknown"; // Grey
    }
}
