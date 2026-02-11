package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "dashboard_config", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "kpi_key" })
})
@Data
public class DashboardConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer configId;

    @ManyToOne
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "kpi_key", nullable = false)
    private String kpiKey;

    @Column(name = "display_label")
    private String displayLabel;

    @Column(name = "is_visible")
    private Boolean isVisible = true;

    @Column(name = "display_order")
    private Integer displayOrder;

    public Integer getConfigId() {
        return configId;
    }

    public void setConfigId(Integer configId) {
        this.configId = configId;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public String getKpiKey() {
        return kpiKey;
    }

    public void setKpiKey(String kpiKey) {
        this.kpiKey = kpiKey;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public void setDisplayLabel(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public Boolean getIsVisible() {
        return isVisible;
    }

    public void setIsVisible(Boolean isVisible) {
        this.isVisible = isVisible;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }
}
