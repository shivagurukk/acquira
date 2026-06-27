package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.ReportSchedule;
import com.acquira.common.model.ReportTemplate;
import com.acquira.common.repository.ReportScheduleRepository;
import com.acquira.core.controller.AnalyticsExplorerController;
import com.acquira.core.controller.AnalyticsExplorerController.ExplorerQuery;
import com.acquira.core.controller.AnalyticsExplorerController.CalcMeasure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes due scheduled Data Explorer report exports (Phase 4.x).
 *
 * Walks enabled {@link ReportSchedule} rows on a fixed delay; when a row's
 * {@code nextRunAt} is due, it parses the linked template's Explorer config,
 * runs the query for the schedule's tenant, renders CSV/Excel via
 * {@link ReportExportService}, and writes the file under
 * {@code <app.reports.dir>/scheduled/}. {@code nextRunAt} is then advanced from
 * the cron expression. Email delivery is a follow-up; DOWNLOAD_ONLY schedules
 * are fully served by the on-disk artifact.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledReportRunner {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ReportScheduleRepository scheduleRepo;
    private final AnalyticsExplorerController explorer;
    private final ReportExportService exportService;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    @Value("${app.reports.dir:/opt/acquira/reports}")
    private String reportsDir;

    @Scheduled(fixedDelayString = "${report.schedule.interval-ms:300000}",
               initialDelayString = "${report.schedule.initial-ms:90000}")
    public void runDue() {
        List<ReportSchedule> schedules;
        try {
            schedules = scheduleRepo.findByIsEnabledTrue();
        } catch (Exception e) {
            log.warn("[report-schedule] could not load schedules: {}", e.toString());
            return;
        }
        if (schedules.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (ReportSchedule s : schedules) {
            try {
                if (s.getNextRunAt() == null) {
                    s.setNextRunAt(nextRun(s));
                    scheduleRepo.save(s);
                    continue;
                }
                if (s.getNextRunAt().isAfter(now)) continue;

                execute(s);
                s.setLastRunAt(LocalDateTime.now());
                s.setNextRunAt(nextRun(s));
                scheduleRepo.save(s);
            } catch (Exception e) {
                log.warn("[report-schedule] schedule {} failed: {}", s.getId(), e.toString());
                try { s.setNextRunAt(nextRun(s)); scheduleRepo.save(s); } catch (Exception ignore) { }
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void execute(ReportSchedule s) throws Exception {
        TenantContext.setCurrentTenant(s.getTenantId());
        ReportTemplate t = s.getTemplate();
        if (t == null || t.getConfigJson() == null) {
            log.warn("[report-schedule] schedule {} has no template config; skipping", s.getId());
            return;
        }

        Map<String, Object> cfg = objectMapper.readValue(t.getConfigJson(), new TypeReference<Map<String, Object>>() {});

        ExplorerQuery q = new ExplorerQuery();
        q.setDimensions(asStringList(cfg.get("dimensions")));
        q.setMeasures(asStringList(cfg.get("measures")));
        if (cfg.get("calc") instanceof List<?> customDefs)
            explorer.applyCustomMeasures(q, customDefs);  // splits calc + agg custom measures
        if (cfg.get("filters") != null)
            q.setFilters(objectMapper.convertValue(cfg.get("filters"), new TypeReference<Map<String, List<String>>>() {}));
        if (cfg.get("startDate") != null) q.setStartDate(String.valueOf(cfg.get("startDate")));
        if (cfg.get("endDate") != null) q.setEndDate(String.valueOf(cfg.get("endDate")));
        q.setLimit(5000);

        List<Map<String, Object>> rows = explorer.runRaw(q);

        byte[] bytes;
        String ext;
        String contentType;
        ReportSchedule.ExportFormat fmt = s.getExportFormat();
        if (fmt == ReportSchedule.ExportFormat.CSV) {
            bytes = exportService.exportCsv(rows);
            ext = "csv"; contentType = "text/csv";
        } else if (fmt == ReportSchedule.ExportFormat.PDF) {
            bytes = exportService.exportPdf(rows, t.getName());
            ext = "pdf"; contentType = "application/pdf";
        } else {
            bytes = exportService.exportExcel(rows, t.getName(), cfg);
            ext = "xlsx"; contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        Path dir = Paths.get(reportsDir, "scheduled");
        Files.createDirectories(dir);
        String fname = sanitize(t.getName()) + "_t" + s.getTenantId() + "_"
            + LocalDateTime.now().format(TS) + "." + ext;
        Files.write(dir.resolve(fname), bytes);
        log.info("[report-schedule] '{}' (tenant {}) -> {} ({} rows, {})",
            t.getName(), s.getTenantId(), fname, rows.size(), s.getDeliveryMethod());

        if (s.getDeliveryMethod() == ReportSchedule.DeliveryMethod.EMAIL
                && s.getRecipientEmails() != null && !s.getRecipientEmails().isBlank()) {
            String html = "<p>Your scheduled report <b>" + escapeHtml(t.getName()) + "</b> is attached"
                + " (" + rows.size() + " rows, generated " + LocalDateTime.now().format(TS) + ").</p>";
            String subject = "Scheduled report: " + t.getName();
            int sent = 0;
            for (String to : s.getRecipientEmails().split("[,;]")) {
                String addr = to.trim();
                if (addr.isEmpty()) continue;
                if (emailService.sendEmailWithAttachment(addr, subject, html, bytes, fname, contentType)) sent++;
            }
            log.info("[report-schedule] emailed '{}' to {} recipient(s)", t.getName(), sent);
        }
    }

    private LocalDateTime nextRun(ReportSchedule s) {
        try {
            CronExpression cron = CronExpression.parse(s.getCronExpression());
            LocalDateTime n = cron.next(LocalDateTime.now());
            return n != null ? n : LocalDateTime.now().plusDays(1);
        } catch (Exception e) {
            log.warn("[report-schedule] bad cron '{}' on schedule {} — defaulting to +1 day",
                s.getCronExpression(), s.getId());
            return LocalDateTime.now().plusDays(1);
        }
    }

    private List<String> asStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null) out.add(String.valueOf(x));
        return out;
    }

    private String sanitize(String s) {
        return s == null ? "report" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
