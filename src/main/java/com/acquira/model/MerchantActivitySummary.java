package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "merchant_activity_summary", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "merchant_id", "calc_date" })
})
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MerchantActivitySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "first_txn_date")
    private LocalDate firstTxnDate;

    @Column(name = "last_txn_date")
    private LocalDate lastTxnDate;

    @Column(name = "last_7d_cnt")
    private Long last7dCount;

    @Column(name = "last_7d_value")
    private BigDecimal last7dValue;

    @Column(name = "last_30d_cnt")
    private Long last30dCount;

    @Column(name = "last_30d_value")
    private BigDecimal last30dValue;

    @Column(name = "prev_30d_cnt")
    private Long prev30dCount;

    @Column(name = "prev_30d_value")
    private BigDecimal prev30dValue;

    private String status;

    @Column(name = "status_change_date")
    private LocalDate statusChangeDate;

    @Column(name = "calc_date")
    private LocalDate calcDate;
}
