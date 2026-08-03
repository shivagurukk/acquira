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

        /**
         * Tenant-scoped heatmap. Adds `s.tenantId = :tenantId` AND
         * `m.tenantId = :tenantId` so heatmap rows cannot leak across tenants.
         * (The unscoped variant was removed — it returned every tenant's rows.)
         */
        @Query("SELECT new com.acquira.common.dto.MerchantHeatmapDTO(" +
                        "m.name, m.internalId, EXTRACT(MONTH FROM s.businessDate), SUM(s.totalVolume)) " +
                        "FROM SumDailyMerchant s " +
                        "JOIN com.acquira.common.model.Merchant m ON s.merchantId = m.merchantId " +
                        "WHERE EXTRACT(YEAR FROM s.businessDate) = :year " +
                        "  AND s.tenantId = :tenantId AND m.tenantId = :tenantId " +
                        "GROUP BY m.name, m.internalId, EXTRACT(MONTH FROM s.businessDate) " +
                        "ORDER BY m.name, EXTRACT(MONTH FROM s.businessDate)")
        java.util.List<com.acquira.common.dto.MerchantHeatmapDTO> findMerchantHeatmapDataForTenant(
                        @org.springframework.data.repository.query.Param("year") int year,
                        @org.springframework.data.repository.query.Param("tenantId") Long tenantId);

        @Query("SELECT m FROM SumDailyMerchant m WHERE m.tenantId = :tenantId AND m.businessDate BETWEEN :startDate AND :endDate ORDER BY m.businessDate")
        java.util.List<SumDailyMerchant> findByTenantIdAndDateRange(
                        @org.springframework.data.repository.query.Param("tenantId") Integer tenantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        /**
         * FAST month grid for the Daily Merchant Dashboard. Returns one row per
         * (merchant, day-of-month) with that day's base volume + txns, joined to
         * dim_merchant for identity. Reads from the pre-aggregated, year-partitioned
         * sum_daily_merchant via idx_sum_merch_tenant_date — a single-month query for
         * one tenant prunes to one partition and does an index range scan, so it stays
         * sub-second even with 5 years / many tenants of data.
         *
         * The controller aggregates these rows in-memory into the dashboard DTO
         * (per-day map, month total, today, 7-day average, trend %, status). This
         * replaces the old async merchant_daily_metrics path, which silently emptied
         * recent months when the async reporting step failed or lagged.
         *
         * Columns: [0]=internal_id (String, used as merchantId for filter compat),
         *          [1]=mid, [2]=name, [3]=day-of-month (int), [4]=SUM(base volume),
         *          [5]=SUM(txns)
         */
        @Query("SELECT dm.internalId, dm.mid, dm.name, " +
                        "EXTRACT(DAY FROM s.businessDate), " +
                        "SUM(COALESCE(s.totalBaseVolume, s.totalVolume)), " +
                        "SUM(s.totalTxns) " +
                        "FROM SumDailyMerchant s " +
                        "JOIN com.acquira.common.model.Merchant dm ON dm.merchantId = s.merchantId " +
                        "WHERE s.tenantId = :tenantId AND dm.tenantId = :tenantId " +
                        "  AND s.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY dm.internalId, dm.mid, dm.name, EXTRACT(DAY FROM s.businessDate)")
        java.util.List<Object[]> findDailyMerchantGrid(
                        @org.springframework.data.repository.query.Param("tenantId") Long tenantId,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        // ── BULK QUERIES for batch PDF pre-fetch ──

        @Query("SELECT m FROM SumDailyMerchant m WHERE m.merchantId IN :merchantIds AND m.businessDate BETWEEN :startDate AND :endDate ORDER BY m.merchantId, m.businessDate")
        java.util.List<SumDailyMerchant> findDailyStatsForMerchants(
                        @org.springframework.data.repository.query.Param("merchantIds") java.util.List<Long> merchantIds,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);

        @Query("SELECT new map(" +
                        "m.merchantId as merchantId, " +
                        "EXTRACT(YEAR FROM m.businessDate) as year, " +
                        "EXTRACT(MONTH FROM m.businessDate) as month, " +
                        "SUM(m.totalVolume) as totalVolume, " +
                        "SUM(COALESCE(m.totalBaseVolume, m.totalVolume)) as totalBaseVolume, " +
                        "SUM(m.totalTxns) as totalTxns, " +
                        "SUM(COALESCE(m.uniqueCustomerCount, 0)) as uniqueCustomers, " +
                        "SUM(COALESCE(m.dccEligibleVolume, 0)) as dccEligibleVolume, " +
                        "SUM(COALESCE(m.dccOptinVolume, 0)) as dccOptinVolume, " +
                        "SUM(COALESCE(m.dccOptoutVolume, 0)) as dccOptoutVolume " +
                        ") " +
                        "FROM SumDailyMerchant m " +
                        "WHERE m.merchantId IN :merchantIds AND m.businessDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY m.merchantId, EXTRACT(YEAR FROM m.businessDate), EXTRACT(MONTH FROM m.businessDate) " +
                        "ORDER BY m.merchantId, 2, 3")
        java.util.List<java.util.Map<String, Object>> findMonthlyTrendsForMerchants(
                        @org.springframework.data.repository.query.Param("merchantIds") java.util.List<Long> merchantIds,
                        @org.springframework.data.repository.query.Param("startDate") LocalDate startDate,
                        @org.springframework.data.repository.query.Param("endDate") LocalDate endDate);
}
