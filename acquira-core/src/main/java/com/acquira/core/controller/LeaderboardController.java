package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.core.service.LeaderboardService;
import com.acquira.core.service.LeaderboardService.Periods;
import com.acquira.core.service.LeaderboardService.Tier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

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
    public ResponseEntity<?> agents(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        return ResponseEntity.ok(leaderboardService.leaderboard(t, Tier.AGENTS,
            periods(t, period, dateFrom, dateTo)));
    }

    @GetMapping("/teams")
    public ResponseEntity<?> teams(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        return ResponseEntity.ok(leaderboardService.leaderboard(t, Tier.TEAMS,
            periods(t, period, dateFrom, dateTo)));
    }

    @GetMapping("/countries")
    public ResponseEntity<?> countries(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        return ResponseEntity.ok(leaderboardService.leaderboard(t, Tier.COUNTRIES,
            periods(t, period, dateFrom, dateTo)));
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        return ResponseEntity.ok(leaderboardService.overview(t,
            periods(t, period, dateFrom, dateTo)));
    }

    /** Path variable is the sales rep CODE (dim_merchant.sales_user_id). */
    @GetMapping("/agents/{salesUserId}")
    public ResponseEntity<?> agentDetail(
            @PathVariable String salesUserId,
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {
        Long t = tenantId();
        return ResponseEntity.ok(leaderboardService.agentDetail(t, salesUserId,
            periods(t, period, dateFrom, dateTo)));
    }
}
