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
 * Top Performers — Top 10 Merchants (Volume + Net Margin + Transactions), Top
 * RMs (Volume + Net Margin), Top RMs by Merchants Signed, Top 10 New Merchants,
 * Top MCCs, and a concentration KPI strip — all in one round trip.
 *
 * PERFORMANCE: two queries total — the current-window aggregate and the
 * first-transaction-date lookup backing the onboarding-date fallback. Each is a
 * single tenant-scoped GROUP BY over the chosen base table. Every board is
 * derived from those result sets in memory afterwards — never re-queried per
 * board.
 *
 * RANKING GRAIN: one row per dim_merchant record, which in this schema is one
 * merchant master record (UNIQUE (tenant_id, internal_id)) carrying a single MID.
 * A legal entity trading under several MIDs therefore ranks as several entries;
 * there is no legal-entity or merchant-group key in dim_merchant to roll up to.
 * Stores and terminals do NOT fan the totals out: sum_daily_merchant is unique on
 * (tenant_id, business_date, merchant_id), so the join cannot multiply rows.
 *
 * ONBOARDING DATE: dim_merchant.date_of_onboarding (the master file's "Date of
 * Onboarding"), falling back to the merchant's first revenue-bearing
 * business_date when the master never carried one. created_date is deliberately
 * NOT the fallback — it is the CRM/ETL record-creation stamp, and a back-loaded
 * master file stamps every merchant with the load date. The New Merchants and
 * Signed-by-RM boards both key off this effective date.
 *
 * ELIGIBILITY: volume-ranked boards only rank merchants with volume > 0 in the
 * window. Signed-by-RM counts every merchant onboarded in the window, traded or
 * not — signing credit does not depend on the merchant having transacted yet.
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

    @Autowired
    private com.acquira.common.service.ReportCache reportCache;

    /** Serializes the resolved filter DTO into a stable cache-key suffix. */
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

        // Key on the RESOLVED window (period defaults derive from today's date)
        // and the resolved filter DTO, so equivalent requests share an entry.
        LocalDate[] curWindow = resolveWindow(period, from, to);
        boolean cardGrain = usesCardFilters(filter);
        final VolumeRevenueFilterDTO f = filter;
        String fk;
        try {
            fk = objectMapper.writeValueAsString(filter);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            fk = null;
        }
        if (fk == null) {
            return buildTopPerformers(f, tenantId, cardGrain, curWindow, period);
        }
        // period is part of the key even though the window is already resolved:
        // the payload echoes response.put("period", period), so period=MTD and
        // an equivalent explicit from/to must not share an entry or one caller
        // reads the other's period label.
        return reportCache.get(
                com.acquira.common.config.ReportCacheConfig.CACHE_REPORT_DATA,
                "topPerformers:" + tenantId + ":" + curWindow[0] + ":" + curWindow[1]
                        + ":" + cardGrain + ":" + period + ":" + fk,
                () -> buildTopPerformers(f, tenantId, cardGrain, curWindow, period));
    }

    private Map<String, Object> buildTopPerformers(VolumeRevenueFilterDTO filter, Long tenantId,
            boolean cardGrain, LocalDate[] curWindow, String period) {

        List<Map<String, Object>> current = runAggregate(filter, tenantId, cardGrain, curWindow[0], curWindow[1]);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("grain", cardGrain ? "insight" : "merchant");
        response.put("period", period);
        response.put("from", curWindow[0].toString());
        response.put("to", curWindow[1].toString());

        // Only merchants with actual volume are eligible for the "top" boards —
        // zero-volume merchants would just clutter a volume/net-revenue ranking.
        List<Map<String, Object>> withVolume = current.stream()
                .filter(r -> toDouble(r.get("volume")) > 0)
                .collect(Collectors.toList());

        response.put("topMerchantsByVolume", rank(withVolume, "volume", TOP_N));
        response.put("topMerchantsByNetRevenue", rank(withVolume, "netRevenue", TOP_N));
        response.put("topMerchantsByTxns", rank(withVolume, "txns", TOP_N));

        Map<String, String> displayNames = agentDisplayNames(tenantId);
        List<Map<String, Object>> rmAgg = groupByRm(withVolume, displayNames);
        response.put("topRmsByVolume", rank(rmAgg, "volume", TOP_N));
        response.put("topRmsByNetRevenue", rank(rmAgg, "netRevenue", TOP_N));

        response.put("topMccs", topMccs(filter, tenantId, curWindow[0], curWindow[1]));

        // Effective onboarding dates back both remaining onboarding boards.
        Map<String, LocalDate> firstTxn = firstTxnDates(tenantId, cardGrain, curWindow[0], curWindow[1]);
        response.put("topSignedByRm", computeSignedByRm(current, firstTxn,
                curWindow[0], curWindow[1], displayNames));
        response.put("topNewMerchants", computeNewMerchants(withVolume, firstTxn,
                curWindow[0], curWindow[1]));

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
     * The comparison window preceding {@code cur}, used by callers that need a
     * like-for-like prior period. Calendar-aligned windows step back a whole
     * month (a full month compares with the whole previous month; a month-to-date
     * with the same opening days, clamped for short months); anything else shifts
     * back by its own length. Package-private: covered by TopPerformersWindowTest.
     */
    LocalDate[] priorWindow(LocalDate[] cur) {
        LocalDate from = cur[0], to = cur[1];

        boolean monthAligned = from.getDayOfMonth() == 1
                && from.getMonthValue() == to.getMonthValue()
                && from.getYear() == to.getYear();

        if (monthAligned) {
            LocalDate priorFrom = from.minusMonths(1);
            LocalDate priorTo = to.getDayOfMonth() == to.lengthOfMonth()
                    ? priorFrom.withDayOfMonth(priorFrom.lengthOfMonth())
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
        sql.append("       m.date_of_onboarding AS dateOfOnboarding, ");
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
        // Open Date drawer filter — was accepted and silently ignored here, the
        // same class of bug already fixed for Industry below.
        if (f.getOpenDateStart() != null) sql.append("  AND CAST(COALESCE(m.date_of_onboarding, m.created_date) AS DATE) >= :openStart ");
        if (f.getOpenDateEnd() != null) sql.append("  AND CAST(COALESCE(m.date_of_onboarding, m.created_date) AS DATE) <= :openEnd ");
        if (needMcc) sql.append("  AND EXISTS (SELECT 1 FROM dim_store st WHERE st.merchant_id = m.merchant_id AND st.tenant_id = m.tenant_id AND st.mcc IN (:mccs)) ");
        if (needSid) sql.append("  AND EXISTS (SELECT 1 FROM dim_store st2 WHERE st2.merchant_id = m.merchant_id AND st2.tenant_id = m.tenant_id AND st2.sid IN (:sids)) ");
        if (needIndustry) sql.append("  AND EXISTS (SELECT 1 FROM dim_store st3 WHERE st3.merchant_id = m.merchant_id AND st3.tenant_id = m.tenant_id "
                + "AND st3.mcc IN (SELECT mcc FROM ref_mcc_category WHERE category IN (:industries))) ");
        sql.append("GROUP BY m.merchant_id, m.name, m.mid, m.sales_email, m.sales_user_id, m.referral_partner, m.created_date, m.date_of_onboarding");

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
        if (f.getOpenDateStart() != null) q.setParameter("openStart", f.getOpenDateStart());
        if (f.getOpenDateEnd() != null) q.setParameter("openEnd", f.getOpenDateEnd());
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
            m.put("dateOfOnboarding", row[7] != null ? row[7].toString() : null);
            m.put("volume", toDouble(row[8]));
            m.put("txns", toDouble(row[9]));
            m.put("msf", toDouble(row[10]));
            m.put("netRevenue", toDouble(row[11]));
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

    /**
     * The merchant's effective onboarding date: dim_merchant.date_of_onboarding
     * when the master file carried one, else the first revenue-bearing
     * business_date (already window-restricted by firstTxnDates). created_date is
     * deliberately not a fallback here — see the class javadoc.
     */
    private LocalDate onboardDate(Map<String, Object> r, Map<String, LocalDate> firstTxnDates) {
        String s = (String) r.get("dateOfOnboarding");
        if (s != null && s.length() >= 10) {
            try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception ignored) { }
        }
        return firstTxnDates.get(idKey(r.get("merchantId")));
    }

    private boolean inWindow(LocalDate d, LocalDate from, LocalDate to) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    /**
     * RMs ranked by how many merchants they signed (effective onboarding date
     * inside the window), regardless of whether those merchants have traded yet —
     * signing credit is about the book growing, not first-month volume. Volume of
     * the signed merchants is carried alongside as a tie-breaker and display
     * figure. Grouped by sales_user_id (the rep CODE), same key as every other RM
     * board; merchants with no sales_user_id are unassigned and excluded.
     */
    private List<Map<String, Object>> computeSignedByRm(List<Map<String, Object>> current,
            Map<String, LocalDate> firstTxnDates, LocalDate from, LocalDate to,
            Map<String, String> displayNames) {
        Map<String, Map<String, Object>> byRm = new LinkedHashMap<>();
        for (Map<String, Object> r : current) {
            if (!inWindow(onboardDate(r, firstTxnDates), from, to)) continue;
            String rm = (String) r.get("salesUserId");
            if (rm == null || rm.isBlank()) continue;
            Map<String, Object> agg = byRm.computeIfAbsent(rm, k -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("salesUserId", k);
                a.put("name", displayNames.getOrDefault(k, k));
                a.put("signedCount", 0);
                a.put("volume", 0.0);
                return a;
            });
            agg.put("signedCount", (Integer) agg.get("signedCount") + 1);
            agg.put("volume", toDouble(agg.get("volume")) + toDouble(r.get("volume")));
        }
        List<Map<String, Object>> ranked = byRm.values().stream()
                .sorted((a, b) -> {
                    int byCount = Integer.compare((Integer) b.get("signedCount"), (Integer) a.get("signedCount"));
                    return byCount != 0 ? byCount
                            : Double.compare(toDouble(b.get("volume")), toDouble(a.get("volume")));
                })
                .limit(TOP_N)
                .collect(Collectors.toList());
        int rank = 1;
        for (Map<String, Object> r : ranked) { r.put("rank", rank++); }
        return ranked;
    }

    /**
     * Merchants whose effective onboarding date falls inside the window, ranked by
     * volume. The caller passes `withVolume`, so zero-volume merchants are already
     * excluded: an onboarded-but-not-yet-trading merchant belongs on the
     * signed-by-RM count, not on a volume ranking, and showing it with a 0 value
     * was why this board was the only one still populated when a window had no
     * transactions.
     */
    private List<Map<String, Object>> computeNewMerchants(List<Map<String, Object>> withVolume,
            Map<String, LocalDate> firstTxnDates, LocalDate from, LocalDate to) {
        List<Map<String, Object>> newOnes = withVolume.stream()
                .filter(r -> inWindow(onboardDate(r, firstTxnDates), from, to))
                .sorted((a, b) -> Double.compare(toDouble(b.get("volume")), toDouble(a.get("volume"))))
                .limit(TOP_N)
                .map(LinkedHashMap::new)
                .collect(Collectors.toList());
        int rank = 1;
        for (Map<String, Object> r : newOnes) {
            r.put("rank", rank++);
            LocalDate d = onboardDate(r, firstTxnDates);
            r.put("onboardingDate", d != null ? d.toString() : null);
        }
        return newOnes;
    }

    /**
     * merchant_id → first business_date on which the merchant recorded positive volume,
     * restricted to merchants whose first such date is inside [from, to]. Serves as the
     * onboarding-date fallback for merchants whose master file carried no
     * "Date of Onboarding".
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
