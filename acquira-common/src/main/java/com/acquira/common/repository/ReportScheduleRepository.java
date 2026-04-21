package com.acquira.common.repository;

import com.acquira.common.model.ReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, Long> {
    List<ReportSchedule> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    List<ReportSchedule> findByIsEnabledTrue();
    List<ReportSchedule> findByTemplateId(Long templateId);
}
