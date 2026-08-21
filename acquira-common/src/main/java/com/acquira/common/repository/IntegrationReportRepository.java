package com.acquira.common.repository;

import com.acquira.common.model.IntegrationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface IntegrationReportRepository extends JpaRepository<IntegrationReport, Long> {
    List<IntegrationReport> findByTenantIdOrderByNameAsc(Long tenantId);
    List<IntegrationReport> findByTenantIdAndReportType(Long tenantId, IntegrationReport.ReportType reportType);
    List<IntegrationReport> findByTenantIdAndIsActiveTrue(Long tenantId);
    List<IntegrationReport> findByTenantIdAndReportTypeAndIsActiveTrue(Long tenantId, IntegrationReport.ReportType reportType);
}
