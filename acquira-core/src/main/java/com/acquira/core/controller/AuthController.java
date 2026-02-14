package com.acquira.core.controller;

import com.acquira.common.model.PasswordResetToken;
import com.acquira.common.model.User;
import com.acquira.common.repository.PasswordResetTokenRepository;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.security.JwtUtil;
import com.acquira.core.service.PasswordService;
import com.acquira.core.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final TenantService tenantService;
    private final com.acquira.common.repository.SysUserGroupRepository groupRepository;
    private final UserRepository userRepository;
    private final com.acquira.common.repository.UserTenantAccessRepository userTenantAccessRepository;
    private final PasswordService passwordService;
    private final PasswordResetTokenRepository resetTokenRepository;

    // ===== IP-based rate limiter (defense-in-depth, kept alongside per-user lockout) =====
    private final ConcurrentHashMap<String, long[]> loginAttempts = new ConcurrentHashMap<>();
    private static final int MAX_IP_ATTEMPTS = 10;
    private static final long WINDOW_MS = 60_000;

    // Per-user lockout settings (could load from tenant_setting)
    private static final int MAX_USER_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;

    public AuthController(JwtUtil jwtUtil, UserDetailsService userDetailsService, TenantService tenantService,
            AuthenticationManager authenticationManager,
            com.acquira.common.repository.SysUserGroupRepository groupRepository,
            UserRepository userRepository,
            com.acquira.common.repository.UserTenantAccessRepository userTenantAccessRepository,
            PasswordService passwordService,
            PasswordResetTokenRepository resetTokenRepository) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.tenantService = tenantService;
        this.authenticationManager = authenticationManager;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.passwordService = passwordService;
        this.resetTokenRepository = resetTokenRepository;
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

        // IP rate limiting (defense-in-depth layer)
        String clientIp = getClientIp(httpRequest);
        if (isRateLimited(clientIp)) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Too many login attempts from this address. Please try again later."));
        }

        // ===== Per-user lockout check =====
        Optional<User> userOpt = userRepository.findByUsername(authenticationRequest.getUsername());
        if (userOpt.isPresent()) {
            User dbUser = userOpt.get();

            // Check if account is locked
            if (dbUser.isAccountLocked()) {
                long minutesRemaining = Duration.between(LocalDateTime.now(), dbUser.getLockedUntil()).toMinutes() + 1;
                return ResponseEntity.status(423).body(Map.of(
                        "error", "Account is locked. Try again in " + minutesRemaining + " minute(s).",
                        "lockedUntil", dbUser.getLockedUntil().toString(),
                        "locked", true));
            }

            // Check if account is deactivated
            if (!dbUser.isActive()) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "Account is deactivated. Contact your administrator."));
            }
        }

        // ===== Authenticate =====
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            authenticationRequest.getUsername(),
                            authenticationRequest.getPassword()));
        } catch (BadCredentialsException e) {
            recordFailedAttempt(clientIp);

            // Per-user failed attempt tracking
            if (userOpt.isPresent()) {
                User dbUser = userOpt.get();
                dbUser.setFailedLoginAttempts(dbUser.getFailedLoginAttempts() + 1);
                dbUser.setLastFailedLogin(LocalDateTime.now());

                if (dbUser.getFailedLoginAttempts() >= MAX_USER_ATTEMPTS) {
                    dbUser.setLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
                    userRepository.save(dbUser);
                    return ResponseEntity.status(423).body(Map.of(
                            "error", "Account locked after " + MAX_USER_ATTEMPTS
                                    + " failed attempts. Try again in " + LOCKOUT_MINUTES + " minutes.",
                            "locked", true));
                }

                userRepository.save(dbUser);
                int remaining = MAX_USER_ATTEMPTS - dbUser.getFailedLoginAttempts();
                return ResponseEntity.status(401).body(Map.of(
                        "error", "Incorrect username or password. "
                                + remaining + " attempt(s) remaining before lockout.",
                        "attemptsRemaining", remaining));
            }

            return ResponseEntity.status(401).body(Map.of("error", "Incorrect username or password"));
        }

        // ===== Successful login — reset lockout counters =====
        loginAttempts.remove(clientIp);

        User user = userOpt.orElse(null);
        if (user != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            user.setLastFailedLogin(null);
            user.setCreatedAt(user.getCreatedAt()); // keep original
            userRepository.save(user);
        }

        final UserDetails userDetails = userDetailsService.loadUserByUsername(authenticationRequest.getUsername());
        final String accessToken = jwtUtil.generateToken(userDetails);
        final String refreshToken = jwtUtil.generateRefreshToken(userDetails.getUsername());

        // Get allowed tenants
        List<com.acquira.common.model.Tenant> allowedTenants = tenantService
                .getAllowedTenantsForUser(userDetails.getUsername());
        Long defaultTenantId = tenantService.getDefaultTenantIdForUser(userDetails.getUsername());

        Long effectiveTenantId = defaultTenantId;
        if (effectiveTenantId == null && !allowedTenants.isEmpty()) {
            effectiveTenantId = allowedTenants.get(0).getTenantId();
        }

        // Load menus
        Set<com.acquira.common.model.SysMenu> menus = new HashSet<>();
        if (user != null && effectiveTenantId != null) {
            Optional<com.acquira.common.model.UserTenantAccess> access = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, effectiveTenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null) {
                menus = access.get().getSysUserGroup().getMenus();
            }

            // Super Admin fallback
            if (menus.isEmpty() && "ROLE_SUPER_ADMIN".equals(user.getRole())) {
                Optional<com.acquira.common.model.SysUserGroup> superGroup = groupRepository
                        .findByGroupName("Super Admin");
                if (superGroup.isPresent() && superGroup.get().getMenus() != null) {
                    menus = superGroup.get().getMenus();
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("jwt", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", effectiveTenantId);
        response.put("roles", userDetails.getAuthorities());
        response.put("menus", menus);
        response.put("username", authenticationRequest.getUsername());
        response.put("userRole", user != null ? user.getRole() : "ROLE_USER");

        // ===== Force password change flag =====
        if (user != null && user.isMustChangePassword()) {
            response.put("mustChangePassword", true);
        }

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

            User dbUser = userRepository.findByUsername(username).orElse(null);
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
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Set<com.acquira.common.model.SysMenu> menus = new HashSet<>();

        if (viewId != null) {
            List<com.acquira.common.model.UserCombinedView> views = tenantService.getCombinedViews(username);
            final Long finalViewId = viewId;
            com.acquira.common.model.UserCombinedView view = views.stream()
                    .filter(v -> v.getViewId().equals(finalViewId))
                    .findFirst().orElse(null);

            if (view != null) {
                String[] ids = view.getTenantIds().split(",");
                if (ids.length > 0) {
                    try { tenantId = Long.parseLong(ids[0].trim()); } catch (Exception e) {}
                }
            }
        }

        if (tenantId != null) {
            Optional<com.acquira.common.model.UserTenantAccess> access = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, tenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null) {
                menus = access.get().getSysUserGroup().getMenus();
            }

            if (menus.isEmpty() && "ROLE_SUPER_ADMIN".equals(user.getRole())) {
                Optional<com.acquira.common.model.SysUserGroup> superGroup = groupRepository
                        .findByGroupName("Super Admin");
                if (superGroup.isPresent() && superGroup.get().getMenus() != null) {
                    menus = superGroup.get().getMenus();
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("menus", menus);
        result.put("activeTenantId", tenantId);

        if (tenantId != null) {
            Optional<com.acquira.common.model.UserTenantAccess> access = userTenantAccessRepository
                    .findByUserAndTenant_TenantId(user, tenantId);
            if (access.isPresent() && access.get().getSysUserGroup() != null) {
                result.put("groupName", access.get().getSysUserGroup().getGroupName());
            } else if ("ROLE_SUPER_ADMIN".equals(user.getRole())) {
                result.put("groupName", "Super Admin");
            }
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/session")
    public ResponseEntity<?> getSessionData() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(401).body(Map.of("error", "No active session"));
        }

        String username = auth.getName();
        List<com.acquira.common.model.Tenant> allowedTenants = tenantService.getAllowedTenantsForUser(username);
        Long defaultTenantId = tenantService.getDefaultTenantIdForUser(username);

        Map<String, Object> response = new HashMap<>();
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", defaultTenantId);
        response.put("username", username);
        response.put("roles", auth.getAuthorities());

        return ResponseEntity.ok(response);
    }

    // ===== FORGOT PASSWORD — Send reset link via email =====
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");
        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        Optional<User> userOpt = userRepository.findByEmail(email.trim());
        // Always return success (don't leak whether email exists)
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("message",
                    "If that email is registered, a password reset link has been sent."));
        }

        User user = userOpt.get();

        // Delete any existing tokens for this user
        resetTokenRepository.deleteByUserId(user.getId());

        // Generate token
        String token = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);
        resetTokenRepository.save(new PasswordResetToken(user, token, expiresAt));

        // TODO: Send email with link — integrate with your existing SMTP service
        // Example link: https://yourdomain.com/reset-password?token=<token>
        // For now, log it (remove in production):
        System.out.println("[PASSWORD RESET] Token for " + user.getUsername() + ": " + token);

        return ResponseEntity.ok(Map.of("message",
                "If that email is registered, a password reset link has been sent."));
    }

    // ===== RESET PASSWORD — Using token from email =====
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> payload) {
        String token = payload.get("token");
        String newPassword = payload.get("newPassword");

        if (token == null || newPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token and new password are required"));
        }

        Optional<PasswordResetToken> tokenOpt = resetTokenRepository.findByTokenAndUsedFalse(token);
        if (tokenOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired reset link"));
        }

        PasswordResetToken resetToken = tokenOpt.get();
        if (resetToken.isExpired()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Reset link has expired. Please request a new one."));
        }

        User user = resetToken.getUser();

        // Use admin reset (no current password needed)
        String error = passwordService.adminResetPassword(user, newPassword);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }

        // Mark token as used
        resetToken.setUsed(true);
        resetTokenRepository.save(resetToken);

        // For email-based reset, don't force change on next login (they just chose it)
        user.setMustChangePassword(false);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password has been reset successfully. You can now sign in."));
    }

    // ===== Rate Limiting Helpers =====
    private boolean isRateLimited(String clientIp) {
        long now = System.currentTimeMillis();
        long[] data = loginAttempts.get(clientIp);
        if (data == null) return false;
        if (now - data[1] > WINDOW_MS) {
            loginAttempts.remove(clientIp);
            return false;
        }
        return data[0] >= MAX_IP_ATTEMPTS;
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

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
