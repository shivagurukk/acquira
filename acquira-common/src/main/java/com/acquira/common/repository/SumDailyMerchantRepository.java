package com.acquira.common.repository;

import com.acquira.common.model.SumDailyMerchant;
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

        // Combined View Version
        @Query("SELECT new map(" +
                        "m.merchantId as key, " +
                        "m.merchant.name as name, " +
                        "SUM(m.totalTxns) as totalTxns, " +
                        "SUM(m.totalVolume) as totalVolume, " +
                        "SUM(m.totalMsf) as totalMsf, " +
                        "SUM(m.totalInterchange) as totalInterchange, " +
                        "SUM(m.totalSchemeFee) as totalSchemeFee, " +
                        "SUM(m.totalMargin) as totalNetRevenue " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.tenantId IN :tenantIds AND m.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY m.merchantId, m.merchant.name")
        Page<java.util.Map<String, Object>> findMerchantProfitabilityCombined(
                        @org.springframework.data.repository.query.Param("tenantIds") java.util.List<Long> tenantIds,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate,
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

        // Combined View Version
        @Query("SELECT new map(" +
                        "m.merchantId as merchantId, " +
                        "m.merchant.name as merchantName, " +
                        "SUM(m.totalMargin) as netRevenue, " +
                        "SUM(m.totalVolume) as totalVolume " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.tenantId IN :tenantIds AND m.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY m.merchantId, m.merchant.name " +
                        "HAVING SUM(m.totalMargin) < 0")
        Page<java.util.Map<String, Object>> findLossMakingMerchantsCombined(
                        @org.springframework.data.repository.query.Param("tenantIds") java.util.List<Long> tenantIds,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate,
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

        // Combined View Version
        @Query("SELECT new map(" +
                        "m.merchantId as merchantId, " +
                        "m.merchant.name as merchantName, " +
                        "SUM(m.totalMargin) as netRevenue, " +
                        "SUM(m.totalVolume) as totalVolume " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.tenantId IN :tenantIds AND m.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY m.merchantId, m.merchant.name " +
                        "HAVING SUM(m.totalVolume) >= :minVolume " +
                        "AND (SUM(m.totalMargin) * 100.0 / NULLIF(SUM(m.totalVolume), 0)) <= :maxMarginPct")
        Page<java.util.Map<String, Object>> findHighVolumeLowMarginCombined(
                        @org.springframework.data.repository.query.Param("tenantIds") java.util.List<Long> tenantIds,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate,
                        java.math.BigDecimal minVolume, java.math.BigDecimal maxMarginPct, Pageable pageable);

        // --- New Aggregation Methods for PDF Service Refactor ---

        @Query("SELECT new map(" +
                        "SUM(m.totalTxns) as total_txns, " +
                        "SUM(m.totalVolume) as total_sales, " +
                        "SUM(COALESCE(m.uniqueCustomerCount, 0)) as unique_customers " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.merchantId = :merchantId AND m.businessDate BETWEEN :startDate AND :endDate")
        java.util.Map<String, Object> getAggregates(
                        @org.springframework.data.repository.query.Param("merchantId") Long merchantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        @Query("SELECT MAX(m.totalVolume) FROM SumDailyMerchant m WHERE m.merchantId = :merchantId AND m.businessDate BETWEEN :startDate AND :endDate")
        java.math.BigDecimal findMaxDailySales(
                        @org.springframework.data.repository.query.Param("merchantId") Long merchantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        @Query("SELECT MAX(m.totalTxns) FROM SumDailyMerchant m WHERE m.merchantId = :merchantId AND m.businessDate BETWEEN :startDate AND :endDate")
        Long findMaxDailyTxns(@org.springframework.data.repository.query.Param("merchantId") Long merchantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        @Query("SELECT MAX(m.topSpendingAmount) FROM SumDailyMerchant m WHERE m.merchantId = :merchantId AND m.businessDate BETWEEN :startDate AND :endDate")
        java.math.BigDecimal findMaxTopSpendingAmount(
                        @org.springframework.data.repository.query.Param("merchantId") Long merchantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        // Daily Listing for Charts
        @Query("SELECT m FROM SumDailyMerchant m WHERE m.merchantId = :merchantId AND m.businessDate BETWEEN :startDate AND :endDate ORDER BY m.businessDate")
        java.util.List<SumDailyMerchant> findDailyStats(
                        @org.springframework.data.repository.query.Param("merchantId") Long merchantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        // Monthly Aggregation for Trends
        @Query("SELECT new map(" +
                        "EXTRACT(YEAR FROM m.businessDate) as year, " +
                        "EXTRACT(MONTH FROM m.businessDate) as month, " +
                        "SUM(m.totalVolume) as totalVolume, " +
                        "SUM(COALESCE(m.totalBaseVolume, m.totalVolume)) as totalBaseVolume, " +
                        "SUM(m.totalTxns) as totalTxns, " +
                        "SUM(COALESCE(m.uniqueCustomerCount, 0)) as uniqueCustomers, " +
                        // DCC Metrics
                        "SUM(COALESCE(m.dccEligibleVolume, 0)) as dccEligibleVolume, " +
                        "SUM(COALESCE(m.dccOptinVolume, 0)) as dccOptinVolume, " +
                        "SUM(COALESCE(m.dccOptoutVolume, 0)) as dccOptoutVolume " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.merchantId = :merchantId AND m.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY EXTRACT(YEAR FROM m.businessDate), EXTRACT(MONTH FROM m.businessDate) " +
                        "ORDER BY 1, 2")
        java.util.List<java.util.Map<String, Object>> findMonthlyTrends(
                        @org.springframework.data.repository.query.Param("merchantId") Long merchantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        @Query("SELECT new com.acquira.common.dto.MerchantHeatmapDTO(" +
                        "m.name, m.internalId, EXTRACT(MONTH FROM s.businessDate), SUM(s.totalVolume)) " +
                        "FROM SumDailyMerchant s " +
                        "JOIN com.acquira.common.model.Merchant m ON s.merchantId = m.merchantId " +
                        "WHERE EXTRACT(YEAR FROM s.businessDate) = :year " +
                        "GROUP BY m.name, m.internalId, EXTRACT(MONTH FROM s.businessDate) " +
                        "ORDER BY m.name, EXTRACT(MONTH FROM s.businessDate)")
        java.util.List<com.acquira.common.dto.MerchantHeatmapDTO> findMerchantHeatmapData(
                        @org.springframework.data.repository.query.Param("year") int year);

        @Query("SELECT m FROM SumDailyMerchant m WHERE m.tenantId = :tenantId AND m.businessDate BETWEEN :startDate AND :endDate ORDER BY m.businessDate")
        java.util.List<SumDailyMerchant> findByTenantIdAndDateRange(
                        @org.springframework.data.repository.query.Param("tenantId") Integer tenantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);
}
