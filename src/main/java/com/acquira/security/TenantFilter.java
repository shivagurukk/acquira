package com.acquira.security;

import com.acquira.config.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(TenantFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String tenantIdHeader = request.getHeader("X-Tenant-Id");

        if (tenantIdHeader != null && !tenantIdHeader.isEmpty() && !"null".equalsIgnoreCase(tenantIdHeader)
                && !"undefined".equalsIgnoreCase(tenantIdHeader)) {
            try {
                Long tenantId = Long.parseLong(tenantIdHeader);
                // In a real scenario, we would validate here if the authenticated user
                // actually has access to this tenantId to prevent IDOR.
                // For now, we assume the JWT/Security context checks happen elsewhere or logic
                // is trusted.
                // Ideally, validate: if (userHasAccess(currentUser, tenantId)) { ... }

                TenantContext.setCurrentTenant(tenantId);
            } catch (NumberFormatException e) {
                logger.warn("Invalid X-Tenant-Id header format: {}", tenantIdHeader);
            }
        } else {
            // Optional: Set a default tenant? Or leave null and let RLS fail/return empty?
            // For now, we leave it null.
            logger.trace("No X-Tenant-Id header found.");
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
