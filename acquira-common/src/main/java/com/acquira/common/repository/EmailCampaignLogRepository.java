package com.acquira.common.repository;

import com.acquira.common.model.EmailCampaignLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailCampaignLogRepository extends JpaRepository<EmailCampaignLog, Long> {
    // Tenant-isolation: campaign_id is a GLOBAL sequence, so a campaign-id-only
    // query returns another tenant's logs for a guessed id. Every finder that
    // takes a campaign id also pins the tenant; the unscoped campaign-id variants
    // were removed so a future caller cannot reintroduce the leak.
    Page<EmailCampaignLog> findByTenantIdAndCampaignIdOrderBySentAtDesc(Long tenantId, Long campaignId, Pageable pageable);
    Page<EmailCampaignLog> findByTenantIdOrderBySentAtDesc(Long tenantId, Pageable pageable);
    long countByTenantIdAndCampaignIdAndStatus(Long tenantId, Long campaignId, EmailCampaignLog.Status status);

    @Query("SELECT COUNT(l) FROM EmailCampaignLog l WHERE l.tenantId = :tenantId AND l.campaignId = :campaignId AND l.status = 'SENT'")
    long countSent(Long tenantId, Long campaignId);

    @Query("SELECT COUNT(l) FROM EmailCampaignLog l WHERE l.tenantId = :tenantId AND l.campaignId = :campaignId AND l.status = 'FAILED'")
    long countFailed(Long tenantId, Long campaignId);
}
