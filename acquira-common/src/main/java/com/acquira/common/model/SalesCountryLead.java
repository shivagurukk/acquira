package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Country Lead — the tier above Team Lead.
 * Hierarchy: Country Lead -> Team Lead -> Sales Agent.
 * Mirrors {@link SalesTeamMapping}; team leads link up via
 * {@code SalesTeamMapping.countryLeadId}.
 */
@Entity
@Table(name = "sales_country_lead", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "country_lead_email" })
})
@Data
public class SalesCountryLead {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "country_lead_name", nullable = false)
    private String countryLeadName;

    @Column(name = "country_lead_email", nullable = false)
    private String countryLeadEmail;

    // Optional ISO 3166-1 alpha-2 (matches ref_country.country_code)
    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "is_default")
    private boolean isDefault;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
