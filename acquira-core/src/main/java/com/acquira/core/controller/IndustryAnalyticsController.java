package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.IndustryAnalyticsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Industry Analytics (/business/industry-analytics) — the acquiring P&amp;L split
 * by industry (MCC sector): volume, MSF, ICF, scheme fee, net revenue and net
 * spread, each with %-of-volume. Same filter drawer + preset-window
 * conventions as the Card Type / Destination dashboards; default window is
 * YTD anchored on this page's own data bounds (client-side).
 *
 * Responses are served through ReportCache (same pattern as
 * NetSpreadController): keyed on tenant + resolved window + fx flag + the
 * POST-resolveFilters DTO, so equivalent filter picks share an entry and an
 * ingest-triggered evictAll() keeps the page fresh. That plus the two-table
 * summary-only read path is what keeps first paint fast.
 */
@RestController
@RequestMapping("/api/business/industry-analytics")
// Menu-grant gate — the sidebar entry and this API are driven by the same
// sys_group_menu grant (see V2026_09_07_02).
@PreAuthorize("@menuAccess.canAccess('/business/industry-analytics')")
public class IndustryAnalyticsController {

    @Autowired
    private IndustryAnalyticsRepository industryAnalyticsRepository;

    @Autowired
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

    @Autowired
    private com.acquira.common.service.ReportCache reportCache;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String filterKey(VolumeRevenueFilterDTO filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /** Same opt-in FX flag as NetSpreadController — part of every cache key. */
    private boolean fxEnabled(Long tenantId) {
        List<String> v = jdbcTemplate.queryForList(
                "SELECT setting_value FROM tenant_setting WHERE tenant_id = ? AND setting_key = 'netspread.fx_enabled'",
                String.class, tenantId);
        return !v.isEmpty() && "true".equalsIgnoreCase(String.valueOf(v.get(0)).trim());
    }

    /**
     * Same resolveFilters convention as BusinessAnalyticsController, including
     * the Industry (MCC sector) → MCC translation: the drawer's Industry
     * dropdown speaks ref_mcc_category.category, and this page's group key is
     * that same vocabulary — the pick becomes an mccList predicate so the
     * repository never touches the mismatched dim_merchant.industry column.
     */
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

        if (filters.getIndustryList() != null && !filters.getIndustryList().isEmpty()) {
            boolean resolved = false;
            try {
                @SuppressWarnings("unchecked")
                List<String> industryMccs = entityManager.createNativeQuery(
                        "SELECT mcc FROM ref_mcc_category WHERE category IN (:cats)")
                        .setParameter("cats", filters.getIndustryList())
                        .getResultList();
                if (industryMccs.isEmpty()) {
                    filters.setMccList(java.util.Collections.singletonList("__NO_MATCH__"));
                } else if (filters.getMccList() != null && !filters.getMccList().isEmpty()) {
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
                // ref_mcc_category absent (pre-migration env) — leave untouched.
            }
            if (resolved) filters.setIndustryList(null);
        }
    }

    /** Server-side fallback so a missing range never scans every partition. */
    private static void defaultDates(VolumeRevenueFilterDTO f) {
        if (f.getEndDate() == null) f.setEndDate(java.time.LocalDate.now());
        if (f.getStartDate() == null) f.setStartDate(f.getEndDate().withDayOfYear(1)); // YTD
    }

    @Autowired
    private com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    /**
     * Warm the page's first-load requests — the YTD default the frontend
     * POSTs on open. Reuses RevenueMixController.defaultOpenFilter, which
     * replicates the shared EMPTY_LISTS body shape (both pages send the
     * identical filter object); the DTO must serialize to the SAME filterKey
     * the live request produces — a drifted key is harmless but useless.
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("industry-analytics", tenantId -> {
            VolumeRevenueFilterDTO f = RevenueMixController.defaultOpenFilter(
                    industryAnalyticsRepository.getBounds(tenantId));
            if (f == null) return;
            boolean fx = fxEnabled(tenantId);
            String fk = filterKey(f);
            if (fk == null) return;
            String range = f.getStartDate() + ".." + f.getEndDate();
            reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA,
                    "industryRows:" + tenantId + ":" + range + ":fx" + fx + ":" + fk,
                    () -> industryAnalyticsRepository.getIndustryRows(f, tenantId, fx));
            reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA,
                    "industryTrend:" + tenantId + ":" + range + ":fx" + fx + ":" + fk,
                    () -> industryAnalyticsRepository.getTrend(f, tenantId, fx));
        });
    }

    /**
     * MIN/MAX business_date in sum_daily_full — the page anchors its presets
     * (YTD default) here, not on the shared fact-anchored /business/data-bounds.
     */
    @GetMapping("/bounds")
    public Map<String, Object> getBounds() {
        return industryAnalyticsRepository.getBounds(tenantService.getCurrentTenantId());
    }

    @PostMapping("/rows")
    public Map<String, Object> getRows(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        boolean fx = fxEnabled(tenantId);
        String fk = filterKey(filters);
        if (fk == null) return industryAnalyticsRepository.getIndustryRows(filters, tenantId, fx);
        String key = "industryRows:" + tenantId + ":" + filters.getStartDate() + ".." + filters.getEndDate()
                + ":fx" + fx + ":" + fk;
        return reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> industryAnalyticsRepository.getIndustryRows(filters, tenantId, fx));
    }

    @PostMapping("/trend")
    public List<Map<String, Object>> getTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        boolean fx = fxEnabled(tenantId);
        String fk = filterKey(filters);
        if (fk == null) return industryAnalyticsRepository.getTrend(filters, tenantId, fx);
        String key = "industryTrend:" + tenantId + ":" + filters.getStartDate() + ".." + filters.getEndDate()
                + ":fx" + fx + ":" + fk;
        return reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> industryAnalyticsRepository.getTrend(filters, tenantId, fx));
    }
}
