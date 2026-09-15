package com.acquira.common.repository;

import com.acquira.common.model.IntegrationRunLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IntegrationRunLogRepository extends JpaRepository<IntegrationRunLog, Long> {

    Page<IntegrationRunLog> findByTenantIdOrderByStartTimeDesc(Long tenantId, Pageable pageable);

    @Query("SELECT r FROM IntegrationRunLog r WHERE r.tenantId = :tenantId " +
           "AND (:status IS NULL OR r.status = :status) " +
           "AND (:reportId IS NULL OR r.report.id = :reportId) " +
           "ORDER BY r.startTime DESC")
    Page<IntegrationRunLog> findFiltered(Long tenantId, IntegrationRunLog.Status status, Long reportId, Pageable pageable);

    List<IntegrationRunLog> findByTenantIdAndStatusOrderByStartTimeDesc(Long tenantId, IntegrationRunLog.Status status);

    @Query("SELECT COUNT(r) FROM IntegrationRunLog r WHERE r.tenantId = :tenantId AND r.startTime >= :since")
    long countRunsSince(Long tenantId, LocalDateTime since);

    @Query("SELECT COUNT(r) FROM IntegrationRunLog r WHERE r.tenantId = :tenantId AND r.status = 'SUCCESS' AND r.startTime >= :since")
    long countSuccessRunsSince(Long tenantId, LocalDateTime since);

    @Query("SELECT COUNT(r) FROM IntegrationRunLog r WHERE r.tenantId = :tenantId AND r.status = 'FAILED' AND r.startTime >= :since")
    long countFailedRunsSince(Long tenantId, LocalDateTime since);

    List<IntegrationRunLog> findTop10ByTenantIdOrderByStartTimeDesc(Long tenantId);

    List<IntegrationRunLog> findTop5ByScheduleIdOrderByStartTimeDesc(Long scheduleId);

    /** Feed-health window: every run for the tenant since a cutoff, newest first. */
    List<IntegrationRunLog> findByTenantIdAndStartTimeAfterOrderByStartTimeDesc(Long tenantId, LocalDateTime after);

    /** Most recent run of a given status for a report (e.g. last SUCCESS ever, beyond the health window). */
    IntegrationRunLog findFirstByReportIdAndStatusOrderByStartTimeDesc(Long reportId, IntegrationRunLog.Status status);

    /**
     * Hard-delete support: run history is kept, but its FK to the deleted
     * report/schedule is nulled so the parent row can actually be removed
     * (the schema declares plain REFERENCES with no ON DELETE clause).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE integration_run_log SET report_id = NULL WHERE report_id = :reportId", nativeQuery = true)
    int detachReport(@org.springframework.data.repository.query.Param("reportId") Long reportId);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE integration_run_log SET schedule_id = NULL WHERE schedule_id = :scheduleId", nativeQuery = true)
    int detachSchedule(@org.springframework.data.repository.query.Param("scheduleId") Long scheduleId);
}
