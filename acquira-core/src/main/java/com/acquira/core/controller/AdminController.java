package com.acquira.core.controller;

import com.acquira.common.model.Tenant;
import com.acquira.common.model.User;
import com.acquira.common.model.UserTenantAccess;
// import com.acquira.common.model.Role; // Role is likely not a separate entity in this simplified version or needs import
import com.acquira.common.model.RefCountry;
import com.acquira.common.repository.TenantRepository;
import com.acquira.common.repository.UserRepository;
import com.acquira.common.repository.UserTenantAccessRepository;
import com.acquira.common.repository.RoleRepository;
import com.acquira.common.repository.RefCountryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminController {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final UserTenantAccessRepository userTenantAccessRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.acquira.common.service.AuditService auditService;
    private final com.acquira.common.repository.TenantSettingRepository tenantSettingRepository;
    private final com.acquira.common.repository.DashboardConfigRepository dashboardConfigRepository;
    private final RefCountryRepository refCountryRepository;
    private final com.acquira.core.service.PasswordService passwordService;
    private final com.acquira.core.service.RefreshTokenService refreshTokenService;
    private final com.acquira.core.service.TenantProvisioningService provisioningService;

    public AdminController(TenantRepository tenantRepository, UserRepository userRepository,
            UserTenantAccessRepository userTenantAccessRepository, RoleRepository roleRepository,
            PasswordEncoder passwordEncoder, com.acquira.common.service.AuditService auditService,
            com.acquira.common.repository.TenantSettingRepository tenantSettingRepository,
            com.acquira.common.repository.DashboardConfigRepository dashboardConfigRepository,
            RefCountryRepository refCountryRepository,
            com.acquira.core.service.PasswordService passwordService,
            com.acquira.core.service.RefreshTokenService refreshTokenService,
            com.acquira.core.service.TenantProvisioningService provisioningService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.tenantSettingRepository = tenantSettingRepository;
        this.dashboardConfigRepository = dashboardConfigRepository;
        this.refCountryRepository = refCountryRepository;
        this.passwordService = passwordService;
        this.refreshTokenService = refreshTokenService;
        this.provisioningService = provisioningService;
    }

    @GetMapping("/countries")
    public ResponseEntity<List<RefCountry>> getAllCountries() {
        return ResponseEntity.ok(refCountryRepository.findAllByOrderByCountryNameAsc());
    }

    @PostMapping("/tenants")
    // SECURITY: tenant creation is platform-level. Class guard is ADMIN+, so without
    // this a Bank Admin could create tenants via this parallel of the SA-only
    // POST /api/banks the UI actually uses.
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Tenant> createTenant(@RequestBody Tenant tenant) {
        Tenant saved = tenantRepository.save(tenant);
        auditService.log("CREATE_TENANT",
                "Created tenant: " + saved.getBankName() + " (" + saved.getInstitutionId() + ")");
        // Auto-provision the new tenant (settings defaults, default sales leads,
        // email templates, ...) from the tenant_provision_script registry.
        // Failures never abort creation — re-runnable from Admin > Tenant Provisioning.
        try {
            org.springframework.security.core.Authentication a = org.springframework.security.core.context
                    .SecurityContextHolder.getContext().getAuthentication();
            provisioningService.provision(saved.getTenantId(), a != null ? a.getName() : "system");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AdminController.class)
                    .error("Tenant provisioning failed for tenant {}: {}", saved.getTenantId(), e.getMessage());
        }
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity createUser(@RequestBody User user,
            @RequestParam(required = false) Long tenantId) {
        // Tenant-isolation fix: a non-super-admin (bank admin) may only create+assign
        // a user within the tenant currently active in their session. tenantId here is
        // a request PARAM, so it is NOT validated by JwtRequestFilter (which only checks
        // the X-Tenant-Id header) — without this guard a bank admin could assign a new
        // user to any tenant by passing an arbitrary tenantId.
        if (tenantId != null && !isSuperAdmin()) {
            Long activeTenant = com.acquira.common.config.TenantContext.getCurrentTenant();
            if (activeTenant == null || !activeTenant.equals(tenantId)) {
                return ResponseEntity.status(403).body(java.util.Map.of(
                        "error", "You may only create users within your active tenant"));
            }
        }
        // A create must never carry a client-supplied id: JPA would merge onto the
        // existing row, silently overwriting another account's credentials.
        user.setId(null);

        // Uniqueness — without these the unique index on username threw a
        // DataIntegrityViolationException that surfaced as an opaque 500.
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Username is required"));
        }
        user.setUsername(user.getUsername().trim());
        if (userRepository.existsByUsername(user.getUsername())) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "Username '" + user.getUsername() + "' already exists"));
        }
        // Blank email is stored as null so multiple email-less users can coexist.
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
            user.setEmail(user.getEmail().trim());
            if (userRepository.existsByEmail(user.getEmail())) {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                        "error", "Email '" + user.getEmail() + "' is already registered"));
            }
        } else {
            user.setEmail(null);
        }

        // Validate password strength before encoding
        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Password is required"));
        }
        // Use the user-aware overload so a password derived from the new account's
        // own username/email/display name is rejected here too, exactly as it is
        // on POST /api/users and on every later password change.
        String strengthError = passwordService.validatePasswordStrength(user.getPassword(), user);
        if (strengthError != null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", strengthError));
        }

        // Encode password + record in history + set mustChangePassword
        String rawPassword = user.getPassword();
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setMustChangePassword(true);
        user.setPasswordChangedAt(java.time.LocalDateTime.now());

        // Default to ROLE_USER only if role is missing
        if (user.getRole() == null || user.getRole().isEmpty()) {
            user.setRole("ROLE_BANK_USER");
        }

        // SECURITY: users.role is read by JwtRequestFilter:137 to decide whether to skip
        // the UserTenantAccess check on X-Tenant-Id. A bank admin who can write
        // ROLE_SUPER_ADMIN here mints an account with access to every tenant, so the
        // role must be authorised, not merely defaulted.
        if ("ROLE_SUPER_ADMIN".equals(user.getRole().trim()) && !isSuperAdmin()) {
            return ResponseEntity.status(403).body(java.util.Map.of(
                    "error", "Only a super admin may assign the SUPER_ADMIN role"));
        }

        User savedUser = userRepository.save(user);

        // Record initial password in history (prevents immediate reuse)
        passwordService.recordPasswordInHistory(savedUser, savedUser.getPassword());
        auditService.log("CREATE_USER", "Created user: " + savedUser.getUsername());

        if (tenantId != null) {
            Tenant tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new RuntimeException("Tenant not found"));
            UserTenantAccess access = new UserTenantAccess();
            access.setUser(savedUser);
            access.setTenant(tenant);
            // First (and only) grant for a brand-new user — mark it default, or
            // login resolves no active tenant and the app opens empty.
            access.setIsDefaultTenant(true);
            userTenantAccessRepository.save(access);
            auditService.log("ASSIGN_TENANT",
                    "Assigned user " + savedUser.getUsername() + " to tenant " + tenant.getBankName());
        }

        return ResponseEntity.ok(savedUser);
    }

    // ==========================================
    // ===== Global Settings (uses current user's default tenant) =====

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(jakarta.servlet.http.HttpServletRequest request) {
        Long tenantId = extractTenantId(request);
        if (tenantId == null) return ResponseEntity.badRequest().body(java.util.Map.of("error", "No tenant context"));
        return ResponseEntity.ok(tenantSettingRepository.findByTenant_TenantId(tenantId));
    }

    @PutMapping("/settings")
    public ResponseEntity<?> updateSetting(jakarta.servlet.http.HttpServletRequest request,
            @RequestBody java.util.Map<String, String> payload) {
        Long tenantId = extractTenantId(request);
        if (tenantId == null) return ResponseEntity.badRequest().body(java.util.Map.of("error", "No tenant context"));

        String key = payload.get("settingKey");
        String value = payload.get("settingValue");
        if (key == null) return ResponseEntity.badRequest().body(java.util.Map.of("error", "settingKey is required"));

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        var existing = tenantSettingRepository.findByTenant_TenantIdAndKey(tenantId, key);
        com.acquira.common.model.TenantSetting setting;
        if (existing.isPresent()) {
            setting = existing.get();
            setting.setValue(value);
        } else {
            setting = new com.acquira.common.model.TenantSetting();
            setting.setTenant(tenant);
            setting.setKey(key);
            setting.setValue(value);
            setting.setType("STRING");
        }
        return ResponseEntity.ok(tenantSettingRepository.save(setting));
    }

    private boolean isSuperAdmin() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    /**
     * SECURITY: tenant-ownership check for the path-variable settings endpoints.
     * A super-admin may target any tenant; a bank admin only tenants present in
     * their own UserTenantAccess rows. Without this, /tenants/{id}/settings and
     * /tenants/{id}/dashboard-config were readable AND writable cross-tenant by
     * any Bank Admin (class guard is only ADMIN+).
     */
    private boolean canAccessTenant(Long tenantId) {
        if (tenantId == null) return false;
        if (isSuperAdmin()) return true;
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null) return false;
        return userTenantAccessRepository.findByUser(user).stream()
                .anyMatch(a -> a.getTenant().getTenantId().equals(tenantId));
    }

    private Long extractTenantId(jakarta.servlet.http.HttpServletRequest request) {
        // SECURITY: use the filter-VALIDATED tenant from TenantContext, never the raw
        // X-Tenant-Id header. JwtRequestFilter checks the header against the caller's
        // UserTenantAccess rows (super-admin bypasses) and only then populates
        // TenantContext; on a spoofed header it logs and falls back to the default
        // tenant. Re-parsing the raw header here let a bank admin read/write ANOTHER
        // tenant's settings by sending a foreign X-Tenant-Id.
        Long ctx = com.acquira.common.config.TenantContext.getCurrentTenant();
        if (ctx != null) return ctx;
        // Fallback: first tenant
        String username = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        var user = userRepository.findByUsername(username).orElse(null);
        if (user != null) {
            var accesses = userTenantAccessRepository.findByUser(user);
            if (!accesses.isEmpty()) return accesses.get(0).getTenant().getTenantId();
        }
        return null;
    }

    // Phase 6: Settings & Config
    // ==========================================

    @GetMapping("/tenants/{tenantId}/settings")
    public ResponseEntity<java.util.List<com.acquira.common.model.TenantSetting>> getTenantSettings(
            @PathVariable Long tenantId) {
        if (!canAccessTenant(tenantId)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(tenantSettingRepository.findByTenant_TenantId(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/settings")
    public ResponseEntity<com.acquira.common.model.TenantSetting> saveTenantSetting(@PathVariable Long tenantId,
            @RequestBody com.acquira.common.model.TenantSetting setting) {
        if (!canAccessTenant(tenantId)) return ResponseEntity.status(403).build();
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        setting.setTenant(tenant);
        return ResponseEntity.ok(tenantSettingRepository.save(setting));
    }

    @GetMapping("/tenants/{tenantId}/dashboard-config")
    public ResponseEntity<java.util.List<com.acquira.common.model.DashboardConfig>> getDashboardConfig(
            @PathVariable Long tenantId) {
        if (!canAccessTenant(tenantId)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(dashboardConfigRepository.findByTenant_TenantIdOrderByDisplayOrderAsc(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/dashboard-config")
    public ResponseEntity<com.acquira.common.model.DashboardConfig> saveDashboardConfig(@PathVariable Long tenantId,
            @RequestBody com.acquira.common.model.DashboardConfig config) {
        if (!canAccessTenant(tenantId)) return ResponseEntity.status(403).build();
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        config.setTenant(tenant);
        return ResponseEntity.ok(dashboardConfigRepository.save(config));
    }

    // ==========================================
    // ===== Security: Locked Users + Unlock =====
    // ==========================================

    /**
     * User ids belonging to the caller's active tenant, or null for a
     * SUPER_ADMIN (meaning: no filtering). A bank ADMIN must not see or act on
     * users of other tenants.
     */
    private java.util.Set<Long> tenantUserIdScope() {
        if (isSuperAdmin()) return null;
        Long tenantId = com.acquira.common.config.TenantContext.getCurrentTenant();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        if (tenantId != null) {
            for (var access : userTenantAccessRepository.findByTenant_TenantId(tenantId)) {
                if (access.getUser() != null) ids.add(access.getUser().getId());
            }
        }
        return ids;
    }

    @GetMapping("/security/locked-users")
    public ResponseEntity<?> getLockedUsers() {
        // Efficient DB query instead of scanning all users in Java
        java.util.List<User> lockedUsers = userRepository.findByLockedUntilAfter(java.time.LocalDateTime.now());
        java.util.Set<Long> scope = tenantUserIdScope();
        if (scope != null) {
            lockedUsers = lockedUsers.stream().filter(u -> scope.contains(u.getId())).toList();
        }
        java.util.List<java.util.Map<String, Object>> locked = new java.util.ArrayList<>();
        for (User u : lockedUsers) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("failedAttempts", u.getFailedLoginAttempts());
            m.put("lockedUntil", u.getLockedUntil());
            m.put("lastFailedLogin", u.getLastFailedLogin());
            locked.add(m);
        }
        return ResponseEntity.ok(locked);
    }

    @PostMapping("/security/unlock-user/{userId}")
    public ResponseEntity<?> unlockUser(@PathVariable Long userId) {
        java.util.Set<Long> scope = tenantUserIdScope();
        if (scope != null && !scope.contains(userId)) {
            return ResponseEntity.status(404).body(java.util.Map.of("error", "User not found"));
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLogin(null);
        userRepository.save(user);
        auditService.log("UNLOCK_USER", "Admin unlocked user: " + user.getUsername());
        return ResponseEntity.ok(java.util.Map.of("message", "User unlocked successfully"));
    }

    // Revoke every active refresh token across all users. Forces everyone
    // (including the calling admin) to sign in again. Backs the "Revoke all
    // sessions" action in Admin > Security Settings > Sessions & Tokens.
    @PostMapping("/security/revoke-all-sessions")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> revokeAllSessions() {
        int revoked = refreshTokenService.revokeAll();
        auditService.log("REVOKE_ALL_SESSIONS", "Admin revoked all active sessions (" + revoked + " tokens)");
        return ResponseEntity.ok(java.util.Map.of(
                "message", "All sessions revoked", "revokedCount", revoked));
    }

    // ==========================================
    // ===== Security: Password Policy (from tenant_setting) =====
    // ==========================================

    @GetMapping("/security/policy")
    public ResponseEntity<?> getPasswordPolicy(jakarta.servlet.http.HttpServletRequest request) {
        Long tenantId = extractTenantId(request);
        if (tenantId == null) return ResponseEntity.badRequest().body(java.util.Map.of("error", "No tenant context"));
        var settings = tenantSettingRepository.findByTenant_TenantId(tenantId);
        java.util.Map<String, Object> policy = new java.util.HashMap<>();
        // Defaults
        policy.put("minLength", 8); policy.put("maxLength", 128);
        policy.put("requireUppercase", true); policy.put("requireLowercase", true);
        policy.put("requireDigit", true); policy.put("requireSpecialChar", true);
        policy.put("passwordHistoryCount", 5); policy.put("maxFailedAttempts", 5);
        policy.put("lockoutDurationMinutes", 15); policy.put("passwordExpiryDays", 90);
        policy.put("sessionTimeoutMinutes", 30); policy.put("forceChangeOnFirstLogin", true);
        // Override from settings
        for (var s : settings) {
            String key = s.getKey();
            if (key != null && key.startsWith("security.")) {
                String prop = key.substring("security.".length());
                String val = s.getValue();
                if ("true".equals(val) || "false".equals(val)) {
                    policy.put(prop, Boolean.parseBoolean(val));
                } else {
                    try { policy.put(prop, Integer.parseInt(val)); } catch (Exception e) { policy.put(prop, val); }
                }
            }
        }
        return ResponseEntity.ok(policy);
    }

    @PutMapping("/security/policy")
    public ResponseEntity<?> updatePasswordPolicy(jakarta.servlet.http.HttpServletRequest request,
            @RequestBody java.util.Map<String, Object> policyMap) {
        Long tenantId = extractTenantId(request);
        if (tenantId == null) return ResponseEntity.badRequest().body(java.util.Map.of("error", "No tenant context"));
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        for (var entry : policyMap.entrySet()) {
            String key = "security." + entry.getKey();
            String value = String.valueOf(entry.getValue());
            var existing = tenantSettingRepository.findByTenant_TenantIdAndKey(tenantId, key);
            com.acquira.common.model.TenantSetting setting;
            if (existing.isPresent()) {
                setting = existing.get();
                setting.setValue(value);
            } else {
                setting = new com.acquira.common.model.TenantSetting();
                setting.setTenant(tenant);
                setting.setKey(key);
                setting.setValue(value);
                setting.setType("STRING");
            }
            tenantSettingRepository.save(setting);
        }
        auditService.log("UPDATE_SECURITY_POLICY", "Updated password/security policy settings");
        return ResponseEntity.ok(java.util.Map.of("message", "Security policy updated"));
    }
}
