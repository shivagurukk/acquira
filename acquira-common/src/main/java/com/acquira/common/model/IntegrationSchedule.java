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

    /**
     * Comma/semicolon-separated recipients notified when a pull's FINAL attempt
     * fails (retries in progress do not alert). Sent via the tenant's own SMTP
     * config. Blank/NULL = no alert.
     */
    @Column(name = "alert_emails", columnDefinition = "TEXT")
    private String alertEmails;

    /** Mute switch for failure alerts without losing the recipient list. */
    @Column(name = "alert_on_failure")
    private Boolean alertOnFailure = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Read-model enrichment (not persisted) ────────────────────
    // Filled by the schedules list endpoint so the UI can show when a schedule
    // fires next and how its recent runs went, without extra round trips.

    /** Next fire instant as ISO-8601 WITH offset (nextRunAt is a bare LocalDateTime and would render in the wrong zone). */
    @Transient
    private String nextRunIso;

    /** Status of the most recent run for this schedule (SUCCESS/FAILED/RUNNING/RETRYING), null if never run. */
    @Transient
    private String lastRunStatus;

    /** Error message of the most recent run when it failed. */
    @Transient
    private String lastRunError;

    /** Statuses of the last few runs, most recent first — for the mini history dots. */
    @Transient
    private java.util.List<String> recentRunStatuses;
}
