import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import api from '../../api/axios';
import MarginGlossaryHint from '../../components/MarginGlossary';
import {
    RefreshCw, Search, Download, ChevronLeft, ChevronRight,
    ChevronUp, ChevronDown, CalendarRange,
    Landmark, Receipt, Percent, Layers, Wallet,
} from 'lucide-react';
import ChannelToggle from '../../components/ChannelToggle';
import MidSidSummary from '../../components/MidSidSummary';
import EmptyState from '../../components/EmptyState';
import SkeletonLoader from '../../components/SkeletonLoader';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    createFmt, formatMsf, resolveDecimals,
    isUsdDisplay, convertForDisplay, displayCurrencyCode, usdRateInfo,
} from '../../utils/formatters';

/* ════════════════════════════════════════════════════════════════════
   Volume & Revenue Mix — the Volume & Revenue detail with three more key
   columns. Every row is SID x MID x Merchant x DESTINATION x MCC x SCHEME
   over the period, with the same fee stack as Volume & Revenue: Count,
   Volume (settlement), MSF, Interchange, Scheme Fee, ECOM Fee, Net Margin,
   Net Margin %. Period: MTD / YTD / This Month / pick any month. Search,
   sortable columns, server pagination, CSV export, POS/ECOM scope.
   Data: /api/business/volume-revenue-mix (sum_daily_full — summary read
   only, never fact_transaction). Its TOTAL ties out with Volume & Revenue
   for the same period and channel.

   No Net Spread: DCC, rental and FX income have no destination / MCC /
   scheme of their own, so this screen stops at Net Margin and says so.

   Visual register: identical to CeoVolumeRevenue.jsx — white panels on
   the slate workspace, hairline borders, uppercase micro-labels, tabular
   numerals, KPI band above the detail table, full-bleed width.
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));
/* Exact (uncompacted) tooltip value. With a symbol it is MONEY and follows the
   tenant's decimals (3dp for BHD); without one it is a count. */
const fullNum = (v, sym = '') => {
    if (!sym) return Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 0 });
    const d = isUsdDisplay(sym) ? 2 : resolveDecimals();
    return displayCurrencyCode(sym) + ' ' + convertForDisplay(v, sym).toLocaleString('en-US',
        { minimumFractionDigits: d, maximumFractionDigits: d });
};

/* Net margin % is null from the server when the ratio is undefined (zero
   volume). Render that as an em-dash — NEVER as 0.00%. */
const pct = (v) => (v == null ? '—' : `${Number(v).toFixed(2)}%`);
const pctTone = (v) => (v == null ? 'var(--text-secondary)' : Number(v) >= 0 ? '#059669' : '#dc2626');

/* Destination is stored upper-case; show it title-cased. */
const titleCase = (s) => (s || '').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());

/* Column order is fixed here (SID first, MID second — same business rule as
   Volume & Revenue), then the three dimension keys, then the fee stack. */
const COLUMNS = [
    { key: 'sid',         label: 'SID',            align: 'left',  sortable: false },
    { key: 'mid',         label: 'MID',            align: 'left',  sortable: true },
    { key: 'name',        label: 'Merchant',       align: 'left',  sortable: true },
    { key: 'destination', label: 'Destination',    align: 'left',  sortable: true },
    { key: 'mcc',         label: 'MCC',            align: 'left',  sortable: true },
    { key: 'mccCategory', label: 'MCC Category',   align: 'left',  sortable: false },
    { key: 'scheme',      label: 'Scheme',         align: 'left',  sortable: true },
    { key: 'txns',        label: 'Count',          align: 'right', sortable: true },
    { key: 'volume',      label: 'Volume',         align: 'right', sortable: true },
    { key: 'msf',         label: 'MSF',            align: 'right', sortable: true },
    { key: 'interchange', label: 'Interchange',    align: 'right', sortable: true },
    { key: 'schemeFee',   label: 'Scheme Fee',     align: 'right', sortable: true },
    { key: 'ecomFee',     label: 'ECOM Fee',       align: 'right', sortable: true },
    { key: 'net',         label: 'Net Margin',     align: 'right', sortable: true },
    { key: 'margin',      label: 'Net Margin %',   align: 'right', sortable: true },
];
const TEXT_COLS = 7; // SID … Scheme — the totals row spans these

const PAGE_SIZE = 50;

/* Build the last N month options as {value:'YYYY-MM', label:'Mon YYYY'}.
   Parsed component-wise (not new Date(iso)) so the anchor never slides a day
   west of UTC. */
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

/* ── KPI stat card (same as Volume & Revenue) ── */
const StatTile = ({ icon, label, value, caption, tone, title }) => {
    const Icon = icon;
    const accent =
        tone === 'danger'  ? '#dc2626' :
        tone === 'success' ? '#059669' : 'var(--brand, #3b82f6)';
    const valueColor = tone ? accent : 'var(--text)';
    return (
        <div className="cvr-card" title={title} style={{
            position: 'relative', minWidth: 0, padding: '15px 18px 14px',
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: 14, overflow: 'hidden',
            boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))',
        }}>
            <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 3,
                background: accent, opacity: 0.9 }} />
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{
                    width: 26, height: 26, borderRadius: 8, flex: 'none',
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                    color: accent,
                    background: `color-mix(in srgb, ${accent} 12%, transparent)`,
                }}>
                    <Icon size={13} strokeWidth={2.2} />
                </span>
                <span style={{
                    fontSize: 10.5, fontWeight: 700, letterSpacing: '0.08em',
                    textTransform: 'uppercase', color: 'var(--text-muted, #94a3b8)',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {label}
                </span>
            </div>
            <div style={{
                marginTop: 9, fontSize: 21, fontWeight: 700, color: valueColor,
                letterSpacing: '-0.01em', fontVariantNumeric: 'tabular-nums',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {value}
            </div>
            {caption && (
                <div style={{ marginTop: 3, fontSize: 11.5, color: 'var(--text-secondary)',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {caption}
                </div>
            )}
        </div>
    );
};

const VolumeRevenueMix = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);

    // period: 'MTD' | 'YTD' | 'THIS_MONTH' | 'MONTH'
    const [period, setPeriod] = useState('MTD');
    const [month, setMonth] = useState('');
    const [page, setPage] = useState(0);
    const [sort, setSort] = useState('volume');
    const [dir, setDir] = useState('desc');
    const [search, setSearch] = useState('');
    const [query, setQuery] = useState('');
    const [channel, setChannel] = useState('ALL');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [exporting, setExporting] = useState(false);
    const debounceRef = useRef(null);

    const monthOptions = useMemo(
        () => buildMonthOptions(data?.effectiveDate, 12),
        [data?.effectiveDate]);

    const periodParams = useMemo(() => {
        if (period === 'MONTH' && month) return { month };
        if (period === 'YTD') return { mode: 'YTD' };
        if (period === 'THIS_MONTH') return { mode: 'THIS_MONTH' };
        return { mode: 'MTD' };
    }, [period, month]);

    useEffect(() => {
        clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(() => { setQuery(search.trim()); setPage(0); }, 350);
        return () => clearTimeout(debounceRef.current);
    }, [search]);

    const load = useCallback(async (signal) => {
        setLoading(true); setError(null);
        try {
            const res = await api.get('/business/volume-revenue-mix', {
                signal,
                params: {
                    ...periodParams, channel,
                    page, size: PAGE_SIZE, sort, dir, search: query || undefined,
                },
            });
            setData(res.data);
        } catch (e) {
            // Superseded by a newer request — leave the screen alone.
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            setError(e?.response?.data?.message || 'Failed to load report');
        } finally {
            setLoading(false);
        }
    }, [periodParams, channel, page, sort, dir, query]);

    useEffect(() => {
        const ac = new AbortController();
        load(ac.signal);
        return () => ac.abort();
    }, [load, tenantVersion]);

    // A tenant switch resets paging and the channel scope, like Volume & Revenue.
    useEffect(() => { setPage(0); setChannel('ALL'); }, [tenantVersion]);

    const onSort = (key) => {
        const col = COLUMNS.find(c => c.key === key);
        if (!col?.sortable) return;
        if (sort === key) setDir(d => (d === 'desc' ? 'asc' : 'desc'));
        else { setSort(key); setDir('desc'); }
        setPage(0);
    };

    const exportCsv = async () => {
        setExporting(true);
        try {
            const base = { ...periodParams, channel, sort, dir, search: query || undefined };
            // export=true returns the FULL result set in one response.
            const res = await api.get('/business/volume-revenue-mix', { params: { ...base, export: true } });
            const rows = res.data?.rows || [];
            const totalRows = num(res.data?.totalRows);
            const exportTotals = res.data?.totals;
            // Excel evaluates a field that begins =, +, - or @ once unquoted;
            // merchant names come from ingested master data. Force text.
            const esc = (v) => {
                const s = String(v ?? '');
                return `"${(/^[=+\-@]/.test(s) ? `'${s}` : s).replace(/"/g, '""')}"`;
            };
            const pctCell = (v) => (v == null ? '' : Number(v).toFixed(2));
            // Money columns follow the tenant's precision (3dp for BHD); MSF keeps
            // its reconciliation digits. USD toggle on: converted, written 2dp.
            const dp = isUsdDisplay(currencyCode) ? 2 : resolveDecimals(currencyDecimals, currencyCode);
            const msfDp = Math.max(4, dp);
            const cv = (v) => convertForDisplay(num(v), currencyCode);
            const fx = usdRateInfo(currencyCode);
            const header = ['SID', 'MID', 'Merchant', 'Destination', 'MCC', 'MCC Category', 'Scheme',
                'Count', 'Volume', 'MSF', 'Interchange Fee', 'Scheme Fee', 'ECOM Fee', 'Net Margin', 'Net Margin %'];
            const lines = [
                `Currency,${displayCurrencyCode(currencyCode) || currencySymbol || 'UNKNOWN'}`,
                ...(fx ? [`FX Rate,1 ${fx.base} = ${fx.rate} USD (indicative; as of ${fx.asOf})`] : []),
                `Channel,${channel}`,
                header.join(','),
            ];
            rows.forEach(r => lines.push([
                esc(r.sid), esc(r.mid), esc(r.name),
                esc(titleCase(r.destination)), esc(r.mcc), esc(r.mccCategory), esc(r.scheme),
                num(r.txns),
                cv(r.volume).toFixed(dp), cv(r.msf).toFixed(msfDp),
                cv(r.interchange).toFixed(dp), cv(r.schemeFee).toFixed(dp),
                cv(r.ecomFee).toFixed(dp),
                cv(r.netRevenue).toFixed(dp), pctCell(r.marginPct),
            ].join(',')));
            // Trailing TOTAL row = the server's own period aggregate, so the file
            // is self-verifying against a SUM() of the Volume column.
            if (exportTotals) {
                lines.push([
                    esc('TOTAL'), esc(''), esc(`${totalRows} rows (period total)`),
                    esc(''), esc(''), esc(''), esc(''),
                    num(exportTotals.txns),
                    cv(exportTotals.volume).toFixed(dp), cv(exportTotals.msf).toFixed(msfDp),
                    cv(exportTotals.interchange).toFixed(dp), cv(exportTotals.schemeFee).toFixed(dp),
                    cv(exportTotals.ecomFee).toFixed(dp),
                    cv(exportTotals.netRevenue).toFixed(dp), pctCell(exportTotals.marginPct),
                ].join(','));
            }
            const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            const tag = (data?.mode || period).toString().toLowerCase().replace(/[^a-z0-9-]/g, '');
            const chTag = channel !== 'ALL' ? `-${channel.toLowerCase()}` : '';
            a.download = `volume-revenue-mix-${tag}${chTag}-${data?.effectiveDate || ''}.csv`;
            a.click();
            URL.revokeObjectURL(a.href);
        } catch (e) {
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

    /* Total costs = every deduction between MSF and net margin. */
    const totalCosts = totals
        ? num(totals.interchange) + num(totals.schemeFee) + num(totals.ecomFee)
        : 0;

    /* THIS_MONTH always ends on the last calendar day — show coverage, not intent. */
    const displayTo = data?.dataThrough && data?.to && data.dataThrough < data.to
        ? data.dataThrough : data?.to;

    return (
        <div style={{ padding: '24px 28px', width: '100%', maxWidth: '100%', margin: 0, boxSizing: 'border-box' }}>
            <style>{`
                .cvr-card { transition: transform .15s ease, box-shadow .15s ease; }
                .cvr-card:hover { transform: translateY(-2px);
                    box-shadow: 0 6px 16px rgba(16,24,40,0.08); }
                .cvr-table tbody tr { transition: background .12s ease; }
                .cvr-table tbody tr.cvr-row:nth-child(even) {
                    background: rgba(148,163,184,0.05); }
                .cvr-table tbody tr.cvr-row:hover {
                    background: var(--bg-hover, rgba(148,163,184,0.10)); }
                .cvr-table thead th { position: sticky; top: 0; z-index: 1;
                    background: var(--bg-subtle, #f8fafc); }
                .cvr-table thead th::after { content: ''; position: absolute;
                    left: 0; right: 0; bottom: 0; height: 2px;
                    background: var(--border); }
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
                        letterSpacing: '-0.01em' }}>
                        Volume &amp; Revenue Mix
                    </h1>
                    <div style={{ marginTop: 4, fontSize: 12.5, color: 'var(--text-secondary)',
                        display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                        <CalendarRange size={13} />
                        {data?.from && displayTo ? `${data.from} → ${displayTo}` : ''}
                        <span style={{ color: 'var(--border)' }}>·</span>
                        volume &amp; fees in settlement currency by destination, MCC and scheme
                        <MarginGlossaryHint compact style={{ marginLeft: 2 }} />
                    </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                    <MidSidSummary from={data?.from} to={displayTo} channel={channel} compact />
                    <div style={{ position: 'relative' }}>
                        <Search size={14} style={{ position: 'absolute', left: 10, top: '50%',
                            transform: 'translateY(-50%)', color: 'var(--text-secondary)' }} />
                        <input value={search} onChange={e => setSearch(e.target.value)}
                            placeholder="Search SID / MID / name / MCC / scheme"
                            style={{
                                padding: '8px 12px 8px 30px', fontSize: 13, width: 250,
                                background: 'var(--bg-card)', border: '1px solid var(--border)',
                                borderRadius: 10, color: 'var(--text)', outline: 'none',
                            }} />
                    </div>

                    <ChannelToggle value={channel}
                        onChange={(ch) => { setChannel(ch); setPage(0); }} />

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
                    <button onClick={() => load()} title="Refresh" style={{
                        border: '1px solid var(--border)', background: 'var(--bg-card)',
                        borderRadius: 10, padding: 8, cursor: 'pointer',
                        color: 'var(--text-secondary)', display: 'flex',
                    }}>
                        <RefreshCw size={15} />
                    </button>
                </div>
            </div>

            {loading ? <SkeletonLoader variant="table" rows={8} cols={8} /> : error ? (
                <EmptyState title="Could not load report" message={error}
                    action={{ label: 'Retry', onClick: () => load() }} />
            ) : !rows.length ? (
                totalRows > 0 ? (
                    <EmptyState title="Page out of range"
                        message={`This page is past the end of ${totalRows.toLocaleString()} result${totalRows === 1 ? '' : 's'}.`}
                        action={{ label: 'Back to first page', onClick: () => setPage(0) }} />
                ) : (
                    <EmptyState title="No rows"
                        message={query ? 'No rows match your search for this period.'
                            : `No data yet for ${periodLabel}. Upload data to populate this report.`} />
                )
            ) : (
                <>
                    {/* ── KPI summary band (period totals) ── */}
                    {totals && (
                        <div style={{
                            display: 'grid', gap: 12, marginBottom: 16,
                            gridTemplateColumns: 'repeat(auto-fit, minmax(178px, 1fr))',
                        }}>
                            <StatTile icon={Layers} label="Rows"
                                value={totalRows.toLocaleString()}
                                caption={`${num(totals.txns).toLocaleString()} transactions`} />
                            <StatTile icon={Wallet} label="Volume"
                                value={fmt.currency(num(totals.volume))}
                                caption="settlement currency"
                                title={fullNum(totals.volume, currencySymbol)} />
                            <StatTile icon={Receipt} label="MSF"
                                value={fmt.currency(num(totals.msf))}
                                caption="gross fee revenue"
                                title={formatMsf(totals.msf, currencySymbol)} />
                            <StatTile icon={Landmark} label="Costs"
                                value={fmt.currency(totalCosts)}
                                caption="interchange + scheme + ECOM"
                                title={fullNum(totalCosts, currencySymbol)} />
                            <StatTile icon={Receipt} label="Net Margin"
                                value={fmt.currency(num(totals.netRevenue))}
                                caption="MSF − costs"
                                tone={num(totals.netRevenue) >= 0 ? 'success' : 'danger'}
                                title={fullNum(totals.netRevenue, currencySymbol)} />
                            <StatTile icon={Percent} label="Net Margin %"
                                value={pct(totals.marginPct)}
                                caption="net margin ÷ volume"
                                tone={totals.marginPct == null ? undefined
                                    : num(totals.marginPct) >= 0 ? 'success' : 'danger'} />
                        </div>
                    )}

                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
                        fontSize: 12, color: 'var(--text-secondary)', margin: '-6px 2px 12px' }}>
                        <Layers size={13} style={{ opacity: 0.7 }} />
                        <span>Each row is one SID × MID × destination × MCC × scheme. DCC, rental and FX income
                            have no destination, MCC or scheme, so Net Spread is on Volume &amp; Revenue only.</span>
                    </div>

                    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
                        borderRadius: 14, overflow: 'hidden',
                        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))' }}>
                        <div className="acq-scroll" style={{ overflowX: 'auto', maxHeight: '68vh', overflowY: 'auto' }}>
                            <table className="cvr-table" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                                <thead>
                                    <tr style={{ borderBottom: '1px solid var(--border)' }}>
                                        {COLUMNS.map(c => {
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
                                        <tr key={`${r.mid}-${r.sid}-${r.destination}-${r.mcc}-${r.scheme}-${i}`}
                                            className="cvr-row"
                                            style={{ borderBottom: '1px solid var(--border-light, var(--border))' }}>
                                            <td style={{ ...tdText, fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{r.sid || '—'}</td>
                                            <td style={{ ...tdText, fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{r.mid || '—'}</td>
                                            <td style={{ ...tdText, maxWidth: 240, overflow: 'hidden',
                                                textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 }}
                                                title={r.name}>{r.name || '—'}</td>
                                            <td style={{ ...tdText, whiteSpace: 'nowrap' }}>{titleCase(r.destination) || '—'}</td>
                                            <td style={{ ...tdText, fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{r.mcc || '—'}</td>
                                            <td style={{ ...tdText, maxWidth: 200, overflow: 'hidden',
                                                textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--text-secondary)' }}
                                                title={r.mccCategory || ''}>{r.mccCategory || '—'}</td>
                                            <td style={{ ...tdText, whiteSpace: 'nowrap' }}>{r.scheme || '—'}</td>
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
                                            <td colSpan={TEXT_COLS} style={{ padding: '12px 14px', fontWeight: 700, color: 'var(--text)' }}>
                                                {periodLabel} Total · {totalRows.toLocaleString()} rows
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

export default VolumeRevenueMix;
