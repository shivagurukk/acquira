package com.acquira.controller;

import com.acquira.dto.ExecutiveDashboardDTO;
import com.acquira.repository.ExecutiveDashboardRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard/v2")
public class ExecutiveDashboardController {

    private final ExecutiveDashboardRepository repository;

    public ExecutiveDashboardController(ExecutiveDashboardRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/data")
    public ExecutiveDashboardDTO getDashboardData(
            @RequestParam(required = false) String dataset,
            @RequestParam(required = false) LocalDate asOfDate) {
        return repository.getDashboardData(dataset, asOfDate);
    }

    @GetMapping("/datasets")
    public List<String> getDatasets() {
        return Arrays.asList("SID_Data_2026", "SID_Data_2025");
    }
}
