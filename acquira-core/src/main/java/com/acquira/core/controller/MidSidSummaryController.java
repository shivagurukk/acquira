package com.acquira.core.controller;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.config.TenantContext;
import com.acquira.common.repository.MidSidSummaryRepository;
import com.acquira.common.service.ChannelSql;
import com.acquira.common.service.ReportCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Active MID / SID counts for the shared header strip that sits on every active
 * EXECUTIVE screen. One tenant-scoped endpoint, so the strip is identical
 * everywhere and each screen only has to pass the window (and channel) it is
 * currently showing.
 *
 * Gated at the category level — anyone who can open at least one EXECUTIVE
 * screen can read the aggregate counts — rather than to a single menu path,
 * because no one path backs a strip shown on nine of them.
 */
@RestController
@RequestMapping("/api/executive")
@RequiredArgsConstructor
@PreAuthorize("@menuAccess.canAccessCategory('EXECUTIVE')")
public class MidSidSummaryController {

    private final MidSidSummaryRepository repository;
    private final ReportCache reportCache;
    private final com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    /**
     * Warm the latest-month-to-date window for all three channel scopes — the
     * strip's default, and the window most executive pages open on. Pages
     * showing a different window still compute on first view (a cheap count).
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("mid-sid-summary", tenantId -> {
            for (String ch : new String[] {"ALL", "POS", "ECOM"}) {
                getSummary(null, null, ch);
            }
        });
    }

    /**
     * Distinct active MID and SID counts for a date window.
     *
     * from / to  — inclusive ISO dates. When either is missing the window falls
     *              back to the tenant's latest loaded month (month-to-date), the
     *              default the executive pages open on.
     * channel    — POS / ECOM / ALL (anything else = ALL).
     */
    @GetMapping("/mid-sid-summary")
    public ResponseEntity<Map<String, Object>> getSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "ALL") String channel) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        final String ch = ChannelSql.normalize(channel);

        LocalDate fromDate, toDate;
        try {
            fromDate = (from != null && !from.isBlank()) ? LocalDate.parse(from.trim()) : null;
            toDate = (to != null && !to.isBlank()) ? LocalDate.parse(to.trim()) : null;
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }

        // Missing / partial window → latest loaded month (MTD). A tenant with no
        // data at all gets zeroed counts rather than an error.
        if (fromDate == null || toDate == null) {
            LocalDate latest = repository.latestBusinessDate(tenantId);
            if (latest == null) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("mids", 0L);
                empty.put("sids", 0L);
                empty.put("from", null);
                empty.put("to", null);
                empty.put("channel", ch);
                return ResponseEntity.ok(empty);
            }
            if (toDate == null) toDate = latest;
            if (fromDate == null) fromDate = toDate.withDayOfMonth(1);
        }
        if (fromDate.isAfter(toDate)) {
            LocalDate swap = fromDate; fromDate = toDate; toDate = swap;
        }

        final LocalDate f = fromDate, t = toDate;
        String key = "midSidSummary:" + tenantId + ":" + f + ".." + t + ":ch" + ch;
        return ResponseEntity.ok(reportCache.get(ReportCacheConfig.CACHE_LOOKUPS, key,
                () -> repository.activeCounts(tenantId, f, t, ch)));
    }
}
