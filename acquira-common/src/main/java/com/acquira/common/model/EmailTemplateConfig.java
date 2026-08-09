package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_template_config")
@Data
public class EmailTemplateConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false)
    private TemplateType templateType;

    @Column(name = "subject_template", nullable = false)
    private String subjectTemplate; // "Your {{month}} Statement - {{merchant_name}}"

    @Column(name = "body_html", columnDefinition = "TEXT", nullable = false)
    private String bodyHtml; // Full HTML with {{variable}} placeholders

    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText; // Plain text fallback

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "is_default_for_type")
    private Boolean isDefaultForType = false;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum TemplateType {
        STATEMENT, WELCOME, ALERT, PROMOTION, CUSTOM,
        /**
         * Covering email for a generated PDF report — the message a merchant
         * receives with their Business Insight Report attached. Resolved per
         * tenant by ReportEmailTemplateService; the tenant's template flagged
         * isDefaultForType is the one the PDF batch uses.
         *
         * template_type is VARCHAR(50) with no CHECK constraint, so adding this
         * value needs no migration.
         */
        REPORT_PDF
    }
}
