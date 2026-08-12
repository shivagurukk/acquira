// ─── Shared Formatting Utilities ─────────────────────────────────────
// Usage: import { formatCurrency, formatNumber, formatCompact, formatPercent, createFmt } from '../../utils/formatters';

// ── Module-level tenant currency ───────────────────────────────────────
// Kept in sync with the active tenant by AuthContext (see setDefaultCurrency).
// This makes formatCurrency(val) and createFmt() render in the tenant's
// currency AND at the tenant's decimal precision app-wide, without each caller
// having to pass it.
//
// There is deliberately NO fallback currency. A wrong currency code on a money
// figure is worse than no code at all: BHD is 3-decimal (1 BHD = 1000 fils)
// while AED/EGP are 2-decimal, so mislabelling silently changes the value by
// 1000x. When the currency is unknown we render the bare number and warn once.
let DEFAULT_CCY = null;         // ISO 4217 code, e.g. 'BHD' | 'EGP' | 'AED'
let DEFAULT_DECIMALS = null;    // integer minor-unit digits: 3 for BHD, 2 for EGP/AED

/**
 * ISO 4217 minor-unit digits for the currencies this product can plausibly see.
 * Used ONLY as a last resort when the backend did not send currencyDecimals,
 * and for per-row currencies in transaction lists (a row can be in a currency
 * that is not the tenant's base currency).
 */
const CURRENCY_MINOR_UNITS = {
    BHD: 3, KWD: 3, OMR: 3, JOD: 3, TND: 3, LYD: 3, IQD: 3,
    JPY: 0, KRW: 0, VND: 0, CLP: 0, ISK: 0, PYG: 0, RWF: 0, UGX: 0, XOF: 0, XAF: 0,
    // Everything else (AED, EGP, USD, EUR, SAR, QAR, …) is 2.
};

/**
 * Decimal places for an arbitrary ISO 4217 code. Exported for per-row currency
 * columns (see components/TransactionList.jsx) where the tenant default does
 * not apply.
 */
export const decimalsForCurrency = (code, fallback = 2) => {
    if (!code) return fallback;
    const d = CURRENCY_MINOR_UNITS[String(code).toUpperCase()];
    return Number.isInteger(d) ? d : fallback;
};

const _warned = new Set();
const warnOnce = (key, msg) => {
    if (_warned.has(key)) return;
    _warned.add(key);
    // eslint-disable-next-line no-console
    console.warn('[formatters] ' + msg);
};

/**
 * Push the active tenant's currency into the shared formatters.
 * @param {string|null} code     ISO 4217 code from allowedTenants[].baseCurrency
 * @param {number|null} decimals allowedTenants[].currencyDecimals (2 or 3).
 *                               null/undefined means UNKNOWN — it is never
 *                               silently coerced to 2.
 */
export const setDefaultCurrency = (code, decimals) => {
    DEFAULT_CCY = code || null;
    DEFAULT_DECIMALS = Number.isInteger(decimals) ? decimals : null;
};
export const getDefaultCurrency = () => DEFAULT_CCY;
export const getCurrencyDecimals = () => DEFAULT_DECIMALS;

/**
 * Resolve the decimal precision to render money at.
 * Order: explicit argument → tenant setting → ISO minor units for the code
 * (with a warning, because the backend should have told us) → 2 (with a
 * warning). Always returns a single number so a column is never jagged.
 */
export const resolveDecimals = (decimals, currency = DEFAULT_CCY) => {
    if (Number.isInteger(decimals)) return decimals;
    if (Number.isInteger(DEFAULT_DECIMALS) && (!currency || currency === DEFAULT_CCY)) return DEFAULT_DECIMALS;
    if (currency) {
        warnOnce('dec:' + currency,
            `currencyDecimals unknown for ${currency}; falling back to ISO minor units (${decimalsForCurrency(currency)}).`);
        return decimalsForCurrency(currency);
    }
    warnOnce('dec:none', 'No tenant currency/decimals set; rendering money at 2dp with no currency code.');
    return 2;
};

const fixed = (n, d) => new Intl.NumberFormat('en-US',
    { minimumFractionDigits: d, maximumFractionDigits: d }).format(n);

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
 * Format a number as currency (defaults to the active tenant's currency and
 * the tenant's decimal precision).
 *
 * minimumFractionDigits === maximumFractionDigits, always: a BHD column must be
 * uniformly 3dp, never jagged 2-or-3dp depending on the value.
 *
 * When the currency is unknown the number is rendered WITHOUT any currency
 * code (and a one-time console warning is emitted) — we never invent one.
 *
 * @param {number} val
 * @param {string} [currency] ISO 4217 code (defaults to tenant currency)
 * @param {number} [decimals] override precision (defaults to tenant decimals)
 */
export const formatCurrency = (val, currency = DEFAULT_CCY, decimals) => {
    const d = resolveDecimals(decimals, currency);
    const n = Number(val) || 0;
    if (!currency) {
        warnOnce('ccy:none', 'Money rendered without a currency code — tenant currency is not set yet.');
        return fixed(n, d);
    }
    try {
        return new Intl.NumberFormat('en-US',
            { style: 'currency', currency, minimumFractionDigits: d, maximumFractionDigits: d }).format(n);
    } catch {
        // Non-ISO / unrecognised code: still label it, just not via Intl.
        return currency + ' ' + fixed(n, d);
    }
};

/**
 * MSF amounts — exact figure, never compacted.
 * MSF is stored at 4-dp precision (DECIMAL(21,4)) so finance can reconcile
 * against source files to the fils/paisa; K/M compaction would hide exactly
 * the digits they need. The floor is the tenant's own precision (3dp for BHD)
 * so a BHD MSF column never renders coarser than the currency itself.
 *
 * Defaults to the tenant symbol — several call sites omit the symbol and used
 * to render a bare, unlabelled number.
 */
export const formatMsf = (val, sym = DEFAULT_CCY) => {
    const min = Math.max(2, resolveDecimals());
    return (sym ? sym + ' ' : '') + new Intl.NumberFormat('en-US',
        { minimumFractionDigits: min, maximumFractionDigits: Math.max(4, min) }).format(Number(val) || 0);
};

/**
 * Format a number with thousand separators.
 */
export const formatNumber = (val) =>
    new Intl.NumberFormat('en-US').format(val || 0);

/**
 * Format a large NON-MONEY number in compact notation (e.g., 1.2M, 3.5K).
 * For money use formatCompactCurrency / createFmt().currency so the figure
 * carries its currency.
 */
export const formatCompact = (val) =>
    new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

// Compact tiers keep ~4 significant digits with trailing zeros trimmed, so a
// 3-decimal currency does not lose real precision when compacted:
//   1234.56 → "1.235K"    1200 → "1.2K"    987654321 → "987.7M"
// (The old implementation hardcoded .toFixed(2)/.toFixed(1), which is wrong
// for BHD and arbitrary for everything else.)
const sig4 = (n) => new Intl.NumberFormat('en-US', { maximumSignificantDigits: 4 }).format(n);

const compactParts = (val, decimals) => {
    const a = Math.abs(val);
    if (a >= 1_000_000_000) return sig4(val / 1_000_000_000) + 'B';
    if (a >= 1_000_000) return sig4(val / 1_000_000) + 'M';
    if (a >= 1_000) return sig4(val / 1_000) + 'K';
    // Below the compaction threshold render the tenant's exact precision, so
    // small BHD figures read 12.345 and small EGP figures read 12.35.
    return fixed(val, decimals);
};

/**
 * Compact money with its currency attached — for chart axes, tooltips and
 * KPI tiles. `sym` defaults to the tenant currency; when it is unknown the
 * number is returned unlabelled rather than mislabelled.
 */
export const formatCompactCurrency = (val, sym = DEFAULT_CCY, decimals) => {
    const d = resolveDecimals(decimals, sym === DEFAULT_CCY ? sym : undefined);
    const n = Number(val) || 0;
    const body = compactParts(n, d);
    if (!sym) {
        warnOnce('ccy:none', 'Money rendered without a currency code — tenant currency is not set yet.');
        return body;
    }
    return sym + ' ' + body;
};

/**
 * Format a percentage value.
 */
export const formatPercent = (val, decimals = 1) =>
    `${Number(val || 0).toFixed(decimals)}%`;

/**
 * Dashboard formatter factory — creates formatters using tenant currency symbol.
 * Shared across all dashboard/analytics pages to avoid duplicating this logic.
 *
 * Compact tiers keep ~4 significant digits (>= 1B → "1.246B", >= 1M →
 * "987.7M", >= 1K → "45.23K"); below 1K the tenant's exact decimals are used.
 * (B tier added for the CEO dashboard — large books cross into billions.)
 *
 * Usage:
 *   const { currencySymbol, currencyDecimals } = useAuth();
 *   const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals),
 *                       [currencySymbol, currencyDecimals]);
 *   fmt.currency(1250000000) → "AED 1.25B"     (compact, for tiles/axes)
 *   fmt.currency(1234.56)    → "BHD 1.235K"
 *   fmt.money(1234.56)       → "BHD 1,234.560" (exact, for tables/exports)
 *   fmt.msf(12.3456)         → "BHD 12.3456"
 *   fmt.amount(1234.56)      → "1.235K"        (compact, currency in the header)
 *   fmt.number(1500000)      → "1.5M"          (counts — never money)
 *   fmt.growth(-3.2)         → "-3.2%"
 *   fmt.date('2025-09-15')   → "Sep 15"
 *
 * `decimals` defaults to the tenant precision pushed in by AuthContext, so
 * existing single-argument call sites keep working and become BHD-correct.
 */
export const createFmt = (sym = DEFAULT_CCY, decimals) => ({
    /** Compact money with currency — tiles, axes, tooltips. */
    currency: (val) => formatCompactCurrency(val, sym, decimals),
    /** Exact money with currency at the tenant's precision — tables, exports. */
    money: (val) => (sym
        ? sym + ' ' + fixed(Number(val) || 0, resolveDecimals(decimals, sym === DEFAULT_CCY ? sym : undefined))
        : fixed(Number(val) || 0, resolveDecimals(decimals, undefined))),
    /** MSF at reconciliation precision, labelled with the currency. */
    msf: (val) => formatMsf(val, sym),
    /** Compact money WITHOUT the currency label — for tables that state the
     *  currency once in the column header instead of on every cell. */
    amount: (val) => compactParts(Number(val) || 0,
        resolveDecimals(decimals, sym === DEFAULT_CCY ? sym : undefined)),
    /** Raw precision digits in use — handy for CSV builders. */
    decimals: () => resolveDecimals(decimals, sym === DEFAULT_CCY ? sym : undefined),
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
