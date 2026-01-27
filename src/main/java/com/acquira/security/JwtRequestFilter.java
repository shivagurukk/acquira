package com.acquira.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private final com.acquira.security.CustomUserDetailsService userDetailsService;
    private final com.acquira.security.JwtUtil jwtUtil;
    private final com.acquira.repository.UserRepository userRepository;
    private final com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository;

    public JwtRequestFilter(com.acquira.security.CustomUserDetailsService userDetailsService,
            com.acquira.security.JwtUtil jwtUtil,
            com.acquira.repository.UserRepository userRepository,
            com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository) {
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        final String authorizationHeader = request.getHeader("Authorization");

        String username = null;
        String jwt = null;

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7);
            try {
                username = jwtUtil.extractUsername(jwt);
            } catch (Exception e) {
                logger.error("Error extracting username from token", e);
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

            if (jwtUtil.validateToken(jwt, userDetails)) {
                UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                usernamePasswordAuthenticationToken
                        .setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);

                // Auto-set Tenant Context for Admin/User
                try {
                    final String finalUsername = username;
                    com.acquira.model.User user = userRepository.findByUsername(username)
                            .orElseThrow(() -> new RuntimeException("User not found: " + finalUsername));

                    // Find Tenant Access
                    java.util.List<com.acquira.model.UserTenantAccess> accessList = userTenantAccessRepository
                            .findByUser(user);
                    // Check for X-Tenant-Id header first
                    String tenantIdHeader = request.getHeader("X-Tenant-Id");
                    Long targetTenantId = null;

                    if (tenantIdHeader != null && !tenantIdHeader.isEmpty() && !"null".equalsIgnoreCase(tenantIdHeader)
                            && !"undefined".equalsIgnoreCase(tenantIdHeader)) {
                        try {
                            Long reqTenantId = Long.parseLong(tenantIdHeader);
                            // Validate user has access to this tenant
                            boolean hasAccess = accessList.stream()
                                    .anyMatch(a -> a.getTenant().getTenantId().equals(reqTenantId));

                            if (hasAccess) {
                                targetTenantId = reqTenantId;
                            } else {
                                logger.warn(
                                        "User " + username + " attempted to access unauthorized tenant " + reqTenantId);
                            }
                        } catch (Exception e) {
                            logger.warn("Invalid Tenant Header", e);
                        }
                    }

                    // Fallback to default (first one) if no header or invalid
                    if (targetTenantId == null && !accessList.isEmpty()) {
                        targetTenantId = accessList.get(0).getTenant().getTenantId();
                    }

                    if (targetTenantId != null) {
                        com.acquira.config.TenantContext.setCurrentTenant(targetTenantId);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Set Tenant Context to " + targetTenantId + " for user " + username);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not auto-set tenant context for user " + username + ": " + e.getMessage());
                }
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // Ensure we clear it after request processing to prevent pollution
            com.acquira.config.TenantContext.clear();
        }
    }
}
