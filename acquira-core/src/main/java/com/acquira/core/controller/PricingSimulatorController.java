package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.core.service.PricingSimulatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Pricing Simulator v2 (/business/pricing-simulator) — segment margin matrix
 * (card_scheme × card_type × domestic/international) with the real realized
 * fee stack per segment, plus the per-segment merchant repricing worklist.
 *
 * Gated twice:
 *  - menu grant (same sys_group_menu row that shows the sidebar entry), and
 *  - the per-tenant {@code pricing.simulator_enabled} tenant_setting flag —
 *    an admin can switch the calculation off for a tenant entirely
 *    (Settings → Regional & Data). Disabled ⇒ data endpoints return 403 and
 *    /config reports enabled:false so the page can render a notice instead.
 */
@RestController
@RequestMapping("/api/business/pricing-simulator")
@PreAuthorize("@menuAccess.canAccess('/business/pricing-simulator')")
public class PricingSimulatorController {

    @Autowired
    private PricingSimulatorService pricingSimulatorService;

    @Autowired
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

    // Same resolveFilters convention as the other business dashboards —
    // team-leader names resolve to sales_user_ids before hitting SQL.
    private void resolveFilters(VolumeRevenueFilterDTO filters) {
        if (filters.getTeamLeaderList() != null && !filters.getTeamLeaderList().isEmpty()) {
            Long tenantId = tenantService.getCurrentTenantId();
            if (tenantId != null) {
                List<String> salesUserIds = salesTeamService.getSalesUserIdsByTeamLeadNames(tenantId,
                        filters.getTeamLeaderList());
                if (salesUserIds.isEmpty()) {
                    filters.setTeamLeaderList(java.util.Collections.singletonList("__NO_MATCH__"));
                } else {
                    filters.setTeamLeaderList(salesUserIds);
                }
            }
        }
    }

    private Long requireTenant() {
        Long tenantId = tenantService.getCurrentTenantId();
        if (tenantId == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tenant context not resolved");
        return tenantId;
    }

    /** Feature flag + data bounds in one call — the page's first request. */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Long tenantId = requireTenant();
        boolean enabled = pricingSimulatorService.isEnabled(tenantId);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("enabled", enabled);
        if (enabled) out.put("bounds", pricingSimulatorService.getBounds(tenantId));
        return out;
    }

    @PostMapping("/segment-matrix")
    public ResponseEntity<?> getSegmentMatrix(@RequestBody VolumeRevenueFilterDTO filters) {
        Long tenantId = requireTenant();
        if (!pricingSimulatorService.isEnabled(tenantId)) return disabled();
        resolveFilters(filters);
        return ResponseEntity.ok(pricingSimulatorService.segmentMatrix(filters, tenantId));
    }

    @PostMapping("/segment-merchants")
    public ResponseEntity<?> getSegmentMerchants(@RequestBody VolumeRevenueFilterDTO filters,
                                                 @RequestParam String scheme,
                                                 @RequestParam String cardType,
                                                 @RequestParam String destination,
                                                 @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = requireTenant();
        if (!pricingSimulatorService.isEnabled(tenantId)) return disabled();
        resolveFilters(filters);
        return ResponseEntity.ok(pricingSimulatorService.segmentMerchants(
                filters, tenantId, scheme, cardType, destination,
                Math.min(Math.max(limit, 1), 200)));
    }

    /** MID-wise view: one merchant's full segment breakdown for repricing. */
    @PostMapping("/merchant-matrix")
    public ResponseEntity<?> getMerchantMatrix(@RequestBody VolumeRevenueFilterDTO filters,
                                               @RequestParam String mid) {
        Long tenantId = requireTenant();
        if (!pricingSimulatorService.isEnabled(tenantId)) return disabled();
        return ResponseEntity.ok(pricingSimulatorService.merchantMatrix(filters, tenantId, mid));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("enabled", false,
                        "error", "Pricing simulator calculations are disabled for this tenant"));
    }
}
