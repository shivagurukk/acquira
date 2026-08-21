package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Merchant portfolio segmentation (Phase 1, six data-backed segments).
 *
 * Written by the transaction batch (computeSegmentsStep) after business metrics are
 * fresh, and read as a filter/column/grouping across dashboards. One row per
 * (tenant, merchant, calc_date); the latest calc_date per merchant is the current
 * segment. Daily snapshots give segment-movement history for free.
 *
 * primary_segment ∈ STRATEGIC | VOLUME_DRIVER | PROFIT_DRIVER | AT_RISK | NEW | LONG_TAIL
 * secondary_tags  = comma-separated other qualifying segments (may be blank).
 */
@Entity
@Table(name = "merchant_segment")
@Data
public class MerchantSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "segment_id")
    private Long segmentId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "calc_date")
    private LocalDate calcDate;

    @Column(name = "primary_segment", length = 30)
    private String primarySegment;

    @Column(name = "secondary_tags", length = 255)
    private String secondaryTags;

    @Column(name = "segment_reason", length = 255)
    private String segmentReason;

    @Column(name = "segment_score")
    private BigDecimal segmentScore;

    @Column(name = "total_volume")
    private BigDecimal totalVolume;

    @Column(name = "net_revenue")
    private BigDecimal netRevenue;

    @Column(name = "net_margin_pct")
    private BigDecimal netMarginPct;

    @Column(name = "effective_bps")
    private BigDecimal effectiveBps;

    @Column(name = "net_take_bps")
    private BigDecimal netTakeBps;

    @Column(name = "volume_growth_pct")
    private BigDecimal volumeGrowthPct;

    @Column(name = "days_since_last")
    private Integer daysSinceLast;

    @Column(name = "model_version", length = 60)
    private String modelVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getSegmentId() { return segmentId; }
    public void setSegmentId(Long segmentId) { this.segmentId = segmentId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public LocalDate getCalcDate() { return calcDate; }
    public void setCalcDate(LocalDate calcDate) { this.calcDate = calcDate; }
    public String getPrimarySegment() { return primarySegment; }
    public void setPrimarySegment(String primarySegment) { this.primarySegment = primarySegment; }
    public String getSecondaryTags() { return secondaryTags; }
    public void setSecondaryTags(String secondaryTags) { this.secondaryTags = secondaryTags; }
    public String getSegmentReason() { return segmentReason; }
    public void setSegmentReason(String segmentReason) { this.segmentReason = segmentReason; }
    public BigDecimal getSegmentScore() { return segmentScore; }
    public void setSegmentScore(BigDecimal segmentScore) { this.segmentScore = segmentScore; }
    public BigDecimal getTotalVolume() { return totalVolume; }
    public void setTotalVolume(BigDecimal totalVolume) { this.totalVolume = totalVolume; }
    public BigDecimal getNetRevenue() { return netRevenue; }
    public void setNetRevenue(BigDecimal netRevenue) { this.netRevenue = netRevenue; }
    public BigDecimal getNetMarginPct() { return netMarginPct; }
    public void setNetMarginPct(BigDecimal netMarginPct) { this.netMarginPct = netMarginPct; }
    public BigDecimal getEffectiveBps() { return effectiveBps; }
    public void setEffectiveBps(BigDecimal effectiveBps) { this.effectiveBps = effectiveBps; }
    public BigDecimal getNetTakeBps() { return netTakeBps; }
    public void setNetTakeBps(BigDecimal netTakeBps) { this.netTakeBps = netTakeBps; }
    public BigDecimal getVolumeGrowthPct() { return volumeGrowthPct; }
    public void setVolumeGrowthPct(BigDecimal volumeGrowthPct) { this.volumeGrowthPct = volumeGrowthPct; }
    public Integer getDaysSinceLast() { return daysSinceLast; }
    public void setDaysSinceLast(Integer daysSinceLast) { this.daysSinceLast = daysSinceLast; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
