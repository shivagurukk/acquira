package com.acquira.controller;

import com.acquira.dto.VolumeRevenueFilterDTO;
import com.acquira.repository.VolumeRevenueRepository;
import com.acquira.service.SalesTeamService;
import com.acquira.service.TenantService;
import com.acquira.service.MerchantDashboardService;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/business")
public class BusinessAnalyticsController {

    private final VolumeRevenueRepository volumeRevenueRepository;
    private final SalesTeamService salesTeamService;
    private final TenantService tenantService;
    private final MerchantDashboardService merchantDashboardService;

    public BusinessAnalyticsController(VolumeRevenueRepository volumeRevenueRepository,
                                       SalesTeamService salesTeamService,
                                       TenantService tenantService,
                                       MerchantDashboardService merchantDashboardService) {
        this.volumeRevenueRepository = volumeRevenueRepository;
        this.salesTeamService = salesTeamService;
        this.tenantService = tenantService;
        this.merchantDashboardService = merchantDashboardService;
    }

    private void resolveFilters(VolumeRevenueFilterDTO filters) {
        if (filters.getTeamLeaderList() != null && !filters.getTeamLeaderList().isEmpty()) {
            Long tenantId = tenantService.getCurrentTenantId();
            if (tenantId != null) {
                List<String> salesUserIds = salesTeamService.getSalesUserIdsByTeamLeadNames(tenantId,
                        filters.getTeamLeaderList());
                if (salesUserIds.isEmpty()) {
                    filters.setTeamLeaderList(Collections.singletonList("__NO_MATCH__"));
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

    @GetMapping("/filter-options")
    public Map<String, List<String>> getFilterOptions() {
        return volumeRevenueRepository.getFilterOptions();
    }
}
