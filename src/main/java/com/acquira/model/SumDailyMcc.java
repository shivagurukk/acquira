package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sum_daily_mcc")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SumDailyMcc {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    @EqualsAndHashCode.Include
    private Long summaryId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "mcc")
    private String mcc;

    @Column(name = "card_scheme")
    private String cardScheme;

    @Column(name = "total_txns")
    private Long totalTxns;

    @Column(name = "total_volume")
    private BigDecimal totalVolume;

    @Column(name = "total_msf")
    private BigDecimal totalMsf;

    @Column(name = "total_scheme_fee")
    private BigDecimal totalSchemeFee;

    @Column(name = "total_net_revenue")
    private BigDecimal totalNetRevenue;
}
