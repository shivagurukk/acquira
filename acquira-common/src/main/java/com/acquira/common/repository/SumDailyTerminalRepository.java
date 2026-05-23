package com.acquira.common.repository;

import com.acquira.common.dto.GeoMetricDTO;
import com.acquira.common.model.SumDailyTerminal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SumDailyTerminalRepository extends JpaRepository<SumDailyTerminal, Long> {

    // Check existence for basic validation or deduplication if needed
    boolean existsByTenantIdAndTerminalIdAndBusinessDate(Long tenantId, Long terminalId, LocalDate businessDate);

    /**
     * @deprecated UNSCOPED — leaks geo metrics across ALL tenants. Do not call.
     * Use {@link #findGeoMetricsByDateForTenant(LocalDate, Long)} instead.
     * Retained only so any pre-existing caller still compiles.
     */
    @Deprecated
    @Query("SELECT new com.acquira.common.dto.GeoMetricDTO(" +
            "s.name, s.latitude, s.longitude, SUM(t.totalVolume), SUM(t.totalTxns), 'LOW') " +
            "FROM SumDailyTerminal t " +
            "JOIN Store s ON t.storeId = s.storeId " +
            "WHERE t.businessDate = :date " +
            "GROUP BY s.name, s.latitude, s.longitude")
    List<GeoMetricDTO> findGeoMetricsByDate(@Param("date") LocalDate date);

    /**
     * Tenant-scoped geo metrics. Filters both the fact side (t.tenantId) and the
     * joined Store (s.tenantId) so heatmap markers cannot leak across tenants.
     */
    @Query("SELECT new com.acquira.common.dto.GeoMetricDTO(" +
            "s.name, s.latitude, s.longitude, SUM(t.totalVolume), SUM(t.totalTxns), 'LOW') " +
            "FROM SumDailyTerminal t " +
            "JOIN Store s ON t.storeId = s.storeId " +
            "WHERE t.businessDate = :date " +
            "  AND t.tenantId = :tenantId AND s.tenantId = :tenantId " +
            "GROUP BY s.name, s.latitude, s.longitude")
    List<GeoMetricDTO> findGeoMetricsByDateForTenant(@Param("date") LocalDate date,
                                                     @Param("tenantId") Long tenantId);
}
