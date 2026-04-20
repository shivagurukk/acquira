package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Entity
@Table(name = "sum_monthly_card")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SumMonthlyCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "month_key")
    private Integer monthKey;

    @Column(name = "card_number")
    private String cardNumber;

    @Column(name = "visit_count")
    private Long visitCount;

    @Column(name = "total_spend")
    private BigDecimal totalSpend;
}
