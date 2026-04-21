// ─── Shared Formatting Utilities ─────────────────────────────────────
// Usage: import { formatCurrency, formatNumber, formatCompact, formatPercent, createFmt } from '../../utils/formatters';

/**
 * Format a number as currency (default: AED).
 * @param {number} val
 * @param {string} currency - ISO 4217 currency code
 */
export const formatCurrency = (val, currency = 'AED') =>
    new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 2 }).format(val || 0);

/**
 * Format a number with thousand separators.
 */
export const formatNumber = (val) =>
    new Intl.NumberFormat('en-US').format(val || 0);

/**
 * Format a large number in compact notation (e.g., 1.2M, 3.5K).
 */
export const formatCompact = (val) =>
    new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

/**
 * Format a percentage value.
 */
export const formatPercent = (val, decimals = 1) =>
    `${Number(val || 0).toFixed(decimals)}%`;

/**
 * Dashboard formatter factory — creates formatters using tenant currency symbol.
 * Shared across all dashboard/analytics pages to avoid duplicating this logic.
 *
 * Usage:
 *   const { currencySymbol } = useAuth();
 *   const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
 *   fmt.currency(125000)  → "AED 125.0K"
 *   fmt.number(1500000)   → "1.5M"
 *   fmt.growth(-3.2)      → "-3.2%"
 *   fmt.date('2025-09-15') → "Sep 15"
 */
export const createFmt = (sym = 'AED') => ({
    currency: (val) => {
        if (val === 0 || val == null) return sym + ' 0';
        if (Math.abs(val) >= 1_000_000) return sym + ' ' + (val / 1_000_000).toFixed(2) + 'M';
        if (Math.abs(val) >= 1_000) return sym + ' ' + (val / 1_000).toFixed(1) + 'K';
        return sym + ' ' + val.toLocaleString();
    },
    number: (val) => {
        if (val == null) return '0';
        if (Math.abs(val) >= 1_000_000) return (val / 1_000_000).toFixed(1) + 'M';
        if (Math.abs(val) >= 1_000) return (val / 1_000).toFixed(1) + 'K';
        return val.toLocaleString();
    },
    growth: (val) => {
        if (val == null) return '+0.0%';
        const sign = val >= 0 ? '+' : '';
        return `${sign}${val.toFixed(1)}%`;
    },
    date: (d) => {
        try {
            return new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
        } catch { return d; }
    },
});
