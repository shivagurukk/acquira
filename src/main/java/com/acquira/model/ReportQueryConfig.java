package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "report_query_config")
public class ReportQueryConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "source_id", nullable = false)
    private DataSourceConfig dataSource;

    @Column(nullable = false)
    private String reportName; // e.g., "Daily Merchant Performance"

    @Column(columnDefinition = "TEXT", nullable = false)
    private String sqlText; // The Actual Query with :param placeholders

    @Column(columnDefinition = "TEXT")
    private String paramSchemaJson; // JSON schema of expected params e.g. { "dateFrom": "date", "bankCode":
                                    // "string" }

    private boolean isActive = true;

    private String approvedBy; // Security Audit

    private LocalDateTime createdAt = LocalDateTime.now();
}
