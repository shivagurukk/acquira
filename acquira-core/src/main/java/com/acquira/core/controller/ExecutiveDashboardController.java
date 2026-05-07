package com.acquira.core.controller;

import com.acquira.common.dto.ExecutiveDashboardDTO;
import com.acquira.common.repository.ExecutiveDashboardRepository;
import com.acquira.core.service.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard/v2")
public class ExecutiveDashboardController {

    @Autowired
    private ExecutiveDashboardRepository repository;

    @Autowired
    private TenantService tenantService;

    @GetMapping("/data")
    public ExecutiveDashboardDTO getDashboardData(
            @RequestParam(required = false) String dataset,
            @RequestParam(required = false) LocalDate asOfDate) {
        // Pass current tenant so the repository can scope all 9 queries.
        // Without this, ExecutiveDashboard would mix data across tenants.
        Long tenantId = tenantService.getCurrentTenantId();
        return repository.getDashboardData(dataset, asOfDate, tenantId);
    }

    @GetMapping("/datasets")
    public List<String> getDatasets() {
        // Return simulated dataset/sheet names
        // In a real scenario, this might scan available tables or configuration
        return Arrays.asList("SID_Data_2026", "SID_Data_2025");
    }
}
