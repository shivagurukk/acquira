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

        // Industry (MCC sector) → MCC translation.
        // The Industry dropdown is fed from ref_mcc_category (the bank's MCC
        // sector sheet), but most report queries have no ref_mcc_category join
        // and dim_merchant.industry carries raw feed text that never matches
        // those categories — so an Industry pick used to silently return
        // zero/unfiltered rows. Resolving categories to their MCC codes here
        // makes every endpoint that honours mccList honour Industry too.
        if (filters.getIndustryList() != null && !filters.getIndustryList().isEmpty()) {
            boolean resolved = false;
            try {
                @SuppressWarnings("unchecked")
                List<String> industryMccs = entityManager.createNativeQuery(
                        "SELECT mcc FROM ref_mcc_category WHERE category IN (:cats)")
                        .setParameter("cats", filters.getIndustryList())
                        .getResultList();
                if (industryMccs.isEmpty()) {
                    // Category selected but no MCC maps to it → force no results
                    // (mirrors the team-leader sentinel) instead of silently
                    // ignoring the filter.
                    filters.setMccList(java.util.Collections.singletonList("__NO_MATCH__"));
                } else if (filters.getMccList() != null && !filters.getMccList().isEmpty()) {
                    // Both Industry and explicit MCCs selected → intersect (AND).
                    List<String> intersect = new java.util.ArrayList<>(filters.getMccList());
                    intersect.retainAll(industryMccs);
                    filters.setMccList(intersect.isEmpty()
                            ? java.util.Collections.singletonList("__NO_MATCH__")
                            : intersect);
                } else {
                    filters.setMccList(industryMccs);
                }
                resolved = true;
            } catch (Exception refEx) {
                // ref_mcc_category absent (pre-migration env) — leave
                // industryList untouched for the few queries that still
                // filter on dim_merchant.industry.
            }
            if (resolved) {
                // Prevent double-filtering against the mismatched
                // dim_merchant.industry column in queries that apply both.
                filters.setIndustryList(null);
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

    /**
     * Debit/Prepaid segment summary: tiles (segment vs. book share, MSF bps,
     * avg ticket), the debit-vs-prepaid bucket split, and destination/channel/
     * scheme/month breakdowns. Reuses resolveFilters so Industry and Team
     * Leader picks behave identically to every other Business Analytics
     * endpoint. Additive — the existing table endpoint above is untouched.
     */
    @PostMapping("/debit-prepaid-summary")
    public Map<String, Object> getDebitPrepaidSummary(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getDebitPrepaidSummary(filters, tenantId);
    }

    @PostMapping("/attrition-report")
    public List<Map<String, Object>> getAttritionReport(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getAttritionReport(filters, tenantId);
    }

    @PostMapping("/retention-report")
    public Map<String, Object> getRetentionReport(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        List<Map<String, Object>> rows = volumeRevenueRepository.getRetentionReport(filters, tenantId);
        Map<String, Object> meta = volumeRevenueRepository.getRetentionReportMeta(filters, tenantId);
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("rows", rows);
        response.put("meta", meta);
        return response;
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
            // P2-6 FIX: previously only queried sum_daily_insight. If
            // populateSummaryStep failed mid-run (e.g. the deadlock storm
            // we've seen), sum_daily_insight may be sparse while
            // fact_transaction has the real data. Use the most
            // authoritative source (fact_transaction) and fall back to
            // the summary tables only if fact is empty.
            String factSql = "SELECT MIN(payment_date)::date AS earliest, MAX(payment_date)::date AS latest " +
                             "FROM fact_transaction" +
                             (tenantId != null ? " WHERE tenant_id = :tid" : "");
            jakarta.persistence.Query qFact = entityManager.createNativeQuery(factSql);
            if (tenantId != null) qFact.setParameter("tid", tenantId);
            Object[] factRow = (Object[]) qFact.getSingleResult();
            String earliest = factRow != null && factRow[0] != null ? factRow[0].toString() : null;
            String latest   = factRow != null && factRow[1] != null ? factRow[1].toString() : null;

            // Fallback: if fact_transaction is empty, try sum_daily_insight.
            if (earliest == null && latest == null) {
                String insSql = "SELECT MIN(business_date) AS earliest, MAX(business_date) AS latest " +
                                "FROM sum_daily_insight" +
                                (tenantId != null ? " WHERE tenant_id = :tid" : "");
                jakarta.persistence.Query qIns = entityManager.createNativeQuery(insSql);
                if (tenantId != null) qIns.setParameter("tid", tenantId);
                Object[] insRow = (Object[]) qIns.getSingleResult();
                earliest = insRow != null && insRow[0] != null ? insRow[0].toString() : null;
                latest   = insRow != null && insRow[1] != null ? insRow[1].toString() : null;
            }

            response.put("earliest", earliest);
            response.put("latest", latest);
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
