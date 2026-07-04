package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A per-tenant monthly budget target for a single metric.
 *
 * Table already existed in schema.sql (RLS-enabled) but had no JPA mapping,
 * repository, controller or UI — so no target was ever enterable and the
 * Business/Finance dashboards had nothing to compare actuals against. This
 * entity is the first mapping of that table.
 *
 *   month_key    : YYYYMM integer (matches sum_monthly_bank.month_key)
 *   metric_type  : VOLUME | NET_REVENUE | MSF | TXNS  (free-text column;
 *                  the controller validates against a known set)
 *   target_value : the numeric goal for that (tenant, month, metric)
 */
@Entity
@Table(name = "bank_budget_target")
@Data
public class BankBudgetTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "budget_id")
    private Long budgetId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "month_key")
    private Integer monthKey; // YYYYMM

    @Column(name = "metric_type")
    private String metricType;

    @Column(name = "target_value")
    private BigDecimal targetValue;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getBudgetId() { return budgetId; }
    public void setBudgetId(Long budgetId) { this.budgetId = budgetId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Integer getMonthKey() { return monthKey; }
    public void setMonthKey(Integer monthKey) { this.monthKey = monthKey; }

    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }

    public BigDecimal getTargetValue() { return targetValue; }
    public void setTargetValue(BigDecimal targetValue) { this.targetValue = targetValue; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
