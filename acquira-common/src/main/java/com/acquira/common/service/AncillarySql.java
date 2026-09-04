package com.acquira.common.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Maintains the ANCILLARY revenue columns (dcc_acquirer / dcc_merchant /
 * rental_amount) on {@code sum_daily_merchant} and
 * {@code sum_daily_finance_rollup}, always recomputed from the two facts
 * ({@code fact_dcc_revenue}, {@code fact_rental}) — never carried forward.
 *
 * WHY THIS CLASS EXISTS
 * ---------------------
 * Net Spread = total_margin + dcc_acquirer + rental_amount. Every executive
 * page reads the summary layer, so the ancillary components live there — but
 * the summary layer is clean-slate rebuilt (DELETE + re-aggregate from
 * fact_transaction) by SummaryPopulationService on every ingest/rebuild,
 * which would silently wipe any column not re-derived. This class is the
 * single re-derivation point, called from:
 *   1. SummaryPopulationService.populateSummary — after the transaction-side
 *      rebuild, for the same date scope (covers upload, backfill, bulk
 *      rebuild and reprice, which all delegate there since 2026-08-28);
 *   2. FinanceRollupSql.rebuildRange — its DELETE+rebuild wipes the rollup's
 *      ancillary columns even when called outside populateSummary;
 *   3. the DCC and rental apply tasklets — for the dates they just loaded,
 *      including creating summary rows for days that have ancillary revenue
 *      but no transactions (rental charge on the 1st, no sales that day).
 *
 * Ancillary-only rows are written with every transaction measure explicitly 0
 * (never NULL) and are deleted again once their ancillary drops back to 0, so
 * they cannot linger as orphans after a DCC replace-by-date removes a day.
 *
 * Plain static SQL on purpose, same convention as {@link FinanceRollupSql}:
 * the callers already hold a JdbcTemplate, and the SQL lives in exactly one
 * place (summary-rebuild-drift rule).
 */
public final class AncillarySql {

    private AncillarySql() {}

    // ── sum_daily_merchant ──────────────────────────────────────────────────

    private static final String MERCH_ZERO =
            "UPDATE sum_daily_merchant SET dcc_acquirer = 0, dcc_merchant = 0, rental_amount = 0 "
            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
            + "AND (dcc_acquirer <> 0 OR dcc_merchant <> 0 OR rental_amount <> 0)";

    // fact_dcc_revenue rows always carry merchant_id (resolved via dim_store at
    // apply; unmatched SIDs never leave staging), but the guard keeps a manual
    // fact edit from raising a unique-violation on the NULL-merchant row.
    private static final String MERCH_DCC_UPSERT =
            "INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id, "
            + "total_txns, total_volume, total_base_volume, total_msf, total_interchange, "
            + "total_scheme_fee, total_margin, dcc_acquirer, dcc_merchant) "
            + "SELECT tenant_id, payment_date, merchant_id, 0, 0, 0, 0, 0, 0, 0, "
            + "SUM(acquirer_share), SUM(merchant_share) "
            + "FROM fact_dcc_revenue "
            + "WHERE tenant_id = ? AND payment_date BETWEEN ? AND ? AND merchant_id IS NOT NULL "
            + "GROUP BY tenant_id, payment_date, merchant_id "
            + "ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET "
            + "dcc_acquirer = EXCLUDED.dcc_acquirer, dcc_merchant = EXCLUDED.dcc_merchant";

    private static final String MERCH_RENTAL_UPSERT =
            "INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id, "
            + "total_txns, total_volume, total_base_volume, total_msf, total_interchange, "
            + "total_scheme_fee, total_margin, rental_amount) "
            + "SELECT tenant_id, payment_date, merchant_id, 0, 0, 0, 0, 0, 0, 0, "
            + "SUM(rental_amount) "
            + "FROM fact_rental "
            + "WHERE tenant_id = ? AND payment_date BETWEEN ? AND ? AND merchant_id IS NOT NULL "
            + "GROUP BY tenant_id, payment_date, merchant_id "
            + "ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET "
            + "rental_amount = EXCLUDED.rental_amount";

    // Ancillary-only rows (total_txns = 0, written above) whose ancillary is
    // now 0 again — e.g. after a DCC replace removed their day — must go, or
    // they read as fake-active merchant-days forever.
    private static final String MERCH_CLEANUP =
            "DELETE FROM sum_daily_merchant "
            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
            + "AND COALESCE(total_txns, 0) = 0 AND COALESCE(total_volume, 0) = 0 "
            + "AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0";

    // ── sum_daily_finance_rollup (tenant-day) ───────────────────────────────

    private static final String ROLLUP_ZERO =
            "UPDATE sum_daily_finance_rollup SET dcc_acquirer = 0, dcc_merchant = 0, rental_amount = 0 "
            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
            + "AND (dcc_acquirer <> 0 OR dcc_merchant <> 0 OR rental_amount <> 0)";

    private static final String ROLLUP_DCC_UPSERT =
            "INSERT INTO sum_daily_finance_rollup (tenant_id, business_date, dcc_acquirer, dcc_merchant) "
            + "SELECT tenant_id, payment_date, SUM(acquirer_share), SUM(merchant_share) "
            + "FROM fact_dcc_revenue "
            + "WHERE tenant_id = ? AND payment_date BETWEEN ? AND ? "
            + "GROUP BY tenant_id, payment_date "
            + "ON CONFLICT (tenant_id, business_date) DO UPDATE SET "
            + "dcc_acquirer = EXCLUDED.dcc_acquirer, dcc_merchant = EXCLUDED.dcc_merchant";

    private static final String ROLLUP_RENTAL_UPSERT =
            "INSERT INTO sum_daily_finance_rollup (tenant_id, business_date, rental_amount) "
            + "SELECT tenant_id, payment_date, SUM(rental_amount) "
            + "FROM fact_rental "
            + "WHERE tenant_id = ? AND payment_date BETWEEN ? AND ? "
            + "GROUP BY tenant_id, payment_date "
            + "ON CONFLICT (tenant_id, business_date) DO UPDATE SET "
            + "rental_amount = EXCLUDED.rental_amount";

    // Ancillary-only rollup rows are recognisable by both built flags being
    // false (a real pivot/fee day always sets one of them).
    private static final String ROLLUP_CLEANUP =
            "DELETE FROM sum_daily_finance_rollup "
            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
            + "AND pivot_built = FALSE AND fees_built = FALSE "
            + "AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0";

    /** Re-derives the ancillary columns on sum_daily_merchant for [start, end]. */
    public static void applyMerchantRange(JdbcTemplate jdbc, Long tenantId, LocalDate start, LocalDate end) {
        if (tenantId == null || start == null || end == null || start.isAfter(end)) return;
        jdbc.update(MERCH_ZERO, tenantId, start, end);
        jdbc.update(MERCH_DCC_UPSERT, tenantId, start, end);
        jdbc.update(MERCH_RENTAL_UPSERT, tenantId, start, end);
        jdbc.update(MERCH_CLEANUP, tenantId, start, end);
    }

    /** Re-derives the ancillary columns on sum_daily_finance_rollup for [start, end]. */
    public static void applyRollupRange(JdbcTemplate jdbc, Long tenantId, LocalDate start, LocalDate end) {
        if (tenantId == null || start == null || end == null || start.isAfter(end)) return;
        jdbc.update(ROLLUP_ZERO, tenantId, start, end);
        jdbc.update(ROLLUP_DCC_UPSERT, tenantId, start, end);
        jdbc.update(ROLLUP_RENTAL_UPSERT, tenantId, start, end);
        jdbc.update(ROLLUP_CLEANUP, tenantId, start, end);
    }

    /** Both tables, one range — the DCC / rental apply tasklets call this. */
    public static void applyRange(JdbcTemplate jdbc, Long tenantId, LocalDate start, LocalDate end) {
        applyMerchantRange(jdbc, tenantId, start, end);
        applyRollupRange(jdbc, tenantId, start, end);
    }

    /**
     * Both tables over an arbitrary set of days, collapsed into contiguous
     * runs — same convention as {@link FinanceRollupSql#rebuildDates}.
     */
    public static void applyDates(JdbcTemplate jdbc, Long tenantId, Collection<LocalDate> dates) {
        forEachRun(dates, (s, e) -> applyRange(jdbc, tenantId, s, e));
    }

    /** sum_daily_merchant only, over a set of days (SummaryPopulationService). */
    public static void applyMerchantDates(JdbcTemplate jdbc, Long tenantId, Collection<LocalDate> dates) {
        forEachRun(dates, (s, e) -> applyMerchantRange(jdbc, tenantId, s, e));
    }

    private interface RangeFn { void accept(LocalDate start, LocalDate end); }

    private static void forEachRun(Collection<LocalDate> dates, RangeFn fn) {
        if (dates == null || dates.isEmpty()) return;
        List<LocalDate> sorted = new ArrayList<>(new TreeSet<>(dates));
        LocalDate runStart = sorted.get(0), runEnd = runStart;
        for (int i = 1; i < sorted.size(); i++) {
            LocalDate d = sorted.get(i);
            if (d.equals(runEnd.plusDays(1))) {
                runEnd = d;
            } else {
                fn.accept(runStart, runEnd);
                runStart = d;
                runEnd = d;
            }
        }
        fn.accept(runStart, runEnd);
    }
}
