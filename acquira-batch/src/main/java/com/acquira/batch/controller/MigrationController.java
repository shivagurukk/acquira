package com.acquira.batch.controller;

import com.acquira.batch.service.BulkMigrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ADMIN API for Bulk Data Migration
 * #8: Added input validation
 * #13: Added audit logging
 */
@RestController
@RequestMapping("/api/admin/migration")
public class MigrationController {

    private final BulkMigrationService migrationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.acquira.common.service.AuditService auditService;

    public MigrationController(BulkMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    // #8: Validation pattern for table names
    private static final java.util.regex.Pattern SAFE_TABLE = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    private static final java.util.regex.Pattern MONTH_PATTERN = java.util.regex.Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final java.util.regex.Pattern DATE_PATTERN = java.util.regex.Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$");

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startMigration(@RequestBody Map<String, Object> request) {
        // #8: Validate required fields
        if (request.get("tenantId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        String sourceTable = (String) request.get("sourceTable");
        String startMonth = (String) request.get("startMonth");
        String endMonth = (String) request.get("endMonth");

        if (sourceTable == null || sourceTable.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceTable is required"));
        }
        if (!SAFE_TABLE.matcher(sourceTable).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid source table name. Only letters, digits, underscores allowed."));
        }
        if (startMonth == null || !MONTH_PATTERN.matcher(startMonth).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "startMonth must be in YYYY-MM format"));
        }
        if (endMonth == null || !MONTH_PATTERN.matcher(endMonth).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "endMonth must be in YYYY-MM format"));
        }
        if (startMonth.compareTo(endMonth) > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "startMonth must be before or equal to endMonth"));
        }

        Long tenantId = Long.valueOf(request.get("tenantId").toString());

        @SuppressWarnings("unchecked")
        Map<String, String> columnMapping = (Map<String, String>) request.getOrDefault("columnMapping", Map.of());

        // #8: Validate required column mappings
        if (!columnMapping.containsKey("mid") || columnMapping.get("mid").isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "columnMapping must include 'mid' (merchant ID column)"));
        }
        if (!columnMapping.containsKey("payment_date") || columnMapping.get("payment_date").isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "columnMapping must include 'payment_date' column"));
        }
        if (!columnMapping.containsKey("txn_currency_amount") || columnMapping.get("txn_currency_amount").isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "columnMapping must include 'txn_currency_amount' column"));
        }

        // #13: Audit
        if (auditService != null) {
            auditService.log("MIGRATION_START", "Started migration from '" + sourceTable + "' range " + startMonth + " to " + endMonth + " tenant=" + tenantId);
        }

        // Run async
        Thread migrationThread = new Thread(() -> {
            migrationService.startMigration(tenantId, sourceTable, startMonth, endMonth, columnMapping);
        }, "bulk-migration");
        migrationThread.setDaemon(true);
        migrationThread.start();

        return ResponseEntity.ok(Map.of(
            "status", "STARTED",
            "message", "Migration started in background. Poll /api/admin/migration/progress for updates.",
            "sourceTable", sourceTable,
            "range", startMonth + " to " + endMonth
        ));
    }

    @GetMapping("/progress")
    public ResponseEntity<Map<String, Object>> getProgress() {
        return ResponseEntity.ok(migrationService.getProgress());
    }

    /**
     * SUPER-ADMIN ONLY: full-day delete (correction tool).
     *
     * Wipes ALL transactions (both AMS and CMM) for one tenant + one date, and cleans up
     * every summary table (rebuilding the monthly rollups from the remaining days) so the
     * dashboards show the day as empty. Requires an explicit confirm flag so it can't fire
     * by accident. The day is left empty — re-upload a file for that date to repopulate.
     *
     * Body: { "tenantId": <id>, "date": "YYYY-MM-DD", "confirm": true }
     */
    @PostMapping("/delete-day")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteDay(@RequestBody Map<String, Object> request) {
        if (request.get("tenantId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        String date = request.get("date") == null ? null : request.get("date").toString().trim();
        if (date == null || !DATE_PATTERN.matcher(date).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "date must be in YYYY-MM-DD format"));
        }
        // Require explicit confirmation — this is a destructive, irreversible operation.
        Object confirm = request.get("confirm");
        boolean confirmed = Boolean.TRUE.equals(confirm) || "true".equalsIgnoreCase(String.valueOf(confirm));
        if (!confirmed) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "This permanently deletes all transactions for the day. Resend with \"confirm\": true."));
        }

        Long tenantId = Long.valueOf(request.get("tenantId").toString());
        java.time.LocalDate parsedDate = java.time.LocalDate.parse(date);

        if (auditService != null) {
            auditService.log("DELETE_DAY",
                "Super-admin full-day delete: tenant=" + tenantId + " date=" + date);
        }

        try {
            Map<String, Object> removed = migrationService.deleteDay(tenantId, parsedDate);
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("status", "DELETED");
            resp.put("tenantId", tenantId);
            resp.put("date", date);
            resp.put("removed", removed);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "FAILED", "error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/dry-run")
    public ResponseEntity<Map<String, Object>> dryRun(@RequestBody Map<String, Object> request) {
        if (request.get("tenantId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        String sourceTable = (String) request.get("sourceTable");
        if (sourceTable == null || sourceTable.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceTable is required"));
        }
        if (!SAFE_TABLE.matcher(sourceTable).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid source table name"));
        }

        Long tenantId = Long.valueOf(request.get("tenantId").toString());

        @SuppressWarnings("unchecked")
        Map<String, String> columnMapping = (Map<String, String>) request.getOrDefault("columnMapping", Map.of());

        return ResponseEntity.ok(migrationService.dryRun(tenantId, sourceTable, columnMapping));
    }
}
