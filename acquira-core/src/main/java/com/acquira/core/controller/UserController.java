package com.acquira.core.controller;

import com.acquira.common.model.User;
import com.acquira.common.repository.UserRepository;
import com.acquira.core.service.PasswordService;
import com.acquira.core.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

        // Validate email
        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        if (userRepository.existsByEmail(user.getEmail().trim())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Email '" + user.getEmail() + "' is already registered"));
        }

        // Validate password
        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
        }
        String strengthError = passwordService.validatePasswordStrength(user.getPassword());
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
        return ResponseEntity.ok(userRepository.findAll());
    }

    // ===== UPDATE USER (email, active status, role) =====
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User userDetails) {
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
        return ResponseEntity.ok(tenantService.getAllowedTenantsForUser(username));
    }
}
