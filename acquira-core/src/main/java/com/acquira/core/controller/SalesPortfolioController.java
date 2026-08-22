package com.acquira.core.controller;

import com.acquira.common.model.SalesAgentProfile;
import com.acquira.common.model.SalesCountryLead;
import com.acquira.common.model.SalesTeamMapping;
import com.acquira.common.model.SalesUserAssignment;
import com.acquira.common.repository.SalesAgentProfileRepository;
import com.acquira.common.repository.SalesCountryLeadRepository;
import com.acquira.common.repository.SalesTeamMappingRepository;
import com.acquira.common.repository.SalesUserAssignmentRepository;
import com.acquira.core.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Sales portfolios at each tier of the hierarchy: Sales Agent, Team Lead, and
 * Country Lead. Unlike the leaderboard (which ranks within a period), these
 * default to all-time "how much are they selling" views with optional date
 * bounds, and drill into the children one level down
 * (country -> teams, team -> agents, agent -> merchants).
 *
 * PERFORMANCE: these read the pre-aggregated daily summary `sum_daily_merchant`
 * (partitioned by business_date, indexed on (tenant_id, business_date)),
 * NOT `fact_transaction`. Over a year of data that turns a full transaction
 * scan into a summary lookup — same approach the dashboards already use.
 *   - volume  = SUM(total_base_volume)  (== SUM(store_base_currency_amount); the
 *               single-currency figure, matching the leaderboard. total_volume is
 *               the cardholder-currency figure and is intentionally NOT used.)
 *   - msf     = SUM(total_msf)
 *   - net     = SUM(total_msf - total_interchange - total_scheme_fee)  (NET MARGIN
 *               = MSF - interchange - scheme fee; the primary ranking metric,
 *               matching the leaderboard)
 *   - txns    = SUM(total_txns)
 *
 * Agent identity here is the rep CODE (dim_merchant.sales_user_id) — the same
 * key the team/country rollups and assignments use — so the numbers reconcile
 * with the hierarchy.
 */
@RestController
@RequestMapping("/api/sales-portfolio")
@RequiredArgsConstructor
public class SalesPortfolioController {

    private final JdbcTemplate jdbcTemplate;
    private final TenantService tenantService;
    private final SalesAgentProfileRepository agentProfileRepository;
    private final SalesTeamMappingRepository teamMappingRepository;
    private final SalesCountryLeadRepository countryLeadRepository;
    private final SalesUserAssignmentRepository userAssignmentRepository;
    /** Stamps the tenant's currency onto every money-bearing response. */
    private final CurrencyMeta currencyMeta;
    private final com.acquira.common.service.ReportCache reportCache;

    private Long getTenantId() {
        Long t = tenantService.getCurrentTenantId();
        if (t == null) throw new RuntimeException("No tenant context");
        return t;
    }

    // Optional date bound on sum_daily_merchant.business_date. Empty when no range given.
    private String dateClause(String dateFrom, String dateTo) {
        if (dateFrom != null && !dateFrom.isBlank() && dateTo != null && !dateTo.isBlank()) {
            return " AND business_date BETWEEN ?::date AND ?::date";
        }
        return "";
    }

    private void addDateParams(List<Object> params, String dateFrom, String dateTo) {
        if (dateFrom != null && !dateFrom.isBlank() && dateTo != null && !dateTo.isBlank()) {
            params.add(dateFrom);
            params.add(dateTo);
        }
    }

    private double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private void addAttainment(Map<String, Object> out, double volume, BigDecimal target) {
        out.put("target", target);
        if (target != null && target.doubleValue() > 0) {
            out.put("attainmentPct", Math.round(volume / target.doubleValue() * 1000.0) / 10.0);
        } else {
            out.put("attainmentPct", null);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  EXECUTIVE DASHBOARD  (the whole hierarchy in one response)
    // ═══════════════════════════════════════════════════════════

    /**
     * Management view of sales performance across the entire org, as one tree:
     * Country Lead -> Team Lead -> Sales Agent, every node carrying the same
     * metric set so a parent's numbers are literally the sum of its children's.
     *
     * dateFrom/dateTo bound the volume metrics and the "new merchants" count.
     * compareFrom/compareTo (optional) bound the comparison period; the caller
     * chooses it, because "previous period" means different things for a
     * month-to-date view and a custom range. When omitted, the *ChangePct fields
     * come back null rather than as a misleading zero.
     *
     * COST: three aggregate queries over sum_daily_merchant/dim_merchant total —
     * one for the current period, one for the comparison, one for the merchant
     * counts — regardless of how many leads, teams or agents exist. The tree is
     * assembled in memory. Do NOT turn this into a query per node.
     *
     * Agents with no team lead, and teams with no country lead, are surfaced under
     * synthetic "Unassigned" parents instead of being dropped — otherwise the tree
     * totals would silently disagree with the leaderboard.
     */
    @PreAuthorize("@menuAccess.canAccess('/sales/executive')")
    @GetMapping("/executive")
    public ResponseEntity<?> getExecutiveDashboard(
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo,
            @RequestParam(defaultValue = "") String compareFrom,
            @RequestParam(defaultValue = "") String compareTo) {

        Long tenantId = getTenantId();
        return ResponseEntity.ok(reportCache.get(
                com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA,
                "salesExec:" + tenantId + ":" + dateFrom + ":" + dateTo
                        + ":" + compareFrom + ":" + compareTo,
                () -> buildExecutiveDashboard(tenantId, dateFrom, dateTo, compareFrom, compareTo)));
    }

    private Map<String, Object> buildExecutiveDashboard(Long tenantId,
            String dateFrom, String dateTo, String compareFrom, String compareTo) {

        Map<String, Map<String, Object>> counts = agentMerchantCounts(tenantId, dateFrom, dateTo);
        Map<String, Map<String, Object>> current = agentVolumes(tenantId, dateFrom, dateTo);
        boolean hasComparison = !compareFrom.isBlank() && !compareTo.isBlank();
        Map<String, Map<String, Object>> previous = hasComparison
                ? agentVolumes(tenantId, compareFrom, compareTo)
                : Collections.emptyMap();

        // Agent display names, and the union of every agent we know about: an agent
        // can appear in dim_merchant, in sales_user_assignment, or in
        // sales_agent_profile without appearing in the other two.
        Map<String, SalesAgentProfile> profiles = new HashMap<>();
        for (SalesAgentProfile p : agentProfileRepository.findAllByTenantId(tenantId)) {
            profiles.put(p.getSalesUserId(), p);
        }
        Map<String, Long> agentToTeam = new HashMap<>();
        for (SalesUserAssignment a : userAssignmentRepository.findAllByTenantId(tenantId)) {
            agentToTeam.put(a.getSalesUserId(), a.getTeamLeadId());
        }
        Set<String> allAgents = new TreeSet<>();
        allAgents.addAll(counts.keySet());
        allAgents.addAll(current.keySet());
        allAgents.addAll(agentToTeam.keySet());
        allAgents.addAll(profiles.keySet());

        // ── Agent nodes, bucketed by their team lead ─────────────────────────
        Map<Long, List<Map<String, Object>>> agentsByTeam = new HashMap<>();
        List<Map<String, Object>> orphanAgents = new ArrayList<>();
        for (String agent : allAgents) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("level", "agent");
            node.put("id", agent);
            SalesAgentProfile p = profiles.get(agent);
            node.put("name", p != null && p.getDisplayName() != null && !p.getDisplayName().isBlank()
                    ? p.getDisplayName() : agent);
            node.put("salesUserId", agent);
            node.put("email", p != null ? p.getSalesEmail() : null);
            node.put("status", p != null ? p.getStatus() : null);
            node.put("agentCount", 1);
            applyMetrics(node, counts.get(agent), current.get(agent), previous.get(agent), hasComparison);

            Long teamId = agentToTeam.get(agent);
            if (teamId == null) orphanAgents.add(node);
            else agentsByTeam.computeIfAbsent(teamId, k -> new ArrayList<>()).add(node);
        }

        // ── Team nodes, bucketed by their country lead ───────────────────────
        Map<Long, List<Map<String, Object>>> teamsByCountry = new HashMap<>();
        List<Map<String, Object>> orphanTeams = new ArrayList<>();
        for (SalesTeamMapping team : teamMappingRepository.findAllByTenantId(tenantId)) {
            List<Map<String, Object>> children = agentsByTeam.getOrDefault(team.getId(), new ArrayList<>());
            Map<String, Object> node = groupNode("team", team.getId(), team.getTeamLeadName(),
                    team.getTeamLeadEmail(), children, hasComparison);
            if (team.getCountryLeadId() == null) orphanTeams.add(node);
            else teamsByCountry.computeIfAbsent(team.getCountryLeadId(), k -> new ArrayList<>()).add(node);
        }
        if (!orphanAgents.isEmpty()) {
            orphanTeams.add(groupNode("team", null, "Unassigned Agents", null, orphanAgents, hasComparison));
        }

        // ── Country nodes ────────────────────────────────────────────────────
        List<Map<String, Object>> tree = new ArrayList<>();
        for (SalesCountryLead country : countryLeadRepository.findAllByTenantId(tenantId)) {
            List<Map<String, Object>> children = teamsByCountry.getOrDefault(country.getId(), new ArrayList<>());
            Map<String, Object> node = groupNode("country", country.getId(), country.getCountryLeadName(),
                    country.getCountryLeadEmail(), children, hasComparison);
            node.put("countryCode", country.getCountryCode());
            tree.add(node);
        }
        if (!orphanTeams.isEmpty()) {
            tree.add(groupNode("country", null, "Unassigned", null, orphanTeams, hasComparison));
        }
        sortByNet(tree);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dateFrom", dateFrom);
        out.put("dateTo", dateTo);
        out.put("compareFrom", hasComparison ? compareFrom : null);
        out.put("compareTo", hasComparison ? compareTo : null);
        out.put("tree", tree);
        // Org-wide totals for the KPI row. Summed from the country nodes, so they
        // agree with the tree by construction.
        out.put("totals", groupNode("org", null, "All Sales", null, tree, hasComparison));
        return currencyMeta.attach(out, tenantId);
    }

    /** Portfolio counts per agent. Merchant counts are all-time; only "new" is date-bound. */
    private Map<String, Map<String, Object>> agentMerchantCounts(Long tenantId, String dateFrom, String dateTo) {
        boolean bounded = !dateFrom.isBlank() && !dateTo.isBlank();
        // created_date is a timestamp: a half-open upper bound keeps merchants
        // created during the last day of the range from being dropped.
        String newExpr = bounded
                ? "COUNT(*) FILTER (WHERE m.created_date >= ?::date AND m.created_date < ?::date + INTERVAL '1 day')"
                : "0";
        String sql = "SELECT m.sales_user_id AS agent,"
            + " COUNT(*) AS merchant_count,"
            + " COUNT(*) FILTER (WHERE UPPER(COALESCE(m.status, '')) = 'ACTIVE') AS active_merchants,"
            + " COUNT(*) FILTER (WHERE UPPER(COALESCE(m.status, '')) <> 'ACTIVE') AS inactive_merchants,"
            + " " + newExpr + " AS new_merchants"
            + " FROM dim_merchant m"
            + " WHERE m.tenant_id = ? AND m.sales_user_id IS NOT NULL AND m.sales_user_id <> ''"
            + " GROUP BY m.sales_user_id";

        List<Object> params = new ArrayList<>();
        if (bounded) { params.add(dateFrom); params.add(dateTo); }
        params.add(tenantId);
        return byAgent(jdbcTemplate.queryForList(sql, params.toArray()));
    }

    /** Volume / net / txns per agent over a date range (blank range = all time). */
    private Map<String, Map<String, Object>> agentVolumes(Long tenantId, String dateFrom, String dateTo) {
        String sql = "SELECT m.sales_user_id AS agent,"
            + " COUNT(DISTINCT sdm.merchant_id) AS transacting_merchants,"
            + " COALESCE(SUM(sdm.total_base_volume), 0) AS volume,"
            + " COALESCE(SUM(sdm.total_msf), 0) AS msf,"
            + " COALESCE(SUM(COALESCE(sdm.total_msf,0) - COALESCE(sdm.total_interchange,0) - COALESCE(sdm.total_scheme_fee,0)), 0) AS net,"
            + " COALESCE(SUM(sdm.total_txns), 0) AS txns"
            + " FROM sum_daily_merchant sdm"
            + " JOIN dim_merchant m ON sdm.merchant_id = m.merchant_id AND sdm.tenant_id = m.tenant_id"
            + " WHERE sdm.tenant_id = ?" + dateClause(dateFrom, dateTo)
            + " AND m.sales_user_id IS NOT NULL AND m.sales_user_id <> ''"
            + " GROUP BY m.sales_user_id";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        addDateParams(params, dateFrom, dateTo);
        return byAgent(jdbcTemplate.queryForList(sql, params.toArray()));
    }

    private Map<String, Map<String, Object>> byAgent(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : rows) out.put((String) r.get("agent"), r);
        return out;
    }

    /** The metric set every node in the tree carries. */
    private void applyMetrics(Map<String, Object> node, Map<String, Object> counts,
            Map<String, Object> current, Map<String, Object> previous, boolean hasComparison) {
        node.put("merchantCount", counts != null ? num(counts.get("merchant_count")) : 0.0);
        node.put("activeMerchants", counts != null ? num(counts.get("active_merchants")) : 0.0);
        node.put("inactiveMerchants", counts != null ? num(counts.get("inactive_merchants")) : 0.0);
        node.put("newMerchants", counts != null ? num(counts.get("new_merchants")) : 0.0);
        node.put("transactingMerchants", current != null ? num(current.get("transacting_merchants")) : 0.0);
        node.put("totalVolume", current != null ? num(current.get("volume")) : 0.0);
        node.put("totalMsf", current != null ? num(current.get("msf")) : 0.0);
        node.put("totalNet", current != null ? num(current.get("net")) : 0.0);
        node.put("totalTxns", current != null ? num(current.get("txns")) : 0.0);
        node.put("prevVolume", previous != null ? num(previous.get("volume")) : 0.0);
        node.put("prevNet", previous != null ? num(previous.get("net")) : 0.0);
        node.put("prevTxns", previous != null ? num(previous.get("txns")) : 0.0);
        finaliseNode(node, hasComparison);
    }

    /**
     * A parent node whose metrics are the sum of its children's. Building parents
     * this way (rather than re-querying per level) is what guarantees a lead's
     * total always equals the sum of the rows shown underneath it.
     */
    private Map<String, Object> groupNode(String level, Long id, String name, String email,
            List<Map<String, Object>> children, boolean hasComparison) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("level", level);
        node.put("id", id);
        node.put("name", name);
        node.put("email", email);

        String[] additive = {"merchantCount", "activeMerchants", "inactiveMerchants", "newMerchants",
            "transactingMerchants", "totalVolume", "totalMsf", "totalNet", "totalTxns",
            "prevVolume", "prevNet", "prevTxns", "agentCount"};
        for (String k : additive) node.put(k, 0.0);
        for (Map<String, Object> c : children) {
            for (String k : additive) node.put(k, num(node.get(k)) + num(c.get(k)));
        }
        sortByNet(children);
        node.put("children", children);
        node.put("childCount", children.size());
        finaliseNode(node, hasComparison);
        return node;
    }

    /** Derived rates and period-over-period change, computed once per node. */
    private void finaliseNode(Map<String, Object> node, boolean hasComparison) {
        double volume = num(node.get("totalVolume"));
        double net = num(node.get("totalNet"));
        double msf = num(node.get("totalMsf"));
        node.put("msfRate", volume > 0 ? Math.round(msf / volume * 10000.0) / 100.0 : 0);
        node.put("netRate", volume > 0 ? Math.round(net / volume * 10000.0) / 100.0 : 0);
        // Null, not zero, when there is nothing to compare against — a 0% change and
        // "no comparison period" are different statements and the UI shows them differently.
        node.put("volumeChangePct", hasComparison ? changePct(volume, num(node.get("prevVolume"))) : null);
        node.put("netChangePct", hasComparison ? changePct(net, num(node.get("prevNet"))) : null);
        node.put("txnChangePct", hasComparison ? changePct(num(node.get("totalTxns")), num(node.get("prevTxns"))) : null);
    }

    private Double changePct(double current, double previous) {
        if (previous == 0) return null;   // growth from zero is undefined, not infinite
        return Math.round((current - previous) / Math.abs(previous) * 1000.0) / 10.0;
    }

    private void sortByNet(List<Map<String, Object>> nodes) {
        nodes.sort((a, b) -> {
            int byNet = Double.compare(num(b.get("totalNet")), num(a.get("totalNet")));
            return byNet != 0 ? byNet : Double.compare(num(b.get("totalVolume")), num(a.get("totalVolume")));
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  AGENT PORTFOLIO  (children: merchants)
    // ═══════════════════════════════════════════════════════════

    @PreAuthorize("@menuAccess.canAccess('/sales/executive') or @menuAccess.canAccess('/sales/agents')")
    @GetMapping("/agent/{salesUserId}")
    public ResponseEntity<?> getAgentPortfolio(@PathVariable String salesUserId,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {

        Long tenantId = getTenantId();
        Map<String, Object> out = new HashMap<>();
        out.put("salesUserId", salesUserId);

        SalesAgentProfile profile = agentProfileRepository
                .findByTenantIdAndSalesUserId(tenantId, salesUserId).orElse(null);
        if (profile != null) {
            out.put("displayName", profile.getDisplayName());
            out.put("salesEmail", profile.getSalesEmail());
            out.put("phone", profile.getPhone());
            out.put("countryCode", profile.getCountryCode());
            out.put("hireDate", profile.getHireDate());
            out.put("status", profile.getStatus());
        }

        // The drill-down row the executive dashboard shows for each merchant.
        // assigned_date is when this agent took the merchant over — the newest
        // reassignment TO them — falling back to the merchant's creation date for a
        // merchant that has never changed hands. last_txn_date is deliberately NOT
        // date-bounded: "when did this merchant last transact" is an absolute fact,
        // and bounding it would just restate dateTo.
        String merchSql = "SELECT m.merchant_id, m.mid, m.name, m.status, m.city, m.created_date,"
            + " COALESCE(v.total_volume, 0) AS volume,"
            + " COALESCE(v.txn_count, 0) AS txn_count,"
            + " COALESCE(v.msf_total, 0) AS msf,"
            + " COALESCE(v.net_total, 0) AS net,"
            + " COALESCE(h.assigned_at, m.created_date) AS assigned_date,"
            + " lt.last_txn_date,"
            + " m.sales_user_id AS current_sales_agent,"
            + " sap.display_name AS current_sales_agent_name,"
            + " stm.team_lead_name AS current_sales_lead"
            + " FROM dim_merchant m"
            + " LEFT JOIN ("
            + "   SELECT merchant_id, SUM(total_base_volume) AS total_volume,"
            + "     SUM(total_txns) AS txn_count, SUM(total_msf) AS msf_total,"
            + "     SUM(COALESCE(total_msf,0) - COALESCE(total_interchange,0) - COALESCE(total_scheme_fee,0)) AS net_total"
            + "   FROM sum_daily_merchant WHERE tenant_id = ?" + dateClause(dateFrom, dateTo)
            + "   GROUP BY merchant_id"
            + " ) v ON m.merchant_id = v.merchant_id"
            + " LEFT JOIN LATERAL ("
            + "   SELECT MAX(changed_at) AS assigned_at FROM merchant_sales_assignment_history msah"
            + "   WHERE msah.tenant_id = m.tenant_id AND msah.merchant_id = m.merchant_id"
            + "     AND msah.new_sales_user_id = m.sales_user_id"
            + " ) h ON TRUE"
            + " LEFT JOIN ("
            + "   SELECT merchant_id, MAX(business_date) AS last_txn_date"
            + "   FROM sum_daily_merchant WHERE tenant_id = ? GROUP BY merchant_id"
            + " ) lt ON lt.merchant_id = m.merchant_id"
            + " LEFT JOIN sales_agent_profile sap"
            + "   ON sap.tenant_id = m.tenant_id AND sap.sales_user_id = m.sales_user_id"
            + " LEFT JOIN sales_user_assignment sua"
            + "   ON sua.tenant_id = m.tenant_id AND sua.sales_user_id = m.sales_user_id"
            + " LEFT JOIN sales_team_mapping stm ON stm.id = sua.team_lead_id AND stm.tenant_id = m.tenant_id"
            + " WHERE m.tenant_id = ? AND m.sales_user_id = ?"
            + " ORDER BY net DESC, volume DESC";

        List<Object> mp = new ArrayList<>();
        mp.add(tenantId);
        addDateParams(mp, dateFrom, dateTo);
        mp.add(tenantId);
        mp.add(tenantId);
        mp.add(salesUserId);
        List<Map<String, Object>> merchants = jdbcTemplate.queryForList(merchSql, mp.toArray());
        out.put("merchants", merchants);

        double totalVolume = 0, totalMsf = 0, totalTxns = 0, totalNet = 0;
        for (Map<String, Object> m : merchants) {
            totalVolume += num(m.get("volume"));
            totalMsf += num(m.get("msf"));
            totalTxns += num(m.get("txn_count"));
            totalNet += num(m.get("net"));
        }
        out.put("merchantCount", merchants.size());
        out.put("totalVolume", totalVolume);
        out.put("totalMsf", totalMsf);
        out.put("totalTxns", totalTxns);
        out.put("totalNet", totalNet);
        out.put("msfRate", totalVolume > 0 ? Math.round(totalMsf / totalVolume * 10000.0) / 100.0 : 0);
        out.put("netRate", totalVolume > 0 ? Math.round(totalNet / totalVolume * 10000.0) / 100.0 : 0);
        addAttainment(out, totalVolume, profile != null ? profile.getMonthlyTarget() : null);

        String trendSql = "SELECT TO_CHAR(sdm.business_date, 'YYYY-MM') AS month,"
            + " SUM(sdm.total_base_volume) AS volume, SUM(sdm.total_txns) AS txn_count, SUM(sdm.total_msf) AS msf,"
            + " SUM(COALESCE(sdm.total_msf,0) - COALESCE(sdm.total_interchange,0) - COALESCE(sdm.total_scheme_fee,0)) AS net"
            + " FROM sum_daily_merchant sdm"
            + " JOIN dim_merchant m ON sdm.merchant_id = m.merchant_id AND sdm.tenant_id = m.tenant_id"
            + " WHERE sdm.tenant_id = ? AND m.sales_user_id = ?"
            + " GROUP BY TO_CHAR(sdm.business_date, 'YYYY-MM') ORDER BY month DESC LIMIT 12";
        out.put("monthlyTrend", jdbcTemplate.queryForList(trendSql, tenantId, salesUserId));

        return ResponseEntity.ok(currencyMeta.attach(out, tenantId));
    }

    // ═══════════════════════════════════════════════════════════
    //  TEAM PORTFOLIO  (children: agents)
    // ═══════════════════════════════════════════════════════════

    @PreAuthorize("@menuAccess.canAccess('/sales/hierarchy') or @menuAccess.canAccess('/sales/agents')")
    @GetMapping("/team/{teamLeadId}")
    public ResponseEntity<?> getTeamPortfolio(@PathVariable Long teamLeadId,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {

        Long tenantId = getTenantId();
        SalesTeamMapping team = teamMappingRepository.findById(teamLeadId).orElse(null);
        if (team == null || !Objects.equals(team.getTenantId(), tenantId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Team lead not found for this tenant"));
        }

        Map<String, Object> out = new HashMap<>();
        out.put("teamLeadId", team.getId());
        out.put("teamLeadName", team.getTeamLeadName());
        out.put("teamLeadEmail", team.getTeamLeadEmail());

        String agentSql = "SELECT sua.sales_user_id AS agent,"
            + " COUNT(DISTINCT m.merchant_id) AS merchants,"
            + " COALESCE(SUM(v.total_volume), 0) AS volume,"
            + " COALESCE(SUM(v.msf_total), 0) AS msf,"
            + " COALESCE(SUM(v.net_total), 0) AS net,"
            + " COALESCE(SUM(v.txn_count), 0) AS txn_count"
            + " FROM sales_user_assignment sua"
            + " LEFT JOIN dim_merchant m ON m.sales_user_id = sua.sales_user_id AND m.tenant_id = ?"
            + " LEFT JOIN ("
            + "   SELECT merchant_id, SUM(total_base_volume) AS total_volume,"
            + "     SUM(total_msf) AS msf_total, SUM(total_txns) AS txn_count,"
            + "     SUM(COALESCE(total_msf,0) - COALESCE(total_interchange,0) - COALESCE(total_scheme_fee,0)) AS net_total"
            + "   FROM sum_daily_merchant WHERE tenant_id = ?" + dateClause(dateFrom, dateTo)
            + "   GROUP BY merchant_id"
            + " ) v ON v.merchant_id = m.merchant_id"
            + " WHERE sua.tenant_id = ? AND sua.team_lead_id = ?"
            + " GROUP BY sua.sales_user_id ORDER BY net DESC, volume DESC";

        List<Object> ap = new ArrayList<>();
        ap.add(tenantId);
        ap.add(tenantId);
        addDateParams(ap, dateFrom, dateTo);
        ap.add(tenantId);
        ap.add(teamLeadId);
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(agentSql, ap.toArray());

        Map<String, SalesAgentProfile> profiles = new HashMap<>();
        for (SalesAgentProfile p : agentProfileRepository.findAllByTenantId(tenantId)) {
            profiles.put(p.getSalesUserId(), p);
        }
        BigDecimal teamTarget = BigDecimal.ZERO;
        boolean anyTarget = false;
        double totalVolume = 0, totalMsf = 0, totalTxns = 0, totalMerchants = 0, totalNet = 0;
        for (Map<String, Object> a : agents) {
            SalesAgentProfile p = profiles.get((String) a.get("agent"));
            a.put("displayName", p != null ? p.getDisplayName() : null);
            if (p != null && p.getMonthlyTarget() != null) {
                teamTarget = teamTarget.add(p.getMonthlyTarget());
                anyTarget = true;
            }
            totalVolume += num(a.get("volume"));
            totalMsf += num(a.get("msf"));
            totalTxns += num(a.get("txn_count"));
            totalMerchants += num(a.get("merchants"));
            totalNet += num(a.get("net"));
        }
        out.put("agents", agents);
        out.put("agentCount", agents.size());
        out.put("merchantCount", totalMerchants);
        out.put("totalVolume", totalVolume);
        out.put("totalMsf", totalMsf);
        out.put("totalTxns", totalTxns);
        out.put("totalNet", totalNet);
        out.put("msfRate", totalVolume > 0 ? Math.round(totalMsf / totalVolume * 10000.0) / 100.0 : 0);
        out.put("netRate", totalVolume > 0 ? Math.round(totalNet / totalVolume * 10000.0) / 100.0 : 0);
        addAttainment(out, totalVolume, anyTarget ? teamTarget : null);

        String trendSql = "SELECT TO_CHAR(sdm.business_date, 'YYYY-MM') AS month,"
            + " SUM(sdm.total_base_volume) AS volume, SUM(sdm.total_txns) AS txn_count, SUM(sdm.total_msf) AS msf,"
            + " SUM(COALESCE(sdm.total_msf,0) - COALESCE(sdm.total_interchange,0) - COALESCE(sdm.total_scheme_fee,0)) AS net"
            + " FROM sum_daily_merchant sdm"
            + " JOIN dim_merchant m ON sdm.merchant_id = m.merchant_id AND sdm.tenant_id = m.tenant_id"
            + " JOIN sales_user_assignment sua ON sua.sales_user_id = m.sales_user_id AND sua.tenant_id = m.tenant_id"
            + " WHERE sdm.tenant_id = ? AND sua.team_lead_id = ?"
            + " GROUP BY TO_CHAR(sdm.business_date, 'YYYY-MM') ORDER BY month DESC LIMIT 12";
        out.put("monthlyTrend", jdbcTemplate.queryForList(trendSql, tenantId, teamLeadId));

        return ResponseEntity.ok(currencyMeta.attach(out, tenantId));
    }

    // ═══════════════════════════════════════════════════════════
    //  COUNTRY PORTFOLIO  (children: team leads)
    // ═══════════════════════════════════════════════════════════

    @PreAuthorize("@menuAccess.canAccess('/sales/hierarchy') or @menuAccess.canAccess('/sales/agents')")
    @GetMapping("/country/{countryLeadId}")
    public ResponseEntity<?> getCountryPortfolio(@PathVariable Long countryLeadId,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {

        Long tenantId = getTenantId();
        SalesCountryLead country = countryLeadRepository.findById(countryLeadId).orElse(null);
        if (country == null || !Objects.equals(country.getTenantId(), tenantId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Country lead not found for this tenant"));
        }

        Map<String, Object> out = new HashMap<>();
        out.put("countryLeadId", country.getId());
        out.put("countryLeadName", country.getCountryLeadName());
        out.put("countryLeadEmail", country.getCountryLeadEmail());
        out.put("countryCode", country.getCountryCode());

        String teamSql = "SELECT stm.id AS team_lead_id, stm.team_lead_name,"
            + " COUNT(DISTINCT sua.sales_user_id) AS agent_count,"
            + " COUNT(DISTINCT m.merchant_id) AS merchants,"
            + " COALESCE(SUM(v.total_volume), 0) AS volume,"
            + " COALESCE(SUM(v.msf_total), 0) AS msf,"
            + " COALESCE(SUM(v.net_total), 0) AS net,"
            + " COALESCE(SUM(v.txn_count), 0) AS txn_count"
            + " FROM sales_team_mapping stm"
            + " LEFT JOIN sales_user_assignment sua ON sua.team_lead_id = stm.id AND sua.tenant_id = stm.tenant_id"
            + " LEFT JOIN dim_merchant m ON m.sales_user_id = sua.sales_user_id AND m.tenant_id = stm.tenant_id"
            + " LEFT JOIN ("
            + "   SELECT merchant_id, SUM(total_base_volume) AS total_volume,"
            + "     SUM(total_msf) AS msf_total, SUM(total_txns) AS txn_count,"
            + "     SUM(COALESCE(total_msf,0) - COALESCE(total_interchange,0) - COALESCE(total_scheme_fee,0)) AS net_total"
            + "   FROM sum_daily_merchant WHERE tenant_id = ?" + dateClause(dateFrom, dateTo)
            + "   GROUP BY merchant_id"
            + " ) v ON v.merchant_id = m.merchant_id"
            + " WHERE stm.tenant_id = ? AND stm.country_lead_id = ?"
            + " GROUP BY stm.id, stm.team_lead_name ORDER BY net DESC, volume DESC";

        List<Object> tp = new ArrayList<>();
        tp.add(tenantId);
        addDateParams(tp, dateFrom, dateTo);
        tp.add(tenantId);
        tp.add(countryLeadId);
        List<Map<String, Object>> teams = jdbcTemplate.queryForList(teamSql, tp.toArray());

        double totalVolume = 0, totalMsf = 0, totalTxns = 0, totalMerchants = 0, totalAgents = 0, totalNet = 0;
        for (Map<String, Object> t : teams) {
            totalVolume += num(t.get("volume"));
            totalMsf += num(t.get("msf"));
            totalTxns += num(t.get("txn_count"));
            totalMerchants += num(t.get("merchants"));
            totalAgents += num(t.get("agent_count"));
            totalNet += num(t.get("net"));
        }
        out.put("teams", teams);
        out.put("teamCount", teams.size());
        out.put("agentCount", totalAgents);
        out.put("merchantCount", totalMerchants);
        out.put("totalVolume", totalVolume);
        out.put("totalMsf", totalMsf);
        out.put("totalTxns", totalTxns);
        out.put("totalNet", totalNet);
        out.put("msfRate", totalVolume > 0 ? Math.round(totalMsf / totalVolume * 10000.0) / 100.0 : 0);
        out.put("netRate", totalVolume > 0 ? Math.round(totalNet / totalVolume * 10000.0) / 100.0 : 0);

        String targetSql = "SELECT COALESCE(SUM(sap.monthly_target), 0) AS total_target"
            + " FROM sales_agent_profile sap"
            + " JOIN sales_user_assignment sua ON sua.sales_user_id = sap.sales_user_id AND sua.tenant_id = sap.tenant_id"
            + " JOIN sales_team_mapping stm ON stm.id = sua.team_lead_id AND stm.tenant_id = sua.tenant_id"
            + " WHERE sap.tenant_id = ? AND stm.country_lead_id = ? AND sap.monthly_target IS NOT NULL";
        BigDecimal countryTarget = jdbcTemplate.queryForObject(targetSql, BigDecimal.class, tenantId, countryLeadId);
        addAttainment(out, totalVolume,
                (countryTarget != null && countryTarget.doubleValue() > 0) ? countryTarget : null);

        String trendSql = "SELECT TO_CHAR(sdm.business_date, 'YYYY-MM') AS month,"
            + " SUM(sdm.total_base_volume) AS volume, SUM(sdm.total_txns) AS txn_count, SUM(sdm.total_msf) AS msf,"
            + " SUM(COALESCE(sdm.total_msf,0) - COALESCE(sdm.total_interchange,0) - COALESCE(sdm.total_scheme_fee,0)) AS net"
            + " FROM sum_daily_merchant sdm"
            + " JOIN dim_merchant m ON sdm.merchant_id = m.merchant_id AND sdm.tenant_id = m.tenant_id"
            + " JOIN sales_user_assignment sua ON sua.sales_user_id = m.sales_user_id AND sua.tenant_id = m.tenant_id"
            + " JOIN sales_team_mapping stm ON stm.id = sua.team_lead_id AND stm.tenant_id = sua.tenant_id"
            + " WHERE sdm.tenant_id = ? AND stm.country_lead_id = ?"
            + " GROUP BY TO_CHAR(sdm.business_date, 'YYYY-MM') ORDER BY month DESC LIMIT 12";
        out.put("monthlyTrend", jdbcTemplate.queryForList(trendSql, tenantId, countryLeadId));

        return ResponseEntity.ok(currencyMeta.attach(out, tenantId));
    }
}
