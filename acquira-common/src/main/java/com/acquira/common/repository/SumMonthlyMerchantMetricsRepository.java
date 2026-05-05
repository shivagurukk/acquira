package com.acquira.common.repository;

import com.acquira.common.model.SumMonthlyMerchantMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SumMonthlyMerchantMetricsRepository extends JpaRepository<SumMonthlyMerchantMetrics, Long> {

    @Query(value = "SELECT * FROM sum_monthly_merchant_metrics WHERE tenant_id = :tenantId AND merchant_id = :merchantId AND month_year = :monthYear", nativeQuery = true)
    Optional<SumMonthlyMerchantMetrics> findByMerchantAndMonth(Integer tenantId, Long merchantId, String monthYear);

    /**
     * PERF: bulk-fetch all existing monthly-metric rows for a tenant+month in a
     * single round trip, used by TransactionJobConfig.calculateDailyDashboardMetricsTasklet
     * to avoid N+1 lookups against RDS.
     */
    @Query(value = "SELECT * FROM sum_monthly_merchant_metrics WHERE tenant_id = :tenantId AND month_year = :monthYear", nativeQuery = true)
    java.util.List<SumMonthlyMerchantMetrics> findAllByTenantAndMonth(Integer tenantId, String monthYear);
}
