package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "sum_monthly_merchant_metrics", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "merchant_id", "month_year" })
})
@Data
public class SumMonthlyMerchantMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "metric_id")
    private Long metricId;

    @Column(name = "tenant_id")
    private Integer tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "month_year")
    private String monthYear;

    @Column(name = "volatility_index", precision = 19, scale = 4)
    private BigDecimal volatilityIndex;

    @Column(name = "stability_label")
    private String stabilityLabel;

    @Column(name = "behavior_tag")
    private String behaviorTag;

    @Column(name = "smart_comment")
    private String smartComment;

    @Column(name = "week_1_health")
    private String week1Health;

    @Column(name = "week_2_health")
    private String week2Health;

    @Column(name = "week_3_health")
    private String week3Health;

    @Column(name = "week_4_health")
    private String week4Health;

    @Column(name = "week_5_health")
    private String week5Health;

    @Column(name = "total_volume", precision = 19, scale = 2)
    private BigDecimal totalVolume;

    @Column(name = "avg_daily_volume", precision = 19, scale = 2)
    private BigDecimal avgDailyVolume;

    @Column(name = "max_daily_volume", precision = 19, scale = 2)
    private BigDecimal maxDailyVolume;

    @Column(name = "min_daily_volume", precision = 19, scale = 2)
    private BigDecimal minDailyVolume;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
