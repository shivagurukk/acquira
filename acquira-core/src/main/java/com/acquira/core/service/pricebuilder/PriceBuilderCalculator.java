package com.acquira.core.service.pricebuilder;

import com.acquira.core.service.pricebuilder.PriceBuilderRates.CountryConfig;
import com.acquira.core.service.pricebuilder.PriceBuilderRates.Mcc;
import com.acquira.core.service.pricebuilder.PriceBuilderRates.RateBook;
import com.acquira.core.service.pricebuilder.PriceBuilderRates.SchemeRow;
import com.acquira.core.service.pricebuilder.PriceBuilderRates.SpecialRule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Price Builder engine (/mis/price-builder).
 *
 * Prices a prospective merchant bottom-up. The quote carries an annual volume
 * in local currency, a transaction count, and the split of both across the
 * scheme rows the country quotes on. For each row it resolves interchange and
 * scheme fee, applies the MSF the salesperson is proposing, and reports the
 * margin. It then recommends an MSF that clears the country's target margin
 * over blended cost.
 *
 * <h2>Everything comes back in USD</h2>
 * Volumes are entered in the country's currency; every money figure returned
 * is USD, converted once at {@link CountryConfig#usdRate()}. That is how the
 * tool this was ported from worked, and it is what lets a Bahrain deal and a
 * UAE deal be compared on one page.
 *
 * <h2>Sign convention</h2>
 * Interchange and scheme fee arrive from the rate book as NEGATIVE fractions,
 * so a cost adds straight into margin with a {@code +}. Nothing in here
 * negates a rate a second time. Percentages the caller supplies (MSF, DCC,
 * opt-in, ticket splits) are plain percent numbers — {@code 1.75} is 1.75% —
 * and are divided by 100 exactly once, at the point of use.
 *
 * <h2>Two places this deliberately differs from the source tool</h2>
 * <ol>
 *   <li><b>The debit MSF recommendation.</b> The source filtered its debit
 *       rows by a substring of the scheme id, which on the Bahrain rate set
 *       also caught Benefit International — so the recommended domestic debit
 *       MSF was blended with international cost and came out too high. Here
 *       the split is on locality and card type, which is what was meant.</li>
 *   <li><b>The e-commerce interchange uplift on a ticket split.</b> The source
 *       computed the uplift and then overwrote it when a motor-trade split
 *       applied, so the uplift silently vanished for those MCCs. Here it
 *       applies to every percentage-based rate on Visa and Mastercard for a
 *       non-preferential MCC, splits included.</li>
 * </ol>
 * Both are stated on the response as {@code notes} so a reader comparing the
 * two tools can see why a number moved.
 */
@Service
public class PriceBuilderCalculator {

    private final PriceBuilderRates rates;

    public PriceBuilderCalculator(PriceBuilderRates rates) {
        this.rates = rates;
    }

    // ── Inputs ─────────────────────────────────────────────────────────────

    /** One line of the volume / transaction / MSF mix. */
    public record MixRow(String schemeKey, double volumePct, double txnPct, double msfPct) { }

    /**
     * Everything a quote is priced from.
     *
     * @param annualVolume   local currency
     * @param totalTxns      annual transaction count
     * @param gatewayCostUsd e-commerce per-transaction gateway cost, USD
     * @param posUnits       terminals on the deal
     * @param posRateMonthly rental per terminal per month, local currency
     * @param ecomSetupFee   one-time e-commerce setup fee, local currency
     * @param ecomAnnualFee  recurring annual e-commerce fee, local currency
     * @param settlementFeePerTxnUsd settlement / payment-gateway fee, USD
     * @param debitSmallTxnPct  % of debit transactions BELOW the ticket split
     * @param creditSmallTxnPct % of credit transactions BELOW the ticket split
     */
    public record Inputs(
            String countryCode, String channel, String mcc,
            double annualVolume, long totalTxns,
            List<MixRow> mixRows,
            double gatewayCostUsd,
            double posUnits, double posRateMonthly,
            double ecomSetupFee, double ecomAnnualFee,
            double settlementFeePerTxnUsd,
            double dccOptInPct, double dccMsfPct,
            double dccMerchantRebatePct, double dccVendorRebatePct,
            Double debitSmallTxnPct, Double creditSmallTxnPct) { }

    // ── Entry point ────────────────────────────────────────────────────────

    /**
     * Prices a quote. Returns {@code rows}, {@code totals}, {@code notes} and
     * the {@code context} (currency, USD rate, MCC) the screen needs to label
     * what it is showing.
     */
    public Map<String, Object> calculate(Inputs in) {
        RateBook book = rates.rateBook(in.countryCode());
        CountryConfig cfg = book.config();
        String channel = PriceBuilderRates.channel(in.channel());

        Mcc mcc = in.mcc() == null ? null : rates.mcc(in.countryCode(), in.mcc());
        if (mcc == null) {
            // An unknown MCC is priced at the sector fallback for "retail" and
            // flagged, rather than silently quoted at zero interchange — which
            // would read as pure margin and win a deal that loses money.
            mcc = new Mcc(in.mcc() == null ? "" : in.mcc(), "retail",
                    "Unmapped MCC — priced at the standard retail sector rate", "NORMAL");
        }

        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<RowResult> results = new ArrayList<>();

        for (MixRow mix : in.mixRows()) {
            SchemeRow row = book.row(mix.schemeKey());
            if (row == null) continue;
            RowResult r = priceRow(mix, row, mcc, in, book, cfg, channel, notes);
            results.add(r);
            rows.add(r.toMap(row, cfg));
        }

        Map<String, Object> totals = total(results, in, cfg, channel);

        if (mcc.description().startsWith("Unmapped MCC"))
            notes.add("MCC " + in.mcc() + " is not in the Price Builder catalog. "
                    + "It has been priced at the standard retail sector rate — confirm "
                    + "the interchange before the quote leaves the building.");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("countryCode",  cfg.countryCode());
        context.put("countryName",  cfg.countryName());
        context.put("currencyCode", cfg.currencyCode());
        context.put("usdRate",      cfg.usdRate());
        context.put("channel",      channel);
        context.put("mcc",          mcc.mcc());
        context.put("mccSector",    mcc.sector());
        context.put("mccDescription", mcc.description());
        context.put("mccType",      mcc.mccType());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("context", context);
        out.put("rows",    rows);
        out.put("totals",  totals);
        out.put("notes",   notes);
        return out;
    }

    // ── One row ────────────────────────────────────────────────────────────

    /** What a priced row carries forward into the totals. */
    private record RowResult(
            String schemeKey, double volumeLocal, double volumeUsd, long txns,
            double icPctShown, double sfPct, double txnFeeUsd,
            double msfRevenueUsd, double icCostUsd, double sfCostUsd,
            double txnFeeCostUsd, double netMarginUsd,
            boolean local, boolean debit, String basis) {

        Map<String, Object> toMap(SchemeRow row, CountryConfig cfg) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemeKey",      schemeKey);
            m.put("label",          row.label());
            m.put("scheme",         row.scheme());
            m.put("locality",       row.locality());
            m.put("tier",           row.tier());
            m.put("cardType",       row.cardType());
            m.put("volumeLocal",    round(volumeLocal, 2));
            m.put("volumeUsd",      round(volumeUsd, 2));
            m.put("transactions",   txns);
            // Rates go out as percent numbers because that is what the table
            // renders; the fractions stay inside the engine.
            m.put("icPct",          round(icPctShown * 100, 4));
            m.put("sfPct",          round(sfPct * 100, 4));
            m.put("txnFeeUsd",      round(txnFeeUsd, 4));
            m.put("msfRevenueUsd",  round(msfRevenueUsd, 2));
            m.put("interchangeUsd", round(icCostUsd, 2));
            m.put("schemeFeeUsd",   round(sfCostUsd, 2));
            m.put("txnFeeCostUsd",  round(txnFeeCostUsd, 2));
            m.put("netMarginUsd",   round(netMarginUsd, 2));
            m.put("netMarginPct",   volumeUsd > 0 ? round(netMarginUsd / volumeUsd * 100, 4) : 0.0);
            m.put("basis",          basis);
            return m;
        }
    }

    private RowResult priceRow(MixRow mix, SchemeRow row, Mcc mcc, Inputs in,
                               RateBook book, CountryConfig cfg, String channel,
                               List<String> notes) {

        double volumeLocal = mix.volumePct() / 100.0 * in.annualVolume();
        double volumeUsd   = volumeLocal * cfg.usdRate();
        long   txns        = Math.round(mix.txnPct() / 100.0 * in.totalTxns());

        SpecialRule rule = book.specialRule(mcc, row, channel);

        // The uplift applies only to percentage-based interchange on Visa and
        // Mastercard, and never to a preferential MCC.
        double uplift = (PriceBuilderRates.isEcom(channel)
                && row.isUpliftScheme()
                && !mcc.isPreferential()) ? cfg.ecomIcUplift() : 0.0;

        double icCostUsd;
        double icPctShown;
        String basis;
        boolean perTxnIc = false;

        if (rule != null && rule.isPerTxn()) {
            // Interchange is an amount per transaction, so volume does not
            // enter it at all and there is no rate to show.
            double amt = rule.amountLocal() == null ? 0.0 : rule.amountLocal();
            icCostUsd  = txns * (rule.amountIsUsd() ? amt : amt * cfg.usdRate());
            icPctShown = 0.0;
            basis      = rule.note() != null ? rule.note() : "Per transaction";
            perTxnIc   = true;

        } else if (rule != null && rule.isTicketSplit()) {
            // The split percentage is a share of TRANSACTIONS, but the source
            // tool applied it to VOLUME, and the commercial team's spreadsheets
            // are built the same way. Kept as-is so quotes reconcile with what
            // they already sign off; see the screen's help text, which says so
            // in the open rather than hiding it in a comment.
            double smallPct = splitPct(row, in);
            double smallVolUsd = smallPct / 100.0 * volumeUsd;
            double largeVolUsd = volumeUsd - smallVolUsd;

            double smallRate = zero(rule.smallRate()) + uplift;
            double largeRate = zero(rule.largeRate()) + uplift;

            icCostUsd  = smallRate * smallVolUsd + largeRate * largeVolUsd;
            icPctShown = volumeUsd > 0 ? icCostUsd / volumeUsd : smallRate;
            basis = String.format("%s — %.2f%% of volume below %s %,.0f",
                    rule.note() != null ? rule.note() : "Ticket split",
                    smallPct, cfg.currencyCode(), zero(rule.threshold()));

        } else if (rule != null && rule.isFlatPct()) {
            icPctShown = zero(rule.flatRate()) + uplift;
            icCostUsd  = icPctShown * volumeUsd;
            basis      = rule.note() != null ? rule.note() : "Per-MCC rate";

        } else {
            double base = book.baseInterchange(mcc.mcc(), mcc.sector(), row);
            icPctShown = base + uplift;
            icCostUsd  = icPctShown * volumeUsd;
            basis      = base == 0.0
                    ? "No interchange on file"
                    : (book.mccCosts().containsKey(mcc.mcc() + "|" + row.scheme() + "|"
                            + row.locality() + "|" + (row.tier() == null ? "" : row.tier()))
                        ? "Per-MCC rate" : "Sector rate (" + mcc.sector() + ")");
            if (base == 0.0 && volumeLocal > 0)
                notes.add("No interchange is on file for " + row.label()
                        + " at MCC " + mcc.mcc() + " — that row is priced at zero cost.");
        }

        double sfPct     = book.schemeFee(row);
        double sfCostUsd = sfPct * volumeUsd;

        // Per-transaction fees: the card-present debit international fee at
        // POS, the gateway cost on e-commerce.
        double txnFeeUsd;
        if (PriceBuilderRates.isEcom(channel)) {
            txnFeeUsd = in.gatewayCostUsd();
        } else {
            txnFeeUsd = (row.isDebit() && row.isInternational()) ? cfg.debitIntlTxnFee() : 0.0;
        }

        // When interchange is charged per transaction, the MSF on that row is
        // quoted the same way — a local-currency amount per transaction, not a
        // rate on volume. Pricing one per transaction and the other on volume
        // would make the row's margin meaningless.
        double msfRevenueUsd = perTxnIc
                ? mix.msfPct() * txns * cfg.usdRate()
                : mix.msfPct() / 100.0 * volumeUsd;

        double txnFeeCostUsd = txns * txnFeeUsd;

        // Transaction fees are a deal-level cost, netted in the totals, not in
        // the row margin — same as the source tool.
        double netMarginUsd = msfRevenueUsd + icCostUsd + sfCostUsd;

        return new RowResult(mix.schemeKey(), volumeLocal, volumeUsd, txns,
                icPctShown, sfPct, txnFeeUsd, msfRevenueUsd, icCostUsd, sfCostUsd,
                txnFeeCostUsd, netMarginUsd, row.isLocal(), row.isDebit(), basis);
    }

    /** The share of transactions below the threshold, defaulting to all. */
    private static double splitPct(SchemeRow row, Inputs in) {
        Double v = row.isDebit() ? in.debitSmallTxnPct() : in.creditSmallTxnPct();
        if (v == null) return 100.0;
        return Math.max(0.0, Math.min(100.0, v));
    }

    // ── Totals ─────────────────────────────────────────────────────────────

    private Map<String, Object> total(List<RowResult> rows, Inputs in,
                                      CountryConfig cfg, String channel) {
        boolean ecom = PriceBuilderRates.isEcom(channel);

        double msfRevenue  = sum(rows, r -> r.msfRevenueUsd());
        double interchange = sum(rows, r -> r.icCostUsd());
        double schemeFees  = sum(rows, r -> r.sfCostUsd());
        double txnFees     = sum(rows, r -> r.txnFeeCostUsd());
        double rowMargin   = sum(rows, r -> r.netMarginUsd());
        double volumeUsd   = sum(rows, r -> r.volumeUsd());

        // Terminal rental is monthly per unit over a year; e-commerce has a
        // one-time setup fee plus a recurring annual fee instead.
        double rentalUsd = ecom
                ? in.ecomAnnualFee() * cfg.usdRate()
                : in.posUnits() * in.posRateMonthly() * 12 * cfg.usdRate();
        double setupUsd = ecom ? in.ecomSetupFee() * cfg.usdRate() : 0.0;
        double settlementUsd = in.settlementFeePerTxnUsd() * in.totalTxns();

        // DCC is only ever earned on international volume.
        double intlVolumeUsd = sum(rows, r -> r.local() ? 0.0 : r.volumeUsd());
        double dccVolumeUsd  = in.dccOptInPct() / 100.0 * intlVolumeUsd;
        double dccMarginPct  = in.dccMsfPct() - in.dccMerchantRebatePct() - in.dccVendorRebatePct();
        double dccRevenueUsd = dccVolumeUsd > 0 ? dccMarginPct / 100.0 * dccVolumeUsd : 0.0;
        double dccSchemeFeeUsd = dccVolumeUsd > 0 ? cfg.dccSchemeFee(channel) * dccVolumeUsd : 0.0;

        double netSpread = rowMargin + dccRevenueUsd + rentalUsd + setupUsd
                + settlementUsd - txnFees;

        // Recommended MSF = blended cost + the country's target margin, built
        // separately for domestic debit and domestic credit because they are
        // quoted separately on the term sheet. International is a fixed
        // commercial number, not a build-up.
        double debitMsf  = recommend(rows, cfg, true);
        double creditMsf = recommend(rows, cfg, false);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("volumeUsd",          round(volumeUsd, 2));
        t.put("msfRevenueUsd",      round(msfRevenue, 2));
        t.put("interchangeUsd",     round(interchange, 2));
        t.put("schemeFeesUsd",      round(schemeFees, 2));
        t.put("txnFeesUsd",         round(txnFees, 2));
        t.put("rowMarginUsd",       round(rowMargin, 2));
        t.put("rentalUsd",          round(rentalUsd, 2));
        t.put("ecomSetupUsd",       round(setupUsd, 2));
        t.put("settlementUsd",      round(settlementUsd, 2));
        t.put("dccVolumeUsd",       round(dccVolumeUsd, 2));
        t.put("dccRevenueUsd",      round(dccRevenueUsd, 2));
        t.put("dccSchemeFeeUsd",    round(dccSchemeFeeUsd, 2));
        t.put("netSpreadUsd",       round(netSpread, 2));
        t.put("effectiveMsfPct",    volumeUsd > 0 ? round(msfRevenue / volumeUsd * 100, 4) : 0.0);
        t.put("blendedIcPct",       volumeUsd > 0 ? round(interchange / volumeUsd * 100, 4) : 0.0);
        t.put("blendedSfPct",       volumeUsd > 0 ? round(schemeFees / volumeUsd * 100, 4) : 0.0);
        t.put("netSpreadPct",       volumeUsd > 0 ? round(netSpread / volumeUsd * 100, 4) : 0.0);
        t.put("avgTicketLocal",     in.totalTxns() > 0 ? round(in.annualVolume() / in.totalTxns(), 2) : 0.0);
        t.put("marginPerTxnUsd",    in.totalTxns() > 0 ? round(netSpread / in.totalTxns(), 4) : 0.0);
        t.put("recommendedDebitMsfPct",  round(debitMsf, 4));
        t.put("recommendedCreditMsfPct", round(creditMsf, 4));
        t.put("recommendedIntlMsfPct",   round(cfg.intlMsf(channel) * 100, 4));
        t.put("targetMarginPct",         round(cfg.targetMargin() * 100, 4));
        return t;
    }

    /**
     * Blended domestic cost plus the target margin, as a percent number.
     *
     * Domestic only, and split by card type: an international row carries a
     * fixed MSF and must not drag the domestic recommendation with it.
     */
    private static double recommend(List<RowResult> rows, CountryConfig cfg, boolean debit) {
        double vol = 0, ic = 0, sf = 0;
        for (RowResult r : rows) {
            if (!r.local() || r.debit() != debit) continue;
            vol += r.volumeUsd();
            ic  += r.icCostUsd();
            sf  += r.sfCostUsd();
        }
        if (vol <= 0) return 0.0;
        return Math.abs(ic / vol * 100) + Math.abs(sf / vol * 100) + cfg.targetMargin() * 100;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private interface Pick { double of(RowResult r); }

    private static double sum(List<RowResult> rows, Pick p) {
        double s = 0;
        for (RowResult r : rows) s += p.of(r);
        return s;
    }

    private static double zero(Double v) { return v == null ? 0.0 : v; }

    /**
     * Rounds for transport. Money is rounded to the cent and rates to four
     * decimals only on the way OUT — every intermediate step above runs at
     * full double precision, so rounding never compounds through the totals.
     */
    private static double round(double v, int dp) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }
}
