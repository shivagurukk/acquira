package com.acquira.repository;

import com.acquira.dto.GeoMetricDTO;
import com.acquira.model.SumDailyTerminal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SumDailyTerminalRepository extends JpaRepository<SumDailyTerminal, Long> {

    // Check existence for basic validation or deduplication if needed
    boolean existsByTenantIdAndTerminalIdAndBusinessDate(Long tenantId, Long terminalId, LocalDate businessDate);

    // Store Leaderboard: top stores by volume for a merchant
    @Query("SELECT new map(" +
            "s.name as storeName, " +
            "SUM(t.totalVolume) as totalVolume, " +
            "SUM(t.totalTxns) as totalTxns" +
            ") " +
            "FROM SumDailyTerminal t " +
            "JOIN Store s ON t.storeId = s.storeId " +
            "WHERE t.merchantId = :merchantId AND t.businessDate BETWEEN :startDate AND :endDate " +
            "GROUP BY s.name " +
            "ORDER BY SUM(t.totalVolume) DESC")
    List<java.util.Map<String, Object>> findStoreLeaderboard(
            @Param("merchantId") Long merchantId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT new com.acquira.dto.GeoMetricDTO(" +
            "s.name, s.latitude, s.longitude, SUM(t.totalVolume), SUM(t.totalTxns), 'LOW') " +
            "FROM SumDailyTerminal t " +
            "JOIN Store s ON t.storeId = s.storeId " +
            "WHERE t.businessDate = :date " +
            "GROUP BY s.name, s.latitude, s.longitude")
    List<GeoMetricDTO> findGeoMetricsByDate(@Param("date") LocalDate date);
}
