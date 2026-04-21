package com.acquira.common.repository;

import com.acquira.common.model.EmailCampaignLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailCampaignLogRepository extends JpaRepository<EmailCampaignLog, Long> {
    Page<EmailCampaignLog> findByCampaignIdOrderBySentAtDesc(Long campaignId, Pageable pageable);
    Page<EmailCampaignLog> findByTenantIdOrderBySentAtDesc(Long tenantId, Pageable pageable);
    long countByCampaignIdAndStatus(Long campaignId, EmailCampaignLog.Status status);

    @Query("SELECT COUNT(l) FROM EmailCampaignLog l WHERE l.campaignId = :campaignId AND l.status = 'SENT'")
    long countSent(Long campaignId);

    @Query("SELECT COUNT(l) FROM EmailCampaignLog l WHERE l.campaignId = :campaignId AND l.status = 'FAILED'")
    long countFailed(Long campaignId);
}
