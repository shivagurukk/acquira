package com.acquira.batch.controller;

import com.acquira.batch.service.BulkMigrationService;
import com.acquira.batch.service.MerchantDedupService;
import com.acquira.common.config.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SUPER-ADMIN API: merge duplicate dim_merchant rows sharing one MID code (see
 * {@link MerchantDedupService}). Tenant is always the active one (X-Tenant-Id via
 * TenantContext), never a body parameter — same posture as /rebuild-summaries.
 *
 *   GET  /api/admin/merchant-dedup/preview           — read-only counts
 *   POST /api/admin/merchant-dedup/apply {confirm}   — runs in the background
 *   GET  /api/admin/merchant-dedup/status            — last run's result
 */
@RestController
@RequestMapping("/api/admin/merchant-dedup")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class MerchantDedupController {

    private final MerchantDedupService service;
    private final BulkMigrationService migrationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.acquira.common.service.AuditService auditService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.acquira.common.service.ReportCache reportCache;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> lastResult = Map.of("status", "IDLE");

    public MerchantDedupController(MerchantDedupService service, BulkMigrationService migrationService) {
        this.service = service;
        this.migrationService = migrationService;
    }

    @GetMapping("/preview")
    public ResponseEntity<?> preview() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return noTenant();
        return ResponseEntity.ok(service.preview(tenantId));
    }

    /** Body: { "confirm": true } — rewrites dimension/fact keys; not reversible. */
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody(required = false) Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return noTenant();
        Object confirm = body == null ? null : body.get("confirm");
        if (!(Boolean.TRUE.equals(confirm) || "true".equalsIgnoreCase(String.valueOf(confirm)))) {
            return ResponseEntity.badRequest().body(Map.of("error",
                "This merges duplicate merchants and rebuilds the affected summaries for the current tenant. "
                    + "Check /preview, then resend with \"confirm\": true."));
        }
        if (migrationService.isRunning()) {
            return ResponseEntity.status(409).body(Map.of("error",
                "A migration or summary rebuild is running. Wait for it to finish."));
        }
        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.status(409).body(Map.of("error", "A merchant merge is already running."));
        }
        if (auditService != null) {
            auditService.log("MERCHANT_DEDUP", "Super-admin duplicate-MID merge: tenant=" + tenantId);
        }

        lastResult = Map.of("status", "RUNNING", "tenantId", tenantId);
        Thread worker = new Thread(() -> {
            // Propagate tenant so TenantAspect sets the RLS backstop on this thread.
            TenantContext.setCurrentTenant(tenantId);
            try {
                Map<String, Object> r = new LinkedHashMap<>(service.merge(tenantId));
                r.put("status", r.containsKey("summaryRebuildError") ? "MERGED_REBUILD_FAILED" : "COMPLETED");
                lastResult = r;
            } catch (Exception e) {
                lastResult = Map.of("status", "FAILED", "tenantId", tenantId, "error", String.valueOf(e.getMessage()));
            } finally {
                reportCache.evict("merchant dedup", tenantId);
                TenantContext.clear();
                running.set(false);
            }
        }, "merchant-dedup");
        worker.setDaemon(true);
        worker.start();
        return ResponseEntity.accepted().body(Map.of("status", "RUNNING", "tenantId", tenantId));
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(lastResult);
    }

    private static ResponseEntity<?> noTenant() {
        return ResponseEntity.badRequest().body(Map.of("error",
            "No active tenant. Switch into the tenant you want to clean up first."));
    }
}
