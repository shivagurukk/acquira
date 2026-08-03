package com.acquira.common.repository;

import com.acquira.common.model.MerchantDailyMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MerchantDailyMetricsRepository extends JpaRepository<MerchantDailyMetrics, Long> {

    /**
     * Returns the most recent report_date that has rows for the given tenant.
     * Used by the /api/business/data-bounds endpoint so the Daily Merchant
     * Dashboard can open on the latest month that actually has data, instead
     * of defaulting to the current calendar month and showing "No rows".
     */
    @Query("SELECT MAX(m.reportDate) FROM MerchantDailyMetrics m WHERE m.tenantId = :tenantId")
    java.time.LocalDate findLatestReportDateByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Tenant-scoped fetch. Closes the cross-tenant leak that existed in the
     * findByReportDate variant (which the DailyMerchantDashboard endpoint was
     * using and would happily return rows from any tenant).
     */
    List<MerchantDailyMetrics> findByReportDateAndTenantId(LocalDate reportDate, Long tenantId);

    /**
     * P0-3 fix: tenant-scoped lookup. The legacy two-arg version omitted
     * tenantId, and `merchantId` here is a bank-assigned internal_id which is
     * NOT unique across tenants — so two banks with overlapping internal_ids
     * would silently overwrite each other's metrics rows. Always use this
     * variant; the unscoped one is removed.
     */
    Optional<MerchantDailyMetrics> findByTenantIdAndMerchantIdAndReportDate(
            Long tenantId, String merchantId, LocalDate reportDate);

    /**
     * P2-9 fix: bulk fetch existing rows for a (tenant, reportDate) so the
     * caller can do an in-memory join and a single batch save instead of
     * N+1 round-trips per merchant per month.
     */
    List<MerchantDailyMetrics> findByTenantIdAndReportDate(
            Long tenantId, LocalDate reportDate);

    // Efficient Deletion for re-runs. Tenant-scoped: the previous variant had no
    // tenant predicate, so one tenant's re-run would have wiped the report date
    // for EVERY tenant.
    @Modifying
    @Transactional
    @Query("DELETE FROM MerchantDailyMetrics m WHERE m.tenantId = :tenantId AND m.reportDate = :reportDate AND m.sourceType = :sourceType")
    void deleteByTenantIdAndReportDateAndSourceType(Long tenantId, LocalDate reportDate,
            MerchantDailyMetrics.SourceType sourceType);
}
