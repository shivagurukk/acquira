package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "integration_report")
@Data
public class IntegrationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "connection_id", nullable = false)
    private IntegrationConnection connection;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false)
    private ReportType reportType;

    @Column(name = "sql_text", columnDefinition = "TEXT", nullable = false)
    private String sqlText;

    @Column(name = "column_mapping", columnDefinition = "TEXT")
    private String columnMapping; // JSON string — {"SQL_COL": "staging_field", ...}

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "param_schema", columnDefinition = "TEXT")
    private String paramSchema; // JSON — [{"name":"year","type":"INTEGER"},{"name":"month","type":"INTEGER"}]

    /**
     * Per-report override of the amount format:
     *   TRUE  = the query returns minor units (fils/cents) — divide at ingest (CMM);
     *   FALSE = the query already returns final decimals — no division (AMS);
     *   NULL  = inherit tenant.input_format, exactly like a file upload.
     * NULL is the default on purpose. It used to be FALSE, and the Integration
     * Hub UI has no control for this field, so every report created from the
     * UI ran in "no division" mode — a CMM tenant's DB pull then loaded
     * volumes 100x larger than the same day's file upload (2026-09-24).
     */
    @Column(name = "amounts_minor_units")
    private Boolean amountsMinorUnits;

    @Column(name = "is_active")
    private Boolean isActive = true;

    /**
     * Who signed off on this report's SQL. NULL = not approved, and
     * IntegrationPullService refuses to execute it. Cleared automatically
     * whenever sqlText changes, so an edit must be re-reviewed.
     * 'LEGACY-PRE-APPROVAL' marks rows grandfathered by
     * V2026_08_22_01__integration_report_approval.sql.
     */
    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** True when this report's SQL may be executed by a pull. */
    public boolean isApproved() {
        return approvedBy != null && !approvedBy.isBlank();
    }

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ReportType {
        MERCHANT, TRANSACTION, RENTAL, DCC
    }
}
