package com.acquira.common.security;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.User;
import com.acquira.common.model.UserTenantAccess;
import com.acquira.common.repository.TenantRepository;
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
    private final TenantRepository tenantRepository;

    // ── Cached all-tenant-IDs list (super-admin visible-tenants scope) ──────
    // Super-admin requests need the full list of tenant IDs to populate
    // visibleTenants for cross-tenant rollups. Loading it from the DB on EVERY
    // super-admin request is a needless hot-path query. Tenants are created very
    // rarely (admin action), so a short TTL cache is safe: a newly-created tenant
    // becomes visible to super-admins within at most CACHE_TTL_MS. The cached
    // reference is replaced atomically (volatile), so concurrent reads are safe
    // without locking; a brief window where two threads both refresh is harmless
    // (same data). On any DB error we do NOT cache, so the next request retries.
    private static volatile java.util.List<Long> cachedTenantIds = null;
    private static volatile long cachedTenantIdsAt = 0L;
    private static final long CACHE_TTL_MS = 60_000L; // 60s

    public JwtRequestFilter(CustomUserDetailsService userDetailsService,
            JwtUtil jwtUtil,
            UserRepository userRepository,
            UserTenantAccessRepository userTenantAccessRepository,
            TenantRepository tenantRepository) {
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * All tenant IDs, cached for {@link #CACHE_TTL_MS}. Returns null on DB error
     * (caller falls back to current-tenant-only scope, which is the safe default).
     */
    private java.util.List<Long> getAllTenantIdsCached() {
        long now = System.currentTimeMillis();
        java.util.List<Long> cached = cachedTenantIds;
        if (cached != null && (now - cachedTenantIdsAt) < CACHE_TTL_MS) {
            return cached;
        }
        try {
            java.util.List<Long> fresh = tenantRepository.findAll().stream()
                    .map(com.acquira.common.model.Tenant::getTenantId)
                    .collect(java.util.stream.Collectors.toList());
            cachedTenantIds = fresh;
            cachedTenantIdsAt = now;
            return fresh;
        } catch (Exception e) {
            logger.warn("Could not load all tenants for super admin scope: " + e.getMessage());
            return null; // do not cache failures; safe fallback handled by caller
        }
    }

    /**
     * Endpoints a user may still reach while must_change_password is set:
     * the change-password call itself plus the auth endpoints (refresh, logout).
     * Matched on the request URI so it works with or without a context path.
     */
    private static boolean isAllowedDuringForcedPasswordChange(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.endsWith("/users/change-password") || uri.contains("/auth/");
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
                // Account expiry was enforced ONLY at login, so a session that was
                // already open when the expiry passed kept working for the full
                // life of its access token (and could be renewed by refresh).
                // Enforce it per-request, and auto-deactivate exactly as the login
                // path does so the account also shows as Inactive in User Management.
                if (dbUser.isAccountExpired()) {
                    logger.warn("Rejected token for expired account: " + username);
                    if (dbUser.isActive()) {
                        dbUser.setActive(false);
                        userRepository.save(dbUser);
                    }
                    chain.doFilter(request, response);
                    return;
                }
                // Forced password change was enforced only by a frontend
                // redirect, which a page refresh bypassed. Enforce it here:
                // while the flag is set, a local (non-SSO) user may only call
                // change-password and the auth endpoints (refresh/logout).
                // 403 + a machine-readable code lets the frontend route the
                // user back to the change-password screen.
                if (dbUser.isMustChangePassword() && !dbUser.isSsoUser()
                        && !isAllowedDuringForcedPasswordChange(request)) {
                    logger.warn("Blocked request pending password change: " + username
                            + " -> " + request.getRequestURI());
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"error\":\"You must change your password before continuing.\","
                            + "\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
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
                                // SECURITY: reject, don't fall back. Silently ignoring a
                                // spoofed X-Tenant-Id left controllers that read the raw
                                // header trusting an unvalidated value (cross-tenant IDOR).
                                logger.warn("User " + username + " attempted unauthorized tenant " + reqTenantId + " — rejected");
                                // Write the 403 directly rather than response.sendError():
                                // sendError() flags the response for container ERROR
                                // dispatch, which re-enters the security chain on the
                                // cleared context and gets rewritten to 401 by the
                                // authenticationEntryPoint — so a genuine cross-tenant
                                // denial looked like an expired session to the client.
                                // Committing the body here (as the forced-password-change
                                // branch does) keeps it a true 403.
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType("application/json");
                                response.getWriter().write(
                                        "{\"error\":\"You do not have access to the requested tenant.\","
                                        + "\"code\":\"TENANT_FORBIDDEN\"}");
                                return;
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
                                // P2-4 fix: super admin sees all tenants for cross-tenant
                                // rollups (executive dashboards, group reports). Without
                                // this, getVisibleTenants() returned only the currently
                                // active tenant for SA users — silently scoping every
                                // multi-tenant query to one tenant.
                                // Cached (60s TTL) to avoid a tenantRepository.findAll()
                                // on every super-admin request — see getAllTenantIdsCached().
                                java.util.List<Long> allTenantIds = getAllTenantIdsCached();
                                if (allTenantIds != null) {
                                    TenantContext.setVisibleTenants(allTenantIds);
                                }
                                // else: load failed — fall back to current-tenant-only
                                // scope; safer than accidentally widening visibility.
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
