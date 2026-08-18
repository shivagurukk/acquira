package com.acquira.core.controller;

import com.acquira.common.model.SalesAgentProfile;
import com.acquira.common.model.SalesCountryLead;
import com.acquira.common.model.SalesTeamMapping;
import com.acquira.common.model.SalesUserAssignment;
import com.acquira.common.repository.SalesAgentProfileRepository;
import com.acquira.common.repository.SalesCountryLeadRepository;
import com.acquira.common.repository.SalesTeamMappingRepository;
import com.acquira.common.repository.SalesUserAssignmentRepository;
import com.acquira.core.service.LeaderboardService;
import com.acquira.core.service.SalesPulseProperties;
import com.acquira.core.service.SalesPulseService;
import com.acquira.core.service.SalesPulseService.Dependency;
import com.acquira.core.service.SalesPulseService.Momentum;
import com.acquira.core.service.SalesPulseService.MomentumWindow;
import com.acquira.core.service.SalesPulseService.WindowSales;
import com.acquira.core.service.SalesTargetResolver;
import com.acquira.core.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Executive Sales Pulse — the C-level read of the sales organisation.
 *
 * <p>One call returns everything the page shows: four summary figures, a
 * generated insight sentence, and every Team Lead with their sales executives
 * underneath, each carrying sales, growth, team contribution, target attainment,
 * momentum and signals.
 *
 * <h3>Deliberately not a CRM view</h3>
 * There is no pipeline, no deal, no opportunity stage anywhere in this response,
 * because there is no such data in Acquira and inventing a proxy for it would be
 * worse than omitting it. "Sales" is realised net margin from ingested merchant
 * activity — the same measure the Leaderboard and Sales Portfolio screens rank
 * on, so the three pages cannot disagree about who is top.
 *
 * <h3>Targets are optional and additive</h3>
 * The page ships with {@code sales_agent_target} empty. Every target cell then
 * renders "—", and momentum, growth and signals are unaffected: they are computed
 * from history alone. A target, once entered, adds a Target column value and — only
 * then — can contribute to an ATTENTION classification. A missing target never
 * counts against anyone.
 *
 * <h3>Periods</h3>
 * Period keywords resolve against the tenant's latest {@code business_date} via
 * {@link LeaderboardService}, not the wall clock, so "this month" means
 * month-to-date OF THE DATA. With ingestion running behind, a calendar-anchored
 * page would show an empty or half-empty month and read as a collapse in sales.
 *
 * <h3>Cost</h3>
 * Four queries for the whole organisation: current+previous window, monthly
 * series, targets, and the (small) hierarchy reads. Independent of headcount.
 */
@RestController
@RequestMapping("/api/executive/sales-pulse")
@RequiredArgsConstructor
@PreAuthorize("@menuAccess.canAccess('/executive/sales')")
public class SalesPulseController {

    private static final String UNASSIGNED_TEAM = "Unassigned";

    private final SalesPulseService pulseService;
    private final SalesPulseProperties props;
    private final LeaderboardService leaderboardService;
    private final SalesTargetResolver targetResolver;
    private final SalesAgentProfileRepository profileRepository;
    private final SalesUserAssignmentRepository assignmentRepository;
    private final SalesTeamMappingRepository teamMappingRepository;
    private final SalesCountryLeadRepository countryLeadRepository;
    private final TenantService tenantService;
    private final CurrencyMeta currencyMeta;

    private Long getTenantId() {
        Long t = tenantService.getCurrentTenantId();
        if (t == null) throw new IllegalStateException("No tenant context");
        return t;
    }

    // ═══════════════════════════════════════════════════════════
    //  MAIN
    // ═══════════════════════════════════════════════════════════

    @GetMapping
    public ResponseEntity<?> pulse(
            @RequestParam(defaultValue = "MTD") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo,
            @RequestParam(required = false) Long teamLeadId,
            @RequestParam(required = false) Long countryLeadId,
            @RequestParam(defaultValue = SalesTargetResolver.DEFAULT_METRIC) String targetMetric) {

        Long tenantId = getTenantId();
        return ResponseEntity.ok(currencyMeta.attach(
                build(tenantId, period, dateFrom, dateTo, teamLeadId, countryLeadId, targetMetric), tenantId));
    }

    /**
     * One sales executive's detail, for the drawer.
     *
     * <p>Runs the SAME assembly as the main call and extracts one row rather than
     * recomputing from a second code path. That costs four queries instead of one,
     * and buys the guarantee that the drawer can never disagree with the row the
     * user clicked — a class of bug that is invisible in testing and corrosive in
     * front of an executive.
     */
    @GetMapping("/agent/{salesUserId}")
    public ResponseEntity<?> agent(
            @PathVariable String salesUserId,
            @RequestParam(defaultValue = "MTD") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo,
            @RequestParam(defaultValue = SalesTargetResolver.DEFAULT_METRIC) String targetMetric) {

        Long tenantId = getTenantId();
        Map<String, Object> full = build(tenantId, period, dateFrom, dateTo, null, null, targetMetric);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> teams = (List<Map<String, Object>>) full.get("teams");
        for (Map<String, Object> team : teams) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) team.get("salesExecutives");
            for (Map<String, Object> m : members) {
                if (salesUserId.equals(m.get("id"))) {
                    Map<String, Object> out = new LinkedHashMap<>(m);
                    out.put("teamLeadId", team.get("teamLeadId"));
                    out.put("teamLeadName", team.get("teamLeadName"));
                    out.put("teamSales", team.get("teamSales"));
                    out.put("period", full.get("period"));
                    out.put("momentumWindow", full.get("momentumWindow"));
                    out.put("dataThrough", full.get("dataThrough"));
                    return ResponseEntity.ok(currencyMeta.attach(out, tenantId));
                }
            }
        }
        return ResponseEntity.status(404).body(Map.of("error", "Sales executive not found: " + salesUserId));
    }

    // ═══════════════════════════════════════════════════════════
    //  ASSEMBLY
    // ═══════════════════════════════════════════════════════════

    private Map<String, Object> build(Long tenantId, String period, String dateFrom, String dateTo,
                                      Long teamLeadId, Long countryLeadId, String targetMetric) {

        LocalDate anchor = leaderboardService.resolveAnchor(tenantId);
        LeaderboardService.Periods p = leaderboardService.resolvePeriods(period, dateFrom, dateTo, anchor);
        MomentumWindow mw = pulseService.momentumWindow(anchor);

        // ── Facts ────────────────────────────────────────────────────────────
        Map<String, WindowSales> window =
                pulseService.windowSales(tenantId, p.from(), p.to(), p.prevFrom(), p.prevTo(), p.hasPrev());
        Map<String, List<Double>> series = pulseService.monthlySeries(tenantId, mw);
        // An unbounded ("all time") window resolves to sentinel dates spanning
        // centuries. A target for all of history is not a thing anyone set, and
        // asking for it would build an IN list of thousands of month keys — so a
        // window longer than three years simply has no targets.
        Map<String, BigDecimal> targets = Map.of();
        LocalDate targetFrom = LocalDate.parse(p.from());
        LocalDate targetTo = LocalDate.parse(p.to());
        if (targetTo.toEpochDay() - targetFrom.toEpochDay() <= 366L * 3) {
            targets = targetResolver.resolveAll(tenantId, targetFrom, targetTo, targetMetric);
        }

        // ── Hierarchy ────────────────────────────────────────────────────────
        Map<String, SalesAgentProfile> profiles = new HashMap<>();
        for (SalesAgentProfile prof : profileRepository.findAllByTenantId(tenantId)) {
            profiles.put(prof.getSalesUserId(), prof);
        }
        Map<String, Long> agentToTeam = new HashMap<>();
        for (SalesUserAssignment a : assignmentRepository.findAllByTenantId(tenantId)) {
            agentToTeam.put(a.getSalesUserId(), a.getTeamLeadId());
        }
        Map<Long, SalesTeamMapping> teamsById = new LinkedHashMap<>();
        for (SalesTeamMapping t : teamMappingRepository.findAllByTenantId(tenantId)) {
            teamsById.put(t.getId(), t);
        }
        Map<Long, String> countryNames = new HashMap<>();
        for (SalesCountryLead c : countryLeadRepository.findAllByTenantId(tenantId)) {
            countryNames.put(c.getId(), c.getCountryLeadName());
        }

        // Every agent we know about from any source — an agent can exist in
        // dim_merchant, in an assignment, or only as a profile.
        Set<String> allAgents = new TreeSet<>();
        allAgents.addAll(window.keySet());
        allAgents.addAll(series.keySet());
        allAgents.addAll(agentToTeam.keySet());
        allAgents.addAll(profiles.keySet());

        // ── Agent rows, bucketed by team ─────────────────────────────────────
        Map<Long, List<Map<String, Object>>> byTeam = new LinkedHashMap<>();
        List<Map<String, Object>> orphans = new ArrayList<>();

        for (String agentId : allAgents) {
            Long team = agentToTeam.get(agentId);

            // Filters prune the population BEFORE any superlative is computed, so
            // "Top Performer" means top of what the user is actually looking at.
            if (teamLeadId != null && !teamLeadId.equals(team)) continue;
            if (countryLeadId != null) {
                SalesTeamMapping tm = team != null ? teamsById.get(team) : null;
                if (tm == null || !countryLeadId.equals(tm.getCountryLeadId())) continue;
            }

            WindowSales ws = window.getOrDefault(agentId, new WindowSales(0, 0, 0, 0, 0));
            List<Double> hist = series.get(agentId);
            BigDecimal target = targets.get(agentId);

            Double attainment = (target != null && target.signum() > 0)
                    ? SalesPulseService.round1(ws.sales() / target.doubleValue() * 100.0)
                    : null;

            Momentum mom = pulseService.classify(hist, attainment);
            SalesAgentProfile prof = profiles.get(agentId);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", agentId);
            row.put("name", prof != null && prof.getDisplayName() != null && !prof.getDisplayName().isBlank()
                    ? prof.getDisplayName() : agentId);
            row.put("email", prof != null ? prof.getSalesEmail() : null);
            row.put("status", prof != null ? prof.getStatus() : null);
            row.put("sales", ws.sales());
            row.put("previousSales", p.hasPrev() ? ws.prevSales() : null);
            row.put("growthPct", p.hasPrev() ? SalesPulseService.changePct(ws.sales(), ws.prevSales()) : null);
            row.put("volume", ws.volume());
            row.put("txns", ws.txns());
            row.put("merchants", ws.merchants());
            // null, not 0 — "no target configured" is not "missed the target".
            row.put("target", target);
            row.put("targetAchievement", attainment);
            row.put("momentum", mom.state());
            row.put("momentumGrowthPct", mom.growthPct());
            row.put("recentAverage", mom.recentAverage());
            row.put("consecutiveDeclines", mom.consecutiveDeclines());
            row.put("consecutiveGrowth", mom.consecutiveGrowth());
            row.put("series", mom.series());
            row.put("teamContribution", null);   // needs the team total; filled below
            row.put("signals", new ArrayList<String>());

            if (team == null) orphans.add(row);
            else byTeam.computeIfAbsent(team, k -> new ArrayList<>()).add(row);
        }

        // ── Team groups ──────────────────────────────────────────────────────
        List<Map<String, Object>> teamNodes = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> e : byTeam.entrySet()) {
            SalesTeamMapping tm = teamsById.get(e.getKey());
            if (tm == null) continue;
            teamNodes.add(teamNode(e.getKey(), tm.getTeamLeadName(), tm.getTeamLeadEmail(),
                    tm.getCountryLeadId() != null ? countryNames.get(tm.getCountryLeadId()) : null,
                    e.getValue(), p.hasPrev()));
        }
        // Agents with no team lead are surfaced, never dropped — otherwise the
        // page's total would quietly disagree with the leaderboard's.
        if (!orphans.isEmpty() && teamLeadId == null && countryLeadId == null) {
            teamNodes.add(teamNode(null, UNASSIGNED_TEAM, null, null, orphans, p.hasPrev()));
        }
        teamNodes.sort((a, b) -> Double.compare(num(b.get("teamSales")), num(a.get("teamSales"))));

        // ── Signals across the filtered population ───────────────────────────
        List<Map<String, Object>> everyone = new ArrayList<>();
        for (Map<String, Object> t : teamNodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) t.get("salesExecutives");
            everyone.addAll(members);
        }
        pulseService.applySignals(everyone);

        // Team dependency depends on contribution, which is set inside teamNode,
        // so it is computed after the members are final.
        for (Map<String, Object> t : teamNodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) t.get("salesExecutives");
            Dependency dep = pulseService.dependency(members, num(t.get("teamSales")));
            t.put("dependencyStatus", dep.status());
            t.put("dependencyTopContributor", dep.topContributor());
            t.put("dependencySharePct", dep.topSharePct());
        }

        // ── Summary ──────────────────────────────────────────────────────────
        double totalSales = 0, totalPrev = 0;
        int needsAttention = 0, longDecline = 0;
        for (Map<String, Object> a : everyone) {
            totalSales += num(a.get("sales"));
            Object prev = a.get("previousSales");
            if (prev instanceof Number n) totalPrev += n.doubleValue();
            if (SalesPulseService.NEEDS_ATTENTION.contains(String.valueOf(a.get("momentum")))) needsAttention++;
            if (num(a.get("consecutiveDeclines")) >= props.getAttentionDeclineStreak()) longDecline++;
        }
        Double growth = p.hasPrev() ? SalesPulseService.changePct(totalSales, totalPrev) : null;

        Map<String, Object> topTeam = null;
        if (!teamNodes.isEmpty() && num(teamNodes.get(0).get("teamSales")) > 0) {
            Map<String, Object> t = teamNodes.get(0);
            topTeam = new LinkedHashMap<>();
            topTeam.put("teamLeadId", t.get("teamLeadId"));
            topTeam.put("teamLeadName", t.get("teamLeadName"));
            topTeam.put("sales", t.get("teamSales"));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSales", totalSales);
        summary.put("previousTotalSales", p.hasPrev() ? totalPrev : null);
        summary.put("growth", growth);
        summary.put("topTeam", topTeam);
        summary.put("needsAttentionCount", needsAttention);
        summary.put("salesExecutiveCount", everyone.size());
        summary.put("teamCount", teamNodes.size());

        // ── Org-level monthly trend (hero chart) ─────────────────────────────
        // Summed from the per-agent series already in memory — never a third
        // query. Agent series are right-aligned to the momentum window's last
        // month (leading months before an agent's first data are absent), so
        // each series is added from the tail backwards.
        List<Map<String, Object>> orgSeries = new ArrayList<>();
        {
            List<YearMonth> months = new ArrayList<>();
            for (YearMonth m = mw.first(); !m.isAfter(mw.last()); m = m.plusMonths(1)) months.add(m);
            double[] sums = new double[months.size()];
            for (Map<String, Object> a : everyone) {
                @SuppressWarnings("unchecked")
                List<Double> s = (List<Double>) a.get("series");
                if (s == null || s.isEmpty()) continue;
                int offset = sums.length - s.size();   // >= 0: a series never exceeds the window
                for (int i = Math.max(0, -offset); i < s.size(); i++) {
                    sums[offset + i] += s.get(i) == null ? 0.0 : s.get(i);
                }
            }
            for (int i = 0; i < months.size(); i++) {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("month", months.get(i).toString());
                pt.put("sales", SalesPulseService.round1(sums[i]));
                orgSeries.add(pt);
            }
        }

        // ── Response ─────────────────────────────────────────────────────────
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("orgSeries", orgSeries);
        out.put("executiveInsight", pulseService.insight(growth,
                topTeam != null ? String.valueOf(topTeam.get("teamLeadName")) : null,
                needsAttention, longDecline));
        out.put("teams", teamNodes);

        Map<String, Object> periodMeta = new LinkedHashMap<>();
        periodMeta.put("key", period);
        periodMeta.put("from", p.from());
        periodMeta.put("to", p.to());
        periodMeta.put("compareFrom", p.hasPrev() ? p.prevFrom() : null);
        periodMeta.put("compareTo", p.hasPrev() ? p.prevTo() : null);
        out.put("period", periodMeta);

        // The momentum window is published because it is NOT the filter period —
        // the UI must be able to say "momentum over Mar–Aug" rather than let the
        // user assume these arrows describe the range they selected.
        Map<String, Object> momentumMeta = new LinkedHashMap<>();
        momentumMeta.put("from", mw.firstLabel());
        momentumMeta.put("to", mw.lastLabel());
        momentumMeta.put("months", props.getMomentumMonths());
        out.put("momentumWindow", momentumMeta);

        out.put("dataThrough", anchor.toString());
        out.put("targetsConfigured", targetResolver.anyConfigured(tenantId));
        out.put("targetMetric", targetMetric);
        return out;
    }

    /**
     * A Team Lead group. Team sales are the SUM of the members shown beneath it,
     * never a separate query — that is what guarantees the group header can never
     * disagree with its own rows.
     */
    private Map<String, Object> teamNode(Long teamLeadId, String name, String email, String countryLeadName,
                                         List<Map<String, Object>> members, boolean hasPrev) {
        double sales = 0, prev = 0;
        for (Map<String, Object> m : members) {
            sales += num(m.get("sales"));
            Object pv = m.get("previousSales");
            if (pv instanceof Number n) prev += n.doubleValue();
        }

        // Contribution is only meaningful against a positive team total. Net margin
        // can legitimately be zero or negative for a whole team in a bad period;
        // a share of a non-positive base is not a percentage anyone should read.
        for (Map<String, Object> m : members) {
            m.put("teamContribution", sales > 0
                    ? SalesPulseService.round1(num(m.get("sales")) / sales * 100.0)
                    : null);
        }
        members.sort((a, b) -> Double.compare(num(b.get("sales")), num(a.get("sales"))));

        int needsAttention = 0;
        for (Map<String, Object> m : members) {
            if (SalesPulseService.NEEDS_ATTENTION.contains(String.valueOf(m.get("momentum")))) needsAttention++;
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("teamLeadId", teamLeadId);
        node.put("teamLeadName", name);
        node.put("teamLeadEmail", email);
        node.put("countryLeadName", countryLeadName);
        node.put("teamSales", sales);
        node.put("previousTeamSales", hasPrev ? prev : null);
        node.put("teamGrowth", hasPrev ? SalesPulseService.changePct(sales, prev) : null);
        node.put("salesExecutiveCount", members.size());
        node.put("needsAttentionCount", needsAttention);
        node.put("salesExecutives", members);
        return node;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
