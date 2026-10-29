/**
 * Executive display-currency toggle — the USD conversion layer in
 * utils/formatters.js (fed by config/fxRates.js).
 *
 * Contract under test:
 *  - 'LOCAL' mode (the default) is a strict no-op: every formatter renders
 *    exactly what it always did, so the 30+ non-executive pages that share
 *    these formatters can never be affected by the feature.
 *  - 'USD' mode converts values at the hardcoded USD_PER_UNIT rate, relabels
 *    them 'USD', and renders 2dp — including when the caller labels money
 *    with the tenant's display symbol rather than the ISO code.
 *  - Fail-safe: a currency with no rate (or already USD) is never converted
 *    and never relabelled — mislabelled money is worse than unconverted money.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
    setDefaultCurrency, setDisplayMode, getDisplayMode,
    formatCurrency, formatCompactCurrency, formatMsf, formatPercent, createFmt,
    convertForDisplay, displayCurrencyCode, isUsdDisplay, usdRateInfo,
    setFxSeries, setDefaultFxSeries, restoreDefaultFxSeries, fxRateForRange,
} from '../utils/formatters';
import { USD_PER_UNIT, FX_RATE_AS_OF } from '../config/fxRates';

const BHD_RATE = USD_PER_UNIT.BHD; // 2.65252

// Intl style:'currency' joins code and number with a non-breaking space —
// normalise it so the assertions can use plain spaces.
const norm = (s) => s.replace(/ /g, ' ');

beforeEach(() => {
    setDisplayMode('LOCAL');
    setDefaultCurrency('BHD', 3, 'BHD');
});
afterEach(() => {
    // Never leak USD mode into other test files sharing the module singleton.
    setDisplayMode('LOCAL');
});

describe('LOCAL mode is a strict no-op', () => {
    it('renders tenant currency at tenant precision, unchanged', () => {
        expect(norm(formatCurrency(100))).toBe('BHD 100.000');
        expect(formatCompactCurrency(1500)).toBe('BHD 1.5K');
        expect(formatMsf(1.2345)).toBe('BHD 1.2345');
        const fmt = createFmt('BHD', 3);
        expect(fmt.money(1234.5)).toBe('BHD 1,234.500');
        expect(fmt.amount(100)).toBe('100.000');
        expect(fmt.decimals()).toBe(3);
        expect(isUsdDisplay()).toBe(false);
        expect(displayCurrencyCode()).toBe('BHD');
        expect(convertForDisplay(100)).toBe(100);
        expect(usdRateInfo()).toBeNull();
    });
});

describe('USD mode converts, relabels and renders 2dp', () => {
    beforeEach(() => setDisplayMode('USD'));

    it('formatCurrency converts BHD → $ at the pegged rate', () => {
        expect(getDisplayMode()).toBe('USD');
        // 100 BHD * 2.65252 = 265.252 → $265.25
        expect(formatCurrency(100)).toBe('$265.25');
    });

    it('formatCompactCurrency converts and compacts in USD', () => {
        // 1,000,000 BHD → 2,652,520 USD → "USD 2.653M"
        expect(formatCompactCurrency(1_000_000)).toBe('USD 2.653M');
        // Below the compact threshold: exact 2dp
        expect(formatCompactCurrency(100)).toBe('USD 265.25');
    });

    it('formatMsf keeps reconciliation digits on the converted figure', () => {
        // 1 BHD → 2.65252 → min 2 / max 4 → "USD 2.6525"
        expect(formatMsf(1)).toBe('USD 2.6525');
    });

    it('createFmt money/amount/decimals follow the toggle', () => {
        const fmt = createFmt('BHD', 3);
        expect(fmt.money(100)).toBe('USD 265.25');
        expect(fmt.amount(100)).toBe('265.25');
        expect(fmt.decimals()).toBe(2);
        expect(fmt.currency(1_000_000)).toBe('USD 2.653M');
        expect(fmt.msf(1)).toBe('USD 2.6525');
    });

    it('recognises the tenant display symbol, not just the ISO code', () => {
        setDefaultCurrency('BHD', 3, 'BD');
        const fmt = createFmt('BD', 3);   // pages pass currencySymbol here
        expect(fmt.money(100)).toBe('USD 265.25');
        expect(displayCurrencyCode('BD')).toBe('USD');
        expect(convertForDisplay(100, 'BD')).toBeCloseTo(100 * BHD_RATE, 6);
        // …and back to the symbol untouched in LOCAL mode
        setDisplayMode('LOCAL');
        expect(fmt.money(100)).toBe('BD 100.000');
    });

    it('converts an explicit non-tenant currency that has a rate (AED)', () => {
        // ExecutiveSalesPulse passes the API-stamped code per response block.
        expect(formatCompactCurrency(100, 'AED', 2)).toBe('USD 27.23');
    });

    it('helpers expose the raw conversion for CSV builders', () => {
        expect(convertForDisplay(100)).toBeCloseTo(100 * BHD_RATE, 6);
        expect(displayCurrencyCode('BHD')).toBe('USD');
        expect(isUsdDisplay('BHD')).toBe(true);
        const fx = usdRateInfo('BHD');
        expect(fx).toMatchObject({ base: 'BHD', rate: BHD_RATE, asOf: FX_RATE_AS_OF, method: 'PEG', floating: false });
    });

    it('never converts percentages or counts', () => {
        expect(formatPercent(12.3)).toBe('12.3%');
        const fmt = createFmt('BHD', 3);
        expect(fmt.number(1500)).toBe('1.5K');
        expect(fmt.growth(-3.2)).toBe('-3.2%');
    });
});

describe('USD mode fail-safes', () => {
    beforeEach(() => setDisplayMode('USD'));

    it('a currency with no rate is left completely untouched', () => {
        setDefaultCurrency('XYZ', 2, 'XYZ');
        expect(isUsdDisplay()).toBe(false);
        expect(displayCurrencyCode()).toBe('XYZ');
        expect(convertForDisplay(100)).toBe(100);
        expect(usdRateInfo()).toBeNull();
        expect(createFmt('XYZ', 2).money(100)).toBe('XYZ 100.00');
    });

    it('a USD tenant is never double-converted', () => {
        setDefaultCurrency('USD', 2, 'USD');
        expect(isUsdDisplay()).toBe(false);
        expect(convertForDisplay(100)).toBe(100);
        expect(formatCurrency(100)).toBe('$100.00');
    });

    it('no tenant currency set → bare number, no invented USD label', () => {
        setDefaultCurrency(null, null, null);
        expect(convertForDisplay(100)).toBe(100);
        expect(displayCurrencyCode()).toBeNull();
    });

    it('flipping back to LOCAL restores the original rendering', () => {
        expect(formatCurrency(100)).toBe('$265.25');
        setDisplayMode('LOCAL');
        expect(norm(formatCurrency(100))).toBe('BHD 100.000');
    });
});

/* ── Floating currency (EGP): dated series from GET /api/fx/series ───────
   No constant exists for EGP. Until a series is loaded nothing converts (and
   nothing is relabelled); with one loaded, a date converts at that day's
   rate, a range at the volume-weighted rate of its days, and the overall
   effective rate applies when no date is given. */
describe('floating currency converts from the dated series', () => {
    const series = {
        base: 'EGP', quote: 'USD', floating: true,
        days: [
            // 1 USD = 50 EGP on the 1st, = 40 EGP on the 2nd (carried to the 3rd)
            { date: '2026-10-01', rateDate: '2026-10-01', unitsPerUsd: 50, usdPerUnit: 0.02,  staleDays: 0, volume: 1000 },
            { date: '2026-10-02', rateDate: '2026-10-02', unitsPerUsd: 40, usdPerUnit: 0.025, staleDays: 0, volume: 3000 },
            { date: '2026-10-03', rateDate: '2026-10-02', unitsPerUsd: 40, usdPerUnit: 0.025, staleDays: 1, volume: 0 },
        ],
        effective: { usdPerUnit: 0.02375, unitsPerUsd: 42.105263, method: 'VOLUME_WEIGHTED',
                     from: '2026-10-01', to: '2026-10-03', lastRateDate: '2026-10-02' },
        latest: { rateDate: '2026-10-02', unitsPerUsd: 40, usdPerUnit: 0.025, source: 'MPS_SEED' },
    };
    beforeEach(() => {
        setDefaultCurrency('EGP', 2, 'EGP');
        setDisplayMode('USD');
        setDefaultFxSeries(null);
        setFxSeries(null);
    });
    afterEach(() => { setDefaultFxSeries(null); setFxSeries(null); setDisplayMode('LOCAL'); });

    it('does not convert or relabel before a series is loaded', () => {
        expect(isUsdDisplay()).toBe(false);
        expect(displayCurrencyCode()).toBe('EGP');
        expect(convertForDisplay(100)).toBe(100);
        expect(norm(formatCurrency(100))).toBe('EGP 100.00');
        expect(usdRateInfo()).toBeNull();
    });

    it('uses the day rate for a date and the volume-weighted rate for a range', () => {
        setFxSeries(series);
        expect(isUsdDisplay()).toBe(true);
        expect(displayCurrencyCode()).toBe('USD');
        // 100 EGP on the 1st at 0.02 → $2; on the 3rd (carried from the 2nd) at 0.025 → $2.50
        expect(convertForDisplay(100, 'EGP', '2026-10-01')).toBeCloseTo(2, 10);
        expect(convertForDisplay(100, 'EGP', '2026-10-03')).toBeCloseTo(2.5, 10);
        // Range 1st–2nd: (1000*0.02 + 3000*0.025)/4000 = 0.02375
        expect(fxRateForRange({ from: '2026-10-01', to: '2026-10-02' })).toBeCloseTo(0.02375, 12);
        // The 3rd has no volume → excluded from the weight; the weighted rate stands
        expect(fxRateForRange({ from: '2026-10-01', to: '2026-10-03' })).toBeCloseTo(0.02375, 12);
        // A range with no volume at all falls back to the simple average of its days
        expect(fxRateForRange({ from: '2026-10-03', to: '2026-10-03' })).toBeCloseTo(0.025, 12);
        // A range outside the series yields nothing → falls back to the effective rate
        expect(fxRateForRange({ from: '2026-09-01', to: '2026-09-02' })).toBeCloseTo(0.02375, 12);
        // No date → the series' overall effective rate
        expect(convertForDisplay(100)).toBeCloseTo(2.375, 10);
        expect(formatCurrency(100)).toBe('$2.38');
    });

    it('reports provenance with the real rate dates used', () => {
        setFxSeries(series);
        const info = usdRateInfo('EGP', { from: '2026-10-01', to: '2026-10-03' });
        expect(info).toMatchObject({ base: 'EGP', floating: true, method: 'VOLUME_WEIGHTED',
            from: '2026-10-01', to: '2026-10-03', daysExact: 2, daysCarried: 1, daysMissing: 0, maxStaleDays: 1,
            asOf: '2026-10-02' });
        expect(info.unitsPerUsd).toBeCloseTo(42.105263, 4);
    });

    it('falls back to the Layout default series and restores it after a page range', () => {
        setDefaultFxSeries(series);
        expect(isUsdDisplay()).toBe(true);
        const pageSeries = { ...series, effective: { ...series.effective, usdPerUnit: 0.02 } };
        setFxSeries(pageSeries);
        expect(convertForDisplay(100)).toBeCloseTo(2, 10);
        restoreDefaultFxSeries();
        expect(convertForDisplay(100)).toBeCloseTo(2.375, 10);
    });

    it('a series for another currency never converts this one', () => {
        setFxSeries({ ...series, base: 'NGN' });
        expect(isUsdDisplay()).toBe(false);
        expect(convertForDisplay(100)).toBe(100);
    });
});
