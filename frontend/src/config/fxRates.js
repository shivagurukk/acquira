// ── Hardcoded USD conversion rates (executive display toggle) ───────────
// USD per 1 unit of local currency, used ONLY by the executive-menu pages'
// display-currency dropdown (utils/formatters.js display layer). These are
// deliberately hardcoded on the frontend per product decision: the Gulf
// currencies are hard-pegged to the dollar, so the rates are stable facts,
// not market data.
//
//   BHD 0.377 /USD  → 2.6525199...   (pegged since 1980)
//   AED 3.6725/USD  → 0.2722941...   (pegged since 1997)
//   OMR 0.3845/USD  → 2.6007802...   (pegged since 1986)
//   SAR 3.75  /USD  → 0.2666667      (pegged)
//   QAR 3.64  /USD  → 0.2747253      (pegged)
//
// FLOATING currencies (EGP) are deliberately NOT listed. They are converted
// at the rate OF EACH BUSINESS DATE from the bank's own daily rate table
// (GET /api/fx/series → utils/formatters.js setFxSeries / hooks/useFxSeries),
// and a constant here would silently win over it.
export const USD_PER_UNIT = {
    BHD: 2.6525199,   // 1 / 0.377   (pegged)
    AED: 0.2722941,   // 1 / 3.6725  (pegged)
    OMR: 2.60078,
    SAR: 0.26667,
    QAR: 0.27473,
    KWD: 3.25733,   // managed basket, near-peg — indicative
    // EGP intentionally absent — see above.
};

// Stamp shown next to PEGGED conversions / in CSV footers so nobody mistakes
// an indicative conversion for a booked rate. Floating currencies carry the
// actual rate dates from the series instead.
export const FX_RATE_AS_OF = '2026-10-28';
