package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.event.IntegrationRunFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Emails a schedule's alert recipients when an integration pull's FINAL attempt
 * fails (the batch module publishes {@link IntegrationRunFailedEvent} only
 * then — retries in progress never alert, so a flapping feed produces at most
 * one email per failed run, not one per attempt).
 *
 * Lives in core because the mail stack (EmailService + per-tenant SMTP config)
 * is a core concern; batch publishes the event and knows nothing about email.
 * EmailService resolves the SMTP config from TenantContext, and this runs on an
 * async listener thread that has none — so the context is set explicitly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntegrationAlertListener {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm");

    private final EmailService emailService;

    @Async
    @EventListener
    public void onRunFailed(IntegrationRunFailedEvent ev) {
        List<String> recipients = Arrays.stream(ev.alertEmails().split("[,;\\s]+"))
                .map(String::trim)
                .filter(a -> a.contains("@"))
                .distinct()
                .toList();
        if (recipients.isEmpty()) return;

        String subject = "[Acquira] Integration pull FAILED — " + ev.reportName();
        String html = render(ev);

        TenantContext.setCurrentTenant(ev.tenantId());
        try {
            int sent = 0;
            for (String addr : recipients) {
                if (emailService.sendEmailWithAttachment(addr, subject, html, null, null, null)) sent++;
            }
            log.info("[Integration] Failure alert for run #{} ('{}') sent to {}/{} recipient(s)",
                    ev.runLogId(), ev.reportName(), sent, recipients.size());
        } catch (Exception e) {
            log.warn("[Integration] Failure alert for run #{} could not be sent: {}",
                    ev.runLogId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    /** Self-contained inline-styled HTML — email clients strip style blocks. */
    private String render(IntegrationRunFailedEvent ev) {
        String window = ev.dateRangeFrom() != null || ev.dateRangeTo() != null
                ? (ev.dateRangeFrom() != null ? ev.dateRangeFrom().toString() : "…")
                  + " to " + (ev.dateRangeTo() != null ? ev.dateRangeTo().toString() : "…")
                : "Default (current month)";
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;color:#1c2733;max-width:640px;\">"
                + "<h2 style=\"color:#b3382c;margin:0 0 4px;\">Integration pull failed</h2>"
                + "<p style=\"color:#64748b;margin:0 0 16px;\">Final attempt " + ev.attemptNumber() + " of "
                + ev.maxRetries() + " — no further retries will run. " + esc(TS.format(LocalDateTime.now())) + "</p>"
                + row("Report", esc(ev.reportName()) + (ev.reportType() != null ? " (" + esc(ev.reportType()) + ")" : ""))
                + row("Connection", ev.connectionName() != null ? esc(ev.connectionName()) : "—")
                + row("Trigger", ev.triggerType() != null ? esc(ev.triggerType()) : "—")
                + row("Date window", esc(window))
                + "<div style=\"margin-top:14px;padding:12px;background:#fdf2f1;border:1px solid #f0c9c4;"
                + "border-radius:6px;font-family:Consolas,Menlo,monospace;font-size:12px;white-space:pre-wrap;\">"
                + esc(ev.errorMessage() != null ? ev.errorMessage() : "No error message recorded")
                + "</div>"
                + "<p style=\"color:#64748b;font-size:12px;margin-top:16px;\">Retry it from Acquira &gt; "
                + "Data Integration Hub &gt; Run history, or adjust the schedule under Schedules. "
                + "This alert was configured on the schedule (Alert on failure).</p>"
                + "</div>";
    }

    private String row(String label, String value) {
        return "<div style=\"padding:4px 0;font-size:14px;\"><span style=\"display:inline-block;width:120px;"
                + "color:#64748b;\">" + label + "</span><strong>" + value + "</strong></div>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
