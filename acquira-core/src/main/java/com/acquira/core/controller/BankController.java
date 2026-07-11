package com.acquira.core.controller;

import com.acquira.common.model.Tenant;
import com.acquira.common.repository.TenantRepository;
import com.acquira.core.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/banks")
public class BankController {

    private final TenantRepository tenantRepository;
    private final TenantService tenantService;
    private final com.acquira.core.service.TenantProvisioningService provisioningService;

    public BankController(TenantRepository tenantRepository, TenantService tenantService,
            com.acquira.core.service.TenantProvisioningService provisioningService) {
        this.tenantRepository = tenantRepository;
        this.tenantService = tenantService;
        this.provisioningService = provisioningService;
    }

    @GetMapping
    public ResponseEntity<List<Tenant>> getAllBanks() {
        // Tenant-isolation fix: this endpoint feeds the tenant dropdowns on the
        // User Management / approve-request / tenant-access screens. Previously
        // it returned ALL tenants to everyone, so a bank admin saw (and could
        // pick) tenants they don't administer — the server then rejected the
        // assignment, producing a confusing silent failure. Now a super-admin
        // still gets every tenant; a non-super-admin only gets the tenants they
        // actually have access to, so the dropdown matches what the server will
        // accept.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isSuperAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));

        if (isSuperAdmin) {
            return ResponseEntity.ok(tenantRepository.findAll());
        }

        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(tenantService.getAllowedTenants(auth.getName()));
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Tenant> createBank(@RequestBody Tenant tenant) {
        // Creating a tenant is a platform-level operation — restrict to SUPER_ADMIN.
        // Previously this had no guard, so any authenticated user could POST a new tenant.
        // Validate or set defaults
        if (tenant.getBankShortCode() == null) {
            tenant.setBankShortCode(tenant.getBankName().toUpperCase().replaceAll(" ", "").substring(0,
                    Math.min(5, tenant.getBankName().length())));
        }
        // Set Institution ID if missing (deprecated but schema requires it)
        if (tenant.getInstitutionId() == null) {
            tenant.setInstitutionId("BANK-" + System.currentTimeMillis());
        }
        Tenant saved = tenantRepository.save(tenant);
        // Auto-provision: run the super-admin-managed setup scripts for the new
        // tenant (settings defaults, default sales leads, email templates, ...).
        // Failures never abort tenant creation — the provisioning log is the
        // record; scripts can be re-run from Admin > Tenant Provisioning.
        try {
            org.springframework.security.core.Authentication a =
                    SecurityContextHolder.getContext().getAuthentication();
            provisioningService.provision(saved.getTenantId(), a != null ? a.getName() : "system");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BankController.class)
                    .error("Tenant provisioning failed for tenant {}: {}", saved.getTenantId(), e.getMessage());
        }
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Tenant> updateBank(@PathVariable Long id, @RequestBody Tenant details) {
        Tenant tenant = tenantRepository.findById(id).orElseThrow(() -> new RuntimeException("Tenant not found"));
        tenant.setBankName(details.getBankName());
        tenant.setBankShortCode(details.getBankShortCode());
        tenant.setCountry(details.getCountry());
        tenant.setCurrencyName(details.getCurrencyName());
        tenant.setCurrencySymbol(details.getCurrencySymbol());
        tenant.setBaseCurrency(details.getBaseCurrency());
        return ResponseEntity.ok(tenantRepository.save(tenant));
    }
}
