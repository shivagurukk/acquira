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

    public BankController(TenantRepository tenantRepository, TenantService tenantService) {
        this.tenantRepository = tenantRepository;
        this.tenantService = tenantService;
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
        return ResponseEntity.ok(tenantRepository.save(tenant));
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
