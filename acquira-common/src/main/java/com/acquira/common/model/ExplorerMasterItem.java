package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * A governed "master item" for the Data Explorer — a tenant-scoped, shared
 * definition that every user in the tenant can pull into their analysis.
 *
 * Phase 4.x: the primary use is publishing a CALCulated measure (an arithmetic
 * formula over base measures) so it becomes reusable org-wide instead of living
 * only inside one user's saved view. The model is generic enough to also curate
 * DIMENSION / MEASURE items later.
 */
@Entity
@Data
@Table(name = "explorer_master_item")
public class ExplorerMasterItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** CALC | DIMENSION | MEASURE */
    @Column(name = "item_type", nullable = false)
    private String itemType;

    /** Stable key used by the engine (e.g. calc_msf_pct, card_scheme, total_msf). */
    @Column(name = "item_key", nullable = false)
    private String itemKey;

    @Column(nullable = false)
    private String label;

    /** For CALC: the formula text. For curated dim/measure: the underlying field key. */
    @Column(columnDefinition = "TEXT")
    private String definition;

    private String description;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
