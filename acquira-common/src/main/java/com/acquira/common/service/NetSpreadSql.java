package com.acquira.common.service;

/**
 * The ONE definition of "net margin" and "net spread" over
 * {@code sum_daily_merchant}, shared by every executive read path.
 *
 * <pre>
 *   net margin = total_margin              (batch: MSF − interchange − scheme fee − PG/ecom fee)
 *   net spread = net margin + dcc_acquirer + rental_amount
 * </pre>
 *
 * WHY THIS CLASS EXISTS
 * ---------------------
 * Before 2026-09-02 three executive screens (Top Performers, Sales Hierarchy,
 * Sales Pulse) each carried their own inline
 * {@code msf - interchange - scheme_fee} — a 3-leg margin that silently
 * excluded the PG fee, while the Executive Dashboard, Volume & Revenue,
 * Daily Merchant and Net Spread pages all read the 4-leg batch figure. Two
 * pages could therefore disagree on the same merchant's margin by exactly
 * its e-commerce fee. Every consumer now goes through here so the number
 * means one thing everywhere, and adding Net Spread to a page is a one-line
 * change rather than a fresh transcription of the formula.
 *
 * {@code total_margin} is populated by SummaryPopulationService (and the
 * bulk-rebuild mirror) for every row; the COALESCE fallback only matters for
 * rows written before the column existed, and is the old 3-leg figure —
 * the best available for such a row, never an invented one.
 *
 * {@code dcc_merchant} is deliberately absent: it is the merchant's money and
 * is never part of the spread (see {@link AncillarySql}).
 *
 * All helpers take the sum_daily_merchant table alias so the same text drops
 * into any FROM/JOIN shape; they return plain SQL fragments with no
 * parameters.
 */
public final class NetSpreadSql {

    private NetSpreadSql() {}

    /** Net margin for one sum_daily_merchant row. */
    public static String margin(String a) {
        return "COALESCE(" + a + ".total_margin, "
                + "COALESCE(" + a + ".total_msf,0) - COALESCE(" + a + ".total_interchange,0)"
                + " - COALESCE(" + a + ".total_scheme_fee,0))";
    }

    /** Ancillary (acquirer-side) revenue for one row: DCC acquirer share + rental. */
    public static String ancillary(String a) {
        return "(COALESCE(" + a + ".dcc_acquirer,0) + COALESCE(" + a + ".rental_amount,0))";
    }

    /** Net spread for one row: {@link #margin} + {@link #ancillary}. */
    public static String spread(String a) {
        return "(" + margin(a) + " + " + ancillary(a) + ")";
    }

    /**
     * ECOM FX income for one row (V2026_09_07_01) — maintained by AncillarySql
     * like the other ancillary legs, but NOT part of {@link #ancillary}/
     * {@link #spread}: it joins the spread only where the tenant has opted in
     * (tenant_setting {@code netspread.fx_enabled} = true, checked by
     * NetSpreadController), so every other consumer of the shared spread is
     * unchanged until the business decides FX belongs everywhere.
     */
    public static String fx(String a) {
        return "COALESCE(" + a + ".fx_revenue,0)";
    }

    /** Net spread INCLUDING FX income — the flag-on read shape. */
    public static String spreadWithFx(String a) {
        return "(" + margin(a) + " + " + ancillary(a) + " + " + fx(a) + ")";
    }

    /** {@code SUM(spread + fx)} with a zero default. */
    public static String sumSpreadWithFx(String a) {
        return "COALESCE(SUM(" + spreadWithFx(a) + "), 0)";
    }

    /** {@code SUM(margin)} with a zero default — the usual aggregate shape. */
    public static String sumMargin(String a) {
        return "COALESCE(SUM(" + margin(a) + "), 0)";
    }

    /** {@code SUM(ancillary)} with a zero default. */
    public static String sumAncillary(String a) {
        return "COALESCE(SUM(" + ancillary(a) + "), 0)";
    }

    /** {@code SUM(spread)} with a zero default. */
    public static String sumSpread(String a) {
        return "COALESCE(SUM(" + spread(a) + "), 0)";
    }

    /**
     * The ONE reading of the {@code netspread.fx_enabled} tenant flag —
     * OPT-IN, only an explicit 'true' enables FX (a tenant without
     * ref_ecom_fx_rate rows would otherwise render a dead all-zero column).
     * Every executive endpoint that surfaces FX resolves the flag through
     * here and must include the result in its cache key so a toggle takes
     * effect immediately.
     */
    public static boolean fxEnabled(org.springframework.jdbc.core.JdbcTemplate jdbc, Long tenantId) {
        java.util.List<String> v = jdbc.queryForList(
                "SELECT setting_value FROM tenant_setting WHERE tenant_id = ? AND setting_key = 'netspread.fx_enabled'",
                String.class, tenantId);
        return !v.isEmpty() && "true".equalsIgnoreCase(String.valueOf(v.get(0)).trim());
    }
}
