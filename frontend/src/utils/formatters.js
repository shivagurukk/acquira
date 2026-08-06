// ─── Shared Formatting Utilities ─────────────────────────────────────
// Usage: import { formatCurrency, formatNumber, formatCompact, formatPercent, createFmt } from '../../utils/formatters';

// Module-level default currency, kept in sync with the active tenant by
// AuthContext (see setDefaultCurrency). This makes formatCurrency(val) and
// createFmt() render in the tenant's currency app-wide without each caller
// having to pass it. Falls back to AED.
let DEFAULT_CCY = 'AED';
export const setDefaultCurrency = (code) => { if (code) DEFAULT_CCY = code; };
export const getDefaultCurrency = () => DEFAULT_CCY;

// ── Per-tenant locale (date format + timezone) ─────────────────────────
// Mirrors the currency pattern above: AuthContext fetches GET /users/me/locale
// (tenant_setting keys locale.date_format / locale.timezone) on login and on
// tenant switch and pushes the values here, so every formatter renders dates
// the way the active bank expects, app-wide, with zero per-caller wiring.
let DEFAULT_DATE_FORMAT = 'DD/MM/YYYY';   // DD/MM/YYYY | MM/DD/YYYY | YYYY-MM-DD | DD-MMM-YYYY
let DEFAULT_TIMEZONE = '';                // IANA id (e.g. Asia/Bahrain); '' = browser zone
export const setDefaultLocale = ({ dateFormat, timezone } = {}) => {
    if (dateFormat) DEFAULT_DATE_FORMAT = dateFormat;
    if (timezone !== undefined) DEFAULT_TIMEZONE = timezone || '';
};
export const getDefaultLocale = () => ({ dateFormat: DEFAULT_DATE_FORMAT, timezone: DEFAULT_TIMEZONE });

const tzOpts = () => (DEFAULT_TIMEZONE ? { timeZone: DEFAULT_TIMEZONE } : {});

/**
 * Full date in the tenant's configured format (and timezone, if set).
 *   DD/MM/YYYY → 15/09/2025    MM/DD/YYYY → 09/15/2025
 *   YYYY-MM-DD → 2025-09-15    DD-MMM-YYYY → 15-Sep-2025
 * Invalid input is returned untouched (matches fmt.date's forgiving behavior).
 */
export const formatDate = (d) => {
    try {
        const dt = new Date(d);
        if (isNaN(dt.getTime())) return d;
        const parts = new Intl.DateTimeFormat('en-GB',
            { day: '2-digit', month: '2-digit', year: 'numeric', ...tzOpts() })
            .formatToParts(dt)
            .reduce((acc, p) => (acc[p.type] = p.value, acc), {});
        const mon = new Intl.DateTimeFormat('en-US', { month: 'short', ...tzOpts() }).format(dt);
        switch (DEFAULT_DATE_FORMAT) {
            case 'MM/DD/YYYY':  return `${parts.month}/${parts.day}/${parts.year}`;
            case 'YYYY-MM-DD':  return `${parts.year}-${parts.month}-${parts.day}`;
            case 'DD-MMM-YYYY': return `${parts.day}-${mon}-${parts.year}`;
            case 'DD/MM/YYYY':
            default:            return `${parts.day}/${parts.month}/${parts.year}`;
        }
    } catch { return d; }
};

/**
 * Date-time in the tenant's format + timezone (24h clock).
 */
export const formatDateTime = (d) => {
    try {
        const dt = new Date(d);
        if (isNaN(dt.getTime())) return d;
        const time = new Intl.DateTimeFormat('en-GB',
            { hour: '2-digit', minute: '2-digit', hour12: false, ...tzOpts() }).format(dt);
        return `${formatDate(d)} ${time}`;
    } catch { return d; }
};

/**
 * Format a number as currency (defaults to the active tenant's currency).
 * @param {number} val
 * @param {string} currency - ISO 4217 currency code (defaults to tenant currency)
 */
export const formatCurrency = (val, currency = DEFAULT_CCY) =>
    new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 2 }).format(val || 0);

/**
 * MSF amounts — exact figure, up to 4 decimal places, never compacted.
 * MSF is stored at 4-dp precision (DECIMAL(21,4)) so finance can reconcile
 * against source files to the fils/paisa; K/M compaction or 2-dp rounding
 * would hide exactly the digits they need.
 */
export const formatMsf = (val, sym = '') =>
    (sym ? sym + ' ' : '') + new Intl.NumberFormat('en-US',
        { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(val || 0);

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
 * Compact tiers: >= 1B → "1.24B", >= 1M → "987.5M", >= 1K → "45.2K".
 * (B tier added for the CEO dashboard — large books cross into billions.)
 *
 * Usage:
 *   const { currencySymbol } = useAuth();
 *   const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
 *   fmt.currency(1250000000) → "AED 1.25B"
 *   fmt.currency(125000)     → "AED 125.0K"
 *   fmt.number(1500000)      → "1.5M"
 *   fmt.growth(-3.2)         → "-3.2%"
 *   fmt.date('2025-09-15')   → "Sep 15"
 */
export const createFmt = (sym = DEFAULT_CCY) => ({
    currency: (val) => {
        if (val === 0 || val == null) return sym + ' 0';
        if (Math.abs(val) >= 1_000_000_000) return sym + ' ' + (val / 1_000_000_000).toFixed(2) + 'B';
        if (Math.abs(val) >= 1_000_000) return sym + ' ' + (val / 1_000_000).toFixed(2) + 'M';
        if (Math.abs(val) >= 1_000) return sym + ' ' + (val / 1_000).toFixed(1) + 'K';
        return sym + ' ' + val.toLocaleString();
    },
    number: (val) => {
        if (val == null) return '0';
        if (Math.abs(val) >= 1_000_000_000) return (val / 1_000_000_000).toFixed(2) + 'B';
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
            // Short axis/tile form ("Sep 15") — honors the tenant timezone; the
            // month-first vs day-first order follows the tenant date format.
            const dt = new Date(d);
            if (isNaN(dt.getTime())) return d;
            const opts = { month: 'short', day: 'numeric', ...tzOpts() };
            const useDayFirst = DEFAULT_DATE_FORMAT.startsWith('DD');
            return dt.toLocaleDateString(useDayFirst ? 'en-GB' : 'en-US', opts);
        } catch { return d; }
    },
    /** Full date in the tenant's configured format — see formatDate(). */
    fullDate: (d) => formatDate(d),
});
