import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { RefreshCw, Download, Calendar, ArrowRight, ChevronRight, Loader2, Columns3, Info } from 'lucide-react';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import { cachedGet } from '../../api/apiCache';
import api from '../../api/axios';

/* ════════════════════════════════════════════════════════════════════
   Finance Summary — the acquiring P&L, read to the fils.

   THE QUESTION THIS PAGE NOW ANSWERS
   ----------------------------------
   It used to answer "how much volume, split three ways". With interchange
   and scheme fee alongside MSF it answers the question finance actually
   opens it for: of every dirham we billed, how much did the schemes take
   and how much did we keep.

   That reframing is the design. The TAKE RAIL at the top is the period's
   MSF drawn once at page width and cut into its three real parts —
   interchange, scheme fee, net margin — at their true proportions. Every
   row in the table below is a slice of that rail, and each row carries
   the same three colours as a hairline under its label, so a month where
   interchange ran hot is visible while scrolling rather than only after
   arithmetic.

   The rail is the one bold element. Everything below it stays a dense,
   mono, tabular report — no chips, no icons, no sparklines per row.

   NUMBERS ARE UNCHANGED. Count / volume / MSF / Volume % come from the
   same pivot they always did (sum_daily_insight + sum_monthly_insight).
   The fee columns are an additive overlay from sum_daily_full, the only
   daily summary carrying the fee stack. Margin is netted against THAT
   table's own MSF (fee_basis_msf) so all three terms share one source
   and can never fail to add up; when the two MSF figures disagree
   materially the rail says so rather than quietly showing a wrong margin.

   COMPONENTS ARE AT MODULE SCOPE ON PURPOSE. Declaring DataRow inside
   the page body (as this file used to) gives it a new function identity
   on every render, so React treats it as a different component type and
   throws away every row's DOM each time state changes — on a report that
   can hold hundreds of rows across three drill-down levels that is the
   difference between an expander feeling instant and feeling stuck.
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v) || 0);

/* Fee-stack identity — one colour per component, everywhere on the page.
   These three tokens have been in the theme since the fee engine landed
   and this is the first screen to use them. */
const MIX = {
    interchange: 'var(--mix-interchange)',
    scheme: 'var(--mix-scheme)',
    pg: 'var(--mix-pg)',
    margin: 'var(--mix-margin)',
};

/* Net margin = the settlement summary's own MSF less all three costs.
   The same formula as the CEO Volume & Revenue screen, so the two agree. */
const netOf = (r) => num(r.fee_basis_msf) - num(r.total_ic) - num(r.total_sf) - num(r.total_pg);

/* Bucket identity for the three column groups. A 3px underline on the
   group header, not a pastel background wash — every other table in the
   app has a navy header and three unrelated pastels fought that. */
const BUCKETS = {
    domDebit: 'var(--cat-1)',
    domCredit: 'var(--cat-3)',
    intl: 'var(--cat-2)',
};

/* Local YYYY-MM-DD. toISOString() shifts by the browser's UTC offset, which
   for any timezone ahead of UTC turns "1st of the month" into the last day
   of the PREVIOUS month — so the day drill-down requested the wrong range. */
const ymd = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

const PERIODS = [
    { key: 'TODAY', label: 'Today' },
    { key: 'MONTH', label: 'This month' },
    { key: 'LAST_MONTH', label: 'Last month' },
    { key: 'YEAR', label: 'This year' },
    { key: 'PY', label: 'Last year' },
];

/* The resolved window for a preset, mirroring the server's own resolution
   in FinanceController. Shown as a caption so nobody exports a report
   without knowing exactly which days are in it. */
const resolveRange = (period, custom) => {
    const now = new Date();
    const y = now.getFullYear();
    const first = (yy, mm) => new Date(yy, mm, 1);
    const last = (yy, mm) => new Date(yy, mm + 1, 0);
    switch (period) {
        case 'TODAY': return [now, now];
        case 'MONTH': return [first(y, now.getMonth()), now];
        case 'LAST_MONTH': return [first(y, now.getMonth() - 1), last(y, now.getMonth() - 1)];
        case 'YEAR': return [first(y, 0), now];
        case 'PY': return [first(y - 1, 0), new Date(y - 1, 11, 31)];
        case 'CUSTOM':
            if (!custom.start || !custom.end) return null;
            return [new Date(custom.start + 'T00:00:00'), new Date(custom.end + 'T00:00:00')];
        default: return null;
    }
};

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const captionDate = (d) => `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;

const formatCount = (val) => new Intl.NumberFormat('en-US').format(num(val));

/* Volume share — bucket volume over the row's own total volume. The three
   bucket shares sum to 100% by construction of the server-side partition. */
const formatPct = (vol, totalVol) => (!num(totalVol) ? '—' : ((num(vol) / num(totalVol)) * 100).toFixed(2) + '%');

/* Take rate — MSF as a percentage of volume. Three decimals: acquiring
   margins move in basis points and two decimals hides the movement. */
const takeRate = (msf, vol) => (!num(vol) ? '—' : ((num(msf) / num(vol)) * 100).toFixed(3) + '%');

const numCell = (extra = {}) => ({
    padding: '9px 10px', textAlign: 'right', whiteSpace: 'nowrap',
    fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
    ...extra,
});
const GROUP_EDGE = { borderRight: '1px solid var(--border)' };

/* ── Take-rail micro-bar ──────────────────────────────────────────────
   The page's signature echoed at row scale: three segments in the
   fee-stack colours under each row's label. Rendered only where the
   overlay actually returned data, so an unbuilt period shows an empty
   gutter rather than a misleading full-width bar. */
const RowRail = ({ row }) => {
    const basis = num(row.fee_basis_msf);
    if (!row.fees_available || basis <= 0) return <div style={{ height: 3 }} />;
    const ic = num(row.total_ic);
    const sf = num(row.total_sf);
    const pg = num(row.total_pg);
    const margin = Math.max(0, basis - ic - sf - pg);
    const tot = ic + sf + pg + margin || 1;
    const seg = (w, c) => <div style={{ width: `${(w / tot) * 100}%`, background: c }} />;
    return (
        <div aria-hidden="true" style={{
            height: 3, display: 'flex', borderRadius: 2, overflow: 'hidden', background: 'var(--border)',
        }}>
            {seg(ic, MIX.interchange)}{seg(sf, MIX.scheme)}{seg(pg, MIX.pg)}{seg(margin, MIX.margin)}
        </div>
    );
};

/* ── One report row, at any of the three drill-down levels ──────────── */
const DataRow = ({ row, isExpanded, onClick, level = 1, feeDetail, fmt }) => {
    const bg = isExpanded ? 'var(--wash)' : (level === 1 ? 'var(--table-row)' : 'var(--table-row-alt)');
    const padLeft = level === 1 ? 12 : level === 2 ? 30 : 52;

    return (
        <tr
            onClick={onClick}
            style={{ cursor: onClick ? 'pointer' : 'default', background: bg }}
            onMouseEnter={e => { if (!isExpanded) e.currentTarget.style.background = 'var(--bg-hover)'; }}
            onMouseLeave={e => { if (!isExpanded) e.currentTarget.style.background = bg; }}
        >
            <td style={{
                position: 'sticky', left: 0, zIndex: 2, background: 'inherit',
                borderRight: '1px solid var(--border)', borderBottom: '1px solid var(--border-light)',
                padding: `7px 12px 7px ${padLeft}px`, minWidth: 240,
            }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    {onClick && (
                        <ChevronRight
                            size={13}
                            style={{
                                color: 'var(--text-muted)', flexShrink: 0,
                                transform: isExpanded ? 'rotate(90deg)' : 'none',
                                transition: 'transform 180ms ease',
                            }}
                        />
                    )}
                    <span style={{
                        fontWeight: level === 1 ? 650 : 500,
                        fontSize: level === 3 ? 11.5 : 12.5,
                        color: level === 3 ? 'var(--text-secondary)' : 'var(--text)',
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                    }}>{row.month_label}</span>
                </div>
                <div style={{ marginTop: 5, marginLeft: onClick ? 19 : 0 }}><RowRail row={row} /></div>
            </td>

            {/* Local debit & prepaid */}
            <td style={numCell({ color: 'var(--text-muted)' })}>{formatCount(row.dom_debit_cnt)}</td>
            <td style={numCell({ fontWeight: 550 })}>{fmt.currency(row.dom_debit_vol)}</td>
            <td style={numCell({ color: 'var(--text-secondary)' })}>{fmt.msf(row.dom_debit_msf)}</td>
            {feeDetail && <td style={numCell({ color: MIX.interchange })}>{fmt.msf(row.dom_debit_ic)}</td>}
            {feeDetail && <td style={numCell({ color: MIX.scheme })}>{fmt.msf(row.dom_debit_sf)}</td>}
            <td style={numCell({ color: 'var(--text-muted)', ...GROUP_EDGE })}>{formatPct(row.dom_debit_vol, row.total_vol)}</td>

            {/* Local credit */}
            <td style={numCell({ color: 'var(--text-muted)' })}>{formatCount(row.dom_credit_cnt)}</td>
            <td style={numCell({ fontWeight: 550 })}>{fmt.currency(row.dom_credit_vol)}</td>
            <td style={numCell({ color: 'var(--text-secondary)' })}>{fmt.msf(row.dom_credit_msf)}</td>
            {feeDetail && <td style={numCell({ color: MIX.interchange })}>{fmt.msf(row.dom_credit_ic)}</td>}
            {feeDetail && <td style={numCell({ color: MIX.scheme })}>{fmt.msf(row.dom_credit_sf)}</td>}
            <td style={numCell({ color: 'var(--text-muted)', ...GROUP_EDGE })}>{formatPct(row.dom_credit_vol, row.total_vol)}</td>

            {/* International */}
            <td style={numCell({ color: 'var(--text-muted)' })}>{formatCount(row.int_cnt)}</td>
            <td style={numCell({ fontWeight: 550 })}>{fmt.currency(row.int_vol)}</td>
            <td style={numCell({ color: 'var(--text-secondary)' })}>{fmt.msf(row.int_msf)}</td>
            {feeDetail && <td style={numCell({ color: MIX.interchange })}>{fmt.msf(row.int_ic)}</td>}
            {feeDetail && <td style={numCell({ color: MIX.scheme })}>{fmt.msf(row.int_sf)}</td>}
            <td style={numCell({ color: 'var(--text-muted)' })}>{formatPct(row.int_vol, row.total_vol)}</td>
            <td style={numCell({ color: 'var(--text-muted)', ...GROUP_EDGE })}>{fmt.currency(row.int_optin)}</td>

            {/* Total + fee stack */}
            <td style={numCell({ fontWeight: 700 })}>{fmt.currency(row.total_vol)}</td>
            <td style={numCell({ fontWeight: 600, color: 'var(--text-secondary)' })}>{fmt.msf(row.total_msf)}</td>
            <td style={numCell({ color: MIX.interchange })}>{row.fees_available ? fmt.msf(row.total_ic) : '—'}</td>
            <td style={numCell({ color: MIX.scheme })}>{row.fees_available ? fmt.msf(row.total_sf) : '—'}</td>
            <td style={numCell({ color: MIX.pg })}>{row.fees_available ? fmt.msf(row.total_pg) : '—'}</td>
            <td style={numCell({ color: MIX.margin, fontWeight: 650 })}>
                {row.fees_available ? fmt.msf(netOf(row)) : '—'}
            </td>
            <td style={numCell({ color: 'var(--text-muted)' })}>{takeRate(row.total_msf, row.total_vol)}</td>
        </tr>
    );
};

/* ── The take rail ────────────────────────────────────────────────────
   The period's MSF at page width, cut into interchange, scheme fee and
   net margin at their true proportions. */
const TakeRail = ({ totals, fmt, feesAvailable, netMargin, msfDrift, hasRows }) => {
    const basis = num(totals.fee_basis_msf);
    const ic = num(totals.total_ic);
    const sf = num(totals.total_sf);
    const pg = num(totals.total_pg);
    const margin = Math.max(0, netMargin);
    const denom = ic + sf + pg + margin || 1;
    const share = (v) => (v / denom) * 100;

    const legs = [
        { key: 'ic', label: 'Interchange', help: 'Paid to the issuing bank', value: ic, color: MIX.interchange },
        { key: 'sf', label: 'Scheme fee', help: 'Paid to the card scheme', value: sf, color: MIX.scheme },
        { key: 'pg', label: 'PG fee', help: 'Paid to the payment gateway (e-com)', value: pg, color: MIX.pg },
        { key: 'ma', label: 'Net margin', help: 'Kept by the bank', value: margin, color: MIX.margin },
    ];

    return (
        <section
            className="dx-card"
            style={{ padding: '18px 20px 16px', marginBottom: 16 }}
            aria-label="Fee stack for the selected period"
        >
            <div style={{
                display: 'flex', flexWrap: 'wrap', alignItems: 'baseline',
                justifyContent: 'space-between', gap: 16, marginBottom: 14,
            }}>
                <div>
                    <div className="section-title">Where the merchant service fee went</div>
                    <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginTop: 4, flexWrap: 'wrap' }}>
                        <span className="num" style={{ fontSize: 26, fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em' }}>
                            {fmt.currency(totals.total_msf)}
                        </span>
                        <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                            MSF billed on <span className="num">{fmt.currency(totals.total_vol)}</span> of volume
                        </span>
                    </div>
                </div>
                <div style={{ textAlign: 'right' }}>
                    <div className="section-title">Take rate</div>
                    <div className="num" style={{ fontSize: 22, fontWeight: 700, color: 'var(--text)', marginTop: 4 }}>
                        {takeRate(totals.total_msf, totals.total_vol)}
                    </div>
                </div>
            </div>

            {feesAvailable ? (
                <>
                    <div style={{
                        display: 'flex', height: 26, borderRadius: 'var(--radius-sm)',
                        overflow: 'hidden', background: 'var(--bg-subtle)', border: '1px solid var(--border)',
                    }}>
                        {legs.map(l => (
                            <div
                                key={l.key}
                                title={`${l.label} — ${fmt.msf(l.value)} (${share(l.value).toFixed(1)}% of MSF)`}
                                style={{
                                    width: `${share(l.value)}%`, background: l.color,
                                    transition: 'width 420ms cubic-bezier(0.22,1,0.36,1)',
                                }}
                            />
                        ))}
                    </div>

                    <div style={{
                        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                        gap: 14, marginTop: 14,
                    }}>
                        {legs.map(l => (
                            <div key={l.key} style={{ borderTop: `2px solid ${l.color}`, paddingTop: 8 }}>
                                <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 8 }}>
                                    <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text)' }}>{l.label}</span>
                                    <span className="num" style={{ fontSize: 12, color: l.color, fontWeight: 650 }}>
                                        {share(l.value).toFixed(1)}%
                                    </span>
                                </div>
                                <div className="num" style={{ fontSize: 15, fontWeight: 650, color: 'var(--text)', marginTop: 3 }}>
                                    {fmt.currency(l.value)}
                                </div>
                                <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>{l.help}</div>
                            </div>
                        ))}
                    </div>

                    {msfDrift > 0.005 && (
                        <p style={{
                            display: 'flex', gap: 8, alignItems: 'flex-start',
                            margin: '14px 0 0', fontSize: 11.5, color: 'var(--warning-text)',
                        }}>
                            <Info size={14} style={{ flexShrink: 0, marginTop: 1 }} />
                            <span>
                                The fee stack sums to <span className="num">{fmt.msf(basis)}</span> of MSF, which
                                differs from the <span className="num">{fmt.msf(totals.total_msf)}</span> in the
                                Total column by {(msfDrift * 100).toFixed(1)}%. The two figures come from different
                                summary tables — rebuild the summaries for this period to reconcile them.
                            </span>
                        </p>
                    )}
                </>
            ) : (
                <p style={{
                    display: 'flex', gap: 8, alignItems: 'flex-start',
                    fontSize: 12, color: 'var(--text-muted)', margin: 0,
                }}>
                    <Info size={14} style={{ flexShrink: 0, marginTop: 1 }} />
                    <span>
                        {!hasRows
                            ? 'Pick a period with transactions to see the fee stack.'
                            : 'Interchange, scheme fee and PG fee have not been built for this period yet. Volume and MSF below are complete; rebuild the daily summaries to fill in the fee columns.'}
                    </span>
                </p>
            )}
        </section>
    );
};

/* ── Header cells ─────────────────────────────────────────────────── */
const GroupTh = ({ label, span, color, title }) => (
    <th
        colSpan={span}
        title={title}
        style={{
            padding: '8px 10px', textAlign: 'left',
            fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase',
            color: 'var(--table-head-text)',
            borderBottom: `3px solid ${color}`,
            borderRight: '1px solid rgba(255,255,255,0.14)',
        }}
    >{label}</th>
);

const SubTh = ({ label, edge = false }) => (
    <th style={{
        padding: '7px 10px', textAlign: 'right', whiteSpace: 'nowrap',
        fontSize: 10.5, fontWeight: 600, letterSpacing: '0.03em',
        color: 'var(--table-head-muted)',
        borderRight: edge ? '1px solid rgba(255,255,255,0.14)' : 'none',
    }}>{label}</th>
);

const FootTd = ({ children, style: extra }) => (
    <td style={{
        padding: '11px 10px', textAlign: 'right', whiteSpace: 'nowrap',
        fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
        borderTop: '2px solid var(--primary)', fontWeight: 700,
        ...extra,
    }}>{children}</td>
);

const chipStyle = (active) => ({
    padding: '6px 13px', borderRadius: 'var(--radius-chip)', fontSize: 12, fontWeight: 600,
    border: 'none', cursor: 'pointer', whiteSpace: 'nowrap',
    background: active ? 'var(--bg-card)' : 'transparent',
    color: active ? 'var(--text)' : 'var(--text-secondary)',
    boxShadow: active ? 'var(--shadow-xs)' : 'none',
    transition: 'background 160ms ease, color 160ms ease',
});

const ICON_BTN = {
    display: 'flex', alignItems: 'center', gap: 7,
    padding: '8px 12px', borderRadius: 'var(--radius-md)', fontSize: 12.5, fontWeight: 600,
    background: 'var(--bg-subtle)', color: 'var(--text-secondary)',
    border: '1px solid var(--border)', cursor: 'pointer',
};

const DATE_INPUT = {
    padding: 8, borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)',
    background: 'var(--bg-subtle)', color: 'var(--text)',
};

/* Keys summed for the TOTAL row and the take rail. */
const SUM_KEYS = [
    'dom_debit_cnt', 'dom_debit_vol', 'dom_debit_msf', 'dom_debit_ic', 'dom_debit_sf',
    'dom_credit_cnt', 'dom_credit_vol', 'dom_credit_msf', 'dom_credit_ic', 'dom_credit_sf',
    'int_cnt', 'int_vol', 'int_msf', 'int_optin', 'int_ic', 'int_sf',
    'total_vol', 'total_msf', 'total_ic', 'total_sf', 'total_pg', 'fee_basis_msf',
];

const FinanceSummary = () => {
    const { currencySymbol, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);

    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    // Every fetch used to `catch { console.error }`, so a 500/403/timeout looked
    // EXACTLY like a genuinely empty period ("No data for selected period").
    // That is why an erroring report was indistinguishable from an empty one.
    const [error, setError] = useState(null);
    // Default to YEAR rather than MONTH — the current month is often empty when
    // transaction data lags real-time (e.g. it's May but data ends in April). YEAR
    // is more likely to render something useful on first load. User can still pick
    // TODAY/MONTH/PY/CUSTOM via the period chips.
    const [period, setPeriod] = useState('YEAR');
    const [customRange, setCustomRange] = useState({ start: '', end: '' });
    const [showCustomPicker, setShowCustomPicker] = useState(false);
    // Bumped by "Apply range" so a custom range can be re-applied without the
    // period value itself changing.
    const [customApplied, setCustomApplied] = useState(0);
    // Per-bucket interchange / scheme columns. Off by default: the fee stack is
    // always visible at Total level, and six more columns of it per bucket is
    // detail you ask for, not detail you are given.
    const [feeDetail, setFeeDetail] = useState(false);

    // Drill-down state
    const [expandedMonth, setExpandedMonth] = useState(null);
    const [expandedDate, setExpandedDate] = useState(null);
    const [dailyData, setDailyData] = useState({});
    const [merchantData, setMerchantData] = useState({});
    const [loadingDaily, setLoadingDaily] = useState(false);
    const [loadingMerchants, setLoadingMerchants] = useState(false);

    // Request generation. Switching period twice quickly used to leave whichever
    // response landed last on screen, which was not necessarily the one asked
    // for. Every fetch stamps its generation and a stale response is dropped.
    const gen = useRef(0);

    /** Best-effort message out of an axios error — the API returns {error: "..."}. */
    const errMsg = (e, fallback) =>
        e?.response?.data?.error || e?.response?.statusText || e?.message || fallback;

    const fetchData = useCallback(async ({ force = false } = {}) => {
        if (period === 'CUSTOM' && (!customRange.start || !customRange.end)) return;
        const my = ++gen.current;
        setLoading(true);
        setError(null);
        setExpandedMonth(null);
        setExpandedDate(null);
        // Drop the drill-down caches too. They are keyed by month/date only, so
        // after a tenant switch (or a period change) the previously loaded rows
        // would be re-displayed under the new context — showing another bank's
        // daily and merchant figures.
        setDailyData({});
        setMerchantData({});
        try {
            let query = `period=${period}`;
            if (period === 'CUSTOM') {
                query += `&startDate=${customRange.start}&endDate=${customRange.end}`;
            }
            const url = `/finance/summary?${query}`;
            // Served from the client cache when the same range is re-opened within
            // the TTL, so navigating away and back is instant. Refresh forces a
            // real round-trip. The server caches the aggregation as well, so even
            // a miss here rarely reaches Postgres.
            const res = force ? await api.get(url) : await cachedGet(url, { ttlMs: 120000 });
            if (my !== gen.current) return;       // superseded — drop it
            setData(Array.isArray(res.data) ? res.data : []);
        } catch (e) {
            if (my !== gen.current) return;
            console.error(e);
            setData([]);
            setError(errMsg(e, 'Could not load the finance summary.'));
        } finally {
            if (my === gen.current) setLoading(false);
        }
    }, [period, customRange.start, customRange.end]);

    useEffect(() => {
        fetchData();
        // customApplied re-runs the effect when the same CUSTOM range is re-applied.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [period, tenantVersion, customApplied]);

    const fetchDailyData = async (monthLabel, avgDate) => {
        setLoadingDaily(true);
        try {
            const dateObj = new Date(avgDate);
            const startStr = ymd(new Date(dateObj.getFullYear(), dateObj.getMonth(), 1));
            const endStr = ymd(new Date(dateObj.getFullYear(), dateObj.getMonth() + 1, 0));
            const res = await api.get(`/finance/summary?period=CUSTOM&groupBy=DAY&startDate=${startStr}&endDate=${endStr}`);
            setDailyData(prev => ({ ...prev, [monthLabel]: Array.isArray(res.data) ? res.data : [] }));
        } catch (e) {
            console.error(e);
            // Mark the drill-down as failed rather than leaving it indistinguishable
            // from "this month genuinely has no daily rows".
            setDailyData(prev => ({ ...prev, [monthLabel]: { error: errMsg(e, 'Could not load daily detail.') } }));
        } finally { setLoadingDaily(false); }
    };

    const fetchMerchantData = async (dateStr) => {
        setLoadingMerchants(true);
        try {
            const res = await api.get(`/finance/summary?period=CUSTOM&groupBy=MERCHANT&startDate=${dateStr}&endDate=${dateStr}`);
            setMerchantData(prev => ({ ...prev, [dateStr]: Array.isArray(res.data) ? res.data : [] }));
        } catch (e) {
            console.error(e);
            setMerchantData(prev => ({ ...prev, [dateStr]: { error: errMsg(e, 'Could not load merchant detail.') } }));
        } finally { setLoadingMerchants(false); }
    };

    const toggleMonth = (row) => {
        if (expandedMonth === row.month_label) {
            setExpandedMonth(null);
            setExpandedDate(null);
        } else {
            setExpandedMonth(row.month_label);
            setExpandedDate(null);
            if (!dailyData[row.month_label]) fetchDailyData(row.month_label, row.sort_date);
        }
    };

    const toggleDate = (row) => {
        if (expandedDate === row.sort_date) {
            setExpandedDate(null);
        } else {
            setExpandedDate(row.sort_date);
            // sort_date is YYYY-MM-DD for daily rows
            if (!merchantData[row.sort_date]) fetchMerchantData(row.sort_date);
        }
    };

    // ── Aggregates ────────────────────────────────────────────────────
    const totals = useMemo(() => data.reduce((acc, row) => {
        SUM_KEYS.forEach(k => { acc[k] = (acc[k] || 0) + num(row[k]); });
        return acc;
    }, {}), [data]);

    // Fee data is built by a different summary table than the volume figures. A
    // period the warehouse has volume for but no fee rows yet must say so rather
    // than render a zero margin that looks like a catastrophic month.
    const feesAvailable = data.length > 0 && data.some(r => r.fees_available);
    const netMargin = netOf(totals);
    // The overlay's MSF vs the pivot's MSF. Same transactions, different
    // aggregation tables; a material gap means one of them is stale.
    const msfDrift = num(totals.total_msf)
        ? Math.abs(num(totals.fee_basis_msf) - num(totals.total_msf)) / Math.abs(num(totals.total_msf))
        : 0;

    const resolved = resolveRange(period, customRange);
    const rangeCaption = resolved
        ? `${captionDate(resolved[0])} — ${captionDate(resolved[1])}`
        : 'Pick a start and end date';

    // ── Export ────────────────────────────────────────────────────────
    // The Export button previously had no onClick at all — it rendered and did
    // nothing. Emits the month rows currently on screen, in the same layout as
    // the table including the fee stack.
    const handleExport = () => {
        if (!data.length) return;
        const cell = (v) => {
            const s = v === null || v === undefined ? '' : String(v);
            const safe = /^[=+\-@\t\r]/.test(s) ? `'${s}` : s;   // neutralise CSV formula injection
            return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
        };
        const header = [
            'Period',
            'Local Debit & Prepaid Count', 'Local Debit & Prepaid Volume', 'Local Debit & Prepaid MSF',
            'Local Debit & Prepaid Interchange', 'Local Debit & Prepaid Scheme Fee', 'Local Debit & Prepaid Volume %',
            'Local Credit Count', 'Local Credit Volume', 'Local Credit MSF',
            'Local Credit Interchange', 'Local Credit Scheme Fee', 'Local Credit Volume %',
            'International Count', 'International Volume', 'International MSF',
            'International Interchange', 'International Scheme Fee', 'International Volume %', 'International Opt-in Volume',
            'Total Volume', 'Total MSF', 'Total Interchange', 'Total Scheme Fee', 'Total PG Fee', 'Net Margin', 'Take Rate %',
        ];
        const pct = (v, t) => (!num(t) ? '0.00' : ((num(v) / num(t)) * 100).toFixed(2));
        const line = (label, r) => [
            label,
            num(r.dom_debit_cnt), num(r.dom_debit_vol), num(r.dom_debit_msf), num(r.dom_debit_ic), num(r.dom_debit_sf), pct(r.dom_debit_vol, r.total_vol),
            num(r.dom_credit_cnt), num(r.dom_credit_vol), num(r.dom_credit_msf), num(r.dom_credit_ic), num(r.dom_credit_sf), pct(r.dom_credit_vol, r.total_vol),
            num(r.int_cnt), num(r.int_vol), num(r.int_msf), num(r.int_ic), num(r.int_sf), pct(r.int_vol, r.total_vol), num(r.int_optin),
            num(r.total_vol), num(r.total_msf), num(r.total_ic), num(r.total_sf), num(r.total_pg),
            netOf(r),
            pct(r.total_msf, r.total_vol),
        ].map(cell).join(',');

        const lines = [header.map(cell).join(',')];
        for (const r of data) lines.push(line(r.month_label, r));
        lines.push(line('TOTAL', totals));

        // Leading BOM so Excel reads it as UTF-8.
        const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `finance-summary-${period.toLowerCase()}-${ymd(new Date())}.csv`;
        document.body.appendChild(a); a.click(); a.remove();
        URL.revokeObjectURL(url);
    };

    // Only signal intent — the effect above does the fetching. Calling fetchData()
    // directly here read `period` from a stale closure, so switching (say) YEAR →
    // CUSTOM fired a request for YEAR, and the effect then fired a second one for
    // CUSTOM; whichever landed last won, so the table could show the wrong range.
    const handleApplyCustom = () => {
        setPeriod('CUSTOM');
        setCustomApplied(n => n + 1);   // re-runs the effect even when already CUSTOM
        setShowCustomPicker(false);
    };

    // Drill-down slots hold either an array of rows or {error} — normalise both.
    const asRows = (v) => (Array.isArray(v) ? v : []);
    const errOf = (v) => (v && !Array.isArray(v) && v.error) ? v.error : null;
    const isLoaded = (v) => v !== undefined;

    const COLS = feeDetail ? 26 : 20;
    const rowProps = { feeDetail, fmt };

    return (
        <div
            className="page-container page-transition"
            style={{
                padding: 'var(--space-page)', color: 'var(--text)',
                height: 'var(--vh100, 100vh)', display: 'flex', flexDirection: 'column',
            }}
        >
            {/* ── Masthead ────────────────────────────────────────────── */}
            <header style={{
                display: 'flex', flexWrap: 'wrap', gap: 16,
                justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: 16,
            }}>
                <div>
                    <h1 style={{ fontSize: 21, fontWeight: 800, color: 'var(--text)', letterSpacing: '-0.015em', margin: 0 }}>
                        Finance Summary
                    </h1>
                    <p style={{ color: 'var(--text-muted)', fontSize: 12.5, margin: '4px 0 0' }}>
                        Volume, MSF and the fee stack by card type and destination
                        <span aria-hidden="true" style={{ margin: '0 8px', color: 'var(--border)' }}>|</span>
                        <span className="num" style={{ color: 'var(--text-secondary)' }}>{rangeCaption}</span>
                    </p>
                </div>

                <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', position: 'relative' }}>
                    <div style={{
                        background: 'var(--bg-subtle)', padding: 4, borderRadius: 'var(--radius-md)',
                        display: 'flex', gap: 3, border: '1px solid var(--border)',
                    }}>
                        {PERIODS.map(p => (
                            <button
                                key={p.key}
                                onClick={() => { setPeriod(p.key); setShowCustomPicker(false); }}
                                style={chipStyle(period === p.key)}
                                aria-pressed={period === p.key}
                            >{p.label}</button>
                        ))}
                        <button
                            onClick={() => setShowCustomPicker(v => !v)}
                            style={{ ...chipStyle(period === 'CUSTOM'), display: 'flex', alignItems: 'center', gap: 5 }}
                            aria-expanded={showCustomPicker}
                        >
                            Custom <Calendar size={12} />
                        </button>
                    </div>

                    <button
                        onClick={() => setFeeDetail(v => !v)}
                        style={{
                            ...ICON_BTN,
                            background: feeDetail ? 'var(--wash)' : 'var(--bg-subtle)',
                            color: feeDetail ? 'var(--primary)' : 'var(--text-secondary)',
                            borderColor: feeDetail ? 'var(--primary)' : 'var(--border)',
                        }}
                        aria-pressed={feeDetail}
                        title="Show interchange and scheme fee for each card-type group, not just the total"
                    >
                        <Columns3 size={14} /> Fee detail
                    </button>

                    <button
                        onClick={() => fetchData({ force: true })}
                        style={ICON_BTN}
                        title="Refetch, bypassing the cache"
                        aria-label="Refresh"
                    >
                        <RefreshCw size={14} className={loading ? 'spin' : undefined} />
                    </button>

                    <button
                        onClick={handleExport}
                        disabled={loading || data.length === 0}
                        title={data.length === 0 ? 'Nothing to export yet' : 'Download these rows as CSV'}
                        style={{
                            display: 'flex', alignItems: 'center', gap: 8,
                            padding: '8px 16px', borderRadius: 'var(--radius-md)', fontSize: 12.5, fontWeight: 600,
                            border: 'none', color: '#FFF',
                            background: data.length === 0 ? 'var(--text-disabled)' : 'var(--grad-primary)',
                            cursor: data.length === 0 ? 'not-allowed' : 'pointer',
                        }}
                    >
                        <Download size={14} /> Export
                    </button>

                    {/* Anchored to the control cluster rather than to magic page
                        coordinates, so it stays put at every viewport width. */}
                    {showCustomPicker && (
                        <div style={{
                            position: 'absolute', right: 0, top: 'calc(100% + 8px)', zIndex: 50,
                            background: 'var(--bg-card)', padding: 16, borderRadius: 'var(--radius-lg)',
                            boxShadow: 'var(--shadow-pop)', border: '1px solid var(--border)',
                        }}>
                            <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 12 }}>
                                <input
                                    type="date" value={customRange.start} aria-label="Start date" style={DATE_INPUT}
                                    onChange={e => setCustomRange({ ...customRange, start: e.target.value })}
                                />
                                <ArrowRight size={15} style={{ color: 'var(--text-muted)' }} />
                                <input
                                    type="date" value={customRange.end} aria-label="End date" style={DATE_INPUT}
                                    onChange={e => setCustomRange({ ...customRange, end: e.target.value })}
                                />
                            </div>
                            <button
                                onClick={handleApplyCustom}
                                disabled={!customRange.start || !customRange.end}
                                style={{
                                    width: '100%', padding: 9, background: 'var(--grad-primary)', color: '#FFF',
                                    border: 'none', borderRadius: 'var(--radius-sm)', fontWeight: 600, fontSize: 12.5,
                                    cursor: (!customRange.start || !customRange.end) ? 'not-allowed' : 'pointer',
                                    opacity: (!customRange.start || !customRange.end) ? 0.5 : 1,
                                }}
                            >
                                Apply range
                            </button>
                        </div>
                    )}
                </div>
            </header>

            {/* Request-level failure — previously only logged to the console, so a
                broken endpoint was indistinguishable from an empty period. */}
            {error && (
                <div role="alert" style={{
                    marginBottom: 12, padding: '10px 14px', borderRadius: 'var(--radius-md)',
                    background: 'var(--danger-bg)', border: '1px solid var(--danger)', color: 'var(--danger)',
                    fontSize: 12.5, display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12,
                }}>
                    <span><strong>The report did not load.</strong> {error}</span>
                    <button onClick={() => fetchData({ force: true })} style={{
                        padding: '4px 12px', borderRadius: 'var(--radius-sm)', border: '1px solid var(--danger)',
                        background: 'transparent', color: 'var(--danger)', fontWeight: 600, cursor: 'pointer', fontSize: 12,
                    }}>Try again</button>
                </div>
            )}

            <TakeRail
                totals={totals}
                fmt={fmt}
                feesAvailable={feesAvailable}
                netMargin={netMargin}
                msfDrift={msfDrift}
                hasRows={data.length > 0}
            />

            {/* ── Table ───────────────────────────────────────────────── */}
            <div className="dx-card" style={{ flex: 1, minHeight: 220, overflow: 'auto', position: 'relative', padding: 0 }}>
                {/* A refresh keeps the previous rows on screen behind a progress
                    hairline rather than blanking the table — the numbers you were
                    reading stay readable while the new ones arrive. */}
                {loading && data.length > 0 && (
                    <div style={{ position: 'sticky', top: 0, left: 0, zIndex: 30, height: 2, background: 'var(--grad-edge)' }} />
                )}

                <table style={{ minWidth: feeDetail ? 1900 : 1520, width: '100%', borderCollapse: 'separate', borderSpacing: 0 }}>
                    <thead style={{ position: 'sticky', top: 0, zIndex: 20 }}>
                        <tr style={{ background: 'var(--table-head-bg)' }}>
                            <th style={{
                                position: 'sticky', left: 0, zIndex: 25, background: 'var(--table-head-bg)',
                                borderRight: '1px solid rgba(255,255,255,0.14)', minWidth: 240,
                            }} />
                            {/* Three mutually exclusive, exhaustive buckets — every transaction
                                lands in exactly one, so the three Volume % columns sum to 100%:
                                  1. Local (domestic) DEBIT + PREPAID
                                  2. Local (domestic) CREDIT — also the catch-all for domestic
                                     rows whose card_type is unmapped/blank, so the partition
                                     stays exhaustive
                                  3. International — any non-domestic destination */}
                            <GroupTh label="Local debit & prepaid" span={feeDetail ? 6 : 4} color={BUCKETS.domDebit}
                                title="Domestic destination, card type DEBIT or PREPAID" />
                            <GroupTh label="Local credit" span={feeDetail ? 6 : 4} color={BUCKETS.domCredit}
                                title="Domestic destination, card type CREDIT (includes any unmapped domestic card type)" />
                            <GroupTh label="International" span={feeDetail ? 7 : 5} color={BUCKETS.intl}
                                title="Any non-domestic destination, all card types" />
                            <GroupTh label="Total & fee stack" span={7} color="var(--primary-soft)"
                                title="MSF less interchange, scheme fee and PG fee is the margin the bank keeps" />
                        </tr>
                        <tr style={{ background: 'var(--table-head-bg)' }}>
                            <th style={{
                                position: 'sticky', left: 0, zIndex: 25, background: 'var(--table-head-bg)',
                                padding: '7px 12px', textAlign: 'left', fontSize: 10.5, fontWeight: 600,
                                letterSpacing: '0.03em', color: 'var(--table-head-muted)',
                                borderRight: '1px solid rgba(255,255,255,0.14)',
                                borderBottom: '2px solid var(--table-head-edge)',
                            }}>Period</th>

                            <SubTh label="Count" /><SubTh label="Volume" /><SubTh label="MSF" />
                            {feeDetail && <SubTh label="Interchange" />}{feeDetail && <SubTh label="Scheme" />}
                            <SubTh label="Volume %" edge />

                            <SubTh label="Count" /><SubTh label="Volume" /><SubTh label="MSF" />
                            {feeDetail && <SubTh label="Interchange" />}{feeDetail && <SubTh label="Scheme" />}
                            <SubTh label="Volume %" edge />

                            <SubTh label="Count" /><SubTh label="Volume" /><SubTh label="MSF" />
                            {feeDetail && <SubTh label="Interchange" />}{feeDetail && <SubTh label="Scheme" />}
                            <SubTh label="Volume %" /><SubTh label="Opt-in vol" edge />

                            <SubTh label="Volume" /><SubTh label="MSF" />
                            <SubTh label="Interchange" /><SubTh label="Scheme fee" /><SubTh label="PG fee" /><SubTh label="Net margin" /><SubTh label="Take rate" />
                        </tr>
                    </thead>

                    <tbody style={{ fontSize: 12 }}>
                        {loading && data.length === 0 ? (
                            Array.from({ length: 6 }).map((_, i) => (
                                <tr key={i}>
                                    <td colSpan={COLS} style={{ padding: '10px 12px' }}>
                                        <div style={{
                                            height: 14, borderRadius: 6, opacity: 1 - i * 0.12,
                                            background: 'linear-gradient(90deg, var(--bg-subtle) 25%, var(--wash) 50%, var(--bg-subtle) 75%)',
                                            backgroundSize: '400% 100%', animation: 'shimmer 1.8s ease-in-out infinite',
                                        }} />
                                    </td>
                                </tr>
                            ))
                        ) : data.length === 0 ? (
                            <tr><td colSpan={COLS} style={{ padding: 48, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>
                                {error
                                    ? 'The report could not be loaded — see the message above.'
                                    : 'No transactions in this period. Try a wider range.'}
                            </td></tr>
                        ) : (
                            data.map((row) => (
                                <React.Fragment key={row.month_label}>
                                    {/* Level 1: month */}
                                    <DataRow
                                        row={row} level={1} {...rowProps}
                                        isExpanded={expandedMonth === row.month_label}
                                        onClick={() => toggleMonth(row)}
                                    />

                                    {/* Level 2: days */}
                                    {expandedMonth === row.month_label && (
                                        <>
                                            {loadingDaily && (
                                                <tr><td colSpan={COLS} style={{ padding: 14, textAlign: 'center', background: 'var(--bg-subtle)' }}>
                                                    <Loader2 size={15} className="spin" style={{ display: 'inline', color: 'var(--text-muted)' }} />
                                                </td></tr>
                                            )}
                                            {asRows(dailyData[row.month_label]).map(day => (
                                                <React.Fragment key={day.sort_date}>
                                                    <DataRow
                                                        row={day} level={2} {...rowProps}
                                                        isExpanded={expandedDate === day.sort_date}
                                                        onClick={() => toggleDate(day)}
                                                    />

                                                    {/* Level 3: merchants */}
                                                    {expandedDate === day.sort_date && (
                                                        <>
                                                            {loadingMerchants && (
                                                                <tr><td colSpan={COLS} style={{ padding: 14, textAlign: 'center', background: 'var(--bg-subtle)' }}>
                                                                    <Loader2 size={15} className="spin" style={{ display: 'inline', color: 'var(--primary)' }} />
                                                                </td></tr>
                                                            )}
                                                            {asRows(merchantData[day.sort_date]).map(merch => (
                                                                <DataRow key={merch.merchant_id} row={merch} level={3} {...rowProps} isExpanded={false} onClick={null} />
                                                            ))}
                                                            {!loadingMerchants && errOf(merchantData[day.sort_date]) && (
                                                                <tr><td colSpan={COLS} style={{ padding: '8px 8px 8px 52px', background: 'var(--danger-bg)', color: 'var(--danger)', fontSize: 11.5 }}>
                                                                    {errOf(merchantData[day.sort_date])}
                                                                </td></tr>
                                                            )}
                                                            {!loadingMerchants && isLoaded(merchantData[day.sort_date]) && !errOf(merchantData[day.sort_date]) && asRows(merchantData[day.sort_date]).length === 0 && (
                                                                <tr><td colSpan={COLS} style={{ padding: '8px 8px 8px 52px', background: 'var(--bg-subtle)', color: 'var(--text-muted)', fontSize: 11.5 }}>
                                                                    No merchant-level detail stored for this day.
                                                                </td></tr>
                                                            )}
                                                        </>
                                                    )}
                                                </React.Fragment>
                                            ))}
                                            {/* A month row can be served from the monthly pre-aggregate while its
                                                day-level detail lives in a separate daily table. When that daily
                                                data is missing the expander used to open onto nothing at all, which
                                                reads as a broken report — say so instead. */}
                                            {!loadingDaily && errOf(dailyData[row.month_label]) && (
                                                <tr><td colSpan={COLS} style={{ padding: '10px 10px 10px 30px', background: 'var(--danger-bg)', color: 'var(--danger)', fontSize: 11.5 }}>
                                                    {errOf(dailyData[row.month_label])}
                                                </td></tr>
                                            )}
                                            {!loadingDaily && isLoaded(dailyData[row.month_label]) && !errOf(dailyData[row.month_label]) && asRows(dailyData[row.month_label]).length === 0 && (
                                                <tr><td colSpan={COLS} style={{ padding: '10px 10px 10px 30px', background: 'var(--bg-subtle)', color: 'var(--text-muted)', fontSize: 11.5 }}>
                                                    No daily detail available for {row.month_label}. The month total above comes from the monthly summary; daily rows for this period have not been loaded into the warehouse.
                                                </td></tr>
                                            )}
                                        </>
                                    )}
                                </React.Fragment>
                            ))
                        )}
                    </tbody>

                    <tfoot style={{ position: 'sticky', bottom: 0, zIndex: 20 }}>
                        <tr style={{ background: 'var(--wash)' }}>
                            <td style={{
                                position: 'sticky', left: 0, zIndex: 25, background: 'var(--wash)',
                                borderRight: '1px solid var(--border)', borderTop: '2px solid var(--primary)',
                                padding: '11px 12px', fontWeight: 700, fontSize: 12, letterSpacing: '0.04em',
                                textTransform: 'uppercase', color: 'var(--text)',
                            }}>Total</td>

                            <FootTd>{formatCount(totals.dom_debit_cnt)}</FootTd>
                            <FootTd>{fmt.currency(totals.dom_debit_vol)}</FootTd>
                            <FootTd>{fmt.msf(totals.dom_debit_msf)}</FootTd>
                            {feeDetail && <FootTd style={{ color: MIX.interchange }}>{fmt.msf(totals.dom_debit_ic)}</FootTd>}
                            {feeDetail && <FootTd style={{ color: MIX.scheme }}>{fmt.msf(totals.dom_debit_sf)}</FootTd>}
                            <FootTd style={GROUP_EDGE}>{formatPct(totals.dom_debit_vol, totals.total_vol)}</FootTd>

                            <FootTd>{formatCount(totals.dom_credit_cnt)}</FootTd>
                            <FootTd>{fmt.currency(totals.dom_credit_vol)}</FootTd>
                            <FootTd>{fmt.msf(totals.dom_credit_msf)}</FootTd>
                            {feeDetail && <FootTd style={{ color: MIX.interchange }}>{fmt.msf(totals.dom_credit_ic)}</FootTd>}
                            {feeDetail && <FootTd style={{ color: MIX.scheme }}>{fmt.msf(totals.dom_credit_sf)}</FootTd>}
                            <FootTd style={GROUP_EDGE}>{formatPct(totals.dom_credit_vol, totals.total_vol)}</FootTd>

                            <FootTd>{formatCount(totals.int_cnt)}</FootTd>
                            <FootTd>{fmt.currency(totals.int_vol)}</FootTd>
                            <FootTd>{fmt.msf(totals.int_msf)}</FootTd>
                            {feeDetail && <FootTd style={{ color: MIX.interchange }}>{fmt.msf(totals.int_ic)}</FootTd>}
                            {feeDetail && <FootTd style={{ color: MIX.scheme }}>{fmt.msf(totals.int_sf)}</FootTd>}
                            <FootTd>{formatPct(totals.int_vol, totals.total_vol)}</FootTd>
                            <FootTd style={GROUP_EDGE}>{fmt.currency(totals.int_optin)}</FootTd>

                            <FootTd>{fmt.currency(totals.total_vol)}</FootTd>
                            <FootTd>{fmt.msf(totals.total_msf)}</FootTd>
                            <FootTd style={{ color: MIX.interchange }}>{feesAvailable ? fmt.msf(totals.total_ic) : '—'}</FootTd>
                            <FootTd style={{ color: MIX.scheme }}>{feesAvailable ? fmt.msf(totals.total_sf) : '—'}</FootTd>
                            <FootTd style={{ color: MIX.pg }}>{feesAvailable ? fmt.msf(totals.total_pg) : '—'}</FootTd>
                            <FootTd style={{ color: MIX.margin }}>{feesAvailable ? fmt.msf(netMargin) : '—'}</FootTd>
                            <FootTd>{takeRate(totals.total_msf, totals.total_vol)}</FootTd>
                        </tr>
                    </tfoot>
                </table>
            </div>

            <p style={{ margin: '10px 2px 0', fontSize: 11, color: 'var(--text-muted)' }}>
                Rows expand month → day → merchant. Volume, count and MSF come from the transaction
                summaries; interchange, scheme fee and PG (gateway) fee come from the settlement summary,
                and net margin is that summary&rsquo;s MSF less all three fees.
            </p>
        </div>
    );
};

export default FinanceSummary;
