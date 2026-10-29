package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.config.TenantContext;
import com.acquira.common.repository.ProfitabilityReportRepository;
import com.acquira.common.repository.VolumeRevenueRepository;
import com.acquira.common.service.ReportCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Profitability Report (/finance/profitability-report) — the finance team's
 * monthly MID P&amp;L at Month x MID x Merchant Type x MCC Category x
 * Destination x Scheme x Card Interchange. See
 * {@link ProfitabilityReportRepository} for the column definitions.
 *
 * Current-tenant scoped (TenantContext; 403 when absent). One calendar month
 * per call (month=YYYY-MM, default = the latest loaded business month).
 * Cached in ReportCache keyed on tenant + month + filters; every
 * adjustment-rate write evicts the report cache so the next read reprices.
 *
 * Rate maintenance (PIE rebate, KFCA fee / margin, IC++ billing) needs the
 * screen's EDIT bit on top of VIEW.
 */
@RestController
@RequestMapping("/api/finance/profitability")
@RequiredArgsConstructor
@PreAuthorize("@menuAccess.canAccess('/finance/profitability-report')")
public class ProfitabilityReportController {

    private final ProfitabilityReportRepository repository;
    private final VolumeRevenueRepository volumeRevenueRepository;
    private final ReportCache reportCache;
    private final CurrencyMeta currencyMeta;

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }

    /** Month list (+ latest date) for the period picker — same feed as Product Summary. */
    @GetMapping("/calendar")
    public ResponseEntity<Map<String, Object>> getCalendar() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(reportCache.get(ReportCacheConfig.CACHE_LOOKUPS,
                "profitCalendar:" + tenantId + ":months", () -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("months", volumeRevenueRepository.getBusinessMonths(tenantId, 36));
                    List<LocalDate> latest = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                    response.put("latest", latest.isEmpty() ? null : latest.get(0).toString());
                    return response;
                }));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> getReport(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> mids) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        YearMonth ym;
        try {
            if (month != null && !month.isBlank()) {
                ym = YearMonth.parse(month.trim());
            } else {
                List<LocalDate> recent = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                if (recent.isEmpty()) {
                    Map<String, Object> empty = new HashMap<>();
                    empty.put("rows", List.of());
                    empty.put("totals", Map.of());
                    empty.put("month", null);
                    return ResponseEntity.ok(currencyMeta.attach(empty));
                }
                ym = YearMonth.from(recent.get(0));
            }
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }

        List<String> midList = mids == null ? null : mids.stream()
                .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().sorted().toList();
        String s = search == null ? "" : search.trim();
        final YearMonth fym = ym;
        String key = "profitability:" + tenantId + ":" + ym + ":" + s + ":" + (midList == null ? "" : midList);
        Map<String, Object> body = reportCache.get(ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> repository.getReport(tenantId, fym, s, midList));
        return ResponseEntity.ok(currencyMeta.attach(new HashMap<>(body)));
    }

    // ------------------------------------------------------------------
    // Adjustment rates
    // ------------------------------------------------------------------

    @GetMapping("/adjustments")
    public ResponseEntity<Map<String, Object>> listAdjustments() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        Map<String, Object> r = new HashMap<>();
        r.put("codes", ProfitabilityReportRepository.ADJ_CODES);
        r.put("rows", repository.listAdjustments(tenantId));
        return ResponseEntity.ok(r);
    }

    public record AdjustmentRequest(Long id, String mid, String code, BigDecimal pctOfVolume, BigDecimal pctOfMsf,
                                    BigDecimal perTxn, LocalDate effectiveFrom, LocalDate effectiveTo, String note) {}

    @PutMapping("/adjustments")
    @PreAuthorize("@perm.can('finance.profitability-report', 'EDIT')")
    public ResponseEntity<Map<String, Object>> saveAdjustment(@RequestBody AdjustmentRequest req) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (req == null || req.code() == null || !ProfitabilityReportRepository.ADJ_CODES.contains(req.code())) {
            return ResponseEntity.badRequest().body(Map.of("message", "code must be one of " + ProfitabilityReportRepository.ADJ_CODES));
        }
        if (req.effectiveFrom() != null && req.effectiveTo() != null && req.effectiveTo().isBefore(req.effectiveFrom())) {
            return ResponseEntity.badRequest().body(Map.of("message", "effectiveTo is before effectiveFrom"));
        }
        long id = repository.upsertAdjustment(tenantId, req.id(), req.mid(), req.code(), req.pctOfVolume(),
                req.pctOfMsf(), req.perTxn(), req.effectiveFrom(), req.effectiveTo(), req.note(), currentUser());
        reportCache.evictAll();
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/adjustments/{id}")
    @PreAuthorize("@perm.can('finance.profitability-report', 'EDIT')")
    public ResponseEntity<Map<String, Object>> deleteAdjustment(@PathVariable long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        int n = repository.deleteAdjustment(tenantId, id);
        if (n == 0) return ResponseEntity.notFound().build();
        reportCache.evictAll();
        return ResponseEntity.ok(Map.of("deleted", n));
    }
}
