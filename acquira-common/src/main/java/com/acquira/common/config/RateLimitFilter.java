package com.acquira.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * #15: Simple sliding-window rate limiter per IP.
 * Limits: 200 requests/minute for regular API, 20/minute for external API.
 * Lightweight — no Redis dependency. Works for single-instance deployment.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final int REGULAR_LIMIT = 200; // per minute
    private static final int EXTERNAL_LIMIT = 20; // per minute for /api/external/*
    private static final int MIGRATION_LIMIT = 5; // per minute for /api/admin/migration/*
    private static final long WINDOW_MS = 60_000;

    // IP -> [count, windowStart]
    private final ConcurrentHashMap<String, long[]> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip rate limiting for static resources and health checks
        if (path.startsWith("/actuator") || path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".ico")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);
        String bucketKey = clientIp + "|" + getBucket(path);
        int limit = getLimit(path);

        long now = System.currentTimeMillis();
        long[] data = counters.compute(bucketKey, (key, existing) -> {
            if (existing == null || now - existing[1] > WINDOW_MS) {
                return new long[]{1, now};
            }
            existing[0]++;
            return existing;
        });

        if (data[0] > limit) {
            log.warn("[RATE-LIMIT] {} exceeded {} req/min on {} (count={})", clientIp, limit, path, data[0]);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded. Please try again shortly.\",\"retryAfterMs\":" + (WINDOW_MS - (now - data[1])) + "}");
            return;
        }

        // Add rate limit headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - (int) data[0])));

        chain.doFilter(request, response);
    }

    private int getLimit(String path) {
        if (path.startsWith("/api/external/")) return EXTERNAL_LIMIT;
        if (path.startsWith("/api/admin/migration/")) return MIGRATION_LIMIT;
        return REGULAR_LIMIT;
    }

    private String getBucket(String path) {
        if (path.startsWith("/api/external/")) return "external";
        if (path.startsWith("/api/admin/migration/")) return "migration";
        return "api";
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Cleanup stale entries every 5 minutes (called by Spring scheduler) */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 300000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(e -> now - e.getValue()[1] > WINDOW_MS * 2);
    }
}
