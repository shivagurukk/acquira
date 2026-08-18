package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Top Performers — Top 10 Merchants (Volume + Net Margin), Top RMs (Volume +
 * Net Margin), Top Movers (up/down vs prior window), Top 10 New Merchants,
 * and a concentration KPI strip — all in one round trip.
 *
 * PERFORMANCE: three queries total — current window, prior window, and the
 * first-transaction-date lookup backing the new-merchant board. Each is a single
 * tenant-scoped GROUP BY over the chosen base table. Every board is derived from
 * those result sets in memory afterwards — never re-queried per board.
 *
 * RANKING GRAIN: one row per dim_merchant record, which in this schema is one
 * merchant master record (UNIQUE (tenant_id, internal_id)) carrying a single MID.
 * A legal entity trading under several MIDs therefore ranks as several entries;
 * there is no legal-entity or merchant-group key in dim_merchant to roll up to.
 * Stores and terminals do NOT fan the totals out: sum_daily_merchant is unique on
 * (tenant_id, business_date, merchant_id), so the join cannot multiply rows.
 *
 * ELIGIBILITY: every board except Movers ranks only merchants with volume > 0 in
 * the window. Movers deliberately keeps merchants that fell TO zero — a merchant
 * dropping from real volume to nothing is the most material move there is.
 *
 * GRAIN: sum_daily_merchant (settlement total_base_volume; real net margin =
 * msf - interchange - scheme_fee) unless a card-level filter (scheme/cardType/
 * destination/channel) is set, in which case we switch to sum_daily_insight
 * (cardholder total_volume; net margin there is approximated as MSF since
 * interchange/scheme are always 0 on that table) — the same dual-grain
 * convention used by AnalyticsController#heatmap-filtered.
 */
@RestController
@RequestMapping("/api/business")
@PreAuthorize("@menuAccess.canAccess('/business/top-performers')")
public class TopPerformersController {

    private static final int TOP_N = 10;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

    /** Stamps the tenant's currency onto every money-bearing response. */
    @Autowired
    private CurrencyMeta currencyMeta;

    private void resolveFilters(VolumeRevenueFilterDTO filters, Long tenantId) {
        if (notEmpty(filters.getTeamLeaderList()) && tenantId != null) {
            List<String> ids = salesTeamService.getSalesUserIdsByTeamLeadNames(tenantId, filters.getTeamLeaderList());
            filters.setTeamLeaderList(ids.isEmpty() ? Collections.singletonList("__NO_MATCH__") : ids);
        }
    }

    @PostMapping("/top-performers-filtered")
    public Map<String, Object> getTopPerformers(
            @RequestParam(defaultValue = "MTD") String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestBody(required = false) VolumeRevenueFilterDTO filter) {

        Long tenantId = tenantService.getCurrentTenantId();
        if (tenantId == null) throw new RuntimeException("No tenant context");
        if (filter == null) filter = new VolumeRevenueFilterDTO();
        resolveFilters(filter, tenantId);

        LocalDate[] curWindow = resolveWindow(period, from, to);
        LocalDate[] priorWindow = priorWindow(curWindow);
        boolean cardGrain = usesCardFilters(filter);

        List<Map<String, Object>> current = runAggregate(filter, tenantId, cardGrain, curWindow[0], curWindow[1]);

        // Run the prior aggregate unconditionally and derive "has data" from the RESULT.
        // The old COUNT(*) probe ignored the active filters, so it could report data for a
        // prior window in which the filtered set was in fact empty.
        List<Map<String, Object>> prior = runAggregate(filter, tenantId, cardGrain, priorWindow[0], priorWindow[1]);
        boolean priorHasData = prior.stream().anyMatch(r -> toDouble(r.get("volume")) > 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("grain", cardGrain ? "insight" : "merchant");
        response.put("period", period);
        response.put("from", curWindow[0].toString());
        response.put("to", curWindow[1].toString());
        response.put("priorFrom", priorWindow[0].toString());
        response.put("priorTo", priorWindow[1].toString());
        response.put("priorWindowHasData", priorHasData);

        // Only merchants with actual volume are eligible for the "top" boards —
        // zero-volume merchants would just clutter a volume/net-revenue ranking.
        List<Map<String, Object>> withVolume = current.stream()
                .filter(r -> toDouble(r.get("volume")) > 0)
                .collect(Collectors.toList());

        response.put("topMerchantsByVolume", rank(withVolume, "volume", TOP_N));
        response.put("topMerchantsByNetRevenue", rank(withVolume, "netRevenue", TOP_N));
        response.put("topMerchantsByTxns", rank(withVolume, "txns", TOP_N));

        List<Map<String, Object>> rmAgg = groupByRm(withVolume, agentDisplayNames(tenantId));
        response.put("topRmsByVolume", rank(rmAgg, "volume", TOP_N));
        response.put("topRmsByNetRevenue", rank(rmAgg, "netRevenue", TOP_N));

        response.put("topMccs", topMccs(filter, tenantId, curWindow[0], curWindow[1]));
        response.put("topMovers", priorHasData ? computeMovers(current, prior) : null);
        response.put("topNewMerchants", computeNewMerchants(withVolume,
                firstTxnDates(tenantId, cardGrain, curWindow[0], curWindow[1])));

        double totalVolume = current.stream().mapToDouble(r -> toDouble(r.get("volume"))).sum();
        double totalNet = current.stream().mapToDouble(r -> toDouble(r.get("netRevenue"))).sum();
        double top10Volume = withVolume.stream()
                .sorted((a, b) -> Double.compare(toDouble(b.get("volume")), toDouble(a.get("volume"))))
                .limit(TOP_N).mapToDouble(r -> toDouble(r.get("volume"))).sum();
        Map<String, Object> concentration = new LinkedHashMap<>();
        concentration.put("totalVolume", totalVolume);
        concentration.put("totalNetRevenue", totalNet);
        concentration.put("activeMerchantCount", withVolume.size());
        concentration.put("top10SharePct", totalVolume > 0 ? round2(top10Volume / totalVolume * 100) : 0);
        response.put("concentration", concentration);

        return currencyMeta.attach(response, tenantId);
    }

    // ─────────────────────────────────────────────────────────────
    // Window resolution
    // ─────────────────────────────────────────────────────────────

    private LocalDate[] resolveWindow(String period, String from, String to) {
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            return new LocalDate[]{ LocalDate.parse(from), LocalDate.parse(to) };
        }
        LocalDate now = LocalDate.now();
        return switch (period) {
            case "QTD" -> new LocalDate[]{ now.withMonth(((now.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1), now };
            case "YTD" -> new LocalDate[]{ now.withDayOfYear(1), now };
            case "LAST_MONTH" -> {
                LocalDate firstOfThis = now.withDayOfMonth(1);
                yield new LocalDate[]{ firstOfThis.minusMonths(1), firstOfThis.minusDays(1) };
            }
            default -> new LocalDate[]{ now.withDayOfMonth(1), now }; // MTD
        };
    }

    /**
     * The comparable previous period.
     *
     * A month-aligned window (starts on the 1st, ends in the same month) is shifted by
     * one CALENDAR month, because a rolling N-day shift slides off the month boundary:
     * a full July is 31 days, so shifting back 31 days gave "May 31 – Jun 30" rather
     * than June. Month-to-date is compared with the same number of days at the start of
     * the previous month (Aug 1–2 → Jul 1–2), not with the days immediately before it
     * (Jul 30–31).
     *
     * Anything else — YTD, QTD, or an arbitrary custom range — has no calendar anchor,
     * so it keeps the immediately-preceding window of equal length.
     */
    LocalDate[] priorWindow(LocalDate[] cur) { // package-private for TopPerformersWindowTest
        LocalDate from = cur[0], to = cur[1];

        boolean monthAligned = from.getDayOfMonth() == 1
                && from.getMonthValue() == to.getMonthValue()
                && from.getYear() == to.getYear();

        if (monthAligned) {
            LocalDate priorFrom = from.minusMonths(1);
            LocalDate priorTo = to.getDayOfMonth() == to.lengthOfMonth()
                    // Full month → the whole previous month, whatever its length.
                    ? priorFrom.withDayOfMonth(priorFrom.lengthOfMonth())
                    // Month-to-date → same day count, clamped for short months
                    // (Mar 1–31 compares against Feb 1–28).
                    : priorFrom.withDayOfMonth(Math.min(to.getDayOfMonth(), priorFrom.lengthOfMonth()));
            return new LocalDate[]{ priorFrom, priorTo };
        }

        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate priorTo = from.minusDays(1);
        return new LocalDate[]{ priorTo.minusDays(days - 1), priorTo };
    }

    private boolean usesCardFilters(VolumeRevenueFilterDTO f) {
        return notEmpty(f.getSchemeList()) || notEmpty(f.getCardTypeList())
                || notEmpty(f.getDestinationList()) || notEmpty(f.getChannelList());
    }

    private static boolean notEmpty(List<String> l) { return l != null && !l.isEmpty(); }

    // ─────────────────────────────────────────────────────────────
    // Aggregate query — ONE grouped scan per window
    // ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> runAggregate(VolumeRevenueFilterDTO f, Long tenantId,
            boolean cardGrain, LocalDate from, LocalDate to) {

        boolean needMcc = notEmpty(f.getMccList());
        boolean needSid = notEmpty(f.getSidList());
        // Industry (MCC sector) — resolved against ref_mcc_category, the bank's
        // MCC sector sheet, exactly as RevenueKpiController does. Without this
        // the drawer's Industry picker was accepted, echoed back as an active
        // filter chip, and then silently dropped: every board ignored it.
        // Kept as its own EXISTS so Industry AND explicit MCCs intersect rather
        // than one overwriting the other.
        boolean needIndustry = notEmpty(f.getIndustryList());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.merchant_id AS merchantId, m.name AS name, m.mid AS mid, ");
        sql.append("       m.sales_email AS salesEmail, m.sales_user_id AS salesUserId, ");
        sql.append("       m.referral_partner AS referralPartner, m.created_date AS createdDate, ");
        if (cardGrain) {
            sql.append("       COALESCE(SUM(s.total_volume), 0) AS volume, ");
            sql.append("       COALESCE(SUM(s.total_txns), 0) AS txns, ");
            sql.append("       COALESCE(SUM(s.total_msf), 0) AS msf, ");
            sql.append("       COALESCE(SUM(s.total_msf), 0) AS netRevenue ");
            sql.append("FROM dim_merchant m ");
            sql.append("LEFT JOIN sum_daily_insight s ON s.merchant_id = m.merchant_id AND s.tenant_id = m.tenant_id ");
            sql.append("  AND s.business_date BETWEEN :from AND :to ");
            if (notEmpty(f.getSchemeList())) sql.append("  AND s.card_scheme IN (:schemes) ");
            if (notEmpty(f.getCardTypeList())) sql.append("  AND s.card_type IN (:cardTypes) ");
            if (notEmpty(f.getDestinationList())) sql.append("  AND s.destination IN (:destinations) ");
            if (notEmpty(f.getChannelList())) sql.append("  AND s.channel IN (:channels) ");
        } else {
            sql.append("       COALESCE(SUM(s.total_base_volume), 0) AS volume, ");
            sql.append("       COALESCE(SUM(s.total_txns), 0) AS txns, ");
            sql.append("       COALESCE(SUM(s.total_msf), 0) AS msf, ");
            sql.append("       COALESCE(SUM(COALESCE(s.total_msf,0) - COALESCE(s.total_interchange,0) - COALESCE(s.total_scheme_fee,0)), 0) AS netRevenue ");
            sql.append("FROM dim_merchant m ");
            sql.append("LEFT JOIN sum_daily_merchant s ON s.merchant_id = m.merchant_id AND s.tenant_id = m.tenant_id ");
            sql.append("  AND s.business_date BETWEEN :from AND :to ");
        }
        sql.append("WHERE m.tenant_id = :tid ");
        if (notEmpty(f.getPartnerList())) sql.append("  AND m.referral_partner IN (:partners) ");
        if (notEmpty(f.getRmList())) sql.append("  AND m.sales_email IN (:rms) ");
        if (notEmpty(f.getTeamLeaderList())) sql.append("  AND m.sales_user_id IN (:teamLeaders) ");
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank()) sql.append("  AND m.name ILIKE :merchName ");
        if (notEmpty(f.getMidList())) sql.append("  AND m.mid IN (:mids) ");
        if (needMcc) sql.append("  AND EXISTS (SELECT 1 FROM dim_store st WHERE st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id AND st.mcc IN (:mccs)) ");
        if (needSid) sql.append("  AND EXISTS (SELECT 1 FROM dim_store st2 WHERE st2.merchant_id = m.merchant_id AND st2.tenant_id = m.tenant_id AND st2.sid IN (:sids)) ");
        if (needIndustry) sql.append("  AND EXISTS (SELECT 1 FROM dim_store st3 WHERE st3.merchant_id = m.merchant_id AND st3.tenant_id = m.tenant_id "
                + "AND st3.mcc IN (SELECT mcc FROM ref_mcc_category WHERE category IN (:industries))) ");
        sql.append("GROUP BY m.merchant_id, m.name, m.mid, m.sales_email, m.sales_user_id, m.referral_partner, m.created_date");

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("tid", tenantId);
        q.setParameter("from", from);
        q.setParameter("to", to);
        if (cardGrain) {
            if (notEmpty(f.getSchemeList())) q.setParameter("schemes", f.getSchemeList());
            if (notEmpty(f.getCardTypeList())) q.setParameter("cardTypes", f.getCardTypeList());
            if (notEmpty(f.getDestinationList())) q.setParameter("destinations", f.getDestinationList());
            if (notEmpty(f.getChannelList())) q.setParameter("channels", f.getChannelList());
        }
        if (notEmpty(f.getPartnerList())) q.setParameter("partners", f.getPartnerList());
        if (notEmpty(f.getRmList())) q.setParameter("rms", f.getRmList());
        if (notEmpty(f.getTeamLeaderList())) q.setParameter("teamLeaders", f.getTeamLeaderList());
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank()) q.setParameter("merchName", "%" + f.getMerchantName() + "%");
        if (notEmpty(f.getMidList())) q.setParameter("mids", f.getMidList());
        if (needMcc) q.setParameter("mccs", f.getMccList());
        if (needSid) q.setParameter("sids", f.getSidList());
        if (needIndustry) q.setParameter("industries", f.getIndustryList());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchantId", row[0]);
            m.put("name", row[1]);
            m.put("mid", row[2]);
            m.put("salesEmail", row[3]);
            m.put("salesUserId", row[4]);
            m.put("referralPartner", row[5]);
            m.put("createdDate", row[6] != null ? row[6].toString() : null);
            m.put("volume", toDouble(row[7]));
            m.put("txns", toDouble(row[8]));
            m.put("msf", toDouble(row[9]));
            m.put("netRevenue", toDouble(row[10]));
            result.add(m);
        }
        return result;
    }

    /**
     * Top 10 MCCs by settlement volume, from sum_daily_full — the one summary
     * that carries mcc alongside merchant_id, the card-filter columns, AND real
     * net revenue (msf - interchange - scheme - ecom), so both merchant-level
     * and card-level filters apply on a single grain with no insight-table
     * approximation. Category label resolved from ref_mcc_category; an MCC the
     * sheet doesn't know keeps its bare code.
     */
    private List<Map<String, Object>> topMccs(VolumeRevenueFilterDTO f, Long tenantId,
            LocalDate from, LocalDate to) {

        boolean needMerchant = notEmpty(f.getPartnerList()) || notEmpty(f.getRmList())
                || notEmpty(f.getTeamLeaderList()) || notEmpty(f.getMidList())
                || (f.getMerchantName() != null && !f.getMerchantName().isBlank());

        StringBuilder sql = new StringBuilder();
        // Category resolved as a scalar subquery, NOT a join — a duplicate row in
        // ref_mcc_category must never fan out the SUMs.
        sql.append("SELECT s.mcc, ");
        sql.append("       (SELECT c.category FROM ref_mcc_category c WHERE c.mcc = s.mcc LIMIT 1) AS category, ");
        sql.append("       COALESCE(SUM(s.total_volume), 0) AS volume, ");
        sql.append("       COALESCE(SUM(s.total_txns), 0) AS txns, ");
        sql.append("       COALESCE(SUM(s.total_net_revenue), 0) AS netRevenue ");
        sql.append("FROM sum_daily_full s ");
        sql.append("WHERE s.tenant_id = :tid AND s.business_date BETWEEN :from AND :to ");
        sql.append("  AND s.mcc IS NOT NULL ");
        if (notEmpty(f.getSchemeList())) sql.append("  AND s.card_scheme IN (:schemes) ");
        if (notEmpty(f.getCardTypeList())) sql.append("  AND s.card_type IN (:cardTypes) ");
        if (notEmpty(f.getDestinationList())) sql.append("  AND s.destination IN (:destinations) ");
        if (notEmpty(f.getChannelList())) sql.append("  AND s.channel IN (:channels) ");
        if (notEmpty(f.getMccList())) sql.append("  AND s.mcc IN (:mccs) ");
        if (notEmpty(f.getIndustryList())) sql.append("  AND s.mcc IN (SELECT mcc FROM ref_mcc_category WHERE category IN (:industries)) ");
        if (needMerchant) {
            sql.append("  AND EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = s.merchant_id AND m.tenant_id = s.tenant_id ");
            if (notEmpty(f.getPartnerList())) sql.append("    AND m.referral_partner IN (:partners) ");
            if (notEmpty(f.getRmList())) sql.append("    AND m.sales_email IN (:rms) ");
            if (notEmpty(f.getTeamLeaderList())) sql.append("    AND m.sales_user_id IN (:teamLeaders) ");
            if (notEmpty(f.getMidList())) sql.append("    AND m.mid IN (:mids) ");
            if (f.getMerchantName() != null && !f.getMerchantName().isBlank()) sql.append("    AND m.name ILIKE :merchName ");
            sql.append(") ");
        }
        sql.append("GROUP BY s.mcc HAVING COALESCE(SUM(s.total_volume), 0) > 0 ");
        sql.append("ORDER BY volume DESC LIMIT ").append(TOP_N);

        Query q = entityManager.createNativeQuery(sql.toString());
        q.setParameter("tid", tenantId);
        q.setParameter("from", from);
        q.setParameter("to", to);
        if (notEmpty(f.getSchemeList())) q.setParameter("schemes", f.getSchemeList());
        if (notEmpty(f.getCardTypeList())) q.setParameter("cardTypes", f.getCardTypeList());
        if (notEmpty(f.getDestinationList())) q.setParameter("destinations", f.getDestinationList());
        if (notEmpty(f.getChannelList())) q.setParameter("channels", f.getChannelList());
        if (notEmpty(f.getMccList())) q.setParameter("mccs", f.getMccList());
        if (notEmpty(f.getIndustryList())) q.setParameter("industries", f.getIndustryList());
        if (needMerchant) {
            if (notEmpty(f.getPartnerList())) q.setParameter("partners", f.getPartnerList());
            if (notEmpty(f.getRmList())) q.setParameter("rms", f.getRmList());
            if (notEmpty(f.getTeamLeaderList())) q.setParameter("teamLeaders", f.getTeamLeaderList());
            if (notEmpty(f.getMidList())) q.setParameter("mids", f.getMidList());
            if (f.getMerchantName() != null && !f.getMerchantName().isBlank()) q.setParameter("merchName", "%" + f.getMerchantName() + "%");
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        int rank = 1;
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String mcc = row[0] != null ? row[0].toString() : "—";
            String category = (String) row[1];
            m.put("rank", rank++);
            m.put("mcc", mcc);
            m.put("name", category != null && !category.isBlank() ? category : ("MCC " + mcc));
            m.put("volume", toDouble(row[2]));
            m.put("txns", toDouble(row[3]));
            m.put("netRevenue", toDouble(row[4]));
            result.add(m);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // In-memory derivation (no extra queries)
    // ─────────────────────────────────────────────────────────────

    /** Sorts by key desc, takes top n, and returns COPIES so mutating "rank"
     *  on one board's output never bleeds into another board sharing rows. */
    private List<Map<String, Object>> rank(List<Map<String, Object>> rows, String key, int n) {
        List<Map<String, Object>> sorted = rows.stream()
                .sorted((a, b) -> Double.compare(toDouble(b.get(key)), toDouble(a.get(key))))
                .limit(n)
                .map(LinkedHashMap::new)
                .collect(Collectors.toList());
        int rank = 1;
        for (Map<String, Object> r : sorted) { r.put("rank", rank++); }
        return sorted;
    }

    /**
     * AGENT IDENTITY: grouped by dim_merchant.sales_user_id (the rep CODE), the
     * same key LeaderboardController uses; sales_email is carried alongside as a
     * DISPLAY attribute only.
     *
     * This used to group by sales_email and skip rows with a blank one — the
     * exact mix-up already fixed once in LeaderboardController. Two consequences,
     * both making this board disagree with the Sales Leaderboard: an agent with a
     * code but no email address vanished from the RM ranking entirely (while
     * their volume still counted in the concentration totals below, so the boards
     * did not reconcile), and two rep codes sharing a mailbox collapsed into one
     * row. Merchants with no sales_user_id at all are genuinely unassigned and
     * remain excluded.
     */
    /** sales_user_id -> admin-entered display name (sales_agent_profile), for the
     *  RM boards: the rep CODE stays the grouping key, but a human name is what
     *  the board should SHOW. Codes without a profile name fall back to the code. */
    private Map<String, String> agentDisplayNames(Long tenantId) {
        Query q = entityManager.createNativeQuery(
                "SELECT sales_user_id, display_name FROM sales_agent_profile " +
                "WHERE tenant_id = :tid AND display_name IS NOT NULL AND display_name <> ''");
        q.setParameter("tid", tenantId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        Map<String, String> out = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] != null && r[1] != null) out.put(r[0].toString(), r[1].toString());
        }
        return out;
    }

    private List<Map<String, Object>> groupByRm(List<Map<String, Object>> merchantRows, Map<String, String> displayNames) {
        Map<String, Map<String, Object>> byRm = new LinkedHashMap<>();
        for (Map<String, Object> r : merchantRows) {
            String rm = (String) r.get("salesUserId");
            if (rm == null || rm.isBlank()) continue;
            Map<String, Object> agg = byRm.computeIfAbsent(rm, k -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("salesUserId", k);
                a.put("name", displayNames.getOrDefault(k, k));
                a.put("salesEmail", null);
                a.put("volume", 0.0);
                a.put("netRevenue", 0.0);
                a.put("msf", 0.0);
                a.put("merchantCount", 0);
                return a;
            });
            // First non-blank email seen for the code wins — display only, never
            // the grouping key, so a missing one cannot drop the agent.
            if (agg.get("salesEmail") == null) {
                String email = (String) r.get("salesEmail");
                if (email != null && !email.isBlank()) agg.put("salesEmail", email);
            }
            agg.put("volume", toDouble(agg.get("volume")) + toDouble(r.get("volume")));
            agg.put("netRevenue", toDouble(agg.get("netRevenue")) + toDouble(r.get("netRevenue")));
            agg.put("msf", toDouble(agg.get("msf")) + toDouble(r.get("msf")));
            agg.put("merchantCount", (Integer) agg.get("merchantCount") + 1);
        }
        for (Map<String, Object> agg : byRm.values()) {
            double vol = toDouble(agg.get("volume"));
            agg.put("msfRateBps", vol > 0 ? round2(toDouble(agg.get("msf")) / vol * 10000) : 0);
        }
        return new ArrayList<>(byRm.values());
    }

    private Map<String, Object> computeMovers(List<Map<String, Object>> current, List<Map<String, Object>> prior) {
        Map<String, Double> priorVolByMerchant = new HashMap<>();
        for (Map<String, Object> r : prior) priorVolByMerchant.put(idKey(r.get("merchantId")), toDouble(r.get("volume")));

        // Noise floor: 25th percentile of current non-zero volumes, so tiny
        // merchants' wild swings don't drown out genuinely material movers.
        List<Double> vols = current.stream().map(r -> toDouble(r.get("volume"))).filter(v -> v > 0)
                .sorted().collect(Collectors.toList());
        double floor = vols.isEmpty() ? 0 : vols.get(Math.max(0, (int) (vols.size() * 0.25) - 1));

        List<Map<String, Object>> movers = new ArrayList<>();
        for (Map<String, Object> r : current) {
            double curVol = toDouble(r.get("volume"));
            if (curVol < floor) continue;
            Double prevVol = priorVolByMerchant.get(idKey(r.get("merchantId")));
            // A zero/absent prior baseline has no defined percentage move — division by
            // zero would read as infinite growth, and a merchant that first traded in this
            // window is a NEW merchant, not a mover. Excluded from both directions.
            if (prevVol == null || prevVol <= 0) continue;
            double pct = round2((curVol - prevVol) / prevVol * 100);
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("priorVolume", prevVol);
            m.put("volumeChangePct", pct);
            // Absolute change alongside the percentage: a 300% jump on a tiny base and a
            // 12% jump on the book's largest merchant are not the same event.
            m.put("volumeChangeAbs", round2(curVol - prevVol));
            movers.add(m);
        }
        List<Map<String, Object>> up = movers.stream()
                .filter(m -> toDouble(m.get("volumeChangePct")) > 0)
                .sorted((a, b) -> Double.compare(toDouble(b.get("volumeChangePct")), toDouble(a.get("volumeChangePct"))))
                .limit(TOP_N).collect(Collectors.toList());
        List<Map<String, Object>> down = movers.stream()
                .filter(m -> toDouble(m.get("volumeChangePct")) < 0)
                .sorted((a, b) -> Double.compare(toDouble(a.get("volumeChangePct")), toDouble(b.get("volumeChangePct"))))
                .limit(TOP_N).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("up", up);
        result.put("down", down);
        result.put("volumeFloor", floor);
        return result;
    }

    /**
     * Merchants whose FIRST revenue-bearing business date falls inside the window.
     *
     * This used to key off dim_merchant.created_date, which is the merchant-master ETL
     * row-creation timestamp, not a business onboarding date — a back-loaded master file
     * stamps every merchant with the load date, and a re-load moves it. dim_merchant has
     * no onboarding/activation column, so first transaction date (from the summary table,
     * a real business_date) is the only defensible business definition available.
     *
     * The caller passes `withVolume`, so zero-volume merchants are already excluded: an
     * onboarded-but-not-yet-trading merchant is not a "top new merchant", and showing it
     * with a 0 value was why this board was the only one still populated when a window
     * had no transactions.
     */
    private List<Map<String, Object>> computeNewMerchants(List<Map<String, Object>> withVolume,
            Map<String, LocalDate> firstTxnDates) {
        List<Map<String, Object>> newOnes = withVolume.stream()
                .filter(r -> firstTxnDates.containsKey(idKey(r.get("merchantId"))))
                .sorted((a, b) -> Double.compare(toDouble(b.get("volume")), toDouble(a.get("volume"))))
                .limit(TOP_N)
                .map(LinkedHashMap::new)
                .collect(Collectors.toList());
        int rank = 1;
        for (Map<String, Object> r : newOnes) {
            r.put("rank", rank++);
            LocalDate d = firstTxnDates.get(idKey(r.get("merchantId")));
            r.put("firstTransactionDate", d != null ? d.toString() : null);
        }
        return newOnes;
    }

    /**
     * merchant_id → first business_date on which the merchant recorded positive volume,
     * restricted to merchants whose first such date is inside [from, to].
     *
     * Scans history up to `to` (one grouped, index-assisted pass on
     * (tenant_id, business_date)) — "first EVER" cannot be answered from the window
     * alone, otherwise every merchant looks new in every period.
     */
    private Map<String, LocalDate> firstTxnDates(Long tenantId, boolean cardGrain, LocalDate from, LocalDate to) {
        String table = cardGrain ? "sum_daily_insight" : "sum_daily_merchant";
        String volCol = cardGrain ? "total_volume" : "total_base_volume";
        String sql = "SELECT merchant_id, MIN(business_date) AS first_date FROM " + table +
                     " WHERE tenant_id = :tid AND business_date <= :to AND COALESCE(" + volCol + ", 0) > 0 " +
                     " GROUP BY merchant_id HAVING MIN(business_date) >= :from";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("tid", tenantId);
        q.setParameter("from", from);
        q.setParameter("to", to);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        Map<String, LocalDate> out = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            out.put(idKey(r[0]), ((java.sql.Date) r[1]).toLocalDate());
        }
        return out;
    }

    /** Merchant ids cross two result sets; normalise so Long vs BigInteger can't mismatch. */
    private String idKey(Object id) { return id == null ? "" : String.valueOf(id); }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
