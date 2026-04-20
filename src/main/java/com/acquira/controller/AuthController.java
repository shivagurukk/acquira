package com.acquira.controller;

import com.acquira.security.JwtUtil;
import com.acquira.service.RateLimiterService;
import com.acquira.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final TenantService tenantService;
    private final com.acquira.repository.SysUserGroupRepository groupRepository;
    private final com.acquira.repository.UserRepository userRepository;
    private final com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository;
    private final RateLimiterService rateLimiterService;

    public AuthController(JwtUtil jwtUtil, UserDetailsService userDetailsService, TenantService tenantService,
            AuthenticationManager authenticationManager,
            com.acquira.repository.SysUserGroupRepository groupRepository,
            com.acquira.repository.UserRepository userRepository,
            com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository,
            RateLimiterService rateLimiterService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.tenantService = tenantService;
        this.authenticationManager = authenticationManager;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.rateLimiterService = rateLimiterService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> createAuthenticationToken(
            @RequestBody AuthRequest authenticationRequest,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        // Input validation
        if (authenticationRequest.getUsername() == null || authenticationRequest.getUsername().trim().isEmpty()
                || authenticationRequest.getPassword() == null
                || authenticationRequest.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }

        // Rate limiting
        String clientIp = rateLimiterService.getClientIp(httpRequest);
        if (rateLimiterService.isRateLimited(clientIp)) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Too many login attempts. Please try again later."));
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            authenticationRequest.getUsername(),
                            authenticationRequest.getPassword()));
        } catch (BadCredentialsException e) {
            rateLimiterService.recordFailedAttempt(clientIp);
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect username or password"));
        }

        // Successful login — clear rate limit
        rateLimiterService.clearAttempts(clientIp);

        final UserDetails userDetails = userDetailsService.loadUserByUsername(authenticationRequest.getUsername());
        final String accessToken = jwtUtil.generateToken(userDetails);
        final String refreshToken = jwtUtil.generateRefreshToken(userDetails.getUsername());

        // Get allowed tenants for the user
        List<com.acquira.model.Tenant> allowedTenants = tenantService
                .getAllowedTenantsForUser(userDetails.getUsername());
        Long defaultTenantId = tenantService.getDefaultTenantIdForUser(userDetails.getUsername());

        Long effectiveTenantId = defaultTenantId;
        if (effectiveTenantId == null && !allowedTenants.isEmpty()) {
            effectiveTenantId = allowedTenants.get(0).getTenantId();
        }

        com.acquira.model.User user = userRepository.findByUsername(authenticationRequest.getUsername()).orElse(null);
        Set<com.acquira.model.SysMenu> menus = new HashSet<>();

        if (user != null && effectiveTenantId != null) {
            Optional<com.acquira.model.UserTenantAccess> access = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, effectiveTenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null) {
                menus = access.get().getSysUserGroup().getMenus();
            } else {
                String userRole = user.getRole();
                // SECURITY FIX: Only SUPER_ADMIN gets fallback to "Super Admin" group menus.
                // Bank Admin without an explicit access row for this tenant gets NO menus
                // (they shouldn't be accessing this tenant at all).
                if ("ROLE_SUPER_ADMIN".equals(userRole)) {
                    // Super Admin: try to find menus from any existing access, then fallback to Super Admin group
                    List<com.acquira.model.UserTenantAccess> allAccess = userTenantAccessRepository.findByUser(user);
                    allAccess.stream()
                            .filter(a -> a.getSysUserGroup() != null)
                            .findFirst()
                            .ifPresent(a -> menus.addAll(a.getSysUserGroup().getMenus()));
                    if (menus.isEmpty()) {
                        groupRepository.findAll().stream()
                                .filter(g -> "Super Admin".equalsIgnoreCase(g.getGroupName()))
                                .findFirst()
                                .ifPresent(g -> menus.addAll(g.getMenus()));
                    }
                } else if ("ROLE_ADMIN".equals(userRole)) {
                    // Bank Admin: try to find menus from any existing access, then fallback to Bank Admin group
                    List<com.acquira.model.UserTenantAccess> allAccess = userTenantAccessRepository.findByUser(user);
                    allAccess.stream()
                            .filter(a -> a.getSysUserGroup() != null)
                            .findFirst()
                            .ifPresent(a -> menus.addAll(a.getSysUserGroup().getMenus()));
                    if (menus.isEmpty()) {
                        groupRepository.findAll().stream()
                                .filter(g -> "Bank Admin".equalsIgnoreCase(g.getGroupName()))
                                .findFirst()
                                .ifPresent(g -> menus.addAll(g.getMenus()));
                    }
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("jwt", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", defaultTenantId);
        response.put("roles", userDetails.getAuthorities());
        response.put("menus", menus);
        // Include username and role so frontend can display them without JWT decode
        response.put("username", user != null ? user.getUsername() : authenticationRequest.getUsername());
        response.put("userRole", user != null ? user.getRole() : "ROLE_USER");
        response.put("displayName", user != null ? user.getDisplayName() : null);
        response.put("mustChangePassword", user != null && Boolean.TRUE.equals(user.getMustChangePassword()));

        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody Map<String, String> payload) {
        String refreshToken = payload.get("refreshToken");
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Refresh token is required"));
        }

        try {
            if (!jwtUtil.isRefreshToken(refreshToken)) {
                return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token"));
            }

            String username = jwtUtil.extractUsername(refreshToken);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (!jwtUtil.validateToken(refreshToken, userDetails)) {
                return ResponseEntity.status(401).body(Map.of("error", "Expired or invalid refresh token"));
            }

            com.acquira.model.User dbUser = userRepository.findByUsername(username).orElse(null);
            if (dbUser == null || !dbUser.isActive()) {
                return ResponseEntity.status(401).body(Map.of("error", "User account is disabled"));
            }

            String newAccessToken = jwtUtil.generateToken(userDetails);
            String newRefreshToken = jwtUtil.generateRefreshToken(username);

            return ResponseEntity.ok(Map.of(
                    "jwt", newAccessToken,
                    "refreshToken", newRefreshToken));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token"));
        }
    }

    @PostMapping("/switch-context")
    public ResponseEntity<?> switchContext(@RequestBody Map<String, Object> payload) {
        Object tenantIdObj = payload.get("tenantId");
        Object viewIdObj = payload.get("viewId");

        Long tenantId = null;
        if (tenantIdObj instanceof Number) {
            tenantId = ((Number) tenantIdObj).longValue();
        }

        Long viewId = null;
        if (viewIdObj instanceof Number) {
            viewId = ((Number) viewIdObj).longValue();
        }

        String username = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getName();
        com.acquira.model.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Set<com.acquira.model.SysMenu> menus = new HashSet<>();

        if (viewId != null) {
            List<com.acquira.model.UserCombinedView> views = tenantService.getCombinedViews(username);
            final Long finalViewId = viewId;
            com.acquira.model.UserCombinedView view = views.stream()
                    .filter(v -> v.getViewId().equals(finalViewId))
                    .findFirst()
                    .orElse(null);

            if (view != null) {
                String[] ids = view.getTenantIds().split(",");
                if (ids.length > 0) {
                    try {
                        tenantId = Long.parseLong(ids[0].trim());
                    } catch (Exception e) {
                        // ignore parse error
                    }
                }
            }
        }

        boolean isSuperAdmin = "ROLE_SUPER_ADMIN".equals(user.getRole());

        if (tenantId != null) {
            // SECURITY FIX: Validate Bank Admin has access to target tenant
            if (!isSuperAdmin) {
                boolean hasAccess = userTenantAccessRepository.findByUser(user).stream()
                        .anyMatch(a -> a.getTenant().getTenantId().equals(tenantId));
                if (!hasAccess) {
                    return ResponseEntity.status(403).body(Map.of("error", "Access denied for this tenant"));
                }
            }

            Optional<com.acquira.model.UserTenantAccess> access = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, tenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null) {
                menus = access.get().getSysUserGroup().getMenus();
            } else if (isSuperAdmin) {
                // Super Admin fallback: use menus from any access, then fallback to Super Admin group
                List<com.acquira.model.UserTenantAccess> allAccess = userTenantAccessRepository.findByUser(user);
                allAccess.stream()
                        .filter(a -> a.getSysUserGroup() != null)
                        .findFirst()
                        .ifPresent(a -> menus.addAll(a.getSysUserGroup().getMenus()));
                if (menus.isEmpty()) {
                    groupRepository.findAll().stream()
                            .filter(g -> "Super Admin".equalsIgnoreCase(g.getGroupName()))
                            .findFirst()
                            .ifPresent(g -> menus.addAll(g.getMenus()));
                }
            }
            // Bank Admin without access row: menus stays empty (they shouldn't be here)
        }

        Map<String, Object> response = new HashMap<>();
        response.put("menus", menus);
        response.put("activeTenantId", tenantId);

        if (tenantId != null) {
            Optional<com.acquira.model.UserTenantAccess> accessObj = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, tenantId);
            if (accessObj.isPresent() && accessObj.get().getSysUserGroup() != null) {
                response.put("groupName", accessObj.get().getSysUserGroup().getGroupName());
                response.put("roleInTenant", accessObj.get().getRoleInTenant());
            } else if (isSuperAdmin) {
                response.put("groupName", "SUPER_ADMIN");
                response.put("roleInTenant", "ROLE_SUPER_ADMIN");
            }
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/session")
    public ResponseEntity<?> getSessionData() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String username = auth.getName();

        List<com.acquira.model.Tenant> allowedTenants = tenantService.getAllowedTenantsForUser(username);
        Long defaultTenantId = tenantService.getDefaultTenantIdForUser(username);

        Map<String, Object> response = new HashMap<>();
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", defaultTenantId);
        response.put("username", username);
        response.put("roles", auth.getAuthorities());

        return ResponseEntity.ok(response);
    }
}

class AuthRequest {
    private String username;
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
