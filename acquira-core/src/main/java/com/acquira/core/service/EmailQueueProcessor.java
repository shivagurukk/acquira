package com.acquira.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * #26: Processes email_queue table.
 * Polls every 60 seconds for PENDING emails, sends via JavaMailSender.
 * Handles retries (max 3) and error tracking.
 */
@Service
public class EmailQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmailQueueProcessor.class);
    private static final int MAX_RETRIES = 3;
    private static final int BATCH_SIZE = 10;

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    public EmailQueueProcessor(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    @Scheduled(fixedDelay = 60000) // Every 60 seconds
    public void processQueue() {
        if (mailSender == null) return; // No SMTP configured, skip

        List<Map<String, Object>> pending;
        try {
            pending = jdbc.queryForList(
                "SELECT id, recipient, subject, body, attachment_path, retry_count, tenant_id " +
                "FROM email_queue WHERE status = 'PENDING' AND retry_count < ? " +
                "ORDER BY created_at LIMIT ?",
                MAX_RETRIES, BATCH_SIZE);
        } catch (Exception e) {
            // Table might not exist yet
            return;
        }

        if (pending.isEmpty()) return;
        log.info("[EMAIL-QUEUE] Processing {} pending emails", pending.size());

        for (Map<String, Object> row : pending) {
            Long id = ((Number) row.get("id")).longValue();
            String recipient = (String) row.get("recipient");
            String subject = (String) row.get("subject");
            String body = (String) row.get("body");
            String attachmentPath = (String) row.get("attachment_path");
            int retryCount = ((Number) row.get("retry_count")).intValue();

            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, attachmentPath != null);
                helper.setTo(recipient);
                helper.setSubject(subject != null ? subject : "Notification");
                helper.setText(body != null ? body : "", true); // HTML

                if (attachmentPath != null) {
                    File file = new File(attachmentPath);
                    if (file.exists()) {
                        helper.addAttachment(file.getName(), file);
                    }
                }

                mailSender.send(message);

                jdbc.update("UPDATE email_queue SET status = 'SENT', sent_at = NOW() WHERE id = ?", id);
                Object tenantIdObj = row.get("tenant_id");
                String tenantTag = tenantIdObj != null ? " (tenant=" + tenantIdObj + ")" : "";
                log.info("[EMAIL-QUEUE] Sent email #{} to {}{}", id, recipient, tenantTag);

            } catch (Exception e) {
                log.warn("[EMAIL-QUEUE] Failed email #{} to {}: {}", id, recipient, e.getMessage());
                jdbc.update(
                    "UPDATE email_queue SET retry_count = ?, error_message = ?, status = CASE WHEN ? >= ? THEN 'FAILED' ELSE 'PENDING' END WHERE id = ?",
                    retryCount + 1, e.getMessage(), retryCount + 1, MAX_RETRIES, id);
            }
        }
    }
}
