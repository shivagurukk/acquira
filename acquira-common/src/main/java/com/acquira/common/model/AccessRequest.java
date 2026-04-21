package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "access_request")
@Data
public class AccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long requestId;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "sso_provider")
    private String ssoProvider;

    @Column(name = "sso_id")
    private String ssoId;

    @Column(name = "requested_tenant_id")
    private Integer requestedTenantId;

    private String message;

    @Column(name = "status")
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes")
    private String reviewNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
