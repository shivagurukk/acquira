package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A sales target for one agent, for one month.
 *
 * <p><b>Entered yearly, stored monthly.</b> An admin types one annual number per
 * agent; {@code SalesAgentTargetController} splits it into the twelve rows of
 * that year. Monthly is the storage grain because it is the only grain that can
 * answer a partial-period question — "what was the target for Aug 1-12" — without
 * every caller re-inventing a phasing assumption. It also leaves room for real
 * seasonality later without a schema change.
 *
 * <p><b>{@code targetValue} may be null, and null is not zero.</b> Null means no
 * target has been set for that month; zero means the target IS zero. Consumers
 * must render the first as "—" and must never classify an agent as
 * underperforming for want of a target. {@code SalesTargetResolver} is the one
 * place that enforces this.
 *
 * <p><b>Tenant-scoped.</b> {@code tenantId} is a hard FK and the table is
 * RLS-protected with the standard {@code tenant_isolation_policy}; the unique key
 * is {@code (tenant_id, sales_user_id, month_key, metric_type)}, so two tenants
 * may hold the same {@code salesUserId} independently.
 *
 * <p>{@code salesUserId} is the rep CODE ({@code dim_merchant.sales_user_id}) —
 * the same key {@link SalesUserAssignment} and {@link SalesAgentProfile} use, so
 * targets join to actuals without a translation step.
 */
@Entity
@Table(name = "sales_agent_target", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "sales_user_id", "month_key", "metric_type" })
})
@Data
public class SalesAgentTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "sales_user_id", nullable = false)
    private String salesUserId;

    /** YYYYMM — same key shape as sum_monthly_bank.month_key. */
    @Column(name = "month_key", nullable = false)
    private Integer monthKey;

    /** Null = no target set for this month. Distinct from a target of zero. */
    @Column(name = "target_value")
    private BigDecimal targetValue;

    /** NET_REVENUE | BASE_VOLUME | VOLUME | MSF | TXNS. Validated in the controller. */
    @Column(name = "metric_type", nullable = false)
    private String metricType;

    /** MANUAL (typed) vs EQUAL/SEASONAL (derived by splitting an annual figure). */
    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (metricType == null) metricType = "NET_REVENUE";
        if (source == null) source = "MANUAL";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
