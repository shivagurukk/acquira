package com.acquira.controller;

import com.acquira.model.User;
import com.acquira.model.Tenant;
import com.acquira.model.UserTenantAccess;
import com.acquira.model.SysUserGroup;
import com.acquira.repository.UserRepository;
import com.acquira.repository.TenantRepository;
import com.acquira.repository.UserTenantAccessRepository;
import com.acquira.repository.SysUserGroupRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final com.acquira.service.TenantService tenantService;
    private final UserTenantAccessRepository accessRepository;
    private final SysUserGroupRepository groupRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository,
            com.acquira.service.TenantService tenantService,
            UserTenantAccessRepository accessRepository,
            SysUserGroupRepository groupRepository,
            TenantRepository tenantRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.accessRepository = accessRepository;
        this.groupRepository = groupRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ==========================================
    // User CRUD
    // ==========================================

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> createUser(@RequestBody User user) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username '" + user.getUsername() + "' already exists"));
        }
        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        User saved = userRepository.save(user);
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userRepository.findAll());
    }

    /**
     * Enriched user list — each user includes their tenant assignments, group, and role info.
     */
    @GetMapping("/enriched")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getEnrichedUsers() {
        List<User> users = userRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (User user : users) {
            Map<String, Object> userMap = new LinkedHashMap<>();
            userMap.put("id", user.getId());
            userMap.put("username", user.getUsername());
            userMap.put("email", user.getEmail());
            userMap.put("displayName", user.getDisplayName());
            userMap.put("role", user.getRole());
            userMap.put("active", user.isActive());
            userMap.put("ssoProvider", user.getSsoProvider());
            userMap.put("mustChangePassword", user.getMustChangePassword());
            userMap.put("lockedUntil", user.getLockedUntil());
            userMap.put("approvalStatus", user.getApprovalStatus());

            List<UserTenantAccess> accesses = accessRepository.findByUser(user);
            List<Map<String, Object>> tenantList = new ArrayList<>();
            for (UserTenantAccess access : accesses) {
                Map<String, Object> tenantInfo = new HashMap<>();
                tenantInfo.put("accessId", access.getAccessId());
                tenantInfo.put("tenantId", access.getTenant().getTenantId());
                tenantInfo.put("tenantName", access.getTenant().getBankName());
                tenantInfo.put("groupId", access.getSysUserGroup() != null ? access.getSysUserGroup().getGroupId() : null);
                tenantInfo.put("groupName", access.getSysUserGroup() != null ? access.getSysUserGroup().getGroupName() : null);
                tenantInfo.put("roleInTenant", access.getRoleInTenant());
                tenantInfo.put("isDefault", access.getIsDefaultTenant());
                tenantList.add(tenantInfo);
            }
            userMap.put("tenants", tenantList);
            result.add(userMap);
        }

        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User userDetails) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (userDetails.getEmail() != null) user.setEmail(userDetails.getEmail());
        if (userDetails.getDisplayName() != null) user.setDisplayName(userDetails.getDisplayName());
        user.setActive(userDetails.isActive());

        if (userDetails.getPassword() != null && !userDetails.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(userDetails.getPassword()));
        }

        return ResponseEntity.ok(userRepository.save(user));
    }

    // ==========================================
    // Tenant Access Management (Multi-Tenant)
    // ==========================================

    @GetMapping("/{userId}/tenant-access")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getUserTenantAccess(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<UserTenantAccess> accesses = accessRepository.findByUser(user);
        List<Map<String, Object>> result = new ArrayList<>();

        for (UserTenantAccess access : accesses) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("accessId", access.getAccessId());
            map.put("tenantId", access.getTenant().getTenantId());
            map.put("tenantName", access.getTenant().getBankName());
            map.put("groupId", access.getSysUserGroup() != null ? access.getSysUserGroup().getGroupId() : null);
            map.put("groupName", access.getSysUserGroup() != null ? access.getSysUserGroup().getGroupName() : null);
            map.put("roleInTenant", access.getRoleInTenant());
            map.put("isDefault", access.getIsDefaultTenant());
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/{userId}/tenant-access")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<?> addTenantAccess(@PathVariable Long userId, @RequestBody Map<String, Object> payload) {
        Long tenantId = payload.get("tenantId") != null ? Long.valueOf(payload.get("tenantId").toString()) : null;
        Long groupId = payload.get("groupId") != null ? Long.valueOf(payload.get("groupId").toString()) : null;
        Boolean isDefault = payload.get("isDefault") != null ? Boolean.valueOf(payload.get("isDefault").toString()) : false;

        if (tenantId == null || groupId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId and groupId are required"));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));
        SysUserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        Optional<UserTenantAccess> existing = accessRepository.findByUserAndTenant_TenantId(user, tenantId);
        if (existing.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User already has access to this tenant"));
        }

        if (Boolean.TRUE.equals(isDefault)) {
            List<UserTenantAccess> allAccess = accessRepository.findByUser(user);
            for (UserTenantAccess a : allAccess) {
                if (a.getIsDefaultTenant()) {
                    a.setIsDefaultTenant(false);
                    accessRepository.save(a);
                }
            }
        }

        List<UserTenantAccess> existingAccesses = accessRepository.findByUser(user);
        if (existingAccesses.isEmpty()) {
            isDefault = true;
        }

        UserTenantAccess access = new UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);
        access.setIsDefaultTenant(isDefault);

        String groupName = group.getGroupName();
        if ("Super Admin".equalsIgnoreCase(groupName)) {
            access.setRoleInTenant("ROLE_SUPER_ADMIN");
        } else if ("Bank Admin".equalsIgnoreCase(groupName)) {
            access.setRoleInTenant("ROLE_ADMIN");
        } else if ("Viewer".equalsIgnoreCase(groupName)) {
            access.setRoleInTenant("ROLE_VIEWER");
        } else {
            access.setRoleInTenant("ROLE_USER");
        }

        accessRepository.save(access);
        return ResponseEntity.ok(Map.of("message", "Tenant access added successfully"));
    }

    @DeleteMapping("/{userId}/tenant-access/{accessId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<?> removeTenantAccess(@PathVariable Long userId, @PathVariable Integer accessId) {
        UserTenantAccess access = accessRepository.findById(accessId)
                .orElseThrow(() -> new RuntimeException("Access record not found"));

        if (!access.getUser().getId().equals(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Access record does not belong to this user"));
        }

        boolean wasDefault = access.getIsDefaultTenant();
        accessRepository.delete(access);

        if (wasDefault) {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                List<UserTenantAccess> remaining = accessRepository.findByUser(user);
                if (!remaining.isEmpty()) {
                    remaining.get(0).setIsDefaultTenant(true);
                    accessRepository.save(remaining.get(0));
                }
            }
        }

        return ResponseEntity.ok(Map.of("message", "Tenant access removed"));
    }

    // ==========================================
    // Password Management
    // ==========================================

    /**
     * Self-service: Change own password.
     * Uses 400 (not 401) for wrong current password to avoid axios logout interceptor.
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> payload) {
        String currentPassword = payload.get("currentPassword");
        String newPassword = payload.get("newPassword");

        if (currentPassword == null || currentPassword.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current and new password are required"));
        }

        // Get authenticated user
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Verify current password — getPassword() returns the hash (column: password_hash)
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect"));
        }

        // Validate new password strength
        List<String> violations = validatePasswordStrength(newPassword);
        if (!violations.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Password does not meet requirements",
                    "violations", violations
            ));
        }

        // Check new password != current password
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "New password must be different from current password"));
        }

        // Save new password
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    /**
     * Admin: Force reset a user's password. Sets mustChangePassword flag.
     */
    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> resetPassword(@PathVariable Long userId, @RequestBody Map<String, String> payload) {
        String newPassword = payload.get("newPassword");
        if (newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "New password is required"));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password reset successfully. User must change on next login."));
    }

    // ==========================================
    // Account Unlock
    // ==========================================

    @PostMapping("/{userId}/unlock")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> unlockAccount(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Account unlocked"));
    }

    // ==========================================
    // Legacy: Single-tenant assignment
    // ==========================================

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

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));
        SysUserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        UserTenantAccess access = new UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);

        accessRepository.save(access);
        return ResponseEntity.ok(Map.of("message", "Tenant and Group assigned successfully"));
    }

    @GetMapping("/{username}/banks")
    public ResponseEntity<List<Tenant>> getUserTenants(@PathVariable String username) {
        return ResponseEntity.ok(tenantService.getAllowedTenantsForUser(username));
    }

    // ==========================================
    // Password Policy (Phase 1: Hardcoded rules)
    // TODO: Phase 3 — read from password_policy table
    // ==========================================

    /**
     * Return the current password policy for the frontend to validate against.
     * Phase 1: Returns hardcoded rules.
     * Phase 3: Will read from password_policy table based on tenant.
     */
    @GetMapping("/password-policy")
    public ResponseEntity<Map<String, Object>> getPasswordPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("minLength", 8);
        policy.put("maxLength", 128);
        policy.put("requireUppercase", true);
        policy.put("requireLowercase", true);
        policy.put("requireDigit", true);
        policy.put("requireSpecial", true);
        policy.put("specialChars", "!@#$%^&*()_+-=[]{}|;':.,<>?/\\");
        return ResponseEntity.ok(policy);
    }

    private List<String> validatePasswordStrength(String password) {
        List<String> violations = new ArrayList<>();
        if (password.length() < 8)         violations.add("At least 8 characters required");
        if (password.length() > 128)       violations.add("Maximum 128 characters");
        if (!password.matches(".*[A-Z].*")) violations.add("At least one uppercase letter required");
        if (!password.matches(".*[a-z].*")) violations.add("At least one lowercase letter required");
        if (!password.matches(".*[0-9].*")) violations.add("At least one digit required");
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':.,<>?/|\\\\].*"))
            violations.add("At least one special character required");
        return violations;
    }
}
