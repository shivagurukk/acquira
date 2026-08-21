package com.acquira.common.repository;

import com.acquira.common.model.SalesAgentProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalesAgentProfileRepository extends JpaRepository<SalesAgentProfile, Long> {
    List<SalesAgentProfile> findAllByTenantId(Long tenantId);

    Optional<SalesAgentProfile> findByTenantIdAndSalesUserId(Long tenantId, String salesUserId);
}
