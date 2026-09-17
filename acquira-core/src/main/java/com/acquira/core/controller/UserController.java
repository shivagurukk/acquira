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

    /** Same shape the frontend enforces: local@domain.tld, no spaces. */
    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserRepository userRepository;
    private final TenantService tenantService;
    private final com.acquira.common.repository.UserTenantAccessRepository accessRepository;
    private final com.acquira.common.repository.SysUserGroupRepository groupRepository;
    private final com.acquira.common.repository.TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordService passwordService;
    private final com.acquira.common.service.AuditService auditService;

    public UserController(UserRepository userRepository,
            TenantService tenantService,
            com.acquira.common.repository.UserTenantAccessRepository accessRepository,
            com.acquira.common.repository.SysUserGroupRepository groupRepository,
            com.acquira.common.repository.TenantRepository tenantRepository,
            PasswordEncoder passwordEncoder,
            PasswordService passwordService,
            com.acquira.common.service.AuditService auditService) {
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.accessRepository = accessRepository;
        this.groupRepository = groupRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordService = passwordService;
        this.auditService = auditService;
    }

    /** Audit without ever failing the caller's operation because logging broke. */
    private void audit(String action, String details) {
        try { auditService.log(action, details); } catch (Exception ignored) { }
    }

    /** 404 body helper — replaces the RuntimeExceptions that surfaced as 500s. */
    private static ResponseEntity<?> notFound(String what) {
        return ResponseEntity.status(404).body(Map.of("error", what + " not found"));
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

    /**
     * SECURITY: CustomUserDetailsService promotes membership of the "Super Admin"
     * group to ROLE_SUPER_ADMIN authority. Assigning that group is therefore
     * equivalent to assigning the super-admin role and must be restricted the same
     * way — only a super admin may hand it out.
     */
    private boolean mayAssignGroup(com.acquira.common.model.SysUserGroup group) {
        if (group == null || group.getGroupName() == null) return true;
        if (isSuperAdmin()) return true;
        return !"Super Admin".equalsIgnoreCase(group.getGroupName().trim());
    }

    // ===== CREATE USER (with username + email duplicate check) =====
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> createUser(@RequestBody User user) {
        // SECURITY: the request body is bound straight onto the entity, so a
        // client-supplied "id" would turn this INSERT into a merge — overwriting
        // an arbitrary existing account (including its password) while passing
        // every uniqueness check below. A create never targets an existing row.
        user.setId(null);
        // Likewise, these are server-managed and must never be settable by the
        // caller: SSO linkage would let a local account impersonate a federated
        // identity, and the lockout counters are security state.
        user.setSsoProvider(null);
        user.setSsoId(null);
        user.setApprovalStatus("APPROVED");
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLogin(null);
        user.setCreatedAt(LocalDateTime.now());

        // Validate username
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        user.setUsername(user.getUsername().trim());
        if (userRepository.existsByUsername(user.getUsername())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username '" + user.getUsername() + "' already exists"));
        }

        // Email is optional. When supplied, normalise and enforce uniqueness;
        // when blank/absent, store null so multiple email-less users can coexist
        // (an empty string would collide on the uniqueness check).
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
            String email = user.getEmail().trim();
            // Server-side format validation (mirrors the frontend check) — the UI
            // guarded this but the API did not, so malformed addresses like "bad@"
            // were persisted.
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Email '" + email + "' is not a valid email address"));
            }
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
        audit("CREATE_USER", "Created user: " + saved.getUsername());

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
    //
    // The body is read as a Map rather than bound onto a User so that "field
    // absent" and "field explicitly null" stay distinguishable. Binding to the
    // entity made that impossible and produced two silent data bugs:
    //   * `active` is a primitive boolean, so a payload that omitted it
    //     deserialised as false — every partial update silently DEACTIVATED the
    //     user (or activated them, depending on the default).
    //   * accountExpiresAt had to be applied unconditionally to remain clearable,
    //     so any caller that omitted it wiped the stored expiry date.
    // Now each field is applied only when its key is actually present.
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        // Tenant-isolation fix: a bank admin may only modify users in their tenant.
        if (!canActOnUser(id)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user"));
        }
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return notFound("User");

        // Update email (with duplicate check if changed). Blank clears the address:
        // storing "" would collide on the uniqueness check for the next email-less
        // user, so an empty value becomes null — matching createUser.
        if (body.containsKey("email")) {
            String email = str(body.get("email"));
            if (email == null || email.isEmpty()) {
                user.setEmail(null);
            } else if (!EMAIL_PATTERN.matcher(email).matches()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Email '" + email + "' is not a valid email address"));
            } else if (!email.equalsIgnoreCase(user.getEmail())) {
                // The equalsIgnoreCase guard above means we only get here when the
                // address genuinely differs from the user's own, so any hit belongs
                // to somebody else. The old check compared the RAW submitted value
                // against the stored one, so re-saving your own address with stray
                // whitespace or different casing was rejected as a duplicate.
                if (userRepository.existsByEmail(email)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Email '" + email + "' is already registered"));
                }
                user.setEmail(email);
            }
        }

        // GAP-3: Update displayName
        if (body.containsKey("displayName")) {
            String dn = str(body.get("displayName"));
            user.setDisplayName(dn == null || dn.isEmpty() ? null : dn);
        }

        // Account expiry — settable and clearable (null/blank = no expiry).
        if (body.containsKey("accountExpiresAt")) {
            user.setAccountExpiresAt(parseDateTime(body.get("accountExpiresAt")));
        }

        // GAP-4: Update role. mayAssignRole() still blocks a bank admin from
        // granting SUPER_ADMIN (which JwtRequestFilter treats as a tenant bypass).
        String role = str(body.get("role"));
        if (role != null && !role.isEmpty()) {
            if (!mayAssignRole(role)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "Only a super admin may assign the SUPER_ADMIN role"));
            }
            user.setRole(role);
        }

        if (body.containsKey("active")) {
            Boolean active = bool(body.get("active"));
            if (active == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "'active' must be true or false"));
            }
            // Locking yourself out of the platform is never the intent, and with a
            // single admin it is unrecoverable through the UI.
            if (!active && isSelf(user)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "You cannot deactivate your own account"));
            }
            user.setActive(active);
        }

        // If password is provided in update, use admin reset flow
        String newPassword = str(body.get("password"));
        if (newPassword != null && !newPassword.isEmpty()) {
            String error = passwordService.adminResetPassword(user, newPassword);
            if (error != null) {
                return ResponseEntity.badRequest().body(Map.of("error", error));
            }
            audit("RESET_PASSWORD", "Password reset for user: " + user.getUsername());
            // adminResetPassword already saves — but we still need to save email/active changes
        }

        User saved = userRepository.save(user);
        audit("UPDATE_USER", "Updated user: " + saved.getUsername());
        return ResponseEntity.ok(saved);
    }

    /** Is the given user the caller themselves? */
    private boolean isSelf(User user) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && user.getUsername() != null && user.getUsername().equals(auth.getName());
    }

    private static String str(Object v) {
        return v == null ? null : v.toString().trim();
    }

    private static Boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return null;
        String s = v.toString().trim();
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        return null;
    }

    /**
     * Accepts the wall-clock form the date picker produces ("2026-08-05T14:00")
     * as well as zoned/instant ISO strings from older clients, which are
     * converted to server-local time so the stored LocalDateTime compares
     * correctly against {@code LocalDateTime.now()}.
     */
    private static LocalDateTime parseDateTime(Object v) {
        String s = str(v);
        if (s == null || s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try { return LocalDateTime.parse(s); } catch (Exception ignored) { }
        try { return java.time.OffsetDateTime.parse(s)
                .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignored) { }
        try { return java.time.Instant.parse(s)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignored) { }
        try { return java.time.LocalDate.parse(s).atStartOfDay(); } catch (Exception ignored) { }
        return null;
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

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return notFound("User");

        String error = passwordService.adminResetPassword(user, newPassword);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        audit("RESET_PASSWORD", "Password reset for user: " + user.getUsername());

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
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return notFound("User");

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastFailedLogin(null);
        userRepository.save(user);
        audit("UNLOCK_USER", "Unlocked account: " + user.getUsername());

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

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        User user = userRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null) return notFound("User");

        String error = passwordService.changePassword(user, currentPassword, newPassword);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        audit("CHANGE_PASSWORD", "User '" + user.getUsername() + "' changed their own password");

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
        // Non-numeric ids used to escape as a NumberFormatException → 500.
        Long tenantId = parseId(payload.get("bankId"));
        Long groupId = parseId(payload.get("groupId"));

        if (tenantId == null || groupId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "TenantId and GroupId are required"));
        }

        // Tenant-isolation fix: bank admins may only assign within their own tenant
        // and only to users in their tenant.
        if (!canActOnUser(userId) || !canAssignTenant(tenantId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user or tenant"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return notFound("User");

        // Same duplicate guard as POST /tenant-access. Without it this endpoint
        // happily inserted a second access row for a tenant the user already had,
        // which then shows up twice everywhere and makes the default-tenant and
        // role-in-tenant resolution order-dependent.
        if (accessRepository.findByUserAndTenant_TenantId(user, tenantId).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User already has access to this tenant"));
        }

        com.acquira.common.model.Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return notFound("Tenant");
        com.acquira.common.model.SysUserGroup group = groupRepository.findById(groupId).orElse(null);
        if (group == null) return notFound("Group");

        com.acquira.common.model.UserTenantAccess access = new com.acquira.common.model.UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);
        // First grant becomes the default, otherwise the user logs in with no
        // active tenant resolved.
        access.setIsDefaultTenant(accessRepository.findAllByUser(user).isEmpty());

        accessRepository.save(access);
        audit("ASSIGN_TENANT", "Assigned user " + user.getUsername() + " to tenant " + tenant.getBankName());
        return ResponseEntity.ok(Map.of("message", "Tenant and Group assigned successfully"));
    }

    /** Lenient numeric id from a JSON body value (accepts numbers and strings). */
    private static Long parseId(Object v) {
        String s = str(v);
        if (s == null || s.isEmpty()) return null;
        try { return Long.valueOf(s); }
        catch (NumberFormatException e) {
            try { return (long) Double.parseDouble(s); } catch (NumberFormatException e2) { return null; }
        }
    }

    @GetMapping("/{username}/banks")
    public ResponseEntity<List<com.acquira.common.model.Tenant>> getUserTenants(@PathVariable String username) {
        // Self-or-admin guard: previously ANY authenticated user could enumerate
        // any other user's tenant/bank assignments by username.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return ResponseEntity.status(401).build();
        if (!auth.getName().equals(username)) {
            // canActOnUser() alone was NOT enough here: for a non-super-admin it
            // only asks "is the target in my active tenant?", which every ordinary
            // colleague in the same tenant satisfies — so any plain user could
            // still enumerate a co-worker's bank assignments. Reading someone
            // else's grants requires an admin role as well.
            boolean isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                            || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
            Long targetId = userRepository.findByUsername(username).map(User::getId).orElse(null);
            if (!isAdmin || targetId == null || !canActOnUser(targetId)) {
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
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return notFound("User");

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
        // A missing or non-numeric tenantId/groupId used to throw
        // NullPointerException / NumberFormatException and surface as a 500.
        Long tenantId = parseId(payload.get("tenantId"));
        Long groupId = parseId(payload.get("groupId"));
        if (tenantId == null || groupId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId and groupId are required"));
        }

        // Tenant-isolation fix: bank admins may only add access within their own
        // tenant and only to users in their tenant.
        if (!canActOnUser(userId) || !canAssignTenant(tenantId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this user or tenant"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return notFound("User");

        String roleInTenant = str(payload.get("roleInTenant"));
        boolean isDefault = Boolean.TRUE.equals(bool(payload.get("isDefault")));

        // Check for duplicate
        if (accessRepository.findByUserAndTenant_TenantId(user, tenantId).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User already has access to this tenant"));
        }

        com.acquira.common.model.Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return notFound("Tenant");
        com.acquira.common.model.SysUserGroup group = groupRepository.findById(groupId).orElse(null);
        if (group == null) return notFound("Group");

        // SECURITY: roleInTenant and the group name both flow into the granted
        // authorities (CustomUserDetailsService), so an unguarded value here lets a
        // bank admin mint ROLE_SUPER_ADMIN for themselves. Only a super admin may
        // assign a super-admin role or the "Super Admin" group.
        if (!mayAssignRole(roleInTenant) || !mayAssignGroup(group)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "You are not allowed to assign this role or group"));
        }

        List<com.acquira.common.model.UserTenantAccess> existing = accessRepository.findAllByUser(user);

        // The very first grant must be the default — otherwise login resolves no
        // active tenant and the user lands on an empty app.
        if (existing.isEmpty()) {
            isDefault = true;
        }

        // If setting as default, unset others
        if (isDefault) {
            existing.forEach(a -> {
                a.setIsDefaultTenant(false);
                accessRepository.save(a);
            });
        }

        com.acquira.common.model.UserTenantAccess access = new com.acquira.common.model.UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);
        access.setRoleInTenant(roleInTenant == null || roleInTenant.isEmpty() ? null : roleInTenant);
        access.setIsDefaultTenant(isDefault);
        accessRepository.save(access);
        audit("ASSIGN_TENANT",
                "Granted " + user.getUsername() + " access to tenant " + tenant.getBankName());

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

        com.acquira.common.model.UserTenantAccess access = accessRepository.findById(accessId).orElse(null);
        if (access == null) return notFound("Access record");

        // Tenant-isolation fix: the access row must belong to the user named in the
        // path (prevents acting on another user's access by guessing accessId), and
        // a bank admin may only touch an access row for a tenant they administer.
        if (!access.getUser().getId().equals(userId)
                || !canAssignTenant(access.getTenant().getTenantId())) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this access record"));
        }

        Long groupId = parseId(payload.get("groupId"));
        if (groupId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupId is required"));
        }
        String roleInTenant = str(payload.get("roleInTenant"));
        boolean isDefault = Boolean.TRUE.equals(bool(payload.get("isDefault")));

        com.acquira.common.model.SysUserGroup group = groupRepository.findById(groupId).orElse(null);
        if (group == null) return notFound("Group");

        // SECURITY: see addTenantAccess — block a non-super-admin from assigning a
        // super-admin role or the "Super Admin" group (privilege escalation).
        if (!mayAssignRole(roleInTenant) || !mayAssignGroup(group)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "You are not allowed to assign this role or group"));
        }

        access.setSysUserGroup(group);
        access.setRoleInTenant(roleInTenant == null || roleInTenant.isEmpty() ? null : roleInTenant);

        if (isDefault) {
            // Unset the others, but skip THIS row — the loop below re-saves every
            // access of the user, and re-fetched instances of the row we are
            // holding would fight over is_default_tenant.
            accessRepository.findAllByUser(access.getUser()).forEach(a -> {
                if (!a.getAccessId().equals(access.getAccessId())) {
                    a.setIsDefaultTenant(false);
                    accessRepository.save(a);
                }
            });
        }
        access.setIsDefaultTenant(isDefault);
        accessRepository.save(access);
        audit("UPDATE_TENANT_ACCESS",
                "Updated tenant access for " + access.getUser().getUsername()
                        + " on " + access.getTenant().getBankName());

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
        com.acquira.common.model.UserTenantAccess access = accessRepository.findById(accessId).orElse(null);
        if (access == null) return notFound("Access record");
        // The access row must belong to the path user, and a bank admin may only
        // remove an access row for a tenant they administer (prevents cross-tenant
        // deletes by guessing accessId).
        if (!access.getUser().getId().equals(userId)
                || !canAssignTenant(access.getTenant().getTenantId())) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to this access record"));
        }

        User target = access.getUser();
        boolean wasDefault = Boolean.TRUE.equals(access.getIsDefaultTenant());
        String tenantName = access.getTenant().getBankName();
        accessRepository.delete(access);

        // Removing the default grant left the user with tenants but no default:
        // JwtRequestFilter then falls back to an arbitrary "first" row and the
        // login response resolves no default tenant. Promote a remaining grant.
        if (wasDefault) {
            List<com.acquira.common.model.UserTenantAccess> remaining = accessRepository.findAllByUser(target);
            if (!remaining.isEmpty()) {
                com.acquira.common.model.UserTenantAccess promoted = remaining.get(0);
                promoted.setIsDefaultTenant(true);
                accessRepository.save(promoted);
            }
        }
        audit("REVOKE_TENANT_ACCESS",
                "Removed " + target.getUsername() + "'s access to tenant " + tenantName);
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

        // One batched query instead of one per user (the old loop issued
        // findAllByUser() per row, plus a lazy tenant/group load inside it).
        Map<Long, List<com.acquira.common.model.UserTenantAccess>> accessByUser = accessesByUser(users);

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
            List<com.acquira.common.model.UserTenantAccess> accesses =
                    accessByUser.getOrDefault(u.getId(), java.util.List.of());
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
        boolean superAdmin = isSuperAdmin();
        List<User> users = superAdmin
                ? userRepository.findAll()
                : userRepository.findAllById(userIdsInCurrentTenant());
        Map<Long, List<com.acquira.common.model.UserTenantAccess>> accessByUser = accessesByUser(users);
        // E2E USER-042: the ROWS were tenant-scoped, but the Tenants/Groups columns
        // still listed a shared user's memberships in OTHER banks — a bank admin
        // could see that a user also holds a group in a tenant they don't
        // administer. Restrict the columns to the active tenant unless super admin.
        Long activeTenantId = TenantContext.getCurrentTenant();
        if (!superAdmin && activeTenantId != null) {
            accessByUser.replaceAll((id, list) -> list.stream()
                    .filter(a -> activeTenantId.equals(a.getTenant().getTenantId()))
                    .collect(Collectors.toList()));
        }

        StringBuilder sb = new StringBuilder();
        // Excel-friendly UTF-8 BOM so accented names render correctly.
        sb.append('\uFEFF');
        String[] header = {
                "Username", "Display Name", "Email", "Role", "Active", "Approval Status",
                "SSO Provider", "Must Change Password", "Locked Until", "Account Expires At",
                "Tenants", "Groups", "Created At"
        };
        sb.append(String.join(",", header)).append("\r\n");

        for (User u : users) {
            List<com.acquira.common.model.UserTenantAccess> accesses =
                    accessByUser.getOrDefault(u.getId(), java.util.List.of());
            // One entry per tenant access, group in brackets since the group
            // grant is PER TENANT: "Bank A [Bank Admin] *; Bank B [Business User]".
            // The * still marks the default tenant.
            String tenants = accesses.stream()
                    .map(a -> a.getTenant().getBankName()
                            + (a.getSysUserGroup() != null ? " [" + a.getSysUserGroup().getGroupName() + "]" : "")
                            + (Boolean.TRUE.equals(a.getIsDefaultTenant()) ? " *" : ""))
                    .collect(Collectors.joining("; "));
            // Distinct group names across all tenant accesses, for flat filtering.
            String groups = accesses.stream()
                    .map(a -> a.getSysUserGroup() != null ? a.getSysUserGroup().getGroupName() : null)
                    .filter(g -> g != null && !g.isBlank())
                    .distinct()
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
                    groups,
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

    /**
     * Access rows for a batch of users, keyed by user id. Uses the batched
     * JOIN FETCH query so rendering N users costs one query rather than N
     * (plus N lazy tenant/group loads).
     */
    private Map<Long, List<com.acquira.common.model.UserTenantAccess>> accessesByUser(List<User> users) {
        if (users == null || users.isEmpty()) return java.util.Map.of();
        return accessRepository.findAllByUserIn(users).stream()
                .collect(Collectors.groupingBy(a -> a.getUser().getId()));
    }

    // Hard-deleting users is intentionally unsupported (audit trail + FK integrity):
    // callers deactivate instead (PUT /{id} {"active":false}). Without this handler an
    // unmatched DELETE fell through to a 500; return a clear 405 explaining the path.
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> deleteUser(@PathVariable Long userId) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error",
                    "User deletion is not supported. Deactivate the account instead (set active=false)."));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** RFC-4180 CSV escaping: wrap in quotes and double internal quotes when the
        value contains a comma, quote, or newline.
        <p>Also neutralises CSV/formula injection: a field that starts with =, +,
        -, @, tab or CR is executed as a formula when the export is opened in
        Excel/Sheets, and these fields carry user-controlled text (display name,
        email, bank name). Prefixing a single quote makes the cell inert while
        still showing the original text. */
    private static String csv(String v) {
        if (v == null) return "";
        String out = v;
        if (!out.isEmpty() && "=+-@\t\r".indexOf(out.charAt(0)) >= 0) {
            out = "'" + out;
        }
        boolean needsQuote = out.contains(",") || out.contains("\"") || out.contains("\n") || out.contains("\r");
        out = out.replace("\"", "\"\"");
        return needsQuote ? "\"" + out + "\"" : out;
    }
}
