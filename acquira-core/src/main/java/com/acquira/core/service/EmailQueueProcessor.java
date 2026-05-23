package com.acquira.core.service;

import com.acquira.common.service.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Processes the {@code email_queue} table — asynchronous, retryable email
 * delivery.
 *
 * Polls every 60 seconds for PENDING rows and sends each via the relevant
 * tenant's active SMTP config ({@code email_smtp_config}). On failure a row's
 * retry_count is incremented; once it reaches MAX_RETRIES the row is marked
 * FAILED.
 *
 * SMTP RESOLUTION: the sender is built per row from {@code email_smtp_config}
 * (the active config for the row's tenant). The stored SMTP password is
 * AES-256-GCM encrypted and is decrypted here via {@link CryptoService}. This
 * keeps the queue consistent with EmailService / CampaignExecutionService —
 * all three now use the same encryption-aware, per-tenant SMTP source.
 *
 * Senders are cached per tenant for the duration of one poll cycle so a batch
 * of emails for the same tenant doesn't rebuild the sender each time.
 */
@Service
public class EmailQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmailQueueProcessor.class);
    private static final int MAX_RETRIES = 3;
    private static final int BATCH_SIZE = 10;

    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;

    public EmailQueueProcessor(JdbcTemplate jdbcTemplate, CryptoService cryptoService) {
        this.jdbc = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    @Scheduled(fixedDelay = 60000) // Every 60 seconds
    public void processQueue() {
        List<Map<String, Object>> pending;
        try {
            pending = jdbc.queryForList(
                "SELECT id, recipient, subject, body, is_html, attachment_path, retry_count, tenant_id " +
                "FROM email_queue WHERE status = 'PENDING' AND retry_count < ? " +
                "ORDER BY created_at LIMIT ?",
                MAX_RETRIES, BATCH_SIZE);
        } catch (Exception e) {
            // Table missing or DB unavailable — log once and skip this cycle.
            log.debug("[EMAIL-QUEUE] Skipped — could not read email_queue: {}", e.getMessage());
            return;
        }

        if (pending.isEmpty()) return;
        log.info("[EMAIL-QUEUE] Processing {} pending emails", pending.size());

        // Cache one sender per tenant for this poll cycle.
        Map<Long, SmtpContext> senderCache = new HashMap<>();

        for (Map<String, Object> row : pending) {
            Long id = ((Number) row.get("id")).longValue();
            String recipient = (String) row.get("recipient");
            String subject = (String) row.get("subject");
            String body = (String) row.get("body");
            String attachmentPath = (String) row.get("attachment_path");
            int retryCount = ((Number) row.get("retry_count")).intValue();
            boolean isHtml = !Boolean.FALSE.equals(row.get("is_html"));
            Long tenantId = row.get("tenant_id") != null
                    ? ((Number) row.get("tenant_id")).longValue() : null;

            try {
                SmtpContext smtp = senderCache.computeIfAbsent(tenantId, this::buildSmtpContext);
                if (smtp == null) {
                    // No SMTP configured for this tenant — fail the row with a clear reason.
                    markFailedOrRetry(id, retryCount,
                            "No active SMTP config for tenant " + tenantId);
                    continue;
                }

                MimeMessage message = smtp.sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, attachmentPath != null, "UTF-8");
                helper.setTo(recipient);
                if (smtp.fromAddress != null) helper.setFrom(smtp.fromAddress);
                helper.setSubject(subject != null ? subject : "Notification");
                helper.setText(body != null ? body : "", isHtml);

                if (attachmentPath != null) {
                    File file = new File(attachmentPath);
                    if (file.exists()) {
                        helper.addAttachment(file.getName(), file);
                    } else {
                        log.warn("[EMAIL-QUEUE] Attachment not found for email #{}: {}", id, attachmentPath);
                    }
                }

                smtp.sender.send(message);

                jdbc.update("UPDATE email_queue SET status = 'SENT', sent_at = NOW(), error_message = NULL WHERE id = ?", id);
                log.info("[EMAIL-QUEUE] Sent email #{} to {}{}", id, recipient,
                        tenantId != null ? " (tenant=" + tenantId + ")" : "");

            } catch (Exception e) {
                log.warn("[EMAIL-QUEUE] Failed email #{} to {}: {}", id, recipient, e.getMessage());
                markFailedOrRetry(id, retryCount, e.getMessage());
            }
        }
    }

    /** Increment retry_count; mark FAILED once MAX_RETRIES is reached, else leave PENDING. */
    private void markFailedOrRetry(Long id, int currentRetry, String error) {
        int next = currentRetry + 1;
        jdbc.update(
            "UPDATE email_queue SET retry_count = ?, error_message = ?, " +
            "status = CASE WHEN ? >= ? THEN 'FAILED' ELSE 'PENDING' END WHERE id = ?",
            next, error, next, MAX_RETRIES, id);
    }

    /** Holds a built sender plus the from-address for a tenant. */
    private static class SmtpContext {
        final JavaMailSenderImpl sender;
        final String fromAddress;
        SmtpContext(JavaMailSenderImpl sender, String fromAddress) {
            this.sender = sender;
            this.fromAddress = fromAddress;
        }
    }

    /**
     * Build an SMTP sender from the active email_smtp_config for a tenant.
     * Returns null if the tenant has no active config. The stored password is
     * AES-256-GCM encrypted and is decrypted here.
     */
    private SmtpContext buildSmtpContext(Long tenantId) {
        try {
            String sql = "SELECT host, port, username, password, auth_enabled, starttls_enabled, " +
                    "ssl_enabled, from_address, connection_timeout, read_timeout, write_timeout " +
                    "FROM email_smtp_config WHERE is_active = true" +
                    (tenantId != null ? " AND tenant_id = ?" : "") + " LIMIT 1";
            Map<String, Object> cfg = (tenantId != null)
                    ? jdbc.queryForMap(sql, tenantId)
                    : jdbc.queryForMap(sql);

            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost((String) cfg.get("host"));
            sender.setPort(((Number) cfg.get("port")).intValue());
            sender.setDefaultEncoding("UTF-8");

            String username = (String) cfg.get("username");
            if (username != null && !username.isEmpty()) {
                sender.setUsername(username);
                // Stored encrypted ("enc:v1:" token) — decrypt before use.
                sender.setPassword(cryptoService.decrypt((String) cfg.get("password")));
            }

            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", String.valueOf(cfg.get("auth_enabled")));
            props.put("mail.smtp.starttls.enable", String.valueOf(cfg.get("starttls_enabled")));
            if (Boolean.TRUE.equals(cfg.get("ssl_enabled"))) {
                props.put("mail.smtp.ssl.enable", "true");
            }
            if (cfg.get("connection_timeout") != null)
                props.put("mail.smtp.connectiontimeout", cfg.get("connection_timeout").toString());
            if (cfg.get("read_timeout") != null)
                props.put("mail.smtp.timeout", cfg.get("read_timeout").toString());
            if (cfg.get("write_timeout") != null)
                props.put("mail.smtp.writetimeout", cfg.get("write_timeout").toString());

            String from = (String) cfg.get("from_address");
            return new SmtpContext(sender, from);
        } catch (org.springframework.dao.EmptyResultDataAccessException none) {
            log.warn("[EMAIL-QUEUE] No active SMTP config for tenant {}", tenantId);
            return null;
        } catch (Exception e) {
            log.error("[EMAIL-QUEUE] Failed to build SMTP sender for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }
}
