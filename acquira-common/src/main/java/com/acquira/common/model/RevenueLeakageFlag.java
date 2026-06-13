package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A detected revenue-leakage / anomaly signal for a merchant.
 *
 * Populated by {@code RevenueLeakageDetectionService}, which compares each
 * merchant's recent activity (last 7 days) against a trailing baseline
 * (the prior 28 days) using the pre-aggregated {@code sum_daily_merchant}
 * table. One row per (tenant, merchant, check_type, business_date).
 *
 * check_type values:
 *   VOLUME_DROP          — recent daily volume fell sharply vs baseline
 *   MSF_RATE_DROP        — effective MSF rate (fee / volume) dropped
 *   ZERO_MSF             — merchant is transacting but capturing no MSF
 *   DORMANT_REVENUE_LOSS — a previously-active merchant stopped transacting
 *
 * status: OPEN | RESOLVED | IGNORED.  is_resolved mirrors (status != OPEN)
 * for backward compatibility with the original minimal table.
 */
@Entity
@Table(name = "revenue_leakage_flags")
@Data
public class RevenueLeakageFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "flag_id")
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "check_type")
    private String checkType;

    private String severity;

    @Column(columnDefinition = "text")
    private String details;

    @Column(name = "business_date")
    private LocalDate businessDate;

    /** Recent observed value (e.g. recent daily volume, or recent rate in %). */
    @Column(name = "metric_value")
    private BigDecimal metricValue;

    /** Baseline value the recent value is compared against. */
    @Column(name = "baseline_value")
    private BigDecimal baselineValue;

    /** Percent change of metric vs baseline (negative = drop). */
    @Column(name = "delta_pct")
    private BigDecimal deltaPct;

    /** Estimated revenue (MSF) at risk per 30 days, in settlement currency. */
    @Column(name = "est_monthly_impact")
    private BigDecimal estMonthlyImpact;

    private String status;

    @Column(name = "is_resolved")
    private Boolean isResolved;

    @Column(name = "detected_at")
    private LocalDateTime detectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;
}
