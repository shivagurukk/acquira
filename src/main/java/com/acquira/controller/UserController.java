package com.acquira.controller;

import com.acquira.model.User;
import com.acquira.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final com.acquira.service.TenantService tenantService;
    private final com.acquira.repository.UserTenantAccessRepository accessRepository;
    private final com.acquira.repository.SysUserGroupRepository groupRepository;
    private final com.acquira.repository.TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository,
            com.acquira.service.TenantService tenantService,
            com.acquira.repository.UserTenantAccessRepository accessRepository,
            com.acquira.repository.SysUserGroupRepository groupRepository,
            com.acquira.repository.TenantRepository tenantRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.accessRepository = accessRepository;
        this.groupRepository = groupRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

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

        // SECURITY FIX: Use BCrypt instead of {noop}
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return ResponseEntity.ok(userRepository.save(user));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<User>> getAllUsers() {
        // Password is now excluded from JSON via @JsonIgnore on User.password
        return ResponseEntity.ok(userRepository.findAll());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User userDetails) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setEmail(userDetails.getEmail());
        user.setActive(userDetails.isActive());

        if (userDetails.getPassword() != null && !userDetails.getPassword().isEmpty()) {
            // SECURITY FIX: Use BCrypt instead of {noop}
            user.setPassword(passwordEncoder.encode(userDetails.getPassword()));
        }

        return ResponseEntity.ok(userRepository.save(user));
    }

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
        com.acquira.model.Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));
        com.acquira.model.SysUserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        com.acquira.model.UserTenantAccess access = new com.acquira.model.UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);

        accessRepository.save(access);
        return ResponseEntity.ok(Map.of("message", "Tenant and Group assigned successfully"));
    }

    @GetMapping("/{username}/banks")
    public ResponseEntity<List<com.acquira.model.Tenant>> getUserTenants(@PathVariable String username) {
        return ResponseEntity.ok(tenantService.getAllowedTenantsForUser(username));
    }
}
