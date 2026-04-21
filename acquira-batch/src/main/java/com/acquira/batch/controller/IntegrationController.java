package com.acquira.batch.controller;

import com.acquira.batch.service.DynamicSchedulerService;
import com.acquira.batch.service.IntegrationPullService;
import com.acquira.common.config.TenantContext;
import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin/integration")
@RequiredArgsConstructor
@Slf4j
public class IntegrationController {

    private final IntegrationConnectionRepository connectionRepo;
    private final IntegrationReportRepository reportRepo;
    private final IntegrationScheduleRepository scheduleRepo;
    private final IntegrationRunLogRepository runLogRepo;
    private final IntegrationPullService pullService;
    private final DynamicSchedulerService schedulerService;

    // ═══════════════════════════════════════════════════════════
    //  CONNECTIONS
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/connections")
    public ResponseEntity<List<IntegrationConnection>> getConnections() {
        Long tenantId = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(connectionRepo.findByTenantIdOrderByNameAsc(tenantId));
    }

    @PostMapping("/connections")
    public ResponseEntity<?> createConnection(@RequestBody IntegrationConnection conn) {
        Long tenantId = TenantContext.getCurrentTenant();
        conn.setTenantId(tenantId);
        conn.setCreatedAt(LocalDateTime.now());

        if (connectionRepo.existsByTenantIdAndNameIgnoreCase(tenantId, conn.getName())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Connection name already exists"));
        }

        return ResponseEntity.ok(connectionRepo.save(conn));
    }

    @PutMapping("/connections/{id}")
    public ResponseEntity<?> updateConnection(@PathVariable Long id, @RequestBody IntegrationConnection updated) {
        Long tenantId = TenantContext.getCurrentTenant();
        return connectionRepo.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .map(existing -> {
                    existing.setName(updated.getName());
                    existing.setDbType(updated.getDbType());
                    existing.setHost(updated.getHost());
                    existing.setPort(updated.getPort());
                    existing.setDbName(updated.getDbName());
                    existing.setUsername(updated.getUsername());
                    if (updated.getEncryptedPassword() != null && !updated.getEncryptedPassword().isBlank()) {
                        existing.setEncryptedPassword(updated.getEncryptedPassword());
                    }
                    existing.setTimeoutSeconds(updated.getTimeoutSeconds());
                    existing.setMaxRetries(updated.getMaxRetries());
                    existing.setIsActive(updated.getIsActive());
                    existing.setUpdatedAt(LocalDateTime.now());
                    return ResponseEntity.ok(connectionRepo.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<?> deleteConnection(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return connectionRepo.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .map(c -> {
                    c.setIsActive(false);
                    c.setUpdatedAt(LocalDateTime.now());
                    connectionRepo.save(c);
                    return ResponseEntity.ok(Map.of("message", "Connection deactivated"));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/connections/{id}/test")
    public ResponseEntity<?> testConnection(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return connectionRepo.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .map(conn -> {
                    boolean success = pullService.testConnection(conn);
                    conn.setLastTestAt(LocalDateTime.now());
                    conn.setLastTestStatus(success ? "SUCCESS" : "FAILED");
                    connectionRepo.save(conn);
                    return ResponseEntity.ok(Map.of(
                            "success", success,
                            "message", success ? "Connection successful" : "Connection failed"
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════
    //  REPORTS
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/reports")
    public ResponseEntity<List<IntegrationReport>> getReports(
            @RequestParam(required = false) String type) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (type != null) {
            IntegrationReport.ReportType rt = IntegrationReport.ReportType.valueOf(type.toUpperCase());
            return ResponseEntity.ok(reportRepo.findByTenantIdAndReportType(tenantId, rt));
        }
        return ResponseEntity.ok(reportRepo.findByTenantIdOrderByNameAsc(tenantId));
    }

    @PostMapping("/reports")
    public ResponseEntity<?> createReport(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();

        IntegrationReport report = new IntegrationReport();
        report.setTenantId(tenantId);
        report.setName((String) body.get("name"));
        report.setReportType(IntegrationReport.ReportType.valueOf(((String) body.get("reportType")).toUpperCase()));
        report.setSqlText((String) body.get("sqlText"));
        report.setColumnMapping((String) body.get("columnMapping"));
        report.setDescription((String) body.get("description"));
        report.setParamSchema((String) body.get("paramSchema"));
        report.setApprovedBy((String) body.get("approvedBy"));
        report.setIsActive(body.get("isActive") != null ? (Boolean) body.get("isActive") : true);
        report.setCreatedAt(LocalDateTime.now());

        Long connectionId = Long.valueOf(body.get("connectionId").toString());
        IntegrationConnection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found"));
        report.setConnection(conn);

        return ResponseEntity.ok(reportRepo.save(report));
    }

    @PutMapping("/reports/{id}")
    public ResponseEntity<?> updateReport(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        return reportRepo.findById(id)
                .filter(r -> r.getTenantId().equals(tenantId))
                .map(existing -> {
                    if (body.containsKey("name")) existing.setName((String) body.get("name"));
                    if (body.containsKey("sqlText")) existing.setSqlText((String) body.get("sqlText"));
                    if (body.containsKey("columnMapping")) existing.setColumnMapping((String) body.get("columnMapping"));
                    if (body.containsKey("description")) existing.setDescription((String) body.get("description"));
                    if (body.containsKey("paramSchema")) existing.setParamSchema((String) body.get("paramSchema"));
                    if (body.containsKey("approvedBy")) existing.setApprovedBy((String) body.get("approvedBy"));
                    if (body.containsKey("isActive")) existing.setIsActive((Boolean) body.get("isActive"));
                    if (body.containsKey("connectionId")) {
                        Long connectionId = Long.valueOf(body.get("connectionId").toString());
                        existing.setConnection(connectionRepo.findById(connectionId).orElseThrow());
                    }
                    existing.setUpdatedAt(LocalDateTime.now());
                    return ResponseEntity.ok(reportRepo.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/reports/{id}")
    public ResponseEntity<?> deleteReport(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return reportRepo.findById(id)
                .filter(r -> r.getTenantId().equals(tenantId))
                .map(r -> {
                    r.setIsActive(false);
                    r.setUpdatedAt(LocalDateTime.now());
                    reportRepo.save(r);
                    return ResponseEntity.ok(Map.of("message", "Report deactivated"));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/reports/{id}/validate")
    public ResponseEntity<?> validateReport(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return reportRepo.findById(id)
                .filter(r -> r.getTenantId().equals(tenantId))
                .map(report -> {
                    try {
                        List<Map<String, Object>> preview = pullService.validateQuery(
                                report.getConnection(), report.getSqlText());
                        return ResponseEntity.ok(Map.of(
                                "success", true,
                                "rowCount", preview.size(),
                                "columns", preview.isEmpty() ? List.of() : new ArrayList<>(preview.get(0).keySet()),
                                "preview", preview
                        ));
                    } catch (Exception e) {
                        return ResponseEntity.ok(Map.of(
                                "success", false,
                                "error", e.getMessage()
                        ));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════
    //  SCHEDULES
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/schedules")
    public ResponseEntity<List<IntegrationSchedule>> getSchedules() {
        Long tenantId = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(scheduleRepo.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }

    @PostMapping("/schedules")
    public ResponseEntity<?> createSchedule(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();

        IntegrationSchedule schedule = new IntegrationSchedule();
        schedule.setTenantId(tenantId);
        schedule.setCronExpression((String) body.get("cronExpression"));
        schedule.setFrequencyLabel((String) body.get("frequencyLabel"));
        schedule.setTimezone(body.getOrDefault("timezone", "UTC").toString());
        schedule.setIsEnabled(body.get("isEnabled") != null ? (Boolean) body.get("isEnabled") : true);
        schedule.setCreatedAt(LocalDateTime.now());

        Long reportId = Long.valueOf(body.get("reportId").toString());
        IntegrationReport report = reportRepo.findById(reportId)
                .orElseThrow(() -> new RuntimeException("Report not found"));
        schedule.setReport(report);

        IntegrationSchedule saved = scheduleRepo.save(schedule);

        // Register with dynamic scheduler
        if (Boolean.TRUE.equals(saved.getIsEnabled())) {
            schedulerService.registerSchedule(saved);
        }

        return ResponseEntity.ok(saved);
    }

    @PutMapping("/schedules/{id}")
    public ResponseEntity<?> updateSchedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        return scheduleRepo.findById(id)
                .filter(s -> s.getTenantId().equals(tenantId))
                .map(existing -> {
                    if (body.containsKey("cronExpression")) existing.setCronExpression((String) body.get("cronExpression"));
                    if (body.containsKey("frequencyLabel")) existing.setFrequencyLabel((String) body.get("frequencyLabel"));
                    if (body.containsKey("timezone")) existing.setTimezone((String) body.get("timezone"));
                    if (body.containsKey("isEnabled")) existing.setIsEnabled((Boolean) body.get("isEnabled"));
                    existing.setUpdatedAt(LocalDateTime.now());

                    IntegrationSchedule saved = scheduleRepo.save(existing);
                    schedulerService.reloadSchedule(saved);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/schedules/{id}/toggle")
    public ResponseEntity<?> toggleSchedule(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return scheduleRepo.findById(id)
                .filter(s -> s.getTenantId().equals(tenantId))
                .map(s -> {
                    s.setIsEnabled(!Boolean.TRUE.equals(s.getIsEnabled()));
                    s.setUpdatedAt(LocalDateTime.now());
                    IntegrationSchedule saved = scheduleRepo.save(s);
                    schedulerService.reloadSchedule(saved);
                    return ResponseEntity.ok(Map.of("enabled", saved.getIsEnabled()));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/schedules/{id}/run-now")
    public ResponseEntity<?> runNow(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        return scheduleRepo.findById(id)
                .filter(s -> s.getTenantId().equals(tenantId))
                .map(schedule -> {
                    LocalDate dateFrom = null;
                    LocalDate dateTo = null;
                    if (body != null) {
                        if (body.containsKey("dateFrom")) dateFrom = LocalDate.parse(body.get("dateFrom"));
                        if (body.containsKey("dateTo")) dateTo = LocalDate.parse(body.get("dateTo"));
                    }
                    schedulerService.runNow(schedule, dateFrom, dateTo);
                    return ResponseEntity.ok(Map.of("message", "Pull started", "reportName", schedule.getReport().getName()));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return scheduleRepo.findById(id)
                .filter(s -> s.getTenantId().equals(tenantId))
                .map(s -> {
                    schedulerService.cancelSchedule(s.getId());
                    scheduleRepo.delete(s);
                    return ResponseEntity.ok(Map.of("message", "Schedule deleted"));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════
    //  RUN HISTORY
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/runs")
    public ResponseEntity<Page<IntegrationRunLog>> getRunHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long reportId) {
        Long tenantId = TenantContext.getCurrentTenant();
        IntegrationRunLog.Status statusEnum = status != null ? IntegrationRunLog.Status.valueOf(status.toUpperCase()) : null;
        return ResponseEntity.ok(runLogRepo.findFiltered(tenantId, statusEnum, reportId, PageRequest.of(page, size)));
    }

    @PostMapping("/runs/{id}/retry")
    public ResponseEntity<?> retryRun(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return runLogRepo.findById(id)
                .filter(r -> r.getTenantId().equals(tenantId))
                .filter(r -> r.getStatus() == IntegrationRunLog.Status.FAILED)
                .map(failedRun -> {
                    pullService.executePull(
                            failedRun.getReport(),
                            failedRun.getSchedule(),
                            IntegrationRunLog.TriggerType.RETRY,
                            failedRun.getDateRangeFrom(),
                            failedRun.getDateRangeTo(),
                            1
                    );
                    return ResponseEntity.ok(Map.of("message", "Retry started"));
                })
                .orElse(ResponseEntity.badRequest().build());
    }

    // ═══════════════════════════════════════════════════════════
    //  OVERVIEW DASHBOARD
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/overview")
    public ResponseEntity<?> getOverview() {
        Long tenantId = TenantContext.getCurrentTenant();
        LocalDateTime since24h = LocalDateTime.now().minusHours(24);

        long totalConnections = connectionRepo.findByTenantIdAndIsActiveTrue(tenantId).size();
        long totalReports = reportRepo.findByTenantIdAndIsActiveTrue(tenantId).size();
        long runsToday = runLogRepo.countRunsSince(tenantId, since24h);
        long successToday = runLogRepo.countSuccessRunsSince(tenantId, since24h);
        long failedToday = runLogRepo.countFailedRunsSince(tenantId, since24h);
        double successRate = runsToday > 0 ? (double) successToday / runsToday * 100 : 0;

        var recentRuns = runLogRepo.findTop10ByTenantIdOrderByStartTimeDesc(tenantId);

        int activeSchedules = schedulerService.getActiveCount();

        return ResponseEntity.ok(Map.of(
                "totalConnections", totalConnections,
                "totalReports", totalReports,
                "activeSchedules", activeSchedules,
                "runsToday", runsToday,
                "successToday", successToday,
                "failedToday", failedToday,
                "successRate", Math.round(successRate),
                "recentRuns", recentRuns
        ));
    }
}
