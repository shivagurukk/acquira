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
}
