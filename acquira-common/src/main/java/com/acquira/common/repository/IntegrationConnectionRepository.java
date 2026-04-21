package com.acquira.common.repository;

import com.acquira.common.model.IntegrationConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, Long> {
    List<IntegrationConnection> findByTenantIdOrderByNameAsc(Long tenantId);
    List<IntegrationConnection> findByTenantIdAndIsActiveTrue(Long tenantId);
    boolean existsByTenantIdAndNameIgnoreCase(Long tenantId, String name);
}
