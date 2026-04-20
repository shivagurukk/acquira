package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long logId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    private String actionType;

    @Column(columnDefinition = "TEXT")
    private String details;

    private String ipAddress;

    private LocalDateTime eventTime;

    @Column(name = "username")
    private String username;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "endpoint")
    private String endpoint;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "category")
    private String category;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "duration_ms")
    private Long duration;

    @PrePersist
    protected void onCreate() {
        eventTime = LocalDateTime.now();
    }
}
