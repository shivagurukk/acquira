package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.ZeroTransactionRepository;
import com.acquira.core.service.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports/zero-txn")
@PreAuthorize("@menuAccess.canAccess('/business/zero-transaction')")
public class ZeroTransactionController {

    @Autowired
    private ZeroTransactionRepository repository;

    @Autowired
    private TenantService tenantService;

    @PostMapping("/list")
    public List<Map<String, Object>> getList(
            @RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "LAST_30") String rangeType) {
        // Pass current tenant so the join chain dim_terminal -> dim_store -> dim_merchant
        // is scoped, plus the inner sum_daily_terminal subquery.
        Long tenantId = tenantService.getCurrentTenantId();
        return repository.getZeroTransactionListSmart(filters, rangeType, tenantId);
    }

    // Endpoint for KPI cards if we want summary counts
    @PostMapping("/kpi")
    public Map<String, Object> getKpi(@RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "LAST_30") String rangeType) {
        Long tenantId = tenantService.getCurrentTenantId();
        return repository.getZeroTransactionSummary(filters, rangeType, tenantId);
    }

    // Accurate counts + days-inactive distribution + top aggregators over the
    // FULL filtered set (independent of pagination / the old 500 cap).
    @PostMapping("/summary")
    public Map<String, Object> getSummary(@RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "LAST_30") String rangeType) {
        Long tenantId = tenantService.getCurrentTenantId();
        return repository.getZeroTransactionSummary(filters, rangeType, tenantId);
    }

    // Terminal / POS estate health over the FULL filtered estate — the
    // denominator the dormancy view lacks (active vs idle vs dormant vs never),
    // plus utilization of the terminals that ARE still transacting. Deliberately
    // takes no rangeType: estate thresholds are fixed at 7d / 30d.
    @PostMapping("/estate")
    public Map<String, Object> getEstateHealth(@RequestBody VolumeRevenueFilterDTO filters) {
        Long tenantId = tenantService.getCurrentTenantId();
        return repository.getEstateHealth(filters, tenantId);
    }

    // Server-side paginated rows + total. status = ALL | IN30 | NEVER | IN7.
    @PostMapping("/page")
    public Map<String, Object> getPage(@RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "LAST_30") String rangeType,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long tenantId = tenantService.getCurrentTenantId();
        return repository.getZeroTransactionPage(filters, rangeType, status, page, size, tenantId);
    }
}
