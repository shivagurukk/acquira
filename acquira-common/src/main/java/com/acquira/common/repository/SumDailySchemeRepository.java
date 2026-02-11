package com.acquira.common.repository;

import com.acquira.common.model.SumDailyScheme;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;

@Repository
public interface SumDailySchemeRepository extends JpaRepository<SumDailyScheme, Long> {
    @Query("SELECT new map(" +
            "s.cardScheme as key, " +
            "SUM(s.totalTxns) as totalTxns, " +
            "SUM(s.totalVolume) as totalVolume, " +
            "SUM(s.totalMsf) as totalMsf, " +
            "SUM(s.totalInterchange) as totalInterchange, " +
            "SUM(s.totalSchemeFee) as totalSchemeFee, " +
            "SUM(s.totalNetRevenue) as totalNetRevenue " +
            ") " +
            "FROM SumDailyScheme s " +
            "WHERE s.tenantId = :tenantId AND s.businessDate BETWEEN :startDate AND :endDate " +
            "GROUP BY s.cardScheme")
    Page<Map<String, Object>> findSchemeProfitability(Long tenantId, LocalDate startDate, LocalDate endDate,
            Pageable pageable);
}
