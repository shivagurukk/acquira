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
        expect(convertForDisplay(100)).toBeCloseTo(265.252, 6);
        expect(displayCurrencyCode('BHD')).toBe('USD');
        expect(isUsdDisplay('BHD')).toBe(true);
        const fx = usdRateInfo('BHD');
        expect(fx).toEqual({ base: 'BHD', rate: BHD_RATE, asOf: FX_RATE_AS_OF });
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
