package com.acquira.common.repository;

import com.acquira.common.model.MerchantActivitySummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantActivitySummaryRepository extends JpaRepository<MerchantActivitySummary, Long> {

    // Find latest entry for a merchant
    MerchantActivitySummary findByTenantIdAndMerchantId(Long tenantId, Long merchantId);

    // List by status
    Page<MerchantActivitySummary> findByTenantIdAndStatus(Long tenantId, String status, Pageable pageable);

    Page<MerchantActivitySummary> findByTenantIdInAndStatus(java.util.List<Long> tenantIds, String status,
            Pageable pageable);

    // Count by status
    // Count by status
    long countByTenantIdAndStatus(Long tenantId, String status);

    long countByTenantIdInAndStatus(java.util.List<Long> tenantIds, String status);

    long countByTenantIdAndStatusAndCalcDate(Long tenantId, String status, java.time.LocalDate calcDate);

    long countByTenantIdInAndStatusAndCalcDate(java.util.List<Long> tenantIds, String status,
            java.time.LocalDate calcDate);

    @Query("SELECT MAX(m.calcDate) FROM MerchantActivitySummary m WHERE m.tenantId = :tenantId")
    java.time.LocalDate findMaxCalcDate(Long tenantId);

    /**
     * Latest snapshot date on or before a given date. Used by the Business
     * Dashboard so dormant/new counts anchor to the nearest available
     * snapshot instead of returning 0 when the user's endDate doesn't fall
     * exactly on a calc_date.
     */
    @Query("SELECT MAX(m.calcDate) FROM MerchantActivitySummary m WHERE m.tenantId = :tenantId AND m.calcDate <= :onOrBefore")
    java.time.LocalDate findMaxCalcDateOnOrBefore(Long tenantId, java.time.LocalDate onOrBefore);

    // Zero sales checks (status = ACTIVE but last 7d/30d count is 0? Or use
    // specific query)
    // Actually, "Zero Sales" feature might just look for merchants where
    // last_Xd_cnt = 0

    @Query("SELECT m FROM MerchantActivitySummary m WHERE m.tenantId = :tenantId AND m.last30dCount = 0")
    Page<MerchantActivitySummary> findZeroSales30Days(Long tenantId, Pageable pageable);

    @Query("SELECT m FROM MerchantActivitySummary m WHERE m.tenantId IN :tenantIds AND m.last30dCount = 0")
    Page<MerchantActivitySummary> findZeroSales30DaysCombined(
            @org.springframework.data.repository.query.Param("tenantIds") java.util.List<Long> tenantIds,
            Pageable pageable);

    @Query("SELECT m FROM MerchantActivitySummary m WHERE m.tenantId = :tenantId AND m.last7dCount = 0")
    Page<MerchantActivitySummary> findZeroSales7Days(Long tenantId, Pageable pageable);

    @Query("SELECT m FROM MerchantActivitySummary m WHERE m.tenantId IN :tenantIds AND m.last7dCount = 0")
    Page<MerchantActivitySummary> findZeroSales7DaysCombined(
            @org.springframework.data.repository.query.Param("tenantIds") java.util.List<Long> tenantIds,
            Pageable pageable);
}
