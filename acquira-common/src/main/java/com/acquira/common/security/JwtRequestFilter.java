package com.acquira.common.security;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.User;
import com.acquira.common.model.UserTenantAccess;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.repository.UserTenantAccessRepository;
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
import java.util.List;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private final CustomUserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final UserTenantAccessRepository userTenantAccessRepository;

    public JwtRequestFilter(CustomUserDetailsService userDetailsService,
            JwtUtil jwtUtil,
            UserRepository userRepository,
            UserTenantAccessRepository userTenantAccessRepository) {
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
                // Reject refresh tokens used as access tokens
                if (jwtUtil.isRefreshToken(jwt)) {
                    logger.warn("Refresh token used as access token — rejected");
                    chain.doFilter(request, response);
                    return;
                }
                username = jwtUtil.extractUsername(jwt);
            } catch (io.jsonwebtoken.ExpiredJwtException e) {
                logger.debug("JWT token expired for request: " + request.getRequestURI());
            } catch (io.jsonwebtoken.security.SignatureException e) {
                logger.warn("Invalid JWT signature");
            } catch (Exception e) {
                logger.warn("Invalid JWT token: " + e.getMessage());
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

            if (jwtUtil.validateToken(jwt, userDetails)) {

                // ===== SECURITY FIX: Check if user is still active =====
                User dbUser = userRepository.findByUsername(username).orElse(null);
                if (dbUser == null || !dbUser.isActive()) {
                    logger.warn("Rejected token for inactive/deleted user: " + username);
                    chain.doFilter(request, response);
                    return;
                }
                // GAP-13: Block PENDING approval users from making API calls
                if (dbUser.isPendingApproval()) {
                    logger.warn("Rejected token for pending-approval user: " + username);
                    chain.doFilter(request, response);
                    return;
                }

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);

                // ===== Tenant Context Resolution (with validation) =====
                try {
                    List<UserTenantAccess> accessList = userTenantAccessRepository.findByUser(dbUser);

                    // Super Admin can access ANY tenant without explicit UserTenantAccess rows
                    boolean isSuperAdmin = "ROLE_SUPER_ADMIN".equals(dbUser.getRole());

                    String tenantIdHeader = request.getHeader("X-Tenant-Id");
                    Long targetTenantId = null;

                    if (tenantIdHeader != null && !tenantIdHeader.isEmpty()
                            && !"null".equalsIgnoreCase(tenantIdHeader)
                            && !"undefined".equalsIgnoreCase(tenantIdHeader)) {
                        try {
                            Long reqTenantId = Long.parseLong(tenantIdHeader);
                            // Super Admin bypasses access-list check
                            boolean hasAccess = isSuperAdmin || accessList.stream()
                                    .anyMatch(a -> a.getTenant().getTenantId().equals(reqTenantId));

                            if (hasAccess) {
                                targetTenantId = reqTenantId;
                            } else {
                                logger.warn("User " + username + " attempted unauthorized tenant " + reqTenantId);
                            }
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid X-Tenant-Id header: " + tenantIdHeader);
                        }
                    }

                    // Fallback to default tenant or first available
                    UserTenantAccess activeAccess = null;
                    if (targetTenantId == null) {
                        // 1. Try to find default
                        activeAccess = accessList.stream()
                                .filter(a -> Boolean.TRUE.equals(a.getIsDefaultTenant()))
                                .findFirst()
                                .orElse(null);

                        // 2. Fallback to first
                        if (activeAccess == null && !accessList.isEmpty()) {
                            activeAccess = accessList.get(0);
                        }

                        if (activeAccess != null) {
                            targetTenantId = activeAccess.getTenant().getTenantId();
                        }
                    } else {
                        // Find the access object for the requested target
                        Long finalTarget = targetTenantId;
                        activeAccess = accessList.stream()
                                .filter(a -> a.getTenant().getTenantId().equals(finalTarget))
                                .findFirst()
                                .orElse(null);
                    }

                    if (targetTenantId != null) {
                        TenantContext.setCurrentTenant(targetTenantId);

                        // NEW: Set Scope and Role
                        if (activeAccess != null) {
                            // 1. Set Effective Role
                            String role = activeAccess.getRoleInTenant();
                            // Fallback to Group Name if role is null (compatibility)
                            if (role == null && activeAccess.getSysUserGroup() != null) {
                                String group = activeAccess.getSysUserGroup().getGroupName();
                                if ("Super Admin".equalsIgnoreCase(group))
                                    role = "ROLE_SUPER_ADMIN";
                                else if ("Bank Admin".equalsIgnoreCase(group))
                                    role = "ROLE_ADMIN";
                                else
                                    role = "ROLE_USER";
                            }
                            if (role != null)
                                TenantContext.setCurrentRole(role);

                            // 2. Set Visible Tenants (for now, just the active one, or ALL if Super Admin)
                            if ("ROLE_SUPER_ADMIN".equals(role)) {
                                // Super admin sees all? For now let's keep it simple
                                // In future fetch all tenant IDs
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not set tenant context for " + username + ": " + e.getMessage());
                }
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
