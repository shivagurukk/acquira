package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.VolumeDropRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Volume Drop (/business/volume-drop) — per-merchant month-over-month volume
 * comparison: last month full volume vs current MTD, projected month-end
 * (linear + pace, both returned), and the drop/gain each implies. Replaces the
 * hand-built "Jul vs Aug MTD" Excel.
 *
 * The as-of date defaults to the latest LOADED business date (feeds lag; the
 * calendar would understate every merchant) and a client-picked endDate is
 * clamped to it. Responses go through ReportCache — keyed on tenant + as-of +
 * the post-resolveFilters DTO — so an ingest-triggered evictAll() keeps the
 * page fresh, same convention as IndustryAnalyticsController.
 */
@RestController
@RequestMapping("/api/business/volume-drop")
// Menu-grant gate — the sidebar entry and this API are driven by the same
// sys_group_menu grant (see V2026_09_07_04).
@PreAuthorize("@menuAccess.canAccess('/business/volume-drop')")
public class VolumeDropController {

    @Autowired
    private VolumeDropRepository volumeDropRepository;

    @Autowired
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

    @Autowired
    private com.acquira.common.service.ReportCache reportCache;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String filterKey(VolumeRevenueFilterDTO filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /** Same team-lead-name → sales_user_id translation as the sibling screens. */
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
     * MIN/MAX business_date in sum_daily_merchant — the page anchors its
     * month picker and default as-of date here.
     */
    @GetMapping("/bounds")
    public Map<String, Object> getBounds() {
        return volumeDropRepository.getBounds(tenantService.getCurrentTenantId());
    }

    @PostMapping("/rows")
    public Map<String, Object> getRows(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = tenantService.getCurrentTenantId();

        // As-of anchor: requested endDate, clamped to the latest loaded date —
        // elapsed days past the data would divide the MTD over empty days.
        Object latestObj = volumeDropRepository.getBounds(tenantId).get("latest");
        LocalDate latest = latestObj == null ? null : LocalDate.parse(latestObj.toString());
        LocalDate asOf = filters.getEndDate() != null ? filters.getEndDate()
                : (latest != null ? latest : LocalDate.now());
        if (latest != null && asOf.isAfter(latest)) asOf = latest;
        final LocalDate anchored = asOf;

        String fk = filterKey(filters);
        if (fk == null) return volumeDropRepository.getDropRows(filters, tenantId, anchored);
        String key = "volumeDropRows:" + tenantId + ":" + anchored + ":" + fk;
        return reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> volumeDropRepository.getDropRows(filters, tenantId, anchored));
    }
}
