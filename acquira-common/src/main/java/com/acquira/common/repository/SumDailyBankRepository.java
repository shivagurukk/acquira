package com.acquira.common.repository;

import com.acquira.common.model.SumDailyBank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SumDailyBankRepository extends JpaRepository<SumDailyBank, Long> {
    List<SumDailyBank> findByTenantIdAndBusinessDateBetween(Long tenantId, LocalDate startDate, LocalDate endDate);

    List<SumDailyBank> findByTenantIdAndBusinessDate(Long tenantId, LocalDate businessDate);

    @Query("SELECT SUM(s.totalVolume) FROM SumDailyBank s WHERE s.tenantId = :tenantId AND s.businessDate BETWEEN :startDate AND :endDate")
    java.math.BigDecimal sumVolumeByTenantAndDateRange(Long tenantId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT SUM(s.totalNetRevenue) FROM SumDailyBank s WHERE s.tenantId = :tenantId AND s.businessDate BETWEEN :startDate AND :endDate")
    java.math.BigDecimal sumRevenueByTenantAndDateRange(Long tenantId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT SUM(s.totalTxns) FROM SumDailyBank s WHERE s.tenantId = :tenantId AND s.businessDate BETWEEN :startDate AND :endDate")
    Long sumTxnsByTenantAndDateRange(Long tenantId, LocalDate startDate, LocalDate endDate);

    List<SumDailyBank> findByTenantIdAndBusinessDateBetweenOrderByBusinessDateAsc(Long tenantId, LocalDate startDate,
            LocalDate endDate);
}
