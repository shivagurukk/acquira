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

        Map<String, Object> pageResult = volumeRevenueRepository.getExecutiveDailyMerchant(
                filter, dateList, rangeStart, rangeEnd, search, sort, dir,
                export ? 0 : page, export ? -1 : size, tenantId);
        Map<String, Object> totals = volumeRevenueRepository.getExecutiveDailyMerchantTotals(
                filter, dateList, rangeStart, rangeEnd, search, tenantId);

        Map<String, Object> response = new HashMap<>();
        // Context data for the month ribbon + mix strips. Skipped on export —
        // the CSV carries rows only, so two extra scans would be pure waste.
        if (!export) {
            LocalDate ctxStart = rangeStart != null ? rangeStart
                    : dateList.get(0).withDayOfMonth(1);
            LocalDate ctxEnd = rangeEnd != null ? rangeEnd
                    : ctxStart.withDayOfMonth(ctxStart.lengthOfMonth());
            response.put("trend", volumeRevenueRepository.getExecutiveDailyMerchantTrend(
                    filter, ctxStart, ctxEnd, search, tenantId));
            response.put("mix", volumeRevenueRepository.getExecutiveDailyMerchantMix(
                    filter, dateList, rangeStart, rangeEnd, search, tenantId, null));
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
        return ResponseEntity.ok(response);
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

        Map<String, Object> response = new HashMap<>();
        response.put("merchantId", merchantId);
        response.put("mix", volumeRevenueRepository.getExecutiveDailyMerchantMix(
                filter, dateList, rangeStart, rangeEnd, search, tenantId, merchantId));
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

        Map<String, Object> response = new HashMap<>();
        if (month != null && !month.isBlank()) {
            java.time.YearMonth ym;
            try {
                ym = java.time.YearMonth.parse(month.trim());
            } catch (java.time.format.DateTimeParseException e) {
                return ResponseEntity.badRequest().build();
            }
            List<LocalDate> dates = volumeRevenueRepository.getBusinessDatesInMonth(
                    tenantId, ym.atDay(1), ym.atEndOfMonth());
            response.put("month", ym.toString());
            response.put("dates", dates.stream().map(LocalDate::toString).toList());
        } else {
            response.put("months", volumeRevenueRepository.getBusinessMonths(tenantId, 24));
            List<LocalDate> latest = volumeRevenueRepository.getRecentBusinessDates(tenantId, 1);
            response.put("latest", latest.isEmpty() ? null : latest.get(0).toString());
        }
        return ResponseEntity.ok(response);
    }
}
