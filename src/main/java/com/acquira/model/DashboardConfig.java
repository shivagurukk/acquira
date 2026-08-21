package com.acquira.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;

@Entity
@Table(name = "dashboard_config", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "kpi_key" })
})
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DashboardConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer configId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Tenant tenant;

    @Column(name = "kpi_key", nullable = false)
    private String kpiKey;

    @Column(name = "display_label")
    private String displayLabel;

    @Column(name = "is_visible")
    private Boolean isVisible = true;

    @Column(name = "display_order")
    private Integer displayOrder;
}
