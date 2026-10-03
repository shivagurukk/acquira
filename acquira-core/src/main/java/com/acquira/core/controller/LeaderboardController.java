package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.core.service.LeaderboardService;
import com.acquira.core.service.LeaderboardService.Periods;
import com.acquira.core.service.LeaderboardService.Tier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Leaderboard & Gamification API — thin HTTP layer.
 *
 * All ranking/period/badge logic lives in {@link LeaderboardService}. Periods
 * are resolved against the tenant's latest business_date (data-anchored), so
 * MTD/QTD/YTD stay meaningful when transaction data lags real time. Explicit
 * dateFrom/dateTo override the period keyword.
 */
@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("@menuAccess.canAccess('/sales/leaderboard')")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;
    private final com.acquira.common.service.ReportCache reportCache;
    private final com.acquira.common.service.ReportCacheWarmup reportCacheWarmup;

    /**
     * Warm the page's first load: SalesLeaderboard.jsx fetches overview +
     * agents + teams + countries with period=MTD and no explicit dates.
     */
    @jakarta.annotation.PostConstruct
    void registerWarmer() {
        reportCacheWarmup.register("leaderboard", tenantId -> {
            overview("MTD", "", "");
            agents("MTD", "", "");
            teams("MTD", "", "");
            countries("MTD", "", "");
        });
    }

    /**
     * Cached tier board. The key carries the RESOLVED Periods (window,
     * comparison window and the data anchor), so a new latest business_date
     * or a calendar-relative fallback never serves an old window.
     */
    private Object board(Long t, Tier tier, Periods p) {
        return reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA,
            "lbBoard:" + t + ":" + tier + ":" + p,
            () -> leaderboardService.leaderboard(t, tier, p));
    }

    private Long tenantId() {
        Long t = TenantContext.getCurrentTenant();
        if (t == null) throw new RuntimeException("No tenant context");
        return t;
    }

    private Periods periods(Long tenantId, String period, String dateFrom, String dateTo) {
        return leaderboardService.resolvePeriods(period, dateFrom, dateTo,
            leaderboardService.resolveAnchor(tenantId));
    }

    @GetMapping("/agents")
    @com.acquira.common.config.ReportResponse
    public ResponseEntity<?> agents(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        return ResponseEntity.ok(board(t, Tier.AGENTS, periods(t, period, dateFrom, dateTo)));
    }

    @GetMapping("/teams")
    @com.acquira.common.config.ReportResponse
    public ResponseEntity<?> teams(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        return ResponseEntity.ok(board(t, Tier.TEAMS, periods(t, period, dateFrom, dateTo)));
    }

    // Also called from the sales hierarchy screen, so either grant passes.
    @PreAuthorize("@menuAccess.canAccess('/sales/leaderboard') or @menuAccess.canAccess('/sales/hierarchy')")
    @GetMapping("/countries")
    @com.acquira.common.config.ReportResponse
    public ResponseEntity<?> countries(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        return ResponseEntity.ok(board(t, Tier.COUNTRIES, periods(t, period, dateFrom, dateTo)));
    }

    @GetMapping("/overview")
    @com.acquira.common.config.ReportResponse
    public ResponseEntity<?> overview(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        Periods p = periods(t, period, dateFrom, dateTo);
        return ResponseEntity.ok(reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA,
            "lbOverview:" + t + ":" + p,
            () -> leaderboardService.overview(t, p)));
    }

    /** Path variable is the sales rep CODE (dim_merchant.sales_user_id). */
    @GetMapping("/agents/{salesUserId}")
    @com.acquira.common.config.ReportResponse
    public ResponseEntity<?> agentDetail(
            @PathVariable String salesUserId,
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        Periods p = periods(t, period, dateFrom, dateTo);
        return ResponseEntity.ok(reportCache.get(com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA,
            "lbAgentDetail:" + t + ":" + salesUserId + ":" + p,
            () -> leaderboardService.agentDetail(t, salesUserId, p)));
    }
}
