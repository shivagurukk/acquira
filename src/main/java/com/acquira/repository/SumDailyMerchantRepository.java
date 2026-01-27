package com.acquira.repository;

import com.acquira.model.SumDailyMerchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface SumDailyMerchantRepository extends JpaRepository<SumDailyMerchant, Long> {

        // Profitability Breakdown by Merchant (Aggregated over time range)
        // We need to group by merchant_id and sum values.
        // Since JPA Repository methods return entities, we need a custom query
        // returning a Projection or DTO.
        // Or we rely on the Controller to aggregate if volume is low, BUT requirement
        // says "Performance < 2s".
        // So DB aggregation is best.

        @Query("SELECT new map(" +
                        "m.merchantId as key, " +
                        "m.merchant.name as name, " + // Corrected field Name
                        "SUM(m.totalTxns) as totalTxns, " +
                        "SUM(m.totalVolume) as totalVolume, " +
                        "SUM(m.totalMsf) as totalMsf, " +
                        "SUM(m.totalInterchange) as totalInterchange, " +
                        "SUM(m.totalSchemeFee) as totalSchemeFee, " +
                        "SUM(m.totalMargin) as totalNetRevenue " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.tenantId = :tenantId AND m.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY m.merchantId, m.merchant.name")
        Page<java.util.Map<String, Object>> findMerchantProfitability(Long tenantId, LocalDate startDate,
                        LocalDate endDate,
                        Pageable pageable);

        // List Loss Making Merchants
        @Query("SELECT new map(" +
                        "m.merchantId as merchantId, " +
                        "m.merchant.name as merchantName, " + // Corrected field Name
                        "SUM(m.totalMargin) as netRevenue, " +
                        "SUM(m.totalVolume) as totalVolume " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.tenantId = :tenantId AND m.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY m.merchantId, m.merchant.name " +
                        "HAVING SUM(m.totalMargin) < 0")
        Page<java.util.Map<String, Object>> findLossMakingMerchants(Long tenantId, LocalDate startDate,
                        LocalDate endDate,
                        Pageable pageable);

        // High Volume Low Margin
        @Query("SELECT new map(" +
                        "m.merchantId as merchantId, " +
                        "m.merchant.name as merchantName, " + // Corrected field Name
                        "SUM(m.totalMargin) as netRevenue, " +
                        "SUM(m.totalVolume) as totalVolume " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.tenantId = :tenantId AND m.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY m.merchantId, m.merchant.name " +
                        "HAVING SUM(m.totalVolume) >= :minVolume " +
                        "AND (SUM(m.totalMargin) * 100.0 / NULLIF(SUM(m.totalVolume), 0)) <= :maxMarginPct")
        Page<java.util.Map<String, Object>> findHighVolumeLowMargin(Long tenantId, LocalDate startDate,
                        LocalDate endDate,
                        java.math.BigDecimal minVolume, java.math.BigDecimal maxMarginPct, Pageable pageable);
}
