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
