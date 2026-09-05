package com.acquira.core.config;

import com.acquira.common.security.ApiKeyPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Idempotency-Key support for the external API's side-effectful verbs.
 *
 * Contract (Stripe-style): a client sends a unique {@code Idempotency-Key}
 * header on POST/PUT/PATCH/DELETE. The first request executes and its response
 * (status + body) is stored per tenant for 24h (purged by
 * ApiKeyExpiryScheduler). A retry with the same key gets the STORED response
 * back — marked {@code X-Idempotency-Replayed: true} — instead of re-running
 * the operation. Two racing requests with the same key: one executes, the
 * other gets 409 and should retry after a beat.
 *
 * Runs AFTER ApiKeyAuthFilter (@Order 5) so the tenant is already resolved;
 * the store is keyed (tenant_id, idem_key) — keys never collide across
 * tenants. GETs and requests without the header pass through untouched.
 * Today the external surface is read-only, so this is forward wiring for the
 * write endpoints the enterprise roadmap adds; it costs nothing until a
 * client sends the header.
 */
@Component
@Order(6)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final int MAX_KEY_LEN = 120;
    private static final int MAX_STORED_BODY = 100_000;

    private final JdbcTemplate jdbc;

    public IdempotencyFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        if (!(p.startsWith("/api/v1/") || p.startsWith("/api/external/"))) return true;
        String m = request.getMethod();
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) return true;
        String key = request.getHeader("Idempotency-Key");
        return key == null || key.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        ApiKeyPrincipal principal = (ApiKeyPrincipal) request.getAttribute(ApiKeyPrincipal.ATTR);
        if (principal == null || principal.getTenantId() == null) {
            // Unauthenticated requests never reach here in practice (auth filter
            // rejects first); belt-and-braces pass-through.
            chain.doFilter(request, response);
            return;
        }
        Long tenantId = principal.getTenantId();
        String idemKey = request.getHeader("Idempotency-Key").trim();
        if (idemKey.length() > MAX_KEY_LEN) {
            writeJson(response, 400, "{\"error\":\"Idempotency-Key must be at most " + MAX_KEY_LEN + " characters\"}");
            return;
        }

        // Replay?
        var rows = jdbc.queryForList(
                "SELECT response_status, response_body FROM api_idempotency WHERE tenant_id = ? AND idem_key = ?",
                tenantId, idemKey);
        if (!rows.isEmpty()) {
            Object status = rows.get(0).get("response_status");
            if (status == null) {
                // Placeholder row without a response: the original request is still in flight.
                writeJson(response, 409,
                        "{\"error\":\"A request with this Idempotency-Key is still being processed\"}");
                return;
            }
            response.setStatus(((Number) status).intValue());
            response.setHeader("X-Idempotency-Replayed", "true");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String body = (String) rows.get(0).get("response_body");
            if (body != null) response.getWriter().write(body);
            return;
        }

        // Claim the key. ON CONFLICT DO NOTHING makes the race explicit: the
        // loser inserts 0 rows and is told to back off.
        int claimed = jdbc.update(
                "INSERT INTO api_idempotency (tenant_id, idem_key, method, endpoint) VALUES (?,?,?,?) " +
                "ON CONFLICT (tenant_id, idem_key) DO NOTHING",
                tenantId, idemKey, request.getMethod(), truncate(request.getRequestURI(), 300));
        if (claimed == 0) {
            writeJson(response, 409,
                    "{\"error\":\"A request with this Idempotency-Key is still being processed\"}");
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(request, wrapped);
            completed = true;
        } finally {
            try {
                if (completed) {
                    String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
                    jdbc.update("UPDATE api_idempotency SET response_status = ?, response_body = ? " +
                                "WHERE tenant_id = ? AND idem_key = ?",
                            wrapped.getStatus(), truncate(body, MAX_STORED_BODY), tenantId, idemKey);
                } else {
                    // The handler threw — release the claim so the client's retry can execute.
                    jdbc.update("DELETE FROM api_idempotency WHERE tenant_id = ? AND idem_key = ? " +
                                "AND response_status IS NULL", tenantId, idemKey);
                }
            } catch (Exception e) {
                log.warn("[API] idempotency bookkeeping failed for key '{}': {}", idemKey, e.getMessage());
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void writeJson(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
