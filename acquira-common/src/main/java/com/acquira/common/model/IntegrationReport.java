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

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ReportType {
        MERCHANT, TRANSACTION
    }
}
