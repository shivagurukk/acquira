package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.DestinationDashboardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Domestic vs International Destination Dashboard
 * (/business/destination-dashboard). Every endpoint returns BOTH the
 * domestic and international split in one payload — "Domestic",
 * "International", and "Compare" mode on the frontend are purely a
 * rendering choice over the same response, not separate backend calls.
 *
 * Note: destinationList in VolumeRevenueFilterDTO is intentionally ignored
 * here (resolveFilters still runs for teamLeaderList -> sales_user_id, same
 * as BusinessAnalyticsController) — destination is the split dimension
 * itself on this page, never a narrowing filter.
 */
@RestController
@RequestMapping("/api/business/destination-dashboard")
// Menu-grant gate, same as every other business screen — the sidebar entry
// and this API are driven by the same sys_group_menu grant.
@PreAuthorize("@menuAccess.canAccess('/business/destination-dashboard')")
public class DestinationDashboardController {

    /** The only group columns getBreakdown supports — anything else is a client error (400), not a 500. */
    private static final java.util.Set<String> BREAKDOWN_DIMENSIONS =
            java.util.Set.of("scheme", "cardType", "channel", "mcc");

    /**
     * Server-side date defaults: without them a missing range scans every
     * partition back to 2024. KPIs already default internally (30d); the
     * other three get an explicit window here.
     */
    private static void defaultDates(VolumeRevenueFilterDTO f, int defaultDays) {
        if (f.getEndDate() == null) f.setEndDate(java.time.LocalDate.now());
        if (f.getStartDate() == null) f.setStartDate(f.getEndDate().minusDays(defaultDays));
    }

    @Autowired
    private DestinationDashboardRepository destinationDashboardRepository;

    @Autowired
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

    // Same resolveFilters convention as BusinessAnalyticsController —
    // duplicated locally (rather than shared) to keep this feature additive
    // and isolated from the existing controller.
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

    @PostMapping("/kpis")
    public Map<String, Object> getKpis(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return destinationDashboardRepository.getKpis(filters, tenantId);
    }

    @PostMapping("/trend")
    public List<Map<String, Object>> getTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters, 365); // 12 months of monthly buckets
        Long tenantId = tenantService.getCurrentTenantId();
        return destinationDashboardRepository.getTrend(filters, tenantId);
    }

    @PostMapping("/breakdown/{dimension}")
    public org.springframework.http.ResponseEntity<?> getBreakdown(@RequestBody VolumeRevenueFilterDTO filters,
                                                   @PathVariable String dimension) {
        if (!BREAKDOWN_DIMENSIONS.contains(dimension)) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown breakdown dimension: " + dimension
                            + " (supported: " + BREAKDOWN_DIMENSIONS + ")"));
        }
        resolveFilters(filters);
        defaultDates(filters, 30);
        Long tenantId = tenantService.getCurrentTenantId();
        return org.springframework.http.ResponseEntity.ok(
                destinationDashboardRepository.getBreakdown(filters, dimension, tenantId));
    }

    @PostMapping("/top-merchants")
    public List<Map<String, Object>> getTopMerchants(@RequestBody VolumeRevenueFilterDTO filters,
                                                       @RequestParam(defaultValue = "15") int limit) {
        resolveFilters(filters);
        defaultDates(filters, 30);
        Long tenantId = tenantService.getCurrentTenantId();
        return destinationDashboardRepository.getTopMerchants(filters, tenantId, Math.min(Math.max(limit, 1), 100));
    }
}
