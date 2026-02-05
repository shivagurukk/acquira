package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "merchant_daily_metrics", indexes = {
        @Index(name = "idx_metrics_date", columnList = "reportDate"),
        @Index(name = "idx_metrics_mid", columnList = "mid")
}, uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "reportDate", "merchantId" })
})
public class MerchantDailyMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private LocalDate reportDate; // The "Month" of the report (usually set to 1st of month)

    @Column(nullable = false)
    private String merchantId;

    private String merchantName;
    private String mid;

    // --- Core Volume Metrics ---
    private Double todayVolume = 0.0;
    private Double yesterdayVolume = 0.0;
    private Double avg7Day = 0.0;
    private Double totalMtd = 0.0;

    // --- BI Intelligence (Backend Computed) ---
    private Double trendPct = 0.0;

    @Column(length = 20)
    private String volatility; // Low, Medium, High

    private Integer riskScore = 0; // 0-100

    @Column(length = 20)
    private String uiStatus; // Stable, Watch, Risk, Unstable

    // --- JSON Data for Frontend ---
    @Column(columnDefinition = "TEXT")
    private String dailyVolumesJson; // { "1": 1200.50, "2": 0.0, ... }

    @Column(columnDefinition = "TEXT")
    private String sparklineDataJson; // [1200, 1100, 1300...]

    // --- Provenance ---
    @Enumerated(EnumType.STRING)
    private SourceType sourceType;

    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum SourceType {
        FILE_UPLOAD, DB_PULL
    }
}
