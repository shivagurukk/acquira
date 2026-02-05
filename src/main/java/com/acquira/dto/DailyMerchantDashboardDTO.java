package com.acquira.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class DailyMerchantDashboardDTO {
    private Long merchantId;
    private String merchantName;
    private String mid;
    private String sid;
    private String referralPartner;
    private String rm;

    // Daily Volumes (Day 1 to 31)
    private Map<Integer, BigDecimal> dailyVolumes;

    // Monthly Aggregates
    private BigDecimal totalVolume;
    private BigDecimal avgDailyVolume;
    private BigDecimal maxDailyVolume;
    private BigDecimal minDailyVolume;

    // Advanced Metrics
    private BigDecimal volatilityIndex;
    private String stabilityLabel; // Stable, Fluctuating, Unstable
    private String behaviorTag; // Weekend Heavy, etc.
    private String smartComment;

    // Weekly Health Status (Green/Yellow/Red)
    private List<String> weeklyHealth;

    // Sparkline Data (List of daily totals for charting)
    private List<BigDecimal> sparklineData;
}
