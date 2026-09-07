package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.RevenueMixRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Revenue Mix (/business/revenue-mix) — the finance team's Destination ×
 * Card Type P&amp;L matrix: #txns, volume and the fee waterfall
 * (MSF → ICF → net revenue → scheme fee → net margin → net spread on totals),
 * each with %-of-volume. Same filter drawer + preset-window conventions as
 * the other business dashboards; default window is YTD anchored on this
 * page's own data bounds (client-side).
 *
 * Caching, filter resolution (team-leader names → sales_user_id, Industry →
 * MCC translation) and the opt-in FX flag all follow the
 * IndustryAnalyticsController / NetSpreadController pattern.
 */
@RestController
@RequestMapping("/api/business/revenue-mix")
// Menu-grant gate — the sidebar entry and this API are driven by the same
// sys_group_menu grant (see V2026_09_07_03).
@PreAuthorize("@menuAccess.canAccess('/business/revenue-mix')")
public class RevenueMixController {

    @Autowired
    private RevenueMixRepository revenueMixRepository;

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

    /** Same resolveFilters convention as IndustryAnalyticsController. */
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

    /** The only split dimensions getMatrix supports — anything else is a 400, not a 500. */
    private static final java.util.Set<String> MATRIX_DIMENSIONS = java.util.Set.of("cardType", "scheme");

    @Autowired
    private com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    /**
     * Warm the page's first-load requests: the YTD default the frontend
     * POSTs on open (explicit Jan-1→latest dates + every drawer list sent as
     * an empty array + blank merchantName — see RevenueMix.jsx EMPTY_LISTS).
     * The DTO here must serialize to the SAME filterKey; a drifted key is
     * harmless but useless.
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("revenue-mix", tenantId -> {
            VolumeRevenueFilterDTO f = defaultOpenFilter(revenueMixRepository.getBounds(tenantId));
            if (f == null) return;
            boolean fx = fxEnabled(tenantId);
            String fk = filterKey(f);
            if (fk == null) return;
            String range = f.getStartDate() + ".." + f.getEndDate();
            reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA,
                    "revMixMatrix:" + tenantId + ":cardType:" + range + ":fx" + fx + ":" + fk,
                    () -> revenueMixRepository.getMatrix(f, "cardType", tenantId, fx));
            reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA,
                    "revMixTrend:" + tenantId + ":" + range + ":" + fk,
                    () -> revenueMixRepository.getTrend(f, tenantId));
        });
    }

    /**
     * The exact DTO the frontend's first POST deserializes into: YTD window
     * anchored on this page's latest loaded date, all drawer lists empty
     * (not null!), merchantName "".
     */
    static VolumeRevenueFilterDTO defaultOpenFilter(Map<String, Object> bounds) {
        Object latest = bounds.get("latest");
        if (latest == null) return null;
        java.time.LocalDate end = java.time.LocalDate.parse(latest.toString());
        VolumeRevenueFilterDTO f = new VolumeRevenueFilterDTO();
        f.setStartDate(end.withDayOfYear(1));
        f.setEndDate(end);
        f.setSchemeList(new java.util.ArrayList<>());
        f.setDestinationList(new java.util.ArrayList<>());
        f.setChannelList(new java.util.ArrayList<>());
        f.setMccList(new java.util.ArrayList<>());
        f.setCardTypeList(new java.util.ArrayList<>());
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

    @GetMapping("/bounds")
    public Map<String, Object> getBounds() {
        return revenueMixRepository.getBounds(tenantService.getCurrentTenantId());
    }

    @PostMapping({"/matrix", "/matrix/{dimension}"})
    public org.springframework.http.ResponseEntity<?> getMatrix(@RequestBody VolumeRevenueFilterDTO filters,
            @PathVariable(required = false) String dimension) {
        String dim = dimension == null ? "cardType" : dimension;
        if (!MATRIX_DIMENSIONS.contains(dim)) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown matrix dimension: " + dim
                            + " (supported: " + MATRIX_DIMENSIONS + ")"));
        }
        resolveFilters(filters);
        defaultDates(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        boolean fx = fxEnabled(tenantId);
        String fk = filterKey(filters);
        if (fk == null)
            return org.springframework.http.ResponseEntity.ok(
                    revenueMixRepository.getMatrix(filters, dim, tenantId, fx));
        String key = "revMixMatrix:" + tenantId + ":" + dim + ":" + filters.getStartDate() + ".."
                + filters.getEndDate() + ":fx" + fx + ":" + fk;
        return org.springframework.http.ResponseEntity.ok(
                reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, key,
                        () -> revenueMixRepository.getMatrix(filters, dim, tenantId, fx)));
    }

    @PostMapping("/trend")
    public List<Map<String, Object>> getTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters);
        Long tenantId = tenantService.getCurrentTenantId();
        String fk = filterKey(filters);
        if (fk == null) return revenueMixRepository.getTrend(filters, tenantId);
        String key = "revMixTrend:" + tenantId + ":" + filters.getStartDate() + ".." + filters.getEndDate()
                + ":" + fk;
        return reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> revenueMixRepository.getTrend(filters, tenantId));
    }
}
