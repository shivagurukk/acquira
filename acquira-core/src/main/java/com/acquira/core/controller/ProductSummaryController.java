package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.config.TenantContext;
import com.acquira.common.repository.ProductSummaryRepository;
import com.acquira.common.repository.VolumeRevenueRepository;
import com.acquira.common.service.NetSpreadSql;
import com.acquira.common.service.ReportCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Product Summary dashboard (/executive/product-summary) — a per-tenant P&amp;L
 * that breaks the book down by revenue PRODUCT rather than by merchant:
 * Acquiring · POS, Acquiring · ECOM, DCC, Rental and (when the tenant's
 * netspread.fx_enabled flag is on) FX income, each with its volume, MSF,
 * interchange, scheme fee, net margin and net spread, closed by a TOTAL row.
 *
 * Current-tenant scoped (like the other executive pages): reads
 * TenantContext.getCurrentTenant() and 403s if null. All figures come from the
 * summary layer (sum_daily_full for the channel split, sum_daily_merchant for
 * the ancillary legs) — never re-priced, never joined to facts — so the page
 * ties out with the channel selector and the Net Spread dashboard.
 *
 * Date selection mirrors NetSpreadController (month=YYYY-MM / dates=... /
 * date=YYYY-MM-DD), but DEFAULTS to the latest loaded business MONTH rather
 * than the latest day — a single-day product P&amp;L is rarely what a summary
 * reader wants. Result cached in ReportCache keyed on tenant + resolved range
 * + fx flag.
 */
@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
@PreAuthorize("@menuAccess.canAccess('/executive/product-summary')")
public class ProductSummaryController {

    private final ProductSummaryRepository productSummaryRepository;
    private final VolumeRevenueRepository volumeRevenueRepository;
    private final ReportCache reportCache;
    private final JdbcTemplate jdbcTemplate;

    private final com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    private boolean fxEnabled(Long tenantId) {
        return NetSpreadSql.fxEnabled(jdbcTemplate, tenantId);
    }

    /**
     * Warm the page's first-load requests: the calendar, then the latest
     * loaded month — the frontend sends month=latest.slice(0,7), so the key
     * and the cached "month"/"selection" fields match that request exactly.
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("product-summary", tenantId -> {
            getCalendar();
            List<LocalDate> recent = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
            if (recent.isEmpty()) return;
            YearMonth ym = YearMonth.from(recent.get(0));
            getProductSummary(null, null, ym.toString());
        });
    }

    @PostMapping("/product-summary")
    public ResponseEntity<Map<String, Object>> getProductSummary(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) List<String> dates,
            @RequestParam(required = false) String month) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        LocalDate start, end;
        String selectionLabel;
        try {
            if (month != null && !month.isBlank()) {
                YearMonth ym = YearMonth.parse(month.trim());
                start = ym.atDay(1);
                end = ym.atEndOfMonth();
                selectionLabel = month.trim();
            } else if (dates != null && !dates.isEmpty()) {
                List<LocalDate> list = dates.stream()
                        .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(LocalDate::parse).distinct().sorted().toList();
                if (list.isEmpty()) return ResponseEntity.badRequest().build();
                start = list.get(0);
                end = list.get(list.size() - 1);
                selectionLabel = list.size() == 1 ? list.get(0).toString()
                        : list.get(0) + " … " + list.get(list.size() - 1);
            } else if (date != null && !date.isBlank()) {
                start = end = LocalDate.parse(date.trim());
                selectionLabel = date.trim();
            } else {
                List<LocalDate> recent = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                if (recent.isEmpty()) return ResponseEntity.ok(emptyResponse(tenantId));
                YearMonth ym = YearMonth.from(recent.get(0));
                start = ym.atDay(1);
                end = ym.atEndOfMonth();
                selectionLabel = ym.toString();
            }
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }

        boolean fx = fxEnabled(tenantId);
        final LocalDate fStart = start, fEnd = end;
        final String fLabel = selectionLabel;
        String key = "productSummary:" + tenantId + ":" + start + ".." + end + ":fx" + fx;
        return ResponseEntity.ok(reportCache.get(
                ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> build(tenantId, fStart, fEnd, fLabel, month, fx)));
    }

    private Map<String, Object> build(Long tenantId, LocalDate start, LocalDate end,
            String selectionLabel, String month, boolean fxEnabled) {
        Map<String, Object> summary = productSummaryRepository.getSummary(tenantId, start, end, fxEnabled);
        Map<String, Object> response = new HashMap<>(summary);
        response.put("fxEnabled", fxEnabled);
        response.put("selection", selectionLabel);
        response.put("month", month != null && !month.isBlank() ? month.trim() : null);
        response.put("start", start.toString());
        response.put("end", end.toString());
        return response;
    }

    private Map<String, Object> emptyResponse(Long tenantId) {
        Map<String, Object> r = new HashMap<>();
        r.put("rows", List.of());
        r.put("totals", Map.of());
        r.put("fxEnabled", fxEnabled(tenantId));
        r.put("selection", null);
        return r;
    }

    /** Month list (+ latest date) for the period picker — same feed as Net Spread. */
    @GetMapping("/product-summary/calendar")
    public ResponseEntity<Map<String, Object>> getCalendar() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(reportCache.get(ReportCacheConfig.CACHE_LOOKUPS,
                "psCalendar:" + tenantId + ":months", () -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("months", volumeRevenueRepository.getBusinessMonths(tenantId, 24));
                    List<LocalDate> latest = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                    response.put("latest", latest.isEmpty() ? null : latest.get(0).toString());
                    return response;
                }));
    }
}
