package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.core.config.ApiRateLimiter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    private final ApiRateLimiter rateLimiter;

    public ApiKeyController(PasswordEncoder passwordEncoder,
                            com.acquira.common.service.AuditService auditService,
                            ApiRateLimiter rateLimiter) {
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getKeys() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        var query = em.createNativeQuery(
            "SELECT key_id, name, key_prefix, permissions, is_active, created_at, " +
            "last_used, request_count, created_by, expires_at, rate_limit_per_minute, " +
            "allowed_ips, last_used_ip FROM api_key " +
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
            m.put("expiresAt", r[9]); m.put("rateLimitPerMinute", r[10]);
            m.put("allowedIps", r[11]); m.put("lastUsedIp", r[12]);
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

        // Optional lifecycle/security fields
        LocalDateTime expiresAt = null;
        Object expRaw = body.get("expiresAt");
        if (expRaw != null && !expRaw.toString().isBlank()) {
            try { expiresAt = LocalDate.parse(expRaw.toString()).atStartOfDay().plusDays(1); } // end-of-day inclusive
            catch (Exception ignore) {
                try { expiresAt = LocalDateTime.parse(expRaw.toString()); } catch (Exception ignore2) {}
            }
        }
        Integer rateLimit = null;
        Object rlRaw = body.get("rateLimitPerMinute");
        if (rlRaw != null) { try { rateLimit = Integer.valueOf(rlRaw.toString()); } catch (Exception ignore) {} }
        if (rateLimit == null || rateLimit <= 0) rateLimit = 120;
        String allowedIps = body.get("allowedIps") != null ? body.get("allowedIps").toString().trim() : null;
        if (allowedIps != null && allowedIps.isBlank()) allowedIps = null;

        em.createNativeQuery(
            "INSERT INTO api_key (tenant_id, name, key_hash, key_prefix, permissions, created_by, " +
            "expires_at, rate_limit_per_minute, allowed_ips) " +
            "VALUES (:tid, :name, :hash, :prefix, :perms, :user, :exp, :rate, :ips)")
            .setParameter("tid", tenantId)
            .setParameter("name", body.get("name"))
            .setParameter("hash", keyHash)
            .setParameter("prefix", keyPrefix)
            .setParameter("perms", permsJson)
            .setParameter("user", username)
            .setParameter("exp", expiresAt)
            .setParameter("rate", rateLimit)
            .setParameter("ips", allowedIps)
            .executeUpdate();

        auditService.log("CREATE_API_KEY", "Created API key: " + body.get("name"));

        // Return the full key ONCE — never stored in plaintext
        return ResponseEntity.ok(Map.of(
            "apiKey", rawKey,
            "name", body.get("name"),
            "keyPrefix", keyPrefix
        ));
    }

    /** Update mutable fields on an existing key (name, permissions, expiry, rate limit, IP allowlist). */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateKey(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        // Ownership check
        Object cnt = em.createNativeQuery("SELECT COUNT(*) FROM api_key WHERE key_id=:id AND tenant_id=:tid")
            .setParameter("id", id).setParameter("tid", tenantId).getSingleResult();
        if (((Number) cnt).longValue() == 0) return ResponseEntity.notFound().build();

        StringBuilder set = new StringBuilder("UPDATE api_key SET updated_at = CURRENT_TIMESTAMP");
        Map<String, Object> params = new HashMap<>();
        if (body.containsKey("name")) { set.append(", name = :name"); params.put("name", body.get("name")); }
        if (body.containsKey("permissions") && body.get("permissions") instanceof List) {
            String permsJson = "[" + String.join(",",
                ((List<?>) body.get("permissions")).stream().map(p -> "\"" + p + "\"").toArray(String[]::new)) + "]";
            set.append(", permissions = :perms"); params.put("perms", permsJson);
        }
        if (body.containsKey("rateLimitPerMinute")) {
            Integer rl = null;
            try { rl = Integer.valueOf(body.get("rateLimitPerMinute").toString()); } catch (Exception ignore) {}
            if (rl != null && rl > 0) { set.append(", rate_limit_per_minute = :rate"); params.put("rate", rl); }
        }
        if (body.containsKey("allowedIps")) {
            String ips = body.get("allowedIps") != null ? body.get("allowedIps").toString().trim() : null;
            if (ips != null && ips.isBlank()) ips = null;
            set.append(", allowed_ips = :ips"); params.put("ips", ips);
        }
        if (body.containsKey("expiresAt")) {
            LocalDateTime exp = null;
            Object expRaw = body.get("expiresAt");
            if (expRaw != null && !expRaw.toString().isBlank()) {
                try { exp = LocalDate.parse(expRaw.toString()).atStartOfDay().plusDays(1); }
                catch (Exception ignore) { try { exp = LocalDateTime.parse(expRaw.toString()); } catch (Exception ignore2) {} }
            }
            set.append(", expires_at = :exp"); params.put("exp", exp);
        }

        set.append(" WHERE key_id = :id AND tenant_id = :tid");
        var q = em.createNativeQuery(set.toString());
        params.forEach(q::setParameter);
        q.setParameter("id", id).setParameter("tid", tenantId);
        q.executeUpdate();

        auditService.log("UPDATE_API_KEY", "Updated API key ID: " + id);
        return ResponseEntity.ok(Map.of("message", "API key updated"));
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

        rateLimiter.evict(id);
        auditService.log("REVOKE_API_KEY", "Revoked API key ID: " + id);
        return ResponseEntity.ok(Map.of("message", "API key revoked"));
    }

    // ─── Usage analytics (from api_request_log) ────────────────────────

    /** Per-key usage: 24h/7d request counts, error count, avg latency, and recent endpoints. */
    @GetMapping("/{id}/usage")
    public ResponseEntity<?> keyUsage(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        // Ownership check
        Object cnt = em.createNativeQuery("SELECT COUNT(*) FROM api_key WHERE key_id=:id AND tenant_id=:tid")
            .setParameter("id", id).setParameter("tid", tenantId).getSingleResult();
        if (((Number) cnt).longValue() == 0) return ResponseEntity.notFound().build();

        Map<String, Object> out = new LinkedHashMap<>();

        Object[] agg = (Object[]) em.createNativeQuery(
            "SELECT " +
            "  COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '24 hours') AS c24, " +
            "  COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '7 days') AS c7, " +
            "  COUNT(*) FILTER (WHERE status >= 400 AND created_at >= NOW() - INTERVAL '7 days') AS err7, " +
            "  COALESCE(ROUND(AVG(latency_ms) FILTER (WHERE created_at >= NOW() - INTERVAL '7 days')),0) AS avg_latency " +
            "FROM api_request_log WHERE tenant_id=:tid AND key_id=:id")
            .setParameter("tid", tenantId).setParameter("id", id)
            .getSingleResult();
        out.put("requests24h", agg[0]);
        out.put("requests7d", agg[1]);
        out.put("errors7d", agg[2]);
        out.put("avgLatencyMs", agg[3]);

        @SuppressWarnings("unchecked")
        List<Object[]> recent = em.createNativeQuery(
            "SELECT method, endpoint, status, client_ip, latency_ms, created_at " +
            "FROM api_request_log WHERE tenant_id=:tid AND key_id=:id " +
            "ORDER BY created_at DESC LIMIT 20")
            .setParameter("tid", tenantId).setParameter("id", id)
            .getResultList();
        List<Map<String, Object>> recentRows = new ArrayList<>();
        for (Object[] r : recent) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", r[0]); m.put("endpoint", r[1]); m.put("status", r[2]);
            m.put("clientIp", r[3]); m.put("latencyMs", r[4]); m.put("createdAt", r[5]);
            recentRows.add(m);
        }
        out.put("recent", recentRows);
        return ResponseEntity.ok(out);
    }

    /** Tenant-wide usage summary: total requests (24h/7d), active keys, top endpoints. */
    @GetMapping("/usage-summary")
    public ResponseEntity<?> usageSummary() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        Map<String, Object> out = new LinkedHashMap<>();

        Object[] agg = (Object[]) em.createNativeQuery(
            "SELECT " +
            "  COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '24 hours') AS c24, " +
            "  COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '7 days') AS c7, " +
            "  COUNT(*) FILTER (WHERE status >= 400 AND created_at >= NOW() - INTERVAL '7 days') AS err7 " +
            "FROM api_request_log WHERE tenant_id=:tid")
            .setParameter("tid", tenantId).getSingleResult();
        out.put("requests24h", agg[0]);
        out.put("requests7d", agg[1]);
        out.put("errors7d", agg[2]);

        Object activeKeys = em.createNativeQuery(
            "SELECT COUNT(*) FROM api_key WHERE tenant_id=:tid AND is_active=true")
            .setParameter("tid", tenantId).getSingleResult();
        out.put("activeKeys", activeKeys);

        @SuppressWarnings("unchecked")
        List<Object[]> top = em.createNativeQuery(
            "SELECT endpoint, COUNT(*) AS hits FROM api_request_log " +
            "WHERE tenant_id=:tid AND created_at >= NOW() - INTERVAL '7 days' " +
            "GROUP BY endpoint ORDER BY hits DESC LIMIT 8")
            .setParameter("tid", tenantId).getResultList();
        List<Map<String, Object>> topRows = new ArrayList<>();
        for (Object[] r : top) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("endpoint", r[0]); m.put("hits", r[1]);
            topRows.add(m);
        }
        out.put("topEndpoints", topRows);
        return ResponseEntity.ok(out);
    }
}
