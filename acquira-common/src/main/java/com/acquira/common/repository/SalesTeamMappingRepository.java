package com.acquira.common.repository;

import com.acquira.common.model.SalesTeamMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalesTeamMappingRepository extends JpaRepository<SalesTeamMapping, Long> {
    List<SalesTeamMapping> findAllByTenantId(Long tenantId);

    // Teams under a given country lead (used when re-parenting / deleting a country lead).
    List<SalesTeamMapping> findAllByTenantIdAndCountryLeadId(Long tenantId, Long countryLeadId);

    // Teams not yet mapped to any country lead (used by auto-assign-to-default).
    List<SalesTeamMapping> findAllByTenantIdAndCountryLeadIdIsNull(Long tenantId);

    Optional<SalesTeamMapping> findByTenantIdAndIsDefaultTrue(Long tenantId);

    Optional<SalesTeamMapping> findByTenantIdAndTeamLeadEmail(Long tenantId, String teamLeadEmail);
}
