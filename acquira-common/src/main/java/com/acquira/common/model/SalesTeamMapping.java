package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "sales_team_mapping", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "team_lead_email" })
})
@Data
public class SalesTeamMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "team_lead_name", nullable = false)
    private String teamLeadName;

    @Column(name = "team_lead_email", nullable = false)
    private String teamLeadEmail;

    // Tier above team lead. Nullable: teams with no country lead roll up to
    // the tenant's default country lead. Integrity checked in the service.
    @Column(name = "country_lead_id")
    private Long countryLeadId;

    @Column(name = "is_default")
    private boolean isDefault;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
