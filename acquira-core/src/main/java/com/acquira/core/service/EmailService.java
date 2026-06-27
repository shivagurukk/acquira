package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.EmailSmtpConfig;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.util.Optional;

/**
 * Email delivery for Acquira.
 *
 * Two sources of SMTP configuration, tried in order:
 *
 *  1. PER-TENANT DB CONFIG (preferred) \u2014 the active {@link EmailSmtpConfig}
 *     row for the current tenant, managed from the SMTP Settings admin page.
 *     The password is stored AES-256-GCM encrypted; SmtpConfigService decrypts
 *     it only in-memory when building the sender. This is the path used for
 *     merchant statement emails and campaigns.
 *
 *  2. PROPERTY FALLBACK \u2014 spring.mail.* from application.properties, exposed
 *     as the optional autoconfigured {@link JavaMailSender} bean. Used for
 *     flows that have NO tenant context (e.g. password-reset, which runs
 *     before login). If neither source is configured the call is logged and
 *     skipped rather than throwing.
 *
 * PDF attachments: {@link #sendEmailWithPdf} builds a multipart MimeMessage so
 * a generated merchant statement PDF can be attached. SimpleMailMessage (used
 * by the plain-text methods) cannot carry attachments.
 *
 * Works with any SMTP provider. For Amazon SES: host
 * email-smtp.<region>.amazonaws.com, port 587, STARTTLS on, SES SMTP
 * credentials (not AWS keys), and an SES-verified from-address.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Value("${app.base-url:http://localhost:5173}")
    private String baseUrl;

    @Value("${spring.mail.host:}")
    private String fallbackMailHost;

    @Value("${spring.mail.from:noreply@acquira.com}")
    private String fallbackFromAddress;

    /** Autoconfigured from spring.mail.* — only present if spring.mail.host is set. */
    @Autowired(required = false)
    private JavaMailSender fallbackMailSender;

    private final SmtpConfigService smtpConfigService;

    public EmailService(SmtpConfigService smtpConfigService) {
        this.smtpConfigService = smtpConfigService;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolved sender — per-tenant DB config, else property fallback
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Small holder pairing a JavaMailSender with the from-identity to use.
     * Returned by {@link #resolveSender()}; null when nothing is configured.
     */
    private record ResolvedSender(JavaMailSender sender, String fromAddress, String fromName) {}

    /**
     * Resolve the mail sender for the CURRENT tenant. Tries the tenant's active
     * EmailSmtpConfig first, then the property-based fallback. Returns null if
     * neither is available (caller logs & skips).
     */
    private ResolvedSender resolveSender() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId != null) {
            Optional<EmailSmtpConfig> active = smtpConfigService.getActiveRaw(tenantId);
            if (active.isPresent()) {
                EmailSmtpConfig cfg = active.get();
                JavaMailSenderImpl sender = smtpConfigService.buildMailSender(cfg);
                String from = (cfg.getFromAddress() != null && !cfg.getFromAddress().isBlank())
                        ? cfg.getFromAddress() : fallbackFromAddress;
                return new ResolvedSender(sender, from, cfg.getFromName());
            }
        }
        if (fallbackMailSender != null && fallbackMailHost != null && !fallbackMailHost.isBlank()) {
            return new ResolvedSender(fallbackMailSender, fallbackFromAddress, null);
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Plain-text email
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Send a plain-text email via the current tenant's active SMTP config.
     * @return true if handed to the mail server, false if no SMTP configured / send failed.
     */
    public boolean sendEmail(String toEmail, String subject, String body) {
        ResolvedSender rs = resolveSender();
        if (rs == null) {
            log.warn("[EMAIL] No SMTP configured (no active tenant config, no spring.mail.*). "
                    + "Would send to {}: {}", toEmail, subject);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(rs.fromAddress());
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            rs.sender().send(message);
            log.info("[EMAIL] Sent plain email to {}: {}", toEmail, subject);
            return true;
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HTML email with optional PDF attachment  (merchant statements)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Send an HTML email with a PDF attachment — the path used to deliver a
     * branded merchant statement.
     *
     * @param toEmail     recipient
     * @param subject     subject line
     * @param htmlBody    HTML body (a plain-text part is derived automatically)
     * @param pdfBytes    the statement PDF; if null/empty, sends with no attachment
     * @param pdfFilename attachment filename, e.g. "Statement-April-2026.pdf"
     * @return true if handed to the mail server, false otherwise.
     */
    public boolean sendEmailWithPdf(String toEmail, String subject, String htmlBody,
                                    byte[] pdfBytes, String pdfFilename) {
        ResolvedSender rs = resolveSender();
        if (rs == null) {
            log.warn("[EMAIL] No SMTP configured. Would send statement to {}: {}", toEmail, subject);
            return false;
        }
        try {
            MimeMessage mime = rs.sender().createMimeMessage();
            // true => multipart, so we can attach the PDF; UTF-8 for non-ASCII.
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");

            if (rs.fromName() != null && !rs.fromName().isBlank()) {
                try {
                    helper.setFrom(new InternetAddress(rs.fromAddress(), rs.fromName()));
                } catch (UnsupportedEncodingException ue) {
                    helper.setFrom(rs.fromAddress());
                }
            } else {
                helper.setFrom(rs.fromAddress());
            }
            helper.setTo(toEmail);
            helper.setSubject(subject);
            // Second arg true => the body is HTML.
            helper.setText(htmlBody, true);

            if (pdfBytes != null && pdfBytes.length > 0) {
                String filename = (pdfFilename != null && !pdfFilename.isBlank())
                        ? pdfFilename : "statement.pdf";
                helper.addAttachment(filename, new ByteArrayResource(pdfBytes), "application/pdf");
            }

            rs.sender().send(mime);
            log.info("[EMAIL] Sent statement email to {} ({} bytes attached): {}",
                    toEmail, pdfBytes != null ? pdfBytes.length : 0, subject);
            return true;
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send statement to {}: {}", toEmail, e.getMessage(), e);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Generic email with an arbitrary attachment (scheduled report exports)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Send an email (HTML or plain) with an arbitrary byte attachment, via the
     * CURRENT tenant's resolved SMTP sender. Used by scheduled report exports to
     * attach CSV / XLSX / PDF artifacts.
     *
     * @return true if handed to the mail server, false if no SMTP / send failed.
     */
    public boolean sendEmailWithAttachment(String toEmail, String subject, String htmlBody,
                                           byte[] bytes, String filename, String contentType) {
        ResolvedSender rs = resolveSender();
        if (rs == null) {
            log.warn("[EMAIL] No SMTP configured. Would send '{}' to {}", subject, toEmail);
            return false;
        }
        try {
            MimeMessage mime = rs.sender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            if (rs.fromName() != null && !rs.fromName().isBlank()) {
                try {
                    helper.setFrom(new InternetAddress(rs.fromAddress(), rs.fromName()));
                } catch (UnsupportedEncodingException ue) {
                    helper.setFrom(rs.fromAddress());
                }
            } else {
                helper.setFrom(rs.fromAddress());
            }
            helper.setTo(toEmail);
            helper.setSubject(subject);
            boolean isHtml = htmlBody != null && htmlBody.contains("<");
            helper.setText(htmlBody != null ? htmlBody : "", isHtml);
            if (bytes != null && bytes.length > 0) {
                String fn = (filename != null && !filename.isBlank()) ? filename : "report";
                String ct = (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream";
                helper.addAttachment(fn, new ByteArrayResource(bytes), ct);
            }
            rs.sender().send(mime);
            log.info("[EMAIL] Sent '{}' to {} ({} bytes attached)", subject, toEmail, bytes != null ? bytes.length : 0);
            return true;
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send '{}' to {}: {}", subject, toEmail, e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Password reset  (runs pre-login → no tenant context → property fallback)
    // ════════════════════════════════════════════════════════════════════════

    public void sendPasswordResetEmail(String toEmail, String username, String token) {
        String resetLink = baseUrl + "/reset-password?token=" + token;
        boolean sent = sendEmail(
                toEmail,
                "Password Reset \u2014 Acquira",
                "Hello " + (username != null ? username : "") + ",\n\n"
                        + "You requested a password reset. Click the link below to set a new password:\n\n"
                        + resetLink + "\n\n"
                        + "This link expires in 1 hour.\n\n"
                        + "If you did not request this, you can safely ignore this email.\n\n"
                        + "\u2014 Acquira Team");
        if (!sent) {
            // Last-resort: log the link so a dev/admin can still complete the flow.
            log.warn("[EMAIL] Reset email not sent (no SMTP). Reset link for {}: {}", username, resetLink);
        }
    }
}
