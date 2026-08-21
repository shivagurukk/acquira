package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Table(name = "merchant_opportunity_score")
@Data
public class MerchantOpportunityScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "score_id")
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    // 0 - 100
    private Integer score;

    @Column(name = "reason_tags", length = 500)
    private String reasonTags;

    @Column(name = "calc_date")
    private LocalDate calcDate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getReasonTags() {
        return reasonTags;
    }

    public void setReasonTags(String reasonTags) {
        this.reasonTags = reasonTags;
    }

    public LocalDate getCalcDate() {
        return calcDate;
    }

    public void setCalcDate(LocalDate calcDate) {
        this.calcDate = calcDate;
    }
}
