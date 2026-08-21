package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "report_schedule")
@Data
public class ReportSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "template_id", nullable = false)
    private ReportTemplate template;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(name = "frequency_label")
    private String frequencyLabel;

    @Column(name = "timezone")
    private String timezone = "UTC";

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method")
    private DeliveryMethod deliveryMethod = DeliveryMethod.EMAIL;

    @Column(name = "recipient_emails", columnDefinition = "TEXT")
    private String recipientEmails; // comma-separated

    @Enumerated(EnumType.STRING)
    @Column(name = "export_format")
    private ExportFormat exportFormat = ExportFormat.EXCEL;

    @Column(name = "is_enabled")
    private Boolean isEnabled = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum DeliveryMethod { EMAIL, DOWNLOAD_ONLY }
    public enum ExportFormat { PDF, EXCEL, CSV }
}
