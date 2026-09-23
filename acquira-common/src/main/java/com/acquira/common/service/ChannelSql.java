package com.acquira.common.service;

/**
 * Channel-scoped (POS / ECOM / All) merchant-day read routing for the
 * executive screens.
 *
 * WHY THIS CLASS EXISTS
 * ---------------------
 * Every executive page reads sum_daily_merchant (merchant-day grain, fee
 * stack + ancillary columns) - which has NO channel dimension. The channel
 * selector (user, 2026-09-08) therefore routes filtered reads to
 * sum_daily_full, the channel-grain settlement pre-aggregate WITH real fees,
 * collapsed back to merchant-day shape here so {@link NetSpreadSql} fragments
 * and existing per-screen SQL drop in unchanged:
 *
 *   channel = ALL (or null)  ->  "sum_daily_merchant"  (byte-identical reads)
 *   channel = POS | ECOM     ->  derived relation over sum_daily_full rows
 *                                WHERE channel_class = &lt;channel&gt;, exposing the
 *                                SAME column names sum_daily_merchant has.
 *
 * ANCILLARY ATTRIBUTION (business rule, 2026-09-08)
 * -------------------------------------------------
 * The ancillary revenue legs have no per-transaction channel, so they are
 * attributed wholesale: DCC (acquirer + merchant share) and terminal RENTAL
 * are physical-terminal revenue -> POS; ECOM FX income is e-commerce by
 * definition -> ECOM. The attribution branch is a UNION ALL leg from
 * sum_daily_merchant (where AncillarySql maintains those columns), so
 * merchant-days with ancillary revenue but no transactions on the selected
 * channel still appear - e.g. rental billed on the 1st shows under POS even
 * if that merchant only transacted ECOM that day.
 *
 * COLUMN CONTRACT of the derived relation (must stay a superset of what every
 * executive repository references off the "s" alias):
 *   tenant_id, business_date, merchant_id, total_txns, total_volume,
 *   total_base_volume, total_msf, total_interchange, total_scheme_fee,
 *   total_ecom_fee, total_margin, dcc_acquirer, dcc_merchant, rental_amount,
 *   fx_revenue.
 * sum_daily_full carries settlement volume only, so total_volume and
 * total_base_volume are BOTH the settlement figure in channel scope (the
 * executive pages read total_base_volume; total_volume is aliased for
 * stragglers - do not use it for cardholder-currency maths in channel scope).
 *
 * SAFETY: {@link #merchantDay} only ever interpolates the two fixed literals
 * 'POS'/'ECOM' (anything else routes to sum_daily_merchant), so callers can
 * pass request input through {@link #normalize} without SQL-injection risk.
 * Predicates on tenant_id / business_date / merchant_id push down through the
 * UNION ALL + GROUP BY into both branches (verified via EXPLAIN, 2026-09-08),
 * so partition pruning is preserved.
 */
public final class ChannelSql {

    private ChannelSql() {}

    public static final String ALL = "ALL";
    public static final String POS = "POS";
    public static final String ECOM = "ECOM";

    /** True only for the two filterable channel classes. */
    public static boolean isChannel(String c) {
        return POS.equals(c) || ECOM.equals(c);
    }

    /** Request input -> POS / ECOM / ALL (anything unrecognised = ALL). */
    public static String normalize(String c) {
        if (c == null) return ALL;
        String u = c.trim().toUpperCase();
        return isChannel(u) ? u : ALL;
    }

    /**
     * The FROM-able merchant-day relation for a channel scope. Callers write
     * {@code "FROM " + ChannelSql.merchantDay(channel) + " s ..."} exactly
     * where they previously wrote {@code "FROM sum_daily_merchant s ..."}.
     */
    public static String merchantDay(String channel) {
        if (!isChannel(channel)) return "sum_daily_merchant";
        boolean pos = POS.equals(channel);
        // Ancillary attribution branch: POS carries DCC + rental, ECOM carries FX.
        String ancillarySelect = pos
                ? "COALESCE(a.dcc_acquirer,0), COALESCE(a.dcc_merchant,0), COALESCE(a.rental_amount,0), 0"
                : "0, 0, 0, COALESCE(a.fx_revenue,0)";
        String ancillaryFilter = pos
                ? "(COALESCE(a.dcc_acquirer,0) <> 0 OR COALESCE(a.dcc_merchant,0) <> 0 OR COALESCE(a.rental_amount,0) <> 0)"
                : "COALESCE(a.fx_revenue,0) <> 0";
        return "(SELECT tenant_id, business_date, merchant_id, "
                + "SUM(cnt) AS total_txns, SUM(vol) AS total_volume, SUM(vol) AS total_base_volume, "
                + "SUM(msf) AS total_msf, SUM(icf) AS total_interchange, SUM(sf) AS total_scheme_fee, "
                + "SUM(pg) AS total_ecom_fee, SUM(marg) AS total_margin, "
                + "SUM(dcc_a) AS dcc_acquirer, SUM(dcc_m) AS dcc_merchant, "
                + "SUM(rent) AS rental_amount, SUM(fxr) AS fx_revenue "
                + "FROM ("
                + "SELECT f.tenant_id, f.business_date, f.merchant_id, "
                + "COALESCE(f.total_txns,0) cnt, COALESCE(f.total_volume,0) vol, COALESCE(f.total_msf,0) msf, "
                + "COALESCE(f.total_interchange,0) icf, COALESCE(f.total_scheme_fee,0) sf, "
                + "COALESCE(f.total_ecom_fee,0) pg, COALESCE(f.total_net_revenue,0) marg, "
                + "0 dcc_a, 0 dcc_m, 0 rent, 0 fxr "
                + "FROM sum_daily_full f WHERE f.channel_class = '" + channel + "' AND f.merchant_id IS NOT NULL "
                + "UNION ALL "
                + "SELECT a.tenant_id, a.business_date, a.merchant_id, 0, 0, 0, 0, 0, 0, 0, "
                + ancillarySelect + " "
                + "FROM sum_daily_merchant a WHERE " + ancillaryFilter
                + ") u GROUP BY tenant_id, business_date, merchant_id)";
    }
}
