package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.GeoMetricDTO;
import com.acquira.common.repository.SumDailyTerminalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/analytics/geo")
@RequiredArgsConstructor
@Slf4j
// No live frontend caller; gated to the heatmap screen it conceptually belongs to.
@PreAuthorize("@menuAccess.canAccess('/business/heatmap')")
public class GeoAnalyticsController {

    private final SumDailyTerminalRepository sumDailyTerminalRepository;
    private final com.acquira.common.service.ReportCache reportCache;

    @GetMapping("/heatmap")
    @com.acquira.common.config.ReportResponse
    public ResponseEntity<List<GeoMetricDTO>> getHeatmapData(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        // Default to yesterday if date is not provided
        if (date == null) {
            date = LocalDate.now().minusDays(1);
        }

        // SECURITY: scope to the caller's tenant. Without this the heatmap
        // returned every tenant's store names, coordinates and volumes.
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            return ResponseEntity.status(403).build();
        }

        log.info("Fetching Geo Heatmap data for tenant {} date {}", tenantId, date);
        // Keyed on the RESOLVED date (defaults to yesterday from the clock).
        final LocalDate day = date;
        List<GeoMetricDTO> geoMetrics = reportCache.get(
                com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA,
                "geoHeatmap:" + tenantId + ":" + day,
                () -> sumDailyTerminalRepository.findGeoMetricsByDateForTenant(day, tenantId));

        return ResponseEntity.ok(geoMetrics);
    }
}
