package com.acquira.controller;

import com.acquira.dto.MerchantDailyMetricsDTO;
import com.acquira.model.MerchantDailyMetrics;
import com.acquira.repository.MerchantDailyMetricsRepository;
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
            @RequestParam(defaultValue = "0") int month,
            @RequestParam(required = false) List<String> sidList,
            @RequestParam(required = false) List<String> midList) {

        LocalDate now = LocalDate.now();
        if (year == 0) year = now.getYear();
        if (month == 0) month = now.getMonthValue();

        LocalDate reportDate = LocalDate.of(year, month, 1);

        List<MerchantDailyMetrics> entities = metricsRepository.findByReportDate(reportDate);

        List<MerchantDailyMetricsDTO> dtos = entities.stream()
                .map(MerchantDailyMetricsDTO::fromEntity)
                .filter(dto -> {
                    // Apply MID filter
                    if (midList != null && !midList.isEmpty()) {
                        if (dto.getMid() == null || !midList.contains(dto.getMid())) {
                            return false;
                        }
                    }
                    // Apply SID filter
                    if (sidList != null && !sidList.isEmpty()) {
                        if (dto.getSid() == null || !sidList.contains(dto.getSid())) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }
}
