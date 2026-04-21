package com.acquira.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * GAP-18: Email service for password reset and notifications.
 * Falls back to logging if SMTP (JavaMailSender) is not configured.
 *
 * To enable actual email sending:
 *   Configure spring.mail.host/port/username/password in application.properties
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Value("${app.base-url:http://localhost:5173}")
    private String baseUrl;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.from:noreply@acquira.com}")
    private String fromAddress;

    // JavaMailSender is optional — only present if spring.mail.host is configured
    @Autowired(required = false)
    private JavaMailSender mailSender;

    /**
     * Send password reset email. If SMTP is not configured, logs the reset link.
     */
    public void sendPasswordResetEmail(String toEmail, String username, String token) {
        String resetLink = baseUrl + "/reset-password?token=" + token;

        if (mailHost == null || mailHost.isBlank() || mailSender == null) {
            log.warn("[EMAIL] SMTP not configured. Reset link for {}: {}", username, resetLink);
            log.warn("[EMAIL] To enable email: configure spring.mail.host/port/username/password in application.properties");
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject("Password Reset — Acquira");
            message.setText(
                "Hello " + (username != null ? username : "") + ",\n\n"
                + "You requested a password reset. Click the link below to set a new password:\n\n"
                + resetLink + "\n\n"
                + "This link expires in 1 hour.\n\n"
                + "If you did not request this, you can safely ignore this email.\n\n"
                + "— Acquira Team"
            );
            mailSender.send(message);
            log.info("[EMAIL] Password reset email sent to {}", toEmail);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send reset email to {}: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Send a generic email (used by EmailQueueProcessor, campaigns, etc.)
     */
    public boolean sendEmail(String toEmail, String subject, String body) {
        if (mailHost == null || mailHost.isBlank() || mailSender == null) {
            log.warn("[EMAIL] SMTP not configured. Would send to {}: {}", toEmail, subject);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("[EMAIL] Sent email to {}: {}", toEmail, subject);
            return true;
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }
}
