package com.acquira.controller;

import com.acquira.model.User;
import com.acquira.model.User;
import com.acquira.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "http://localhost:5173")
public class UserController {

    private final UserRepository userRepository;
    private final com.acquira.service.TenantService tenantService; // Use service
    private final com.acquira.repository.UserTenantAccessRepository accessRepository;
    private final com.acquira.repository.SysUserGroupRepository groupRepository;
    private final com.acquira.repository.TenantRepository tenantRepository;

    public UserController(UserRepository userRepository,
            com.acquira.service.TenantService tenantService,
            com.acquira.repository.UserTenantAccessRepository accessRepository,
            com.acquira.repository.SysUserGroupRepository groupRepository,
            com.acquira.repository.TenantRepository tenantRepository) {
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.accessRepository = accessRepository;
        this.groupRepository = groupRepository;
        this.tenantRepository = tenantRepository;
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody User user) {
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new RuntimeException("Username '" + user.getUsername() + "' already exists.");
        }
        if (!user.getPassword().startsWith("{")) {
            user.setPassword("{noop}" + user.getPassword());
        }
        // Group is not set here anymore
        return ResponseEntity.ok(userRepository.save(user));
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        // We might want to enrich users with their group info if needed, but for now
        // simple list
        List<User> users = userRepository.findAll();
        // Populate transient group field if needed for UI backward compatibility?
        // For now, UI lists groups. We can fetch them separately or UI handles it.
        // But UI expects user.sysUserGroup for display?
        // UI column: "Group / Role".
        // The User object no longer has sysUserGroup.
        // We can leave it null, and UI will show "No Group" or we can try to find a
        // default group.
        // Let's rely on Access Map for detail.
        return ResponseEntity.ok(users);
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Long id, @RequestBody User userDetails) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setEmail(userDetails.getEmail());
        user.setActive(userDetails.isActive());
        // Role and Group are not directly on User anymore

        if (userDetails.getPassword() != null && !userDetails.getPassword().isEmpty()) {
            if (!userDetails.getPassword().startsWith("{")) {
                user.setPassword("{noop}" + userDetails.getPassword());
            } else {
                user.setPassword(userDetails.getPassword());
            }
        }

        return ResponseEntity.ok(userRepository.save(user));
    }

    @PostMapping("/{userId}/assign")
    public ResponseEntity<?> assignTenant(@PathVariable Long userId, @RequestBody Map<String, Object> payload) {
        // Accepts { bankId: <id>, groupId: <id> }
        // Note: frontend sends 'bankId' which maps to TenantId
        Object bankIdObj = payload.get("bankId");
        Object groupIdObj = payload.get("groupId");

        Long tenantId = bankIdObj != null ? Long.valueOf(bankIdObj.toString()) : null;
        Long groupId = groupIdObj != null ? Long.valueOf(groupIdObj.toString()) : null;

        if (tenantId == null || groupId == null) {
            return ResponseEntity.badRequest().body("TenantId and GroupId are required");
        }

        User user = userRepository.findById(userId).orElseThrow();
        com.acquira.model.Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        com.acquira.model.SysUserGroup group = groupRepository.findById(groupId).orElseThrow();

        com.acquira.model.UserTenantAccess access = new com.acquira.model.UserTenantAccess();
        access.setUser(user);
        access.setTenant(tenant);
        access.setSysUserGroup(group);

        accessRepository.save(access);
        return ResponseEntity.ok(Map.of("message", "Tenant and Group assigned successfully"));
    }

    @GetMapping("/{username}/banks")
    public ResponseEntity<List<com.acquira.model.Tenant>> getUserTenants(@PathVariable String username) {
        // Use TenantService
        return ResponseEntity.ok(tenantService.getAllowedTenantsForUser(username));
    }
}
