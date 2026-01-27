package com.acquira.repository;

import com.acquira.model.SumDailyChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;

@Repository
public interface SumDailyChannelRepository extends JpaRepository<SumDailyChannel, Long> {
    @Query("SELECT new map(" +
            "c.channel as key, " +
            "SUM(c.totalTxns) as totalTxns, " +
            "SUM(c.totalVolume) as totalVolume, " +
            "SUM(c.totalMsf) as totalMsf, " +
            "SUM(c.totalInterchange) as totalInterchange, " +
            "SUM(c.totalSchemeFee) as totalSchemeFee, " +
            "SUM(c.totalNetRevenue) as totalNetRevenue " +
            ") " +
            "FROM SumDailyChannel c " +
            "WHERE c.tenantId = :tenantId AND c.businessDate BETWEEN :startDate AND :endDate " +
            "GROUP BY c.channel")
    Page<Map<String, Object>> findChannelProfitability(Long tenantId, LocalDate startDate, LocalDate endDate,
            Pageable pageable);
}
