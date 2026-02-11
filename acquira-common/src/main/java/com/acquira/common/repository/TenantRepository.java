package com.acquira.common.repository;

import com.acquira.common.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    java.util.Optional<Tenant> findByInstitutionId(String institutionId);

    java.util.Optional<Tenant> findByBankShortCode(String bankShortCode);
}
