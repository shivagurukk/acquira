package com.acquira.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Sales Leaderboard logic (redesigned).
 *
 * Ranking metric: NET MARGIN = MSF - interchange - scheme fee, summed from
 * sum_daily_merchant's per-fee columns. Tiebreak on volume.
 *
 * Design decisions of this rewrite:
 *
 * 1. DATA-ANCHORED PERIODS. Period keywords (MTD/QTD/YTD/...) are resolved in
 *    Java against the tenant's latest business_date, not CURRENT_DATE. When
 *    transaction data lags real time (it's August but data ends in June), MTD
 *    means "month-to-date of the data", so the leaderboard is never empty.
 *    Explicit dateFrom/dateTo still win over the period keyword.
 *
 * 2. ONE QUERY BODY FOR ALL TIERS. Agents, team leads and country leads differ
 *    only in how sales_user_id maps to a group; that mapping is a per-tier CTE
 *    injected into a shared SQL body (merchants -> onboarding/totals ->
 *    activity). Fixes drift where the three hand-written queries disagreed.
 *
 * 3. CURRENT + PREVIOUS PERIOD IN ONE SCAN. The activity CTE aggregates both
 *    windows with FILTER clauses, so period-over-period change is available
 *    for every tier (previously agents only, via a second query).
 *
 * 4. STRUCTURED BADGES. Badges are stable keys ("million_club"), not emoji
 *    strings the frontend has to substring-match.
 *
 * Response keys are kept backward compatible: /countries is also consumed by
 * SalesHierarchyTree (country_lead, total_volume, net_revenue, team_count...).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaderboardService {

    private final JdbcTemplate jdbcTemplate;

    // Sentinel dates for the "all time" window (BETWEEN with from > to is
    // always false, giving an empty previous window).
    private static final String MIN_DATE = "1900-01-01";
    private static final String MAX_DATE = "9999-12-31";

    public enum Tier { AGENTS, TEAMS, COUNTRIES }

    /** A resolved reporting window plus its comparison window. */
    public record Periods(String from, String to, String prevFrom, String prevTo, boolean hasPrev, LocalDate anchor) {}

    /** Shared net-margin definition (NetSpreadSql): batch 4-leg margin incl. PG fee. */
    private static final String NET_EXPR = com.acquira.common.service.NetSpreadSql.margin("sdm");

    // ═══════════════════════════════════════════════════════════
    //  PERIOD RESOLUTION
    // ═══════════════════════════════════════════════════════════

    /** Latest business_date for the tenant — the "today" all periods anchor to. */
    public LocalDate resolveAnchor(Long tenantId) {
        try {
            // total_txns > 0: an ancillary-only day (rental/DCC loaded ahead
            // of that day's transaction file) must not drag the MTD/QTD/YTD
            // anchor past the last real trading day.
            LocalDate max = jdbcTemplate.queryForObject(
                "SELECT MAX(business_date) FROM sum_daily_merchant "
                + "WHERE tenant_id = ? AND COALESCE(total_txns,0) > 0",
                LocalDate.class, tenantId);
            return max != null ? max : LocalDate.now();
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    public Periods resolvePeriods(String period, String dateFrom, String dateTo, LocalDate anchor) {
        // Explicit custom range wins; previous window = same length immediately before.
        if (dateFrom != null && !dateFrom.isEmpty() && dateTo != null && !dateTo.isEmpty()) {
            LocalDate from = LocalDate.parse(dateFrom.substring(0, Math.min(10, dateFrom.length())));
            LocalDate to = LocalDate.parse(dateTo.substring(0, Math.min(10, dateTo.length())));
            long len = ChronoUnit.DAYS.between(from, to);
            LocalDate prevTo = from.minusDays(1);
            LocalDate prevFrom = prevTo.minusDays(len);
            return new Periods(from.toString(), to.toString(), prevFrom.toString(), prevTo.toString(), true, anchor);
        }

        LocalDate from, to;
        switch (period == null ? "" : period) {
            case "MTD" -> { from = anchor.withDayOfMonth(1); to = anchor; }
            case "QTD" -> { from = quarterStart(anchor); to = anchor; }
            case "YTD" -> { from = anchor.withDayOfYear(1); to = anchor; }
            case "LAST_MONTH" -> {
                LocalDate monthStart = anchor.withDayOfMonth(1);
                from = monthStart.minusMonths(1); to = monthStart.minusDays(1);
            }
            case "LAST_QUARTER" -> {
                LocalDate qStart = quarterStart(anchor);
                from = qStart.minusMonths(3); to = qStart.minusDays(1);
            }
            default -> { // all time
                return new Periods(MIN_DATE, MAX_DATE, MIN_DATE, "1899-12-31", false, anchor);
            }
        }

        // Comparable previous window: same number of days, one period earlier
        // (MTD Aug 1-12 compares against Jul 1-12, not the whole of July).
        long len = ChronoUnit.DAYS.between(from, to);
        LocalDate prevFrom = switch (period) {
            case "MTD", "LAST_MONTH" -> from.minusMonths(1);
            case "QTD", "LAST_QUARTER" -> from.minusMonths(3);
            case "YTD" -> from.minusYears(1);
            default -> from.minusDays(len + 1);
        };
        LocalDate prevTo = prevFrom.plusDays(len);
        // Don't let the comparison window bleed into the current one.
        if (!prevTo.isBefore(from)) prevTo = from.minusDays(1);
        return new Periods(from.toString(), to.toString(), prevFrom.toString(), prevTo.toString(), true, anchor);
    }

    private static LocalDate quarterStart(LocalDate d) {
        int firstMonth = ((d.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(d.getYear(), firstMonth, 1);
    }

    // ═══════════════════════════════════════════════════════════
    //  TIER LEADERBOARDS (shared body, per-tier grouping CTE)
    // ═══════════════════════════════════════════════════════════

    // Per-tier mapping: sales_user_id -> (group_key, group_label, group_email, sub_id).
    // sub_id carries the team_lead_id for the country rollup (NULL elsewhere).
    private static final String GRP_AGENTS =
        "SELECT m.sales_user_id, m.sales_user_id AS group_key, m.sales_user_id AS group_label,"
        + " MAX(m.sales_email) AS group_email, NULL::bigint AS sub_id"
        + " FROM dim_merchant m WHERE m.tenant_id = ? AND m.sales_user_id IS NOT NULL"
        + " GROUP BY m.sales_user_id";

    private static final String GRP_TEAMS =
        "SELECT sua.sales_user_id, stm.team_lead_name AS group_key, stm.team_lead_name AS group_label,"
        + " COALESCE(stm.team_lead_email, '') AS group_email, NULL::bigint AS sub_id"
        + " FROM sales_team_mapping stm"
        + " JOIN sales_user_assignment sua ON sua.team_lead_id = stm.id AND sua.tenant_id = stm.tenant_id"
        + " WHERE stm.tenant_id = ?";

    // Teams without a country lead roll into an explicit 'Unassigned' bucket so
    // their volume is never silently dropped.
    private static final String GRP_COUNTRIES =
        "SELECT sua.sales_user_id, COALESCE(scl.id, -1)::text AS group_key,"
        + " COALESCE(scl.country_lead_name, 'Unassigned') AS group_label,"
        + " COALESCE(scl.country_lead_email, '') AS group_email, stm.id AS sub_id"
        + " FROM sales_team_mapping stm"
        + " JOIN sales_user_assignment sua ON sua.team_lead_id = stm.id AND sua.tenant_id = stm.tenant_id"
        + " LEFT JOIN sales_country_lead scl ON scl.id = stm.country_lead_id AND scl.tenant_id = stm.tenant_id"
        + " WHERE stm.tenant_id = ?";

    public List<Map<String, Object>> leaderboard(Long tenantId, Tier tier, Periods p) {
        String grpCte = switch (tier) {
            case AGENTS -> GRP_AGENTS;
            case TEAMS -> GRP_TEAMS;
            case COUNTRIES -> GRP_COUNTRIES;
        };

        String sql =
            "WITH bounds AS (SELECT ?::date AS cf, ?::date AS ct, ?::date AS pf, ?::date AS pt),"
            + " grp AS (" + grpCte + "),"
            + " grp_size AS ("
            + "   SELECT group_key, MAX(group_label) AS group_label, MAX(group_email) AS group_email,"
            + "     COUNT(DISTINCT sales_user_id) AS agent_count, COUNT(DISTINCT sub_id) AS sub_count"
            + "   FROM grp GROUP BY group_key"
            + " ),"
            + " merch AS ("
            + "   SELECT DISTINCT g.group_key, m.merchant_id, m.created_date"
            + "   FROM grp g JOIN dim_merchant m ON m.sales_user_id = g.sales_user_id AND m.tenant_id = ?"
            + " ),"
            + " merch_stats AS ("
            + "   SELECT mm.group_key, COUNT(*) AS total_merchants,"
            + "     COUNT(*) FILTER (WHERE mm.created_date >= b.cf AND mm.created_date < b.ct + INTERVAL '1 day') AS merchants_onboarded"
            + "   FROM merch mm CROSS JOIN bounds b GROUP BY mm.group_key"
            + " ),"
            + " activity AS ("
            + "   SELECT mm.group_key,"
            // total_txns > 0: an ancillary-only row must not count as activity.
            + "     COUNT(DISTINCT sdm.merchant_id) FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct AND COALESCE(sdm.total_txns,0) > 0) AS active_merchants,"
            + "     COALESCE(SUM(sdm.total_txns) FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct), 0) AS txn_count,"
            + "     COALESCE(SUM(sdm.total_base_volume) FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct), 0) AS total_volume,"
            + "     COALESCE(SUM(sdm.total_msf) FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct), 0) AS total_msf,"
            + "     COALESCE(SUM(" + NET_EXPR + ") FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct), 0) AS net_revenue,"
            + "     COALESCE(SUM(sdm.total_base_volume) FILTER (WHERE sdm.business_date BETWEEN b.pf AND b.pt), 0) AS prev_volume,"
            + "     COALESCE(SUM(" + NET_EXPR + ") FILTER (WHERE sdm.business_date BETWEEN b.pf AND b.pt), 0) AS prev_net"
            + "   FROM merch mm CROSS JOIN bounds b"
            + "   JOIN sum_daily_merchant sdm ON sdm.merchant_id = mm.merchant_id AND sdm.tenant_id = ?"
            + "     AND sdm.business_date BETWEEN LEAST(b.cf, b.pf) AND b.ct"
            + "   GROUP BY mm.group_key"
            + " )"
            + " SELECT gs.group_key, gs.group_label, gs.group_email, gs.agent_count, gs.sub_count,"
            + "   COALESCE(ms.total_merchants, 0) AS total_merchants,"
            + "   COALESCE(ms.merchants_onboarded, 0) AS merchants_onboarded,"
            + "   COALESCE(a.active_merchants, 0) AS active_merchants,"
            + "   COALESCE(a.txn_count, 0) AS txn_count,"
            + "   COALESCE(a.total_volume, 0) AS total_volume,"
            + "   COALESCE(a.total_msf, 0) AS total_msf,"
            + "   COALESCE(a.net_revenue, 0) AS net_revenue,"
            + "   COALESCE(a.prev_volume, 0) AS prev_volume,"
            + "   COALESCE(a.prev_net, 0) AS prev_net,"
            + "   CASE WHEN COALESCE(ms.total_merchants, 0) > 0"
            + "     THEN ROUND(COALESCE(a.active_merchants, 0)::numeric / ms.total_merchants * 100, 1)"
            + "     ELSE 0 END AS active_rate"
            + " FROM grp_size gs"
            + " LEFT JOIN merch_stats ms ON ms.group_key = gs.group_key"
            + " LEFT JOIN activity a ON a.group_key = gs.group_key"
            + " ORDER BY COALESCE(a.net_revenue, 0) DESC, COALESCE(a.total_volume, 0) DESC";

        Object[] params = { p.from(), p.to(), p.prevFrom(), p.prevTo(), tenantId, tenantId, tenantId };
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            row.put("rank", i + 1);

            double vol = num(row, "total_volume");
            double msf = num(row, "total_msf");
            double net = num(row, "net_revenue");
            row.put("msf_rate", vol > 0 ? Math.round(msf / vol * 10000.0) / 100.0 : 0);
            row.put("net_rate", vol > 0 ? Math.round(net / vol * 10000.0) / 100.0 : 0);

            double prevVol = num(row, "prev_volume");
            double prevNet = num(row, "prev_net");
            row.put("volume_change_pct", p.hasPrev() && prevVol > 0
                ? Math.round((vol - prevVol) / prevVol * 1000.0) / 10.0 : null);
            row.put("net_change_pct", p.hasPrev() && prevNet > 0
                ? Math.round((net - prevNet) / prevNet * 1000.0) / 10.0 : null);
            row.remove("prev_volume");
            row.remove("prev_net");

            // Tier-specific identity keys (kept for API compatibility)
            String label = (String) row.remove("group_label");
            String email = (String) row.remove("group_email");
            row.remove("group_key");
            switch (tier) {
                case AGENTS -> {
                    row.put("agent", label);
                    row.put("agent_email", email);
                    row.remove("agent_count");
                    row.remove("sub_count");
                }
                case TEAMS -> {
                    row.put("team_lead", label);
                    row.put("team_lead_email", email);
                    row.remove("sub_count");
                }
                case COUNTRIES -> {
                    row.put("country_lead", label);
                    row.put("country_lead_email", email);
                    row.put("team_count", row.remove("sub_count"));
                }
            }
            row.put("badges", badges(tier, row));
        }
        return rows;
    }

    // ═══════════════════════════════════════════════════════════
    //  OVERVIEW
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> overview(Long tenantId, Periods p) {
        String sql =
            "SELECT"
            + " (SELECT COUNT(DISTINCT sales_user_id) FROM dim_merchant WHERE tenant_id = ? AND sales_user_id IS NOT NULL) AS total_agents,"
            + " (SELECT COUNT(*) FROM sales_team_mapping WHERE tenant_id = ?) AS total_teams,"
            + " (SELECT COUNT(DISTINCT merchant_id) FROM dim_merchant WHERE tenant_id = ?"
            + "    AND created_date >= ?::date AND created_date < ?::date + INTERVAL '1 day') AS merchants_onboarded,"
            + " COALESCE(SUM(sdm.total_base_volume), 0) AS total_volume,"
            + " COALESCE(SUM(sdm.total_msf), 0) AS total_msf,"
            + " COALESCE(SUM(sdm.total_txns), 0) AS total_txns,"
            + " COALESCE(SUM(" + NET_EXPR + "), 0) AS total_net"
            + " FROM sum_daily_merchant sdm"
            + " WHERE sdm.tenant_id = ? AND sdm.business_date BETWEEN ?::date AND ?::date";

        Map<String, Object> r = jdbcTemplate.queryForMap(sql,
            tenantId, tenantId, tenantId, p.from(), p.to(), tenantId, p.from(), p.to());

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalAgents", r.get("total_agents"));
        overview.put("totalTeams", r.get("total_teams"));
        overview.put("merchantsOnboarded", r.get("merchants_onboarded"));
        overview.put("totalVolume", r.get("total_volume"));
        overview.put("totalMsf", r.get("total_msf"));
        overview.put("totalTxns", r.get("total_txns"));
        overview.put("totalNetRevenue", r.get("total_net"));
        // Transparency about what window the anchored period actually resolved to
        overview.put("dataThrough", p.anchor().toString());
        overview.put("periodFrom", MIN_DATE.equals(p.from()) ? null : p.from());
        overview.put("periodTo", MAX_DATE.equals(p.to()) ? null : p.to());
        return overview;
    }

    // ═══════════════════════════════════════════════════════════
    //  AGENT DETAIL
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> agentDetail(Long tenantId, String salesUserId, Periods p) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("agent", salesUserId);
        detail.put("agentEmail", jdbcTemplate.query(
            "SELECT MAX(sales_email) FROM dim_merchant WHERE tenant_id = ? AND sales_user_id = ?",
            rs -> rs.next() ? rs.getString(1) : null, tenantId, salesUserId));

        String merchSql = "SELECT m.merchant_id, m.mid, m.name, m.status, m.city, m.created_date,"
            + " COALESCE(v.total_volume, 0) AS volume,"
            + " COALESCE(v.txn_count, 0) AS txn_count,"
            + " COALESCE(v.msf_total, 0) AS msf,"
            + " COALESCE(v.net_total, 0) AS net"
            + " FROM dim_merchant m"
            + " LEFT JOIN ("
            + "   SELECT sdm.merchant_id, SUM(sdm.total_base_volume) AS total_volume,"
            + "     SUM(sdm.total_txns) AS txn_count, SUM(sdm.total_msf) AS msf_total,"
            + "     SUM(" + NET_EXPR + ") AS net_total"
            + "   FROM sum_daily_merchant sdm WHERE sdm.tenant_id = ?"
            + "     AND sdm.business_date BETWEEN ?::date AND ?::date"
            + "   GROUP BY sdm.merchant_id"
            + " ) v ON m.merchant_id = v.merchant_id"
            + " WHERE m.tenant_id = ? AND m.sales_user_id = ?"
            + " ORDER BY net DESC, volume DESC";
        detail.put("merchants", jdbcTemplate.queryForList(merchSql,
            tenantId, p.from(), p.to(), tenantId, salesUserId));

        String trendSql = "SELECT TO_CHAR(sdm.business_date, 'YYYY-MM') AS month,"
            + " SUM(sdm.total_base_volume) AS volume,"
            + " SUM(sdm.total_txns) AS txn_count,"
            + " SUM(sdm.total_msf) AS msf,"
            + " SUM(" + NET_EXPR + ") AS net"
            + " FROM sum_daily_merchant sdm"
            + " JOIN dim_merchant m ON sdm.merchant_id = m.merchant_id AND sdm.tenant_id = m.tenant_id"
            + " WHERE sdm.tenant_id = ? AND m.sales_user_id = ?"
            + " GROUP BY TO_CHAR(sdm.business_date, 'YYYY-MM')"
            + " ORDER BY month DESC LIMIT 12";
        detail.put("monthlyTrend", jdbcTemplate.queryForList(trendSql, tenantId, salesUserId));

        return detail;
    }

    // ═══════════════════════════════════════════════════════════
    //  BADGES (stable keys — the frontend owns labels/icons)
    // ═══════════════════════════════════════════════════════════

    private List<String> badges(Tier tier, Map<String, Object> row) {
        List<String> badges = new ArrayList<>();
        int rank = ((Number) row.get("rank")).intValue();
        double volume = num(row, "total_volume");
        double activeRate = num(row, "active_rate");

        switch (tier) {
            case AGENTS -> {
                int onboarded = ((Number) row.get("merchants_onboarded")).intValue();
                if (rank == 1) badges.add("top_performer");
                else if (rank == 2) badges.add("runner_up");
                else if (rank == 3) badges.add("bronze");
                if (onboarded >= 10) badges.add("onboarding_star");
                else if (onboarded >= 5) badges.add("growing");
                if (activeRate >= 90) badges.add("high_activation");
                if (volume >= 1_000_000) badges.add("million_club");
                else if (volume >= 500_000) badges.add("half_m");
            }
            case TEAMS -> {
                int agents = ((Number) row.get("agent_count")).intValue();
                if (rank == 1) badges.add("top_team");
                else if (rank == 2) badges.add("runner_up");
                if (agents >= 5) badges.add("large_team");
                if (activeRate >= 85) badges.add("high_activation");
                if (volume >= 5_000_000) badges.add("5m_club");
                else if (volume >= 1_000_000) badges.add("million_team");
            }
            case COUNTRIES -> {
                int teams = ((Number) row.get("team_count")).intValue();
                if (rank == 1) badges.add("top_country");
                else if (rank == 2) badges.add("runner_up");
                if (teams >= 3) badges.add("multi_team");
                if (activeRate >= 85) badges.add("high_activation");
                if (volume >= 10_000_000) badges.add("10m_club");
                else if (volume >= 5_000_000) badges.add("5m_country");
            }
        }
        return badges;
    }

    private static double num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
