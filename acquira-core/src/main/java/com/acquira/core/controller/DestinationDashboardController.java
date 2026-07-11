package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.DestinationDashboardRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
public class DestinationDashboardController {

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
        Long tenantId = tenantService.getCurrentTenantId();
        return destinationDashboardRepository.getTrend(filters, tenantId);
    }

    @PostMapping("/breakdown/{dimension}")
    public List<Map<String, Object>> getBreakdown(@RequestBody VolumeRevenueFilterDTO filters,
                                                   @PathVariable String dimension) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return destinationDashboardRepository.getBreakdown(filters, dimension, tenantId);
    }

    @PostMapping("/top-merchants")
    public List<Map<String, Object>> getTopMerchants(@RequestBody VolumeRevenueFilterDTO filters,
                                                       @RequestParam(defaultValue = "15") int limit) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return destinationDashboardRepository.getTopMerchants(filters, tenantId, limit);
    }
}
