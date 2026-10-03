package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.config.ReportResponse;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.LocalDebitBankDashboardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Local Debit Bank Dashboard (/business/local-debit-bank-dashboard) —
 * DOMESTIC DEBIT traffic split by issuing bank, resolved from the card BIN via
 * the tenant's ref_tenant_bin_bank list. That list is dashboard-owned,
 * tenant-scoped reference data, deliberately separate from the global
 * ref_bin / ref_bin_range managed on the BIN Management screen (which this page
 * never consults) — bank names come EXCLUSIVELY from ref_tenant_bin_bank, and
 * an unmatched local-debit BIN renders in the 'Other Banks' bucket rather than
 * being dropped.
 *
 * THE BIN LIST IS SEEDED THROUGH THE DATABASE, NOT THROUGH THIS API
 * (decision 2026-08-20). It is controlled reference data: because bank names
 * resolve at QUERY time, a wrong or partial file would silently re-attribute
 * every bank across every historical month the moment it landed — with no
 * rebuild needed to spread it and none to undo it. So the list is exposed
 * READ-ONLY here; there is deliberately no upload, edit or delete endpoint.
 * Maintain it with docs/deploy/02_seed_uae_bin_bank.sql or an equivalent
 * reviewed INSERT. A guarded self-service flow (validation, preview, audit,
 * rollback) may replace this later.
 */
@RestController
@RequestMapping("/api/business/local-debit-bank-dashboard")
// Menu-grant gate, same as every other business screen — the sidebar entry
// and this API are driven by the same sys_group_menu grant.
@PreAuthorize("@menuAccess.canAccess('/business/local-debit-bank-dashboard')")
public class LocalDebitBankDashboardController {

    @Autowired
    private LocalDebitBankDashboardRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

    @Autowired
    private com.acquira.common.service.ReportCache reportCache;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    /**
     * Warm the page's first fetch: the D30 preset anchored on this page's own
     * latest date with LocalDebitBankDashboard.jsx's EMPTY_LISTS (merchant-level
     * lists only, merchantName ""), and top merchants for all banks, limit=25.
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("local-debit-bank-dashboard", tenantId -> {
            Object latest = repository.getBounds(tenantId).get("latest");
            if (latest == null) return;
            java.time.LocalDate end = java.time.LocalDate.parse(latest.toString());
            getKpis(defaultOpenFilter(end));
            getTrend(defaultOpenFilter(end));
            getTopMerchants(defaultOpenFilter(end), null, 25);
        });
    }

    /** The DTO the frontend's first POST deserializes into (fresh per call — handlers mutate it). */
    private static VolumeRevenueFilterDTO defaultOpenFilter(java.time.LocalDate end) {
        VolumeRevenueFilterDTO f = new VolumeRevenueFilterDTO();
        f.setStartDate(end.minusDays(29));
        f.setEndDate(end);
        f.setMidList(new java.util.ArrayList<>());
        f.setPartnerList(new java.util.ArrayList<>());
        f.setRmList(new java.util.ArrayList<>());
        f.setTeamLeaderList(new java.util.ArrayList<>());
        f.setIndustryList(new java.util.ArrayList<>());
        f.setMerchantName("");
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
     * ReportCache wrapper keyed on tenant + extra + the post-resolveFilters,
     * post-defaultDates DTO. Unserializable filter = uncached load.
     * Bank names resolve from ref_tenant_bin_bank at query time; that table is
     * DB-seeded only (see class javadoc), so a manual re-seed is picked up at
     * the cache TTL backstop, like any other out-of-band SQL.
     */
    private <T> T cached(String prefix, Long tenantId, String extra, VolumeRevenueFilterDTO filters,
            java.util.function.Supplier<T> loader) {
        String fk = filterKey(filters);
        if (fk == null) return loader.get();
        return reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA,
                prefix + ":" + tenantId + ":" + extra + ":" + fk, loader);
    }

    private Long requireTenant() {
        Long tenantId = tenantService.getCurrentTenantId();
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved");
        return tenantId;
    }

    // Same resolveFilters convention as BusinessAnalyticsController —
    // duplicated locally (rather than shared) to keep this feature additive
    // and isolated from the existing controllers.
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
     * Server-side date defaults: without them a missing range scans every
     * partition back to 2024.
     */
    private static void defaultDates(VolumeRevenueFilterDTO f, int defaultDays) {
        if (f.getEndDate() == null) f.setEndDate(java.time.LocalDate.now());
        if (f.getStartDate() == null) f.setStartDate(f.getEndDate().minusDays(defaultDays));
    }

    // ─── dashboard reads ───────────────────────────────────────────────

    /** MIN/MAX business_date in sum_daily_local_debit_bin — the page anchors its presets here. */
    @ReportResponse
    @GetMapping("/bounds")
    public Map<String, Object> getBounds() {
        return repository.getBounds(requireTenant());
    }

    @ReportResponse
    @PostMapping("/kpis")
    public Map<String, Object> getKpis(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        Long tenantId = requireTenant();
        // The repository defaults a missing endDate to today — key on that day.
        String asOf = filters.getEndDate() == null ? "now" + java.time.LocalDate.now() : "-";
        return cached("ldbDashKpis", tenantId, asOf, filters,
                () -> repository.getKpis(filters, tenantId));
    }

    @ReportResponse
    @PostMapping("/trend")
    public List<Map<String, Object>> getTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters, 365); // 12 months of monthly buckets
        Long tenantId = requireTenant();
        return cached("ldbDashTrend", tenantId, "-", filters,
                () -> repository.getTrend(filters, tenantId));
    }

    @ReportResponse
    @PostMapping("/daily-trend")
    public List<Map<String, Object>> getDailyTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters, 30);
        Long tenantId = requireTenant();
        return cached("ldbDashDailyTrend", tenantId, "-", filters,
                () -> repository.getDailyTrend(filters, tenantId));
    }

    @ReportResponse
    @PostMapping("/top-merchants")
    public List<Map<String, Object>> getTopMerchants(@RequestBody VolumeRevenueFilterDTO filters,
                                                     @RequestParam(required = false) String bank,
                                                     @RequestParam(defaultValue = "25") int limit) {
        resolveFilters(filters);
        defaultDates(filters, 30);
        Long tenantId = requireTenant();
        int lim = Math.min(Math.max(limit, 1), 100);
        return cached("ldbDashTopMerchants", tenantId, (bank == null ? "allBanks" : "bank=" + bank) + ":lim" + lim, filters,
                () -> repository.getTopMerchants(filters, tenantId, bank, lim));
    }

    /** Coverage worklist: top local-debit BINs with no row in ref_tenant_bin_bank. */
    @ReportResponse
    @PostMapping("/unmatched-bins")
    public List<Map<String, Object>> getUnmatchedBins(@RequestBody VolumeRevenueFilterDTO filters,
                                                      @RequestParam(defaultValue = "50") int limit) {
        defaultDates(filters, 30);
        Long tenantId = requireTenant();
        int lim = Math.min(Math.max(limit, 1), 500);
        return cached("ldbDashUnmatchedBins", tenantId, "lim" + lim, filters,
                () -> repository.getUnmatchedBins(filters, tenantId, lim));
    }

    // ─── tenant BIN->bank list (READ-ONLY — seeded via the database) ────

    /**
     * The configured BIN -> bank mappings for the current tenant, so the page
     * can show what it resolves against. Read-only by design: see the class
     * javadoc for why there is no write endpoint here.
     */
    @ReportResponse
    @GetMapping("/bins")
    public List<Map<String, Object>> listBins() {
        Long tenantId = requireTenant();
        return jdbcTemplate.queryForList(
                "SELECT bin, bank_name, source_file, loaded_at FROM ref_tenant_bin_bank " +
                "WHERE tenant_id = ? ORDER BY bank_name, bin", tenantId);
    }
}
