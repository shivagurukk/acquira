// ── Hardcoded USD conversion rates (executive display toggle) ───────────
// USD per 1 unit of local currency, used ONLY by the executive-menu pages'
// display-currency dropdown (utils/formatters.js display layer). These are
// deliberately hardcoded on the frontend per product decision: the Gulf
// currencies are hard-pegged to the dollar, so the rates are stable facts,
// not market data.
//
//   BHD 0.376 /USD  → 2.6595744...   (pegged since 1980)
//   AED 3.6725/USD  → 0.2722941...   (pegged since 1997)
//   OMR 0.3845/USD  → 2.6007802...   (pegged since 1986)
//   SAR 3.75  /USD  → 0.2666667      (pegged)
//   QAR 3.64  /USD  → 0.2747253      (pegged)
//
// EGP FLOATS. Its entry is an indicative snapshot and goes stale — update it
// (and FX_RATE_AS_OF) manually, or move it to a tenant setting if execs start
// caring about precision. The UI labels converted figures as indicative.
export const USD_PER_UNIT = {
    BHD: 2.65957,
    AED: 0.27229,
    OMR: 2.60078,
    SAR: 0.26667,
    QAR: 0.27473,
    KWD: 3.25733,   // managed basket, near-peg — indicative
    EGP: 0.02070,   // FLOATING — indicative snapshot
};

// Stamp shown next to converted figures / in CSV footers so nobody mistakes
// an indicative conversion for a booked rate.
export const FX_RATE_AS_OF = '2026-08-25';
