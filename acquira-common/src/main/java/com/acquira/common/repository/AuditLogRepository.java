package com.acquira.common.repository;

import com.acquira.common.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    List<AuditLog> findByTenantId(Long tenantId);

    /**
     * Fill in the HTTP outcome for rows a controller wrote before the status was
     * known. Guarded on {@code statusCode IS NULL} so an explicitly recorded
     * outcome is never overwritten.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AuditLog a SET a.statusCode = :statusCode, a.duration = :durationMs "
            + "WHERE a.logId IN :logIds AND a.statusCode IS NULL")
    int applyOutcome(@Param("logIds") List<Long> logIds,
                     @Param("statusCode") Integer statusCode,
                     @Param("durationMs") Long durationMs);

    /** Distinct users active since a point in time, across all tenants. */
    @Query("SELECT COUNT(DISTINCT a.username) FROM AuditLog a "
            + "WHERE a.eventTime >= :since AND a.username IS NOT NULL")
    long countActiveUsersSince(@Param("since") LocalDateTime since);

    /** Distinct users active since a point in time, within one tenant. */
    @Query("SELECT COUNT(DISTINCT a.username) FROM AuditLog a "
            + "WHERE a.eventTime >= :since AND a.username IS NOT NULL AND a.tenantId = :tenantId")
    long countActiveUsersSinceForTenant(@Param("since") LocalDateTime since,
                                        @Param("tenantId") Long tenantId);
}
