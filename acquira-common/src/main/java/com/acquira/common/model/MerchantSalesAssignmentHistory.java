package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Append-only audit trail of sales-agent reassignment on a merchant.
 *
 * One row per actual change of dim_merchant.sales_user_id, written set-based by
 * MerchantMasterJobConfig.upsertDimensionsTasklet from a snapshot taken BEFORE
 * the upsert that performs the change — so history and current state can never
 * disagree. Rows are never updated or deleted.
 *
 * A merchant's FIRST agent (assigned as part of creating the merchant) is not
 * recorded here: there is no previous holder to audit.
 *
 * @see #source — UPLOAD for a merchant-master file, MANUAL for an admin action,
 *      API for a programmatic call.
 */
@Entity
@Table(name = "merchant_sales_assignment_history")
@Data
public class MerchantSalesAssignmentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Null when the merchant had no agent before this change. */
    @Column(name = "old_sales_user_id")
    private String oldSalesUserId;

    @Column(name = "old_sales_email")
    private String oldSalesEmail;

    @Column(name = "new_sales_user_id", nullable = false)
    private String newSalesUserId;

    @Column(name = "new_sales_email")
    private String newSalesEmail;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    /** Username for a human action; "BATCH:&lt;jobExecutionId&gt;" for an upload. */
    @Column(name = "changed_by")
    private String changedBy;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "upload_file_name")
    private String uploadFileName;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) changedAt = LocalDateTime.now();
        if (source == null) source = "MANUAL";
    }
}
