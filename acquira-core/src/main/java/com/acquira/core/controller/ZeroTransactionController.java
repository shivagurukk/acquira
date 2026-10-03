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

    @Autowired
    private com.acquira.common.service.ReportCache reportCache;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    /**
     * Warm the page's first load (ZeroTransactionReport.jsx runReport on
     * mount): summary + estate + page 0/50, rangeType LAST_30, status ALL,
     * with the empty-drawer payload { merchantName: "", partnerList: [],
     * midList: [], sidList: [], tidList: [] }.
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("zero-transaction", tenantId -> {
            getSummary(defaultFilter(), "LAST_30");
            getEstateHealth(defaultFilter());
            getPage(defaultFilter(), "LAST_30", "ALL", 0, 50);
        });
    }

    /** The DTO the page's empty-drawer POST body deserializes into. */
    private static VolumeRevenueFilterDTO defaultFilter() {
        VolumeRevenueFilterDTO f = new VolumeRevenueFilterDTO();
        f.setMerchantName("");
        f.setPartnerList(new java.util.ArrayList<>());
        f.setMidList(new java.util.ArrayList<>());
        f.setSidList(new java.util.ArrayList<>());
        f.setTidList(new java.util.ArrayList<>());
        return f;
    }

    private String filterKey(VolumeRevenueFilterDTO filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (tools.jackson.core.JacksonException e) {
            return null;
        }
    }

    /**
     * All windows anchor on the tenant's latest loaded business_date inside the
     * repository (data-derived, so ingest eviction keeps it fresh) — the key
     * needs only tenant + params + the full filter body.
     */
    private <T> T cached(String key, VolumeRevenueFilterDTO filters, java.util.function.Supplier<T> loader) {
        String fk = filterKey(filters);
        if (fk == null) return loader.get();
        return reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA, key + ":" + fk, loader);
    }

    @PostMapping("/list")
    @com.acquira.common.config.ReportResponse
    public List<Map<String, Object>> getList(
            @RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "LAST_30") String rangeType) {
        // Pass current tenant so the join chain dim_terminal -> dim_store -> dim_merchant
        // is scoped, plus the inner sum_daily_terminal subquery.
        Long tenantId = tenantService.getCurrentTenantId();
        return cached("zeroTxnList:" + tenantId + ":" + rangeType, filters,
                () -> repository.getZeroTransactionListSmart(filters, rangeType, tenantId));
    }

    // Endpoint for KPI cards if we want summary counts
    @PostMapping("/kpi")
    @com.acquira.common.config.ReportResponse
    public Map<String, Object> getKpi(@RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "LAST_30") String rangeType) {
        Long tenantId = tenantService.getCurrentTenantId();
        return cached("zeroTxnSummary:" + tenantId + ":" + rangeType, filters,
                () -> repository.getZeroTransactionSummary(filters, rangeType, tenantId));
    }

    // Accurate counts + days-inactive distribution + top aggregators over the
    // FULL filtered set (independent of pagination / the old 500 cap).
    @PostMapping("/summary")
    @com.acquira.common.config.ReportResponse
    public Map<String, Object> getSummary(@RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "LAST_30") String rangeType) {
        Long tenantId = tenantService.getCurrentTenantId();
        return cached("zeroTxnSummary:" + tenantId + ":" + rangeType, filters,
                () -> repository.getZeroTransactionSummary(filters, rangeType, tenantId));
    }

    // Terminal / POS estate health over the FULL filtered estate — the
    // denominator the dormancy view lacks (active vs idle vs dormant vs never),
    // plus utilization of the terminals that ARE still transacting. Deliberately
    // takes no rangeType: estate thresholds are fixed at 7d / 30d.
    @PostMapping("/estate")
    @com.acquira.common.config.ReportResponse
    public Map<String, Object> getEstateHealth(@RequestBody VolumeRevenueFilterDTO filters) {
        Long tenantId = tenantService.getCurrentTenantId();
        return cached("zeroTxnEstate:" + tenantId, filters,
                () -> repository.getEstateHealth(filters, tenantId));
    }

    // Server-side paginated rows + total. status = ALL | IN30 | NEVER | IN7.
    @PostMapping("/page")
    @com.acquira.common.config.ReportResponse
    public Map<String, Object> getPage(@RequestBody VolumeRevenueFilterDTO filters,
            @RequestParam(defaultValue = "LAST_30") String rangeType,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long tenantId = tenantService.getCurrentTenantId();
        return cached("zeroTxnPage:" + tenantId + ":" + rangeType + ":" + status + ":" + page + ":" + size, filters,
                () -> repository.getZeroTransactionPage(filters, rangeType, status, page, size, tenantId));
    }
}
