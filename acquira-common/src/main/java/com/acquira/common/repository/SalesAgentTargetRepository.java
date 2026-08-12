package com.acquira.common.repository;

import com.acquira.common.model.SalesAgentTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Targets per agent per month.
 *
 * Every finder is tenant-scoped by signature — there is deliberately no
 * "findByMonthKey" without a tenant, so a caller cannot accidentally read across
 * tenants even in a context where RLS is not applied.
 *
 * Month windows are expressed as inclusive YYYYMM bounds. Because month_key is
 * an integer of that shape, BETWEEN over it is only contiguous WITHIN a year
 * (202601..202612); a window spanning a year boundary is handled by
 * {@link #findByTenantIdAndMonthKeyIn} with an explicit key list, which the
 * resolver builds. Callers must not hand-roll a cross-year BETWEEN.
 */
@Repository
public interface SalesAgentTargetRepository extends JpaRepository<SalesAgentTarget, Long> {

    List<SalesAgentTarget> findByTenantIdAndSalesUserId(Long tenantId, String salesUserId);

    Optional<SalesAgentTarget> findByTenantIdAndSalesUserIdAndMonthKeyAndMetricType(
            Long tenantId, String salesUserId, Integer monthKey, String metricType);

    /** Every agent's targets for an explicit list of months — the resolver's read path. */
    List<SalesAgentTarget> findByTenantIdAndMonthKeyIn(Long tenantId, List<Integer> monthKeys);

    /** One agent's targets for an explicit list of months. */
    List<SalesAgentTarget> findByTenantIdAndSalesUserIdAndMonthKeyIn(
            Long tenantId, String salesUserId, List<Integer> monthKeys);

    /** The whole tenant's grid for one calendar year — the admin page's read path. */
    @Query("SELECT t FROM SalesAgentTarget t WHERE t.tenantId = :tenantId "
         + "AND t.monthKey >= :yearStart AND t.monthKey <= :yearEnd "
         + "ORDER BY t.salesUserId, t.monthKey")
    List<SalesAgentTarget> findYear(@Param("tenantId") Long tenantId,
                                    @Param("yearStart") Integer yearStart,
                                    @Param("yearEnd") Integer yearEnd);

    /**
     * Clears one agent's year before a re-save. Bounds are within a single year,
     * so an integer BETWEEN is contiguous here.
     */
    @Modifying
    @Query("DELETE FROM SalesAgentTarget t WHERE t.tenantId = :tenantId "
         + "AND t.salesUserId = :salesUserId AND t.metricType = :metricType "
         + "AND t.monthKey >= :yearStart AND t.monthKey <= :yearEnd")
    int deleteYearForAgent(@Param("tenantId") Long tenantId,
                           @Param("salesUserId") String salesUserId,
                           @Param("metricType") String metricType,
                           @Param("yearStart") Integer yearStart,
                           @Param("yearEnd") Integer yearEnd);

    /** Whether this tenant has configured any target at all — drives "targetsConfigured". */
    boolean existsByTenantId(Long tenantId);
}
