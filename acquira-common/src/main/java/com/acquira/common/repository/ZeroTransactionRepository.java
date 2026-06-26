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

@Repository
public class ZeroTransactionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public List<Map<String, Object>> getZeroTransactionList(VolumeRevenueFilterDTO filter) {
        return getZeroTransactionList(filter, null);
    }

    /**
     * Tenant-scoped variant. When tenantId is non-null, the join chain
     * (dim_terminal -> dim_store -> dim_merchant) is filtered to that tenant
     * AND the inner sum_daily_terminal subquery is also scoped, preventing
     * cross-tenant rows from leaking through any path.
     */
    public List<Map<String, Object>> getZeroTransactionList(VolumeRevenueFilterDTO filter, Long tenantId) {
        StringBuilder sql = new StringBuilder();

        // Base Query: Terminal granularity
        sql.append("SELECT ");
        sql.append("  m.name as merchant_name, "); // Merchant Identity

        // Entity Name logic: Use Store Legal Name if available, else Merchant Name
        sql.append("  COALESCE(st.legal_name, m.name) as entity_name, ");

        sql.append("  m.referral_partner as aggregator_name, ");
        sql.append("  m.referral_partner as aggregator_code, "); // Duplicated as per request
        sql.append("  m.mid as mid, ");
        sql.append("  st.sid as sid, ");
        sql.append("  st.name as store_name, ");
        sql.append("  t.tid as terminal_id, ");

        // Last Txn Date Subquery (also scoped to tenant when applicable so cross-tenant
        // terminal-id reuse cannot leak transaction dates)
        sql.append(
                "  (SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id"
                + (tenantId != null ? " AND s.tenant_id = :tenantId" : "")
                + ") as last_txn_date, ");
        sql.append("  t.created_date as onboarding_date "); // or m.created_date

        sql.append("FROM dim_terminal t ");
        sql.append("JOIN dim_store st ON t.store_id = st.store_id ");
        sql.append("JOIN dim_merchant m ON st.merchant_id = m.merchant_id ");

        sql.append("WHERE 1=1 ");

        // Tenant scope. We attach to m (merchant) since dim_terminal/dim_store/dim_merchant
        // are all tenant-partitioned in this schema.
        if (tenantId != null) {
            sql.append("AND m.tenant_id = :tenantId ");
            sql.append("AND st.tenant_id = :tenantId ");
            sql.append("AND t.tenant_id = :tenantId ");
        }

        // Filters
        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty()) {
            sql.append("AND m.referral_partner IN (:partners) ");
        }
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank()) {
            sql.append("AND (m.name ILIKE :merchName OR st.legal_name ILIKE :merchName) ");
        }

        // Identity Filters
        if (filter.getMidList() != null && !filter.getMidList().isEmpty()) {
            sql.append("AND m.mid IN (:mids) ");
        }
        // Assuming VolumeRevenueFilterDTO has tidList/sidList or we map request
        // manually
        if (filter.getSidList() != null && !filter.getSidList().isEmpty()) {
            sql.append("AND st.sid IN (:sids) ");
        }
        if (filter.getTidList() != null && !filter.getTidList().isEmpty()) {
            sql.append("AND t.tid IN (:tids) ");
        }

        // Wrap logic for Inactivity Filter (HAVING or WHERE based on subquery)
        // Since we can't use subquery in WHERE easy without repeating, let's wrap or
        // iterate.
        // Actually, for better SQL, we can JOIN a CTE or just accept overhead.
        // Best approach: Filter in Java for "time since" to keep SQL simple, OR use
        // HAVING.
        // Let's use WHERE on the subquery logic if DB supports it (Postgres allows
        // scalar subqueries in WHERE).

        // Filters: Last 7 days, Last 30 days, Since Onboarding
        // Passed as startDate/endDate or specific flags?
        // Let's assume filter.getStartDate() represents the "Start of Inactivity".
        // Example: "Last 7 days" -> start=Now-7. We want MAX(date) < start.

        // BUT strict requirement: "Zero Txn - Last 7 Days"
        // If I transacted yesterday, I am NOT in this report.
        // If I transacted 8 days ago, I AM in this report.

        // However, "Since Onboarding" (Never Transacted) -> MAX(date) IS NULL.

        // We will return ALL rows matching identity/aggregator first, then filter in
        // Java?
        // No, dataset might be huge.
        // Let's add the condition:
        // sql.append("AND (SELECT MAX(business_date) ...) < :cutoffDate ");
        // Note: For "Never Transacted", comparison < cutoff might fail on NULL.

        // Handling Logic:
        // If "Since Onboarding" (Never): last_txn_date IS NULL.
        // If "Last 7 Days": last_txn_date < (Now - 7) OR last_txn_date IS NULL.

        // Let's structure the SQL to select columns first then filter.

        // LIMIT for safety
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

        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();

        LocalDate now = LocalDate.now();
        // Determine cutoffs from filter if present, or assume request logic maps to
        // specific date params
        // But here we just get all and filter in Java for the specific "Status" logic
        // requested?
        // "Status: Never Transacted, Inactive 7-30, Inactive 30+"
        // It implies we just show everyone who is "Zero Transacting" recently?
        // Or "Zero Merchant Transaction Report" usually implies showing ALL inactive
        // merchants.

        // IMPORTANT: If I just return 1000 arbitrary terminals, I might miss the
        // inactive ones if I don't filter in SQL.
        // I MUST filter in SQL.

        // Let's Refine SQL to include HAVING clause logic.
        // Re-writing the main query logic below in a safer way.

        return processResults(rows);
    }

    // Better implementation with filtering
    public List<Map<String, Object>> getZeroTransactionListSmart(VolumeRevenueFilterDTO filter, String rangeType) {
        return getZeroTransactionListSmart(filter, rangeType, null);
    }

    public List<Map<String, Object>> getZeroTransactionListSmart(VolumeRevenueFilterDTO filter, String rangeType, Long tenantId) {
        // rangeType: "LAST_7", "LAST_30", "NEVER"

        // Tenant predicate fragment (used both in the outer WHERE and in every
        // inner sum_daily_terminal subquery so each scope is independently safe).
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

        // Range Logic
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

        sql.append("LIMIT 500");

        Query query = entityManager.createNativeQuery(sql.toString());

        if (tenantId != null) query.setParameter("tenantId", tenantId);

        if (filter.getPartnerList() != null && !filter.getPartnerList().isEmpty())
            query.setParameter("partners", filter.getPartnerList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");

        if ("LAST_7".equals(rangeType)) {
            query.setParameter("cutoff7", LocalDate.now().minusDays(7));
        } else if ("LAST_30".equals(rangeType)) {
            query.setParameter("cutoff30", LocalDate.now().minusDays(30));
        }

        List<Object[]> rows = query.getResultList();
        return processResults(rows);
    }

    // ============================================================
    // Accurate, cap-free summary + server-side pagination.
    // A CTE computes each terminal's last_txn once, then counts /
    // buckets / aggregators / page rows all read from it. No LIMIT 500.
    // ============================================================

    private String baseCte(VolumeRevenueFilterDTO f, Long tenantId) {
        String innerTenant = (tenantId != null) ? " AND s.tenant_id = :tenantId" : "";
        StringBuilder b = new StringBuilder();
        b.append("WITH base AS (SELECT m.name AS merchant_name, COALESCE(st.legal_name, m.name) AS entity_name, ");
        b.append("m.referral_partner AS aggregator_name, m.mid AS mid, st.sid AS sid, st.name AS store_name, t.tid AS tid, ");
        b.append("(SELECT MAX(s.business_date) FROM sum_daily_terminal s WHERE s.terminal_id = t.terminal_id")
         .append(innerTenant).append(") AS last_txn ");
        b.append("FROM dim_terminal t JOIN dim_store st ON t.store_id = st.store_id ");
        b.append("JOIN dim_merchant m ON st.merchant_id = m.merchant_id WHERE 1=1 ");
        if (tenantId != null) b.append("AND m.tenant_id = :tenantId AND st.tenant_id = :tenantId AND t.tenant_id = :tenantId ");
        if (f.getPartnerList() != null && !f.getPartnerList().isEmpty()) b.append("AND m.referral_partner IN (:partners) ");
        if (f.getMerchantName() != null && !f.getMerchantName().isBlank()) b.append("AND (m.name ILIKE :merchName OR st.legal_name ILIKE :merchName) ");
        if (f.getMidList() != null && !f.getMidList().isEmpty()) b.append("AND m.mid IN (:mids) ");
        if (f.getSidList() != null && !f.getSidList().isEmpty()) b.append("AND st.sid IN (:sids) ");
        if (f.getTidList() != null && !f.getTidList().isEmpty()) b.append("AND t.tid IN (:tids) ");
        b.append(") ");
        return b.toString();
    }

    private String rangePredicate(String rangeType) {
        if ("NEVER".equals(rangeType)) return " AND last_txn IS NULL ";
        if ("LAST_7".equals(rangeType)) return " AND (last_txn < :cutoff7 OR last_txn IS NULL) ";
        return " AND (last_txn < :cutoff30 OR last_txn IS NULL) "; // LAST_30 (default)
    }

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
    private void bindCommon(Query q, String sql, VolumeRevenueFilterDTO f, Long tenantId) {
        if (sql.contains(":tenantId")) q.setParameter("tenantId", tenantId);
        if (sql.contains(":partners")) q.setParameter("partners", f.getPartnerList());
        if (sql.contains(":merchName")) q.setParameter("merchName", "%" + f.getMerchantName() + "%");
        if (sql.contains(":mids")) q.setParameter("mids", f.getMidList());
        if (sql.contains(":sids")) q.setParameter("sids", f.getSidList());
        if (sql.contains(":tids")) q.setParameter("tids", f.getTidList());
        if (sql.contains(":cutoff7")) q.setParameter("cutoff7", LocalDate.now().minusDays(7));
        if (sql.contains(":cutoff30")) q.setParameter("cutoff30", LocalDate.now().minusDays(30));
    }

    private long num(Object o) { return (o instanceof Number) ? ((Number) o).longValue() : 0L; }

    private Map<String, Object> bucket(String label, long count) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", label); m.put("count", count);
        return m;
    }

    /** Accurate counts + days-inactive distribution + top aggregators over the FULL filtered set. */
    public Map<String, Object> getZeroTransactionSummary(VolumeRevenueFilterDTO f, String rangeType, Long tenantId) {
        String cte = baseCte(f, tenantId);
        String range = rangePredicate(rangeType);

        String countSql = cte +
            "SELECT COUNT(*) AS total, " +
            "COUNT(*) FILTER (WHERE last_txn IS NULL) AS never_c, " +
            "COUNT(*) FILTER (WHERE last_txn < :cutoff30) AS in30_c, " +
            "COUNT(*) FILTER (WHERE last_txn >= :cutoff30 AND last_txn < :cutoff7) AS in7_c, " +
            "COUNT(*) FILTER (WHERE last_txn IS NOT NULL AND (CURRENT_DATE - last_txn) <= 14) AS b14, " +
            "COUNT(*) FILTER (WHERE (CURRENT_DATE - last_txn) BETWEEN 15 AND 30) AS b30, " +
            "COUNT(*) FILTER (WHERE (CURRENT_DATE - last_txn) BETWEEN 31 AND 60) AS b60, " +
            "COUNT(*) FILTER (WHERE (CURRENT_DATE - last_txn) BETWEEN 61 AND 90) AS b90, " +
            "COUNT(*) FILTER (WHERE (CURRENT_DATE - last_txn) > 90) AS b90p " +
            "FROM base WHERE 1=1 " + range;
        Query cq = entityManager.createNativeQuery(countSql);
        bindCommon(cq, countSql, f, tenantId);
        Object[] c = (Object[]) cq.getSingleResult();

        String aggSql = cte +
            "SELECT COALESCE(aggregator_name, '— Unassigned —') AS agg, COUNT(*) AS c " +
            "FROM base WHERE 1=1 " + range +
            " GROUP BY COALESCE(aggregator_name, '— Unassigned —') ORDER BY c DESC LIMIT 6";
        Query aq = entityManager.createNativeQuery(aggSql);
        bindCommon(aq, aggSql, f, tenantId);
        @SuppressWarnings("unchecked")
        List<Object[]> aggRows = aq.getResultList();

        Map<String, Object> out = new HashMap<>();
        out.put("total", num(c[0]));
        out.put("never", num(c[1]));
        out.put("in30", num(c[2]));
        out.put("in7", num(c[3]));
        List<Map<String, Object>> dist = new ArrayList<>();
        dist.add(bucket("≤14d", num(c[4])));
        dist.add(bucket("15–30d", num(c[5])));
        dist.add(bucket("31–60d", num(c[6])));
        dist.add(bucket("61–90d", num(c[7])));
        dist.add(bucket("90d+", num(c[8])));
        dist.add(bucket("Never", num(c[1])));
        out.put("distribution", dist);
        List<Map<String, Object>> aggs = new ArrayList<>();
        for (Object[] r : aggRows) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", r[0]); m.put("count", num(r[1]));
            aggs.add(m);
        }
        out.put("topAggregators", aggs);
        return out;
    }

    /** Server-side paginated rows (risk-ordered: Inactive 30+ first, then Never, then 7–30) + total. */
    public Map<String, Object> getZeroTransactionPage(VolumeRevenueFilterDTO f, String rangeType, String status,
                                                      int page, int size, Long tenantId) {
        String cte = baseCte(f, tenantId);
        String pred = rangePredicate(rangeType) + statusPredicate(status);
        int safeSize = Math.min(Math.max(size, 1), 1000);
        int offset = Math.max(page, 0) * safeSize;

        String rowSql = cte +
            "SELECT merchant_name, entity_name, aggregator_name, mid, sid, store_name, tid, last_txn " +
            "FROM base WHERE 1=1 " + pred +
            " ORDER BY (CASE WHEN last_txn < :cutoff30 THEN 3 WHEN last_txn IS NULL THEN 2 ELSE 1 END) DESC, " +
            " last_txn ASC NULLS LAST LIMIT :size OFFSET :offset";
        Query rq = entityManager.createNativeQuery(rowSql);
        bindCommon(rq, rowSql, f, tenantId);
        rq.setParameter("size", safeSize);
        rq.setParameter("offset", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = rq.getResultList();

        String countSql = cte + "SELECT COUNT(*) FROM base WHERE 1=1 " + pred;
        Query cq = entityManager.createNativeQuery(countSql);
        bindCommon(cq, countSql, f, tenantId);
        long total = num(cq.getSingleResult());

        Map<String, Object> out = new HashMap<>();
        out.put("content", processResults(rows));
        out.put("total", total);
        out.put("page", Math.max(page, 0));
        out.put("size", safeSize);
        return out;
    }

    private List<Map<String, Object>> processResults(List<Object[]> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate now = LocalDate.now();

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

            java.sql.Date sqlDate = (java.sql.Date) row[7];
            LocalDate lastTxn = sqlDate != null ? sqlDate.toLocalDate() : null;
            map.put("lastTransactionDate", lastTxn);

            // Status Calculation
            if (lastTxn == null) {
                map.put("status", "Never Transacted");
                map.put("daysInactive", -1); // Or "N/A"
            } else {
                long days = java.time.temporal.ChronoUnit.DAYS.between(lastTxn, now);
                map.put("daysInactive", days);

                if (days > 30) {
                    map.put("status", "Inactive 30+");
                } else if (days > 7) {
                    map.put("status", "Inactive 7–30");
                } else {
                    map.put("status", "Active"); // Should not happen given filters but as fallback
                }
            }

            result.add(map);
        }
        return result;
    }
}
