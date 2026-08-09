import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import api from '../api/axios';
import {
    RefreshCw, Search, Download, ChevronLeft, ChevronRight,
    ChevronUp, ChevronDown, CalendarRange, TrendingDown,
    Landmark, Receipt, Percent, Layers, Wallet,
} from 'lucide-react';
import EmptyState from '../components/EmptyState';
import SkeletonLoader from '../components/SkeletonLoader';
import { useAuth } from '../contexts/AuthContext';
import { showToast } from '../contexts/ToastContext';
import { createFmt, formatMsf } from '../utils/formatters';

/* ════════════════════════════════════════════════════════════════════
   CEO Volume & Revenue — MID x SID detail with the full fee stack:
   MID, SID, Name, Count, Volume (settlement), MSF, Interchange, Scheme
   Fee, Net Margin, Net Margin %. Period: MTD / YTD / This Month / pick
   any month. Search, sortable columns, server pagination, CSV export.
   Data: /api/business/ceo-volume-revenue (sum_daily_terminal — summary
   read only, never fact_transaction).

   Reused for the Loss-Making Merchants screen via the `lossOnly` prop
   (adds lossOnly=true -> HAVING net_revenue < 0 server-side). lossOnly
   also rolls the server-side query up to MID (merchant) level instead
   of MID x SID, so a merchant's overall position is evaluated as a
   whole rather than flagging/hiding individual stores independently
   of their siblings under the same MID — the SID column is dropped
   from that view since it has nothing meaningful left to show.

   Visual register: restrained financial instrument — white panels on the
   slate workspace, hairline borders, uppercase micro-labels, tabular
   numerals, no gradients. KPI band summarises the period totals above
   the detail table. Full-bleed width — no max-width cap — so the table
   uses the available workspace on wide monitors instead of being boxed
   in at ~1380px.
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));
const fullNum = (v, sym = '') =>
    (sym ? sym + ' ' : '') + Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 2 });

/* Net margin % is null from the server when the ratio is undefined (zero
   volume). Render that as an em-dash — NEVER as 0.00%, which the colour rules
   below would paint green and pass off as healthy. */
const pct = (v) => (v == null ? '—' : `${Number(v).toFixed(2)}%`);
/* Colour tone for a possibly-null margin: neutral when undefined. */
const pctTone = (v) => (v == null ? 'var(--text-secondary)' : Number(v) >= 0 ? '#059669' : '#dc2626');

const ALL_COLUMNS = [
    { key: 'mid',         label: 'MID',            align: 'left',  sortable: true },
    { key: 'sid',         label: 'SID',            align: 'left',  sortable: false },
    { key: 'name',        label: 'Merchant',       align: 'left',  sortable: true },
    { key: 'txns',        label: 'Count',          align: 'right', sortable: true },
    { key: 'volume',      label: 'Volume',         align: 'right', sortable: true },
    { key: 'msf',         label: 'MSF',            align: 'right', sortable: true },
    { key: 'interchange', label: 'Interchange',    align: 'right', sortable: true },
    { key: 'schemeFee',   label: 'Scheme Fee',     align: 'right', sortable: true },
    { key: 'ecomFee',     label: 'ECOM Fee',       align: 'right', sortable: true },
    { key: 'net',         label: 'Net Margin',     align: 'right', sortable: true },
    // Sortable since 2026-08-05 — on the Loss-Making view this is the ordering
    // that matters most (absolute net margin cannot separate a large merchant
    // losing 0.1% from a small one losing 40%). Server sorts on the ratio, with
    // undefined-ratio rows (zero volume) under NULLS LAST.
    { key: 'margin',      label: 'Net Margin %',   align: 'right', sortable: true },
];
// lossOnly rolls the server-side query up to MID (merchant) level, so the
// SID column has nothing meaningful to show — drop it from that view.
const columnsFor = (lossOnly) => lossOnly ? ALL_COLUMNS.filter(c => c.key !== 'sid') : ALL_COLUMNS;

const PAGE_SIZE = 50;

/* Build the last N month options as {value:'YYYY-MM', label:'Mon YYYY'}.
   The anchor is parsed component-wise, NOT via new Date(anchorISO): a bare
   'YYYY-MM-DD' parses as UTC midnight but getFullYear()/getMonth() read local
   time, so west of UTC the anchor slid back a day and, on the 1st of a month,
   dropped the current month off the list entirely. */
const buildMonthOptions = (anchorISO, n = 12) => {
    const opts = [];
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(anchorISO || '');
    const base = m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date();
    for (let i = 0; i < n; i++) {
        const d = new Date(base.getFullYear(), base.getMonth() - i, 1);
        const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
        const label = d.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
        opts.push({ value, label });
    }
    return opts;
};

/* ── KPI stat tile: uppercase micro-label + tabular-nums value + caption ── */
const StatTile = ({ icon: Icon, label, value, caption, tone, title }) => {
    const valueColor =
        tone === 'danger'  ? '#dc2626' :
        tone === 'success' ? '#059669' : 'var(--text)';
    return (
        <div title={title} style={{ padding: '16px 20px', minWidth: 0 }}>
            <div style={{
                display: 'flex', alignItems: 'center', gap: 6,
                fontSize: 10.5, fontWeight: 600, letterSpacing: '0.08em',
                textTransform: 'uppercase', color: 'var(--text-muted, #94a3b8)',
                whiteSpace: 'nowrap',
            }}>
                <Icon size={12} strokeWidth={2.2} />
                {label}
            </div>
            <div style={{
                marginTop: 7, fontSize: 21, fontWeight: 700, color: valueColor,
                letterSpacing: '-0.01em', fontVariantNumeric: 'tabular-nums',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {value}
            </div>
            {caption && (
                <div style={{ marginTop: 3, fontSize: 11.5, color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                    {caption}
                </div>
            )}
        </div>
    );
};

const CeoVolumeRevenue = ({
    lossOnly = false,
    title = 'Volume & Revenue',
    subtitleSuffix = 'volume & fees in settlement currency',
}) => {
    const { currencySymbol, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);

    // period: 'MTD' | 'YTD' | 'THIS_MONTH' | 'MONTH'
    const [period, setPeriod] = useState('MTD');
    const [month, setMonth] = useState('');           // 'YYYY-MM' when period==='MONTH'
    const [page, setPage] = useState(0);
    const [sort, setSort] = useState(lossOnly ? 'net' : 'volume');
    const [dir, setDir] = useState(lossOnly ? 'asc' : 'desc');  // loss: worst (most negative) first
    const [search, setSearch] = useState('');
    const [query, setQuery] = useState('');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [exporting, setExporting] = useState(false);
    const debounceRef = useRef(null);

    const monthOptions = useMemo(
        () => buildMonthOptions(data?.effectiveDate, 12),
        [data?.effectiveDate]);

    /* the params sent to the API for the current period selection */
    const periodParams = useMemo(() => {
        if (period === 'MONTH' && month) return { month };
        if (period === 'YTD') return { mode: 'YTD' };
        if (period === 'THIS_MONTH') return { mode: 'THIS_MONTH' };
        return { mode: 'MTD' };
    }, [period, month]);

    // lossOnly is a fixed prop (not state), so this only needs to react to it
    // in case a future caller ever toggles it live.
    const visibleColumns = useMemo(() => columnsFor(lossOnly), [lossOnly]);

    useEffect(() => {
        clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(() => { setQuery(search.trim()); setPage(0); }, 350);
        return () => clearTimeout(debounceRef.current);
    }, [search]);

    const load = useCallback(async (signal) => {
        setLoading(true); setError(null);
        try {
            const res = await api.get('/business/ceo-volume-revenue', {
                signal,
                params: {
                    ...periodParams, lossOnly: lossOnly || undefined,
                    page, size: PAGE_SIZE, sort, dir, search: query || undefined,
                },
            });
            setData(res.data);
        } catch (e) {
            // Superseded by a newer request — leave the screen alone. Without this
            // guard a slow YTD response could land AFTER a fast MTD one issued
            // later, leaving MTD highlighted in the toolbar while YTD numbers sat
            // on screen (and relabelling itself from data.from/data.to, so it
            // looked entirely consistent).
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            setError(e?.response?.data?.message || 'Failed to load report');
        } finally {
            setLoading(false);
        }
    }, [periodParams, lossOnly, page, sort, dir, query]);

    useEffect(() => {
        const ac = new AbortController();
        load(ac.signal);
        return () => ac.abort();
    }, [load, tenantVersion]);

    // Every other input resets pagination (period, month, search, sort) — a
    // tenant switch must too. Staying on page 3 while moving to a tenant with
    // fewer loss rows returned an empty page with a non-zero totalRows, which
    // the empty state below used to report as "That's good news" — a false
    // all-clear on a risk screen.
    useEffect(() => { setPage(0); }, [tenantVersion]);

    const onSort = (key) => {
        const col = visibleColumns.find(c => c.key === key);
        if (!col?.sortable) return;
        if (sort === key) setDir(d => (d === 'desc' ? 'asc' : 'desc'));
        else { setSort(key); setDir('desc'); }
        setPage(0);
    };

    const exportCsv = async () => {
        setExporting(true);
        try {
            const base = { ...periodParams, lossOnly: lossOnly || undefined, sort, dir, search: query || undefined };
            // export=true returns the FULL result set in one response. Paging
            // through in 500-row chunks re-ran the grouped aggregate per chunk
            // (twice: rows + totals) and OFFSET re-sorted all skipped rows, so
            // large periods took dozens of sequential round trips to download.
            const res = await api.get('/business/ceo-volume-revenue', { params: { ...base, export: true } });
            const rows = res.data?.rows || [];
            const totalRows = num(res.data?.totalRows);
            const exportTotals = res.data?.totals;
            // Quoting is correct for CSV parsing, but Excel still evaluates a field
            // that begins =, +, - or @ once unquoted, and merchant names come from
            // ingested master data. Prefix an apostrophe to force text.
            const esc = (v) => {
                const s = String(v ?? '');
                return `"${(/^[=+\-@]/.test(s) ? `'${s}` : s).replace(/"/g, '""')}"`;
            };
            // Net margin % is null when undefined (zero volume) — leave the cell
            // empty rather than writing a 0.00 that reads as a real measurement.
            const pctCell = (v) => (v == null ? '' : Number(v).toFixed(2));
            const header = lossOnly
                ? ['MID', 'Merchant', 'Count', 'Volume', 'MSF',
                    'Interchange Fee', 'Scheme Fee', 'ECOM Fee', 'Net Margin', 'Net Margin %']
                : ['MID', 'SID', 'Merchant', 'Count', 'Volume', 'MSF',
                    'Interchange Fee', 'Scheme Fee', 'ECOM Fee', 'Net Margin', 'Net Margin %'];
            const lines = [header.join(',')];
            rows.forEach(r => lines.push([
                esc(r.mid), ...(lossOnly ? [] : [esc(r.sid)]), esc(r.name), num(r.txns),
                num(r.volume).toFixed(2), num(r.msf).toFixed(4),
                num(r.interchange).toFixed(2), num(r.schemeFee).toFixed(2),
                num(r.ecomFee).toFixed(2),
                num(r.netRevenue).toFixed(2), pctCell(r.marginPct),
            ].join(',')));
            // Always append the server's own period-total aggregate (unbounded, matches
            // the on-screen KPI band) as a trailing TOTAL row -- so the file is
            // self-verifying even if pagination is ever capped again, and so a plain
            // SUM() of the Volume column by the row count above it can be sanity-checked
            // against this line.
            if (exportTotals) {
                lines.push([
                    esc('TOTAL'), ...(lossOnly ? [] : [esc('')]), esc(`${totalRows} rows (period total)`),
                    num(exportTotals.txns),
                    num(exportTotals.volume).toFixed(2), num(exportTotals.msf).toFixed(4),
                    num(exportTotals.interchange).toFixed(2), num(exportTotals.schemeFee).toFixed(2),
                    num(exportTotals.ecomFee).toFixed(2),
                    num(exportTotals.netRevenue).toFixed(2), pctCell(exportTotals.marginPct),
                ].join(','));
            }
            const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            const tag = (data?.mode || period).toString().toLowerCase().replace(/[^a-z0-9-]/g, '');
            a.download = `${lossOnly ? 'loss-making' : 'volume-revenue'}-${tag}-${data?.effectiveDate || ''}.csv`;
            a.click();
            URL.revokeObjectURL(a.href);
        } catch (e) {
            // NOT handled globally, despite what this comment used to claim: the
            // axios response interceptor toasts on 401 only, logs 403 to console,
            // and rethrows everything else untouched. So a 429/500/dropped
            // connection anywhere in the paging loop above silently produced no
            // file and no message — the button just flipped back to "CSV" and the
            // user re-clicked, which under a 429 makes it worse.
            showToast(
                e?.response?.status === 429
                    ? 'Export throttled — too many requests. Try a shorter period.'
                    : 'Export failed. Please try again.',
                'error', 5000);
        }
        finally { setExporting(false); }
    };

    const rows = data?.rows || [];
    const totals = data?.totals;
    const totalRows = num(data?.totalRows);
    const totalPages = Math.max(1, Math.ceil(totalRows / PAGE_SIZE));

    const periodLabel = data?.mode === 'YTD' ? 'YTD'
        : data?.mode === 'THIS_MONTH' ? 'This Month'
        : (data?.mode || '').match(/^\d{4}-\d{2}$/)
            ? monthOptions.find(o => o.value === data.mode)?.label || data.mode
            : 'MTD';

    /* Total costs = EVERY deduction between MSF and net margin. The ECOM fee was
       missing here while sum_daily_terminal.total_revenue subtracts it
       (msf - interchange - scheme_fee - ecom_fee), so for any tenant with
       e-commerce volume the band did not reconcile: MSF - Costs was short of Net
       Margin by exactly the ECOM total, on the one screen whose job is explaining
       why a merchant loses money. (Dashboard.jsx already sums all three.) */
    const totalCosts = totals
        ? num(totals.interchange) + num(totals.schemeFee) + num(totals.ecomFee)
        : 0;

    /* The window can legitimately run past the data — THIS_MONTH always ends on
       the last day of the calendar month. Show coverage, not intent. */
    const displayTo = data?.dataThrough && data?.to && data.dataThrough < data.to
        ? data.dataThrough : data?.to;

    return (
        <div style={{ padding: '24px 28px', width: '100%', maxWidth: '100%', margin: 0, boxSizing: 'border-box' }}>
            <style>{`
                .cvr-table tbody tr { transition: background .12s ease; }
                .cvr-table tbody tr:hover { background: var(--bg-hover, rgba(148,163,184,0.07)); }
                .cvr-table thead th { position: sticky; top: 0; z-index: 1;
                    background: var(--bg-subtle, #f8fafc); }
            `}</style>

            {/* ── Header ── */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                flexWrap: 'wrap', gap: 14, marginBottom: 16 }}>
                <div>
                    <div style={{ fontFamily: 'ui-monospace, monospace', fontSize: 10.5,
                        letterSpacing: '0.2em', textTransform: 'uppercase',
                        color: 'var(--text-muted, #94a3b8)' }}>
                        Executive · {periodLabel}
                    </div>
                    <h1 style={{ margin: '3px 0 0', fontSize: 22, fontWeight: 700, color: 'var(--text)',
                        letterSpacing: '-0.01em', display: 'flex', alignItems: 'center', gap: 9 }}>
                        {lossOnly && <TrendingDown size={20} style={{ color: '#dc2626' }} />}
                        {title}
                    </h1>
                    <div style={{ marginTop: 4, fontSize: 12.5, color: 'var(--text-secondary)',
                        display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                        <CalendarRange size={13} />
                        {data?.from && displayTo ? `${data.from} → ${displayTo}` : ''}
                        <span style={{ color: 'var(--border)' }}>·</span>
                        {subtitleSuffix}
                        {lossOnly && <>
                            <span style={{ color: 'var(--border)' }}>·</span>
                            <span style={{ color: '#dc2626', fontWeight: 600 }}>net margin &lt; 0 only</span>
                        </>}
                    </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                    {/* search */}
                    <div style={{ position: 'relative' }}>
                        <Search size={14} style={{ position: 'absolute', left: 10, top: '50%',
                            transform: 'translateY(-50%)', color: 'var(--text-secondary)' }} />
                        <input value={search} onChange={e => setSearch(e.target.value)}
                            placeholder={lossOnly ? 'Search MID / name' : 'Search MID / SID / name'}
                            style={{
                                padding: '8px 12px 8px 30px', fontSize: 13, width: 200,
                                background: 'var(--bg-card)', border: '1px solid var(--border)',
                                borderRadius: 10, color: 'var(--text)', outline: 'none',
                            }} />
                    </div>

                    {/* period selector: MTD | YTD | This Month */}
                    <div style={{ display: 'inline-flex', background: 'var(--bg-card)',
                        border: '1px solid var(--border)', borderRadius: 10, padding: 3 }}>
                        {[['MTD', 'MTD'], ['YTD', 'YTD'], ['THIS_MONTH', 'This Month']].map(([val, lbl]) => (
                            <button key={val} onClick={() => { setPeriod(val); setPage(0); }} style={{
                                border: 'none', cursor: 'pointer', borderRadius: 8,
                                padding: '6px 14px', fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap',
                                background: period === val ? 'var(--brand, #3b82f6)' : 'transparent',
                                color: period === val ? '#fff' : 'var(--text-secondary)',
                                transition: 'background 0.15s, color 0.15s',
                            }}>{lbl}</button>
                        ))}
                    </div>

                    {/* month picker */}
                    <select value={period === 'MONTH' ? month : ''}
                        onChange={e => {
                            const v = e.target.value;
                            if (v) { setMonth(v); setPeriod('MONTH'); setPage(0); }
                        }}
                        style={{
                            padding: '8px 12px', fontSize: 13, borderRadius: 10,
                            background: period === 'MONTH' ? 'var(--brand, #3b82f6)' : 'var(--bg-card)',
                            color: period === 'MONTH' ? '#fff' : 'var(--text)',
                            border: '1px solid var(--border)', cursor: 'pointer', outline: 'none',
                        }}>
                        <option value="">Pick month…</option>
                        {monthOptions.map(o => (
                            <option key={o.value} value={o.value}
                                style={{ background: 'var(--bg-card)', color: 'var(--text)' }}>{o.label}</option>
                        ))}
                    </select>

                    <button onClick={exportCsv} disabled={exporting || !rows.length} style={{
                        display: 'flex', alignItems: 'center', gap: 6,
                        border: '1px solid var(--border)', background: 'var(--bg-card)',
                        borderRadius: 10, padding: '8px 14px', fontSize: 13, fontWeight: 600,
                        cursor: exporting || !rows.length ? 'default' : 'pointer',
                        color: 'var(--text-secondary)', opacity: exporting ? 0.6 : 1,
                    }}>
                        <Download size={14} /> {exporting ? 'Exporting…' : 'CSV'}
                    </button>
                    {/* () => load() — not onClick={load}: load's first arg is now an
                        AbortSignal, and handing it a MouseEvent would poison the
                        axios config. */}
                    <button onClick={() => load()} title="Refresh" style={{
                        border: '1px solid var(--border)', background: 'var(--bg-card)',
                        borderRadius: 10, padding: 8, cursor: 'pointer',
                        color: 'var(--text-secondary)', display: 'flex',
                    }}>
                        <RefreshCw size={15} />
                    </button>
                </div>
            </div>

            {loading ? <SkeletonLoader type="table" /> : error ? (
                <EmptyState title="Could not load report" message={error}
                    action={{ label: 'Retry', onClick: () => load() }} />
            ) : !rows.length ? (
                // An empty PAGE is not an empty RESULT SET. When totalRows > 0 the
                // page index is simply past the end — saying "no merchants are
                // running at a loss" there is a false all-clear on a risk screen.
                totalRows > 0 ? (
                    <EmptyState title="Page out of range"
                        message={`This page is past the end of ${totalRows.toLocaleString()} result${totalRows === 1 ? '' : 's'}.`}
                        action={{ label: 'Back to first page', onClick: () => setPage(0) }} />
                ) : (
                    <EmptyState title={lossOnly ? 'No loss-making merchants' : 'No rows'}
                        message={query ? 'No merchants match your search for this period.'
                            : lossOnly ? `No merchants are running at a loss for ${periodLabel}. That's good news.`
                            : `No data yet for ${periodLabel}. Upload data to populate this report.`} />
                )
            ) : (
                <>
                    {/* ── KPI summary band (period totals) ── */}
                    {totals && (
                        <div style={{
                            background: 'var(--bg-card)', border: '1px solid var(--border)',
                            borderRadius: 14, marginBottom: 14, overflow: 'hidden',
                            boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))',
                            display: 'grid',
                            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                        }}>
                            <div style={{ borderRight: '1px solid var(--border-light, var(--border))' }}>
                                <StatTile icon={Layers}
                                    label={lossOnly ? 'Loss Rows' : 'Rows'}
                                    value={totalRows.toLocaleString()}
                                    caption={`${num(totals.txns).toLocaleString()} transactions`}
                                    tone={lossOnly ? 'danger' : undefined} />
                            </div>
                            <div style={{ borderRight: '1px solid var(--border-light, var(--border))' }}>
                                <StatTile icon={Wallet} label="Volume"
                                    value={fmt.currency(num(totals.volume))}
                                    caption="settlement currency"
                                    title={fullNum(totals.volume, currencySymbol)} />
                            </div>
                            <div style={{ borderRight: '1px solid var(--border-light, var(--border))' }}>
                                <StatTile icon={Receipt} label="MSF"
                                    value={fmt.currency(num(totals.msf))}
                                    caption="gross fee revenue"
                                    title={formatMsf(totals.msf, currencySymbol)} />
                            </div>
                            <div style={{ borderRight: '1px solid var(--border-light, var(--border))' }}>
                                <StatTile icon={Landmark} label="Costs"
                                    value={fmt.currency(totalCosts)}
                                    caption="interchange + scheme + ECOM"
                                    title={fullNum(totalCosts, currencySymbol)} />
                            </div>
                            <div style={{ borderRight: '1px solid var(--border-light, var(--border))' }}>
                                <StatTile icon={lossOnly ? TrendingDown : Receipt}
                                    label={lossOnly ? 'Total Net Loss' : 'Net Margin'}
                                    value={fmt.currency(num(totals.netRevenue))}
                                    caption={lossOnly ? 'across loss rows' : 'MSF − costs'}
                                    tone={num(totals.netRevenue) >= 0 ? 'success' : 'danger'}
                                    title={fullNum(totals.netRevenue, currencySymbol)} />
                            </div>
                            <div>
                                <StatTile icon={Percent} label="Net Margin %"
                                    value={pct(totals.marginPct)}
                                    caption="net margin ÷ volume"
                                    tone={totals.marginPct == null ? undefined
                                        : num(totals.marginPct) >= 0 ? 'success' : 'danger'} />
                            </div>
                        </div>
                    )}

                    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
                        borderRadius: 14, overflow: 'hidden',
                        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))' }}>
                        <div style={{ overflowX: 'auto', maxHeight: '68vh', overflowY: 'auto' }}>
                            <table className="cvr-table" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                                <thead>
                                    <tr style={{ borderBottom: '1px solid var(--border)' }}>
                                        {visibleColumns.map(c => {
                                            const active = sort === c.key;
                                            return (
                                                <th key={c.key} onClick={() => onSort(c.key)}
                                                    style={{
                                                        textAlign: c.align, padding: '12px 14px',
                                                        fontSize: 11, fontWeight: 600,
                                                        letterSpacing: '0.06em', textTransform: 'uppercase',
                                                        color: active ? 'var(--text)' : 'var(--text-secondary)',
                                                        whiteSpace: 'nowrap',
                                                        borderBottom: '1px solid var(--border)',
                                                        cursor: c.sortable ? 'pointer' : 'default',
                                                        userSelect: 'none',
                                                    }}>
                                                    {c.label}
                                                    {active && (dir === 'desc'
                                                        ? <ChevronDown size={12} style={{ verticalAlign: '-2px', marginLeft: 3 }} />
                                                        : <ChevronUp size={12} style={{ verticalAlign: '-2px', marginLeft: 3 }} />)}
                                                </th>
                                            );
                                        })}
                                    </tr>
                                </thead>
                                <tbody>
                                    {rows.map((r, i) => (
                                        <tr key={`${r.mid}-${r.sid}-${i}`}
                                            style={{ borderBottom: '1px solid var(--border-light, var(--border))',
                                                background: lossOnly ? 'rgba(220,38,38,0.03)' : 'transparent' }}>
                                            <td style={{ ...tdText, fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{r.mid || '—'}</td>
                                            {!lossOnly && (
                                                <td style={{ ...tdText, fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{r.sid || '—'}</td>
                                            )}
                                            <td style={{ ...tdText, maxWidth: 260, overflow: 'hidden',
                                                textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 }}
                                                title={r.name}>{r.name || '—'}</td>
                                            <td style={tdNum} title={fullNum(r.txns)}>{num(r.txns).toLocaleString()}</td>
                                            <td style={tdNum} title={fullNum(r.volume, currencySymbol)}>{fmt.currency(num(r.volume))}</td>
                                            <td style={tdNum} title={formatMsf(r.msf, currencySymbol)}>{fmt.currency(num(r.msf))}</td>
                                            <td style={tdNum} title={fullNum(r.interchange, currencySymbol)}>{fmt.currency(num(r.interchange))}</td>
                                            <td style={tdNum} title={fullNum(r.schemeFee, currencySymbol)}>{fmt.currency(num(r.schemeFee))}</td>
                                            <td style={tdNum} title={fullNum(r.ecomFee, currencySymbol)}>{fmt.currency(num(r.ecomFee))}</td>
                                            <td style={{ ...tdNum, fontWeight: 600,
                                                color: num(r.netRevenue) >= 0 ? 'var(--text)' : '#dc2626' }}
                                                title={fullNum(r.netRevenue, currencySymbol)}>
                                                {fmt.currency(num(r.netRevenue))}
                                            </td>
                                            <td style={tdNum}>
                                                <span title={r.marginPct == null
                                                        ? 'No settlement volume in this period — margin % is undefined'
                                                        : undefined}
                                                    style={{
                                                        display: 'inline-block', minWidth: 64, textAlign: 'right',
                                                        padding: '2px 8px', borderRadius: 6, fontWeight: 700,
                                                        fontVariantNumeric: 'tabular-nums',
                                                        color: pctTone(r.marginPct),
                                                        background: r.marginPct == null
                                                            ? 'transparent'
                                                            : num(r.marginPct) >= 0
                                                                ? 'var(--success-bg, rgba(5,150,105,0.08))'
                                                                : 'var(--danger-bg, rgba(220,38,38,0.08))',
                                                    }}>
                                                    {pct(r.marginPct)}
                                                </span>
                                            </td>
                                        </tr>
                                    ))}
                                    {totals && (
                                        <tr style={{ background: 'var(--bg-hover, rgba(148,163,184,0.06))' }}>
                                            <td colSpan={lossOnly ? 2 : 3} style={{ padding: '12px 14px', fontWeight: 700, color: 'var(--text)' }}>
                                                {periodLabel} Total · {totalRows.toLocaleString()} {lossOnly ? 'loss rows' : 'rows'}
                                            </td>
                                            <td style={tdTotal} title={fullNum(totals.txns)}>{num(totals.txns).toLocaleString()}</td>
                                            <td style={tdTotal} title={fullNum(totals.volume, currencySymbol)}>{fmt.currency(num(totals.volume))}</td>
                                            <td style={tdTotal} title={formatMsf(totals.msf, currencySymbol)}>{fmt.currency(num(totals.msf))}</td>
                                            <td style={tdTotal} title={fullNum(totals.interchange, currencySymbol)}>{fmt.currency(num(totals.interchange))}</td>
                                            <td style={tdTotal} title={fullNum(totals.schemeFee, currencySymbol)}>{fmt.currency(num(totals.schemeFee))}</td>
                                            <td style={tdTotal} title={fullNum(totals.ecomFee, currencySymbol)}>{fmt.currency(num(totals.ecomFee))}</td>
                                            <td style={{ ...tdTotal, color: num(totals.netRevenue) >= 0 ? 'var(--text)' : '#dc2626' }}
                                                title={fullNum(totals.netRevenue, currencySymbol)}>{fmt.currency(num(totals.netRevenue))}</td>
                                            <td style={{ ...tdTotal, color: pctTone(totals.marginPct) }}>
                                                {pct(totals.marginPct)}
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                        gap: 10, marginTop: 14, fontSize: 13, color: 'var(--text-secondary)' }}>
                        <span style={{ fontSize: 12.5 }}>
                            Showing {(page * PAGE_SIZE + 1).toLocaleString()}–{Math.min((page + 1) * PAGE_SIZE, totalRows).toLocaleString()} of {totalRows.toLocaleString()}
                        </span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                            <span>Page {page + 1} of {totalPages}</span>
                            <button disabled={page === 0} onClick={() => setPage(p => p - 1)} style={pagerBtn(page === 0)}>
                                <ChevronLeft size={15} />
                            </button>
                            <button disabled={page + 1 >= totalPages} onClick={() => setPage(p => p + 1)}
                                style={pagerBtn(page + 1 >= totalPages)}>
                                <ChevronRight size={15} />
                            </button>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
};

const tdText = { padding: '11px 14px', color: 'var(--text)' };
const tdNum = {
    padding: '11px 14px', textAlign: 'right', color: 'var(--text)',
    fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
};
const tdTotal = { ...tdNum, fontWeight: 700 };
const pagerBtn = (disabled) => ({
    border: '1px solid var(--border)', background: 'var(--bg-card)',
    borderRadius: 8, padding: 6, display: 'flex',
    cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.45 : 1,
    color: 'var(--text-secondary)',
});

export default CeoVolumeRevenue;
