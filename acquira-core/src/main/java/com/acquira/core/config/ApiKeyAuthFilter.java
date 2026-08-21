package com.acquira.core.config;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.Tenant;
import com.acquira.common.repository.TenantRepository;
import com.acquira.common.security.ApiKeyPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Single authentication spine for external API-key traffic.
 *
 * Applies to {@code /api/v1/**} (data products) and {@code /api/external/**} (PDF reports).
 * Per request:
 *   1. Read X-API-Key.
 *   2. Prefix lookup (indexed) among active keys, then BCrypt-verify the full key.
 *   3. Enforce is_active, expiry, and IP allowlist.
 *   4. Resolve the owning tenant FROM the key row (the key is the tenant boundary).
 *   5. Set TenantContext + an ApiKeyPrincipal request attribute.
 *   6. Per-key rate limit (in-memory; single-replica safe).
 *   7. On completion, best-effort log to api_request_log and bump usage counters.
 *
 * A legacy static break-glass key (external.api.key) is accepted only when
 * external.api.allow-static-key=true; it is all-tenant and must carry a tenantCode.
 *
 * The OpenAPI document ({@code /api/v1/openapi.json}) is public (no key) — it leaks
 * no tenant data and integrators need it before they hold credentials.
 *
 * This filter authenticates but does NOT authorize scopes — controllers assert the
 * scope they need via ApiScopes.require(request, "read:xxx").
 */
@Component
@Order(5)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Public paths under the API surface that do NOT require a key. */
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/v1/openapi.json");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TenantRepository tenantRepository;
    private final ApiRateLimiter rateLimiter;

    @Value("${external.api.key:}")
    private String staticApiKey;

    @Value("${external.api.allow-static-key:false}")
    private boolean allowStaticKey;

    public ApiKeyAuthFilter(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                            TenantRepository tenantRepository, ApiRateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.tenantRepository = tenantRepository;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        if (PUBLIC_PATHS.contains(p)) return true;               // public spec — no key
        return !(p.startsWith("/api/v1/") || p.startsWith("/api/external/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long start = System.currentTimeMillis();
        String apiKey = request.getHeader("X-API-Key");
        String clientIp = clientIp(request);
        String requestedTenant = request.getParameter("tenantCode");

        AuthResult auth = authenticate(apiKey, requestedTenant, clientIp);
        if (!auth.ok) {
            writeError(response, auth.status, auth.message);
            return;
        }

        ApiKeyPrincipal principal = auth.principal;

        // Rate limit (per key). Static break-glass key is not rate limited here.
        if (!principal.isStaticKey()) {
            if (!rateLimiter.allow(principal.getKeyId(), auth.rateLimitPerMinute)) {
                response.setHeader("Retry-After", "60");
                writeError(response, 429, "Rate limit exceeded — try again in a minute");
                return;
            }
            response.setHeader("X-RateLimit-Limit", String.valueOf(auth.rateLimitPerMinute));
            response.setHeader("X-RateLimit-Remaining",
                    String.valueOf(rateLimiter.remaining(principal.getKeyId(), auth.rateLimitPerMinute)));
        }

        int status = 200;
        try {
            // Establish tenant context for the downstream query layer.
            TenantContext.setCurrentTenant(principal.getTenantId());
            request.setAttribute(ApiKeyPrincipal.ATTR, principal);
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            TenantContext.clear();
            long latency = System.currentTimeMillis() - start;
            recordUsage(principal, request, status, clientIp, latency);
        }
    }

    // ─── Authentication ────────────────────────────────────────────────

    private static final class AuthResult {
        boolean ok;
        int status;
        String message;
        ApiKeyPrincipal principal;
        int rateLimitPerMinute = 120;

        static AuthResult fail(int status, String message) {
            AuthResult r = new AuthResult(); r.ok = false; r.status = status; r.message = message; return r;
        }
        static AuthResult ok(ApiKeyPrincipal p, int rate) {
            AuthResult r = new AuthResult(); r.ok = true; r.status = 200; r.principal = p; r.rateLimitPerMinute = rate; return r;
        }
    }

    private AuthResult authenticate(String apiKey, String requestedTenantCode, String clientIp) {
        if (apiKey == null || apiKey.isBlank()) {
            return AuthResult.fail(401, "Missing API key");
        }

        // 1. DB-issued, tenant-bound key (preferred).
        if (apiKey.length() >= 12) {
            String prefix = apiKey.substring(0, 12) + "...";
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.queryForList(
                        "SELECT key_id, tenant_id, key_hash, permissions, expires_at, " +
                        "rate_limit_per_minute, allowed_ips FROM api_key " +
                        "WHERE is_active = true AND key_prefix = ?", prefix);
            } catch (Exception e) {
                log.warn("[API-AUTH] key lookup failed: {}", e.getMessage());
                rows = List.of();
            }
            for (Map<String, Object> r : rows) {
                String hash = (String) r.get("key_hash");
                if (hash == null || !passwordEncoder.matches(apiKey, hash)) continue;

                // Expiry
                Object exp = r.get("expires_at");
                if (exp instanceof Timestamp ts && ts.toLocalDateTime().isBefore(LocalDateTime.now())) {
                    return AuthResult.fail(401, "API key has expired");
                }
                // IP allowlist
                String allowedIps = (String) r.get("allowed_ips");
                if (!ipAllowed(allowedIps, clientIp)) {
                    log.warn("[API-AUTH] key {} used from disallowed IP {}", r.get("key_id"), clientIp);
                    return AuthResult.fail(403, "Source IP is not allowed for this API key");
                }

                Long keyId = ((Number) r.get("key_id")).longValue();
                Long tenantId = ((Number) r.get("tenant_id")).longValue();
                Tenant t = tenantRepository.findById(tenantId).orElse(null);
                if (t == null) return AuthResult.fail(403, "Key tenant no longer exists");
                String keyCode = t.getBankShortCode();

                // A supplied tenantCode may only match (never widen) the key's own tenant.
                if (requestedTenantCode != null && !requestedTenantCode.isBlank()
                        && !requestedTenantCode.equalsIgnoreCase(keyCode)) {
                    return AuthResult.fail(403, "API key is not authorized for the requested tenant");
                }

                Set<String> scopes = parseScopes((String) r.get("permissions"));
                int rate = r.get("rate_limit_per_minute") != null
                        ? ((Number) r.get("rate_limit_per_minute")).intValue() : 120;
                ApiKeyPrincipal p = new ApiKeyPrincipal(keyId, tenantId, keyCode, scopes, false);
                return AuthResult.ok(p, rate);
            }
        }

        // 2. Legacy static break-glass key — all-tenant, off by default, requires tenantCode.
        if (allowStaticKey && staticApiKey != null && !staticApiKey.isBlank()
                && constantTimeEquals(staticApiKey, apiKey)) {
            if (requestedTenantCode == null || requestedTenantCode.isBlank()) {
                return AuthResult.fail(400, "tenantCode is required when using the static API key");
            }
            Tenant t = tenantRepository.findAll().stream()
                    .filter(x -> requestedTenantCode.equalsIgnoreCase(x.getBankShortCode()))
                    .findFirst().orElse(null);
            if (t == null) return AuthResult.fail(403, "Invalid tenant code");
            log.warn("[API-AUTH] static all-tenant key used for tenant '{}'. Prefer DB-issued keys.", requestedTenantCode);
            ApiKeyPrincipal p = new ApiKeyPrincipal(null, t.getTenantId(), t.getBankShortCode(), Set.of(), true);
            return AuthResult.ok(p, Integer.MAX_VALUE);
        }

        return AuthResult.fail(401, "Invalid API key");
    }

    // ─── Usage logging (best-effort; never fails the request) ──────────

    private void recordUsage(ApiKeyPrincipal p, HttpServletRequest req, int status, String clientIp, long latencyMs) {
        try {
            jdbc.update(
                "INSERT INTO api_request_log (tenant_id, key_id, method, endpoint, status, client_ip, latency_ms) " +
                "VALUES (?,?,?,?,?,?,?)",
                p.getTenantId(), p.getKeyId(), req.getMethod(),
                truncate(req.getRequestURI(), 300), status, truncate(clientIp, 64), (int) latencyMs);
            if (p.getKeyId() != null) {
                jdbc.update(
                    "UPDATE api_key SET last_used = CURRENT_TIMESTAMP, last_used_ip = ?, " +
                    "request_count = COALESCE(request_count,0) + 1 WHERE key_id = ?",
                    truncate(clientIp, 64), p.getKeyId());
            }
        } catch (Exception e) {
            log.debug("[API-AUTH] usage log skipped: {}", e.getMessage());
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private Set<String> parseScopes(String permsJson) {
        if (permsJson == null || permsJson.isBlank()) return Set.of();
        String s = permsJson.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        Set<String> out = new HashSet<>();
        for (String tok : s.split(",")) {
            String v = tok.trim().replace("\"", "");
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** allowedIps blank/null → any. Otherwise exact-match against comma-separated list (IPs). */
    private boolean ipAllowed(String allowedIps, String clientIp) {
        if (allowedIps == null || allowedIps.isBlank()) return true;
        if (clientIp == null) return false;
        for (String entry : allowedIps.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            if (e.equals(clientIp)) return true;
        }
        return false;
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }

    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\",\"status\":" + status + "}");
    }
}
