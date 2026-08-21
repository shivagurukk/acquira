package com.acquira.common.dto;

import java.math.BigDecimal;

public class MerchantSummaryDTO {
    private String merchantName;
    private String mid;
    private String storeName;
    private String sid;
    private String tid;
    private String deviceNumber;

    // New fields
    private BigDecimal creditVolume;
    private BigDecimal debitPrepaidVolume;
    private String salesUserId;

    // Daily
    private Long dailyCount;
    private BigDecimal dailyVolume;

    // MTD
    private Long mtdCount;
    private BigDecimal mtdVolume;

    // YTD
    private Long ytdCount;
    private BigDecimal ytdVolume;

    public MerchantSummaryDTO() {
    }

    public MerchantSummaryDTO(String merchantName, String mid, String storeName, String sid, String tid,
            String deviceNumber, Long dailyCount, BigDecimal dailyVolume, Long mtdCount, BigDecimal mtdVolume,
            Long ytdCount, BigDecimal ytdVolume, BigDecimal creditVolume, BigDecimal debitPrepaidVolume,
            String salesUserId) {
        this.merchantName = merchantName;
        this.mid = mid;
        this.storeName = storeName;
        this.sid = sid;
        this.tid = tid;
        this.deviceNumber = deviceNumber;
        this.dailyCount = dailyCount;
        this.dailyVolume = dailyVolume;
        this.mtdCount = mtdCount;
        this.mtdVolume = mtdVolume;
        this.ytdCount = ytdCount;
        this.ytdVolume = ytdVolume;
        this.creditVolume = creditVolume;
        this.debitPrepaidVolume = debitPrepaidVolume;
        this.salesUserId = salesUserId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMid() {
        return mid;
    }

    public void setMid(String mid) {
        this.mid = mid;
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getSid() {
        return sid;
    }

    public void setSid(String sid) {
        this.sid = sid;
    }

    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public String getDeviceNumber() {
        return deviceNumber;
    }

    public void setDeviceNumber(String deviceNumber) {
        this.deviceNumber = deviceNumber;
    }

    public Long getDailyCount() {
        return dailyCount;
    }

    public void setDailyCount(Long dailyCount) {
        this.dailyCount = dailyCount;
    }

    public BigDecimal getDailyVolume() {
        return dailyVolume;
    }

    public void setDailyVolume(BigDecimal dailyVolume) {
        this.dailyVolume = dailyVolume;
    }

    public Long getMtdCount() {
        return mtdCount;
    }

    public void setMtdCount(Long mtdCount) {
        this.mtdCount = mtdCount;
    }

    public BigDecimal getMtdVolume() {
        return mtdVolume;
    }

    public void setMtdVolume(BigDecimal mtdVolume) {
        this.mtdVolume = mtdVolume;
    }

    public Long getYtdCount() {
        return ytdCount;
    }

    public void setYtdCount(Long ytdCount) {
        this.ytdCount = ytdCount;
    }

    public BigDecimal getYtdVolume() {
        return ytdVolume;
    }

    public void setYtdVolume(BigDecimal ytdVolume) {
        this.ytdVolume = ytdVolume;
    }

    public BigDecimal getCreditVolume() {
        return creditVolume;
    }

    public void setCreditVolume(BigDecimal creditVolume) {
        this.creditVolume = creditVolume;
    }

    public BigDecimal getDebitPrepaidVolume() {
        return debitPrepaidVolume;
    }

    public void setDebitPrepaidVolume(BigDecimal debitPrepaidVolume) {
        this.debitPrepaidVolume = debitPrepaidVolume;
    }

    public String getSalesUserId() {
        return salesUserId;
    }

    public void setSalesUserId(String salesUserId) {
        this.salesUserId = salesUserId;
    }
}
