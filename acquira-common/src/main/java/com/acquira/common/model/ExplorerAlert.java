package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * A Data Explorer threshold alert. Periodically the {@code ExplorerAlertScheduler}
 * runs the configured measure (a base measure, or a calculated one supplied via
 * {@code calcJson}) over a recent window for this tenant, compares the value to
 * {@code threshold} using {@code operator}, and — on breach — writes a row into
 * the existing {@code alert_history} table so it surfaces in the Alerts UI.
 */
@Entity
@Data
@Table(name = "explorer_alert")
public class ExplorerAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    /** Measure key to evaluate — a base measure key OR a calc measure key defined in calcJson. */
    @Column(name = "measure_key", nullable = false)
    private String measureKey;

    /** Optional JSON array of calc-measure defs [{key,label,formula}] when measureKey is calculated. */
    @Column(name = "calc_json", columnDefinition = "TEXT")
    private String calcJson;

    /** Optional JSON object of associative filters {fieldKey:[values]}. */
    @Column(name = "filter_json", columnDefinition = "TEXT")
    private String filterJson;

    /** How many trailing days to aggregate over (inclusive of today). */
    @Column(name = "window_days")
    private Integer windowDays = 1;

    /** One of: > >= < <= == != */
    @Column(nullable = false)
    private String operator;

    @Column(nullable = false)
    private Double threshold;

    private String severity = "WARNING";

    @Column(columnDefinition = "TEXT")
    private String recipients;

    @Column(name = "is_enabled")
    private Boolean isEnabled = true;

    @Column(name = "last_value")
    private Double lastValue;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
