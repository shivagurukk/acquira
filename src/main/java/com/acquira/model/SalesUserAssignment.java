package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "sales_user_assignment", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "sales_user_id" })
})
@Data
public class SalesUserAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "sales_user_id", nullable = false)
    private String salesUserId;

    @Column(name = "team_lead_id", nullable = false)
    private Long teamLeadId;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @PrePersist
    protected void onCreate() {
        assignedAt = LocalDateTime.now();
    }
}
