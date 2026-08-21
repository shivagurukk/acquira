package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Entity
@Table(name = "sum_monthly_card")
@Data
public class SumMonthlyCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "month_key")
    private Integer monthKey; // YYYYMM

    @Column(name = "card_number")
    private String cardNumber;

    @Column(name = "visit_count")
    private Long visitCount;

    @Column(name = "total_spend")
    private BigDecimal totalSpend;

    // Optional indices for performance:
    // (tenant_id, merchant_id, month_key)
    // Manual Getters (Fallback if Lombok fails)
    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public Integer getMonthKey() {
        return monthKey;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public Long getVisitCount() {
        return visitCount;
    }

    public BigDecimal getTotalSpend() {
        return totalSpend;
    }
}
