package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ML churn-risk score for a merchant at a point in time.
 *
 * Written by the transaction batch (scoreMlStep) after summaries are fresh, and
 * read by the Attrition Report to show "who's about to go dormant" alongside the
 * existing "who already declined" view. One row per (tenant, merchant, calc_date);
 * the latest calc_date per merchant is the current risk.
 *
 * scored_by = MODEL when a trained model produced the value, HEURISTIC when the
 * cold-start fallback did (no model yet, or too little history to train). The
 * distinction lets the UI/ops know whether ML is actually live for a tenant.
 */
@Entity
@Table(name = "merchant_churn_score")
@Data
public class MerchantChurnScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "churn_id")
    private Long churnId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "calc_date")
    private LocalDate calcDate;

    @Column(name = "churn_probability")
    private BigDecimal churnProbability; // 0.0000 .. 1.0000

    @Column(name = "risk_band", length = 10)
    private String riskBand; // LOW | MEDIUM | HIGH

    @Column(name = "top_reason", length = 255)
    private String topReason;

    @Column(name = "model_version", length = 60)
    private String modelVersion;

    @Column(name = "scored_by", length = 20)
    private String scoredBy; // MODEL | HEURISTIC

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getChurnId() { return churnId; }
    public void setChurnId(Long churnId) { this.churnId = churnId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public LocalDate getCalcDate() { return calcDate; }
    public void setCalcDate(LocalDate calcDate) { this.calcDate = calcDate; }

    public BigDecimal getChurnProbability() { return churnProbability; }
    public void setChurnProbability(BigDecimal churnProbability) { this.churnProbability = churnProbability; }

    public String getRiskBand() { return riskBand; }
    public void setRiskBand(String riskBand) { this.riskBand = riskBand; }

    public String getTopReason() { return topReason; }
    public void setTopReason(String topReason) { this.topReason = topReason; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public String getScoredBy() { return scoredBy; }
    public void setScoredBy(String scoredBy) { this.scoredBy = scoredBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
