package com.acquira.controller;

import com.acquira.dto.VolumeRevenueFilterDTO;
import com.acquira.repository.VolumeRevenueRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/business")
public class BusinessAnalyticsController {

    @Autowired
    private VolumeRevenueRepository volumeRevenueRepository;

    @Autowired
    private com.acquira.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.service.TenantService tenantService;

    private void resolveFilters(VolumeRevenueFilterDTO filters) {
        if (filters.getTeamLeaderList() != null && !filters.getTeamLeaderList().isEmpty()) {
            Long tenantId = tenantService.getCurrentTenantId();
            if (tenantId != null) {
                List<String> salesUserIds = salesTeamService.getSalesUserIdsByTeamLeadNames(tenantId,
                        filters.getTeamLeaderList());
                if (salesUserIds.isEmpty()) {
                    // Filter selected but no users found -> force no results
                    filters.setTeamLeaderList(java.util.Collections.singletonList("__NO_MATCH__"));
                } else {
                    filters.setTeamLeaderList(salesUserIds);
                }
            }
        }
    }

    @PostMapping("/volume-revenue-summary")
    public List<Map<String, Object>> getVolumeRevenueSummary(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        return volumeRevenueRepository.getSummary(filters);
    }

    @PostMapping("/merchant-financial-summary")
    public List<Map<String, Object>> getMerchantFinancialSummary(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        return volumeRevenueRepository.getMerchantFinancialSummary(filters);
    }

    @PostMapping("/performance-dashboard")
    public List<Map<String, Object>> getPerformanceDashboard(
            @RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam String groupBy,
            @RequestParam(required = false) String parentValue,
            @RequestParam(required = false) String grandParentValue) {
        resolveFilters(filters);
        return volumeRevenueRepository.getPerformanceDashboardData(filters, groupBy, parentValue, grandParentValue);
    }

    @PostMapping("/debit-prepaid-metrics")
    public List<Map<String, Object>> getDebitPrepaidMetrics(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        return volumeRevenueRepository.getDebitPrepaidMetrics(filters);
    }

    @PostMapping("/attrition-report")
    public List<Map<String, Object>> getAttritionReport(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        return volumeRevenueRepository.getAttritionReport(filters);
    }

    @PostMapping("/executive-metrics")
    public Map<String, Object> getExecutiveMetrics(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        return volumeRevenueRepository.getExecutiveMetrics(filters);
    }

    @PostMapping("/merchant-analytics")
    public Map<String, Object> getMerchantAnalyticsReport(
            @RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        resolveFilters(filters);
        return volumeRevenueRepository.getMerchantAnalyticsReport(filters, page, size);
    }

    @Autowired
    private com.acquira.service.MerchantDashboardService merchantDashboardService;

    // Placeholder for filter options (dropdowns)
    @GetMapping("/filter-options")
    public Map<String, List<String>> getFilterOptions() {
        return volumeRevenueRepository.getFilterOptions();
    }

    // @GetMapping("/daily-merchant-dashboard")
    // public List<com.acquira.dto.DailyMerchantDashboardDTO>
    // getDailyMerchantDashboard(
    // @RequestParam int month,
    // @RequestParam int year,
    // @RequestAttribute(value = "tenantId", required = false) Integer tenantId //
    // Assuming injected by
    // // Aspect/Interceptor
    // ) {
    // // Fallback for dev if tenantId missing
    // if (tenantId == null)
    // tenantId = 1;
    // return merchantDashboardService.getDailyDashboard(tenantId, month, year);
    // }
}
