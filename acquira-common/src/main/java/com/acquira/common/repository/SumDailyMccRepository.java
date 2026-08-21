package com.acquira.common.repository;

import com.acquira.common.model.SumDailyMcc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;

@Repository
public interface SumDailyMccRepository extends JpaRepository<SumDailyMcc, Long> {

    @Query("SELECT new map(" +
            "m.mcc as key, " +
            "SUM(m.totalTxns) as totalTxns, " +
            "SUM(m.totalVolume) as totalVolume, " +
            "SUM(m.totalMsf) as totalMsf, " +
            "0.0 as totalInterchange, " + // SumDailyMcc didn't implement Interchange yet? schema.sql has it. Let me
                                          // check SumDailyMcc entity.
            "SUM(m.totalSchemeFee) as totalSchemeFee, " +
            "SUM(m.totalNetRevenue) as totalNetRevenue " +
            ") " +
            "FROM SumDailyMcc m " +
            "WHERE m.tenantId = :tenantId AND m.businessDate BETWEEN :startDate AND :endDate " +
            "GROUP BY m.mcc")
    Page<Map<String, Object>> findMccProfitability(Long tenantId, LocalDate startDate, LocalDate endDate,
            Pageable pageable);
}
