package com.acquira.common.repository;

import com.acquira.common.model.SumMonthlyBank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SumMonthlyBankRepository extends JpaRepository<SumMonthlyBank, Long> {
    List<SumMonthlyBank> findByTenantIdAndMonthKeyBetween(Long tenantId, Integer startMonth, Integer endMonth);

    /**
     * Latest month_key with an actual row for the tenant. Used by the Budget
     * attainment default range so it never silently compares a target
     * against a month that hasn't been ingested yet (which always reads as
     * 0% / BEHIND and is misleading, not a real attainment signal).
     */
    @org.springframework.data.jpa.repository.Query("SELECT MAX(s.monthKey) FROM SumMonthlyBank s WHERE s.tenantId = :tenantId")
    Integer findMaxMonthKey(@org.springframework.data.repository.query.Param("tenantId") Long tenantId);
}
