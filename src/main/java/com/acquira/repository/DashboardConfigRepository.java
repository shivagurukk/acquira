package com.acquira.repository;

import com.acquira.model.DashboardConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DashboardConfigRepository extends JpaRepository<DashboardConfig, Integer> {
    List<DashboardConfig> findByTenant_TenantIdOrderByDisplayOrderAsc(Long tenantId);
}
