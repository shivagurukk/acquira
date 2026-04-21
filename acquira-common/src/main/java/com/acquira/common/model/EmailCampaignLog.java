package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_campaign_log")
@Data
public class EmailCampaignLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "recipient_email")
    private String recipientEmail;

    @Column(name = "subject_rendered")
    private String subjectRendered;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Status {
        PENDING, SENDING, SENT, FAILED, RETRYING
    }
}
