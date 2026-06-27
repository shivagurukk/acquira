package com.acquira.common.repository;

import com.acquira.common.model.ExplorerAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ExplorerAlertRepository extends JpaRepository<ExplorerAlert, Long> {
    List<ExplorerAlert> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    List<ExplorerAlert> findByIsEnabledTrue();
}
