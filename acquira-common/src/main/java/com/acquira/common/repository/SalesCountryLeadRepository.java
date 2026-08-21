package com.acquira.common.repository;

import com.acquira.common.model.SalesCountryLead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalesCountryLeadRepository extends JpaRepository<SalesCountryLead, Long> {
    List<SalesCountryLead> findAllByTenantId(Long tenantId);

    Optional<SalesCountryLead> findByTenantIdAndIsDefaultTrue(Long tenantId);

    Optional<SalesCountryLead> findByTenantIdAndCountryLeadEmail(Long tenantId, String countryLeadEmail);
}
