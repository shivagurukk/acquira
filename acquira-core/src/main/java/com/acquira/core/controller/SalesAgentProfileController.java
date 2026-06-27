package com.acquira.core.controller;

import com.acquira.core.service.SalesAgentProfileService;
import com.acquira.core.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Sales Agent directory + profiles. An agent is a distinct
 * {@code dim_merchant.sales_user_id}; emails are auto-populated from merchant
 * data via the /sync endpoint.
 */
@RestController
@RequestMapping("/api/sales-agents")
@RequiredArgsConstructor
public class SalesAgentProfileController {

    private final SalesAgentProfileService salesAgentProfileService;
    private final TenantService tenantService;

    private Long getTenantId() {
        return tenantService.getCurrentTenantId();
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAgents() {
        return ResponseEntity.ok(salesAgentProfileService.getAgents(getTenantId()));
    }

    @GetMapping("/{salesUserId}")
    public ResponseEntity<?> getAgent(@PathVariable String salesUserId) {
        try {
            return ResponseEntity.ok(salesAgentProfileService.getAgent(getTenantId(), salesUserId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{salesUserId}")
    public ResponseEntity<?> updateAgent(@PathVariable String salesUserId,
            @RequestBody Map<String, Object> payload) {
        try {
            return ResponseEntity.ok(salesAgentProfileService.updateAgent(getTenantId(), salesUserId, payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Create/refresh agent profile stubs from merchant data (auto-populates email). */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync() {
        return ResponseEntity.ok(salesAgentProfileService.syncFromMerchants(getTenantId()));
    }
}
