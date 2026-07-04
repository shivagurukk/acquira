package com.acquira.common.repository;

import com.acquira.common.model.BankBudgetTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankBudgetTargetRepository extends JpaRepository<BankBudgetTarget, Long> {

    List<BankBudgetTarget> findByTenantIdAndMonthKeyBetween(Long tenantId, Integer startMonth, Integer endMonth);

    List<BankBudgetTarget> findByTenantIdAndMonthKey(Long tenantId, Integer monthKey);

    Optional<BankBudgetTarget> findByTenantIdAndMonthKeyAndMetricType(Long tenantId, Integer monthKey, String metricType);

    List<BankBudgetTarget> findByTenantIdOrderByMonthKeyDesc(Long tenantId);
}
