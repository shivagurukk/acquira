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

    public AdminController(TenantRepository tenantRepository, UserRepository userRepository,
            UserTenantAccessRepository userTenantAccessRepository, RoleRepository roleRepository,
            PasswordEncoder passwordEncoder, com.acquira.common.service.AuditService auditService,
            com.acquira.common.repository.TenantSettingRepository tenantSettingRepository,
            com.acquira.common.repository.DashboardConfigRepository dashboardConfigRepository,
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

    private Long extractTenantId(jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("X-Tenant-Id");
        if (header != null && !header.isBlank()) {
            try { return Long.parseLong(header); } catch (Exception e) { /* fall through */ }
        }
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
        return ResponseEntity.ok(tenantSettingRepository.findByTenant_TenantId(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/settings")
    public ResponseEntity<com.acquira.common.model.TenantSetting> saveTenantSetting(@PathVariable Long tenantId,
            @RequestBody com.acquira.common.model.TenantSetting setting) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        setting.setTenant(tenant);
        return ResponseEntity.ok(tenantSettingRepository.save(setting));
    }

    @GetMapping("/tenants/{tenantId}/dashboard-config")
    public ResponseEntity<java.util.List<com.acquira.common.model.DashboardConfig>> getDashboardConfig(
            @PathVariable Long tenantId) {
        return ResponseEntity.ok(dashboardConfigRepository.findByTenant_TenantIdOrderByDisplayOrderAsc(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/dashboard-config")
    public ResponseEntity<com.acquira.common.model.DashboardConfig> saveDashboardConfig(@PathVariable Long tenantId,
            @RequestBody com.acquira.common.model.DashboardConfig config) {
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        config.setTenant(tenant);
        return ResponseEntity.ok(dashboardConfigRepository.save(config));
    }
}
