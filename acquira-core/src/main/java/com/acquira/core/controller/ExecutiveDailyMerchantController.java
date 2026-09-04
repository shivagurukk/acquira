package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
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
 * Executive Daily Merchant Dashboard — single business date, per-(MID, SID)
 * rows with the full fee set (Vol, Count, MSF, ICF, SF, PG, NM) read from
 * sum_daily_full. Distinct from the month-grid DailyMerchantDashboardController
 * at /business/daily-dashboard, which stays as-is.
 *
 * NM comes from the stored total_net_revenue (batch-computed
 * MSF − interchange − scheme fee − ecom fee); this controller never recomputes
 * fees.
 */
@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
@PreAuthorize("@menuAccess.canAccess('/executive/daily-merchant')")
public class ExecutiveDailyMerchantController {

    private static final int MAX_PAGE_SIZE = 500;

    private final VolumeRevenueRepository volumeRevenueRepository;
    private final com.acquira.common.service.ReportCache reportCache;
    /** Serializes the filter DTO into a stable cache-key suffix. */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    /**
     * Warm the page's first-load requests: the calendar feed, then the table
     * for the latest loaded date with the frontend's initial (empty) filters.
     * The filter template mirrors DailyMerchantDashboard.jsx EMPTY_FILTERS —
     * empty ARRAYS, not nulls, or the serialized filter key won't match the
     * live request and the warm entry is never read.
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("executive-daily-merchant", tenantId -> {
            reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS,
                    "edmCalendar:" + tenantId + ":months", () -> {
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
                    "edmCalendar:" + tenantId + ":" + ym, () -> {
                        Map<String, Object> response = new HashMap<>();
                        List<LocalDate> ds = volumeRevenueRepository.getBusinessDatesInMonth(
                                tenantId, ym.atDay(1), ym.atEndOfMonth());
                        response.put("month", ym.toString());
                        response.put("dates", ds.stream().map(LocalDate::toString).toList());
                        return response;
                    });
            VolumeRevenueFilterDTO filter;
            try {
                filter = objectMapper.readValue(
                        "{\"mccList\":[],\"destinationList\":[],\"cardTypeList\":[],\"schemeList\":[],\"rmList\":[]}",
                        VolumeRevenueFilterDTO.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return;
            }
            String fk = filterKey(filter);
            if (fk == null) return;
            List<LocalDate> dates = List.of(latest);
            String key = "execDaily:" + tenantId + ":" + dates
                    + ":0:50:volume:desc:" + fk;
            reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA, key,
                    () -> buildDailyMerchants(filter, dates, null, null, null,
                            latest.toString(), null, "volume", "desc", 0, 50, false, tenantId, fk));
        });
    }

    /** Filter DTO as a key suffix, or null when it cannot be serialized (→ skip caching). */
    private String filterKey(VolumeRevenueFilterDTO filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * One round trip returns both the KPI totals and the table page, so the
     * strip and the grid can never disagree about what the filters selected.
     *
     * export=true returns the WHOLE filtered result set (no pagination) in the
     * requested sort order; the frontend builds the CSV (CeoVolumeRevenue
     * pattern: currency header, tenant precision, formula-injection guard).
     */
    /**
     * Date selection — exactly one of, in priority order:
     *   month=YYYY-MM        whole calendar month
     *   dates=d1&dates=d2    explicit list (IN semantics; also accepts one
     *                        comma-separated value)
     *   date=YYYY-MM-DD      single day
     *   (none)               the latest loaded business date
     */
    @PostMapping("/executive-daily-merchant")
    public ResponseEntity<Map<String, Object>> getDailyMerchants(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) List<String> dates,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "volume") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "false") boolean export,
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
                // Default: latest loaded date. A tenant with no data at all gets
                // an empty (not erroring) response.
                List<LocalDate> recent = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                if (recent.isEmpty()) {
                    Map<String, Object> empty = new HashMap<>();
                    empty.put("businessDate", null);
                    empty.put("selection", null);
                    empty.put("totals", Map.of("volume", 0, "count", 0, "msf", 0,
                            "icf", 0, "sf", 0, "pg", 0, "nm", 0));
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

        // Cache the common shapes only: exports return the full result set and
        // search keys would churn the small reportData cap per keystroke.
        String fk = (export || (search != null && !search.isBlank())) ? null : filterKey(filter);
        if (fk == null) {
            return ResponseEntity.ok(buildDailyMerchants(filter, dateList, rangeStart, rangeEnd,
                    month, selectionLabel, search, sort, dir, page, size, export, tenantId, null));
        }
        final VolumeRevenueFilterDTO f = filter;
        final List<LocalDate> fDates = dateList;
        final LocalDate fStart = rangeStart, fEnd = rangeEnd;
        final int fPage = page, fSize = size;
        final String fLabel = selectionLabel;
        // Key on the RESOLVED selection, never the raw month param: the branch
        // above tests month.isBlank() but a `month=` (empty, non-null) request
        // keyed on the raw value would collapse every date selection onto one
        // entry and serve the wrong day. rangeStart/rangeEnd are non-null iff
        // the month branch was taken; otherwise fDates (distinct+sorted, so its
        // toString is order-stable) identifies the selection exactly.
        String selection = fStart != null ? (fStart + ".." + fEnd) : String.valueOf(fDates);
        String key = "execDaily:" + tenantId + ":" + selection
                + ":" + fPage + ":" + fSize + ":" + sort + ":" + dir + ":" + fk;
        // Page/sort-independent parts (totals, trend, mix) get their own inner
        // cache keys (built from fk in buildDailyMerchants) so a page or sort
        // click only re-runs the table query.
        final String innerFk = fk;
        return ResponseEntity.ok(reportCache.get(
                com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA, key,
                () -> buildDailyMerchants(f, fDates, fStart, fEnd, month, fLabel,
                        search, sort, dir, fPage, fSize, false, tenantId, innerFk)));
    }

    /**
     * fk (the serialized filter DTO) keys the page/sort-independent parts
     * (totals, trend, mix) in CACHE_LOOKUPS — a different cache than the outer
     * CACHE_REPORT_DATA entry this method computes into, so the nested-compute
     * contract in ReportCache holds. Null fk (search / export / unserializable
     * filter) skips the inner caching entirely.
     */
    private Map<String, Object> buildDailyMerchants(VolumeRevenueFilterDTO filter,
            List<LocalDate> dateList, LocalDate rangeStart, LocalDate rangeEnd,
            String month, String selectionLabel, String search, String sort, String dir,
            int page, int size, boolean export, Long tenantId, String fk) {

        final String lookups = com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS;
        // Same resolved-selection identity as the outer cache key.
        final String selection = rangeStart != null
                ? (rangeStart + ".." + rangeEnd) : String.valueOf(dateList);

        Map<String, Object> pageResult = volumeRevenueRepository.getExecutiveDailyMerchant(
                filter, dateList, rangeStart, rangeEnd, search, sort, dir,
                export ? 0 : page, export ? -1 : size, tenantId);
        java.util.function.Supplier<Map<String, Object>> totalsLoader =
                () -> volumeRevenueRepository.getExecutiveDailyMerchantTotals(
                        filter, dateList, rangeStart, rangeEnd, search, tenantId);
        Map<String, Object> totals = fk == null ? totalsLoader.get()
                : reportCache.get(lookups,
                        "execDailyTotals:" + tenantId + ":" + selection + ":" + fk, totalsLoader);

        Map<String, Object> response = new HashMap<>();
        // Context data for the month ribbon + mix strips. Skipped on export —
        // the CSV carries rows only, so two extra scans would be pure waste.
        if (!export) {
            LocalDate ctxStart = rangeStart != null ? rangeStart
                    : dateList.get(0).withDayOfMonth(1);
            LocalDate ctxEnd = rangeEnd != null ? rangeEnd
                    : ctxStart.withDayOfMonth(ctxStart.lengthOfMonth());
            // The trend covers the whole ctx month regardless of which days are
            // selected, so it is keyed on the ctx window (plus filters), letting
            // every date selection inside one month share a single computation.
            java.util.function.Supplier<List<Map<String, Object>>> trendLoader =
                    () -> volumeRevenueRepository.getExecutiveDailyMerchantTrend(
                            filter, ctxStart, ctxEnd, search, tenantId);
            response.put("trend", fk == null ? trendLoader.get()
                    : reportCache.get(lookups,
                            "execDailyTrend:" + tenantId + ":" + ctxStart + ".." + ctxEnd + ":" + fk,
                            trendLoader));
            // ":all:" occupies the merchantId slot of the breakdown endpoint's
            // execDailyMix keys, so the two can never collide.
            java.util.function.Supplier<Map<String, List<Map<String, Object>>>> mixLoader =
                    () -> volumeRevenueRepository.getExecutiveDailyMerchantMix(
                            filter, dateList, rangeStart, rangeEnd, search, tenantId, null);
            response.put("mix", fk == null ? mixLoader.get()
                    : reportCache.get(lookups,
                            "execDailyMix:" + tenantId + ":all:" + selection + ":" + fk, mixLoader));
        }
        // businessDate kept for the single-date case (frontend pill highlight);
        // selection is the human-readable summary for every mode.
        response.put("businessDate",
                dateList != null && dateList.size() == 1 ? dateList.get(0).toString() : null);
        response.put("dates", dateList == null ? null
                : dateList.stream().map(LocalDate::toString).toList());
        response.put("month", month != null && !month.isBlank() ? month.trim() : null);
        response.put("selection", selectionLabel);
        response.put("totals", totals);
        response.put("content", pageResult.get("content"));
        response.put("totalElements", pageResult.get("totalElements"));
        response.put("page", export ? 0 : page);
        response.put("size", size);
        return response;
    }

    /**
     * Scheme / card-type / destination breakdown for ONE merchant over the same
     * date selection and filters — the drilldown drawer's detail. Read from the
     * same summary as the table, so the parts always sum to the row.
     */
    @PostMapping("/executive-daily-merchant/breakdown")
    public ResponseEntity<Map<String, Object>> getMerchantBreakdown(
            @RequestParam Long merchantId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) List<String> dates,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String search,
            @RequestBody(required = false) VolumeRevenueFilterDTO filter) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (filter == null) filter = new VolumeRevenueFilterDTO();

        List<LocalDate> dateList = null;
        LocalDate rangeStart = null, rangeEnd = null;
        try {
            if (month != null && !month.isBlank()) {
                java.time.YearMonth ym = java.time.YearMonth.parse(month.trim());
                rangeStart = ym.atDay(1);
                rangeEnd = ym.atEndOfMonth();
            } else if (dates != null && !dates.isEmpty()) {
                dateList = dates.stream()
                        .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(LocalDate::parse).distinct().sorted().toList();
                if (dateList.isEmpty()) return ResponseEntity.badRequest().build();
            } else if (date != null && !date.isBlank()) {
                dateList = List.of(LocalDate.parse(date.trim()));
            } else {
                List<LocalDate> recent = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                if (recent.isEmpty()) return ResponseEntity.ok(Map.of("mix", Map.of()));
                dateList = List.of(recent.get(0));
            }
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }

        String fk = (search != null && !search.isBlank()) ? null : filterKey(filter);
        final VolumeRevenueFilterDTO f = filter;
        final List<LocalDate> fDates = dateList;
        final LocalDate fStart = rangeStart, fEnd = rangeEnd;
        // Same resolved-selection keying rule as the main endpoint. Cached in
        // CACHE_LOOKUPS, not CACHE_REPORT_DATA: the mix is one small map per
        // merchant, and per-merchant keys in the tightly-capped report cache
        // would evict the genuinely expensive whole-report entries one grid
        // click at a time.
        String selection = fStart != null ? (fStart + ".." + fEnd) : String.valueOf(fDates);
        Map<String, List<Map<String, Object>>> mix = fk == null
                ? volumeRevenueRepository.getExecutiveDailyMerchantMix(
                        f, fDates, fStart, fEnd, search, tenantId, merchantId)
                : reportCache.get(
                        com.acquira.common.config.ReportCacheConfig.CACHE_LOOKUPS,
                        "execDailyMix:" + tenantId + ":" + merchantId + ":" + selection + ":" + fk,
                        () -> volumeRevenueRepository.getExecutiveDailyMerchantMix(
                                f, fDates, fStart, fEnd, search, tenantId, merchantId));

        Map<String, Object> response = new HashMap<>();
        response.put("merchantId", merchantId);
        response.put("mix", mix);
        return ResponseEntity.ok(response);
    }

    /** Latest loaded business dates for the date-pill row (default 5). */
    @GetMapping("/executive-daily-merchant/recent-dates")
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

    /**
     * Month-driven date picker feed. Without `month`: the months that hold data
     * (newest first) plus the latest loaded date — the page opens on the latest
     * month with the latest date pre-selected. With `month=YYYY-MM`: every
     * loaded business date inside that month, so the pill row always mirrors
     * what is actually loaded, never a synthetic calendar.
     */
    @GetMapping("/executive-daily-merchant/calendar")
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
                    "edmCalendar:" + tenantId + ":" + ym, () -> {
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
                "edmCalendar:" + tenantId + ":months", () -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("months", volumeRevenueRepository.getBusinessMonths(tenantId, 24));
                    List<LocalDate> latest = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
                    response.put("latest", latest.isEmpty() ? null : latest.get(0).toString());
                    return response;
                }));
    }
}
