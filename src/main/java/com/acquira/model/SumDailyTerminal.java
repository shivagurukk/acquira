package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sum_daily_terminal")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SumDailyTerminal {

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

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "terminal_id")
    private Long terminalId;

    private Long totalTxns;
    private BigDecimal totalVolume;
    private BigDecimal totalMsf;
    private BigDecimal totalInterchange;
    private BigDecimal totalMargin;
    private BigDecimal totalRevenue;
}
