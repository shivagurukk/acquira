package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "integration_run_log")
@Data
public class IntegrationRunLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "report_id")
    private IntegrationReport report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private IntegrationSchedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "attempt_number")
    private Integer attemptNumber = 1;

    @Column(name = "max_retries")
    private Integer maxRetries = 3;

    @Column(name = "rows_fetched")
    private Integer rowsFetched = 0;

    @Column(name = "rows_processed")
    private Integer rowsProcessed = 0;

    @Column(name = "rows_failed")
    private Integer rowsFailed = 0;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "date_range_from")
    private LocalDate dateRangeFrom;

    @Column(name = "date_range_to")
    private LocalDate dateRangeTo;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum TriggerType {
        SCHEDULED, MANUAL, RETRY
    }

    public enum Status {
        RUNNING, SUCCESS, FAILED, RETRYING
    }
}
