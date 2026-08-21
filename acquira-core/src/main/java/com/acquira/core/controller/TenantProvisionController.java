package com.acquira.core.controller;

import com.acquira.core.service.TenantProvisioningService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Super-admin management of the tenant provisioning script registry and the
 * schema migration registry.
 *
 *  - Provision scripts: CRUD + per-tenant run + execution log. Scripts run
 *    automatically on tenant creation (BankController / AdminController hook)
 *    and can be re-run here (all scripts are required to be idempotent).
 *  - Migration registry: read-only list + applied_on_prod toggle (the files
 *    in db/migration remain the landing mechanism; the registry is the
 *    visibility layer).
 *
 * Platform-level (cross-tenant DDL/seed capability) — SUPER_ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/provision")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantProvisionController {

    private final JdbcTemplate jdbc;
    private final TenantProvisioningService provisioningService;
    private final com.acquira.common.service.AuditService auditService;

    public TenantProvisionController(JdbcTemplate jdbc,
                                     TenantProvisioningService provisioningService,
                                     com.acquira.common.service.AuditService auditService) {
        this.jdbc = jdbc;
        this.provisioningService = provisioningService;
        this.auditService = auditService;
    }

    // ── Provision scripts CRUD ──────────────────────────────────────────────

    @GetMapping("/scripts")
    public ResponseEntity<List<Map<String, Object>>> listScripts() {
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT script_id, script_name, script_order, script_sql, description, "
                        + "is_active, continue_on_error, created_by, created_at, updated_at "
                        + "FROM tenant_provision_script ORDER BY script_order ASC, script_id ASC"));
    }

    @PostMapping("/scripts")
    public ResponseEntity<?> createScript(@RequestBody Map<String, Object> body) {
        String name = str(body.get("scriptName"));
        String sql = str(body.get("scriptSql"));
        if (name == null || name.isBlank() || sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "scriptName and scriptSql are required"));
        }
        Integer order = body.get("scriptOrder") != null ? ((Number) body.get("scriptOrder")).intValue() : 100;
        boolean active = body.get("isActive") == null || Boolean.TRUE.equals(body.get("isActive"));
        boolean coe = Boolean.TRUE.equals(body.get("continueOnError"));
        try {
            jdbc.update("INSERT INTO tenant_provision_script "
                            + "(script_name, script_order, script_sql, description, is_active, continue_on_error, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    name.trim(), order, sql, str(body.get("description")), active, coe, currentUser());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "A script with that name already exists"));
        }
        auditService.log("PROVISION_SCRIPT_CREATE", "Created provisioning script: " + name);
        return ResponseEntity.ok(Map.of("message", "Script created"));
    }

    @PutMapping("/scripts/{id}")
    public ResponseEntity<?> updateScript(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String name = str(body.get("scriptName"));
        String sql = str(body.get("scriptSql"));
        if (name == null || name.isBlank() || sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "scriptName and scriptSql are required"));
        }
        Integer order = body.get("scriptOrder") != null ? ((Number) body.get("scriptOrder")).intValue() : 100;
        boolean active = body.get("isActive") == null || Boolean.TRUE.equals(body.get("isActive"));
        boolean coe = Boolean.TRUE.equals(body.get("continueOnError"));
        int updated = jdbc.update("UPDATE tenant_provision_script SET script_name = ?, script_order = ?, "
                        + "script_sql = ?, description = ?, is_active = ?, continue_on_error = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE script_id = ?",
                name.trim(), order, sql, str(body.get("description")), active, coe, id);
        if (updated == 0) return ResponseEntity.notFound().build();
        auditService.log("PROVISION_SCRIPT_UPDATE", "Updated provisioning script: " + name);
        return ResponseEntity.ok(Map.of("message", "Script updated"));
    }

    @DeleteMapping("/scripts/{id}")
    public ResponseEntity<?> deleteScript(@PathVariable Long id) {
        int deleted = jdbc.update("DELETE FROM tenant_provision_script WHERE script_id = ?", id);
        if (deleted == 0) return ResponseEntity.notFound().build();
        auditService.log("PROVISION_SCRIPT_DELETE", "Deleted provisioning script id=" + id);
        return ResponseEntity.ok(Map.of("message", "Script deleted"));
    }

    // ── Run + logs ──────────────────────────────────────────────────────────

    @PostMapping("/run/{tenantId}")
    public ResponseEntity<?> runForTenant(@PathVariable Long tenantId) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        if (exists == null || exists == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant not found"));
        }
        List<Map<String, Object>> results = provisioningService.provision(tenantId, currentUser());
        auditService.log("PROVISION_RUN", "Ran provisioning scripts for tenant " + tenantId
                + " (" + results.size() + " scripts)");
        return ResponseEntity.ok(Map.of("tenantId", tenantId, "results", results));
    }

    @GetMapping("/logs")
    public ResponseEntity<List<Map<String, Object>>> logs(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "200") int limit) {
        int capped = Math.min(Math.max(limit, 1), 1000);
        if (tenantId != null) {
            return ResponseEntity.ok(jdbc.queryForList(
                    "SELECT l.log_id, l.tenant_id, t.bank_name, l.script_name, l.status, "
                            + "l.error_message, l.duration_ms, l.executed_by, l.executed_at "
                            + "FROM tenant_provision_log l LEFT JOIN tenant t ON t.tenant_id = l.tenant_id "
                            + "WHERE l.tenant_id = ? ORDER BY l.executed_at DESC LIMIT " + capped,
                    tenantId));
        }
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT l.log_id, l.tenant_id, t.bank_name, l.script_name, l.status, "
                        + "l.error_message, l.duration_ms, l.executed_by, l.executed_at "
                        + "FROM tenant_provision_log l LEFT JOIN tenant t ON t.tenant_id = l.tenant_id "
                        + "ORDER BY l.executed_at DESC LIMIT " + capped));
    }

    // ── Migration registry ──────────────────────────────────────────────────

    @GetMapping("/registry")
    public ResponseEntity<List<Map<String, Object>>> registry() {
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT registry_id, migration_name, description, applied_on_dev, applied_on_prod, "
                        + "applied_at, applied_by, created_at "
                        + "FROM schema_migration_registry ORDER BY migration_name ASC"));
    }

    @PutMapping("/registry/{id}/prod-applied")
    public ResponseEntity<?> markProdApplied(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean applied = Boolean.TRUE.equals(body.get("applied"));
        int updated = jdbc.update("UPDATE schema_migration_registry SET applied_on_prod = ?, "
                        + "applied_by = COALESCE(applied_by, ?) WHERE registry_id = ?",
                applied, currentUser(), id);
        if (updated == 0) return ResponseEntity.notFound().build();
        auditService.log("MIGRATION_REGISTRY_UPDATE",
                "Marked migration registry id=" + id + " prod-applied=" + applied);
        return ResponseEntity.ok(Map.of("message", "Registry updated"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
