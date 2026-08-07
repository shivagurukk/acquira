package com.acquira.common.repository;

import com.acquira.common.model.MerchantSalesAssignmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MerchantSalesAssignmentHistoryRepository
        extends JpaRepository<MerchantSalesAssignmentHistory, Long> {

    /** Audit trail for one merchant, newest change first. */
    List<MerchantSalesAssignmentHistory> findByTenantIdAndMerchantIdOrderByChangedAtDesc(
            Long tenantId, Long merchantId);

    /** Everything one upload moved — the per-run reassignment report. */
    List<MerchantSalesAssignmentHistory> findByTenantIdAndJobExecutionIdOrderByChangedAtDesc(
            Long tenantId, Long jobExecutionId);
}
