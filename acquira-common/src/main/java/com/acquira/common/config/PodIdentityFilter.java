package com.acquira.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps every response with the pod role that served it
 * ({@code X-Acquira-Pod: core|pdf|batch|all}), so the ingress routing can be
 * checked from the browser's Network tab or with curl -I — no log digging.
 * Also exposes it to CORS so the SPA could read it if ever needed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PodIdentityFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Acquira-Pod";

    private final String role;

    public PodIdentityFilter(@Value("${" + PodRoleFilter.ROLE_PROPERTY + ":" + PodRoleFilter.ROLE_ALL + "}") String role) {
        this.role = role.trim().toLowerCase();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, role);
        chain.doFilter(request, response);
    }
}
