package com.acquira.core.controller;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Top Performers — Top 10 Merchants (Volume + Net Revenue), Top RMs (Volume +
 * Net Revenue), Top Movers (up/down vs prior window), Top 10 New Merchants,
 * and a concentration KPI strip — all in one round trip.
 *
 * PERFORMANCE: exactly two aggregation queries total (current window + prior
 * window). Each is a single tenant-scoped, partition-pruned GROUP BY over the
 * chosen base table. Every board is derived from those two result sets in
 * memory afterwards — never re-queried per board.
 *
 * GRAIN: sum_daily_merchant (settlement total_base_volume; real net revenue =
 * msf - interchange - scheme_fee) unless a card-level filter (scheme/cardType/
 * destination/channel) is set, in which case we switch to sum_daily_insight
 * (cardholder total_volume; net revenue there is approximated as MSF since
 * interchange/scheme are always 0 on that table) — the same dual-grain
 * convention used by AnalyticsController#heatmap-filtered.
 */
@RestController
@RequestMapping("/api/business")
public class TopPerformersController {

    private static final int TOP_N = 10;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private com.acquira.core.service.SalesTeamService salesTeamService;

    @Autowired
    private com.acquira.core.service.TenantService tenantService;

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
        boolean priorHasData = countRows(tenantId, cardGrain, priorWindow[0], priorWindow[1]) > 0;
        List<Map<String, Object>> prior = priorHasData
                ? runAggregate(filter, tenantId, cardGrain, priorWindow[0], priorWindow[1])
                : Collections.emptyList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("grain", cardGrain ? "insight" : "merchant");
        response.put("period", period);
        response.put("from", curWindow[0].toString());
        response.put("to", curWindow[1].toString());
        response.put("priorWindowHasData", priorHasData);

        // Only merchants with actual volume are eligible for the "top" boards —
        // zero-volume merchants would just clutter a volume/net-revenue ranking.
        List<Map<String, Object>> withVolume = current.stream()
                .filter(r -> toDouble(r.get("volume")) > 0)
                .collect(Collectors.toList());

        response.put("topMerchantsByVolume", rank(withVolume, "volume", TOP_N));
        response.put("topMerchantsByNetRevenue", rank(withVolume, "netRevenue", TOP_N));

        List<Map<String, Object>> rmAgg = groupByRm(withVolume);
        response.put("topRmsByVolume", rank(rmAgg, "volume", TOP_N));
        response.put("topRmsByNetRevenue", rank(rmAgg, "netRevenue", TOP_N));

        response.put("topMovers", priorHasData ? computeMovers(current, prior) : null);
        response.put("topNewMerchants", computeNewMerchants(current, curWindow[0], curWindow[1]));

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

        return response;
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

    private LocalDate[] priorWindow(LocalDate[] cur) {
        long days = ChronoUnit.DAYS.between(cur[0], cur[1]) + 1;
        LocalDate priorTo = cur[0].minusDays(1);
        LocalDate priorFrom = priorTo.minusDays(days - 1);
        return new LocalDate[]{ priorFrom, priorTo };
    }

    private boolean usesCardFilters(VolumeRevenueFilterDTO f) {
        return notEmpty(f.getSchemeList()) || notEmpty(f.getCardTypeList())
                || notEmpty(f.getDestinationList()) || notEmpty(f.getChannelList());
    }

    private static boolean notEmpty(List<String> l) { return l != null && !l.isEmpty(); }

    // ─────────────────────────────────────────────────────────────
    // Aggregate query — ONE grouped scan per window
    // ─────────────────────────────────────────────────────────────

    private long countRows(Long tenantId, boolean cardGrain, LocalDate from, LocalDate to) {
        String table = cardGrain ? "sum_daily_insight" : "sum_daily_merchant";
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = :tid AND business_date BETWEEN :from AND :to";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("tid", tenantId);
        q.setParameter("from", from);
        q.setParameter("to", to);
        return ((Number) q.getSingleResult()).longValue();
    }

    private List<Map<String, Object>> runAggregate(VolumeRevenueFilterDTO f, Long tenantId,
            boolean cardGrain, LocalDate from, LocalDate to) {

        boolean needMcc = notEmpty(f.getMccList());
        boolean needSid = notEmpty(f.getSidList());

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

    private List<Map<String, Object>> groupByRm(List<Map<String, Object>> merchantRows) {
        Map<String, Map<String, Object>> byRm = new LinkedHashMap<>();
        for (Map<String, Object> r : merchantRows) {
            String rm = (String) r.get("salesEmail");
            if (rm == null || rm.isBlank()) continue;
            Map<String, Object> agg = byRm.computeIfAbsent(rm, k -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("salesEmail", k);
                a.put("volume", 0.0);
                a.put("netRevenue", 0.0);
                a.put("msf", 0.0);
                a.put("merchantCount", 0);
                return a;
            });
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
        Map<Object, Double> priorVolByMerchant = new HashMap<>();
        for (Map<String, Object> r : prior) priorVolByMerchant.put(r.get("merchantId"), toDouble(r.get("volume")));

        // Noise floor: 25th percentile of current non-zero volumes, so tiny
        // merchants' wild swings don't drown out genuinely material movers.
        List<Double> vols = current.stream().map(r -> toDouble(r.get("volume"))).filter(v -> v > 0)
                .sorted().collect(Collectors.toList());
        double floor = vols.isEmpty() ? 0 : vols.get(Math.max(0, (int) (vols.size() * 0.25) - 1));

        List<Map<String, Object>> movers = new ArrayList<>();
        for (Map<String, Object> r : current) {
            double curVol = toDouble(r.get("volume"));
            if (curVol < floor) continue;
            Double prevVol = priorVolByMerchant.get(r.get("merchantId"));
            if (prevVol == null || prevVol <= 0) continue; // need a real prior baseline for a % move
            double pct = round2((curVol - prevVol) / prevVol * 100);
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("priorVolume", prevVol);
            m.put("volumeChangePct", pct);
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

    private List<Map<String, Object>> computeNewMerchants(List<Map<String, Object>> current, LocalDate from, LocalDate to) {
        List<Map<String, Object>> newOnes = current.stream()
                .filter(r -> {
                    String cd = (String) r.get("createdDate");
                    if (cd == null) return false;
                    LocalDate d = LocalDate.parse(cd.length() > 10 ? cd.substring(0, 10) : cd);
                    return !d.isBefore(from) && !d.isAfter(to);
                })
                .sorted((a, b) -> Double.compare(toDouble(b.get("volume")), toDouble(a.get("volume"))))
                .limit(TOP_N)
                .map(LinkedHashMap::new)
                .collect(Collectors.toList());
        int rank = 1;
        for (Map<String, Object> r : newOnes) r.put("rank", rank++);
        return newOnes;
    }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
