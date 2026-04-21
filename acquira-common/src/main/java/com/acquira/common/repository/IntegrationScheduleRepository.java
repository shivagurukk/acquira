package com.acquira.common.repository;

import com.acquira.common.model.IntegrationSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface IntegrationScheduleRepository extends JpaRepository<IntegrationSchedule, Long> {
    List<IntegrationSchedule> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    List<IntegrationSchedule> findByIsEnabledTrue();
    List<IntegrationSchedule> findByTenantIdAndIsEnabledTrue(Long tenantId);
    List<IntegrationSchedule> findByReportId(Long reportId);
}
