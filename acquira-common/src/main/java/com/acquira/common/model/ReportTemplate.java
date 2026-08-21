package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "report_template")
@Data
public class ReportTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    private String description;

    /**
     * JSON config storing the full explorer query configuration:
     * {
     *   "source": "transaction",
     *   "dimensions": ["card_scheme", "payment_date"],
     *   "measures": [{"field": "txn_currency_amount", "aggregation": "SUM"}],
     *   "filters": [...],
     *   "chartType": "bar",
     *   "startDate": "2026-01-01",
     *   "endDate": "2026-01-31"
     * }
     */
    @Column(name = "config_json", columnDefinition = "TEXT", nullable = false)
    private String configJson;

    @Column(name = "is_shared")
    private Boolean isShared = false;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
