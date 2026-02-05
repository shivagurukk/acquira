package com.acquira.repository;

import com.acquira.model.MerchantDailyMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MerchantDailyMetricsRepository extends JpaRepository<MerchantDailyMetrics, Long> {

    // UI Fetch
    List<MerchantDailyMetrics> findByReportDate(LocalDate reportDate);

    Optional<MerchantDailyMetrics> findByMerchantIdAndReportDate(String merchantId, LocalDate reportDate);

    // Efficient Deletion for re-runs
    @Modifying
    @Transactional
    @Query("DELETE FROM MerchantDailyMetrics m WHERE m.reportDate = :reportDate AND m.sourceType = :sourceType")
    void deleteByReportDateAndSourceType(LocalDate reportDate, MerchantDailyMetrics.SourceType sourceType);
}
