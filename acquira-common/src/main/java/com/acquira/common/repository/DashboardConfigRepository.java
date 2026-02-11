package com.acquira.common.repository;

import com.acquira.common.model.DashboardConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DashboardConfigRepository extends JpaRepository<DashboardConfig, Integer> {
    List<DashboardConfig> findByTenant_TenantIdOrderByDisplayOrderAsc(Long tenantId);
}
