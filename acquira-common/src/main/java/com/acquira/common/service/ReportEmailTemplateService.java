package com.acquira.common.service;

import com.acquira.common.model.EmailTemplateConfig;
import com.acquira.common.repository.EmailTemplateConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the covering email for a generated PDF report — the message the
 * merchant actually receives with their Business Insight Report attached.
 *
 * WHY THIS EXISTS: the subject and body used to be hardcoded inside
 * PdfController (a ~60-line string-concatenated HTML builder), so every tenant
 * sent byte-identical wording and branding and nothing could be changed without
 * a redeploy. The platform already had a tenant-scoped template store
 * (email_template_config, edited under Admin > Email campaign hub > Templates)
 * but the PDF path never consulted it.
 *
 * Resolution order per send:
 *   1. the tenant's default, active {@link EmailTemplateConfig.TemplateType#REPORT_PDF} template
 *   2. the built-in template below
 *
 * The built-in is the previous hardcoded email verbatim, with the merchant name
 * and period lifted out as {{placeholders}}. That keeps behaviour identical for
 * every tenant that hasn't authored a template — nothing changes on deploy —
 * and it doubles as the starter body the admin UI offers when creating one, so
 * tenants edit the real email rather than starting from a blank box.
 *
 * Lives in acquira-common because both senders need it and they sit in
 * different modules: acquira-pdf (merchant monthly batch) and acquira-core.
 * acquira-pdf depends only on acquira-common, so core's TemplateRendererService
 * is out of reach from there — hence the small local {@link #render} rather than
 * a dependency edge between the two service modules.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportEmailTemplateService {

    private final EmailTemplateConfigRepository templateRepo;

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    /**
     * Merge variables this template type supplies, and what each one means.
     * Surfaced by the admin UI as the insertable-variable list, so it is the
     * single source of truth for "what can I put in a PDF report email".
     */
    public static final Map<String, String> REPORT_VARIABLES = new LinkedHashMap<>() {{
        put("merchant_name", "Merchant name");
        put("mid", "Merchant ID (MID)");
        put("month_year", "Report period, long form (e.g. March 2026)");
        put("statement_month", "Report period, code form (e.g. 2026-03)");
        put("pdf_filename", "File name of the attached PDF");
    }};

    /** A resolved, fully rendered email ready to be queued. */
    public record Rendered(String subject, String bodyHtml, boolean fromTenantTemplate) { }

    public static String builtInSubject() {
        return "Your Business Insight Report — {{month_year}}";
    }

    /**
     * Resolve and render the covering email for one merchant's report.
     * Never throws: a malformed tenant template falls back to the built-in
     * rather than failing the send, because this runs inside a batch loop where
     * one bad template must not stop every other merchant's report going out.
     */
    public Rendered resolve(Long tenantId, Map<String, String> vars) {
        String subject = builtInSubject();
        String body = builtInBodyHtml();
        boolean custom = false;

        if (tenantId != null) {
            try {
                Optional<EmailTemplateConfig> found = templateRepo
                        .findByTenantIdAndTemplateTypeAndIsDefaultForTypeTrue(
                                tenantId, EmailTemplateConfig.TemplateType.REPORT_PDF)
                        .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
                        .filter(t -> t.getBodyHtml() != null && !t.getBodyHtml().isBlank());
                if (found.isPresent()) {
                    EmailTemplateConfig t = found.get();
                    if (t.getSubjectTemplate() != null && !t.getSubjectTemplate().isBlank())
                        subject = t.getSubjectTemplate();
                    body = t.getBodyHtml();
                    custom = true;
                }
            } catch (Exception e) {
                log.warn("[report-email] template lookup failed for tenant {} — using built-in: {}",
                        tenantId, e.toString());
            }
        }

        return new Rendered(render(subject, vars), render(body, vars), custom);
    }

    /** Replaces every {{name}} with its value; unknown names collapse to empty. */
    public String render(String template, Map<String, String> variables) {
        if (template == null) return "";
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String value = variables.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * The built-in report email, verbatim from the previous hardcoded builder.
     *
     * Deliberately table-based with inline styles and no {@code <style>} head,
     * no flexbox and no web fonts — that is what survives Outlook and Gmail.
     * Keep any edit within those constraints.
     */
    public static String builtInBodyHtml() {
        return ""
            + "<!DOCTYPE html>"
            + "<html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>Business Insight Report</title></head>"
            + "<body style=\"margin:0;padding:0;background:#f4f5f7;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            +   "style=\"background:#f4f5f7;padding:32px 12px;\"><tr><td align=\"center\">"

            +   "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" "
            +     "style=\"width:560px;max-width:560px;background:#ffffff;border:1px solid #e6e8eb;"
            +     "border-radius:12px;overflow:hidden;font-family:-apple-system,BlinkMacSystemFont,"
            +     "'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">"

            +     "<tr><td style=\"padding:20px 32px;border-bottom:1px solid #eef0f2;\">"
            +       "<span style=\"font-size:15px;font-weight:700;letter-spacing:.02em;color:#0b1f3a;\">"
            +         "AFS&nbsp;NEXUS</span>"
            +       "<span style=\"float:right;font-size:12px;color:#8a94a6;\">Monthly Statement</span>"
            +     "</td></tr>"

            +     "<tr><td style=\"padding:32px;\">"
            +       "<div style=\"font-size:12px;text-transform:uppercase;letter-spacing:.08em;"
            +         "color:#6b7688;margin-bottom:6px;\">Business Insight Report</div>"
            +       "<div style=\"font-size:22px;font-weight:700;color:#0b1f3a;margin-bottom:2px;\">"
            +         "{{month_year}}</div>"
            +       "<div style=\"height:3px;width:44px;background:#2f5fe0;border-radius:2px;"
            +         "margin:14px 0 22px;\"></div>"

            +       "<p style=\"font-size:14px;line-height:1.6;color:#1f2733;margin:0 0 14px;\">"
            +         "Dear {{merchant_name}},</p>"
            +       "<p style=\"font-size:14px;line-height:1.6;color:#1f2733;margin:0 0 14px;\">"
            +         "We are pleased to share your Business Insight Report for <strong>{{month_year}}</strong>, "
            +         "prepared exclusively for your business. The full report is attached to this email "
            +         "as a PDF.</p>"
            +       "<p style=\"font-size:14px;line-height:1.6;color:#1f2733;margin:0 0 14px;\">"
            +         "We hope these insights help you better understand your business performance and "
            +         "support your decision-making.</p>"
            +       "<p style=\"font-size:14px;line-height:1.6;color:#1f2733;margin:0 0 22px;\">"
            +         "If you have any questions about this report, simply reply to this email and our "
            +         "team will be happy to assist you.</p>"

            +       "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
            +         "style=\"margin:0 0 26px;\"><tr>"
            +         "<td style=\"background:#f6f8fc;border:1px solid #e2e8f4;border-radius:8px;"
            +           "padding:12px 16px;font-size:13px;color:#33415a;\">"
            +           "&#128196;&nbsp; {{pdf_filename}}"
            +         "</td></tr></table>"

            +       "<p style=\"font-size:14px;line-height:1.6;color:#1f2733;margin:0;\">"
            +         "Best regards,<br>"
            +         "The AFS NEXUS Team<br>"
            +         "Arab Financial Services</p>"
            +     "</td></tr>"

            +     "<tr><td style=\"padding:18px 32px;background:#fafbfc;border-top:1px solid #eef0f2;\">"
            +       "<div style=\"font-size:11px;color:#8a94a6;line-height:1.6;\">"
            +         "This is an automated message from AFS NEXUS, the merchant intelligence platform "
            +         "by Arab Financial Services.<br>"
            +         "If you prefer not to receive these monthly reports, simply email to "
            +         "<a href=\"mailto:uaemerchants@afs.com.bh\" style=\"color:#6b7688;\">uaemerchants@afs.com.bh</a> "
            +         "or contact our call center +971 4312 4848 and we will remove you from future mailings."
            +       "</div>"
            +     "</td></tr>"

            +     "</table>"

            +     "<div style=\"font-size:11px;color:#aab2c0;margin-top:16px;line-height:1.6;max-width:560px;\">"
            +       "This email and its attachment are confidential and intended solely for the named "
            +       "recipient. If you have received this message in error, please notify the sender and "
            +       "delete it. &copy; Arab Financial Services B.S.C.(c). All rights reserved.</div>"

            +   "</td></tr></table>"
            + "</body></html>";
    }
}
