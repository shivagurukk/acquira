package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Entity
@Table(name = "sum_monthly_bank")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SumMonthlyBank {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    @EqualsAndHashCode.Include
    private Long summaryId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "month_key")
    private Integer monthKey;

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

    @Column(name = "total_vat")
    private BigDecimal totalVat;

    @Column(name = "total_net_revenue")
    private BigDecimal totalNetRevenue;
}
