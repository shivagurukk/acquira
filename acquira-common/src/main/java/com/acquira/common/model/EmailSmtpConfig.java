package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Per-tenant SMTP server configuration used to send merchant statement emails
 * (and password-reset / campaign mail).
 *
 * SECURITY: the {@code password} column stores the SMTP password ENCRYPTED
 * (AES-256-GCM via CryptoService, "enc:v1:" prefixed). It is never persisted
 * in plaintext and never returned to the client \u2014 see SmtpConfigService /
 * EmailSmtpController, which strip it from every response DTO.
 *
 * Multi-tenant: every row carries tenant_id. A tenant may have several configs
 * but at most ONE active at a time ({@code isActive}); SmtpConfigService
 * enforces that invariant on activate.
 *
 * Works with any standard SMTP provider \u2014 Amazon SES (email-smtp.<region>
 * .amazonaws.com:587, STARTTLS), Microsoft 365, SendGrid, a corporate relay,
 * etc. For SES, use the SES-generated SMTP credentials (NOT AWS access keys)
 * and a verified from-address.
 */
@Entity
@Table(name = "email_smtp_config")
@Data
public class EmailSmtpConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "config_name", nullable = false)
    private String configName;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private Integer port = 587;

    private String username;

    /**
     * SMTP password \u2014 STORED ENCRYPTED. Persisted only as an "enc:v1:" token.
     * Service layer encrypts on save and decrypts only in-memory when building
     * the mail sender. Never serialized back to the API client.
     */
    @Column(name = "password", length = 1024)
    private String password;

    @Column(name = "auth_enabled")
    private Boolean authEnabled = true;

    @Column(name = "starttls_enabled")
    private Boolean starttlsEnabled = true;

    @Column(name = "ssl_enabled")
    private Boolean sslEnabled = false;

    @Column(name = "from_address")
    private String fromAddress;

    @Column(name = "from_name")
    private String fromName;

    @Column(name = "reply_to")
    private String replyTo;

    @Column(name = "connection_timeout")
    private Integer connectionTimeout = 10000;

    @Column(name = "read_timeout")
    private Integer readTimeout = 10000;

    @Column(name = "write_timeout")
    private Integer writeTimeout = 10000;

    /** Delay between consecutive sends (ms) \u2014 helps respect provider rate caps. */
    @Column(name = "rate_limit_ms")
    private Integer rateLimitMs = 200;

    @Column(name = "max_retries")
    private Integer maxRetries = 3;

    /** At most one config per tenant may have isActive = true. */
    @Column(name = "is_active")
    private Boolean isActive = false;

    @Column(name = "auto_send_after_batch")
    private Boolean autoSendAfterBatch = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
