package com.acquira.core.controller;

import com.acquira.common.model.User;
import com.acquira.common.repository.UserRepository;
import com.acquira.core.service.PasswordService;
import com.acquira.core.service.TenantService;
import com.acquira.common.config.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final TenantService tenantService;
    private final com.acquira.common.repository.UserTenantAccessRepository accessRepository;
    private final com.acquira.common.repository.SysUserGroupRepository groupRepository;
    private final com.acquira.common.repository.TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordService passwordService;

    public UserController(UserRepository userRepository,
            TenantService tenantService,
            com.acquira.common.repository.UserTenantAccessRepository accessRepository,
            com.acquira.common.repository.SysUserGroupRepository groupRepository,
            com.acquira.common.repository.TenantRepository tenantRepository,
            PasswordEncoder passwordEncoder,
            PasswordService passwordService) {
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.accessRepository = accessRepository;
        this.groupRepository = groupRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordService = passwordService;
    }

    // ============================================================
    //  Tenant-isolation helpers (audit fix)
    //
    //  A SUPER_ADMIN may see/manage every user across all tenants.
    //  A bank ADMIN may only see/manage users that belong to the
    //  tenant currently active in their session (X-Tenant-Id, resolved
    //  into TenantContext by JwtRequestFilter). Previously every admin
    //  endpoint operated on userRepository.findAll() with no tenant
    //  filter, leaking the full cross-tenant user list.
    // ============================================================

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    /** The set of user-ids that belong to the active tenant. */
    private Set<Long> userIdsInCurrentTenant() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return java.util.Collections.emptySet();
        return accessRepository.findByTenant_TenantId(tenantId).stream()
                .map(a -> a.getUser().getId())
                .collect(Collectors.toSet());
    }

    /**
     * Guard for by-id operations. Returns true if the caller is allowed to
     * act on the given user. Super-admins always pass; bank admins only pass
     * if the target user belongs to the active tenant.
     */
    private boolean canActOnUser(Long targetUserId) {
        if (isSuperAdmin()) return true;
        return userIdsInCurrentTenant().contains(targetUserId);
    }

    /**
     * Whether the caller may grant/modify access to the given tenant. Super
     * admins may target any tenant; a bank admin may only target the tenant
     * currently active in their session. Prevents a bank admin from granting
     * a user access to a tenant they don't themselves administer.
     */
    private boolean canAssignTenant(Long targetTenantId) {
        if (isSuperAdmin()) return true;
        Long current = TenantContext.getCurrentTenant();
        return current != null && current.equals(targetTenantId);
    }

    /**
     * SECURITY: the users.role column is not just a UI hint — JwtRequestFilter:137
     * reads it and, when it equals ROLE_SUPER_ADMIN, SKIPS the UserTenantAccess check
     * for the X-Tenant-Id header. Letting a bank admin write that value through a
     * request body is therefore a full tenant-isolation bypass, not a cosmetic
     * privilege bump. Only a super admin may assign a super-admin role.
     */
    private boolean mayAssignRole(String role) {
        if (role == null || role.isBlank()) return true;
        if (isSuperAdmin()) return true;
        return !"ROLE_SUPER_ADMIN".equals(role.trim());
    }

    // ===== CREATE USER (with username + email duplicate check) =====
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> createUser(@RequestBody User user) {
        // Validate username
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        if (userRepository.existsByUsername(user.getUsername().trim())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username '" + user.getUsername() + "' already exists"));
        }

        // Email is optional. When supplied, normalise and enforce uniqueness;
        // when blank/absent, store null so multiple email-less users can coexist
        // (an empty string would collide on the uniqueness check).
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
            String email = user.getEmail().trim();
            if (userRepository.existsByEmail(email)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Email '" + email + "' is already registered"));
            }
            user.setEmail(email);
        } else {
            user.setEmail(null);
        }

        if (!mayAssignRole(user.getRole())) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Only a super admin may assign the SUPER_ADMIN role"));
        }

        // Validate password
        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
        }
        String strengthError = passwordService.validatePasswordStrength(user.getPassword(), user);
        if (strengthError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", strengthError));
        }

        // Save with encoded password + must_change_password = true
        String rawPassword = user.getPassword();
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setMustChangePassword(true);
        user.setPasswordChangedAt(LocalDateTime.now());
        User saved = userRepository.save(user);

        // Record initial password in history
        passwordService.recordPasswordInHistory(saved, saved.getPassword());

        return ResponseEntity.ok(saved);
    }

    // ===== GET ALL USERS =====
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<User>> getAllUsers() {
        // Tenant-isolation fix: super-admins see all users; bank admins only
        // see users belonging to their active tenant.
        if (isSuperAdmin()) {
            return ResponseEntity.ok(userRepository.findAll());
        }
        Set<Long> allowedIds = userIdsInCurrentTenant();
        List<User> scoped = userRepository.findAllById(allowedIds);
        return ResponseEntity.ok(scoped);
    }

    // ===== UPDATE USER (email, active status, role) =====
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User userDetails) {
        // Tenant-isolation fix: a bank admin may only modify users in their tenant.
        if (!canActOnUser(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user"));
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update email (with duplicate check if changed)
        if (userDetails.getEmail() != null && !userDetails.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(userDetails.getEmail().trim())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Email '" + userDetails.getEmail() + "' is already registered"));
            }
            user.setEmail(userDetails.getEmail().trim());
        }

        // GAP-3: Update displayName
        if (userDetails.getDisplayName() != null) {
            user.setDisplayName(userDetails.getDisplayName());
        }

        // Account expiry — always applied from the edit payload so it can be both
        // set and cleared (null = no expiry). The frontend sends the current value
        // on every save, so an unchanged edit is a harmless no-op.
        user.setAccountExpiresAt(userDetails.getAccountExpiresAt());

        // GAP-4: Update role (only if provided and caller is SUPER_ADMIN)
        if (userDetails.getRole() != null && !userDetails.getRole().isBlank()) {
            if (!mayAssignRole(userDetails.getRole())) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "Only a super admin may assign the SUPER_ADMIN role"));
            }
            user.setRole(userDetails.getRole());
        }

        user.setActive(userDetails.isActive());

        // If password is provided in update, use admin reset flow
        if (userDetails.getPassword() != null && !userDetails.getPassword().isEmpty()) {
            String error = passwordService.adminResetPassword(user, userDetails.getPassword());
            if (error != null) {
                return ResponseEntity.badRequest().body(Map.of("error", error));
            }
            // adminResetPassword already saves — but we still need to save email/active changes
        }

        return ResponseEntity.ok(userRepository.save(user));
    }

    // ===== ADMIN RESET PASSWORD (dedicated endpoint) =====
    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> adminResetPassword(@PathVariable Long userId,
            @RequestBody Map<String, String> payload) {
        // Tenant-isolation fix: a bank admin may only reset passwords for users in their tenant.
        if (!canActOnUser(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user"));
        }
        String newPassword = payload.get("newPassword");
        if (newPassword == null || newPassword.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "New password is required"));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String error = passwordService.adminResetPassword(user, newPassword);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }

        return ResponseEntity.ok(Map.of(
                "message", "Password for '" + user.getUsername()
                        + "' has been reset. User will be required to change it on next login."));
    }

    // ===== UNLOCK ACCOUNT =====
    @PostMapping("/{userId}/unlock")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> unlockAccount(@PathVariable Long userId) {
        // Tenant-isolation fix: a bank admin may only unlock users in their tenant.
        if (!canActOnUser(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user"));
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLogin(null);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message",
                "Account '" + user.getUsername() + "' has been unlocked successfully."));
    }

    // ===== SELF-SERVICE CHANGE PASSWORD (authenticated user) =====
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> payload) {
        String currentPassword = payload.get("currentPassword");
        String newPassword = payload.get("newPassword");

        if (currentPassword == null || newPassword == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Both currentPassword and newPassword are required"));
        }

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String error = passwordService.changePassword(user, currentPassword, newPassword);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }

        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    // ===== CHECK EMAIL AVAILABILITY =====
    @GetMapping("/check-email")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> checkEmail(@RequestParam String email) {
        boolean exists = userRepository.existsByEmail(email.trim());
        return ResponseEntity.ok(Map.of("available", !exists));
    }

    // ===== CHECK USERNAME AVAILABILITY =====
    @GetMapping("/check-username")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> checkUsername(@RequestParam String username) {
        boolean exists = userRepository.existsByUsername(username.trim());
        return ResponseEntity.ok(Map.of("available", !exists));
    }

    // ===== ASSIGN TENANT =====
    @PostMapping("/{userId}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> assignTenant(@PathVariable Long userId, @RequestBody Map<String, Object> payload) {
        Object bankIdObj = payload.get("bankId");
        Object groupIdObj = payload.get("groupId");

        Long tenantId = bankIdObj != null ? Long.valueOf(bankIdObj.toString()) : null;
        Long groupId = groupIdObj != null ? Long.valueOf(groupIdObj.toString()) : null;

        if (tenantId == null || groupId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "TenantId and GroupId are required"));
        }

        // Tenant-isolation fix: bank admins may only assign within their own tenant
        // and only to users in their tenant.
        if (!canActOnUser(userId) || !canAssignTenant(tenantId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user or tenant"));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        com.acquira.common.model.Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));
        com.acquira.common.model.SysUserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        com.acquira.common.model.UserTenantAccess access = new com.acquira.common.model.UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);

        accessRepository.save(access);
        return ResponseEntity.ok(Map.of("message", "Tenant and Group assigned successfully"));
    }

    @GetMapping("/{username}/banks")
    public ResponseEntity<List<com.acquira.common.model.Tenant>> getUserTenants(@PathVariable String username) {
        // Self-or-admin guard: previously ANY authenticated user could enumerate
        // any other user's tenant/bank assignments by username.
        String caller = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        if (!caller.equals(username)) {
            Long targetId = userRepository.findByUsername(username).map(User::getId).orElse(null);
            if (targetId == null || !canActOnUser(targetId)) {
                return ResponseEntity.status(403).build();
            }
        }
        return ResponseEntity.ok(tenantService.getAllowedTenantsForUser(username));
    }

    // ===== GET USER TENANT ASSIGNMENTS (for edit panel) =====
    @GetMapping("/{userId}/tenant-access")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> getUserTenantAccess(@PathVariable Long userId) {
        // Tenant-isolation fix: bank admins may only view access for users in their tenant.
        if (!canActOnUser(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user"));
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<com.acquira.common.model.UserTenantAccess> accesses = accessRepository.findAllByUser(user);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (com.acquira.common.model.UserTenantAccess a : accesses) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("accessId", a.getAccessId());
            map.put("tenantId", a.getTenant().getTenantId());
            map.put("tenantName", a.getTenant().getBankName());
            map.put("groupId", a.getSysUserGroup() != null ? a.getSysUserGroup().getGroupId() : null);
            map.put("groupName", a.getSysUserGroup() != null ? a.getSysUserGroup().getGroupName() : null);
            map.put("roleInTenant", a.getRoleInTenant());
            map.put("isDefault", Boolean.TRUE.equals(a.getIsDefaultTenant()));
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    // ===== ADD TENANT ACCESS =====
    @PostMapping("/{userId}/tenant-access")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> addTenantAccess(@PathVariable Long userId, @RequestBody Map<String, Object> payload) {
        Long tenantId = Long.valueOf(payload.get("tenantId").toString());
        Long groupId = Long.valueOf(payload.get("groupId").toString());

        // Tenant-isolation fix: bank admins may only add access within their own
        // tenant and only to users in their tenant.
        if (!canActOnUser(userId) || !canAssignTenant(tenantId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user or tenant"));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String roleInTenant = (String) payload.get("roleInTenant");
        Boolean isDefault = Boolean.TRUE.equals(payload.get("isDefault"));

        // Check for duplicate
        if (accessRepository.findByUserAndTenant_TenantId(user, tenantId).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User already has access to this tenant"));
        }

        com.acquira.common.model.Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));
        com.acquira.common.model.SysUserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        // If setting as default, unset others
        if (isDefault) {
            accessRepository.findAllByUser(user).forEach(a -> {
                a.setIsDefaultTenant(false);
                accessRepository.save(a);
            });
        }

        com.acquira.common.model.UserTenantAccess access = new com.acquira.common.model.UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);
        access.setRoleInTenant(roleInTenant);
        access.setIsDefaultTenant(isDefault);
        accessRepository.save(access);

        return ResponseEntity.ok(Map.of("message", "Tenant access added"));
    }

    // ===== UPDATE TENANT ACCESS =====
    @PutMapping("/{userId}/tenant-access/{accessId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> updateTenantAccess(@PathVariable Long userId, @PathVariable Integer accessId,
            @RequestBody Map<String, Object> payload) {
        // Tenant-isolation fix: bank admins may only modify access for users in their tenant.
        if (!canActOnUser(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user"));
        }

        com.acquira.common.model.UserTenantAccess access = accessRepository.findById(accessId)
                .orElseThrow(() -> new RuntimeException("Access not found"));

        // Tenant-isolation fix: the access row must belong to the user named in the
        // path (prevents acting on another user's access by guessing accessId), and
        // a bank admin may only touch an access row for a tenant they administer.
        if (!access.getUser().getId().equals(userId)
                || !canAssignTenant(access.getTenant().getTenantId())) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this access record"));
        }

        Long groupId = Long.valueOf(payload.get("groupId").toString());
        String roleInTenant = (String) payload.get("roleInTenant");
        Boolean isDefault = Boolean.TRUE.equals(payload.get("isDefault"));

        com.acquira.common.model.SysUserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        access.setSysUserGroup(group);
        access.setRoleInTenant(roleInTenant);

        if (isDefault) {
            accessRepository.findAllByUser(access.getUser()).forEach(a -> {
                a.setIsDefaultTenant(false);
                accessRepository.save(a);
            });
        }
        access.setIsDefaultTenant(isDefault);
        accessRepository.save(access);

        return ResponseEntity.ok(Map.of("message", "Tenant access updated"));
    }

    // ===== REMOVE TENANT ACCESS =====
    @DeleteMapping("/{userId}/tenant-access/{accessId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> removeTenantAccess(@PathVariable Long userId, @PathVariable Integer accessId) {
        // Tenant-isolation fix: bank admins may only remove access for users in their tenant.
        if (!canActOnUser(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user"));
        }
        com.acquira.common.model.UserTenantAccess access = accessRepository.findById(accessId)
                .orElseThrow(() -> new RuntimeException("Access not found"));
        // The access row must belong to the path user, and a bank admin may only
        // remove an access row for a tenant they administer (prevents cross-tenant
        // deletes by guessing accessId).
        if (!access.getUser().getId().equals(userId)
                || !canAssignTenant(access.getTenant().getTenantId())) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this access record"));
        }
        accessRepository.delete(access);
        return ResponseEntity.ok(Map.of("message", "Tenant access removed"));
    }

    // ===== GET ALL USERS (ENRICHED with tenant info) =====
    @GetMapping("/enriched")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> getAllUsersEnriched() {
        // Tenant-isolation fix: super-admins see all users; bank admins only
        // see users belonging to their active tenant.
        List<User> users = isSuperAdmin()
                ? userRepository.findAll()
                : userRepository.findAllById(userIdsInCurrentTenant());
        List<Map<String, Object>> result = new java.util.ArrayList<>();

        for (User u : users) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("email", u.getEmail());
            map.put("displayName", u.getDisplayName());
            map.put("role", u.getRole());
            map.put("active", u.isActive());
            map.put("ssoProvider", u.getSsoProvider());
            map.put("approvalStatus", u.getApprovalStatus());
            map.put("mustChangePassword", u.isMustChangePassword());
            map.put("lockedUntil", u.getLockedUntil());
            map.put("accountExpiresAt", u.getAccountExpiresAt());
            map.put("createdAt", u.getCreatedAt());

            // Tenant assignments
            List<com.acquira.common.model.UserTenantAccess> accesses = accessRepository.findAllByUser(u);
            List<Map<String, Object>> tenantList = new java.util.ArrayList<>();
            for (com.acquira.common.model.UserTenantAccess a : accesses) {
                Map<String, Object> ta = new java.util.HashMap<>();
                ta.put("accessId", a.getAccessId());
                ta.put("tenantId", a.getTenant().getTenantId());
                ta.put("tenantName", a.getTenant().getBankName());
                ta.put("groupName", a.getSysUserGroup() != null ? a.getSysUserGroup().getGroupName() : null);
                ta.put("isDefault", Boolean.TRUE.equals(a.getIsDefaultTenant()));
                tenantList.add(ta);
            }
            map.put("tenants", tenantList);
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    // ===== EXPORT USERS (CSV) =====
    // Same tenant-isolation as /enriched: super-admins export all users; bank
    // admins export only users in their active tenant. No secrets are ever
    // emitted (password hash is @JsonIgnore on the entity and simply not read
    // here). Tenants column lists the user's bank assignments; the default one
    // is flagged with an asterisk.
    @GetMapping("/export/csv")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportUsersCsv() {
        List<User> users = isSuperAdmin()
                ? userRepository.findAll()
                : userRepository.findAllById(userIdsInCurrentTenant());

        StringBuilder sb = new StringBuilder();
        // Excel-friendly UTF-8 BOM so accented names render correctly.
        sb.append('\uFEFF');
        String[] header = {
                "Username", "Display Name", "Email", "Role", "Active", "Approval Status",
                "SSO Provider", "Must Change Password", "Locked Until", "Account Expires At",
                "Tenants", "Created At"
        };
        sb.append(String.join(",", header)).append("\r\n");

        for (User u : users) {
            List<com.acquira.common.model.UserTenantAccess> accesses = accessRepository.findAllByUser(u);
            String tenants = accesses.stream()
                    .map(a -> a.getTenant().getBankName()
                            + (Boolean.TRUE.equals(a.getIsDefaultTenant()) ? " *" : ""))
                    .collect(Collectors.joining("; "));

            String[] row = {
                    nz(u.getUsername()),
                    nz(u.getDisplayName()),
                    nz(u.getEmail()),
                    nz(u.getRole() != null ? u.getRole().replace("ROLE_", "") : ""),
                    u.isActive() ? "Active" : "Inactive",
                    nz(u.getApprovalStatus()),
                    nz(u.getSsoProvider()),
                    u.isMustChangePassword() ? "Yes" : "No",
                    u.getLockedUntil() != null ? u.getLockedUntil().toString() : "",
                    u.getAccountExpiresAt() != null ? u.getAccountExpiresAt().toString() : "",
                    tenants,
                    u.getCreatedAt() != null ? u.getCreatedAt().toString() : ""
            };
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(csv(row[i]));
            }
            sb.append("\r\n");
        }

        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "users-" + java.time.LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(bytes);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** RFC-4180 CSV escaping: wrap in quotes and double internal quotes when the
        value contains a comma, quote, or newline. */
    private static String csv(String v) {
        if (v == null) return "";
        boolean needsQuote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        String out = v.replace("\"", "\"\"");
        return needsQuote ? "\"" + out + "\"" : out;
    }
}
