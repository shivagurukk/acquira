package com.acquira.repository;

import com.acquira.model.SumMonthlyBank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SumMonthlyBankRepository extends JpaRepository<SumMonthlyBank, Long> {
    List<SumMonthlyBank> findByTenantIdAndMonthKeyBetween(Long tenantId, Integer startMonth, Integer endMonth);
}
