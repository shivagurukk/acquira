package com.acquira.repository;

import com.acquira.model.SumMonthlyMerchantMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SumMonthlyMerchantMetricsRepository extends JpaRepository<SumMonthlyMerchantMetrics, Long> {

    @Query(value = "SELECT * FROM sum_monthly_merchant_metrics WHERE tenant_id = :tenantId AND merchant_id = :merchantId AND month_year = :monthYear", nativeQuery = true)
    Optional<SumMonthlyMerchantMetrics> findByMerchantAndMonth(Integer tenantId, Long merchantId, String monthYear);
}
