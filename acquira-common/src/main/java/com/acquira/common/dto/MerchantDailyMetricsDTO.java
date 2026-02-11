package com.acquira.common.dto;

import lombok.Data;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Data
public class MerchantDailyMetricsDTO {
    private String merchantId;
    private String merchantName;
    private String mid;

    private Double todayVolume;
    private Double yesterdayVolume;
    private Double avg7Day;
    private Double totalMtd;

    private Double trendPct;
    private String volatility;
    private Integer riskScore;
    private String uiStatus; // Stable, Watch, Risk

    private Map<Integer, Double> dailyVolumes; // Parsed from JSON

    // Sparkline can be derived from dailyVolumes or sent separately

    public MerchantDailyMetricsDTO() {
    }

    // Manual mapping or use Mapper
    public static MerchantDailyMetricsDTO fromEntity(com.acquira.common.model.MerchantDailyMetrics entity) {
        MerchantDailyMetricsDTO dto = new MerchantDailyMetricsDTO();
        dto.setMerchantId(entity.getMerchantId());
        dto.setMerchantName(entity.getMerchantName());
        dto.setMid(entity.getMid());

        dto.setTodayVolume(entity.getTodayVolume());
        dto.setYesterdayVolume(entity.getYesterdayVolume());
        dto.setAvg7Day(entity.getAvg7Day());
        dto.setTotalMtd(entity.getTotalMtd());

        dto.setTrendPct(entity.getTrendPct());
        dto.setVolatility(entity.getVolatility());
        dto.setRiskScore(entity.getRiskScore());
        dto.setUiStatus(entity.getUiStatus());

        // Parse JSON
        try {
            if (entity.getDailyVolumesJson() != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                dto.setDailyVolumes(mapper.readValue(entity.getDailyVolumesJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<Integer, Double>>() {
                        }));
            } else {
                dto.setDailyVolumes(new HashMap<>());
            }
        } catch (Exception e) {
            dto.setDailyVolumes(new HashMap<>());
        }

        return dto;
    }
}
