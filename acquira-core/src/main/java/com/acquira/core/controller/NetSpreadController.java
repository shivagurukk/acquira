package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.NetSpreadRepository;
import com.acquira.common.repository.VolumeRevenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Net Spread dashboard (/executive/net-spread) — a replica of the Executive
 * Daily Merchant Performance layout at MERCHANT grain, extended with the
 * ancillary revenue columns:
 *
 *   Net Spread = NM (MSF − ICF − SF − PG) + DCC acquirer share + rental income
 *
 * Reads sum_daily_merchant only (fee stack from the batch rebuild, ancillary
 * from AncillarySql) — never recomputes fees and never joins facts. A
 * merchant negative on NM but non-negative on spread is flagged "rescued";
 * the totals carry lossOnMargin / rescued / lossOnSpread counts so the page
 * can tell that story explicitly. dcc_merchant is returned as an
 * informational column and never added to the spread.
 *
 * Date-selection contract, caching and endpoint shapes deliberately mirror
 * ExecutiveDailyMerchantController so the cloned frontend stays thin. The
 * date pickers reuse VolumeRevenueRepository's business-date feeds — the
 * months gate on LOADED TRANSACTION days, so an ancillary-only month never
 * shows up as a selectable (and misleading, 100%-ancillary) period.
 */
@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
@PreAuthorize("@menuAccess.canAccess('/executive/net-spread')")
public class NetSpreadController {

    private static final int MAX_PAGE_SIZE = 500;

    private final NetSpreadRepository netSpreadRepository;
    private final VolumeRevenueRepository volumeRevenueRepository;
    private final com.acquira.common.service.ReportCache reportCache;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    private String filterKey(VolumeRevenueFilterDTO filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Warm the page's first-load requests: the calendar feed, then the table
     * for the latest loaded date. The frontend's initial POST body is a bare
     * {} (all-null DTO) — see NetSpreadDashboard.jsx load().
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("net-spread", tenantId -> {
            reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS,
                    "nsCalendar:" + tenantId + ":months", () -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("months", volumeRevenueRepository.getBusinessMonths(tenantId, 24));
                        List<LocalDate> l = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                        response.put("latest", l.isEmpty() ? null : l.get(0).toString());
                        return response;
                    });
            List<LocalDate> recent = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
            if (recent.isEmpty()) return;
            LocalDate latest = recent.get(0);
            java.time.YearMonth ym = java.time.YearMonth.from(latest);
            reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS,
                    "nsCalendar:" + tenantId + ":" + ym, () -> {
                        Map<String, Object> response = new HashMap<>();
                        List<LocalDate> ds = volumeRevenueRepository.getBusinessDatesInMonth(
                                tenantId, ym.atDay(1), ym.atEndOfMonth());
                        response.put("month", ym.toString());
                        response.put("dates", ds.stream().map(LocalDate::toString).toList());
                        return response;
                    });
            VolumeRevenueFilterDTO filter = new VolumeRevenueFilterDTO();
            String fk = filterKey(filter);
            if (fk == null) return;
            List<LocalDate> dates = List.of(latest);
            String key = "netSpread:" + tenantId + ":" + dates
                    + ":0:50:spread:desc:false:" + fk;
            reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA, key,
                    () -> buildNetSpread(filter, dates, null, null, null,
                            latest.toString(), null, "spread", "desc", 0, 50, false, false, tenantId));
        });
    }

    /**
     * One round trip: totals + table page + trend. Date selection — exactly
     * one of month=YYYY-MM / dates=... / date=YYYY-MM-DD / (none = latest
     * loaded business date). export=true returns the whole filtered result
     * set unpaginated; the frontend builds the CSV.
     */
    @PostMapping("/net-spread")
    public ResponseEntity<Map<String, Object>> getNetSpread(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) List<String> dates,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "spread") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "false") boolean export,
            @RequestParam(defaultValue = "false") boolean lossOnly,
            @RequestParam(required = false) String search,
            @RequestBody(required = false) VolumeRevenueFilterDTO filter) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new VolumeRevenueFilterDTO();

        List<LocalDate> dateList = null;
        LocalDate rangeStart = null, rangeEnd = null;
        String selectionLabel;
        try {
            if (month != null && !month.isBlank()) {
                java.time.YearMonth ym = java.time.YearMonth.parse(month.trim());
                rangeStart = ym.atDay(1);
                rangeEnd = ym.atEndOfMonth();
                selectionLabel = month.trim();
            } else if (dates != null && !dates.isEmpty()) {
                dateList = dates.stream()
                        .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(LocalDate::parse).distinct().sorted().toList();
                if (dateList.isEmpty()) return ResponseEntity.badRequest().build();
                selectionLabel = dateList.size() == 1 ? dateList.get(0).toString()
                        : dateList.size() + " dates";
            } else if (date != null && !date.isBlank()) {
                dateList = List.of(LocalDate.parse(date.trim()));
                selectionLabel = date.trim();
            } else {
                List<LocalDate> recent = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                if (recent.isEmpty()) {
                    Map<String, Object> empty = new HashMap<>();
                    empty.put("businessDate", null);
                    empty.put("selection", null);
                    Map<String, Object> emptyTotals = new HashMap<>();
                    for (String k : List.of("volume", "count", "msf", "icf", "sf", "pg", "nm",
                            "dcc", "dccMerchant", "rental", "spread",
                            "lossOnMargin", "rescued", "lossOnSpread", "merchants")) {
                        emptyTotals.put(k, 0);
                    }
                    empty.put("totals", emptyTotals);
                    empty.put("content", List.of());
                    empty.put("totalElements", 0);
                    empty.put("page", 0);
                    empty.put("size", size);
                    return ResponseEntity.ok(empty);
                }
                dateList = List.of(recent.get(0));
                selectionLabel = recent.get(0).toString();
            }
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }

        if (page < 0) page = 0;
        if (size <= 0) size = 50;
        if (size > MAX_PAGE_SIZE) size = MAX_PAGE_SIZE;

        String fk = (export || (search != null && !search.isBlank())) ? null : filterKey(filter);
        if (fk == null) {
            return ResponseEntity.ok(buildNetSpread(filter, dateList, rangeStart, rangeEnd,
                    month, selectionLabel, search, sort, dir, page, size, export, lossOnly, tenantId));
        }
        final VolumeRevenueFilterDTO f = filter;
        final List<LocalDate> fDates = dateList;
        final LocalDate fStart = rangeStart, fEnd = rangeEnd;
        final int fPage = page, fSize = size;
        final boolean fLossOnly = lossOnly;
        final String fLabel = selectionLabel;
        // Keyed on the RESOLVED selection (same rule as the exec daily page):
        // an empty `month=` param must never collapse distinct selections.
        String selection = fStart != null ? (fStart + ".." + fEnd) : String.valueOf(fDates);
        String key = "netSpread:" + tenantId + ":" + selection
                + ":" + fPage + ":" + fSize + ":" + sort + ":" + dir + ":" + fLossOnly + ":" + fk;
        return ResponseEntity.ok(reportCache.get(
                com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> buildNetSpread(f, fDates, fStart, fEnd, month, fLabel,
                        search, sort, dir, fPage, fSize, false, fLossOnly, tenantId)));
    }

    private Map<String, Object> buildNetSpread(VolumeRevenueFilterDTO filter,
            List<LocalDate> dateList, LocalDate rangeStart, LocalDate rangeEnd,
            String month, String selectionLabel, String search, String sort, String dir,
            int page, int size, boolean export, boolean lossOnly, Long tenantId) {

        Map<String, Object> pageResult = netSpreadRepository.getMerchants(
                tenantId, dateList, rangeStart, rangeEnd, search,
                filter.getMidList(), filter.getSidList(), filter.getMerchantName(),
                sort, dir, export ? 0 : page, export ? -1 : size);
        Map<String, Object> totals = netSpreadRepository.getTotals(
                tenantId, dateList, rangeStart, rangeEnd, search,
                filter.getMidList(), filter.getSidList(), filter.getMerchantName());

        // lossOnly (the rescued-merchants lens): keep merchants negative on NM,
        // applied AFTER aggregation. Page-local filtering would break the row
        // count, so re-query unpaginated when the lens is on — the result set
        // shrinks to the loss cohort, which is small by construction.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) pageResult.get("content");
        Object totalElements = pageResult.get("totalElements");
        if (lossOnly) {
            Map<String, Object> full = netSpreadRepository.getMerchants(
                    tenantId, dateList, rangeStart, rangeEnd, search,
                    filter.getMidList(), filter.getSidList(), filter.getMerchantName(),
                    sort, dir, 0, -1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> all = (List<Map<String, Object>>) full.get("content");
            List<Map<String, Object>> losers = all.stream()
                    .filter(r -> r.get("nm") instanceof java.math.BigDecimal nm && nm.signum() < 0)
                    .toList();
            totalElements = (long) losers.size();
            content = export ? losers
                    : losers.stream().skip((long) page * size).limit(size).toList();
        }

        Map<String, Object> response = new HashMap<>();
        if (!export) {
            LocalDate ctxStart = rangeStart != null ? rangeStart
                    : dateList.get(0).withDayOfMonth(1);
            LocalDate ctxEnd = rangeEnd != null ? rangeEnd
                    : ctxStart.withDayOfMonth(ctxStart.lengthOfMonth());
            response.put("trend", netSpreadRepository.getTrend(
                    tenantId, ctxStart, ctxEnd, search,
                    filter.getMidList(), filter.getSidList(), filter.getMerchantName()));
        }
        response.put("businessDate",
                dateList != null && dateList.size() == 1 ? dateList.get(0).toString() : null);
        response.put("dates", dateList == null ? null
                : dateList.stream().map(LocalDate::toString).toList());
        response.put("month", month != null && !month.isBlank() ? month.trim() : null);
        response.put("selection", selectionLabel);
        response.put("totals", totals);
        response.put("content", content);
        response.put("totalElements", totalElements);
        response.put("page", export ? 0 : page);
        response.put("size", size);
        return response;
    }

    /** Latest loaded business dates for the date-pill row (default 5). */
    @GetMapping("/net-spread/recent-dates")
    public ResponseEntity<Map<String, Object>> getRecentDates(
            @RequestParam(defaultValue = "5") int limit) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (limit < 1) limit = 1;
        if (limit > 30) limit = 30;
        List<LocalDate> dates = volumeRevenueRepository.getRecentBusinessDates(tenantId, limit);
        Map<String, Object> response = new HashMap<>();
        response.put("dates", dates.stream().map(LocalDate::toString).toList());
        return ResponseEntity.ok(response);
    }

    /** Month-driven date picker feed — same contract as the exec daily page. */
    @GetMapping("/net-spread/calendar")
    public ResponseEntity<Map<String, Object>> getCalendar(
            @RequestParam(required = false) String month) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        if (month != null && !month.isBlank()) {
            java.time.YearMonth ym;
            try {
                ym = java.time.YearMonth.parse(month.trim());
            } catch (java.time.format.DateTimeParseException e) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(reportCache.get(
                    com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS,
                    "nsCalendar:" + tenantId + ":" + ym, () -> {
                        Map<String, Object> response = new HashMap<>();
                        List<LocalDate> dates = volumeRevenueRepository.getBusinessDatesInMonth(
                                tenantId, ym.atDay(1), ym.atEndOfMonth());
                        response.put("month", ym.toString());
                        response.put("dates", dates.stream().map(LocalDate::toString).toList());
                        return response;
                    }));
        }
        return ResponseEntity.ok(reportCache.get(
                com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS,
                "nsCalendar:" + tenantId + ":months", () -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("months", volumeRevenueRepository.getBusinessMonths(tenantId, 24));
                    List<LocalDate> latest = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                    response.put("latest", latest.isEmpty() ? null : latest.get(0).toString());
                    return response;
                }));
    }

    /**
     * DCC feed status for the page's ingest strip: exceptions from the latest
     * load (stg_dcc_revenue_raw keeps the latest load's rows per tenant) plus
     * fact coverage bounds.
     */
    @GetMapping("/net-spread/dcc-status")
    public ResponseEntity<Map<String, Object>> getDccStatus() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        Map<String, Object> exceptions = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected, "
            + "COUNT(*) FILTER (WHERE status = 'UNMATCHED') AS unmatched, "
            + "COUNT(*) FILTER (WHERE status = 'PROCESSED') AS processed, "
            + "MAX(load_time) AS last_load_time "
            + "FROM stg_dcc_revenue_raw WHERE tenant_id = ?", tenantId);

        Map<String, Object> bounds = jdbcTemplate.queryForMap(
            "SELECT MIN(payment_date) AS min_date, MAX(payment_date) AS max_date, "
            + "COUNT(*) AS fact_rows "
            + "FROM fact_dcc_revenue WHERE tenant_id = ?", tenantId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT raw_id, status, error_message, sid, file_tenant_id, "
            + "merchant_share, acquirer_share, payment_date, load_time "
            + "FROM stg_dcc_revenue_raw WHERE tenant_id = ? AND status IN ('REJECTED','UNMATCHED') "
            + "ORDER BY status, raw_id LIMIT 500", tenantId);

        Map<String, Object> response = new HashMap<>();
        response.put("exceptions", exceptions);
        response.put("bounds", bounds);
        response.put("rows", rows);
        return ResponseEntity.ok(response);
    }
}
