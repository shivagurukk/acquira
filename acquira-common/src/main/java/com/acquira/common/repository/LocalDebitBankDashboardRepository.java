package com.acquira.common.repository;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the Local Debit Bank Dashboard (/business/local-debit-bank-dashboard)
 * — DOMESTIC DEBIT traffic split by ISSUING BANK, where the bank identity is
 * resolved from the card BIN via the tenant-uploaded ref_tenant_bin_bank list.
 *
 * Every query reads ONE table: sum_daily_local_debit_bin, which holds ONLY the
 * domestic-debit slice of fact_transaction at (day x merchant x bin6) grain,
 * built with the same merchant rule, signed settlement volume and card_type
 * normalization as sum_daily_full — so this page's total (matched banks +
 * "Other Banks") reconciles exactly with the Card Type Dashboard's
 * DOMESTIC x DEBIT cell. Basis is always "SETTLEMENT".
 *
 * The bank name is resolved AT QUERY TIME via LEFT JOIN ref_tenant_bin_bank
 * (b.tenant_id = s.tenant_id AND b.bin = s.bin6); an unmatched local-debit BIN
 * folds into the 'Other Banks' bucket rather than being dropped, so injection
 * gaps stay visible and totals stay exact. Re-uploading the BIN list therefore
 * re-labels ALL history instantly — no summary rebuild.
 *
 * The table has no store/channel/scheme dims, so only merchant-level drawer
 * filters apply (partner / rm / team leader / mid / industry / merchant name).
 * destination and card type are the page's fixed scope, never filters.
 */
@Repository
public class LocalDebitBankDashboardRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** Query-time split key — matched bank name, else the visible unmatched bucket. */
    public static final String OTHER_BUCKET = "Other Banks";
    private static final String BANK_EXPR = "COALESCE(b.bank_name, 'Other Banks')";

    /** Max distinct banks a payload returns before folding into "Other Banks". */
    private static final int TOP_N_BANKS = 25;

    private static boolean listNonEmpty(List<?> l) { return l != null && !l.isEmpty(); }

    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    private static long lng(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(o.toString());
    }

    /** Fail closed: a null tenant must never silently widen a query to every tenant. */
    private static void requireTenant(Long tenantId) {
        if (tenantId == null)
            throw new IllegalStateException("Tenant context not resolved — refusing unscoped query");
    }

    private void appendJoins(StringBuilder sql) {
        // merchant_id is NOT NULL on this table (population predicate), so the
        // dim_merchant join could be INNER; LEFT keeps rows visible if a dim
        // row is ever missing.
        sql.append("LEFT JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("LEFT JOIN ref_tenant_bin_bank b ON b.tenant_id = s.tenant_id AND b.bin = s.bin6 ");
    }

    /** Merchant-level drawer filters only — the table carries no other dims. */
    private void appendCommonFilters(StringBuilder sql, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getPartnerList()))    sql.append("AND m.referral_partner IN (:partners) ");
        if (listNonEmpty(filter.getRmList()))         sql.append("AND m.sales_email IN (:rms) ");
        if (listNonEmpty(filter.getTeamLeaderList())) sql.append("AND m.sales_user_id IN (:teamLeaders) ");
        if (listNonEmpty(filter.getMidList()))        sql.append("AND m.mid IN (:mids) ");
        if (listNonEmpty(filter.getIndustryList()))   sql.append("AND m.industry IN (:industries) ");
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            sql.append("AND m.name ILIKE :merchName ");
    }

    private void bindCommonParams(Query query, VolumeRevenueFilterDTO filter) {
        if (listNonEmpty(filter.getPartnerList()))    query.setParameter("partners", filter.getPartnerList());
        if (listNonEmpty(filter.getRmList()))         query.setParameter("rms", filter.getRmList());
        if (listNonEmpty(filter.getTeamLeaderList())) query.setParameter("teamLeaders", filter.getTeamLeaderList());
        if (listNonEmpty(filter.getMidList()))        query.setParameter("mids", filter.getMidList());
        if (listNonEmpty(filter.getIndustryList()))   query.setParameter("industries", filter.getIndustryList());
        if (filter.getMerchantName() != null && !filter.getMerchantName().isBlank())
            query.setParameter("merchName", "%" + filter.getMerchantName() + "%");
    }

    // ─────────────────────────────────────────────────────────────────
    // 0) Data bounds — MIN/MAX business_date in THIS page's backing table,
    //    same rationale as the Destination/Card Type dashboards' own bounds.
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getBounds(Long tenantId) {
        requireTenant(tenantId);
        Query query = entityManager.createNativeQuery(
                "SELECT MIN(s.business_date), MAX(s.business_date) FROM sum_daily_local_debit_bin s WHERE s.tenant_id = :tenantId");
        query.setParameter("tenantId", tenantId);

        Object[] r = (Object[]) query.getSingleResult();
        Map<String, Object> out = new HashMap<>();
        out.put("earliest", r[0] == null ? null : r[0].toString());
        out.put("latest", r[1] == null ? null : r[1].toString());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 1) KPIs — current vs. prior window: overall totals + per-bank blocks
    //    + BIN-injection coverage. Two separate windowed scans (never one
    //    combined prevStart→end scan — the perf lesson from the Card Type /
    //    Destination getKpis fix).
    // ─────────────────────────────────────────────────────────────────
    public Map<String, Object> getKpis(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        LocalDate end = filter.getEndDate() != null ? filter.getEndDate() : LocalDate.now();
        LocalDate start = filter.getStartDate() != null ? filter.getStartDate() : end.minusDays(30);

        long days = ChronoUnit.DAYS.between(start, end);
        if (days == 0) days = 1;

        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days);

        String select = "SELECT " + BANK_EXPR + " as bank, " +
                "SUM(s.total_volume) as vol, SUM(s.total_txns) as txn, SUM(s.total_msf) as msf, " +
                "COUNT(DISTINCT s.merchant_id) as merch, COUNT(DISTINCT s.bin6) as bins " +
                "FROM sum_daily_local_debit_bin s ";

        StringBuilder currSql = new StringBuilder(select);
        appendJoins(currSql);
        currSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        currSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(currSql, filter);
        currSql.append("GROUP BY ").append(BANK_EXPR).append(" ORDER BY vol DESC");

        StringBuilder prevSql = new StringBuilder(select);
        appendJoins(prevSql);
        prevSql.append("WHERE s.business_date BETWEEN :winStart AND :winEnd ");
        prevSql.append("AND s.tenant_id = :tenantId ");
        appendCommonFilters(prevSql, filter);
        prevSql.append("GROUP BY ").append(BANK_EXPR);

        Query currQuery = entityManager.createNativeQuery(currSql.toString());
        currQuery.setParameter("winStart", start);
        currQuery.setParameter("winEnd", end);
        currQuery.setParameter("tenantId", tenantId);
        bindCommonParams(currQuery, filter);

        Query prevQuery = entityManager.createNativeQuery(prevSql.toString());
        prevQuery.setParameter("winStart", prevStart);
        prevQuery.setParameter("winEnd", prevEnd);
        prevQuery.setParameter("tenantId", tenantId);
        bindCommonParams(prevQuery, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = currQuery.getResultList();
        @SuppressWarnings("unchecked")
        List<Object[]> prevRows = prevQuery.getResultList();

        Map<String, Object[]> prevByBank = new HashMap<>();
        boolean priorHasData = false;
        BigDecimal totalVolPrev = BigDecimal.ZERO;
        long totalTxnPrev = 0L;
        for (Object[] p : prevRows) {
            prevByBank.put(String.valueOf(p[0]), p);
            totalVolPrev = totalVolPrev.add(bd(p[1]));
            totalTxnPrev += lng(p[2]);
            priorHasData = priorHasData || bd(p[1]).signum() > 0 || lng(p[2]) > 0;
        }

        BigDecimal totalVol = BigDecimal.ZERO, matchedVol = BigDecimal.ZERO, totalMsf = BigDecimal.ZERO;
        long totalTxn = 0L, matchedTxn = 0L, unmatchedBins = 0L;
        List<Map<String, Object>> banks = new ArrayList<>();
        for (Object[] r : rows) {
            String bank = String.valueOf(r[0]);
            BigDecimal vol = bd(r[1]); long txn = lng(r[2]); BigDecimal msf = bd(r[3]);
            Object[] p = prevByBank.get(bank);
            BigDecimal volPrev = p != null ? bd(p[1]) : BigDecimal.ZERO;
            long txnPrev = p != null ? lng(p[2]) : 0L;

            Map<String, Object> bk = new HashMap<>();
            bk.put("bank", bank);
            bk.put("isOther", OTHER_BUCKET.equals(bank));
            bk.put("volume", vol);
            bk.put("volumeGrowthPct", growth(vol.doubleValue(), volPrev.doubleValue()));
            bk.put("txns", txn);
            bk.put("txnsGrowthPct", growth(txn, txnPrev));
            bk.put("msf", msf);
            bk.put("activeMerchants", lng(r[4]));
            bk.put("binCount", lng(r[5]));
            bk.put("avgTicket", txn > 0 ? vol.doubleValue() / txn : 0.0);
            banks.add(bk);

            totalVol = totalVol.add(vol);
            totalTxn += txn;
            totalMsf = totalMsf.add(msf);
            if (OTHER_BUCKET.equals(bank)) {
                unmatchedBins = lng(r[5]);
            } else {
                matchedVol = matchedVol.add(vol);
                matchedTxn += txn;
            }
        }
        for (Map<String, Object> bk : banks) {
            BigDecimal v = (BigDecimal) bk.get("volume");
            bk.put("sharePct", totalVol.signum() > 0 ? v.doubleValue() / totalVol.doubleValue() * 100.0 : 0.0);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("banks", banks);
        out.put("totalVolume", totalVol);
        out.put("totalTxns", totalTxn);
        out.put("totalMsf", totalMsf);
        out.put("totalVolumeGrowthPct", growth(totalVol.doubleValue(), totalVolPrev.doubleValue()));
        out.put("totalTxnsGrowthPct", growth(totalTxn, totalTxnPrev));
        out.put("avgTicket", totalTxn > 0 ? totalVol.doubleValue() / totalTxn : 0.0);
        // Injection coverage — % of local-debit volume/count carrying a bank name.
        out.put("matchedVolumePct", totalVol.signum() > 0 ? matchedVol.doubleValue() / totalVol.doubleValue() * 100.0 : 0.0);
        out.put("matchedTxnPct", totalTxn > 0 ? (double) matchedTxn / totalTxn * 100.0 : 0.0);
        out.put("unmatchedBinCount", unmatchedBins);
        out.put("priorWindowHasData", priorHasData);
        out.put("priorStart", prevStart.toString());
        out.put("priorEnd", prevEnd.toString());
        out.put("start", start.toString());
        out.put("end", end.toString());
        out.put("basis", "SETTLEMENT");
        out.put("scope", "DOMESTIC_DEBIT");
        return out;
    }

    private double growth(double curr, double prev) {
        if (prev == 0) return curr > 0 ? 100.0 : 0.0;
        return (curr - prev) / prev * 100.0;
    }

    // ─────────────────────────────────────────────────────────────────
    // 2) Monthly trend — one row per month × bank (frontend pivots).
    //    Banks beyond the top N by total volume fold into 'Other Banks'
    //    so the chart stays readable and the stacked totals stay exact.
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTrend(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TO_CHAR(s.business_date, 'YYYY-MM') as month_label, ");
        sql.append(BANK_EXPR).append(" as bank, ");
        sql.append("SUM(s.total_volume) as volume, SUM(s.total_txns) as txns ");
        sql.append("FROM sum_daily_local_debit_bin s ");
        appendJoins(sql);
        sql.append("WHERE 1=1 AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY TO_CHAR(s.business_date, 'YYYY-MM'), ").append(BANK_EXPR).append(" ");
        sql.append("ORDER BY month_label ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return foldBeyondTopN(rows, 0, 1, 2, 3);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3) Daily trend — one row per day × bank, for short windows.
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getDailyTrend(VolumeRevenueFilterDTO filter, Long tenantId) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT CAST(s.business_date AS VARCHAR) as day_label, ");
        sql.append(BANK_EXPR).append(" as bank, ");
        sql.append("SUM(s.total_volume) as volume, SUM(s.total_txns) as txns ");
        sql.append("FROM sum_daily_local_debit_bin s ");
        appendJoins(sql);
        sql.append("WHERE 1=1 AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY s.business_date, ").append(BANK_EXPR).append(" ");
        sql.append("ORDER BY s.business_date ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        bindCommonParams(query, filter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return foldBeyondTopN(rows, 0, 1, 2, 3);
    }

    /**
     * Shared fold: rows are (periodLabel, bank, volume, txns). Banks outside
     * the top {@link #TOP_N_BANKS} by total volume merge into 'Other Banks'
     * per period; the real unmatched bucket merges with them (same label), so
     * the payload never exceeds TOP_N+1 series and totals stay exact.
     */
    private List<Map<String, Object>> foldBeyondTopN(List<Object[]> rows, int li, int bi, int vi, int ti) {
        Map<String, BigDecimal> bankTotals = new LinkedHashMap<>();
        for (Object[] r : rows)
            bankTotals.merge(String.valueOf(r[bi]), bd(r[vi]), BigDecimal::add);
        java.util.Set<String> topBanks = bankTotals.entrySet().stream()
                .filter(e -> !OTHER_BUCKET.equals(e.getKey()))
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(TOP_N_BANKS)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        Map<String, Map<String, Object>> folded = new LinkedHashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String bank = String.valueOf(r[bi]);
            String label = String.valueOf(r[li]);
            if (topBanks.contains(bank)) {
                Map<String, Object> m = new HashMap<>();
                m.put("period", label);
                m.put("bank", bank);
                m.put("volume", bd(r[vi]));
                m.put("txns", lng(r[ti]));
                out.add(m);
            } else {
                Map<String, Object> m = folded.computeIfAbsent(label, k -> {
                    Map<String, Object> x = new HashMap<>();
                    x.put("period", k);
                    x.put("bank", OTHER_BUCKET);
                    x.put("volume", BigDecimal.ZERO);
                    x.put("txns", 0L);
                    return x;
                });
                m.put("volume", ((BigDecimal) m.get("volume")).add(bd(r[vi])));
                m.put("txns", (Long) m.get("txns") + lng(r[ti]));
            }
        }
        out.addAll(folded.values());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 4) Top merchants — overall, or for ONE bank (bank == null → all;
    //    bank == 'Other Banks' → the unmatched bucket).
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getTopMerchants(VolumeRevenueFilterDTO filter, Long tenantId,
                                                     String bank, int limit) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.mid as mid, m.name as merchant_name, ");
        sql.append("SUM(s.total_volume) as volume, SUM(s.total_txns) as txns, SUM(s.total_msf) as msf, ");
        sql.append("COUNT(DISTINCT s.bin6) as bins ");
        sql.append("FROM sum_daily_local_debit_bin s ");
        // INNER — a merchant ranking has no row without a dim_merchant match.
        sql.append("JOIN dim_merchant m ON s.merchant_id = m.merchant_id AND m.tenant_id = s.tenant_id ");
        sql.append("LEFT JOIN ref_tenant_bin_bank b ON b.tenant_id = s.tenant_id AND b.bin = s.bin6 ");
        sql.append("WHERE 1=1 AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        if (bank != null && !bank.isBlank()) sql.append("AND ").append(BANK_EXPR).append(" = :bank ");
        appendCommonFilters(sql, filter);
        sql.append("GROUP BY m.mid, m.name ");
        sql.append("ORDER BY SUM(s.total_volume) DESC ");
        sql.append("LIMIT :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        if (bank != null && !bank.isBlank()) query.setParameter("bank", bank);
        bindCommonParams(query, filter);
        query.setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("mid", r[0]);
            m.put("merchantName", r[1]);
            m.put("volume", bd(r[2]));
            m.put("txns", lng(r[3]));
            m.put("msf", bd(r[4]));
            m.put("binCount", lng(r[5]));
            long txns = lng(r[3]);
            m.put("avgTicket", txns > 0 ? bd(r[2]).doubleValue() / txns : 0.0);
            out.add(m);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────
    // 5) Unmatched BINs — the injection worklist: top local-debit BINs with
    //    no ref_tenant_bin_bank row, by volume. Copy these into the next
    //    upload file to raise coverage. '??????' rows (malformed PANs) are
    //    included so data-quality issues stay visible.
    // ─────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> getUnmatchedBins(VolumeRevenueFilterDTO filter, Long tenantId, int limit) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT s.bin6, SUM(s.total_volume) as volume, SUM(s.total_txns) as txns, ");
        sql.append("COUNT(DISTINCT s.merchant_id) as merch ");
        sql.append("FROM sum_daily_local_debit_bin s ");
        sql.append("LEFT JOIN ref_tenant_bin_bank b ON b.tenant_id = s.tenant_id AND b.bin = s.bin6 ");
        sql.append("WHERE b.bin IS NULL AND s.tenant_id = :tenantId ");
        if (filter.getStartDate() != null) sql.append("AND s.business_date >= :startDate ");
        if (filter.getEndDate() != null) sql.append("AND s.business_date <= :endDate ");
        sql.append("GROUP BY s.bin6 ");
        sql.append("ORDER BY SUM(s.total_volume) DESC ");
        sql.append("LIMIT :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("tenantId", tenantId);
        if (filter.getStartDate() != null) query.setParameter("startDate", filter.getStartDate());
        if (filter.getEndDate() != null) query.setParameter("endDate", filter.getEndDate());
        query.setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("bin", r[0]);
            m.put("volume", bd(r[1]));
            m.put("txns", lng(r[2]));
            m.put("merchants", lng(r[3]));
            out.add(m);
        }
        return out;
    }
}
