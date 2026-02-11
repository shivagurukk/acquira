package com.acquira.controller;

import com.acquira.security.JwtUtil;
import com.acquira.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ===== SECURITY FIX: Simple in-memory rate limiter =====
    // Key: IP address, Value: [attempt count, first attempt timestamp]
    private final ConcurrentHashMap<String, long[]> loginAttempts = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 60_000; // 1 minute

    public AuthController(JwtUtil jwtUtil, UserDetailsService userDetailsService, TenantService tenantService,
            AuthenticationManager authenticationManager,
            com.acquira.repository.SysUserGroupRepository groupRepository,
            com.acquira.repository.UserRepository userRepository,
            com.acquira.repository.UserTenantAccessRepository userTenantAccessRepository) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.tenantService = tenantService;
        this.authenticationManager = authenticationManager;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<?> createAuthenticationToken(
            @RequestBody AuthRequest authenticationRequest,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        // ===== SECURITY FIX: Input validation =====
        if (authenticationRequest.getUsername() == null || authenticationRequest.getUsername().trim().isEmpty()
                || authenticationRequest.getPassword() == null
                || authenticationRequest.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }

        // ===== SECURITY FIX: Rate limiting =====
        String clientIp = getClientIp(httpRequest);
        if (isRateLimited(clientIp)) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Too many login attempts. Please try again later."));
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            authenticationRequest.getUsername(),
                            authenticationRequest.getPassword()));
        } catch (BadCredentialsException e) {
            recordFailedAttempt(clientIp);
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect username or password"));
        }

        // Successful login — clear rate limit
        loginAttempts.remove(clientIp);

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
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("jwt", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", defaultTenantId);
        response.put("roles", userDetails.getAuthorities());
        response.put("menus", menus);

        return ResponseEntity.ok(response);
    }

    // ===== Refresh Token Endpoint =====
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

            // Check user is still active
            com.acquira.model.User dbUser = userRepository.findByUsername(username).orElse(null);
            if (dbUser == null || !dbUser.isActive()) {
                return ResponseEntity.status(401).body(Map.of("error", "User account is disabled"));
            }

            // Issue new tokens
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
            // Combined View: Fetch view to get tenant IDs
            // For menu generation, we'll currently use the PRIMARY tenant of the view
            // (first one)
            // or perhaps a specific logic. For now: First valid tenant user has access to.
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
                    }
                }
            }
        }

        if (tenantId != null) {
            Optional<com.acquira.model.UserTenantAccess> access = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, tenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null) {
                menus = access.get().getSysUserGroup().getMenus();
            }
        }

        return ResponseEntity.ok(Map.of("menus", menus));
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

    // ===== Rate Limiting Helpers =====
    private boolean isRateLimited(String clientIp) {
        long now = System.currentTimeMillis();
        long[] data = loginAttempts.get(clientIp);
        if (data == null)
            return false;
        if (now - data[1] > WINDOW_MS) {
            loginAttempts.remove(clientIp);
            return false;
        }
        return data[0] >= MAX_ATTEMPTS;
    }

    private void recordFailedAttempt(String clientIp) {
        long now = System.currentTimeMillis();
        loginAttempts.compute(clientIp, (key, data) -> {
            if (data == null || now - data[1] > WINDOW_MS) {
                return new long[] { 1, now };
            }
            data[0]++;
            return data;
        });
    }

    private String getClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
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
