package com.acquira.common.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Maintains {@code sum_daily_finance_rollup}: ONE row per tenant per business
 * day carrying exactly what the Finance Summary screen (GET /api/finance/summary)
 * shows at its MONTH and DAY grains.
 *
 * WHY THIS TABLE EXISTS
 * ---------------------
 * The screen opens on a YEAR preset. Before this rollup, that one request
 * aggregated every sum_monthly_insight row of the complete months, every
 * sum_daily_insight row of the partial month, AND every sum_daily_full row of
 * the whole year for the fee overlay — millions of rows for a large tenant,
 * several seconds even with covering indexes, and paid again after every
 * ingest evicts the report cache. With this rollup the same request reads at
 * most 365 rows.
 *
 * PARITY CONTRACT (the only reason this is safe)
 * ----------------------------------------------
 * The pivot measures here are SUMs over sum_daily_insight using the SAME
 * bucket predicates as {@code VolumeRevenueRepository.getPerformanceDashboardDataDaily},
 * and the fee columns are SUMs over sum_daily_full using the SAME predicates as
 * {@code getFinanceFeeOverlay}. Both sources are additive, so a month/day
 * summed from this table equals the figure the old query produced — the
 * screen's numbers do not change, only how quickly they arrive.
 *
 * Every ingest path that rewrites sum_daily_insight / sum_daily_full for a set
 * of days must call {@link #rebuildDates} or {@link #rebuildRange} for those
 * days AFTER both writes land (upload job, backfill, summary rebuild). History
 * is seeded once by migration V2026_08_22_02 with this same statement.
 *
 * Plain static SQL on purpose: the three callers are a Spring Batch tasklet
 * and two services that already hold a JdbcTemplate — no bean wiring needed,
 * and the SQL lives in exactly one place.
 */
public final class FinanceRollupSql {

    public static final String TABLE = "sum_daily_finance_rollup";

    /** Identical to the pivot's exhaustive partition — see VolumeRevenueRepository. */
    private static final String DOM_DEBIT =
            "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID')";
    private static final String DOM_CREDIT =
            "UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID')";
    private static final String INTL =
            "UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC'";

    public static final String[] PIVOT_COLS = {
            "dom_debit_cnt", "dom_debit_vol", "dom_debit_msf", "dom_debit_optin",
            "dom_credit_cnt", "dom_credit_vol", "dom_credit_msf", "dom_credit_optin",
            "int_cnt", "int_vol", "int_msf", "int_optin",
            "total_vol", "total_msf"
    };

    /** total_pg = PG / e-commerce gateway fee (sum_daily_full.total_ecom_fee); whole-row only, it has no card-type split. */
    public static final String[] FEE_COLS = {
            "dom_debit_ic", "dom_debit_sf", "dom_credit_ic", "dom_credit_sf",
            "int_ic", "int_sf", "total_ic", "total_sf", "fee_basis_msf", "total_pg"
    };

    private static String bucket(String pred) {
        return " SUM(CASE WHEN " + pred + " THEN s.total_txns ELSE 0 END), "
             + " SUM(CASE WHEN " + pred + " THEN s.total_volume ELSE 0 END), "
             + " SUM(CASE WHEN " + pred + " THEN s.total_msf ELSE 0 END), "
             + " SUM(CASE WHEN " + pred + " AND s.is_opt_in = true THEN s.total_volume ELSE 0 END), ";
    }

    private static String feeBucket(String pred) {
        return " SUM(CASE WHEN " + pred + " THEN COALESCE(s.total_interchange,0) ELSE 0 END), "
             + " SUM(CASE WHEN " + pred + " THEN COALESCE(s.total_scheme_fee,0) ELSE 0 END), ";
    }

    /**
     * Tenant-day aggregate of sum_daily_insight (pivot measures). Column order
     * = PIVOT_COLS. Parameters: tenantId, start, end.
     */
    static final String PIVOT_SUBQUERY =
            "SELECT s.tenant_id, s.business_date, "
            + bucket(DOM_DEBIT) + bucket(DOM_CREDIT) + bucket(INTL)
            + " SUM(s.total_volume), SUM(s.total_msf) "
            + "FROM sum_daily_insight s "
            + "WHERE s.tenant_id = ? AND s.business_date BETWEEN ? AND ? "
            + "GROUP BY s.tenant_id, s.business_date";

    /**
     * Tenant-day aggregate of sum_daily_full (fee stack). Column order
     * = FEE_COLS. Parameters: tenantId, start, end.
     */
    static final String FEE_SUBQUERY =
            "SELECT s.tenant_id, s.business_date, "
            + feeBucket(DOM_DEBIT) + feeBucket(DOM_CREDIT) + feeBucket(INTL)
            + " SUM(COALESCE(s.total_interchange,0)), SUM(COALESCE(s.total_scheme_fee,0)), "
            + " SUM(COALESCE(s.total_msf,0)), SUM(COALESCE(s.total_ecom_fee,0)) "
            + "FROM sum_daily_full s "
            + "WHERE s.tenant_id = ? AND s.business_date BETWEEN ? AND ? "
            + "GROUP BY s.tenant_id, s.business_date";

    /** Full statement. Parameters: tenantId, start, end, tenantId, start, end. */
    public static final String REBUILD_INSERT = buildInsert();

    private static String buildInsert() {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(TABLE).append(" (tenant_id, business_date, ");
        for (String c : PIVOT_COLS) sb.append(c).append(", ");
        for (String c : FEE_COLS) sb.append(c).append(", ");
        sb.append("pivot_built, fees_built, built_at) ");

        // Subqueries are positional (p.c1..cN / f.c1..cN) so the column lists
        // above are the single source of truth for names.
        sb.append("SELECT COALESCE(p.tenant_id, f.tenant_id), COALESCE(p.business_date, f.business_date), ");
        for (int i = 0; i < PIVOT_COLS.length; i++) sb.append("COALESCE(p.c").append(i + 1).append(", 0), ");
        for (int i = 0; i < FEE_COLS.length; i++) sb.append("COALESCE(f.c").append(i + 1).append(", 0), ");
        // pivot_built: the day exists in sum_daily_insight. The read side lists
        // a period only if some day in it is pivot_built — exactly the row set
        // the old pivot produced (fee-only days never made a row of their own).
        sb.append("(p.business_date IS NOT NULL), (f.business_date IS NOT NULL), NOW() ");

        sb.append("FROM (").append(aliased(PIVOT_SUBQUERY, PIVOT_COLS.length)).append(") p ");
        sb.append("FULL OUTER JOIN (").append(aliased(FEE_SUBQUERY, FEE_COLS.length)).append(") f ");
        sb.append("ON p.tenant_id = f.tenant_id AND p.business_date = f.business_date ");

        sb.append("ON CONFLICT (tenant_id, business_date) DO UPDATE SET ");
        for (String c : PIVOT_COLS) sb.append(c).append(" = EXCLUDED.").append(c).append(", ");
        for (String c : FEE_COLS) sb.append(c).append(" = EXCLUDED.").append(c).append(", ");
        sb.append("pivot_built = EXCLUDED.pivot_built, fees_built = EXCLUDED.fees_built, built_at = EXCLUDED.built_at");
        return sb.toString();
    }

    /** Wraps a subquery so its measure columns are addressable as c1..cN. */
    private static String aliased(String subquery, int n) {
        StringBuilder sb = new StringBuilder("SELECT q.tenant_id, q.business_date");
        for (int i = 1; i <= n; i++) sb.append(", q.col").append(i).append(" AS c").append(i);
        sb.append(" FROM (").append(subquery).append(") AS q(tenant_id, business_date");
        for (int i = 1; i <= n; i++) sb.append(", col").append(i);
        sb.append(")");
        return sb.toString();
    }

    private FinanceRollupSql() {}

    /**
     * Clean-slate rebuild of [start, end] for one tenant. The DELETE matters: a
     * day whose source rows were all removed must disappear from the rollup
     * too, which an upsert alone would never do.
     *
     * @return rollup rows written
     */
    public static int rebuildRange(JdbcTemplate jdbc, Long tenantId, LocalDate start, LocalDate end) {
        if (tenantId == null || start == null || end == null || start.isAfter(end)) return 0;
        jdbc.update("DELETE FROM " + TABLE + " WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
                tenantId, start, end);
        int written = jdbc.update(REBUILD_INSERT, tenantId, start, end, tenantId, start, end);
        // The DELETE above also wiped the ancillary columns (dcc_acquirer /
        // dcc_merchant / rental_amount) — re-derive them from their facts so a
        // rollup rebuild can never silently lose DCC or rental revenue.
        AncillarySql.applyRollupRange(jdbc, tenantId, start, end);
        return written;
    }

    /**
     * Rebuilds an arbitrary set of days, collapsed into contiguous runs so a
     * one-month upload is one statement and a sparse one does not re-aggregate
     * the months in between.
     */
    public static int rebuildDates(JdbcTemplate jdbc, Long tenantId, Collection<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) return 0;
        List<LocalDate> sorted = new ArrayList<>(new TreeSet<>(dates));
        int written = 0;
        LocalDate runStart = sorted.get(0), runEnd = runStart;
        for (int i = 1; i < sorted.size(); i++) {
            LocalDate d = sorted.get(i);
            if (d.equals(runEnd.plusDays(1))) {
                runEnd = d;
            } else {
                written += rebuildRange(jdbc, tenantId, runStart, runEnd);
                runStart = d;
                runEnd = d;
            }
        }
        written += rebuildRange(jdbc, tenantId, runStart, runEnd);
        return written;
    }
}
