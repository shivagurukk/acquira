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
}
