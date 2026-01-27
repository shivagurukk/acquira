package com.acquira.repository;

import com.acquira.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    java.util.Optional<Tenant> findByInstitutionId(String institutionId);

    java.util.Optional<Tenant> findByBankShortCode(String bankShortCode);
}
