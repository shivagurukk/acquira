package com.acquira.controller;

import com.acquira.model.Tenant;
import com.acquira.repository.TenantRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/banks")
public class BankController {

    private final TenantRepository tenantRepository;

    public BankController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping
    public ResponseEntity<List<Tenant>> getAllBanks() {
        return ResponseEntity.ok(tenantRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<Tenant> createBank(@RequestBody Tenant tenant) {
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
