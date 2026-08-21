package com.acquira.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sum_daily_merchant")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SumDailyMerchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    @EqualsAndHashCode.Include
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

    @Column(name = "unique_customer_count")
    private Long uniqueCustomerCount;

    @Column(name = "top_spending_customer_id")
    private String topSpendingCustomerId;

    @Column(name = "top_spending_amount")
    private BigDecimal topSpendingAmount;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Merchant merchant;
}
