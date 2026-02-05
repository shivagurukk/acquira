package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sum_daily_merchant")
@Data
public class SumDailyMerchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    private Long summaryId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "total_txns")
    private Long totalTxns;

    @Column(name = "total_volume")
    private BigDecimal totalVolume;

    @Column(name = "total_msf")
    private BigDecimal totalMsf;

    @Column(name = "total_interchange")
    private BigDecimal totalInterchange;

    @Column(name = "total_scheme_fee")
    private BigDecimal totalSchemeFee;

    @Column(name = "total_margin")
    private BigDecimal totalMargin;

    @Column(name = "total_debit_prepaid_volume")
    private BigDecimal totalDebitPrepaidVolume;

    @Column(name = "total_credit_volume")
    private BigDecimal totalCreditVolume;

    @Column(name = "sales_user_id")
    private String salesUserId;

    // --- New Metrics for Scalability ---
    @Column(name = "unique_customer_count")
    private Long uniqueCustomerCount;

    @Column(name = "top_spending_customer_id")
    private String topSpendingCustomerId;

    @Column(name = "top_spending_amount")
    private BigDecimal topSpendingAmount;

    // --- DCC Metrics ---
    @Column(name = "dcc_eligible_volume")
    private BigDecimal dccEligibleVolume;

    @Column(name = "dcc_optin_volume")
    private BigDecimal dccOptinVolume;

    @Column(name = "dcc_optout_volume")
    private BigDecimal dccOptoutVolume;

    @Column(name = "dcc_eligible_count")
    private Long dccEligibleCount;

    @Column(name = "dcc_optin_count")
    private Long dccOptinCount;

    // We might need to join with DimMerchant to get names, or just store name here?
    // Usually Summary tables use IDs. We'll join in Query or fetch separately.
    @ManyToOne
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false)
    private Merchant merchant;

    public Long getSummaryId() {
        return summaryId;
    }

    public void setSummaryId(Long summaryId) {
        this.summaryId = summaryId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public Long getTotalTxns() {
        return totalTxns;
    }

    public void setTotalTxns(Long totalTxns) {
        this.totalTxns = totalTxns;
    }

    public BigDecimal getTotalVolume() {
        return totalVolume;
    }

    public void setTotalVolume(BigDecimal totalVolume) {
        this.totalVolume = totalVolume;
    }

    public BigDecimal getTotalMsf() {
        return totalMsf;
    }

    public void setTotalMsf(BigDecimal totalMsf) {
        this.totalMsf = totalMsf;
    }

    public BigDecimal getTotalInterchange() {
        return totalInterchange;
    }

    public void setTotalInterchange(BigDecimal totalInterchange) {
        this.totalInterchange = totalInterchange;
    }

    public BigDecimal getTotalSchemeFee() {
        return totalSchemeFee;
    }

    public void setTotalSchemeFee(BigDecimal totalSchemeFee) {
        this.totalSchemeFee = totalSchemeFee;
    }

    public BigDecimal getTotalMargin() {
        return totalMargin;
    }

    public void setTotalMargin(BigDecimal totalMargin) {
        this.totalMargin = totalMargin;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public void setMerchant(Merchant merchant) {
        this.merchant = merchant;
    }
}
