package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin/alerts")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AlertController {

    @PersistenceContext
    private EntityManager em;

    private final com.acquira.common.service.AuditService auditService;

    public AlertController(com.acquira.common.service.AuditService auditService) {
        this.auditService = auditService;
    }

    // ===== ALERT RULES CRUD =====

    @GetMapping("/rules")
    public ResponseEntity<List<Map<String, Object>>> getRules() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        var query = em.createNativeQuery(
            "SELECT rule_id, name, description, metric, operator, threshold, severity, " +
            "recipients, is_active, check_frequency, scope, created_at, updated_at " +
            "FROM alert_rule WHERE tenant_id = :tid ORDER BY created_at DESC");
        query.setParameter("tid", tenantId);

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]); m.put("name", r[1]); m.put("description", r[2]);
            m.put("metric", r[3]); m.put("operator", r[4]); m.put("threshold", r[5]);
            m.put("severity", r[6]); m.put("recipients", r[7]); m.put("isActive", r[8]);
            m.put("checkFrequency", r[9]); m.put("scope", r[10]);
            m.put("createdAt", r[11]); m.put("updatedAt", r[12]);
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/rules")
    @Transactional
    public ResponseEntity<?> createRule(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        em.createNativeQuery(
            "INSERT INTO alert_rule (tenant_id, name, description, metric, operator, threshold, " +
            "severity, recipients, is_active, check_frequency, scope) " +
            "VALUES (:tid, :name, :desc, :metric, :op, :threshold, :severity, :recipients, :active, :freq, :scope)")
            .setParameter("tid", tenantId)
            .setParameter("name", body.get("name"))
            .setParameter("desc", body.get("description"))
            .setParameter("metric", body.get("metric"))
            .setParameter("op", body.getOrDefault("operator", ">"))
            .setParameter("threshold", body.getOrDefault("threshold", 0))
            .setParameter("severity", body.getOrDefault("severity", "WARNING"))
            .setParameter("recipients", body.get("recipients"))
            .setParameter("active", body.getOrDefault("isActive", true))
            .setParameter("freq", body.getOrDefault("checkFrequency", "DAILY"))
            .setParameter("scope", body.getOrDefault("scope", "ALL_MERCHANTS"))
            .executeUpdate();

        auditService.log("CREATE_ALERT_RULE", "Created alert rule: " + body.get("name"));
        return ResponseEntity.ok(Map.of("message", "Alert rule created"));
    }

    @PutMapping("/rules/{id}")
    @Transactional
    public ResponseEntity<?> updateRule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        em.createNativeQuery(
            "UPDATE alert_rule SET name=:name, description=:desc, metric=:metric, operator=:op, " +
            "threshold=:threshold, severity=:severity, recipients=:recipients, is_active=:active, " +
            "check_frequency=:freq, scope=:scope, updated_at=CURRENT_TIMESTAMP " +
            "WHERE rule_id=:id AND tenant_id=:tid")
            .setParameter("tid", tenantId)
            .setParameter("id", id)
            .setParameter("name", body.get("name"))
            .setParameter("desc", body.get("description"))
            .setParameter("metric", body.get("metric"))
            .setParameter("op", body.getOrDefault("operator", ">"))
            .setParameter("threshold", body.getOrDefault("threshold", 0))
            .setParameter("severity", body.getOrDefault("severity", "WARNING"))
            .setParameter("recipients", body.get("recipients"))
            .setParameter("active", body.getOrDefault("isActive", true))
            .setParameter("freq", body.getOrDefault("checkFrequency", "DAILY"))
            .setParameter("scope", body.getOrDefault("scope", "ALL_MERCHANTS"))
            .executeUpdate();

        auditService.log("UPDATE_ALERT_RULE", "Updated alert rule ID: " + id);
        return ResponseEntity.ok(Map.of("message", "Alert rule updated"));
    }

    @DeleteMapping("/rules/{id}")
    @Transactional
    public ResponseEntity<?> deleteRule(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        em.createNativeQuery("DELETE FROM alert_rule WHERE rule_id=:id AND tenant_id=:tid")
            .setParameter("id", id).setParameter("tid", tenantId).executeUpdate();

        auditService.log("DELETE_ALERT_RULE", "Deleted alert rule ID: " + id);
        return ResponseEntity.ok(Map.of("message", "Alert rule deleted"));
    }

    // ===== ALERT HISTORY =====

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        var query = em.createNativeQuery(
            "SELECT alert_id, rule_name, severity, merchant_name, message, metric_value, " +
            "acknowledged, triggered_at FROM alert_history " +
            "WHERE tenant_id = :tid ORDER BY triggered_at DESC LIMIT :limit");
        query.setParameter("tid", tenantId);
        query.setParameter("limit", limit);

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]); m.put("ruleName", r[1]); m.put("severity", r[2]);
            m.put("merchantName", r[3]); m.put("message", r[4]); m.put("metricValue", r[5]);
            m.put("acknowledged", r[6]); m.put("triggeredAt", r[7]);
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/history/{id}/acknowledge")
    @Transactional
    public ResponseEntity<?> acknowledgeAlert(@PathVariable Long id) {
        // Tenant-isolation fix: scope the acknowledge to the active tenant so a
        // bank admin can't acknowledge another tenant's alert by guessing its id.
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        String username = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication().getName();

        int updated = em.createNativeQuery(
            "UPDATE alert_history SET acknowledged=true, acknowledged_by=:user, " +
            "acknowledged_at=CURRENT_TIMESTAMP WHERE alert_id=:id AND tenant_id=:tid")
            .setParameter("id", id).setParameter("user", username).setParameter("tid", tenantId)
            .executeUpdate();

        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", "Alert acknowledged"));
    }
}
