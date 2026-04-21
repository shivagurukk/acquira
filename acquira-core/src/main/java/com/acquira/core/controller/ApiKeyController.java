package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin/api-keys")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ApiKeyController {

    @PersistenceContext
    private EntityManager em;

    private final PasswordEncoder passwordEncoder;
    private final com.acquira.common.service.AuditService auditService;

    public ApiKeyController(PasswordEncoder passwordEncoder,
                            com.acquira.common.service.AuditService auditService) {
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getKeys() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        var query = em.createNativeQuery(
            "SELECT key_id, name, key_prefix, permissions, is_active, created_at, " +
            "last_used, request_count, created_by FROM api_key " +
            "WHERE tenant_id = :tid ORDER BY created_at DESC");
        query.setParameter("tid", tenantId);

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]); m.put("name", r[1]); m.put("keyPrefix", r[2]);
            // Parse permissions JSON
            String permStr = (String) r[3];
            if (permStr != null && permStr.startsWith("[")) {
                m.put("permissions", permStr.replace("[", "").replace("]", "")
                    .replace("\"", "").split(",\\s*"));
            } else {
                m.put("permissions", new String[]{});
            }
            m.put("isActive", r[4]); m.put("createdAt", r[5]);
            m.put("lastUsed", r[6]); m.put("requestCount", r[7]); m.put("createdBy", r[8]);
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createKey(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        String username = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication().getName();

        // Generate a secure API key
        String rawKey = "aqr_" + UUID.randomUUID().toString().replace("-", "");
        String keyHash = passwordEncoder.encode(rawKey);
        String keyPrefix = rawKey.substring(0, 12) + "...";

        // Convert permissions list to JSON string
        Object permsObj = body.get("permissions");
        String permsJson = "[]";
        if (permsObj instanceof List) {
            permsJson = "[" + String.join(",",
                ((List<?>) permsObj).stream()
                    .map(p -> "\"" + p + "\"")
                    .toArray(String[]::new)) + "]";
        }

        em.createNativeQuery(
            "INSERT INTO api_key (tenant_id, name, key_hash, key_prefix, permissions, created_by) " +
            "VALUES (:tid, :name, :hash, :prefix, :perms, :user)")
            .setParameter("tid", tenantId)
            .setParameter("name", body.get("name"))
            .setParameter("hash", keyHash)
            .setParameter("prefix", keyPrefix)
            .setParameter("perms", permsJson)
            .setParameter("user", username)
            .executeUpdate();

        auditService.log("CREATE_API_KEY", "Created API key: " + body.get("name"));

        // Return the full key ONCE — never stored in plaintext
        return ResponseEntity.ok(Map.of(
            "apiKey", rawKey,
            "name", body.get("name"),
            "keyPrefix", keyPrefix
        ));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> revokeKey(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        String username = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication().getName();

        em.createNativeQuery(
            "UPDATE api_key SET is_active=false, revoked_at=CURRENT_TIMESTAMP, revoked_by=:user " +
            "WHERE key_id=:id AND tenant_id=:tid")
            .setParameter("id", id).setParameter("tid", tenantId).setParameter("user", username)
            .executeUpdate();

        auditService.log("REVOKE_API_KEY", "Revoked API key ID: " + id);
        return ResponseEntity.ok(Map.of("message", "API key revoked"));
    }
}
