package com.acquira.core.controller;

import com.acquira.common.model.PasswordResetToken;
import com.acquira.common.model.User;
import com.acquira.common.repository.PasswordResetTokenRepository;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.security.JwtUtil;
import com.acquira.core.service.PasswordService;
import com.acquira.core.service.RefreshTokenService;
import com.acquira.core.service.TenantService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import com.acquira.core.service.EmailService;
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
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;
    private final com.acquira.common.service.AuditService auditService;

    // ===== IP-based rate limiter (defense-in-depth, kept alongside per-user lockout) =====
    // P2-7 fix: bucket key is now (ip|username), not just ip. Previously a single
    // typo'd password from a corporate NAT could lock the whole office out for 1
    // minute, because every employee shared one IP. Now the bucket is per-pair so
    // one user's failures only affect that user's logins from that IP.
    private final ConcurrentHashMap<String, long[]> loginAttempts = new ConcurrentHashMap<>();
    private static final int MAX_IP_ATTEMPTS = 10;
    private static final long WINDOW_MS = 60_000;

    // P1-5 fix: collapse multiple distinct auth-failure responses into one
    // generic 401 message to prevent username enumeration. The specific reason
    // (bad creds vs inactive vs pending) is captured in the audit log only.
    private static final String GENERIC_AUTH_FAILURE = "Invalid username or password";

    // Per-user lockout settings (could load from tenant_setting)
    private static final int MAX_USER_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;

    public AuthController(JwtUtil jwtUtil, UserDetailsService userDetailsService, TenantService tenantService,
            AuthenticationManager authenticationManager,
            com.acquira.common.repository.SysUserGroupRepository groupRepository,
            UserRepository userRepository,
            com.acquira.common.repository.UserTenantAccessRepository userTenantAccessRepository,
            PasswordService passwordService,
            PasswordResetTokenRepository resetTokenRepository,
            EmailService emailService,
            RefreshTokenService refreshTokenService,
            com.acquira.common.service.AuditService auditService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.tenantService = tenantService;
        this.authenticationManager = authenticationManager;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.passwordService = passwordService;
        this.resetTokenRepository = resetTokenRepository;
        this.emailService = emailService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
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

        // IP rate limiting (defense-in-depth layer) — P2-7: keyed by (ip, username)
        String clientIp = getClientIp(httpRequest);
        String rateKey = clientIp + "|" + (authenticationRequest.getUsername() == null ? ""
                : authenticationRequest.getUsername().trim().toLowerCase());
        if (isRateLimited(rateKey)) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Too many login attempts. Please try again later."));
        }

        // ===== Per-user lockout check =====
        Optional<User> userOpt = userRepository.findByUsername(authenticationRequest.getUsername());
        if (userOpt.isPresent()) {
            User dbUser = userOpt.get();

            // P1-4 fix: if a previous lockout has expired, reset the counter so
            // the user gets a fresh 5 attempts instead of being immediately re-locked.
            if (dbUser.getLockedUntil() != null
                    && dbUser.getLockedUntil().isBefore(LocalDateTime.now())) {
                dbUser.setFailedLoginAttempts(0);
                dbUser.setLockedUntil(null);
                userRepository.save(dbUser);
            }

            // P1-5 fix: do NOT short-circuit with distinct status codes for
            // 'inactive' / 'pending approval' — that lets an attacker enumerate
            // valid usernames. We still record an audit entry with the real reason.
            // The actual auth attempt below will return the generic failure for
            // any of: bad creds / inactive / pending. Lockout (423) stays distinct
            // because it's the user's OWN account state and they need to know
            // why they can't log in even with the right password.
            if (dbUser.isAccountLocked()) {
                long minutesRemaining = Duration.between(LocalDateTime.now(), dbUser.getLockedUntil()).toMinutes() + 1;
                return ResponseEntity.status(423).body(Map.of(
                        "error", "Account is locked. Try again in " + minutesRemaining + " minute(s).",
                        "locked", true));
            }

            if (!dbUser.isActive() || dbUser.isPendingApproval()) {
                // Audit the real reason for ops to see; respond generically to the user.
                String reason = !dbUser.isActive() ? "INACTIVE" : "PENDING_APPROVAL";
                auditService.log("LOGIN_DENIED",
                        "User '" + dbUser.getUsername() + "' login denied: " + reason +
                        " from " + clientIp);
                recordFailedAttempt(rateKey);
                return ResponseEntity.status(401).body(Map.of("error", GENERIC_AUTH_FAILURE));
            }
        }

        // ===== Authenticate =====
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            authenticationRequest.getUsername(),
                            authenticationRequest.getPassword()));
        } catch (BadCredentialsException e) {
            recordFailedAttempt(rateKey);

            // Per-user failed attempt tracking
            if (userOpt.isPresent()) {
                User dbUser = userOpt.get();
                dbUser.setFailedLoginAttempts(dbUser.getFailedLoginAttempts() + 1);
                dbUser.setLastFailedLogin(LocalDateTime.now());

                if (dbUser.getFailedLoginAttempts() >= MAX_USER_ATTEMPTS) {
                    dbUser.setLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
                    userRepository.save(dbUser);
                    return ResponseEntity.status(423).body(Map.of(
                            "error", "Account locked due to too many failed attempts. Try again later.",
                            "locked", true));
                }

                userRepository.save(dbUser);
                // P1-5: don't include attemptsRemaining — that's a side-channel
                // hint that the username exists.
            }

            return ResponseEntity.status(401).body(Map.of("error", GENERIC_AUTH_FAILURE));
        }

        // ===== Successful login — reset lockout counters =====
        loginAttempts.remove(rateKey);

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

        // #14: Store refresh token in DB for rotation tracking
        refreshTokenService.storeToken(userDetails.getUsername(), refreshToken,
            java.time.LocalDateTime.now().plusDays(7),
            httpRequest.getHeader("User-Agent"), getClientIp(httpRequest));

        // #13: Audit successful login
        auditService.log("LOGIN", "User '" + userDetails.getUsername() + "' logged in from " + getClientIp(httpRequest));

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
        response.put("refreshToken", refreshToken); // Still in body for backward compat; frontend should migrate to cookie
        response.put("allowedTenants", allowedTenants);
        response.put("defaultTenantId", effectiveTenantId);
        response.put("roles", userDetails.getAuthorities());
        response.put("menus", menus);
        response.put("username", authenticationRequest.getUsername());
        response.put("userRole", user != null ? user.getRole() : "ROLE_USER");
        // GAP-12: Include displayName and ssoProvider in login response
        if (user != null) {
            response.put("displayName", user.getDisplayName());
            response.put("ssoProvider", user.getSsoProvider());
        }

        // ===== Force password change flag =====
        if (user != null && user.isMustChangePassword()) {
            response.put("mustChangePassword", true);
        }

        // #12: Set refresh token as HttpOnly cookie (XSS-safe)
        ResponseCookie cookie = buildRefreshCookie(refreshToken, 7 * 24 * 60 * 60);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    // ===== Refresh Token Endpoint =====
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody(required = false) Map<String, String> payload,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        // #12: Read refresh token from HttpOnly cookie first, fall back to body
        String refreshToken = null;
        if (httpRequest.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : httpRequest.getCookies()) {
                if ("refreshToken".equals(c.getName())) {
                    refreshToken = c.getValue();
                    break;
                }
            }
        }
        // Fallback: read from request body (backward compat)
        if ((refreshToken == null || refreshToken.isBlank()) && payload != null) {
            refreshToken = payload.get("refreshToken");
        }
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

            // #14: Check if token is valid in DB (not revoked)
            if (!refreshTokenService.isTokenValid(refreshToken)) {
                // Token was revoked (possibly stolen and reused)
                return ResponseEntity.status(401).body(Map.of("error", "Refresh token has been revoked. Please log in again."));
            }

            User dbUser = userRepository.findByUsername(username).orElse(null);
            if (dbUser == null || !dbUser.isActive()) {
                return ResponseEntity.status(401).body(Map.of("error", "User account is disabled"));
            }

            String newAccessToken = jwtUtil.generateToken(userDetails);
            String newRefreshToken = jwtUtil.generateRefreshToken(username);

            // #14: Rotate — revoke old, store new
            boolean rotated = refreshTokenService.rotateToken(username, refreshToken, newRefreshToken,
                java.time.LocalDateTime.now().plusDays(7),
                httpRequest.getHeader("User-Agent"), getClientIp(httpRequest));

            if (!rotated) {
                // Token reuse detected — all sessions revoked
                return ResponseEntity.status(401).body(Map.of(
                    "error", "Security alert: refresh token reuse detected. All sessions have been revoked. Please log in again."));
            }

            // #12: Set new refresh token as HttpOnly cookie
            ResponseCookie newCookie = buildRefreshCookie(newRefreshToken, 7 * 24 * 60 * 60);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, newCookie.toString())
                    .body(Map.of(
                        "jwt", newAccessToken,
                        "refreshToken", newRefreshToken)); // Body included for backward compat
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token"));
        }
    }

    // #14: Logout all devices — revokes all refresh tokens
    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAllDevices() {
        String username = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getName();
        int revoked = refreshTokenService.revokeAllForUser(username);
        auditService.log("LOGOUT_ALL", "User '" + username + "' revoked " + revoked + " sessions");
        // #12: Clear refresh token cookie
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .body(Map.of("message", "All sessions revoked", "revokedCount", revoked));
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

        // GAP-18: Send email with reset link (falls back to log if SMTP not configured)
        emailService.sendPasswordResetEmail(user.getEmail(), user.getUsername(), token);

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

    // ===== HttpOnly Cookie Helper =====
    private ResponseCookie buildRefreshCookie(String refreshToken, long maxAgeSeconds) {
        return ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)          // Not accessible via JavaScript — XSS safe
                .secure(true)            // Only sent over HTTPS
                .path("/api/auth")       // Scoped to auth endpoints only
                .maxAge(maxAgeSeconds)   // 7 days
                .sameSite("Strict")      // CSRF protection
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true).secure(true).path("/api/auth")
                .maxAge(0).sameSite("Strict").build();
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
