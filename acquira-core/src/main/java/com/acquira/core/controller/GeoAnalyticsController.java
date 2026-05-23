package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.GeoMetricDTO;
import com.acquira.common.repository.SumDailyTerminalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/analytics/geo")
@RequiredArgsConstructor
@Slf4j
public class GeoAnalyticsController {

    private final SumDailyTerminalRepository sumDailyTerminalRepository;

    @GetMapping("/heatmap")
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
        List<GeoMetricDTO> geoMetrics = sumDailyTerminalRepository.findGeoMetricsByDateForTenant(date, tenantId);

        return ResponseEntity.ok(geoMetrics);
    }
}
