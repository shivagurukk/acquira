package com.acquira.core.controller;

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
    @GetMapping("/bounds")
    public Map<String, Object> getBounds() {
        return repository.getBounds(requireTenant());
    }

    @PostMapping("/kpis")
    public Map<String, Object> getKpis(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        return repository.getKpis(filters, requireTenant());
    }

    @PostMapping("/trend")
    public List<Map<String, Object>> getTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters, 365); // 12 months of monthly buckets
        return repository.getTrend(filters, requireTenant());
    }

    @PostMapping("/daily-trend")
    public List<Map<String, Object>> getDailyTrend(@RequestBody VolumeRevenueFilterDTO filters) {
        resolveFilters(filters);
        defaultDates(filters, 30);
        return repository.getDailyTrend(filters, requireTenant());
    }

    @PostMapping("/top-merchants")
    public List<Map<String, Object>> getTopMerchants(@RequestBody VolumeRevenueFilterDTO filters,
                                                     @RequestParam(required = false) String bank,
                                                     @RequestParam(defaultValue = "25") int limit) {
        resolveFilters(filters);
        defaultDates(filters, 30);
        return repository.getTopMerchants(filters, requireTenant(), bank, Math.min(Math.max(limit, 1), 100));
    }

    /** Coverage worklist: top local-debit BINs with no row in ref_tenant_bin_bank. */
    @PostMapping("/unmatched-bins")
    public List<Map<String, Object>> getUnmatchedBins(@RequestBody VolumeRevenueFilterDTO filters,
                                                      @RequestParam(defaultValue = "50") int limit) {
        defaultDates(filters, 30);
        return repository.getUnmatchedBins(filters, requireTenant(), Math.min(Math.max(limit, 1), 500));
    }

    // ─── tenant BIN->bank list (READ-ONLY — seeded via the database) ────

    /**
     * The configured BIN -> bank mappings for the current tenant, so the page
     * can show what it resolves against. Read-only by design: see the class
     * javadoc for why there is no write endpoint here.
     */
    @GetMapping("/bins")
    public List<Map<String, Object>> listBins() {
        Long tenantId = requireTenant();
        return jdbcTemplate.queryForList(
                "SELECT bin, bank_name, source_file, loaded_at FROM ref_tenant_bin_bank " +
                "WHERE tenant_id = ? ORDER BY bank_name, bin", tenantId);
    }
}
