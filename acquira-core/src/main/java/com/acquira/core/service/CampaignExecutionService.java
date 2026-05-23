package com.acquira.core.service;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import com.acquira.common.service.CryptoService;
import com.acquira.common.service.MerchantInsightService;
import com.acquira.pdf.service.PlaywrightPdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * Executes email campaigns — sends emails to filtered merchants
 * using customizable templates with merge variables.
 * Builds JavaMailSender dynamically from email_smtp_config table.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignExecutionService {

    private final EmailCampaignRepository campaignRepo;
    private final EmailCampaignLogRepository campaignLogRepo;
    private final EmailTemplateConfigRepository templateRepo;
    private final TemplateRendererService templateRenderer;
    private final JdbcTemplate jdbcTemplate;
    // Decrypts the SMTP password (stored AES-256-GCM encrypted).
    private final CryptoService cryptoService;
    // Builds the per-merchant statement data; PlaywrightPdfService renders it.
    private final MerchantInsightService merchantInsightService;
    private final PlaywrightPdfService playwrightPdfService;

    /**
     * Build a JavaMailSender from the active SMTP config in the database.
     */
    private JavaMailSenderImpl buildMailSender() {
        try {
            Map<String, Object> cfg = jdbcTemplate.queryForMap(
                "SELECT host, port, username, password, auth_enabled, starttls_enabled, ssl_enabled, " +
                "connection_timeout, read_timeout, write_timeout FROM email_smtp_config WHERE is_active = true LIMIT 1");

            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost((String) cfg.get("host"));
            sender.setPort(((Number) cfg.get("port")).intValue());

            String username = (String) cfg.get("username");
            if (username != null && !username.isEmpty()) {
                sender.setUsername(username);
                // The password is stored AES-256-GCM encrypted ("enc:v1:" token).
                // Decrypt it here so the real password reaches the SMTP server.
                // cryptoService.decrypt() passes plaintext through unchanged, so
                // this is safe for any legacy un-encrypted rows too.
                sender.setPassword(cryptoService.decrypt((String) cfg.get("password")));
            }

            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", String.valueOf(cfg.get("auth_enabled")));
            props.put("mail.smtp.starttls.enable", String.valueOf(cfg.get("starttls_enabled")));
            props.put("mail.smtp.ssl.enable", String.valueOf(cfg.get("ssl_enabled")));

            if (cfg.get("connection_timeout") != null)
                props.put("mail.smtp.connectiontimeout", cfg.get("connection_timeout").toString());
            if (cfg.get("read_timeout") != null)
                props.put("mail.smtp.timeout", cfg.get("read_timeout").toString());
            if (cfg.get("write_timeout") != null)
                props.put("mail.smtp.writetimeout", cfg.get("write_timeout").toString());

            return sender;
        } catch (Exception e) {
            log.error("No active SMTP config found in email_smtp_config table: {}", e.getMessage());
            throw new RuntimeException("No active SMTP configuration. Please configure SMTP settings first.");
        }
    }

    private String getFromAddress() {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT from_address FROM email_smtp_config WHERE is_active = true LIMIT 1",
                String.class);
        } catch (Exception e) {
            return "noreply@acquira.com";
        }
    }

    private int getRateLimitMs() {
        try {
            Integer rate = jdbcTemplate.queryForObject(
                "SELECT rate_limit_ms FROM email_smtp_config WHERE is_active = true LIMIT 1",
                Integer.class);
            return rate != null ? rate : 200;
        } catch (Exception e) {
            return 200;
        }
    }

    /**
     * Launch a campaign — resolves recipients, renders templates, sends emails.
     */
    @Async
    public void launchCampaign(Long campaignId) {
        EmailCampaign campaign = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campaign not found"));

        campaign.setStatus(EmailCampaign.Status.SENDING);
        campaign.setSentAt(LocalDateTime.now());
        campaignRepo.save(campaign);

        try {
            EmailTemplateConfig template = campaign.getTemplate();
            Long tenantId = campaign.getTenantId();

            // 1. Resolve recipients
            List<Map<String, Object>> recipients = resolveRecipients(tenantId, campaign.getRecipientFilterJson());
            campaign.setTotalRecipients(recipients.size());
            campaignRepo.save(campaign);

            log.info("[Campaign] '{}' — sending to {} recipients", campaign.getName(), recipients.size());

            // 2. Build mail sender from DB config
            JavaMailSenderImpl mailSender = buildMailSender();
            String fromAddress = getFromAddress();
            int rateLimitMs = getRateLimitMs();
            int sent = 0, failed = 0;

            // 3. Send to each recipient
            for (Map<String, Object> recipient : recipients) {
                Long merchantId = ((Number) recipient.get("merchant_id")).longValue();
                String merchantName = (String) recipient.get("name");
                String email = (String) recipient.get("email");

                if (email == null || email.isBlank()) {
                    logSend(campaign, tenantId, merchantId, merchantName, email, null,
                            EmailCampaignLog.Status.FAILED, "No email address");
                    failed++;
                    continue;
                }

                try {
                    Map<String, String> vars = templateRenderer.buildVariablesForMerchant(
                            merchantId, tenantId, campaign.getStatementMonth());

                    String subject = templateRenderer.render(template.getSubjectTemplate(), vars);
                    String body = templateRenderer.render(template.getBodyHtml(), vars);

                    MimeMessage message = mailSender.createMimeMessage();
                    // Multipart so we can attach the statement PDF when requested.
                    MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                    helper.setTo(email);
                    helper.setFrom(fromAddress);
                    helper.setSubject(subject);
                    helper.setText(body, true);

                    // Attach the branded statement PDF when the campaign is
                    // configured for STATEMENT_PDF attachments. Generated per
                    // merchant for the campaign's statement month.
                    if (campaign.getAttachmentType() == EmailCampaign.AttachmentType.STATEMENT_PDF) {
                        byte[] pdf = generateStatementPdf(merchantId, merchantName,
                                campaign.getStatementMonth());
                        if (pdf != null && pdf.length > 0) {
                            String fileName = "Statement_"
                                    + (merchantName != null ? merchantName.replaceAll("[^a-zA-Z0-9.\\-]", "_") : merchantId)
                                    + "_" + (campaign.getStatementMonth() != null ? campaign.getStatementMonth() : "")
                                    + ".pdf";
                            helper.addAttachment(fileName, new ByteArrayResource(pdf), "application/pdf");
                        } else {
                            // PDF could not be built - send the email anyway (body
                            // still has the statement summary) but record why.
                            log.warn("[Campaign] No statement PDF for merchant {} ({}) - sending without attachment",
                                    merchantName, merchantId);
                        }
                    }

                    mailSender.send(message);

                    logSend(campaign, tenantId, merchantId, merchantName, email, subject,
                            EmailCampaignLog.Status.SENT, null);
                    sent++;

                    Thread.sleep(rateLimitMs);

                } catch (Exception e) {
                    log.warn("[Campaign] Failed to send to {} ({}): {}", merchantName, email, e.getMessage());
                    logSend(campaign, tenantId, merchantId, merchantName, email, null,
                            EmailCampaignLog.Status.FAILED, e.getMessage());
                    failed++;
                }
            }

            campaign.setSentCount(sent);
            campaign.setFailedCount(failed);
            campaign.setStatus(EmailCampaign.Status.COMPLETED);
            campaign.setUpdatedAt(LocalDateTime.now());
            campaignRepo.save(campaign);

            log.info("[Campaign] '{}' COMPLETED — sent: {}, failed: {}", campaign.getName(), sent, failed);

        } catch (Exception e) {
            log.error("[Campaign] '{}' FAILED: {}", campaign.getName(), e.getMessage(), e);
            campaign.setStatus(EmailCampaign.Status.FAILED);
            campaignRepo.save(campaign);
        }
    }

    /**
     * Resolve recipient merchants based on filter JSON.
     */
    public List<Map<String, Object>> resolveRecipients(Long tenantId, String filterJson) {
        StringBuilder sql = new StringBuilder(
            "SELECT m.merchant_id, m.name, " +
            "COALESCE(mc.email, m.sales_email) AS email " +
            "FROM dim_merchant m " +
            "LEFT JOIN merchant_contact mc ON mc.merchant_id = m.merchant_id AND mc.is_primary = true " +
            "WHERE m.tenant_id = ?"
        );
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (filterJson != null && !filterJson.isBlank() && !filterJson.equals("{}")) {
            Map<String, Object> filters = parseSimpleJson(filterJson);

            if (filters.containsKey("status")) {
                @SuppressWarnings("unchecked")
                List<String> statuses = (List<String>) filters.get("status");
                if (statuses != null && !statuses.isEmpty()) {
                    String placeholders = String.join(",", Collections.nCopies(statuses.size(), "?"));
                    sql.append(" AND m.status IN (").append(placeholders).append(")");
                    params.addAll(statuses);
                }
            }
            if (filters.containsKey("city")) {
                @SuppressWarnings("unchecked")
                List<String> cities = (List<String>) filters.get("city");
                if (cities != null && !cities.isEmpty()) {
                    String placeholders = String.join(",", Collections.nCopies(cities.size(), "?"));
                    sql.append(" AND m.city IN (").append(placeholders).append(")");
                    params.addAll(cities);
                }
            }
        }

        sql.append(" ORDER BY m.name");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public int countRecipients(Long tenantId, String filterJson) {
        return resolveRecipients(tenantId, filterJson).size();
    }

    @Async
    public void retryFailed(Long campaignId) {
        launchCampaign(campaignId);
    }

    /**
     * Generate the branded statement PDF for one merchant for a given month.
     *
     * statementMonth is expected as "yyyy-MM" (e.g. "2026-04"); if null/blank
     * or unparseable, the previous calendar month is used as a sensible default.
     * Returns null on any failure so the caller can still send the email body
     * without the attachment rather than failing the whole recipient.
     */
    private byte[] generateStatementPdf(Long merchantId, String merchantName, String statementMonth) {
        try {
            YearMonth ym;
            try {
                ym = (statementMonth != null && !statementMonth.isBlank())
                        ? YearMonth.parse(statementMonth.trim())
                        : YearMonth.now().minusMonths(1);
            } catch (Exception parseEx) {
                log.warn("[Campaign] Unparseable statementMonth '{}' - defaulting to last month", statementMonth);
                ym = YearMonth.now().minusMonths(1);
            }

            MerchantInsightsDTO dto = merchantInsightService.getInsights(
                    merchantId, ym.getYear(), ym.getMonthValue());
            if (dto == null) {
                log.warn("[Campaign] No insight data for merchant {} ({})", merchantName, merchantId);
                return null;
            }

            // monthYear is the human-readable label printed on the statement.
            String monthYear = ym.getMonth().getDisplayName(
                    java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
            return playwrightPdfService.generatePdf(dto,
                    merchantName != null ? merchantName : ("Merchant " + merchantId), monthYear);
        } catch (Exception e) {
            log.error("[Campaign] Statement PDF generation failed for merchant {} ({}): {}",
                    merchantName, merchantId, e.getMessage());
            return null;
        }
    }

    private void logSend(EmailCampaign campaign, Long tenantId, Long merchantId,
                         String merchantName, String email, String subject,
                         EmailCampaignLog.Status status, String error) {
        EmailCampaignLog logEntry = new EmailCampaignLog();
        logEntry.setCampaignId(campaign.getId());
        logEntry.setTenantId(tenantId);
        logEntry.setMerchantId(merchantId);
        logEntry.setMerchantName(merchantName);
        logEntry.setRecipientEmail(email);
        logEntry.setSubjectRendered(subject);
        logEntry.setStatus(status);
        logEntry.setSentAt(LocalDateTime.now());
        logEntry.setErrorMessage(error);
        campaignLogRepo.save(logEntry);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();
        try {
            json = json.trim();
            if (json.startsWith("{")) json = json.substring(1);
            if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
            String[] pairs = json.split(",(?=\\s*\"\\w+\"\\s*:)");
            for (String pair : pairs) {
                int colonIdx = pair.indexOf(':');
                if (colonIdx < 0) continue;
                String key = pair.substring(0, colonIdx).trim().replaceAll("\"", "");
                String val = pair.substring(colonIdx + 1).trim();
                if (val.startsWith("[")) {
                    val = val.substring(1, val.lastIndexOf(']'));
                    List<String> list = new ArrayList<>();
                    for (String item : val.split(",")) {
                        list.add(item.trim().replaceAll("\"", ""));
                    }
                    result.put(key, list);
                } else {
                    result.put(key, val.replaceAll("\"", ""));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse filter JSON: {}", e.getMessage());
        }
        return result;
    }
}
