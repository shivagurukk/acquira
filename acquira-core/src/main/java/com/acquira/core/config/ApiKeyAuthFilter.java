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
    private final ApiKeyVerificationCache verificationCache;

    @Value("${external.api.key:}")
    private String staticApiKey;

    @Value("${external.api.allow-static-key:false}")
    private boolean allowStaticKey;

    private final ApiUsageRecorder usageRecorder;

    /**
     * Static-key tenant lookup cache. The static break-glass path resolved its
     * tenant with tenantRepository.findAll() on EVERY request; the tenant list
     * is tiny and changes about never, so a short TTL removes that scan without
     * making a code deploy necessary to pick up a new tenant.
     *
     * Caches the two SCALARS this path reads (id + short code), never the
     * detached JPA entities — entities shared across request threads for 60s
     * would turn any future lazy field on Tenant into a LazyInitialization
     * landmine here rather than at the query site. Staleness window: a tenant
     * deleted or re-coded stays resolvable by the static key for up to 60s.
     */
    private static final long TENANT_CACHE_TTL_MS = 60_000;
    private record TenantRef(Long tenantId, String shortCode) {}
    private volatile List<TenantRef> cachedTenants = null;
    private volatile long cachedTenantsAt = 0L;

    public ApiKeyAuthFilter(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                            TenantRepository tenantRepository, ApiRateLimiter rateLimiter,
                            ApiUsageRecorder usageRecorder, ApiKeyVerificationCache verificationCache) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.tenantRepository = tenantRepository;
        this.rateLimiter = rateLimiter;
        this.usageRecorder = usageRecorder;
        this.verificationCache = verificationCache;
    }

    private List<TenantRef> tenantsCached() {
        long now = System.currentTimeMillis();
        List<TenantRef> snapshot = cachedTenants;
        if (snapshot == null || now - cachedTenantsAt > TENANT_CACHE_TTL_MS) {
            snapshot = tenantRepository.findAll().stream()
                    .map(t -> new TenantRef(t.getTenantId(), t.getBankShortCode()))
                    .toList();
            cachedTenants = snapshot;
            cachedTenantsAt = now;
        }
        return snapshot;
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
            // Log the rejection too — auth failures are exactly what an
            // anomaly review needs (key brute-force, expired-key retry storms),
            // and until now only successful calls reached api_request_log.
            usageRecorder.record(null, null, request.getMethod(),
                    truncate(request.getRequestURI(), 300), auth.status, truncate(clientIp, 64),
                    System.currentTimeMillis() - start);
            writeError(response, auth.status, auth.message);
            return;
        }

        ApiKeyPrincipal principal = auth.principal;

        // Rate limit (per key). Static break-glass key is not rate limited here.
        if (!principal.isStaticKey()) {
            // Daily quota first — a request over the day ceiling must not also
            // consume a minute-window slot.
            if (!rateLimiter.allowDay(principal.getKeyId(), auth.quotaPerDay)) {
                response.setHeader("Retry-After", "3600");
                response.setHeader("X-RateLimit-Limit-Day", String.valueOf(auth.quotaPerDay));
                response.setHeader("X-RateLimit-Remaining-Day", "0");
                usageRecorder.record(principal.getTenantId(), principal.getKeyId(), request.getMethod(),
                        truncate(request.getRequestURI(), 300), 429, truncate(clientIp, 64),
                        System.currentTimeMillis() - start);
                writeError(response, 429, "Daily request quota exceeded for this API key");
                return;
            }
            if (!rateLimiter.allow(principal.getKeyId(), auth.rateLimitPerMinute)) {
                response.setHeader("Retry-After", "60");
                writeError(response, 429, "Rate limit exceeded — try again in a minute");
                return;
            }
            if (auth.quotaPerDay != null && auth.quotaPerDay > 0) {
                response.setHeader("X-RateLimit-Limit-Day", String.valueOf(auth.quotaPerDay));
                response.setHeader("X-RateLimit-Remaining-Day",
                        String.valueOf(rateLimiter.remainingDay(principal.getKeyId(), auth.quotaPerDay)));
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
        Integer quotaPerDay;

        static AuthResult fail(int status, String message) {
            AuthResult r = new AuthResult(); r.ok = false; r.status = status; r.message = message; return r;
        }
        static AuthResult ok(ApiKeyPrincipal p, int rate, Integer quotaPerDay) {
            AuthResult r = new AuthResult(); r.ok = true; r.status = 200; r.principal = p;
            r.rateLimitPerMinute = rate; r.quotaPerDay = quotaPerDay; return r;
        }
    }

    private AuthResult authenticate(String apiKey, String requestedTenantCode, String clientIp) {
        if (apiKey == null || apiKey.isBlank()) {
            return AuthResult.fail(401, "Missing API key");
        }

        // 1. DB-issued, tenant-bound key (preferred).
        if (apiKey.length() >= 12) {
            // Fast path: a prior request on this instance already paid the
            // BCrypt for this exact raw key. The row is still re-read fresh
            // (is_active/expiry/IP enforced every request); only the hash
            // comparison is skipped, and only while the stored key_hash is
            // byte-identical to the row's current one (rotation invalidates).
            ApiKeyVerificationCache.Entry cached = verificationCache.get(apiKey);
            if (cached != null) {
                Map<String, Object> row = loadActiveKeyRow(cached.keyId());
                if (row != null && cached.keyHash().equals(row.get("key_hash"))) {
                    return validateRow(row, requestedTenantCode, clientIp);
                }
                verificationCache.invalidate(apiKey);
            }

            String prefix = apiKey.substring(0, 12) + "...";
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.queryForList(
                        "SELECT key_id, tenant_id, key_hash, permissions, expires_at, " +
                        "rate_limit_per_minute, quota_per_day, allowed_ips FROM api_key " +
                        "WHERE is_active = true AND key_prefix = ?", prefix);
            } catch (Exception e) {
                log.warn("[API-AUTH] key lookup failed: {}", e.getMessage());
                rows = List.of();
            }
            for (Map<String, Object> r : rows) {
                String hash = (String) r.get("key_hash");
                if (hash == null || !passwordEncoder.matches(apiKey, hash)) continue;
                verificationCache.put(apiKey, ((Number) r.get("key_id")).longValue(), hash);
                return validateRow(r, requestedTenantCode, clientIp);
            }
        }

        // 2. Legacy static break-glass key — all-tenant, off by default, requires tenantCode.
        if (allowStaticKey && staticApiKey != null && !staticApiKey.isBlank()
                && constantTimeEquals(staticApiKey, apiKey)) {
            if (requestedTenantCode == null || requestedTenantCode.isBlank()) {
                return AuthResult.fail(400, "tenantCode is required when using the static API key");
            }
            TenantRef t = tenantsCached().stream()
                    .filter(x -> requestedTenantCode.equalsIgnoreCase(x.shortCode()))
                    .findFirst().orElse(null);
            if (t == null) return AuthResult.fail(403, "Invalid tenant code");
            log.warn("[API-AUTH] static all-tenant key used for tenant '{}'. Prefer DB-issued keys.", requestedTenantCode);
            ApiKeyPrincipal p = new ApiKeyPrincipal(null, t.tenantId(), t.shortCode(), Set.of(), true);
            return AuthResult.ok(p, Integer.MAX_VALUE, null);
        }

        return AuthResult.fail(401, "Invalid API key");
    }

    /** Fresh row fetch for the verification-cache fast path. */
    private Map<String, Object> loadActiveKeyRow(long keyId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT key_id, tenant_id, key_hash, permissions, expires_at, " +
                    "rate_limit_per_minute, quota_per_day, allowed_ips FROM api_key " +
                    "WHERE is_active = true AND key_id = ?", keyId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[API-AUTH] cached-key row fetch failed: {}", e.getMessage());
            return null;
        }
    }

    /** Post-hash-verification checks, shared by the cached and BCrypt paths. */
    private AuthResult validateRow(Map<String, Object> r, String requestedTenantCode, String clientIp) {
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
        Integer quotaPerDay = r.get("quota_per_day") != null
                ? ((Number) r.get("quota_per_day")).intValue() : null;
        ApiKeyPrincipal p = new ApiKeyPrincipal(keyId, tenantId, keyCode, scopes, false);
        return AuthResult.ok(p, rate, quotaPerDay);
    }

    // ─── Usage logging (best-effort; never fails the request) ──────────

    private void recordUsage(ApiKeyPrincipal p, HttpServletRequest req, int status, String clientIp, long latencyMs) {
        // Buffered, not written inline. The two statements this used to issue
        // per request cost two round trips AND serialized every concurrent
        // caller of the same key on the api_key row lock — see ApiUsageRecorder.
        usageRecorder.record(p.getTenantId(), p.getKeyId(), req.getMethod(),
                truncate(req.getRequestURI(), 300), status, truncate(clientIp, 64), latencyMs);
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

    /**
     * allowedIps blank/null → any. Otherwise each comma-separated entry is an
     * exact IP or a CIDR block (e.g. 10.20.0.0/16, 2a01:4f8::/32). The old
     * exact-string-only match made the allowlist decorative for any caller
     * behind a NAT pool or load balancer with a rotating egress.
     */
    boolean ipAllowed(String allowedIps, String clientIp) {
        if (allowedIps == null || allowedIps.isBlank()) return true;
        if (clientIp == null) return false;
        for (String entry : allowedIps.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            if (e.equals(clientIp)) return true;
            int slash = e.indexOf('/');
            if (slash > 0 && cidrMatches(e, clientIp)) return true;
        }
        return false;
    }

    boolean cidrMatches(String cidr, String clientIp) {
        try {
            int slash = cidr.indexOf('/');
            byte[] net = java.net.InetAddress.getByName(cidr.substring(0, slash)).getAddress();
            byte[] addr = java.net.InetAddress.getByName(clientIp).getAddress();
            int prefixLen = Integer.parseInt(cidr.substring(slash + 1).trim());
            if (net.length != addr.length) return false; // v4 block vs v6 caller (or vice versa)
            if (prefixLen < 0 || prefixLen > net.length * 8) return false;
            int fullBytes = prefixLen / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (net[i] != addr[i]) return false;
            }
            int remainder = prefixLen % 8;
            if (remainder == 0) return true;
            int mask = 0xFF << (8 - remainder);
            return (net[fullBytes] & mask) == (addr[fullBytes] & mask);
        } catch (Exception e) {
            return false; // malformed entry never matches
        }
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
