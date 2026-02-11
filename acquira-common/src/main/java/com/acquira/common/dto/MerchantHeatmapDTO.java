package com.acquira.common.dto;

import java.math.BigDecimal;

public class MerchantHeatmapDTO {
    private String merchantName;
    private String merchantId; // Internal ID or MID
    private Integer month; // 1-12
    private BigDecimal totalVolume;

    public MerchantHeatmapDTO(String merchantName, String merchantId, Integer month, BigDecimal totalVolume) {
        this.merchantName = merchantName;
        this.merchantId = merchantId;
        this.month = month;
        this.totalVolume = totalVolume;
    }

    // Getters and Setters
    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public Integer getMonth() {
        return month;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public BigDecimal getTotalVolume() {
        return totalVolume;
    }

    public void setTotalVolume(BigDecimal totalVolume) {
        this.totalVolume = totalVolume;
    }
}
