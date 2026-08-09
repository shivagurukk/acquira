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
        tenant.setInputFormat(normalizeInputFormat(tenant.getInputFormat()));
        tenant.setCardTypeSource(normalizeCardTypeSource(tenant.getCardTypeSource()));
        tenant.setHomeCountryCode(normalizeCountryCode(tenant.getHomeCountryCode()));
        warnIfCountryCurrencyMismatch(tenant);
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
        tenant.setInputFormat(normalizeInputFormat(details.getInputFormat()));
        tenant.setCardTypeSource(normalizeCardTypeSource(details.getCardTypeSource()));
        // Only overwrite when the payload carries a value — an older UI build
        // that doesn't send homeCountryCode must not reset a tenant to 'AE'.
        if (details.getHomeCountryCode() != null && !details.getHomeCountryCode().isBlank()) {
            tenant.setHomeCountryCode(normalizeCountryCode(details.getHomeCountryCode()));
        }
        warnIfCountryCurrencyMismatch(tenant);
        return ResponseEntity.ok(tenantRepository.save(tenant));
    }

    /**
     * home_country_code selects the tenant's RATE CARD (interchange + scheme
     * fees) — it must be a clean ISO alpha-2 code. FK to ref_country rejects
     * unknown codes at save. Blank/null falls back to 'AE' (legacy default).
     */
    private static String normalizeCountryCode(String raw) {
        if (raw == null || raw.isBlank()) return "AE";
        return raw.trim().toUpperCase();
    }

    /**
     * The cheap alarm for the "Bahrain tenant silently priced on the UAE card"
     * class of bug: a tenant whose home country is AE but whose base currency
     * is not AED is almost certainly misconfigured. Warn loudly; don't block —
     * super-admin may have a legitimate transitional state.
     */
    private static void warnIfCountryCurrencyMismatch(Tenant t) {
        if ("AE".equals(t.getHomeCountryCode())
                && t.getBaseCurrency() != null && !t.getBaseCurrency().isBlank()
                && !"AED".equalsIgnoreCase(t.getBaseCurrency().trim())) {
            org.slf4j.LoggerFactory.getLogger(BankController.class).warn(
                "Tenant '{}' has home_country_code=AE but base_currency={} — rate cards "
                + "will resolve to the UAE card. If this is a new-country tenant, select "
                + "the correct Jurisdiction in Tenant Management.",
                t.getBankName(), t.getBaseCurrency());
        }
    }

    /**
     * Amount format of the tenant's feed: CMM (minor units, divide at ingest —
     * legacy default) or AMS (final decimals, no division). Anything else —
     * including null from an older UI payload — falls back to CMM so existing
     * tenants keep their current behaviour.
     */
    private static String normalizeInputFormat(String raw) {
        return "AMS".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? "AMS" : "CMM";
    }

    /**
     * Card product/type source: FILE (transaction-file columns, default) or
     * BIN (ref_bin 8-digit mapping). Config only for now — nothing in
     * ingestion reads it yet.
     */
    private static String normalizeCardTypeSource(String raw) {
        return "BIN".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? "BIN" : "FILE";
    }
}
