package com.acquira.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GeoMetricDTO {
    private String storeName;
    private Double latitude;
    private Double longitude;
    private Double volume;
    private Long txnCount;
    private String riskLevel; // For color coding (Green/Red)

    /**
     * JPQL constructor for SumDailyTerminalRepository.findGeoMetricsByDateForTenant:
     * SUM(totalVolume) is a BigDecimal, and Hibernate 6.6+ matches constructor
     * argument types exactly (no implicit BigDecimal -> Double).
     */
    public GeoMetricDTO(String storeName, Double latitude, Double longitude,
                        java.math.BigDecimal volume, Long txnCount, String riskLevel) {
        this(storeName, latitude, longitude, volume != null ? volume.doubleValue() : null, txnCount, riskLevel);
    }
}
