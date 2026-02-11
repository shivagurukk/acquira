package com.acquira.controller;

import com.acquira.model.Tenant;
import com.acquira.model.User;
import com.acquira.model.UserTenantAccess;
// import com.acquira.model.Role; // Role is likely not a separate entity in this simplified version or needs import
import com.acquira.model.RefCountry;
import com.acquira.repository.TenantRepository;
import com.acquira.repository.UserRepository;
import com.acquira.repository.UserTenantAccessRepository;
import com.acquira.repository.RoleRepository;
import com.acquira.repository.RefCountryRepository;
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
    private final com.acquira.service.AuditService auditService;
    private final com.acquira.repository.TenantSettingRepository tenantSettingRepository;
    private final com.acquira.repository.DashboardConfigRepository dashboardConfigRepository;
    private final RefCountryRepository refCountryRepository;

    public AdminController(TenantRepository tenantRepository, UserRepository userRepository,
            UserTenantAccessRepository userTenantAccessRepository, RoleRepository roleRepository,
            PasswordEncoder passwordEncoder, com.acquira.service.AuditService auditService,
            com.acquira.repository.TenantSettingRepository tenantSettingRepository,
            com.acquira.repository.DashboardConfigRepository dashboardConfigRepository,
            RefCountryRepository refCountryRepository) { // Added repo
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.userTenantAccessRepository = userTenantAccessRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.tenantSettingRepository = tenantSettingRepository;
        this.dashboardConfigRepository = dashboardConfigRepository;
        this.refCountryRepository = refCountryRepository;
    }

    @GetMapping("/countries")
    public ResponseEntity<List<RefCountry>> getAllCountries() {
        return ResponseEntity.ok(refCountryRepository.findAllByOrderByCountryNameAsc());
    }

    @PostMapping("/tenants")
    public ResponseEntity<Tenant> createTenant(@RequestBody Tenant tenant) {
        Tenant saved = tenantRepository.save(tenant);
        auditService.log("CREATE_TENANT",
                "Created tenant: " + saved.getBankName() + " (" + saved.getInstitutionId() + ")");
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<User> createUser(@RequestBody User user,
            @RequestParam(required = false) Long tenantId) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        // Default to ROLE_USER only if role is missing
        if (user.getRole() == null || user.getRole().isEmpty()) {
            user.setRole("ROLE_BANK_USER"); // Default for Admin created users? Or just ROLE_USER
        }

        User savedUser = userRepository.save(user);
        auditService.log("CREATE_USER", "Created user: " + savedUser.getUsername());

        if (tenantId != null) {
            Tenant tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new RuntimeException("Tenant not found"));
            UserTenantAccess access = new UserTenantAccess();
            access.setUser(savedUser);
            access.setTenant(tenant);
            userTenantAccessRepository.save(access);
            auditService.log("ASSIGN_TENANT",
                    "Assigned user " + savedUser.getUsername() + " to tenant " + tenant.getBankName());
        }

        return ResponseEntity.ok(savedUser);
    }

    // ==========================================
    // Phase 6: Settings & Config
    // ==========================================

    @GetMapping("/tenants/{tenantId}/settings")
    public ResponseEntity<java.util.List<com.acquira.model.TenantSetting>> getTenantSettings(
            @PathVariable Long tenantId) {
        return ResponseEntity.ok(tenantSettingRepository.findByTenant_TenantId(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/settings")
    public ResponseEntity<com.acquira.model.TenantSetting> saveTenantSetting(@PathVariable Long tenantId,
            @RequestBody com.acquira.model.TenantSetting setting) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        setting.setTenant(tenant);
        return ResponseEntity.ok(tenantSettingRepository.save(setting));
    }

    @GetMapping("/tenants/{tenantId}/dashboard-config")
    public ResponseEntity<java.util.List<com.acquira.model.DashboardConfig>> getDashboardConfig(
            @PathVariable Long tenantId) {
        return ResponseEntity.ok(dashboardConfigRepository.findByTenant_TenantIdOrderByDisplayOrderAsc(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/dashboard-config")
    public ResponseEntity<com.acquira.model.DashboardConfig> saveDashboardConfig(@PathVariable Long tenantId,
            @RequestBody com.acquira.model.DashboardConfig config) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        config.setTenant(tenant);
        return ResponseEntity.ok(dashboardConfigRepository.save(config));
    }
}
