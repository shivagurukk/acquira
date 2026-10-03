package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.config.ReportResponse;
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

    @Autowired
    private com.acquira.common.service.ReportCache reportCache;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    /**
     * Warm the page's first fetch: the D30 preset anchored on this page's own
     * latest date, every drawer list sent as an empty array (destinationList
     * absent = null), merchantName "" — see DestinationDashboard.jsx
     * EMPTY_LISTS — with the default 'scheme' breakdown tab and limit=15.
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("destination-dashboard", tenantId -> {
            Object latest = destinationDashboardRepository.getBounds(tenantId).get("latest");
            if (latest == null) return;
            java.time.LocalDate end = java.time.LocalDate.parse(latest.toString());
            getKpis(defaultOpenFilter(end));
            getTrend(defaultOpenFilter(end));
            getBreakdown(defaultOpenFilter(end), "scheme");
            getTopMerchants(defaultOpenFilter(end), 15);
        });
    }

    /** The DTO the frontend's first POST deserializes into (fresh per call — handlers mutate it). */
    private static VolumeRevenueFilterDTO defaultOpenFilter(java.time.LocalDate end) {
        VolumeRevenueFilterDTO f = new VolumeRevenueFilterDTO();
        f.setStartDate(end.minusDays(29));
        f.setEndDate(end);
        f.setSchemeList(new java.util.ArrayList<>());
        f.setCardTypeList(new java.util.ArrayList<>());
        f.setChannelList(new java.util.ArrayList<>());
        f.setMccList(new java.util.ArrayList<>());
        f.setMidList(new java.util.ArrayList<>());
        f.setSidList(new java.util.ArrayList<>());
        f.setPartnerList(new java.util.ArrayList<>());
        f.setRmList(new java.util.ArrayList<>());
        f.setTeamLeaderList(new java.util.ArrayList<>());
        f.setIndustryList(new java.util.ArrayList<>());
        f.setSectorList(new java.util.ArrayList<>());
        f.setTerminalTypeList(new java.util.ArrayList<>());
        f.setMerchantName("");
        return f;
    }

    private String filterKey(VolumeRevenueFilterDTO filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (tools.jackson.core.JacksonException e) {
            return null;
        }
    }

    /**
     * ReportCache wrapper keyed on tenant + extra + the post-resolveFilters,
     * post-defaultDates DTO. Unserializable filter = uncached load.
     */
    private <T> T cached(String prefix, Long tenantId, String extra, VolumeRevenueFilterDTO filters,
            java.util.function.Supplier<T> loader) {
        String fk = filterKey(filters);
        if (fk == null) return loader.get();
        return reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA,
                prefix + ":" + tenantId + ":" + extra + ":" + fk, loader);
    }

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

    /**
     * MIN/MAX business_date in sum_daily_merchant_destination for this tenant.
     * The page anchors its date presets here rather than on the shared
     * /business/data-bounds, which is fact_transaction-anchored and can point
     * at a range this table has no rows for — anchoring there made "This
     * year" span months with no data (the 2-month YTD symptom). Same pattern
     * as CardTypeDashboardController.
     */
    @ReportResponse
    @GetMapping("/bounds")
    public Map<String, Object> getBounds() {
        return destinationDashboardRepository.getBounds(tenantService.getCurrentTenantId());
    }

    @ReportResponse
    @PostMapping("/kpis")
    public Map<String, Object> getKpis(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        // The repository defaults a missing endDate to today — key on that day.
        String asOf = filters.getEndDate() == null ? "now" + java.time.LocalDate.now() : "-";
        return cached("destDashKpis", tenantId, asOf, filters,
                () -> destinationDashboardRepository.getKpis(filters, tenantId));
    }

    @ReportResponse
    @PostMapping("/trend")
    public List<Map<String, Object>> getTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters, 365); // 12 months of monthly buckets
        Long tenantId = tenantService.getCurrentTenantId();
        return cached("destDashTrend", tenantId, "-", filters,
                () -> destinationDashboardRepository.getTrend(filters, tenantId));
    }

    @ReportResponse
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
        return org.springframework.http.ResponseEntity.ok(cached("destDashBreakdown", tenantId, dimension, filters,
                () -> destinationDashboardRepository.getBreakdown(filters, dimension, tenantId)));
    }

    @ReportResponse
    @PostMapping("/top-merchants")
    public List<Map<String, Object>> getTopMerchants(@RequestBody VolumeRevenueFilterDTO filters,
                                                       @RequestParam(defaultValue = "15") int limit) {
        resolveFilters(filters);
        defaultDates(filters, 30);
        Long tenantId = tenantService.getCurrentTenantId();
        int lim = Math.min(Math.max(limit, 1), 100);
        return cached("destDashTopMerchants", tenantId, "lim" + lim, filters,
                () -> destinationDashboardRepository.getTopMerchants(filters, tenantId, lim));
    }
}
