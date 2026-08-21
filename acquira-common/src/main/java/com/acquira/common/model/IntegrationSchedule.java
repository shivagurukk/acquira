package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "integration_schedule")
@Data
public class IntegrationSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "report_id", nullable = false)
    private IntegrationReport report;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression; // e.g. "0 0 2 * * ?" = daily 2AM

    @Column(name = "frequency_label")
    private String frequencyLabel; // HOURLY, DAILY, WEEKLY, MONTHLY, CUSTOM

    @Column(name = "timezone")
    private String timezone = "UTC";

    @Column(name = "is_enabled")
    private Boolean isEnabled = true;

    /**
     * Optional upstream-readiness gate. When enabled, the scheduled pull first
     * runs this single-statement query against the SAME external connection and
     * only proceeds when the first cell of the first row is truthy
     * (true / non-zero number / Y / YES / 1 / COMPLETED / SUCCESS / DONE).
     * Otherwise the pull is deferred through the normal retry backoff.
     * Manual "Run Now" bypasses the gate.
     */
    @Column(name = "precondition_enabled")
    private Boolean preconditionEnabled = false;

    @Column(name = "precondition_sql", columnDefinition = "TEXT")
    private String preconditionSql;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
