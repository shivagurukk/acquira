package com.acquira.common.repository;

import com.acquira.common.model.ReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, Long> {
    List<ReportSchedule> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    // Cross-tenant by design: the scheduler iterates all enabled schedules and
    // re-scopes per row via the schedule's own tenantId.
    List<ReportSchedule> findByIsEnabledTrue();
}
