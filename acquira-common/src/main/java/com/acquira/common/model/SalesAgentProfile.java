package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Sales Agent profile.
 *
 * An "agent" is a distinct {@code dim_merchant.sales_user_id}. This table is
 * the single reconciliation point between the rep CODE ({@code salesUserId},
 * the key used by {@link SalesUserAssignment} and team rollups) and the EMAIL
 * ({@code salesEmail}, the key used by the leaderboard queries). The email is
 * auto-populated from dim_merchant during sync; the remaining fields are
 * admin-entered and are never overwritten by sync.
 */
@Entity
@Table(name = "sales_agent_profile", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "sales_user_id" })
})
@Data
public class SalesAgentProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "sales_user_id", nullable = false)
    private String salesUserId;

    // Auto-populated from dim_merchant.sales_email — not admin-editable.
    @Column(name = "sales_email")
    private String salesEmail;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "monthly_target")
    private BigDecimal monthlyTarget;

    @Column(name = "status")
    private String status;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
