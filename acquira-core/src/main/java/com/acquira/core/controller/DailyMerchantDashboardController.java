package com.acquira.core.controller;

import com.acquira.common.dto.MerchantDailyMetricsDTO;
import com.acquira.common.model.MerchantDailyMetrics;
import com.acquira.common.repository.MerchantDailyMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
public class DailyMerchantDashboardController {

    private final MerchantDailyMetricsRepository metricsRepository;

    @GetMapping("/daily-merchant-dashboard")
    public ResponseEntity<List<MerchantDailyMetricsDTO>> getDashboardData(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "0") int month) {

        // Default to current date if params missing
        LocalDate now = LocalDate.now();
        if (year == 0)
            year = now.getYear();
        if (month == 0)
            month = now.getMonthValue();

        LocalDate reportDate = LocalDate.of(year, month, 1);

        List<MerchantDailyMetrics> entities = metricsRepository.findByReportDate(reportDate);

        List<MerchantDailyMetricsDTO> dtos = entities.stream()
                .map(MerchantDailyMetricsDTO::fromEntity)
                // Filter out zero-volume rows if needed? User wants "Everything with Granular
                // Detail".
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }
}
