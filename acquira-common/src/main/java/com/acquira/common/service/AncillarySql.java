package com.acquira.common.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Maintains the ANCILLARY revenue columns (dcc_acquirer / dcc_merchant /
 * rental_amount / fx_revenue) on {@code sum_daily_merchant} and
 * {@code sum_daily_finance_rollup}, always recomputed from their sources
 * ({@code fact_dcc_revenue}, {@code fact_rental}, and for FX income
 * {@code fact_transaction} × {@code ref_ecom_fx_rate}) — never carried forward.
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
            "UPDATE sum_daily_merchant SET dcc_acquirer = 0, dcc_merchant = 0, rental_amount = 0, fx_revenue = 0 "
            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
            + "AND (dcc_acquirer <> 0 OR dcc_merchant <> 0 OR rental_amount <> 0 OR fx_revenue <> 0)";

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

    // ECOM FX income (V2026_09_07_01, BH calc sheet): per ECOM transaction NOT
    // carried by Benefit PG, in a currency with a ref_ecom_fx_rate row,
    //   fx = multiplier * (settlement/cost_rate - settlement/board_rate).
    // A mid-specific rate row beats the tenant default (LATERAL ... LIMIT 1,
    // leading zeros stripped from BOTH mids — feed MIDs are zero-padded).
    // Refund rows carry a NEGATIVE store_base_currency_amount (volume-signing,
    // 2026-07-18), so refunds reverse their FX income with no special casing.
    // Unlisted currencies (incl. BHD) simply never match — inner JOIN LATERAL.
    // Rows always land on merchant-days that already exist (they come from the
    // same fact rows the summary was built from); the INSERT arm is
    // belt-and-braces only.
    private static final String MERCH_FX_UPSERT =
            "INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id, "
            + "total_txns, total_volume, total_base_volume, total_msf, total_interchange, "
            + "total_scheme_fee, total_margin, fx_revenue) "
            + "SELECT f.tenant_id, DATE(f.payment_date), f.merchant_id, 0, 0, 0, 0, 0, 0, 0, "
            + "SUM(fr.multiplier * (COALESCE(f.store_base_currency_amount,0) / fr.cost_rate "
            + "                   - COALESCE(f.store_base_currency_amount,0) / fr.board_rate)) "
            + "FROM fact_transaction f "
            + "JOIN dim_merchant m ON m.merchant_id = f.merchant_id AND m.tenant_id = f.tenant_id "
            + "LEFT JOIN dim_terminal dt ON dt.terminal_id = f.terminal_id AND dt.tenant_id = f.tenant_id "
            + "JOIN LATERAL ("
            + "  SELECT r.board_rate, r.cost_rate, r.multiplier FROM ref_ecom_fx_rate r "
            + "  WHERE r.tenant_id = f.tenant_id "
            + "    AND r.txn_currency = UPPER(TRIM(f.txn_currency)) "
            + "    AND (r.mid IS NULL OR LTRIM(r.mid, '0') = LTRIM(m.mid, '0')) "
            + "  ORDER BY (r.mid IS NOT NULL) DESC LIMIT 1"
            + ") fr ON TRUE "
            + "WHERE f.tenant_id = ? AND f.payment_date >= ? AND f.payment_date < ? "
            + "AND f.merchant_id IS NOT NULL AND f.channel = 'ECOM' "
            + "AND UPPER(TRIM(COALESCE(dt.type, ''))) <> 'BENEFIT PG' "
            + "GROUP BY f.tenant_id, DATE(f.payment_date), f.merchant_id "
            + "ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET "
            + "fx_revenue = EXCLUDED.fx_revenue";

    // Ancillary-only rows (total_txns = 0, written above) whose ancillary is
    // now 0 again — e.g. after a DCC replace removed their day — must go, or
    // they read as fake-active merchant-days forever.
    private static final String MERCH_CLEANUP =
            "DELETE FROM sum_daily_merchant "
            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
            + "AND COALESCE(total_txns, 0) = 0 AND COALESCE(total_volume, 0) = 0 "
            + "AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0 AND fx_revenue = 0";

    // ── sum_daily_finance_rollup (tenant-day) ───────────────────────────────

    private static final String ROLLUP_ZERO =
            "UPDATE sum_daily_finance_rollup SET dcc_acquirer = 0, dcc_merchant = 0, rental_amount = 0, fx_revenue = 0 "
            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
            + "AND (dcc_acquirer <> 0 OR dcc_merchant <> 0 OR rental_amount <> 0 OR fx_revenue <> 0)";

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

    // Tenant-day FX rollup — same rate resolution as MERCH_FX_UPSERT.
    private static final String ROLLUP_FX_UPSERT =
            "INSERT INTO sum_daily_finance_rollup (tenant_id, business_date, fx_revenue) "
            + "SELECT f.tenant_id, DATE(f.payment_date), "
            + "SUM(fr.multiplier * (COALESCE(f.store_base_currency_amount,0) / fr.cost_rate "
            + "                   - COALESCE(f.store_base_currency_amount,0) / fr.board_rate)) "
            + "FROM fact_transaction f "
            + "JOIN dim_merchant m ON m.merchant_id = f.merchant_id AND m.tenant_id = f.tenant_id "
            + "LEFT JOIN dim_terminal dt ON dt.terminal_id = f.terminal_id AND dt.tenant_id = f.tenant_id "
            + "JOIN LATERAL ("
            + "  SELECT r.board_rate, r.cost_rate, r.multiplier FROM ref_ecom_fx_rate r "
            + "  WHERE r.tenant_id = f.tenant_id "
            + "    AND r.txn_currency = UPPER(TRIM(f.txn_currency)) "
            + "    AND (r.mid IS NULL OR LTRIM(r.mid, '0') = LTRIM(m.mid, '0')) "
            + "  ORDER BY (r.mid IS NOT NULL) DESC LIMIT 1"
            + ") fr ON TRUE "
            + "WHERE f.tenant_id = ? AND f.payment_date >= ? AND f.payment_date < ? "
            + "AND f.merchant_id IS NOT NULL AND f.channel = 'ECOM' "
            + "AND UPPER(TRIM(COALESCE(dt.type, ''))) <> 'BENEFIT PG' "
            + "GROUP BY f.tenant_id, DATE(f.payment_date) "
            + "ON CONFLICT (tenant_id, business_date) DO UPDATE SET "
            + "fx_revenue = EXCLUDED.fx_revenue";

    // Ancillary-only rollup rows are recognisable by both built flags being
    // false (a real pivot/fee day always sets one of them).
    private static final String ROLLUP_CLEANUP =
            "DELETE FROM sum_daily_finance_rollup "
            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ? "
            + "AND pivot_built = FALSE AND fees_built = FALSE "
            + "AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0 AND fx_revenue = 0";

    /**
     * True when the tenant has any FX rate rows. Guards the FX fact scans so
     * tenants without seeded rates (everyone but BH today) never pay a
     * fact_transaction pass per rebuild for a guaranteed-empty result.
     */
    private static boolean hasFxRates(JdbcTemplate jdbc, Long tenantId) {
        Boolean b = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ref_ecom_fx_rate WHERE tenant_id = ?)",
                Boolean.class, tenantId);
        return Boolean.TRUE.equals(b);
    }

    /** Re-derives the ancillary columns on sum_daily_merchant for [start, end]. */
    public static void applyMerchantRange(JdbcTemplate jdbc, Long tenantId, LocalDate start, LocalDate end) {
        if (tenantId == null || start == null || end == null || start.isAfter(end)) return;
        jdbc.update(MERCH_ZERO, tenantId, start, end);
        jdbc.update(MERCH_DCC_UPSERT, tenantId, start, end);
        jdbc.update(MERCH_RENTAL_UPSERT, tenantId, start, end);
        if (hasFxRates(jdbc, tenantId)) {
            // Sargable timestamp bounds on fact.payment_date: [start, end+1day).
            jdbc.update(MERCH_FX_UPSERT, tenantId, start, end.plusDays(1));
        }
        jdbc.update(MERCH_CLEANUP, tenantId, start, end);
    }

    /** Re-derives the ancillary columns on sum_daily_finance_rollup for [start, end]. */
    public static void applyRollupRange(JdbcTemplate jdbc, Long tenantId, LocalDate start, LocalDate end) {
        if (tenantId == null || start == null || end == null || start.isAfter(end)) return;
        jdbc.update(ROLLUP_ZERO, tenantId, start, end);
        jdbc.update(ROLLUP_DCC_UPSERT, tenantId, start, end);
        jdbc.update(ROLLUP_RENTAL_UPSERT, tenantId, start, end);
        if (hasFxRates(jdbc, tenantId)) {
            jdbc.update(ROLLUP_FX_UPSERT, tenantId, start, end.plusDays(1));
        }
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
