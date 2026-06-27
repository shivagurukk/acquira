package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.ExplorerAlert;
import com.acquira.common.repository.ExplorerAlertRepository;
import com.acquira.core.controller.AnalyticsExplorerController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Evaluates Data Explorer threshold alerts (Phase 4.x).
 *
 * Runs on a fixed delay. For each enabled {@link ExplorerAlert}, it sets the
 * tenant context, evaluates the configured measure over its trailing window via
 * the Explorer engine, records last value/checked time, and — on a breach that
 * has cooled down past {@code explorer.alert.cooldown-hours} — writes a row into
 * the existing {@code alert_history} table so it surfaces in the Alerts UI.
 *
 * Tenant context is set per-alert and cleared afterwards. Loading the enabled
 * alerts across all tenants runs with no tenant set, which TenantAspect skips
 * (it only pushes the GUC when a tenant is present); isolation on the actual
 * data query is enforced by the explicit tenant_id predicate in the engine.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExplorerAlertScheduler {

    private final ExplorerAlertRepository alertRepo;
    private final AnalyticsExplorerController explorer;
    private final JdbcTemplate jdbcTemplate;
    private final EmailService emailService;

    @Value("${explorer.alert.cooldown-hours:6}")
    private long cooldownHours;

    @Scheduled(fixedDelayString = "${explorer.alert.interval-ms:1800000}",
               initialDelayString = "${explorer.alert.initial-ms:120000}")
    public void evaluateAll() {
        List<ExplorerAlert> alerts;
        try {
            alerts = alertRepo.findByIsEnabledTrue();
        } catch (Exception e) {
            log.warn("[explorer-alert] could not load alerts: {}", e.toString());
            return;
        }
        if (alerts.isEmpty()) return;
        log.debug("[explorer-alert] evaluating {} enabled alert(s)", alerts.size());
        for (ExplorerAlert a : alerts) {
            try {
                evaluateOne(a);
            } catch (Exception e) {
                log.warn("[explorer-alert] alert {} ('{}') failed: {}", a.getId(), a.getName(), e.toString());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void evaluateOne(ExplorerAlert a) {
        TenantContext.setCurrentTenant(a.getTenantId());
        double value = explorer.evaluateAlert(a);
        a.setLastValue(value);
        a.setLastCheckedAt(LocalDateTime.now());

        if (AnalyticsExplorerController.breaches(a.getOperator(), value, a.getThreshold())) {
            boolean cooled = a.getLastTriggeredAt() == null
                || a.getLastTriggeredAt().isBefore(LocalDateTime.now().minusHours(cooldownHours));
            if (cooled) {
                String msg = a.getName() + ": " + a.getMeasureKey() + " = " + fmt(value)
                    + " " + a.getOperator() + " " + fmt(a.getThreshold());
                try {
                    jdbcTemplate.update(
                        "INSERT INTO alert_history (tenant_id, rule_name, severity, merchant_name, message, " +
                        "metric_value, acknowledged, triggered_at) VALUES (?,?,?,?,?,?,false,CURRENT_TIMESTAMP)",
                        a.getTenantId(), a.getName(),
                        a.getSeverity() == null ? "WARNING" : a.getSeverity(),
                        "—", msg, value);
                    a.setLastTriggeredAt(LocalDateTime.now());
                    log.info("[explorer-alert] BREACH tenant={} '{}' value={} {} {}",
                        a.getTenantId(), a.getName(), value, a.getOperator(), a.getThreshold());
                } catch (Exception e) {
                    log.warn("[explorer-alert] failed to write alert_history for {}: {}", a.getId(), e.toString());
                }
                if (a.getRecipients() != null && !a.getRecipients().isBlank()) {
                    String subject = "[" + (a.getSeverity() == null ? "WARNING" : a.getSeverity()) + "] Alert: " + a.getName();
                    String body = msg + "\n\nWindow: last " + (a.getWindowDays() != null ? a.getWindowDays() : 1) + " day(s)"
                        + "\nChecked: " + LocalDateTime.now();
                    for (String to : a.getRecipients().split("[,;]")) {
                        String addr = to.trim();
                        if (!addr.isEmpty()) emailService.sendEmail(addr, subject, body);
                    }
                }
            }
        }
        alertRepo.save(a);
    }

    private static String fmt(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.format("%,.0f", d);
        return String.format("%,.2f", d);
    }
}
