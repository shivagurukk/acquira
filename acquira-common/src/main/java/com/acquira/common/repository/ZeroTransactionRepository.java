package com.acquira.common.repository;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDate;

/**
 * Zero Transaction Report — terminal-grain "who has gone quiet" report over
 * sum_daily_terminal (last activity per terminal) joined up the
 * dim_terminal -> dim_store -> dim_merchant chain.
 *
 * [2026-07-06 FLOW FIX] Three correctness bugs repaired:
 *
 *  1. ANCHOR — all cutoffs were CURRENT_DATE / LocalDate.now(). When ingested
 *     data lags the calendar (latest business_date 2026-06-25 vs today
 *     2026-07-06), EVERY terminal — including ones that transacted on the very
 *     latest data day — sat "past" the 7-day cutoff, so 'Last 7 Days' flagged
 *     the whole portfolio and every days-inactive figure was inflated by the
 *     lag. All windows now anchor on the tenant's LATEST DATA DATE
 *     (MAX(business_date) in sum_daily_terminal), falling back to today only
 *     when the tenant has no data. The anchor is returned as "asOf".
 *
 *  2. STATUS × RANGE CONTRADICTION — /page and /summary AND-combined the
 *     range predicate with the status predicate. Under the default LAST_30
 *     range (last_txn < cutoff30 OR NULL), the IN7 bucket
 *     (cutoff30 <= last_txn < cutoff7) is disjoint from the universe, so the
 *     'Inactive 7–30' chip always counted 0 and its tab was always empty; the
 *     ≤14d / 15–30d distribution bars were structurally zero too. Now: chip
 *     counts and the recency distribution are computed UNRANGED over the
 *     filtered base (each bucket carries its own complete predicate); the
 *     range only scopes 'total' and the status=ALL table. A non-ALL status
 *     fully determines the bucket and is applied alone.
 *
 *  3. Legacy smart-list had LIMIT without ORDER BY (arbitrary rows). Ordered.
 *
 * Tenant scoping is unchanged: outer joins are all pinned to :tenantId and
 * every inner sum_daily_terminal subquery is independently scoped.
 */
@Repository
public class ZeroTransactionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    // ============================================================
    // Data anchor: the tenant's latest business_date. All "days
    // inactive" / cutoff math is relative to THIS, not the calendar.
    // ============================================================
    private LocalDate resolveAnchor(Long tenantId) {
        try {
            String sql = "SELECT MAX(business_date) FROM sum_daily_terminal"
                    + (tenantId != null ? " WHERE tenant_id = :tenantId" : "");
            Query q = entityManager.createNativeQuery(sql);
            if (tenantId != null) q.setParameter("tenantId", tenantId);
            Object r = q.getSingleResult();
            if (r instanceof java.sql.Date d) return d.toLocalDate();
            if (r instanceof LocalDate ld) return ld;
        } catch (Exception ignored) { /* fall through to today */ }
        return LocalDate.now();
    }

    // ============================================================
    // Legacy list endpoints (kept for compatibility; the UI uses
    // /summary + /page below).
    // ============================================================

    /** Fail closed: a null tenant must never silently widen a query to every tenant. */
    private static void requireTenant(Long tenantId) {
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved — refusing unscoped query");
    }

    /**
     * Tenant-scoped variant. When tenantId is non-null, the join chain
     * (dim_terminal -> dim_store -> dim_merchant) is filtered to that tenant
     * AND the inner sum_daily_terminal subquery is also scoped, preventing
     * cross-tenant rows from leaking through any path.
     */
    public List<Map<String, Object>> getZeroTransactionList(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");
        sql.append("  m.name as merchant_name, ");
        sql.append("  COALESCE(st.legal_name, m.name) as entity_name, ");
        sql.append("  m.referral_partner as aggregator_name, ");
        sql.append("  m.referral_partner as aggregator_code, ");
        sql.append("  m.mid as mid, ");
        sql.append("  st.sid as sid, ");
        sql.append("  st.name as store_name, ");
        sql.append("  t.tid as terminal_id, ");
        sql.append(
                "  (SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id"
                + (tenantId != null ? " AND s.tenant_id = :tenantId" : "")
                + ") as last_txn_date ");
        sql.append("FROM dim_terminal t ");
        sql.append("JOIN dim_store st ON t.store_id = st.store_id ");
        sql.append("JOIN dim_merchant m ON st.merchant_id = m.merchant_id ");
        sql.append("WHERE 1=1 ");

        if (tenantId != null) {
            sql.append("AND m.tenant_id = :tenantId ");
            sql.append("AND st.tenant_id = :tenantId ");
            sql.append("AND t.tenant_id = :tenantId ");
        }

        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty()) {
            sql.append("AND m.referral_partner IN (:partners) ");
        }
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank()) {
            sql.append("AND (m.name ILIKE :merchName OR st.legal_name ILIKE :merchName) ");
        }
        if (filter.getMidList() != null && !filter.getMidList().isEmpty()) {
            sql.append("AND m.mid IN (:mids) ");
        }
        if (filter.getSidList() != null && !filter.getSidList().isEmpty()) {
            sql.append("AND st.sid IN (:sids) ");
        }
        if (filter.getTidList() != null && !filter.getTidList().isEmpty()) {
            sql.append("AND t.tid IN (:tids) ");
        }

        sql.append("ORDER BY m.mid, t.tid LIMIT 1000");

        Query query = entityManager.createNativeQuery(sql.toString());
        if (tenantId != null) query.setParameter("tenantId", tenantId);
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if (filter.getMidList() != null && !filter.getMidList().isEmpty())
            query.setParameter("mids", filter.getMidList());
        if (filter.getSidList() != null && !filter.getSidList().isEmpty())
            query.setParameter("sids", filter.getSidList());
        if (filter.getTidList() != null && !filter.getTidList().isEmpty())
            query.setParameter("tids", filter.getTidList());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        // NOTE: legacy list selects 9 columns (extra aggregator_code at index 3);
        // remap to the 8-column shape processResults expects.
        List<Object[]> remapped = new ArrayList<>();
        for (Object[] r : rows) {
            remapped.add(new Object[] { r[0], r[1], r[2], r[4], r[5], r[6], r[7], r[8] });
        }
        return processResults(remapped, resolveAnchor(tenantId));
    }

    public List<Map<String, Object>> getZeroTransactionListSmart(VolumeRevenueFilterDTO filter, String rangeType, Long tenantId) {
        requireTenant(tenantId);
        // rangeType: "LAST_7", "LAST_30", "NEVER"
        LocalDate anchor = resolveAnchor(tenantId);
        final String innerTenant = (tenantId != null) ? " AND s.tenant_id = :tenantId" : "";

        StringBuilder sql = new StringBuilder();
        sql.append(
                "SELECT m.name, COALESCE(st.legal_name, m.name), m.referral_partner, m.mid, st.sid, st.name, t.tid, ");
        sql.append(
                "(SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id" + innerTenant + ") as last_txn ");
        sql.append("FROM dim_terminal t ");
        sql.append("JOIN dim_store st ON t.store_id = st.store_id ");
        sql.append("JOIN dim_merchant m ON st.merchant_id = m.merchant_id ");
        sql.append("WHERE 1=1 ");

        if (tenantId != null) {
            sql.append("AND m.tenant_id = :tenantId ");
            sql.append("AND st.tenant_id = :tenantId ");
            sql.append("AND t.tenant_id = :tenantId ");
        }

        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty()) {
            sql.append("AND m.referral_partner IN (:partners) ");
        }
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank()) {
            sql.append("AND (m.name ILIKE :merchName OR st.legal_name ILIKE :merchName) ");
        }

        if ("NEVER".equals(rangeType)) {
            sql.append(
                    "AND (SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id" + innerTenant + ") IS NULL ");
        } else if ("LAST_7".equals(rangeType)) {
            sql.append(
                    "AND ((SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id" + innerTenant + ") < :cutoff7 ");
            sql.append(
                    "     OR (SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id" + innerTenant + ") IS NULL) ");
        } else if ("LAST_30".equals(rangeType)) {
            sql.append(
                    "AND ((SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id" + innerTenant + ") < :cutoff30 ");
            sql.append(
                    "     OR (SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id" + innerTenant + ") IS NULL) ");
        }

        sql.append("ORDER BY m.mid, t.tid LIMIT 500");

        Query query = entityManager.createNativeQuery(sql.toString());
        if (tenantId != null) query.setParameter("tenantId", tenantId);
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
        if ("LAST_7".equals(rangeType)) {
            query.setParameter("cutoff7", anchor.minusDays(7));
        } else if ("LAST_30".equals(rangeType)) {
            query.setParameter("cutoff30", anchor.minusDays(30));
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return processResults(rows, anchor);
    }

    // ============================================================
    // Accurate, cap-free summary + server-side pagination.
    // A CTE computes each terminal's last_txn once, then counts /
    // buckets / aggregators / page rows all read from it.
    // ============================================================

    /**
     * base = one row per terminal in the filtered estate.
     *
     * [ESTATE HEALTH] The CTE now also carries each terminal's TRAILING-30-DAY
     * activity (txns / volume / days-with-activity), sourced from a single
     * LEFT JOIN LATERAL over the same (tenant_id, terminal_id, business_date)
     * index the last_txn subquery already rides. This is what separates a
     * dormancy report from an estate-health report: "last_txn" only answers
     * *when did it stop*, while active_days30 answers *was it ever really
     * working* — a terminal that fired on 2 of the last 30 days is a sick
     * terminal even though it is technically "active".
     *
     * last_txn is deliberately left as the original correlated subquery — every
     * range/status/bucket predicate in this class is defined against it and has
     * been debugged against real data (see the 2026-07-06 FLOW FIX above). The
     * lateral only ADDS columns; it changes no existing predicate.
     *
     * The 30-day window reuses :cutoff30 (= anchor - 30d) rather than binding a
     * second identical parameter, so it always resolves for every caller.
     *
     * withActivity gates the lateral. Postgres will NOT remove a LEFT JOIN
     * LATERAL whose output columns go unread (join removal only applies to base
     * relations with a proving unique index), so leaving it in unconditionally
     * would charge every callsite a second per-terminal index probe — including
     * /summary and the pagination COUNT, neither of which reads those columns.
     * Callers that need trailing activity pass true; everyone else pays nothing.
     */
    private String baseCte(VolumeRevenueFilterDTO f, Long tenantId, boolean withActivity) {
        String innerTenant = (tenantId != null) ? " AND s.tenant_id = :tenantId" : "";
        StringBuilder b = new StringBuilder();
        b.append("WITH base AS (SELECT m.name AS merchant_name, COALESCE(st.legal_name, m.name) AS entity_name, ");
        b.append("m.referral_partner AS aggregator_name, m.mid AS mid, st.sid AS sid, st.name AS store_name, t.tid AS tid, ");
        b.append("(SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id")
         .append(innerTenant).append(") AS last_txn");
        if (withActivity) {
            // terminal_key is the numeric surrogate — needed so the estate trend
            // query can join sum_daily_terminal back to the filtered base.
            b.append(", t.terminal_id AS terminal_key, ");
            b.append("COALESCE(act.txns30, 0) AS txns30, COALESCE(act.vol30, 0) AS vol30, ");
            b.append("COALESCE(act.active_days30, 0) AS active_days30");
        }
        b.append(" FROM dim_terminal t JOIN dim_store st ON t.store_id = st.store_id ");
        b.append("JOIN dim_merchant m ON st.merchant_id = m.merchant_id ");
        if (withActivity) {
            b.append("LEFT JOIN LATERAL (SELECT SUM(s.total_txns) AS txns30, SUM(s.total_volume) AS vol30, ");
            b.append("COUNT(DISTINCT s.business_date) FILTER (WHERE COALESCE(s.total_txns, 0) > 0) AS active_days30 ");
            b.append("FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id").append(innerTenant);
            b.append(" AND s.business_date > :cutoff30 AND s.business_date <= :anchorDate) act ON TRUE ");
        }
        b.append("WHERE 1=1 ");
        if (tenantId != null) b.append("AND m.tenant_id = :tenantId AND st.tenant_id = :tenantId AND t.tenant_id = :tenantId ");
        if (f.getPartnerList() != null && !f.getPartnerList().isEmpty()) b.append("AND m.referral_partner IN (:partners) ");
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank()) b.append("AND (m.name ILIKE :merchName OR st.legal_name ILIKE :merchName) ");
        if (f.getMidList() != null && !f.getMidList().isEmpty()) b.append("AND m.mid IN (:mids) ");
        if (f.getSidList() != null && !f.getSidList().isEmpty()) b.append("AND st.sid IN (:sids) ");
        if (f.getTidList() != null && !f.getTidList().isEmpty()) b.append("AND t.tid IN (:tids) ");
        b.append(") ");
        return b.toString();
    }

    /** Bare range condition (no leading AND) — the report's universe. */
    private String rangeCondition(String rangeType) {
        if ("NEVER".equals(rangeType)) return "(last_txn IS NULL)";
        if ("LAST_7".equals(rangeType)) return "(last_txn < :cutoff7 OR last_txn IS NULL)";
        return "(last_txn < :cutoff30 OR last_txn IS NULL)"; // LAST_30 (default)
    }

    private String rangePredicate(String rangeType) {
        return " AND " + rangeCondition(rangeType) + " ";
    }

    /**
     * Status buckets are SELF-CONTAINED classifications relative to the anchor:
     *   NEVER = no activity ever; IN30 = lapsed more than 30 days; IN7 = lapsed
     *   7–30 days. Applied ALONE (never AND-ed with the range) so a bucket tab
     *   can never contradict the selected range and return a false empty set.
     */
    private String statusPredicate(String status) {
        if (status == null) return "";
        switch (status) {
            case "NEVER": return " AND last_txn IS NULL ";
            case "IN30":  return " AND last_txn < :cutoff30 ";
            case "IN7":   return " AND last_txn >= :cutoff30 AND last_txn < :cutoff7 ";
            default:      return ""; // ALL
        }
    }

    /** Bind only the params actually present in the SQL (avoids "parameter not found"). */
    private void bindCommon(Query q, String sql, VolumeRevenueFilterDTO f, Long tenantId, LocalDate anchor) {
        if (sql.contains(":tenantId")) q.setParameter("tenantId", tenantId);
        if (sql.contains(":partners")) q.setParameter("partners", f.getPartnerList());
        if (sql.contains(":merchName")) q.setParameter("merchName", "%" + f.getMerchantName() + "%");
        if (sql.contains(":mids")) q.setParameter("mids", f.getMidList());
        if (sql.contains(":sids")) q.setParameter("sids", f.getSidList());
        if (sql.contains(":tids")) q.setParameter("tids", f.getTidList());
        if (sql.contains(":cutoff7")) q.setParameter("cutoff7", anchor.minusDays(7));
        if (sql.contains(":cutoff30")) q.setParameter("cutoff30", anchor.minusDays(30));
        if (sql.contains(":anchorDate")) q.setParameter("anchorDate", anchor);
    }

    private long num(Object o) { return (o instanceof Number) ? ((Number) o).longValue() : 0L; }

    private Map<String, Object> bucket(String label, long count) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", label); m.put("count", count);
        return m;
    }

    /**
     * Counts + days-inactive distribution + top aggregators over the FULL
     * filtered set. Bucket counts (never/in30/in7) and the recency distribution
     * are computed UNRANGED with self-contained predicates; only 'total' — the
     * headline "inactive in the selected range" figure — applies rangeType.
     * Distribution days are relative to the DATA anchor, not the calendar.
     */
    public Map<String, Object> getZeroTransactionSummary(VolumeRevenueFilterDTO f, String rangeType, Long tenantId) {
        requireTenant(tenantId);
        LocalDate anchor = resolveAnchor(tenantId);
        // Dormancy counts read last_txn only — no trailing-activity lateral needed.
        String cte = baseCte(f, tenantId, false);
        String rangeCond = rangeCondition(rangeType);

        // Counts come in TWO grains:
        //   *_t  = terminal-grain (one row per dim_terminal) — the drill-down unit.
        //   *_m  = merchant-grain (COUNT(DISTINCT mid))       — the headline unit.
        // A churn/dormancy report headlines MERCHANTS: one merchant with 20 idle
        // terminals is ONE dormant merchant, not twenty. The old report only had
        // terminal counts, so a single never-transacted test merchant read as a
        // portfolio of 20 inactive entities.
        //
        // Distribution buckets are DORMANCY buckets — they must exclude terminals
        // that are still active. Dormancy begins after the 7-day floor (the same
        // threshold processResults uses for row status: days > 7). The old ≤14d
        // bucket counted terminals whose last txn was within 14 days of the anchor,
        // i.e. recently-active terminals that don't belong in this report at all —
        // that was the stray blue bar sitting next to "Never".
        String countSql = cte +
            "SELECT COUNT(*) FILTER (WHERE " + rangeCond + ") AS total_t, " +
            "COUNT(DISTINCT mid) FILTER (WHERE " + rangeCond + ") AS total_m, " +
            "COUNT(*) FILTER (WHERE last_txn IS NULL) AS never_t, " +
            "COUNT(DISTINCT mid) FILTER (WHERE last_txn IS NULL) AS never_m, " +
            "COUNT(*) FILTER (WHERE last_txn < :cutoff30) AS in30_t, " +
            "COUNT(DISTINCT mid) FILTER (WHERE last_txn < :cutoff30) AS in30_m, " +
            "COUNT(*) FILTER (WHERE last_txn >= :cutoff30 AND last_txn < :cutoff7) AS in7_t, " +
            "COUNT(DISTINCT mid) FILTER (WHERE last_txn >= :cutoff30 AND last_txn < :cutoff7) AS in7_m, " +
            // Dormancy distribution (terminal-grain), floored at >7 days so no
            // active terminal is counted. 8–14 / 15–30 / 31–60 / 61–90 / 90d+ / Never.
            "COUNT(*) FILTER (WHERE last_txn IS NOT NULL AND (:anchorDate - last_txn) BETWEEN 8 AND 14) AS b14, " +
            "COUNT(*) FILTER (WHERE (:anchorDate - last_txn) BETWEEN 15 AND 30) AS b30, " +
            "COUNT(*) FILTER (WHERE (:anchorDate - last_txn) BETWEEN 31 AND 60) AS b60, " +
            "COUNT(*) FILTER (WHERE (:anchorDate - last_txn) BETWEEN 61 AND 90) AS b90, " +
            "COUNT(*) FILTER (WHERE (:anchorDate - last_txn) > 90) AS b90p " +
            "FROM base";
        Query cq = entityManager.createNativeQuery(countSql);
        bindCommon(cq, countSql, f, tenantId, anchor);
        Object[] c = (Object[]) cq.getSingleResult();

        // Top aggregators by dormancy — merchant-grain (distinct dormant merchants
        // per aggregator), with terminal count kept as a secondary figure.
        String aggSql = cte +
            "SELECT COALESCE(aggregator_name, '— Unassigned —') AS agg, " +
            "COUNT(DISTINCT mid) AS c_m, COUNT(*) AS c_t " +
            "FROM base WHERE 1=1 " + rangePredicate(rangeType) +
            " GROUP BY COALESCE(aggregator_name, '— Unassigned —') ORDER BY c_m DESC, c_t DESC LIMIT 6";
        Query aq = entityManager.createNativeQuery(aggSql);
        bindCommon(aq, aggSql, f, tenantId, anchor);
        @SuppressWarnings("unchecked")
        List<Object[]> aggRows = aq.getResultList();

        // Column order from countSql:
        //  [0] total_t  [1] total_m  [2] never_t [3] never_m
        //  [4] in30_t   [5] in30_m   [6] in7_t   [7] in7_m
        //  [8] b14 [9] b30 [10] b60 [11] b90 [12] b90p
        Map<String, Object> out = new HashMap<>();
        out.put("asOf", anchor.toString());
        // Headline counts are MERCHANT-grain. Terminal counts are exposed
        // alongside (…Terminals) so the UI can show "N merchants · M terminals".
        out.put("total", num(c[1]));
        out.put("never", num(c[3]));
        out.put("in30", num(c[5]));
        out.put("in7",  num(c[7]));
        out.put("totalTerminals", num(c[0]));
        out.put("neverTerminals", num(c[2]));
        out.put("in30Terminals",  num(c[4]));
        out.put("in7Terminals",   num(c[6]));

        // Dormancy distribution (terminal-grain). No active-terminal bucket.
        List<Map<String, Object>> dist = new ArrayList<>();
        dist.add(bucket("8–14d", num(c[8])));
        dist.add(bucket("15–30d", num(c[9])));
        dist.add(bucket("31–60d", num(c[10])));
        dist.add(bucket("61–90d", num(c[11])));
        dist.add(bucket("90d+", num(c[12])));
        dist.add(bucket("Never", num(c[2])));
        out.put("distribution", dist);

        List<Map<String, Object>> aggs = new ArrayList<>();
        for (Object[] r : aggRows) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", r[0]);
            m.put("count", num(r[1]));           // merchant-grain (primary)
            m.put("terminals", num(r[2]));       // terminal-grain (secondary)
            aggs.add(m);
        }
        out.put("topAggregators", aggs);
        return out;
    }

    /**
     * Server-side paginated rows (risk-ordered: Inactive 30+ first, then Never,
     * then 7–30) + total. status=ALL pages the RANGE universe; any other status
     * pages that bucket ALONE (a bucket fully determines its own window).
     */
    public Map<String, Object> getZeroTransactionPage(VolumeRevenueFilterDTO f, String rangeType, String status,
                                                      int page, int size, Long tenantId) {
        requireTenant(tenantId);
        LocalDate anchor = resolveAnchor(tenantId);
        // Rows carry trailing activity; the COUNT does not read it, so it runs
        // against the cheap variant.
        String cte = baseCte(f, tenantId, true);
        String cteLite = baseCte(f, tenantId, false);
        boolean bucketed = status != null && !"ALL".equals(status) && !status.isBlank();
        String pred = bucketed ? statusPredicate(status) : rangePredicate(rangeType);
        int safeSize = Math.min(Math.max(size, 1), 1000);
        int offset = Math.max(page, 0) * safeSize;

        String rowSql = cte +
            "SELECT merchant_name, entity_name, aggregator_name, mid, sid, store_name, tid, last_txn, " +
            "txns30, vol30, active_days30 " +
            "FROM base WHERE 1=1 " + pred +
            " ORDER BY (CASE WHEN last_txn < :cutoff30 THEN 3 WHEN last_txn IS NULL THEN 2 ELSE 1 END) DESC, " +
            " last_txn ASC NULLS LAST, mid ASC, tid ASC LIMIT :size OFFSET :offset";
        Query rq = entityManager.createNativeQuery(rowSql);
        bindCommon(rq, rowSql, f, tenantId, anchor);
        rq.setParameter("size", safeSize);
        rq.setParameter("offset", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = rq.getResultList();

        String countSql = cteLite + "SELECT COUNT(*) FROM base WHERE 1=1 " + pred;
        Query cq = entityManager.createNativeQuery(countSql);
        bindCommon(cq, countSql, f, tenantId, anchor);
        long total = num(cq.getSingleResult());

        Map<String, Object> out = new HashMap<>();
        out.put("content", processResults(rows, anchor));
        out.put("total", total);
        out.put("page", Math.max(page, 0));
        out.put("size", safeSize);
        out.put("asOf", anchor.toString());
        return out;
    }

    // ============================================================
    // TERMINAL / POS ESTATE HEALTH
    // ============================================================

    /**
     * Estate health over the FULL filtered terminal base — deliberately
     * range-independent.
     *
     * The rest of this report only ever looks at the inactive slice, which
     * means it can tell you 400 terminals went quiet but never tells you
     * whether that is 400 out of 500 (the estate is collapsing) or 400 out of
     * 40,000 (routine churn). Estate health supplies that denominator, plus the
     * gradient the dormancy view is blind to:
     *
     *   ACTIVE   last txn within 7d of the anchor
     *   IDLE     last txn 7–30d ago   → still recoverable, still carrying volume
     *   DORMANT  last txn > 30d ago
     *   NEVER    no txn on record
     *
     * On top of that, "utilization" splits ACTIVE terminals by how many of the
     * trailing 30 days they actually fired on. A terminal that transacted twice
     * in a month is functionally dead hardware sitting on a merchant counter —
     * it never appears in a zero-transaction report, and it is exactly the row a
     * field/estate team wants to visit.
     *
     * volumeAtRisk is the trailing-30d volume of the IDLE bucket only. Dormant
     * and never terminals contribute nothing over that window by definition, so
     * quoting a figure for them would be fabricated; the honest number is the
     * volume that is still on the table and still saveable.
     */
    public Map<String, Object> getEstateHealth(VolumeRevenueFilterDTO f, Long tenantId) {
        requireTenant(tenantId);
        LocalDate anchor = resolveAnchor(tenantId);
        String cte = baseCte(f, tenantId, true);

        String sql = cte +
            "SELECT COUNT(*) AS total_t, COUNT(DISTINCT mid) AS total_m, " +
            "COUNT(*) FILTER (WHERE last_txn >= :cutoff7) AS active_t, " +
            "COUNT(DISTINCT mid) FILTER (WHERE last_txn >= :cutoff7) AS active_m, " +
            "COUNT(*) FILTER (WHERE last_txn >= :cutoff30 AND last_txn < :cutoff7) AS idle_t, " +
            "COUNT(*) FILTER (WHERE last_txn < :cutoff30) AS dormant_t, " +
            "COUNT(*) FILTER (WHERE last_txn IS NULL) AS never_t, " +
            // Under-used = still 'active' but fired on 5 or fewer of the last 30 days.
            "COUNT(*) FILTER (WHERE last_txn >= :cutoff7 AND active_days30 <= 5) AS lowuse_t, " +
            "COALESCE(SUM(vol30) FILTER (WHERE last_txn >= :cutoff30 AND last_txn < :cutoff7), 0) AS vol_at_risk, " +
            "COALESCE(SUM(vol30), 0) AS vol_30, COALESCE(SUM(txns30), 0) AS txns_30, " +
            // Utilization spread across the WHOLE estate (terminal grain).
            "COUNT(*) FILTER (WHERE active_days30 = 0) AS u0, " +
            "COUNT(*) FILTER (WHERE active_days30 BETWEEN 1 AND 5) AS u5, " +
            "COUNT(*) FILTER (WHERE active_days30 BETWEEN 6 AND 15) AS u15, " +
            "COUNT(*) FILTER (WHERE active_days30 BETWEEN 16 AND 25) AS u25, " +
            "COUNT(*) FILTER (WHERE active_days30 > 25) AS u30 " +
            "FROM base";
        Query q = entityManager.createNativeQuery(sql);
        bindCommon(q, sql, f, tenantId, anchor);
        Object[] r = (Object[]) q.getSingleResult();

        long totalT = num(r[0]), activeT = num(r[2]), idleT = num(r[4]);
        long dormantT = num(r[5]), neverT = num(r[6]), lowUseT = num(r[7]);

        Map<String, Object> out = new HashMap<>();
        out.put("asOf", anchor.toString());
        out.put("totalTerminals", totalT);
        out.put("totalMerchants", num(r[1]));
        out.put("activeTerminals", activeT);
        out.put("activeMerchants", num(r[3]));
        out.put("idleTerminals", idleT);
        out.put("dormantTerminals", dormantT);
        out.put("neverTerminals", neverT);
        out.put("lowUseTerminals", lowUseT);
        // Estate utilization = share of the estate that transacted in the last 7d.
        out.put("utilizationPct", totalT == 0 ? 0d : Math.round((activeT * 1000d) / totalT) / 10d);
        out.put("volumeAtRisk", dec(r[8]));
        out.put("volume30", dec(r[9]));
        out.put("txns30", num(r[10]));

        List<Map<String, Object>> util = new ArrayList<>();
        util.add(bucket("0 days", num(r[11])));
        util.add(bucket("1–5 days", num(r[12])));
        util.add(bucket("6–15 days", num(r[13])));
        util.add(bucket("16–25 days", num(r[14])));
        util.add(bucket("26–30 days", num(r[15])));
        out.put("utilization", util);

        // Estate composition, ordered healthiest → worst so the UI bar reads
        // left-to-right as decay.
        List<Map<String, Object>> comp = new ArrayList<>();
        comp.add(bucket("Active", activeT));
        comp.add(bucket("Idle 7–30d", idleT));
        comp.add(bucket("Dormant 30d+", dormantT));
        comp.add(bucket("Never", neverT));
        out.put("composition", comp);

        // Daily count of distinct transacting terminals across the trailing 30
        // days — a falling line means the estate is shrinking even while the
        // headline dormancy counts look flat.
        String trendSql = cte +
            "SELECT s.business_date, COUNT(DISTINCT s.terminal_id) AS live_t, " +
            "COALESCE(SUM(s.total_txns), 0) AS txns " +
            "FROM sum_daily_terminal s JOIN base b ON b.terminal_key = s.terminal_id " +
            "WHERE s.tenant_id = :tenantId AND s.business_date > :cutoff30 " +
            "AND s.business_date <= :anchorDate AND COALESCE(s.total_txns, 0) > 0 " +
            "GROUP BY s.business_date ORDER BY s.business_date";
        Query tq = entityManager.createNativeQuery(trendSql);
        bindCommon(tq, trendSql, f, tenantId, anchor);
        @SuppressWarnings("unchecked")
        List<Object[]> trendRows = tq.getResultList();
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Object[] tr : trendRows) {
            Map<String, Object> m = new HashMap<>();
            m.put("date", String.valueOf(tr[0]));
            m.put("terminals", num(tr[1]));
            m.put("txns", num(tr[2]));
            trend.add(m);
        }
        out.put("trend", trend);
        return out;
    }

    private double dec(Object o) { return (o instanceof Number n) ? n.doubleValue() : 0d; }

    /** daysInactive and status thresholds are relative to the DATA anchor. */
    private List<Map<String, Object>> processResults(List<Object[]> rows, LocalDate anchor) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("merchantName", row[0]);
            map.put("entityName", row[1]);
            map.put("aggregatorName", row[2]);
            map.put("aggregatorCode", row[2]); // Using name as code for now
            map.put("mid", row[3]);
            map.put("sid", row[4]);
            map.put("storeName", row[5]);
            map.put("terminalId", row[6]);

            LocalDate lastTxn = null;
            Object d = row[7];
            if (d instanceof java.sql.Date sd) lastTxn = sd.toLocalDate();
            else if (d instanceof LocalDate ld) lastTxn = ld;
            map.put("lastTransactionDate", lastTxn);

            if (lastTxn == null) {
                map.put("status", "Never Transacted");
                map.put("daysInactive", -1);
            } else {
                long days = java.time.temporal.ChronoUnit.DAYS.between(lastTxn, anchor);
                // Guard against a future last_txn (clock/anchor edge cases) so
                // daysInactive is never negative for a transacted terminal.
                if (days < 0) days = 0;
                map.put("daysInactive", days);

                // This report's universe is inactive terminals only (rows are
                // pre-scoped by the range/bucket predicate). Never emit "Active"
                // — the UI has no such chip and would mislabel it. Anything that
                // reaches here at <=30 days is the mildest dormant band.
                if (days > 30) {
                    map.put("status", "Inactive 30+");
                } else {
                    map.put("status", "Inactive 7–30");
                }
            }

            // [ESTATE HEALTH] Trailing-30d activity, present only on the /page
            // shape (11 cols). The two legacy list endpoints build their own
            // 8-column SQL, so guard on length rather than assuming.
            if (row.length > 10) {
                long txns30 = num(row[8]);
                long activeDays = num(row[10]);
                map.put("txns30", txns30);
                map.put("volume30", row[9] instanceof Number bn ? bn.doubleValue() : 0d);
                map.put("activeDays30", activeDays);
                // Utilization = share of the trailing 30 days on which the
                // terminal actually transacted. 0 for dead/never terminals.
                map.put("utilization30", Math.round((activeDays / 30.0) * 1000) / 10.0);
            }

            result.add(map);
        }
        return result;
    }
}
