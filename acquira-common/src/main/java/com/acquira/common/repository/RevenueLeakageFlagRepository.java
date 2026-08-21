package com.acquira.common.repository;

import com.acquira.common.model.RevenueLeakageFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RevenueLeakageFlagRepository extends JpaRepository<RevenueLeakageFlag, Long> {

    List<RevenueLeakageFlag> findByTenantIdAndStatusOrderByEstMonthlyImpactDesc(Long tenantId, String status);

    long countByTenantIdAndStatus(Long tenantId, String status);
}
