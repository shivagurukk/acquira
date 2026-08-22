package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.VolumeRevenueRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @Autowired
    private com.acquira.common.service.DataBoundsService dataBoundsService;

    @Autowired
    private com.acquira.common.service.ReportCache reportCache;

    /** Serializes the resolved filter DTO into a stable cache-key suffix. */
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String filterKey(VolumeRevenueFilterDTO filters) {
        try {
            return objectMapper.writeValueAsString(filters);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Unkeyable filters just mean an uncached (direct) execution.
            return null;
        }
    }

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

    @PreAuthorize("@menuAccess.canAccess('/business/volume-revenue')")
    @PostMapping("/volume-revenue-summary")
    public List<Map<String, Object>> getVolumeRevenueSummary(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getSummary(filters, tenantId);
    }

    @PreAuthorize("@menuAccess.canAccess('/business/merchant-financial')")
    @PostMapping("/merchant-financial-summary")
    public List<Map<String, Object>> getMerchantFinancialSummary(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getMerchantFinancialSummary(filters, tenantId);
    }

    @PreAuthorize("@menuAccess.canAccess('/business/performance')")
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

    @PreAuthorize("@menuAccess.canAccess('/business/debit-prepaid')")
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
    @PreAuthorize("@menuAccess.canAccess('/business/debit-prepaid')")
    @PostMapping("/debit-prepaid-summary")
    public Map<String, Object> getDebitPrepaidSummary(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getDebitPrepaidSummary(filters, tenantId);
    }

    /**
     * Attrition rows only — the original response shape, kept so any existing
     * caller keeps working unchanged.
     */
    @PreAuthorize("@menuAccess.canAccess('/business/attrition')")
    @PostMapping("/attrition-report")
    public List<Map<String, Object>> getAttritionReport(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getAttritionReport(filters, tenantId);
    }

    /**
     * Attrition rows PLUS the comparison-window metadata.
     * <p>
     * getAttritionReportMeta() was fully implemented but no endpoint ever exposed
     * it, so the signal it exists to carry — "this comparison window has no data
     * at all" — never reached the UI. Without it a merchant with an empty
     * prior-year window is indistinguishable from real explosive growth: both
     * render as +100%. Mirrors the {rows, meta} shape of /retention-report.
     */
    @PreAuthorize("@menuAccess.canAccess('/business/attrition')")
    @PostMapping("/attrition-report-with-meta")
    public Map<String, Object> getAttritionReportWithMeta(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        // Rows + meta computed the "latest loaded business date" separately —
        // same query, run twice, on every page load. getAttritionReportWithMeta
        // computes it once and threads it through both.
        // Cached on the POST-resolveFilters DTO (canonical: team-leader names
        // and industries are already resolved to ids/MCCs), so two spellings of
        // the same effective filter share an entry.
        String fk = filterKey(filters);
        if (fk == null) {
            return volumeRevenueRepository.getAttritionReportWithMeta(filters, tenantId);
        }
        return reportCache.get(
                com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA,
                "attritionMeta:" + tenantId + ":" + fk,
                () -> volumeRevenueRepository.getAttritionReportWithMeta(filters, tenantId));
    }

    @PreAuthorize("@menuAccess.canAccess('/business/retention')")
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

    @PreAuthorize("@menuAccess.canAccess('/dashboard')")
    @PostMapping("/executive-metrics")
    public Map<String, Object> getExecutiveMetrics(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        return volumeRevenueRepository.getExecutiveMetrics(filters, tenantId);
    }

    @PreAuthorize("@menuAccess.canAccess('/business/merchant-analytics') or @menuAccess.canAccess('/business/dashboard')")
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
    // Deliberately UNGATED: called by the shared Layout/filter components on
    // every screen — gating it would break the whole app for non-admin users.
    @GetMapping("/filter-options")
    public Map<String, List<String>> getFilterOptions() {
        // Pass tenant context so dropdown lists are scoped to the user's tenant.
        // Falls through to the unscoped variant when tenantId is null.
        // DO NOT wrap this call in ReportCache.get: the repository method is
        // itself @Cacheable on CACHE_LOOKUPS, and nesting a cache write inside
        // Caffeine's compute on the same cache violates ConcurrentHashMap's
        // no-recursive-update rule (IllegalStateException on a bin collision).
        // The inner @Cacheable also carries unless="#result.isEmpty()" so a
        // transient DB failure is not pinned — an outer unconditional cache
        // would hold the empty map for the full TTL.
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
    // Deliberately UNGATED: called by the shared Layout/filter components on
    // every screen — gating it would break the whole app for non-admin users.
    @GetMapping("/data-bounds")
    public Map<String, Object> getDataBounds() {
        // Delegated to DataBoundsService: same fact-first/insight-fallback
        // logic (P2-6), now cached per tenant because this endpoint gates the
        // first data fetch of most report pages. The cache is evicted when a
        // batch ingest completes.
        return dataBoundsService.getBounds(tenantService.getCurrentTenantId());
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
