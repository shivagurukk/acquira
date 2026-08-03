package com.acquira.core.controller;

import com.acquira.common.model.SalesAgentProfile;
import com.acquira.common.model.SalesCountryLead;
import com.acquira.common.model.SalesTeamMapping;
import com.acquira.common.repository.SalesAgentProfileRepository;
import com.acquira.common.repository.SalesCountryLeadRepository;
import com.acquira.common.repository.SalesTeamMappingRepository;
import com.acquira.core.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
    //  AGENT PORTFOLIO  (children: merchants)
    // ═══════════════════════════════════════════════════════════

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

        String merchSql = "SELECT m.merchant_id, m.mid, m.name, m.status, m.city, m.created_date,"
            + " COALESCE(v.total_volume, 0) AS volume,"
            + " COALESCE(v.txn_count, 0) AS txn_count,"
            + " COALESCE(v.msf_total, 0) AS msf,"
            + " COALESCE(v.net_total, 0) AS net"
            + " FROM dim_merchant m"
            + " LEFT JOIN ("
            + "   SELECT merchant_id, SUM(total_base_volume) AS total_volume,"
            + "     SUM(total_txns) AS txn_count, SUM(total_msf) AS msf_total,"
            + "     SUM(COALESCE(total_msf,0) - COALESCE(total_interchange,0) - COALESCE(total_scheme_fee,0)) AS net_total"
            + "   FROM sum_daily_merchant WHERE tenant_id = ?" + dateClause(dateFrom, dateTo)
            + "   GROUP BY merchant_id"
            + " ) v ON m.merchant_id = v.merchant_id"
            + " WHERE m.tenant_id = ? AND m.sales_user_id = ?"
            + " ORDER BY net DESC, volume DESC";

        List<Object> mp = new ArrayList<>();
        mp.add(tenantId);
        addDateParams(mp, dateFrom, dateTo);
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

        return ResponseEntity.ok(out);
    }

    // ═══════════════════════════════════════════════════════════
    //  TEAM PORTFOLIO  (children: agents)
    // ═══════════════════════════════════════════════════════════

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

        return ResponseEntity.ok(out);
    }

    // ═══════════════════════════════════════════════════════════
    //  COUNTRY PORTFOLIO  (children: team leads)
    // ═══════════════════════════════════════════════════════════

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

        return ResponseEntity.ok(out);
    }
}
