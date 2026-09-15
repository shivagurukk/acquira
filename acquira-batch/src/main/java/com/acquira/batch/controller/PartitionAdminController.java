package com.acquira.batch.controller;

import com.acquira.batch.service.PartitionMaintenanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ADMIN API for partition health — specifically the DEFAULT-partition drain.
 *
 * Rows that land in fact_transaction_default (historical backfills from before
 * the staging-range provisioning fix, or a lock-timeout on partition DDL) can
 * never be pruned, so every date-windowed query scans the whole default — and
 * partition creation for those months fails forever after. This controller
 * exposes the repair:
 *
 *   GET  /api/admin/partitions/default-status            cheap size/row estimate
 *   GET  /api/admin/partitions/default-status?detail=true  exact per-month scan
 *   POST /api/admin/partitions/drain-default {"confirm":true}
 *
 * The drain takes ACCESS EXCLUSIVE on fact_transaction for its duration — run
 * it when no ingestion is active. It executes in a background thread (moving
 * tens of GB outlives any HTTP timeout); poll default-status for progress.
 */
@RestController
@RequestMapping("/api/admin/partitions")
public class PartitionAdminController {

    private final PartitionMaintenanceService partitionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.acquira.common.service.AuditService auditService;

    public PartitionAdminController(PartitionMaintenanceService partitionService) {
        this.partitionService = partitionService;
    }

    private static final java.util.regex.Pattern SAFE_TABLE =
            java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    @GetMapping("/default-status")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> defaultStatus(
            @RequestParam(defaultValue = "fact_transaction") String table,
            @RequestParam(defaultValue = "false") boolean detail) {
        if (!SAFE_TABLE.matcher(table).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid table name"));
        }
        try {
            return ResponseEntity.ok(partitionService.defaultPartitionStatus(table, detail));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Body: { "table": "fact_transaction" (optional), "confirm": true }
     */
    @PostMapping("/drain-default")
    // SECURITY: table-wide DDL (DETACH/ATTACH under ACCESS EXCLUSIVE) affecting
    // EVERY tenant's transaction data — SUPER_ADMIN only, same posture as
    // /api/admin/migration/start.
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> drainDefault(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String table = body.get("table") == null ? "fact_transaction" : body.get("table").toString().trim();
        if (!SAFE_TABLE.matcher(table).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid table name"));
        }

        Object confirm = body.get("confirm");
        boolean confirmed = Boolean.TRUE.equals(confirm) || "true".equalsIgnoreCase(String.valueOf(confirm));
        if (!confirmed) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "This locks " + table + " exclusively while stranded rows are moved into "
                            + "monthly partitions. Make sure no upload is running, then resend with \"confirm\": true."));
        }

        if (partitionService.isDrainRunning()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "A default-partition drain is already running.",
                    "drain", partitionService.getDrainProgress()));
        }

        if (auditService != null) {
            auditService.log("PARTITION_DRAIN_DEFAULT",
                    "Super-admin default-partition drain started for table=" + table);
        }

        final String targetTable = table;
        Thread drainThread = new Thread(() -> {
            try {
                partitionService.drainDefaultPartition(targetTable);
            } catch (Exception e) {
                // Progress already carries the FAILED phase; the thread must not die noisily.
            }
        }, "partition-drain");
        drainThread.setDaemon(true);
        drainThread.start();

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "table", table,
                "message", "Drain started in background. Poll GET /api/admin/partitions/default-status for progress."));
    }
}
