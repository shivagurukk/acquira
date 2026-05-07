package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.VolumeRevenueRepository;
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
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

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
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getSummary(filters, tenantId);
    }

    @PostMapping("/merchant-financial-summary")
    public List<Map<String, Object>> getMerchantFinancialSummary(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getMerchantFinancialSummary(filters, tenantId);
    }

    @PostMapping("/performance-dashboard")
    public List<Map<String, Object>> getPerformanceDashboard(
            @RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam String groupBy,
            @RequestParam(required = false) String parentValue,
            @RequestParam(required = false) String grandParentValue) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getPerformanceDashboardData(filters, groupBy, parentValue, grandParentValue, tenantId);
    }

    @PostMapping("/debit-prepaid-metrics")
    public List<Map<String, Object>> getDebitPrepaidMetrics(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getDebitPrepaidMetrics(filters, tenantId);
    }

    @PostMapping("/attrition-report")
    public List<Map<String, Object>> getAttritionReport(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getAttritionReport(filters, tenantId);
    }

    @PostMapping("/executive-metrics")
    public Map<String, Object> getExecutiveMetrics(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getExecutiveMetrics(filters, tenantId);
    }

    @PostMapping("/merchant-analytics")
    public Map<String, Object> getMerchantAnalyticsReport(
            @RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        resolveFilters(filters);
        // Pass tenant context so cross-tenant rows can never leak through this endpoint.
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getMerchantAnalyticsReport(filters, page, size, tenantId);
    }

    @Autowired
    private com.acquira.core.service.MerchantDashboardService merchantDashboardService;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    // Placeholder for filter options (dropdowns)
    @GetMapping("/filter-options")
    public Map<String, List<String>> getFilterOptions() {
        // Pass tenant context so dropdown lists are scoped to the user's tenant.
        // Falls through to the unscoped variant when tenantId is null.
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getFilterOptions(tenantId);
    }

    /**
     * Returns the date range that actually has data for the current tenant.
     * The frontend uses this to default to the LAST month with data instead of
     * the calendar's current month — which is otherwise empty in environments
     * where transaction data lags real time (e.g. data through April but it's
     * already May).
     */
    @GetMapping("/data-bounds")
    public Map<String, Object> getDataBounds() {
        Long tenantId = tenantService.getCurrentTenantId();
        Map<String, Object> response = new java.util.HashMap<>();
        try {
            String sql = "SELECT MIN(business_date) AS earliest, MAX(business_date) AS latest " +
                         "FROM sum_daily_insight" +
                         (tenantId != null ? " WHERE tenant_id = :tid" : "");
            jakarta.persistence.Query q = entityManager.createNativeQuery(sql);
            if (tenantId != null) q.setParameter("tid", tenantId);
            Object[] row = (Object[]) q.getSingleResult();
            response.put("earliest", row != null && row[0] != null ? row[0].toString() : null);
            response.put("latest",   row != null && row[1] != null ? row[1].toString() : null);
        } catch (Exception e) {
            response.put("earliest", null);
            response.put("latest", null);
            response.put("error", e.getMessage());
        }
        return response;
    }

    // @GetMapping("/daily-merchant-dashboard")
    // public List<com.acquira.common.dto.DailyMerchantDashboardDTO>
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
