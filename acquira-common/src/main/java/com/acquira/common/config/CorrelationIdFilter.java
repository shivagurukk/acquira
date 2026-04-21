package com.acquira.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Enterprise Observability: Correlation ID + MDC context for every request.
 *
 * Injects into SLF4J MDC:
 *   correlationId — unique per request (or from X-Correlation-Id header for distributed tracing)
 *   username      — from SecurityContext (once auth resolves)
 *   tenantId      — from X-Tenant-Id header
 *   clientIp      — from X-Forwarded-For or remoteAddr
 *
 * Response header: X-Correlation-Id echoed back to client for support reference.
 *
 * Ordered HIGHEST_PRECEDENCE to run before security filters.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String CID_HEADER = "X-Correlation-Id";
    private static final String MDC_CID = "correlationId";
    private static final String MDC_USER = "username";
    private static final String MDC_TENANT = "tenantId";
    private static final String MDC_IP = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        try {
            // 1. Correlation ID — accept from upstream or generate
            String correlationId = request.getHeader(CID_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString().substring(0, 8); // Short ID for readability
            }
            MDC.put(MDC_CID, correlationId);
            response.setHeader(CID_HEADER, correlationId);

            // 2. Tenant ID from header
            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId != null && !tenantId.isBlank()
                    && !"null".equalsIgnoreCase(tenantId)
                    && !"undefined".equalsIgnoreCase(tenantId)) {
                MDC.put(MDC_TENANT, tenantId);
            }

            // 3. Client IP
            String clientIp = request.getHeader("X-Forwarded-For");
            if (clientIp != null && !clientIp.isEmpty()) {
                clientIp = clientIp.split(",")[0].trim();
            } else {
                clientIp = request.getRemoteAddr();
            }
            MDC.put(MDC_IP, clientIp);

            // Continue filter chain
            chain.doFilter(request, response);

            // 4. After auth resolves, enrich MDC with username
            //    (This only matters for the very last log statements in the response path)
            enrichUsername();

        } finally {
            MDC.clear();
        }
    }

    private void enrichUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                MDC.put(MDC_USER, auth.getName());
            }
        } catch (Exception ignored) {}
    }

    /**
     * Utility: Call from anywhere (e.g., async threads) to copy MDC username from SecurityContext.
     */
    public static void enrichMdc() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                MDC.put(MDC_USER, auth.getName());
            }
            Long tenantId = TenantContext.getCurrentTenant();
            if (tenantId != null) {
                MDC.put(MDC_TENANT, String.valueOf(tenantId));
            }
        } catch (Exception ignored) {}
    }
}
