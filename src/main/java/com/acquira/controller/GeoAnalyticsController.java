package com.acquira.controller;

import com.acquira.dto.GeoMetricDTO;
import com.acquira.repository.SumDailyTerminalRepository;
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

        log.info("Fetching Geo Heatmap data for date: {}", date);
        List<GeoMetricDTO> geoMetrics = sumDailyTerminalRepository.findGeoMetricsByDate(date);

        return ResponseEntity.ok(geoMetrics);
    }
}
