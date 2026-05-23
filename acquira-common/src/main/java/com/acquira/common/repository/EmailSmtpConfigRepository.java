package com.acquira.common.repository;

import com.acquira.common.model.EmailSmtpConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailSmtpConfigRepository extends JpaRepository<EmailSmtpConfig, Long> {

    /** All SMTP configs for a tenant, newest first. */
    List<EmailSmtpConfig> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    /** The single active config for a tenant, if one is set. */
    Optional<EmailSmtpConfig> findByTenantIdAndIsActiveTrue(Long tenantId);

    /** All active configs for a tenant \u2014 used to clear the flag before activating another. */
    List<EmailSmtpConfig> findAllByTenantIdAndIsActiveTrue(Long tenantId);
}
