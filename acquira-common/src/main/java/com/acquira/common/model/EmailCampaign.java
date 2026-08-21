package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_campaign")
@Data
public class EmailCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "template_id", nullable = false)
    private EmailTemplateConfig template;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false)
    private CampaignType campaignType;

    /**
     * JSON filter for selecting recipients:
     * {"status":["ACTIVE"],"city":["Dubai","Riyadh"],"min_volume":1000}
     * null = ALL merchants
     */
    @Column(name = "recipient_filter_json", columnDefinition = "TEXT")
    private String recipientFilterJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "attachment_type")
    private AttachmentType attachmentType = AttachmentType.NONE;

    @Column(name = "attachment_report_template_id")
    private Long attachmentReportTemplateId; // FK to report_template

    @Column(name = "statement_month")
    private String statementMonth; // YYYY-MM for statement-type campaigns

    @Column(name = "schedule_cron")
    private String scheduleCron;

    @Column(name = "schedule_timezone")
    private String scheduleTimezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "total_recipients")
    private Integer totalRecipients = 0;

    @Column(name = "sent_count")
    private Integer sentCount = 0;

    @Column(name = "failed_count")
    private Integer failedCount = 0;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum CampaignType { BULK, TARGETED, SCHEDULED }
    public enum AttachmentType { NONE, STATEMENT_PDF, CUSTOM_REPORT }
    public enum Status { DRAFT, SCHEDULED, SENDING, COMPLETED, PAUSED, FAILED }
}
