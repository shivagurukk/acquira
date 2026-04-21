package com.acquira.common.repository;

import com.acquira.common.model.ReportTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, Long> {
    List<ReportTemplate> findByTenantIdAndUserIdOrderByNameAsc(Long tenantId, Long userId);
    List<ReportTemplate> findByTenantIdAndIsSharedTrueOrderByNameAsc(Long tenantId);
    List<ReportTemplate> findByTenantIdOrderByNameAsc(Long tenantId);
}
