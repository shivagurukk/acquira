package com.acquira.core.controller;

import com.acquira.common.model.SalesTeamMapping;
import com.acquira.core.service.SalesTeamService;
import com.acquira.core.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sales-team")
@RequiredArgsConstructor
public class SalesTeamController {

    private final SalesTeamService salesTeamService;
    private final TenantService tenantService;

    private Long getTenantId() {
        return tenantService.getCurrentTenantId();
    }

    @GetMapping("/team-leads")
    public ResponseEntity<List<SalesTeamMapping>> getTeamLeads() {
        return ResponseEntity.ok(salesTeamService.getTeamLeads(getTenantId()));
    }

    @PostMapping("/team-leads")
    public ResponseEntity<SalesTeamMapping> createTeamLead(@RequestBody Map<String, Object> payload) {
        String name = (String) payload.get("name");
        String email = (String) payload.get("email");
        boolean isDefault = Boolean.TRUE.equals(payload.get("isDefault"));
        return ResponseEntity.ok(salesTeamService.createTeamLead(getTenantId(), name, email, isDefault));
    }

    @PutMapping("/team-leads/{id}")
    public ResponseEntity<SalesTeamMapping> updateTeamLead(@PathVariable Long id,
            @RequestBody Map<String, Object> payload) {
        String name = (String) payload.get("name");
        String email = (String) payload.get("email");
        boolean isDefault = Boolean.TRUE.equals(payload.get("isDefault"));
        return ResponseEntity.ok(salesTeamService.updateTeamLead(id, name, email, isDefault));
    }

    @DeleteMapping("/team-leads/{id}")
    public ResponseEntity<Void> deleteTeamLead(@PathVariable Long id) {
        salesTeamService.deleteTeamLead(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/sales-users")
    public ResponseEntity<List<Map<String, Object>>> getSalesUsers() {
        return ResponseEntity.ok(salesTeamService.getSalesUsersWithStatus(getTenantId()));
    }

    @PostMapping("/assign")
    public ResponseEntity<Void> assignSalesUser(@RequestBody Map<String, Object> payload) {
        String salesUserId = (String) payload.get("salesUserId");
        Number teamLeadIdNum = (Number) payload.get("teamLeadId");
        salesTeamService.assignSalesUser(getTenantId(), salesUserId, teamLeadIdNum.longValue());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/auto-assign")
    public ResponseEntity<Void> autoAssign() {
        salesTeamService.autoAssignUnmapped(getTenantId());
        return ResponseEntity.ok().build();
    }
}
