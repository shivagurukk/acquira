package com.acquira.batch.controller;

import com.acquira.batch.service.DynamicSchedulerService;
import com.acquira.batch.service.IntegrationPullService;
import com.acquira.common.config.TenantContext;
import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import com.acquira.common.service.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * AUTHORIZATION MODEL (added 2026-08-22 — this controller previously had NO
 * method security at all and relied solely on the blanket
 * SecurityConfig rule "/api/admin/** = ADMIN or SUPER_ADMIN").
 *
 * Two tiers, because the endpoints are not equally dangerous:
 *
 *  TIER 1 — configuration & operations (class-level menu grant).
 *    Connections, schedules, run history, overview. Gated on the DB-driven
 *    sys_group_menu grant for /admin/integration, so WHO may administer feeds
 *    is a Security-Settings decision per user group rather than a hardcoded
 *    role — consistent with the rest of the product's RBAC.
 *
 *  TIER 2 — SQL authoring & execution (SUPER_ADMIN, method-level).
 *    Creating/editing a report's sqlText, approving it, and the ad-hoc query
 *    executor. This SQL runs against the CUSTOMER's production database with
 *    the stored service credentials, and Connection.setReadOnly is a no-op on
 *    the Oracle and MSSQL drivers — so an authoring right is effectively a
 *    query console into a third party's core system. Sibling controllers
 *    (MigrationController, BackfillController) already step up the same way.
 *
 * Reads stay at tier 1 so tenant admins can still see and operate their feeds.
 *
 * NOTE: the application layer is not the real boundary. The source database
 * account must be granted SELECT only, on only the tables a feed needs.
 */
@RestController
@RequestMapping("/api/admin/integration")
@RequiredArgsConstructor
@Slf4j
@org.springframework.security.access.prepost.PreAuthorize("@menuAccess.canAccess('/admin/integration')")
public class IntegrationController {

    /** Tier 2 gate — see the class javadoc. */
    private static final String SQL_AUTHORING = "hasRole('SUPER_ADMIN')";

    private final IntegrationConnectionRepository connectionRepo;
    private final IntegrationReportRepository reportRepo;
    private final IntegrationScheduleRepository scheduleRepo;
    private final IntegrationRunLogRepository runLogRepo;
    private final IntegrationPullService pullService;
    private final DynamicSchedulerService schedulerService;
    // P0 fix: encrypt connection password before persisting it. Was being
    // saved as plaintext into integration_connection.encrypted_password.
    private final CryptoService cryptoService;

    // ═══════════════════════════════════════════════════════════
    //  CONNECTIONS
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/connections")
    public ResponseEntity<List<IntegrationConnection>> getConnections() {
        Long tenantId = TenantContext.getCurrentTenant();
        // P0 fix: never return the encrypted password to the UI. The list
        // endpoint returned the full ciphertext token, which lets anyone with
        // browser dev-tools copy a credential to a different account.
        List<IntegrationConnection> connections = connectionRepo.findByTenantIdOrderByNameAsc(tenantId);
        connections.forEach(this::sanitizeForResponse);
        return ResponseEntity.ok(connections);
    }

    /**
     * Strip the password ciphertext from a connection before sending it to
     * the client. Replace with a placeholder so the UI can detect "already
     * has a password" without ever seeing the value. On update the UI sends
     * either a real new password (we re-encrypt) or this placeholder back
     * (we leave the existing ciphertext alone via the isBlank() guard
     * combined with encrypt()'s idempotent prefix check).
     */
    private static final String PASSWORD_PLACEHOLDER = "__UNCHANGED__";
    private IntegrationConnection sanitizeForResponse(IntegrationConnection c) {
        if (c != null && c.getEncryptedPassword() != null && !c.getEncryptedPassword().isBlank()) {
            c.setEncryptedPassword(PASSWORD_PLACEHOLDER);
        }
        return c;
    }

    @PostMapping("/connections")
    public ResponseEntity<?> createConnection(@RequestBody IntegrationConnection conn) {
        Long tenantId = TenantContext.getCurrentTenant();
        conn.setTenantId(tenantId);
        conn.setCreatedAt(LocalDateTime.now());

        if (connectionRepo.existsByTenantIdAndNameIgnoreCase(tenantId, conn.getName())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Connection name already exists"));
        }

        // P0 fix: encrypt password at rest. CryptoService.encrypt is idempotent
        // so re-saving an already-encrypted token is a no-op.
        if (conn.getEncryptedPassword() != null && !conn.getEncryptedPassword().isBlank()) {
            conn.setEncryptedPassword(cryptoService.encrypt(conn.getEncryptedPassword()));
        }

        return ResponseEntity.ok(sanitizeForResponse(connectionRepo.save(conn)));
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
                    if (updated.getEncryptedPassword() != null
                            && !updated.getEncryptedPassword().isBlank()
                            && !PASSWORD_PLACEHOLDER.equals(updated.getEncryptedPassword())) {
                        // P0 fix: encrypt before persisting. encrypt() is a no-op
                        // if the value is already an encrypted token. The placeholder
                        // check above means "UI didn't change the password" — leave
                        // the existing ciphertext untouched.
                        existing.setEncryptedPassword(cryptoService.encrypt(updated.getEncryptedPassword()));
                    }
                    existing.setTimeoutSeconds(updated.getTimeoutSeconds());
                    existing.setMaxRetries(updated.getMaxRetries());
                    if (updated.getTrustServerCert() != null) existing.setTrustServerCert(updated.getTrustServerCert());
                    existing.setIsActive(updated.getIsActive());
                    existing.setUpdatedAt(LocalDateTime.now());
                    return ResponseEntity.ok(sanitizeForResponse(connectionRepo.save(existing)));
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

    /**
     * Pre-save connection test: exercises the DRAFT in the editor modal without
     * persisting anything, so a bad host/credential never has to be saved first.
     * A blank / placeholder password on an existing connection (id present)
     * falls back to the stored ciphertext — mirroring what update() would keep.
     * Nothing is written: no lastTestStatus, no row.
     */
    @PostMapping("/connections/test-adhoc")
    public ResponseEntity<?> testConnectionAdhoc(@RequestBody IntegrationConnection draft) {
        Long tenantId = TenantContext.getCurrentTenant();
        String pwd = draft.getEncryptedPassword();
        if (pwd == null || pwd.isBlank() || PASSWORD_PLACEHOLDER.equals(pwd)) {
            if (draft.getId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Enter a password to test the connection"));
            }
            IntegrationConnection stored = connectionRepo.findById(draft.getId())
                    .filter(c -> c.getTenantId().equals(tenantId))
                    .orElse(null);
            if (stored == null) return ResponseEntity.notFound().build();
            draft.setEncryptedPassword(stored.getEncryptedPassword());
        }
        draft.setTenantId(tenantId);
        String error = pullService.testConnectionError(draft);
        return ResponseEntity.ok(error == null
                ? Map.of("success", true, "message", "Connection successful")
                : Map.of("success", false, "message", error));
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

    @org.springframework.security.access.prepost.PreAuthorize(SQL_AUTHORING)
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
        // approvedBy is NEVER taken from the request body — that would let the
        // author approve their own SQL and defeat the separation of duties.
        // A new report starts unapproved and cannot be executed until someone
        // calls POST /reports/{id}/approve.
        report.setApprovedBy(null);
        report.setApprovedAt(null);
        report.setAmountsMinorUnits(body.get("amountsMinorUnits") != null ? (Boolean) body.get("amountsMinorUnits") : false);
        report.setIsActive(body.get("isActive") != null ? (Boolean) body.get("isActive") : true);
        report.setCreatedAt(LocalDateTime.now());

        Long connectionId = Long.valueOf(body.get("connectionId").toString());
        // Tenant-isolation fix: ensure the referenced connection belongs to the
        // same tenant before linking it. Previously a crafted connectionId could
        // link a report to another tenant's DB connection.
        IntegrationConnection conn = connectionRepo.findById(connectionId)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new RuntimeException("Connection not found"));
        report.setConnection(conn);

        return ResponseEntity.ok(reportRepo.save(report));
    }

    @org.springframework.security.access.prepost.PreAuthorize(SQL_AUTHORING)
    @PutMapping("/reports/{id}")
    public ResponseEntity<?> updateReport(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        return reportRepo.findById(id)
                .filter(r -> r.getTenantId().equals(tenantId))
                .map(existing -> {
                    if (body.containsKey("name")) existing.setName((String) body.get("name"));
                    // Changing the SQL REVOKES the approval — the new statement
                    // has not been reviewed, and silently inheriting the old
                    // approval would make the whole gate cosmetic (author an
                    // innocuous query, get it approved, then swap the body).
                    if (body.containsKey("sqlText")) {
                        String newSql = (String) body.get("sqlText");
                        if (!java.util.Objects.equals(newSql, existing.getSqlText())) {
                            existing.setApprovedBy(null);
                            existing.setApprovedAt(null);
                            log.info("[Integration] Report {} SQL changed — approval revoked, re-approval required", id);
                        }
                        existing.setSqlText(newSql);
                    }
                    if (body.containsKey("columnMapping")) existing.setColumnMapping((String) body.get("columnMapping"));
                    if (body.containsKey("description")) existing.setDescription((String) body.get("description"));
                    if (body.containsKey("paramSchema")) existing.setParamSchema((String) body.get("paramSchema"));
                    // approvedBy deliberately NOT settable here — use the
                    // dedicated approve/revoke endpoints below.
                    if (body.containsKey("amountsMinorUnits")) existing.setAmountsMinorUnits((Boolean) body.get("amountsMinorUnits"));
                    if (body.containsKey("isActive")) existing.setIsActive((Boolean) body.get("isActive"));
                    if (body.containsKey("connectionId")) {
                        Long connectionId = Long.valueOf(body.get("connectionId").toString());
                        // Tenant-isolation fix: re-verify the swapped-in connection
                        // belongs to the same tenant.
                        IntegrationConnection conn = connectionRepo.findById(connectionId)
                                .filter(c -> c.getTenantId().equals(tenantId))
                                .orElseThrow(() -> new RuntimeException("Connection not found"));
                        existing.setConnection(conn);
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

    /**
     * Approve a report's SQL for execution. Separation of duties: the approver
     * is recorded from the SECURITY CONTEXT, never from the request body, and
     * this endpoint is SUPER_ADMIN-only while a tenant admin may author.
     * Any later edit to sqlText revokes this automatically.
     */
    @org.springframework.security.access.prepost.PreAuthorize(SQL_AUTHORING)
    @PostMapping("/reports/{id}/approve")
    public ResponseEntity<?> approveReport(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        String approver = currentUsername();
        return reportRepo.findById(id)
                .filter(r -> r.getTenantId().equals(tenantId))
                .map(r -> {
                    r.setApprovedBy(approver);
                    r.setApprovedAt(LocalDateTime.now());
                    r.setUpdatedAt(LocalDateTime.now());
                    reportRepo.save(r);
                    log.warn("[Integration] Report {} ('{}') SQL APPROVED by {}", id, r.getName(), approver);
                    return ResponseEntity.ok(Map.of(
                            "message", "Report approved for execution",
                            "approvedBy", approver));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** Withdraw approval — the report stops running until re-approved. */
    @org.springframework.security.access.prepost.PreAuthorize(SQL_AUTHORING)
    @DeleteMapping("/reports/{id}/approve")
    public ResponseEntity<?> revokeReportApproval(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        return reportRepo.findById(id)
                .filter(r -> r.getTenantId().equals(tenantId))
                .map(r -> {
                    r.setApprovedBy(null);
                    r.setApprovedAt(null);
                    r.setUpdatedAt(LocalDateTime.now());
                    reportRepo.save(r);
                    log.warn("[Integration] Report {} ('{}') approval REVOKED by {}", id, r.getName(), currentUsername());
                    return ResponseEntity.ok(Map.of("message", "Approval revoked — this report will not run until re-approved"));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private String currentUsername() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    @org.springframework.security.access.prepost.PreAuthorize(SQL_AUTHORING)
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

    /**
     * Ad-hoc validate — dry-run a query against a connection BEFORE the report is
     * saved. Mirrors validateReport but resolves the connection + SQL from the
     * request body ({"connectionId":.., "sqlText":".."}) so the composer modal
     * can test the query without first persisting a possibly-broken config.
     * Tenant-isolation: the connection must belong to the caller's tenant.
     *
     * HIGHEST-RISK ENDPOINT IN THIS CONTROLLER: it executes caller-supplied SQL
     * against the customer's production database and returns the ROWS in the
     * response — a query console, not a config screen. SUPER_ADMIN only, and
     * every use is logged with the caller's identity.
     */
    @org.springframework.security.access.prepost.PreAuthorize(SQL_AUTHORING)
    @PostMapping("/reports/validate-adhoc")
    public ResponseEntity<?> validateAdhoc(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        log.warn("[Integration] AD-HOC external query executed by {} (tenant {})", currentUsername(), tenantId);

        Object connIdRaw = body.get("connectionId");
        String sqlText = (String) body.get("sqlText");
        if (connIdRaw == null || connIdRaw.toString().isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Select a connection first"));
        }
        if (sqlText == null || sqlText.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Enter a SQL query to validate"));
        }

        Long connectionId;
        try { connectionId = Long.valueOf(connIdRaw.toString()); }
        catch (NumberFormatException e) { return ResponseEntity.ok(Map.of("success", false, "error", "Invalid connection")); }

        IntegrationConnection conn = connectionRepo.findById(connectionId)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElse(null);
        if (conn == null) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Connection not found"));
        }

        try {
            List<Map<String, Object>> preview = pullService.validateQuery(conn, sqlText);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "rowCount", preview.size(),
                    "columns", preview.isEmpty() ? List.of() : new ArrayList<>(preview.get(0).keySet()),
                    "preview", preview
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  SCHEDULES
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/schedules")
    public ResponseEntity<List<IntegrationSchedule>> getSchedules() {
        Long tenantId = TenantContext.getCurrentTenant();
        List<IntegrationSchedule> schedules = scheduleRepo.findByTenantIdOrderByCreatedAtDesc(tenantId);
        // Enrich with next fire time + recent run outcomes (transient fields —
        // one small indexed query per schedule; tenants have a handful at most).
        for (IntegrationSchedule s : schedules) {
            if (Boolean.TRUE.equals(s.getIsEnabled())) {
                s.setNextRunIso(computeNextRun(s.getCronExpression(), s.getTimezone()));
            }
            List<IntegrationRunLog> recent = runLogRepo.findTop5ByScheduleIdOrderByStartTimeDesc(s.getId());
            if (!recent.isEmpty()) {
                IntegrationRunLog last = recent.get(0);
                s.setLastRunStatus(last.getStatus() != null ? last.getStatus().name() : null);
                if (last.getStatus() == IntegrationRunLog.Status.FAILED) {
                    s.setLastRunError(last.getErrorMessage());
                }
                s.setRecentRunStatuses(recent.stream()
                        .map(r -> r.getStatus() != null ? r.getStatus().name() : "UNKNOWN")
                        .toList());
            }
        }
        return ResponseEntity.ok(schedules);
    }

    /** Next fire instant for a cron in a timezone, as ISO-8601 with offset; null when it can't be computed. */
    private String computeNextRun(String cron, String timezone) {
        try {
            var expr = org.springframework.scheduling.support.CronExpression.parse(cron.trim());
            var zone = java.time.ZoneId.of(timezone != null && !timezone.isBlank() ? timezone : "UTC");
            var next = expr.next(java.time.ZonedDateTime.now(zone));
            return next != null ? next.toOffsetDateTime().toString() : null;
        } catch (RuntimeException e) {
            return null; // bad legacy cron/timezone — the row still renders, just without a next-run
        }
    }

    /**
     * Live preview for the schedule editor: validates the draft cron + timezone
     * and returns the next few fire instants, so a schedule that would silently
     * never fire (or fire in the wrong zone) is visible BEFORE saving.
     */
    @PostMapping("/schedules/preview-cron")
    public ResponseEntity<?> previewCron(@RequestBody Map<String, String> body) {
        String cron = body.get("cronExpression");
        String cronErr = validateCron(cron);
        if (cronErr != null) {
            return ResponseEntity.ok(Map.of("valid", false, "error", cronErr));
        }
        java.time.ZoneId zone;
        try {
            String tz = body.getOrDefault("timezone", "UTC");
            zone = java.time.ZoneId.of(tz == null || tz.isBlank() ? "UTC" : tz);
        } catch (RuntimeException e) {
            return ResponseEntity.ok(Map.of("valid", false, "error", "Unknown timezone"));
        }
        var expr = org.springframework.scheduling.support.CronExpression.parse(cron.trim());
        List<String> nextRuns = new ArrayList<>();
        var cursor = java.time.ZonedDateTime.now(zone);
        for (int i = 0; i < 3; i++) {
            cursor = expr.next(cursor);
            if (cursor == null) break;
            nextRuns.add(cursor.toOffsetDateTime().toString());
        }
        return ResponseEntity.ok(Map.of("valid", true, "nextRuns", nextRuns));
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
        schedule.setPreconditionEnabled(body.get("preconditionEnabled") != null
                ? (Boolean) body.get("preconditionEnabled") : false);
        schedule.setPreconditionSql((String) body.get("preconditionSql"));
        schedule.setAlertEmails((String) body.get("alertEmails"));
        schedule.setAlertOnFailure(body.get("alertOnFailure") != null
                ? (Boolean) body.get("alertOnFailure") : true);
        schedule.setCreatedAt(LocalDateTime.now());

        // Validate the cron up front so an invalid expression fails the request
        // instead of silently saving a schedule the scheduler can't register.
        String cronErr = validateCron(schedule.getCronExpression());
        if (cronErr != null) {
            return ResponseEntity.badRequest().body(Map.of("error", cronErr));
        }

        Long reportId = Long.valueOf(body.get("reportId").toString());
        // Tenant-isolation fix: ensure the referenced report belongs to the same
        // tenant before scheduling it.
        IntegrationReport report = reportRepo.findById(reportId)
                .filter(r -> r.getTenantId().equals(tenantId))
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
                    if (body.containsKey("cronExpression")) {
                        String cron = (String) body.get("cronExpression");
                        String cronErr = validateCron(cron);
                        if (cronErr != null) {
                            return ResponseEntity.badRequest().body((Object) Map.of("error", cronErr));
                        }
                        existing.setCronExpression(cron);
                    }
                    if (body.containsKey("frequencyLabel")) existing.setFrequencyLabel((String) body.get("frequencyLabel"));
                    if (body.containsKey("timezone")) existing.setTimezone((String) body.get("timezone"));
                    if (body.containsKey("isEnabled")) existing.setIsEnabled((Boolean) body.get("isEnabled"));
                    if (body.containsKey("preconditionEnabled")) existing.setPreconditionEnabled((Boolean) body.get("preconditionEnabled"));
                    if (body.containsKey("preconditionSql")) existing.setPreconditionSql((String) body.get("preconditionSql"));
                    if (body.containsKey("alertEmails")) existing.setAlertEmails((String) body.get("alertEmails"));
                    if (body.containsKey("alertOnFailure")) existing.setAlertOnFailure((Boolean) body.get("alertOnFailure"));
                    existing.setUpdatedAt(LocalDateTime.now());

                    IntegrationSchedule saved = scheduleRepo.save(existing);
                    schedulerService.reloadSchedule(saved);
                    return ResponseEntity.ok((Object) saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Validate a cron expression against Spring's parser (the same
     * org.springframework.scheduling.support.CronExpression the scheduler uses).
     * Returns null when valid, or a human-readable error otherwise. Spring uses
     * 6-field cron and does NOT accept Quartz's '?' token — this stops a schedule
     * from being saved-but-never-fired.
     */
    private String validateCron(String cron) {
        if (cron == null || cron.isBlank()) return "Cron expression is required";
        try {
            org.springframework.scheduling.support.CronExpression.parse(cron.trim());
            return null;
        } catch (IllegalArgumentException e) {
            return "Invalid cron expression: " + e.getMessage()
                    + " (use 6-field Spring cron, e.g. '0 0 2 * * *'; the '?' token is not supported)";
        }
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
    //  FEED HEALTH
    // ═══════════════════════════════════════════════════════════

    /**
     * Per-report health rollup for the Overview tab: freshness (did the feed
     * land when its schedule said it should?), failure streak, and 7-day
     * volume/duration stats. One runs query for the whole window plus one
     * "last success ever" lookup per report.
     */
    @GetMapping("/health")
    public ResponseEntity<?> getFeedHealth() {
        Long tenantId = TenantContext.getCurrentTenant();
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<IntegrationReport> reports = reportRepo.findByTenantIdOrderByNameAsc(tenantId);
        List<IntegrationSchedule> schedules = scheduleRepo.findByTenantIdOrderByCreatedAtDesc(tenantId);
        List<IntegrationRunLog> runs = runLogRepo.findByTenantIdAndStartTimeAfterOrderByStartTimeDesc(tenantId, since);

        Map<Long, List<IntegrationRunLog>> runsByReport = new HashMap<>();
        for (IntegrationRunLog r : runs) {
            if (r.getReport() != null) {
                runsByReport.computeIfAbsent(r.getReport().getId(), k -> new ArrayList<>()).add(r);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (IntegrationReport report : reports) {
            if (Boolean.FALSE.equals(report.getIsActive())) continue;

            List<IntegrationRunLog> rr = runsByReport.getOrDefault(report.getId(), List.of());
            long success7d = rr.stream().filter(r -> r.getStatus() == IntegrationRunLog.Status.SUCCESS).count();
            long failed7d = rr.stream().filter(r -> r.getStatus() == IntegrationRunLog.Status.FAILED).count();
            long avgDuration = Math.round(rr.stream()
                    .filter(r -> r.getDurationMs() != null && r.getStatus() == IntegrationRunLog.Status.SUCCESS)
                    .mapToLong(IntegrationRunLog::getDurationMs).average().orElse(0));

            // Consecutive final failures from the newest run down (RUNNING/RETRYING
            // don't break or extend the streak — they are still in flight).
            int failStreak = 0;
            for (IntegrationRunLog r : rr) {
                if (r.getStatus() == IntegrationRunLog.Status.FAILED) failStreak++;
                else if (r.getStatus() == IntegrationRunLog.Status.SUCCESS) break;
            }

            IntegrationRunLog lastSuccess = runLogRepo.findFirstByReportIdAndStatusOrderByStartTimeDesc(
                    report.getId(), IntegrationRunLog.Status.SUCCESS);
            IntegrationRunLog lastRun = rr.isEmpty() ? null : rr.get(0);

            IntegrationSchedule enabledSchedule = schedules.stream()
                    .filter(s -> s.getReport() != null && s.getReport().getId().equals(report.getId()))
                    .filter(s -> Boolean.TRUE.equals(s.getIsEnabled()))
                    .findFirst().orElse(null);

            Map<String, Object> row = new HashMap<>();
            row.put("reportId", report.getId());
            row.put("name", report.getName());
            row.put("reportType", report.getReportType() != null ? report.getReportType().name() : null);
            row.put("approved", report.isApproved());
            row.put("scheduled", enabledSchedule != null);
            row.put("freshness", freshness(enabledSchedule, lastSuccess));
            row.put("lastSuccessAt", lastSuccess != null ? lastSuccess.getStartTime() : null);
            row.put("rowsLastSuccess", lastSuccess != null ? lastSuccess.getRowsProcessed() : null);
            row.put("lastRunStatus", lastRun != null && lastRun.getStatus() != null ? lastRun.getStatus().name() : null);
            row.put("lastRunAt", lastRun != null ? lastRun.getStartTime() : null);
            row.put("failStreak", failStreak);
            row.put("runs7d", rr.size());
            row.put("success7d", success7d);
            row.put("failed7d", failed7d);
            row.put("avgDurationMs7d", avgDuration);
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * FRESH  — last success is newer than the schedule's previous expected fire.
     * LATE   — the schedule should have delivered since the last success (30-min grace).
     * NEVER  — scheduled but no successful run yet.
     * UNSCHEDULED — no enabled schedule; freshness has no yardstick.
     */
    private String freshness(IntegrationSchedule schedule, IntegrationRunLog lastSuccess) {
        if (schedule == null) return "UNSCHEDULED";
        if (lastSuccess == null || lastSuccess.getStartTime() == null) return "NEVER";
        try {
            var expr = org.springframework.scheduling.support.CronExpression.parse(schedule.getCronExpression().trim());
            var zone = java.time.ZoneId.of(schedule.getTimezone() != null && !schedule.getTimezone().isBlank()
                    ? schedule.getTimezone() : "UTC");
            var next = expr.next(java.time.ZonedDateTime.now(zone));
            if (next == null) return "UNSCHEDULED";
            var afterNext = expr.next(next);
            if (afterNext == null) return "FRESH";
            // Spring's CronExpression only walks forward, so the previous expected
            // fire is estimated as next minus the cron's own interval.
            var interval = java.time.Duration.between(next, afterNext);
            var prevExpected = next.minus(interval)
                    .withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
            return lastSuccess.getStartTime().isBefore(prevExpected.minusMinutes(30)) ? "LATE" : "FRESH";
        } catch (RuntimeException e) {
            return "UNSCHEDULED"; // unparsable legacy cron/timezone
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CONFIG PORTABILITY (export / import between environments)
    // ═══════════════════════════════════════════════════════════

    /**
     * Export this tenant's integration configuration as a portable JSON bundle
     * for promotion between environments (local → UAT → prod). NEVER includes
     * passwords or SQL approvals — those are per-environment by design.
     */
    @GetMapping("/export")
    public ResponseEntity<?> exportConfig() {
        Long tenantId = TenantContext.getCurrentTenant();

        List<Map<String, Object>> connections = new ArrayList<>();
        for (IntegrationConnection c : connectionRepo.findByTenantIdOrderByNameAsc(tenantId)) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", c.getName());
            m.put("dbType", c.getDbType() != null ? c.getDbType().name() : null);
            m.put("host", c.getHost());
            m.put("port", c.getPort());
            m.put("dbName", c.getDbName());
            m.put("username", c.getUsername());
            m.put("timeoutSeconds", c.getTimeoutSeconds());
            m.put("maxRetries", c.getMaxRetries());
            m.put("trustServerCert", c.getTrustServerCert());
            m.put("isActive", c.getIsActive());
            connections.add(m);
        }

        List<Map<String, Object>> reports = new ArrayList<>();
        for (IntegrationReport r : reportRepo.findByTenantIdOrderByNameAsc(tenantId)) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", r.getName());
            m.put("reportType", r.getReportType() != null ? r.getReportType().name() : null);
            m.put("connectionName", r.getConnection() != null ? r.getConnection().getName() : null);
            m.put("sqlText", r.getSqlText());
            m.put("columnMapping", r.getColumnMapping());
            m.put("paramSchema", r.getParamSchema());
            m.put("amountsMinorUnits", r.getAmountsMinorUnits());
            m.put("description", r.getDescription());
            m.put("isActive", r.getIsActive());
            reports.add(m);
        }

        List<Map<String, Object>> schedules = new ArrayList<>();
        for (IntegrationSchedule s : scheduleRepo.findByTenantIdOrderByCreatedAtDesc(tenantId)) {
            Map<String, Object> m = new HashMap<>();
            m.put("reportName", s.getReport() != null ? s.getReport().getName() : null);
            m.put("cronExpression", s.getCronExpression());
            m.put("frequencyLabel", s.getFrequencyLabel());
            m.put("timezone", s.getTimezone());
            m.put("preconditionEnabled", s.getPreconditionEnabled());
            m.put("preconditionSql", s.getPreconditionSql());
            m.put("alertEmails", s.getAlertEmails());
            m.put("alertOnFailure", s.getAlertOnFailure());
            schedules.add(m);
        }

        Map<String, Object> bundle = new HashMap<>();
        bundle.put("format", "acquira-integration-config");
        bundle.put("version", 1);
        bundle.put("exportedAt", LocalDateTime.now().toString());
        bundle.put("connections", connections);
        bundle.put("reports", reports);
        bundle.put("schedules", schedules);
        return ResponseEntity.ok(bundle);
    }

    /**
     * Import a bundle produced by /export. Upserts by name, and is deliberately
     * conservative about anything security-relevant:
     *  - passwords never travel: NEW connections arrive INACTIVE with no
     *    password (set it, test it, then activate);
     *  - approvals never travel: new or changed report SQL lands unapproved and
     *    a SUPER_ADMIN must re-approve in the target environment;
     *  - NEW schedules arrive disabled so nothing fires by surprise.
     * SUPER_ADMIN only — this writes SQL that will run against source systems.
     */
    @org.springframework.security.access.prepost.PreAuthorize(SQL_AUTHORING)
    @PostMapping("/import")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> importConfig(@RequestBody Map<String, Object> bundle) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (!"acquira-integration-config".equals(bundle.get("format"))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Not an Acquira integration config bundle (missing format marker)"));
        }
        List<String> warnings = new ArrayList<>();
        int connCreated = 0, connUpdated = 0, repCreated = 0, repUpdated = 0, schCreated = 0, schUpdated = 0;
        boolean approvalsCleared = false;

        // ── Connections ─────────────────────────────────────────
        List<IntegrationConnection> existingConns = connectionRepo.findByTenantIdOrderByNameAsc(tenantId);
        for (Map<String, Object> m : (List<Map<String, Object>>) bundle.getOrDefault("connections", List.of())) {
            String name = (String) m.get("name");
            if (name == null || name.isBlank()) { warnings.add("Skipped a connection with no name"); continue; }
            IntegrationConnection c = existingConns.stream()
                    .filter(x -> x.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            boolean isNew = c == null;
            if (isNew) {
                c = new IntegrationConnection();
                c.setTenantId(tenantId);
                c.setName(name);
                c.setCreatedAt(LocalDateTime.now());
                // No password travels in a bundle — keep it inert until one is set.
                c.setIsActive(false);
                warnings.add("Connection '" + name + "' created INACTIVE — set its password, test, then activate.");
            }
            if (m.get("dbType") != null) c.setDbType(IntegrationConnection.DbType.valueOf(m.get("dbType").toString()));
            if (m.get("host") != null) c.setHost((String) m.get("host"));
            if (m.get("port") != null) c.setPort(((Number) m.get("port")).intValue());
            if (m.get("dbName") != null) c.setDbName((String) m.get("dbName"));
            if (m.get("username") != null) c.setUsername((String) m.get("username"));
            if (m.get("timeoutSeconds") != null) c.setTimeoutSeconds(((Number) m.get("timeoutSeconds")).intValue());
            if (m.get("maxRetries") != null) c.setMaxRetries(((Number) m.get("maxRetries")).intValue());
            if (m.get("trustServerCert") != null) c.setTrustServerCert((Boolean) m.get("trustServerCert"));
            c.setUpdatedAt(LocalDateTime.now());
            connectionRepo.save(c);
            if (isNew) { connCreated++; existingConns.add(c); } else connUpdated++;
        }

        // ── Reports ─────────────────────────────────────────────
        List<IntegrationReport> existingReports = reportRepo.findByTenantIdOrderByNameAsc(tenantId);
        for (Map<String, Object> m : (List<Map<String, Object>>) bundle.getOrDefault("reports", List.of())) {
            String name = (String) m.get("name");
            String connectionName = (String) m.get("connectionName");
            if (name == null || name.isBlank()) { warnings.add("Skipped a report with no name"); continue; }
            IntegrationConnection conn = connectionName == null ? null : existingConns.stream()
                    .filter(x -> x.getName().equalsIgnoreCase(connectionName)).findFirst().orElse(null);
            if (conn == null) {
                warnings.add("Skipped report '" + name + "' — its connection '" + connectionName + "' does not exist here.");
                continue;
            }
            IntegrationReport r = existingReports.stream()
                    .filter(x -> x.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            boolean isNew = r == null;
            if (isNew) {
                r = new IntegrationReport();
                r.setTenantId(tenantId);
                r.setName(name);
                r.setCreatedAt(LocalDateTime.now());
            }
            String newSql = (String) m.get("sqlText");
            boolean sqlChanged = newSql != null && !newSql.equals(r.getSqlText());
            r.setConnection(conn);
            if (m.get("reportType") != null) r.setReportType(IntegrationReport.ReportType.valueOf(m.get("reportType").toString()));
            if (newSql != null) r.setSqlText(newSql);
            if (m.containsKey("columnMapping")) r.setColumnMapping((String) m.get("columnMapping"));
            if (m.containsKey("paramSchema")) r.setParamSchema((String) m.get("paramSchema"));
            if (m.get("amountsMinorUnits") != null) r.setAmountsMinorUnits((Boolean) m.get("amountsMinorUnits"));
            if (m.containsKey("description")) r.setDescription((String) m.get("description"));
            if (m.get("isActive") != null) r.setIsActive((Boolean) m.get("isActive"));
            if (isNew || sqlChanged) {
                // Approvals never travel between environments.
                r.setApprovedBy(null);
                r.setApprovedAt(null);
                approvalsCleared = true;
            }
            r.setUpdatedAt(LocalDateTime.now());
            reportRepo.save(r);
            if (isNew) { repCreated++; existingReports.add(r); } else repUpdated++;
        }

        // ── Schedules (matched by report + cron) ────────────────
        List<IntegrationSchedule> existingSchedules = scheduleRepo.findByTenantIdOrderByCreatedAtDesc(tenantId);
        for (Map<String, Object> m : (List<Map<String, Object>>) bundle.getOrDefault("schedules", List.of())) {
            String reportName = (String) m.get("reportName");
            String cron = (String) m.get("cronExpression");
            IntegrationReport report = reportName == null ? null : existingReports.stream()
                    .filter(x -> x.getName().equalsIgnoreCase(reportName)).findFirst().orElse(null);
            if (report == null) {
                warnings.add("Skipped a schedule — its report '" + reportName + "' does not exist here.");
                continue;
            }
            String cronErr = validateCron(cron);
            if (cronErr != null) {
                warnings.add("Skipped a schedule for '" + reportName + "' — " + cronErr);
                continue;
            }
            final IntegrationReport rep = report;
            IntegrationSchedule s = existingSchedules.stream()
                    .filter(x -> x.getReport() != null && x.getReport().getId().equals(rep.getId()))
                    .filter(x -> cron.trim().equals(x.getCronExpression() != null ? x.getCronExpression().trim() : null))
                    .findFirst().orElse(null);
            boolean isNew = s == null;
            if (isNew) {
                s = new IntegrationSchedule();
                s.setTenantId(tenantId);
                s.setReport(report);
                s.setCronExpression(cron.trim());
                s.setCreatedAt(LocalDateTime.now());
                s.setIsEnabled(false); // nothing fires by surprise after an import
            }
            if (m.get("frequencyLabel") != null) s.setFrequencyLabel((String) m.get("frequencyLabel"));
            if (m.get("timezone") != null) s.setTimezone((String) m.get("timezone"));
            if (m.get("preconditionEnabled") != null) s.setPreconditionEnabled((Boolean) m.get("preconditionEnabled"));
            if (m.containsKey("preconditionSql")) s.setPreconditionSql((String) m.get("preconditionSql"));
            if (m.containsKey("alertEmails")) s.setAlertEmails((String) m.get("alertEmails"));
            if (m.get("alertOnFailure") != null) s.setAlertOnFailure((Boolean) m.get("alertOnFailure"));
            s.setUpdatedAt(LocalDateTime.now());
            IntegrationSchedule saved = scheduleRepo.save(s);
            if (!isNew && Boolean.TRUE.equals(saved.getIsEnabled())) schedulerService.reloadSchedule(saved);
            if (isNew) { schCreated++; existingSchedules.add(saved); } else schUpdated++;
        }

        if (approvalsCleared) {
            warnings.add("Imported report SQL is NOT approved — a Super Admin must review and approve each report before it runs.");
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("connectionsCreated", connCreated);
        summary.put("connectionsUpdated", connUpdated);
        summary.put("reportsCreated", repCreated);
        summary.put("reportsUpdated", repUpdated);
        summary.put("schedulesCreated", schCreated);
        summary.put("schedulesUpdated", schUpdated);
        summary.put("warnings", warnings);
        return ResponseEntity.ok(summary);
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

        // Tenant-isolation: schedulerService.getActiveCount() is the size of a
        // GLOBAL in-memory task map (all tenants), so every tenant admin saw the
        // platform-wide schedule count. Count only this tenant's enabled schedules.
        long activeSchedules = scheduleRepo.countByTenantIdAndIsEnabledTrue(tenantId);

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
