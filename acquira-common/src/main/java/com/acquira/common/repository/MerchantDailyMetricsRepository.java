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

    // Legacy un-scoped fetch — retained only for backward compat with any
    // pre-existing callers. New callers should use findByReportDateAndTenant.
    List<MerchantDailyMetrics> findByReportDate(LocalDate reportDate);

    /**
     * Tenant-scoped fetch. Closes the cross-tenant leak that existed in the
     * findByReportDate variant (which the DailyMerchantDashboard endpoint was
     * using and would happily return rows from any tenant).
     */
    List<MerchantDailyMetrics> findByReportDateAndTenantId(LocalDate reportDate, Long tenantId);

    Optional<MerchantDailyMetrics> findByMerchantIdAndReportDate(String merchantId, LocalDate reportDate);

    // Efficient Deletion for re-runs
    @Modifying
    @Transactional
    @Query("DELETE FROM MerchantDailyMetrics m WHERE m.reportDate = :reportDate AND m.sourceType = :sourceType")
    void deleteByReportDateAndSourceType(LocalDate reportDate, MerchantDailyMetrics.SourceType sourceType);
}
