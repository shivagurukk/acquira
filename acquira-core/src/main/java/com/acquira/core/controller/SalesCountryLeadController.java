package com.acquira.core.controller;

import com.acquira.common.model.SalesCountryLead;
import com.acquira.core.service.SalesCountryLeadService;
import com.acquira.core.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Country Lead tier (above Team Lead). Mirrors {@link SalesTeamController}.
 * Hierarchy: Country Lead -> Team Lead -> Sales Agent.
 */
@RestController
@RequestMapping("/api/sales-country-lead")
@RequiredArgsConstructor
public class SalesCountryLeadController {

    private final SalesCountryLeadService salesCountryLeadService;
    private final TenantService tenantService;

    private Long getTenantId() {
        return tenantService.getCurrentTenantId();
    }

    @GetMapping("/country-leads")
    public ResponseEntity<List<SalesCountryLead>> getCountryLeads() {
        return ResponseEntity.ok(salesCountryLeadService.getCountryLeads(getTenantId()));
    }

    @PostMapping("/country-leads")
    public ResponseEntity<SalesCountryLead> createCountryLead(@RequestBody Map<String, Object> payload) {
        String name = (String) payload.get("name");
        String email = (String) payload.get("email");
        String countryCode = (String) payload.get("countryCode");
        boolean isDefault = Boolean.TRUE.equals(payload.get("isDefault"));
        return ResponseEntity.ok(
                salesCountryLeadService.createCountryLead(getTenantId(), name, email, countryCode, isDefault));
    }

    @PutMapping("/country-leads/{id}")
    public ResponseEntity<SalesCountryLead> updateCountryLead(@PathVariable Long id,
            @RequestBody Map<String, Object> payload) {
        String name = (String) payload.get("name");
        String email = (String) payload.get("email");
        String countryCode = (String) payload.get("countryCode");
        boolean isDefault = Boolean.TRUE.equals(payload.get("isDefault"));
        return ResponseEntity.ok(
                salesCountryLeadService.updateCountryLead(getTenantId(), id, name, email, countryCode, isDefault));
    }

    @DeleteMapping("/country-leads/{id}")
    public ResponseEntity<Void> deleteCountryLead(@PathVariable Long id) {
        salesCountryLeadService.deleteCountryLead(getTenantId(), id);
        return ResponseEntity.ok().build();
    }

    /** Team leads with their current country-lead mapping + MAPPED/UNMAPPED status. */
    @GetMapping("/team-leads")
    public ResponseEntity<List<Map<String, Object>>> getTeamLeads() {
        return ResponseEntity.ok(salesCountryLeadService.getTeamLeadsWithStatus(getTenantId()));
    }

    @PostMapping("/assign")
    public ResponseEntity<?> assignTeamLead(@RequestBody Map<String, Object> payload) {
        Object teamLeadIdRaw = payload.get("teamLeadId");
        Object countryLeadIdRaw = payload.get("countryLeadId");
        // Mirror SalesTeamController: validate numeric ids up front so a null/blank
        // selection returns a clean 400 instead of an NPE -> 500.
        if (!(teamLeadIdRaw instanceof Number)) {
            return ResponseEntity.badRequest().body(Map.of("error", "teamLeadId is required and must be numeric"));
        }
        if (!(countryLeadIdRaw instanceof Number)) {
            return ResponseEntity.badRequest().body(Map.of("error", "countryLeadId is required and must be numeric"));
        }
        try {
            salesCountryLeadService.assignTeamLeadToCountry(getTenantId(),
                    ((Number) teamLeadIdRaw).longValue(), ((Number) countryLeadIdRaw).longValue());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/auto-assign")
    public ResponseEntity<Void> autoAssign() {
        salesCountryLeadService.autoAssignUnmapped(getTenantId());
        return ResponseEntity.ok().build();
    }
}
