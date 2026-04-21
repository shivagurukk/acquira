package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Leaderboard & Gamification API (Phase 2 Enhanced)
 *
 * Rankings for sales agents and team leads based on:
 * - Merchants onboarded (dim_merchant.created_date within period)
 * - Transaction volume, count, MSF revenue
 * - Active merchant ratio
 * - MSF Rate (margin)
 * - Period-over-period change
 */
@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
@Slf4j
public class LeaderboardController {

    private final JdbcTemplate jdbcTemplate;

    private Long getTenantId() {
        Long t = TenantContext.getCurrentTenant();
        if (t == null) throw new RuntimeException("No tenant context");
        return t;
    }

    // ═══════════════════════════════════════════════════════════
    //  SALES AGENT LEADERBOARD
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/agents")
    public ResponseEntity<?> getAgentLeaderboard(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {

        Long tenantId = getTenantId();
        String dateFilter = buildDateFilter(period, dateFrom, dateTo);
        String prevDateFilter = buildPreviousPeriodDateFilter(period);

        // Current period stats
        String sql = "WITH agent_onboarding AS ("
            + " SELECT m.sales_email AS agent,"
            + "   COUNT(DISTINCT m.merchant_id) AS merchants_onboarded"
            + " FROM dim_merchant m"
            + " WHERE m.tenant_id = ? AND m.sales_email IS NOT NULL AND m.created_date IS NOT NULL"
            + (dateFilter.isEmpty() ? "" : " AND m.created_date " + dateFilter)
            + " GROUP BY m.sales_email"
            + "), agent_volume AS ("
            + " SELECT m.sales_email AS agent,"
            + "   COUNT(DISTINCT ft.merchant_id) AS active_merchants,"
            + "   COUNT(*) AS txn_count,"
            + "   COALESCE(SUM(ft.store_base_currency_amount), 0) AS total_volume,"
            + "   COALESCE(SUM(ft.msf), 0) AS total_msf"
            + " FROM fact_transaction ft"
            + " JOIN dim_merchant m ON ft.merchant_id = m.merchant_id AND ft.tenant_id = m.tenant_id"
            + " WHERE ft.tenant_id = ? AND m.sales_email IS NOT NULL"
            + (dateFilter.isEmpty() ? "" : " AND ft.payment_date " + dateFilter)
            + " GROUP BY m.sales_email"
            + "), agent_total AS ("
            + " SELECT m.sales_email AS agent, COUNT(DISTINCT m.merchant_id) AS total_merchants"
            + " FROM dim_merchant m"
            + " WHERE m.tenant_id = ? AND m.sales_email IS NOT NULL"
            + " GROUP BY m.sales_email"
            + ")"
            + " SELECT COALESCE(ao.agent, av.agent, at.agent) AS agent,"
            + "   COALESCE(ao.merchants_onboarded, 0) AS merchants_onboarded,"
            + "   COALESCE(av.active_merchants, 0) AS active_merchants,"
            + "   COALESCE(at.total_merchants, 0) AS total_merchants,"
            + "   COALESCE(av.txn_count, 0) AS txn_count,"
            + "   COALESCE(av.total_volume, 0) AS total_volume,"
            + "   COALESCE(av.total_msf, 0) AS total_msf,"
            + "   CASE WHEN COALESCE(at.total_merchants, 0) > 0"
            + "     THEN ROUND(COALESCE(av.active_merchants, 0)::NUMERIC / at.total_merchants * 100, 1)"
            + "     ELSE 0 END AS active_rate"
            + " FROM agent_onboarding ao"
            + " FULL OUTER JOIN agent_volume av ON ao.agent = av.agent"
            + " FULL OUTER JOIN agent_total at ON COALESCE(ao.agent, av.agent) = at.agent"
            + " ORDER BY total_volume DESC";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (!dateFilter.isEmpty()) addDateParams(params, period, dateFrom, dateTo);
        params.add(tenantId);
        if (!dateFilter.isEmpty()) addDateParams(params, period, dateFrom, dateTo);
        params.add(tenantId);

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params.toArray());

        // Previous period volumes for change calculation
        Map<String, Double> prevVolumes = new HashMap<>();
        if (!prevDateFilter.isEmpty()) {
            try {
                String prevSql = "SELECT m.sales_email AS agent, COALESCE(SUM(ft.store_base_currency_amount), 0) AS prev_volume"
                    + " FROM fact_transaction ft"
                    + " JOIN dim_merchant m ON ft.merchant_id = m.merchant_id AND ft.tenant_id = m.tenant_id"
                    + " WHERE ft.tenant_id = ? AND m.sales_email IS NOT NULL"
                    + " AND ft.payment_date " + prevDateFilter
                    + " GROUP BY m.sales_email";
                List<Map<String, Object>> prevResults = jdbcTemplate.queryForList(prevSql, tenantId);
                for (Map<String, Object> row : prevResults) {
                    prevVolumes.put((String) row.get("agent"), ((Number) row.get("prev_volume")).doubleValue());
                }
            } catch (Exception e) { log.debug("Could not fetch previous period data: {}", e.getMessage()); }
        }

        // Enrich with rank, badges, change %
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> row = results.get(i);
            row.put("rank", i + 1);
            row.put("badges", computeAgentBadges(row));

            double currVol = ((Number) row.get("total_volume")).doubleValue();
            double currMsf = ((Number) row.get("total_msf")).doubleValue();
            row.put("msf_rate", currVol > 0 ? Math.round(currMsf / currVol * 10000.0) / 100.0 : 0);

            String agent = (String) row.get("agent");
            Double prevVol = prevVolumes.get(agent);
            if (prevVol != null && prevVol > 0) {
                row.put("volume_change_pct", Math.round((currVol - prevVol) / prevVol * 1000.0) / 10.0);
            } else {
                row.put("volume_change_pct", null);
            }
        }

        return ResponseEntity.ok(results);
    }

    // ═══════════════════════════════════════════════════════════
    //  TEAM LEAD LEADERBOARD
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/teams")
    public ResponseEntity<?> getTeamLeaderboard(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {

        Long tenantId = getTenantId();
        String dateFilter = buildDateFilter(period, dateFrom, dateTo);

        String sql = "WITH team_agents AS ("
            + " SELECT stm.team_lead_name, stm.team_lead_email, sua.sales_user_id"
            + " FROM sales_team_mapping stm"
            + " JOIN sales_user_assignment sua ON stm.id = sua.team_lead_id"
            + " WHERE stm.tenant_id = ?"
            + "), team_onboarding AS ("
            + " SELECT ta.team_lead_name, ta.team_lead_email,"
            + "   COUNT(DISTINCT m.merchant_id) AS merchants_onboarded,"
            + "   COUNT(DISTINCT ta.sales_user_id) AS agent_count"
            + " FROM team_agents ta"
            + " LEFT JOIN dim_merchant m"
            + "   ON m.sales_email = ta.sales_user_id"
            + "   AND m.tenant_id = ?"
            + "   AND m.created_date IS NOT NULL"
            + (dateFilter.isEmpty() ? "" : "   AND m.created_date " + dateFilter)
            + " GROUP BY ta.team_lead_name, ta.team_lead_email"
            + "), team_volume AS ("
            + " SELECT ta.team_lead_name,"
            + "   COUNT(DISTINCT ft.merchant_id) AS active_merchants,"
            + "   COUNT(*) AS txn_count,"
            + "   COALESCE(SUM(ft.store_base_currency_amount), 0) AS total_volume,"
            + "   COALESCE(SUM(ft.msf), 0) AS total_msf"
            + " FROM team_agents ta"
            + " JOIN dim_merchant m ON m.sales_email = ta.sales_user_id AND m.tenant_id = ?"
            + " JOIN fact_transaction ft ON ft.merchant_id = m.merchant_id AND ft.tenant_id = m.tenant_id"
            + (dateFilter.isEmpty() ? "" : "   AND ft.payment_date " + dateFilter)
            + " GROUP BY ta.team_lead_name"
            + "), team_total AS ("
            + " SELECT ta.team_lead_name,"
            + "   COUNT(DISTINCT m.merchant_id) AS total_merchants"
            + " FROM team_agents ta"
            + " JOIN dim_merchant m ON m.sales_email = ta.sales_user_id AND m.tenant_id = ?"
            + " GROUP BY ta.team_lead_name"
            + ")"
            + " SELECT COALESCE(tob.team_lead_name, tv.team_lead_name, tt.team_lead_name) AS team_lead,"
            + "   COALESCE(tob.team_lead_email, '') AS team_lead_email,"
            + "   COALESCE(tob.agent_count, 0) AS agent_count,"
            + "   COALESCE(tob.merchants_onboarded, 0) AS merchants_onboarded,"
            + "   COALESCE(tv.active_merchants, 0) AS active_merchants,"
            + "   COALESCE(tt.total_merchants, 0) AS total_merchants,"
            + "   COALESCE(tv.txn_count, 0) AS txn_count,"
            + "   COALESCE(tv.total_volume, 0) AS total_volume,"
            + "   COALESCE(tv.total_msf, 0) AS total_msf,"
            + "   CASE WHEN COALESCE(tt.total_merchants, 0) > 0"
            + "     THEN ROUND(COALESCE(tv.active_merchants, 0)::NUMERIC / tt.total_merchants * 100, 1)"
            + "     ELSE 0 END AS active_rate"
            + " FROM team_onboarding tob"
            + " FULL OUTER JOIN team_volume tv ON tob.team_lead_name = tv.team_lead_name"
            + " FULL OUTER JOIN team_total tt ON COALESCE(tob.team_lead_name, tv.team_lead_name) = tt.team_lead_name"
            + " ORDER BY total_volume DESC";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(tenantId);
        if (!dateFilter.isEmpty()) addDateParams(params, period, dateFrom, dateTo);
        params.add(tenantId);
        if (!dateFilter.isEmpty()) addDateParams(params, period, dateFrom, dateTo);
        params.add(tenantId);

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params.toArray());

        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> row = results.get(i);
            row.put("rank", i + 1);
            row.put("badges", computeTeamBadges(row));
            double currVol = ((Number) row.get("total_volume")).doubleValue();
            double currMsf = ((Number) row.get("total_msf")).doubleValue();
            row.put("msf_rate", currVol > 0 ? Math.round(currMsf / currVol * 10000.0) / 100.0 : 0);
        }

        return ResponseEntity.ok(results);
    }

    // ═══════════════════════════════════════════════════════════
    //  OVERVIEW / SUMMARY
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/overview")
    public ResponseEntity<?> getOverview(
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {

        Long tenantId = getTenantId();
        String dateFilter = buildDateFilter(period, dateFrom, dateTo);

        Map<String, Object> overview = new HashMap<>();

        try {
            Integer agentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT sales_email) FROM dim_merchant WHERE tenant_id = ? AND sales_email IS NOT NULL",
                Integer.class, tenantId);
            overview.put("totalAgents", agentCount != null ? agentCount : 0);
        } catch (Exception e) { overview.put("totalAgents", 0); }

        try {
            Integer teamCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sales_team_mapping WHERE tenant_id = ?",
                Integer.class, tenantId);
            overview.put("totalTeams", teamCount != null ? teamCount : 0);
        } catch (Exception e) { overview.put("totalTeams", 0); }

        // Merchants onboarded — using dim_merchant.created_date
        String onbSql = "SELECT COUNT(DISTINCT merchant_id) FROM dim_merchant WHERE tenant_id = ? AND created_date IS NOT NULL";
        List<Object> onbParams = new ArrayList<>();
        onbParams.add(tenantId);
        if (!dateFilter.isEmpty()) {
            onbSql += " AND created_date " + dateFilter;
            addDateParams(onbParams, period, dateFrom, dateTo);
        }
        try {
            Integer onboarded = jdbcTemplate.queryForObject(onbSql, Integer.class, onbParams.toArray());
            overview.put("merchantsOnboarded", onboarded != null ? onboarded : 0);
        } catch (Exception e) { overview.put("merchantsOnboarded", 0); }

        String volSql = "SELECT COALESCE(SUM(store_base_currency_amount), 0) FROM fact_transaction WHERE tenant_id = ?";
        List<Object> volParams = new ArrayList<>();
        volParams.add(tenantId);
        if (!dateFilter.isEmpty()) {
            volSql += " AND payment_date " + dateFilter;
            addDateParams(volParams, period, dateFrom, dateTo);
        }
        try {
            overview.put("totalVolume", jdbcTemplate.queryForObject(volSql, Double.class, volParams.toArray()));
        } catch (Exception e) { overview.put("totalVolume", 0); }

        String msfSql = "SELECT COALESCE(SUM(msf), 0) FROM fact_transaction WHERE tenant_id = ?";
        List<Object> msfParams = new ArrayList<>();
        msfParams.add(tenantId);
        if (!dateFilter.isEmpty()) {
            msfSql += " AND payment_date " + dateFilter;
            addDateParams(msfParams, period, dateFrom, dateTo);
        }
        try {
            overview.put("totalMsf", jdbcTemplate.queryForObject(msfSql, Double.class, msfParams.toArray()));
        } catch (Exception e) { overview.put("totalMsf", 0); }

        return ResponseEntity.ok(overview);
    }

    // ═══════════════════════════════════════════════════════════
    //  AGENT DETAIL
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/agents/{agentEmail}")
    public ResponseEntity<?> getAgentDetail(
            @PathVariable String agentEmail,
            @RequestParam(defaultValue = "") String period,
            @RequestParam(defaultValue = "") String dateFrom,
            @RequestParam(defaultValue = "") String dateTo) {

        Long tenantId = getTenantId();
        String dateFilter = buildDateFilter(period, dateFrom, dateTo);

        Map<String, Object> detail = new HashMap<>();
        detail.put("agent", agentEmail);

        String merchSql = "SELECT m.merchant_id, m.mid, m.name, m.status, m.city, m.created_date,"
            + " COALESCE(v.total_volume, 0) AS volume,"
            + " COALESCE(v.txn_count, 0) AS txn_count,"
            + " COALESCE(v.msf_total, 0) AS msf"
            + " FROM dim_merchant m"
            + " LEFT JOIN ("
            + "   SELECT merchant_id, SUM(store_base_currency_amount) AS total_volume,"
            + "     COUNT(*) AS txn_count, SUM(msf) AS msf_total"
            + "   FROM fact_transaction WHERE tenant_id = ?"
            + (dateFilter.isEmpty() ? "" : " AND payment_date " + dateFilter)
            + "   GROUP BY merchant_id"
            + " ) v ON m.merchant_id = v.merchant_id"
            + " WHERE m.tenant_id = ? AND m.sales_email = ?"
            + " ORDER BY volume DESC";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (!dateFilter.isEmpty()) addDateParams(params, period, dateFrom, dateTo);
        params.add(tenantId);
        params.add(agentEmail);

        detail.put("merchants", jdbcTemplate.queryForList(merchSql, params.toArray()));

        String trendSql = "SELECT TO_CHAR(ft.payment_date, 'YYYY-MM') AS month,"
            + " SUM(ft.store_base_currency_amount) AS volume,"
            + " COUNT(*) AS txn_count,"
            + " SUM(ft.msf) AS msf"
            + " FROM fact_transaction ft"
            + " JOIN dim_merchant m ON ft.merchant_id = m.merchant_id AND ft.tenant_id = m.tenant_id"
            + " WHERE ft.tenant_id = ? AND m.sales_email = ?"
            + " GROUP BY TO_CHAR(ft.payment_date, 'YYYY-MM')"
            + " ORDER BY month DESC LIMIT 12";
        detail.put("monthlyTrend", jdbcTemplate.queryForList(trendSql, tenantId, agentEmail));

        return ResponseEntity.ok(detail);
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    private String buildDateFilter(String period, String dateFrom, String dateTo) {
        if (dateFrom != null && !dateFrom.isEmpty() && dateTo != null && !dateTo.isEmpty()) {
            return "BETWEEN ?::timestamp AND ?::timestamp";
        }
        return switch (period) {
            case "MTD" -> ">= DATE_TRUNC('month', CURRENT_DATE)";
            case "QTD" -> ">= DATE_TRUNC('quarter', CURRENT_DATE)";
            case "YTD" -> ">= DATE_TRUNC('year', CURRENT_DATE)";
            case "LAST_MONTH" -> "BETWEEN DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') AND DATE_TRUNC('month', CURRENT_DATE) - INTERVAL '1 day'";
            case "LAST_QUARTER" -> "BETWEEN DATE_TRUNC('quarter', CURRENT_DATE - INTERVAL '3 months') AND DATE_TRUNC('quarter', CURRENT_DATE) - INTERVAL '1 day'";
            default -> "";
        };
    }

    private String buildPreviousPeriodDateFilter(String period) {
        return switch (period) {
            case "MTD" -> "BETWEEN DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') AND DATE_TRUNC('month', CURRENT_DATE) - INTERVAL '1 day'";
            case "QTD" -> "BETWEEN DATE_TRUNC('quarter', CURRENT_DATE - INTERVAL '3 months') AND DATE_TRUNC('quarter', CURRENT_DATE) - INTERVAL '1 day'";
            case "YTD" -> "BETWEEN DATE_TRUNC('year', CURRENT_DATE - INTERVAL '1 year') AND DATE_TRUNC('year', CURRENT_DATE) - INTERVAL '1 day'";
            case "LAST_MONTH" -> "BETWEEN DATE_TRUNC('month', CURRENT_DATE - INTERVAL '2 months') AND DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') - INTERVAL '1 day'";
            case "LAST_QUARTER" -> "BETWEEN DATE_TRUNC('quarter', CURRENT_DATE - INTERVAL '6 months') AND DATE_TRUNC('quarter', CURRENT_DATE - INTERVAL '3 months') - INTERVAL '1 day'";
            default -> "";
        };
    }

    private void addDateParams(List<Object> params, String period, String dateFrom, String dateTo) {
        if (dateFrom != null && !dateFrom.isEmpty() && dateTo != null && !dateTo.isEmpty()) {
            params.add(dateFrom);
            params.add(dateTo);
        }
    }

    private List<String> computeAgentBadges(Map<String, Object> agent) {
        List<String> badges = new ArrayList<>();
        int rank = ((Number) agent.get("rank")).intValue();
        double volume = ((Number) agent.get("total_volume")).doubleValue();
        int onboarded = ((Number) agent.get("merchants_onboarded")).intValue();
        double activeRate = ((Number) agent.get("active_rate")).doubleValue();

        if (rank == 1) badges.add("🥇 Top Performer");
        else if (rank == 2) badges.add("🥈 Runner Up");
        else if (rank == 3) badges.add("🥉 Bronze");
        if (onboarded >= 10) badges.add("🚀 Onboarding Star");
        else if (onboarded >= 5) badges.add("⭐ Growing Portfolio");
        if (activeRate >= 90) badges.add("🔥 High Activation");
        if (volume >= 1_000_000) badges.add("💎 Million Club");
        else if (volume >= 500_000) badges.add("🏆 Half-M Club");

        return badges;
    }

    private List<String> computeTeamBadges(Map<String, Object> team) {
        List<String> badges = new ArrayList<>();
        int rank = ((Number) team.get("rank")).intValue();
        double volume = ((Number) team.get("total_volume")).doubleValue();
        int agents = ((Number) team.get("agent_count")).intValue();
        double activeRate = ((Number) team.get("active_rate")).doubleValue();

        if (rank == 1) badges.add("🏆 #1 Team");
        else if (rank == 2) badges.add("🥈 Runner Up");
        if (agents >= 5) badges.add("👥 Large Team");
        if (activeRate >= 85) badges.add("🔥 High Activation");
        if (volume >= 5_000_000) badges.add("💎 5M Club");
        else if (volume >= 1_000_000) badges.add("🏅 Million Team");

        return badges;
    }
}
