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
import java.util.stream.Collectors;

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
     * Used by the UserManagement frontend.
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

            // Tenant assignments
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

    /**
     * Get all tenant assignments for a user.
     */
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

    /**
     * Add a tenant assignment to a user. Supports multiple tenants per user.
     */
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

        // Check for duplicate assignment
        Optional<UserTenantAccess> existing = accessRepository.findByUserAndTenant_TenantId(user, tenantId);
        if (existing.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User already has access to this tenant"));
        }

        // If this is marked as default, unset other defaults
        if (Boolean.TRUE.equals(isDefault)) {
            List<UserTenantAccess> allAccess = accessRepository.findByUser(user);
            for (UserTenantAccess a : allAccess) {
                if (a.getIsDefaultTenant()) {
                    a.setIsDefaultTenant(false);
                    accessRepository.save(a);
                }
            }
        }

        // If user has no other tenant access, make this the default
        List<UserTenantAccess> existingAccesses = accessRepository.findByUser(user);
        if (existingAccesses.isEmpty()) {
            isDefault = true;
        }

        UserTenantAccess access = new UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);
        access.setIsDefaultTenant(isDefault);

        // Derive roleInTenant from group name
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

    /**
     * Remove a tenant assignment from a user.
     */
    @DeleteMapping("/{userId}/tenant-access/{accessId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<?> removeTenantAccess(@PathVariable Long userId, @PathVariable Integer accessId) {
        UserTenantAccess access = accessRepository.findById(accessId)
                .orElseThrow(() -> new RuntimeException("Access record not found"));

        // Safety check: ensure this access belongs to the specified user
        if (!access.getUser().getId().equals(userId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Access record does not belong to this user"));
        }

        boolean wasDefault = access.getIsDefaultTenant();
        accessRepository.delete(access);

        // If the deleted record was default, promote another one
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
    // Password Reset
    // ==========================================

    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> resetPassword(@PathVariable Long userId, @RequestBody Map<String, String> payload) {
        String newPassword = payload.get("newPassword");
        if (newPassword == null || newPassword.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "New password is required"));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true); // Force password change on next login
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
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
}
