package com.acquira.common.repository;

import com.acquira.common.model.EmailTemplateConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailTemplateConfigRepository extends JpaRepository<EmailTemplateConfig, Long> {
    List<EmailTemplateConfig> findByTenantIdOrderByNameAsc(Long tenantId);
    List<EmailTemplateConfig> findByTenantIdAndTemplateType(Long tenantId, EmailTemplateConfig.TemplateType type);
    List<EmailTemplateConfig> findByTenantIdAndIsActiveTrue(Long tenantId);
    Optional<EmailTemplateConfig> findByTenantIdAndTemplateTypeAndIsDefaultForTypeTrue(Long tenantId, EmailTemplateConfig.TemplateType type);
}
