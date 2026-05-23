package com.acquira.core.service;

import com.acquira.common.model.EmailSmtpConfig;
import com.acquira.common.repository.EmailSmtpConfigRepository;
import com.acquira.common.service.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Manages per-tenant {@link EmailSmtpConfig} records: CRUD, at-rest password
 * encryption, single-active enforcement, building a JavaMailSender from a
 * config, and connection testing.
 *
 * Encryption: the SMTP password is encrypted with {@link CryptoService}
 * (AES-256-GCM) before persistence and decrypted only in-memory when a
 * JavaMailSender is built. It is NEVER returned to the API client \u2014
 * {@link #stripSecrets(EmailSmtpConfig)} blanks it on every read path.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmtpConfigService {

    private final EmailSmtpConfigRepository repository;
    private final CryptoService cryptoService;

    /** Sentinel the API layer sends back when the user did NOT change the password. */
    public static final String UNCHANGED_PASSWORD_TOKEN = "__UNCHANGED__";

    // ── Read ────────────────────────────────────────────────────────────────

    /** All configs for a tenant, with passwords stripped for safe client display. */
    public List<EmailSmtpConfig> listForTenant(Long tenantId) {
        List<EmailSmtpConfig> configs = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        configs.forEach(this::stripSecrets);
        return configs;
    }

    public Optional<EmailSmtpConfig> getActiveRaw(Long tenantId) {
        return repository.findByTenantIdAndIsActiveTrue(tenantId);
    }

    // ── Create / Update ─────────────────────────────────────────────────────

    @Transactional
    public EmailSmtpConfig create(Long tenantId, EmailSmtpConfig incoming) {
        incoming.setId(null);
        incoming.setTenantId(tenantId);
        // Encrypt the password before it ever touches the DB. encrypt() is
        // idempotent so this is safe even if somehow already encrypted.
        incoming.setPassword(encryptIfPresent(incoming.getPassword()));
        // A brand-new config is never auto-activated; activation is explicit.
        incoming.setIsActive(false);
        EmailSmtpConfig saved = repository.save(incoming);
        log.info("[SMTP] Created config '{}' (id={}) for tenant {}",
                saved.getConfigName(), saved.getId(), tenantId);
        return stripSecrets(saved);
    }

    @Transactional
    public EmailSmtpConfig update(Long tenantId, Long id, EmailSmtpConfig incoming) {
        EmailSmtpConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SMTP config not found: " + id));
        assertTenant(existing, tenantId);

        existing.setConfigName(incoming.getConfigName());
        existing.setHost(incoming.getHost());
        existing.setPort(incoming.getPort());
        existing.setUsername(incoming.getUsername());
        existing.setAuthEnabled(incoming.getAuthEnabled());
        existing.setStarttlsEnabled(incoming.getStarttlsEnabled());
        existing.setSslEnabled(incoming.getSslEnabled());
        existing.setFromAddress(incoming.getFromAddress());
        existing.setFromName(incoming.getFromName());
        existing.setReplyTo(incoming.getReplyTo());
        if (incoming.getConnectionTimeout() != null) existing.setConnectionTimeout(incoming.getConnectionTimeout());
        if (incoming.getReadTimeout() != null) existing.setReadTimeout(incoming.getReadTimeout());
        if (incoming.getWriteTimeout() != null) existing.setWriteTimeout(incoming.getWriteTimeout());
        if (incoming.getRateLimitMs() != null) existing.setRateLimitMs(incoming.getRateLimitMs());
        if (incoming.getMaxRetries() != null) existing.setMaxRetries(incoming.getMaxRetries());
        if (incoming.getAutoSendAfterBatch() != null) existing.setAutoSendAfterBatch(incoming.getAutoSendAfterBatch());

        // Password handling: the client never receives the stored password, so
        // on edit it sends back either a NEW password, or the UNCHANGED token
        // (or blank) meaning "keep what's stored". Only re-encrypt on a real change.
        String incomingPw = incoming.getPassword();
        if (incomingPw != null && !incomingPw.isBlank()
                && !UNCHANGED_PASSWORD_TOKEN.equals(incomingPw)) {
            existing.setPassword(encryptIfPresent(incomingPw));
            log.info("[SMTP] Password updated for config id={}", id);
        }
        // else: leave existing.password as-is.

        EmailSmtpConfig saved = repository.save(existing);
        return stripSecrets(saved);
    }

    @Transactional
    public void delete(Long tenantId, Long id) {
        EmailSmtpConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SMTP config not found: " + id));
        assertTenant(existing, tenantId);
        repository.delete(existing);
        log.info("[SMTP] Deleted config id={} for tenant {}", id, tenantId);
    }

    /**
     * Make one config the active one for the tenant. Clears isActive on every
     * other config first so the "at most one active" invariant always holds.
     */
    @Transactional
    public EmailSmtpConfig activate(Long tenantId, Long id) {
        EmailSmtpConfig target = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SMTP config not found: " + id));
        assertTenant(target, tenantId);

        for (EmailSmtpConfig other : repository.findAllByTenantIdAndIsActiveTrue(tenantId)) {
            if (!other.getId().equals(id)) {
                other.setIsActive(false);
                repository.save(other);
            }
        }
        target.setIsActive(true);
        EmailSmtpConfig saved = repository.save(target);
        log.info("[SMTP] Config id={} is now ACTIVE for tenant {}", id, tenantId);
        return stripSecrets(saved);
    }

    // ── Mail sender construction ────────────────────────────────────────────

    /**
     * Build a JavaMailSender from a stored config. The password is decrypted
     * here and only here, held in memory for the lifetime of the send.
     */
    public JavaMailSenderImpl buildMailSender(EmailSmtpConfig cfg) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.getHost());
        sender.setPort(cfg.getPort() != null ? cfg.getPort() : 587);
        sender.setUsername(cfg.getUsername());
        sender.setPassword(decryptIfPresent(cfg.getPassword()));
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(Boolean.TRUE.equals(cfg.getAuthEnabled())));
        props.put("mail.smtp.starttls.enable", String.valueOf(Boolean.TRUE.equals(cfg.getStarttlsEnabled())));
        if (Boolean.TRUE.equals(cfg.getStarttlsEnabled())) {
            props.put("mail.smtp.starttls.required", "true");
        }
        if (Boolean.TRUE.equals(cfg.getSslEnabled())) {
            // Implicit SSL (typically port 465).
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout",
                String.valueOf(cfg.getConnectionTimeout() != null ? cfg.getConnectionTimeout() : 10000));
        props.put("mail.smtp.timeout",
                String.valueOf(cfg.getReadTimeout() != null ? cfg.getReadTimeout() : 10000));
        props.put("mail.smtp.writetimeout",
                String.valueOf(cfg.getWriteTimeout() != null ? cfg.getWriteTimeout() : 10000));
        return sender;
    }

    /**
     * Test connectivity for a stored config by opening (and closing) an SMTP
     * transport connection. Does NOT send a message. Returns a human-readable
     * result; never throws.
     */
    public TestResult testConnection(Long tenantId, Long id) {
        EmailSmtpConfig cfg = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SMTP config not found: " + id));
        assertTenant(cfg, tenantId);
        try {
            JavaMailSenderImpl sender = buildMailSender(cfg);
            sender.testConnection(); // opens + closes a transport connection
            log.info("[SMTP] Test connection OK for config id={}", id);
            return new TestResult("SUCCESS", "Connected to " + cfg.getHost() + ":" + cfg.getPort());
        } catch (Exception e) {
            log.warn("[SMTP] Test connection FAILED for config id={}: {}", id, e.getMessage());
            return new TestResult("FAILED", e.getMessage());
        }
    }

    public record TestResult(String status, String message) {}

    // ── Internals ───────────────────────────────────────────────────────────

    private String encryptIfPresent(String value) {
        return (value == null || value.isBlank()) ? value : cryptoService.encrypt(value);
    }

    private String decryptIfPresent(String value) {
        return (value == null || value.isBlank()) ? value : cryptoService.decrypt(value);
    }

    /** Blank the password so it is never serialized back to the API client. */
    private EmailSmtpConfig stripSecrets(EmailSmtpConfig cfg) {
        if (cfg != null && cfg.getPassword() != null && !cfg.getPassword().isBlank()) {
            // Signal "a password is set" without revealing it.
            cfg.setPassword(UNCHANGED_PASSWORD_TOKEN);
        }
        return cfg;
    }

    private void assertTenant(EmailSmtpConfig cfg, Long tenantId) {
        if (!cfg.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("SMTP config does not belong to this tenant");
        }
    }
}
