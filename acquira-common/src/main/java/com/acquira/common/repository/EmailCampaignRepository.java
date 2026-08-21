package com.acquira.common.repository;

import com.acquira.common.model.EmailCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface EmailCampaignRepository extends JpaRepository<EmailCampaign, Long> {
    List<EmailCampaign> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    List<EmailCampaign> findByTenantIdAndStatus(Long tenantId, EmailCampaign.Status status);
}
