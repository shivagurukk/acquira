package com.acquira.repository;

import com.acquira.model.SalesUserAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalesUserAssignmentRepository extends JpaRepository<SalesUserAssignment, Long> {
    List<SalesUserAssignment> findAllByTenantId(Long tenantId);

    List<SalesUserAssignment> findAllByTeamLeadId(Long teamLeadId);

    Optional<SalesUserAssignment> findByTenantIdAndSalesUserId(Long tenantId, String salesUserId);

    void deleteByTeamLeadId(Long teamLeadId);
}
