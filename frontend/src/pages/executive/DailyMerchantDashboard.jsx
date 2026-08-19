import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Drawer, IconButton } from '@mui/material';
import {
    RefreshCw, Search, Download, ChevronLeft, ChevronRight,
    ChevronUp, ChevronDown, CalendarDays, X, Check,
    Layers, Wallet, Receipt, Landmark, Globe2, CreditCard, Coins,
    TrendingUp, TrendingDown, ExternalLink,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import api from '../../api/axios';
import { cachedGet } from '../../api/apiCache';
import EmptyState from '../../components/EmptyState';
import SkeletonLoader from '../../components/SkeletonLoader';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import { createFmt, formatMsf, resolveDecimals } from '../../utils/formatters';

/* ════════════════════════════════════════════════════════════════════
   Executive Daily Merchant Dashboard — ONE business date, per-(MID, SID)
   rows with the full fee stack: SID, MID, Name, Vol, Count, MSF, ICF,
   SF, PG, NM. Compact acquiring filter bar (MCC / Destination / Card
   Type / Scheme / RM), SID-MID-name search, daily KPI totals, server
   pagination + sorting, CSV export, row drilldown drawer.

   Data: POST /api/business/executive-daily-merchant (sum_daily_full —
   summary read only, never fact_transaction). NM is the batch-computed
   total_net_revenue (MSF − ICF − SF − PG), never recomputed client-side.

   Distinct page from /business/daily-dashboard (the month heat-grid) —
   both exist on purpose.
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));
const fullNum = (v, sym = '') => {
    if (!sym) return Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 0 });
    const d = resolveDecimals();
    return sym + ' ' + Number(v || 0).toLocaleString('en-US',
        { minimumFractionDigits: d, maximumFractionDigits: d });
};

/* Fixed column set — the spec is explicit: never configurable. */
const COLUMNS = [
    { key: 'sid',   label: 'SID',   align: 'left',  sortable: true,  sticky: 1 },
    { key: 'mid',   label: 'MID',   align: 'left',  sortable: true,  sticky: 2 },
    { key: 'name',  label: 'Name',  align: 'left',  sortable: true,  sticky: 3 },
    { key: 'volume', label: 'Vol',  align: 'right', sortable: true },
    { key: 'count', label: 'Count', align: 'right', sortable: true },
    { key: 'msf',   label: 'MSF',   align: 'right', sortable: true },
    { key: 'icf',   label: 'ICF',   align: 'right', sortable: true },
    { key: 'sf',    label: 'SF',    align: 'right', sortable: true },
    { key: 'pg',    label: 'PG',    align: 'right', sortable: true },
    { key: 'nm',    label: 'NM',    align: 'right', sortable: true },
];

const PAGE_SIZES = [25, 50, 100];

const EMPTY_FILTERS = () => ({
    mccList: [], destinationList: [], cardTypeList: [], schemeList: [], rmList: [],
});

/* Parse 'YYYY-MM-DD' component-wise — new Date(iso) is UTC midnight and slides
   a day in some zones (same trap documented in PremiumReportHeader). */
const parseDay = (iso) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
};
const pillLabel = (iso) => {
    const d = parseDay(iso);
    return d ? d.toLocaleDateString('en-US', { day: '2-digit', month: 'short' }) : iso;
};
const longDate = (iso) => {
    const d = parseDay(iso);
    return d ? d.toLocaleDateString('en-US', { day: '2-digit', month: 'short', year: 'numeric' }) : iso;
};
const monthLabel = (ym) => {
    const m = /^(\d{4})-(\d{2})$/.exec(ym || '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, 1)
        .toLocaleDateString('en-US', { month: 'short', year: 'numeric' }) : ym;
};

/* ── Token-styled multi-select dropdown (checkbox list + option search).
   The shared components/MultiSelect.jsx is hardcoded to Tailwind blues; this
   one draws entirely from Meridian tokens so it sits on any theme. ── */
const FilterSelect = ({ label, options, selected, onChange }) => {
    const [open, setOpen] = useState(false);
    const [q, setQ] = useState('');
    const ref = useRef(null);

    useEffect(() => {
        const onDoc = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
        document.addEventListener('mousedown', onDoc);
        return () => document.removeEventListener('mousedown', onDoc);
    }, []);

    const shown = useMemo(() => {
        const list = options || [];
        if (!q.trim()) return list;
        const needle = q.trim().toLowerCase();
        return list.filter(o => String(o).toLowerCase().includes(needle));
    }, [options, q]);

    const toggle = (opt) => {
        onChange(selected.includes(opt) ? selected.filter(v => v !== opt) : [...selected, opt]);
    };
    const active = selected.length > 0;

    return (
        <div ref={ref} style={{ position: 'relative' }}>
            <button onClick={() => setOpen(o => !o)} style={{
                display: 'flex', alignItems: 'center', gap: 6,
                padding: '7px 12px', fontSize: 12.5, fontWeight: 600, whiteSpace: 'nowrap',
                background: active ? 'var(--wash, rgba(59,130,246,0.08))' : 'var(--bg-card)',
                color: active ? 'var(--primary, #3b82f6)' : 'var(--text-secondary)',
                border: `1px solid ${active ? 'var(--primary, #3b82f6)' : 'var(--border)'}`,
                borderRadius: 9, cursor: 'pointer',
            }}>
                {label}
                {active && (
                    <span style={{
                        minWidth: 17, height: 17, borderRadius: 9, padding: '0 4px',
                        background: 'var(--primary, #3b82f6)', color: '#fff',
                        fontSize: 10, fontWeight: 700,
                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                    }}>{selected.length}</span>
                )}
                <ChevronDown size={13} style={{
                    transition: 'transform .15s', transform: open ? 'rotate(180deg)' : 'none' }} />
            </button>

            {open && (
                <div style={{
                    position: 'absolute', top: 'calc(100% + 4px)', left: 0, zIndex: 60,
                    minWidth: 220, maxWidth: 320, background: 'var(--bg-card)',
                    border: '1px solid var(--border)', borderRadius: 10,
                    boxShadow: 'var(--shadow-lg, 0 12px 28px rgba(15,23,42,0.18))',
                    overflow: 'hidden',
                }}>
                    {(options?.length || 0) > 8 && (
                        <div style={{ padding: 8, borderBottom: '1px solid var(--border-light, var(--border))' }}>
                            <input autoFocus value={q} onChange={e => setQ(e.target.value)}
                                placeholder={`Search ${label.toLowerCase()}…`}
                                style={{
                                    width: '100%', boxSizing: 'border-box', padding: '6px 9px',
                                    fontSize: 12, background: 'var(--bg-subtle, var(--bg))',
                                    border: '1px solid var(--border)', borderRadius: 7,
                                    color: 'var(--text)', outline: 'none',
                                }} />
                        </div>
                    )}
                    <div style={{ maxHeight: 240, overflowY: 'auto', padding: '4px 0' }}>
                        {shown.map(opt => {
                            const on = selected.includes(opt);
                            return (
                                <div key={opt} onClick={() => toggle(opt)} style={{
                                    display: 'flex', alignItems: 'center', gap: 8,
                                    padding: '7px 12px', fontSize: 12.5, cursor: 'pointer',
                                    color: on ? 'var(--primary, #3b82f6)' : 'var(--text)',
                                    fontWeight: on ? 600 : 400,
                                    background: on ? 'var(--wash, rgba(59,130,246,0.06))' : 'transparent',
                                }}
                                    onMouseEnter={e => { if (!on) e.currentTarget.style.background = 'var(--bg-hover, rgba(148,163,184,0.08))'; }}
                                    onMouseLeave={e => { if (!on) e.currentTarget.style.background = 'transparent'; }}>
                                    <span style={{
                                        width: 15, height: 15, borderRadius: 4, flexShrink: 0,
                                        border: `1.5px solid ${on ? 'var(--primary, #3b82f6)' : 'var(--border)'}`,
                                        background: on ? 'var(--primary, #3b82f6)' : 'transparent',
                                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                                    }}>
                                        {on && <Check size={11} color="#fff" strokeWidth={3} />}
                                    </span>
                                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                        {String(opt)}
                                    </span>
                                </div>
                            );
                        })}
                        {!shown.length && (
                            <div style={{ padding: '14px 12px', fontSize: 12, color: 'var(--text-muted, #94a3b8)', textAlign: 'center' }}>
                                No options
                            </div>
                        )}
                    </div>
                    {active && (
                        <div onClick={() => onChange([])} style={{
                            padding: '7px 12px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer',
                            color: 'var(--danger, #dc2626)', borderTop: '1px solid var(--border-light, var(--border))',
                        }}>
                            Clear {label}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};

/* ── KPI tile (CeoVolumeRevenue register: micro-label + tabular value) ── */
const StatTile = ({ icon: Icon, label, value, caption, tone, title }) => {
    const valueColor =
        tone === 'danger'  ? 'var(--danger, #dc2626)' :
        tone === 'success' ? 'var(--success, #059669)' : 'var(--text)';
    return (
        <div title={title} style={{ padding: '14px 18px', minWidth: 0 }}>
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
                marginTop: 6, fontSize: 19, fontWeight: 700, color: valueColor,
                letterSpacing: '-0.01em', fontVariantNumeric: 'tabular-nums',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {value}
            </div>
            {caption && (
                <div style={{ marginTop: 2, fontSize: 11, color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                    {caption}
                </div>
            )}
        </div>
    );
};

const FILTER_DEFS = [
    { key: 'mccList',         label: 'MCC',         optKey: 'mccs' },
    { key: 'destinationList', label: 'Destination', optKey: 'destinations' },
    { key: 'cardTypeList',    label: 'Card Type',   optKey: 'cardTypes' },
    { key: 'schemeList',      label: 'Scheme',      optKey: 'schemes' },
    { key: 'rmList',          label: 'RM Name',     optKey: 'rms' },
];
const CHIP_LABELS = {
    mccList: 'MCC', destinationList: 'Dest', cardTypeList: 'Card',
    schemeList: 'Scheme', rmList: 'RM',
};

const DailyMerchantDashboard = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const navigate = useNavigate();

    // Month-driven date picker: pick a month -> its loaded dates render as
    // toggle pills. Any number of pills can be on; NONE selected = the whole
    // month. Default: the latest month with its latest date pre-selected.
    const [months, setMonths] = useState([]);       // ['2026-06', ...] newest first
    const [latest, setLatest] = useState('');       // latest loaded date ISO
    const [month, setMonth] = useState('');         // selected 'YYYY-MM'
    const [monthDates, setMonthDates] = useState([]); // loaded dates in that month
    const [selectedDates, setSelectedDates] = useState([]); // [] = whole month
    const [filters, setFilters] = useState(EMPTY_FILTERS);
    const [options, setOptions] = useState({});
    const [search, setSearch] = useState('');
    const [query, setQuery] = useState('');
    const [page, setPage] = useState(0);
    const [pageSize, setPageSize] = useState(50);
    const [sort, setSort] = useState('volume');
    const [dir, setDir] = useState('desc');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [exporting, setExporting] = useState(false);
    const [detailRow, setDetailRow] = useState(null);
    const debounceRef = useRef(null);

    /* Exact money for the table: the spec asks for full locale numbers, not
       compact — currency stated once in the toolbar, tenant precision (BHD 3dp). */
    const dp = resolveDecimals(currencyDecimals, currencyCode);
    const money = (v) => Number(v || 0).toLocaleString('en-US',
        { minimumFractionDigits: dp, maximumFractionDigits: dp });

    /* ── Reference data: month list (+ latest date) and filter options.
       Opening state: latest month selected with just the latest date on. ── */
    useEffect(() => {
        let cancelled = false;
        api.get('/business/executive-daily-merchant/calendar')
            .then(res => {
                if (cancelled) return;
                const ms = res.data?.months || [];
                const lt = res.data?.latest || '';
                setMonths(ms); setLatest(lt);
                if (lt) {
                    setMonth(lt.slice(0, 7));
                    setSelectedDates([lt]);
                }
            })
            .catch(() => { /* the dashboard still loads with the backend default (latest date) */ });
        cachedGet('/business/filter-options')
            .then(res => { if (!cancelled) setOptions(res.data || {}); })
            .catch(() => {});
        return () => { cancelled = true; };
    }, [tenantVersion]);

    /* Month change -> load that month's actual data dates for the pill row. */
    useEffect(() => {
        if (!month) { setMonthDates([]); return; }
        let cancelled = false;
        api.get('/business/executive-daily-merchant/calendar', { params: { month } })
            .then(res => { if (!cancelled) setMonthDates(res.data?.dates || []); })
            .catch(() => { if (!cancelled) setMonthDates([]); });
        return () => { cancelled = true; };
    }, [month, tenantVersion]);

    /* Debounced search */
    useEffect(() => {
        clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(() => { setQuery(search.trim()); setPage(0); }, 350);
        return () => clearTimeout(debounceRef.current);
    }, [search]);

    /* Selected pills win; none selected = the whole month; before the calendar
       loads, no param at all lets the backend default to the latest date. */
    const dateParams = useMemo(() => {
        if (selectedDates.length) return { dates: selectedDates.join(',') };
        if (month) return { month };
        return {};
    }, [selectedDates, month]);

    const load = useCallback(async (signal) => {
        setLoading(true); setError(null);
        try {
            const res = await api.post('/business/executive-daily-merchant', filters, {
                signal,
                params: {
                    ...dateParams, page, size: pageSize,
                    sort, dir, search: query || undefined,
                },
            });
            setData(res.data);
        } catch (e) {
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            setError(e?.response?.data?.message || 'Failed to load dashboard');
        } finally {
            setLoading(false);
        }
    }, [filters, dateParams, page, pageSize, sort, dir, query]);

    useEffect(() => {
        const ac = new AbortController();
        load(ac.signal);
        return () => ac.abort();
    }, [load, tenantVersion]);

    // Tenant switch: back to page 0 AND back to "latest for this tenant" — the
    // previous tenant's month may not even exist in the new tenant's data.
    // (The calendar effect above re-runs on tenantVersion and re-seeds month +
    // selection from the new tenant's latest date.)
    useEffect(() => {
        setPage(0); setMonth(''); setSelectedDates([]); setDetailRow(null);
    }, [tenantVersion]);

    /* Every pill is a toggle — one date, several dates, or none (whole month). */
    const toggleDate = (iso) => {
        setSelectedDates(l => l.includes(iso) ? l.filter(d => d !== iso) : [...l, iso].sort());
        setPage(0); setDetailRow(null);
    };
    const pickMonth = (ym) => {
        if (!ym || ym === month) return;
        setMonth(ym);
        setSelectedDates([]);   // whole month until the user narrows it
        setPage(0); setDetailRow(null);
    };
    const selectAllDays = () => { setSelectedDates([]); setPage(0); };
    const setFilter = (key, values) => {
        setFilters(f => ({ ...f, [key]: values }));
        setPage(0);
    };
    const clearFilters = () => { setFilters(EMPTY_FILTERS()); setPage(0); }; // date survives, per spec

    const onSort = (key) => {
        const col = COLUMNS.find(c => c.key === key);
        if (!col?.sortable) return;
        if (sort === key) setDir(d => (d === 'desc' ? 'asc' : 'desc'));
        else { setSort(key); setDir('desc'); }
        setPage(0);
    };

    const activeChips = useMemo(() => {
        const chips = [];
        FILTER_DEFS.forEach(({ key }) => (filters[key] || []).forEach(v =>
            chips.push({ key, value: v, label: `${CHIP_LABELS[key]}: ${v}` })));
        return chips;
    }, [filters]);

    const exportCsv = async () => {
        setExporting(true);
        try {
            const res = await api.post('/business/executive-daily-merchant', filters, {
                params: { ...dateParams, sort, dir, search: query || undefined, export: true },
            });
            const rows = res.data?.content || [];
            const totals = res.data?.totals;
            const bd = res.data?.selection || res.data?.businessDate || date;
            // Excel evaluates unquoted =,+,-,@ leads; names come from ingested
            // master data, so force text with a leading apostrophe.
            const esc = (v) => {
                const s = String(v ?? '');
                return `"${(/^[=+\-@]/.test(s) ? `'${s}` : s).replace(/"/g, '""')}"`;
            };
            const msfDp = Math.max(4, dp);
            const lines = [
                `Currency,${currencyCode || currencySymbol || 'UNKNOWN'}`,
                `Business Date,${res.data?.month ? res.data.month + ' (full month)'
                    : (res.data?.dates || []).join(' ') || bd || ''}`,
                ['SID', 'MID', 'Name', 'Vol', 'Count', 'MSF', 'ICF', 'SF', 'PG', 'NM'].join(','),
            ];
            rows.forEach(r => lines.push([
                esc(r.sid), esc(r.mid), esc(r.merchantName),
                num(r.volume).toFixed(dp), num(r.count),
                num(r.msf).toFixed(msfDp), num(r.icf).toFixed(dp),
                num(r.sf).toFixed(dp), num(r.pg).toFixed(dp), num(r.nm).toFixed(dp),
            ].join(',')));
            // Server-side day total as a trailing row so the file self-verifies.
            if (totals) {
                lines.push([
                    esc('TOTAL'), esc(''), esc(`${rows.length} rows (day total)`),
                    num(totals.volume).toFixed(dp), num(totals.count),
                    num(totals.msf).toFixed(msfDp), num(totals.icf).toFixed(dp),
                    num(totals.sf).toFixed(dp), num(totals.pg).toFixed(dp),
                    num(totals.nm).toFixed(dp),
                ].join(','));
            }
            const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `daily-merchant-${String(bd || 'latest').replace(/[^0-9A-Za-z-]+/g, '_')}.csv`;
            a.click();
            URL.revokeObjectURL(a.href);
        } catch (e) {
            showToast(
                e?.response?.status === 429
                    ? 'Export throttled — too many requests. Try again shortly.'
                    : 'Export failed. Please try again.',
                'error', 5000);
        } finally {
            setExporting(false);
        }
    };

    const rows = data?.content || [];
    const totals = data?.totals;
    const totalRows = num(data?.totalElements);
    const totalPages = Math.max(1, Math.ceil(totalRows / pageSize));
    const latestMonth = latest ? latest.slice(0, 7) : '';
    const behindLatest = latest && month && month !== latestMonth;

    /* Human summary of the current date selection for the header. */
    const selectionText = selectedDates.length
        ? (selectedDates.length === 1 ? longDate(selectedDates[0])
            : selectedDates.map(pillLabel).join(', '))
        : month ? `${monthLabel(month)} — all days`
        : data?.businessDate ? longDate(data.businessDate) : '—';

    /* Only months that actually hold data (from the calendar endpoint). */
    const monthOptions = useMemo(
        () => months.map(m => ({ value: m, label: monthLabel(m) })), [months]);

    const pillActive = (d) => selectedDates.includes(d);

    /* NM presentation: colour + explicit glyph, never colour alone. */
    const NmCell = ({ v, bold = false }) => {
        const n = num(v);
        const pos = n >= 0;
        return (
            <span style={{
                display: 'inline-flex', alignItems: 'center', gap: 4, justifyContent: 'flex-end',
                padding: '2px 8px', borderRadius: 6, fontWeight: bold ? 700 : 600,
                fontVariantNumeric: 'tabular-nums',
                color: pos ? 'var(--success-text, #0B6B4D)' : 'var(--danger-text, #B3382C)',
                background: pos ? 'var(--success-bg, rgba(5,150,105,0.08))'
                                : 'var(--danger-bg, rgba(220,38,38,0.08))',
            }} title={fullNum(v, currencySymbol)}>
                {pos ? <TrendingUp size={11} strokeWidth={2.4} /> : <TrendingDown size={11} strokeWidth={2.4} />}
                {money(n)}
            </span>
        );
    };

    return (
        <div style={{ padding: '24px 28px', width: '100%', maxWidth: '100%', margin: 0, boxSizing: 'border-box' }}>
            <style>{`
                .edm-table tbody tr { transition: background .12s ease; cursor: pointer; }
                .edm-table tbody tr:hover { background: var(--bg-hover, rgba(148,163,184,0.07)); }
                .edm-table thead th { position: sticky; top: 0; z-index: 2;
                    background: var(--bg-subtle, #f8fafc); }
                /* Sticky identifier columns (left-pinned during horizontal scroll).
                   Widths are fixed so the left offsets line up. */
                .edm-table .edm-c1 { position: sticky; left: 0;    min-width: 96px;  max-width: 96px; }
                .edm-table .edm-c2 { position: sticky; left: 96px; min-width: 116px; max-width: 116px; }
                .edm-table .edm-c3 { position: sticky; left: 212px; min-width: 200px; max-width: 240px;
                    box-shadow: 4px 0 6px -4px rgba(15,23,42,0.12); }
                .edm-table thead .edm-c1, .edm-table thead .edm-c2, .edm-table thead .edm-c3 { z-index: 3; }
                .edm-table tbody .edm-c1, .edm-table tbody .edm-c2, .edm-table tbody .edm-c3 {
                    background: var(--bg-card, #fff); z-index: 1; }
                @media (max-width: 900px) {
                    /* Small screens: unpin Name so the pinned block doesn't eat the viewport */
                    .edm-table .edm-c3 { position: static; box-shadow: none; min-width: 160px; }
                }
            `}</style>

            {/* ── Header ── */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                flexWrap: 'wrap', gap: 14, marginBottom: 14 }}>
                <div>
                    <div style={{ fontFamily: 'ui-monospace, monospace', fontSize: 10.5,
                        letterSpacing: '0.2em', textTransform: 'uppercase',
                        color: 'var(--text-muted, #94a3b8)' }}>
                        Executive · Daily
                    </div>
                    <h1 style={{ margin: '3px 0 0', fontSize: 22, fontWeight: 700, color: 'var(--text)',
                        letterSpacing: '-0.01em' }}>
                        Daily Merchant Dashboard
                    </h1>
                    <div style={{ marginTop: 4, fontSize: 12.5, color: 'var(--text-secondary)',
                        display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                        <CalendarDays size={13} />
                        {selectionText}
                        <span style={{ color: 'var(--border)' }}>·</span>
                        Daily merchant volume, transaction and profitability performance
                        <span style={{ color: 'var(--border)' }}>·</span>
                        {currencyCode || currencySymbol || ''}
                    </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                    <button onClick={exportCsv} disabled={exporting || !rows.length} style={{
                        display: 'flex', alignItems: 'center', gap: 6,
                        border: '1px solid var(--border)', background: 'var(--bg-card)',
                        borderRadius: 10, padding: '8px 14px', fontSize: 13, fontWeight: 600,
                        cursor: exporting || !rows.length ? 'default' : 'pointer',
                        color: 'var(--text-secondary)', opacity: exporting ? 0.6 : 1,
                    }}>
                        <Download size={14} /> {exporting ? 'Exporting…' : 'Export'}
                    </button>
                    {/* () => load() — load's first arg is an AbortSignal */}
                    <button onClick={() => load()} title="Refresh" style={{
                        border: '1px solid var(--border)', background: 'var(--bg-card)',
                        borderRadius: 10, padding: 8, cursor: 'pointer',
                        color: 'var(--text-secondary)', display: 'flex',
                    }}>
                        <RefreshCw size={15} className={loading ? 'animate-spin' : ''} />
                    </button>
                </div>
            </div>

            {/* ── Business date: month select drives the pill row; every pill is
                a toggle (one day, several days, or none = whole month) ── */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
                <span style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: '0.08em',
                    textTransform: 'uppercase', color: 'var(--text-muted, #94a3b8)' }}>Month</span>
                <select value={month} onChange={e => pickMonth(e.target.value)}
                    aria-label="Select month"
                    style={{
                        padding: '7px 10px', fontSize: 12.5, fontWeight: 600, borderRadius: 9,
                        background: 'var(--bg-card)', color: 'var(--text)',
                        border: '1px solid var(--border)', cursor: 'pointer', outline: 'none',
                    }}>
                    {!month && <option value="">—</option>}
                    {monthOptions.map(o => (
                        <option key={o.value} value={o.value}
                            style={{ background: 'var(--bg-card)', color: 'var(--text)' }}>{o.label}</option>
                    ))}
                </select>
                <span style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: '0.08em',
                    textTransform: 'uppercase', color: 'var(--text-muted, #94a3b8)' }}>Dates</span>
                {monthDates.length > 0 ? (
                    <div style={{ display: 'inline-flex', background: 'var(--bg-card)',
                        border: '1px solid var(--border)', borderRadius: 10, padding: 3, flexWrap: 'wrap' }}>
                        {/* All days = no pill selected */}
                        <button onClick={selectAllDays} title="Show the whole month" style={{
                            border: 'none', cursor: 'pointer', borderRadius: 8,
                            padding: '6px 13px', fontSize: 12.5, fontWeight: 600, whiteSpace: 'nowrap',
                            background: selectedDates.length === 0 ? 'var(--primary, #3b82f6)' : 'transparent',
                            color: selectedDates.length === 0 ? '#fff' : 'var(--text-secondary)',
                            transition: 'background .15s, color .15s',
                        }}>All days</button>
                        {monthDates.map(d => (
                            <button key={d} onClick={() => toggleDate(d)}
                                title="Click to select / deselect — combine as many days as you need"
                                style={{
                                    border: 'none', cursor: 'pointer', borderRadius: 8,
                                    padding: '6px 11px', fontSize: 12.5, fontWeight: 600, whiteSpace: 'nowrap',
                                    background: pillActive(d) ? 'var(--primary, #3b82f6)' : 'transparent',
                                    color: pillActive(d) ? '#fff' : 'var(--text-secondary)',
                                    transition: 'background .15s, color .15s',
                                }}>{pillLabel(d)}</button>
                        ))}
                    </div>
                ) : (
                    <span style={{ fontSize: 12, color: 'var(--text-muted, #94a3b8)' }}>
                        {month ? 'No data in this month' : 'Loading…'}
                    </span>
                )}
                {behindLatest && (
                    <button onClick={() => { pickMonth(latestMonth); setSelectedDates([latest]); }} style={{
                        display: 'flex', alignItems: 'center', gap: 5,
                        border: '1px dashed var(--warning, #8C5E12)', background: 'var(--warning-bg, rgba(140,94,18,0.08))',
                        color: 'var(--warning-text, #8C5E12)', borderRadius: 9,
                        padding: '6px 11px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer',
                    }}>
                        Latest data: {pillLabel(latest)} — jump
                    </button>
                )}
            </div>

            {/* ── Filter bar + search ── */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
                <span style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: '0.08em',
                    textTransform: 'uppercase', color: 'var(--text-muted, #94a3b8)' }}>Filters</span>
                {FILTER_DEFS.map(({ key, label, optKey }) => (
                    <FilterSelect key={key} label={label}
                        options={options?.[optKey] || []}
                        selected={filters[key] || []}
                        onChange={vals => setFilter(key, vals)} />
                ))}
                <div style={{ position: 'relative', marginLeft: 'auto' }}>
                    <Search size={14} style={{ position: 'absolute', left: 10, top: '50%',
                        transform: 'translateY(-50%)', color: 'var(--text-secondary)' }} />
                    <input value={search} onChange={e => setSearch(e.target.value)}
                        placeholder="Search SID, MID or Merchant Name"
                        style={{
                            padding: '8px 12px 8px 30px', fontSize: 13, width: 230,
                            background: 'var(--bg-card)', border: '1px solid var(--border)',
                            borderRadius: 10, color: 'var(--text)', outline: 'none',
                        }} />
                </div>
            </div>

            {/* ── Active filter chips ── */}
            {activeChips.length > 0 && (
                <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, marginBottom: 12 }}>
                    {activeChips.map((c, i) => (
                        <span key={`${c.key}-${c.value}-${i}`} style={{
                            display: 'inline-flex', alignItems: 'center', gap: 5,
                            padding: '3px 10px', borderRadius: 8, fontSize: 11, fontWeight: 600,
                            background: 'var(--wash, rgba(59,130,246,0.08))', color: 'var(--primary, #3b82f6)',
                        }}>
                            {c.label}
                            <button onClick={() => setFilter(c.key, filters[c.key].filter(v => v !== c.value))}
                                aria-label={`Remove ${c.label}`}
                                style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0,
                                    display: 'flex', opacity: 0.75 }}>
                                <X size={11} color="var(--primary, #3b82f6)" />
                            </button>
                        </span>
                    ))}
                    <button onClick={clearFilters} style={{
                        fontSize: 11, color: 'var(--text-muted, #94a3b8)', textDecoration: 'underline',
                        border: 'none', background: 'none', cursor: 'pointer', fontWeight: 500,
                    }}>
                        Clear All Filters
                    </button>
                </div>
            )}

            {loading ? <SkeletonLoader variant="table" rows={10} cols={10} /> : error ? (
                <EmptyState title="Could not load dashboard" message={error}
                    action={{ label: 'Retry', onClick: () => load() }} />
            ) : !rows.length ? (
                totalRows > 0 ? (
                    <EmptyState title="Page out of range"
                        message={`This page is past the end of ${totalRows.toLocaleString()} result${totalRows === 1 ? '' : 's'}.`}
                        action={{ label: 'Back to first page', onClick: () => setPage(0) }} />
                ) : (
                    <EmptyState variant="table" title="No merchant activity"
                        message={selectionText !== '—'
                            ? `No records match the selected filters for ${selectionText}.`
                            : 'No daily data has been loaded yet for this tenant.'} />
                )
            ) : (
                <>
                    {/* ── Daily KPI totals (same filtered set as the table) ── */}
                    {totals && (
                        <div style={{
                            background: 'var(--bg-card)', border: '1px solid var(--border)',
                            borderRadius: 14, marginBottom: 14, overflow: 'hidden',
                            boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))',
                            display: 'grid',
                            gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                        }}>
                            {[
                                { icon: Wallet,   label: 'Vol',   value: fmt.currency(num(totals.volume)), title: fullNum(totals.volume, currencySymbol) },
                                { icon: Layers,   label: 'Count', value: num(totals.count).toLocaleString(), caption: `${totalRows.toLocaleString()} merchants` },
                                { icon: Receipt,  label: 'MSF',   value: fmt.currency(num(totals.msf)), title: formatMsf(totals.msf, currencySymbol) },
                                { icon: Landmark, label: 'ICF',   value: fmt.currency(num(totals.icf)), caption: 'interchange', title: fullNum(totals.icf, currencySymbol) },
                                { icon: Globe2,   label: 'SF',    value: fmt.currency(num(totals.sf)), caption: 'scheme fees', title: fullNum(totals.sf, currencySymbol) },
                                { icon: CreditCard, label: 'PG',  value: fmt.currency(num(totals.pg)), caption: 'gateway fees', title: fullNum(totals.pg, currencySymbol) },
                                { icon: Coins,    label: 'NM',    value: fmt.currency(num(totals.nm)), caption: 'MSF − ICF − SF − PG',
                                  tone: num(totals.nm) >= 0 ? 'success' : 'danger', title: fullNum(totals.nm, currencySymbol) },
                            ].map((t, i, arr) => (
                                <div key={t.label} style={i < arr.length - 1
                                    ? { borderRight: '1px solid var(--border-light, var(--border))' } : undefined}>
                                    <StatTile {...t} />
                                </div>
                            ))}
                        </div>
                    )}

                    {/* ── Merchant table ── */}
                    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
                        borderRadius: 14, overflow: 'hidden',
                        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))' }}>
                        <div style={{ overflowX: 'auto', maxHeight: '64vh', overflowY: 'auto' }}>
                            <table className="edm-table" style={{ width: '100%', borderCollapse: 'separate',
                                borderSpacing: 0, fontSize: 13 }}>
                                <thead>
                                    <tr>
                                        {COLUMNS.map(c => {
                                            const active = sort === c.key;
                                            return (
                                                <th key={c.key} onClick={() => onSort(c.key)}
                                                    className={c.sticky ? `edm-c${c.sticky}` : undefined}
                                                    aria-sort={active ? (dir === 'desc' ? 'descending' : 'ascending') : undefined}
                                                    style={{
                                                        textAlign: c.align, padding: '12px 14px',
                                                        fontSize: 11, fontWeight: 600,
                                                        letterSpacing: '0.06em', textTransform: 'uppercase',
                                                        color: active ? 'var(--text)' : 'var(--text-secondary)',
                                                        whiteSpace: 'nowrap',
                                                        borderBottom: '1px solid var(--border)',
                                                        cursor: 'pointer', userSelect: 'none',
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
                                        <tr key={`${r.mid}-${r.sid}-${i}`} onClick={() => setDetailRow(r)}>
                                            <td className="edm-c1" style={{ ...tdText, fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{r.sid || '—'}</td>
                                            <td className="edm-c2" style={{ ...tdText, fontFamily: 'ui-monospace, monospace', fontSize: 12.5 }}>{r.mid || '—'}</td>
                                            <td className="edm-c3" style={{ ...tdText, overflow: 'hidden',
                                                textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 }}
                                                title={r.merchantName}>{r.merchantName || '—'}</td>
                                            <td style={tdNum} title={fullNum(r.volume, currencySymbol)}>{money(r.volume)}</td>
                                            <td style={tdNum} title={fullNum(r.count)}>{num(r.count).toLocaleString()}</td>
                                            <td style={tdNum} title={formatMsf(r.msf, currencySymbol)}>{money(r.msf)}</td>
                                            <td style={tdNum} title={fullNum(r.icf, currencySymbol)}>{money(r.icf)}</td>
                                            <td style={tdNum} title={fullNum(r.sf, currencySymbol)}>{money(r.sf)}</td>
                                            <td style={tdNum} title={fullNum(r.pg, currencySymbol)}>{money(r.pg)}</td>
                                            <td style={tdNum}><NmCell v={r.nm} /></td>
                                        </tr>
                                    ))}
                                    {totals && (
                                        <tr style={{ background: 'var(--bg-hover, rgba(148,163,184,0.06))', cursor: 'default' }}
                                            onClick={e => e.stopPropagation()}>
                                            <td colSpan={3} className="edm-c1" style={{ padding: '12px 14px', fontWeight: 700,
                                                color: 'var(--text)', position: 'static' }}>
                                                {selectedDates.length === 1 ? 'Day Total' : selectedDates.length > 1 ? 'Selection Total' : 'Month Total'} · {totalRows.toLocaleString()} rows
                                            </td>
                                            <td style={tdTotal} title={fullNum(totals.volume, currencySymbol)}>{money(totals.volume)}</td>
                                            <td style={tdTotal} title={fullNum(totals.count)}>{num(totals.count).toLocaleString()}</td>
                                            <td style={tdTotal} title={formatMsf(totals.msf, currencySymbol)}>{money(totals.msf)}</td>
                                            <td style={tdTotal} title={fullNum(totals.icf, currencySymbol)}>{money(totals.icf)}</td>
                                            <td style={tdTotal} title={fullNum(totals.sf, currencySymbol)}>{money(totals.sf)}</td>
                                            <td style={tdTotal} title={fullNum(totals.pg, currencySymbol)}>{money(totals.pg)}</td>
                                            <td style={tdTotal}><NmCell v={totals.nm} bold /></td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>

                    {/* ── Pagination ── */}
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                        gap: 10, marginTop: 14, fontSize: 13, color: 'var(--text-secondary)', flexWrap: 'wrap' }}>
                        <span style={{ fontSize: 12.5 }}>
                            Showing {(page * pageSize + 1).toLocaleString()}–{Math.min((page + 1) * pageSize, totalRows).toLocaleString()} of {totalRows.toLocaleString()}
                        </span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5 }}>
                                Rows
                                <select value={pageSize}
                                    onChange={e => { setPageSize(Number(e.target.value)); setPage(0); }}
                                    style={{
                                        padding: '5px 8px', fontSize: 12.5, borderRadius: 8,
                                        background: 'var(--bg-card)', color: 'var(--text)',
                                        border: '1px solid var(--border)', cursor: 'pointer', outline: 'none',
                                    }}>
                                    {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
                                </select>
                            </label>
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

            {/* ── Merchant drilldown drawer (renders from the clicked row) ── */}
            <Drawer anchor="right" open={!!detailRow} onClose={() => setDetailRow(null)}
                PaperProps={{ sx: {
                    width: { xs: '100%', sm: 420 }, bgcolor: 'var(--bg-card)',
                    borderLeft: '1px solid var(--border)', backgroundImage: 'none',
                } }}>
                {detailRow && (
                    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
                        <div style={{ padding: '18px 20px', borderBottom: '1px solid var(--border)',
                            display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 10 }}>
                            <div style={{ minWidth: 0 }}>
                                <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text)',
                                    overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                    {detailRow.merchantName || '—'}
                                </div>
                                <div style={{ marginTop: 3, fontFamily: 'ui-monospace, monospace', fontSize: 12,
                                    color: 'var(--text-secondary)' }}>
                                    MID {detailRow.mid || '—'} · SID {detailRow.sid || '—'}
                                </div>
                                <div style={{ marginTop: 3, fontSize: 12, color: 'var(--text-muted, #94a3b8)' }}>
                                    {selectionText !== '—' ? selectionText : ''}
                                </div>
                            </div>
                            <IconButton size="small" onClick={() => setDetailRow(null)}
                                sx={{ color: 'var(--text-secondary)' }} aria-label="Close">
                                <X size={16} />
                            </IconButton>
                        </div>

                        <div style={{ padding: 20, flex: 1, overflowY: 'auto' }}>
                            <div style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: '0.08em',
                                textTransform: 'uppercase', color: 'var(--text-muted, #94a3b8)', marginBottom: 10 }}>
                                Day performance · {currencyCode || currencySymbol || ''}
                            </div>
                            <div style={{ border: '1px solid var(--border)', borderRadius: 10, overflow: 'hidden' }}>
                                {[
                                    ['Vol',   money(detailRow.volume)],
                                    ['Count', num(detailRow.count).toLocaleString()],
                                    ['MSF',   money(detailRow.msf)],
                                    ['ICF',   money(detailRow.icf)],
                                    ['SF',    money(detailRow.sf)],
                                    ['PG',    money(detailRow.pg)],
                                ].map(([k, v], i) => (
                                    <div key={k} style={{
                                        display: 'flex', justifyContent: 'space-between', padding: '10px 14px',
                                        borderTop: i ? '1px solid var(--border-light, var(--border))' : 'none',
                                        fontSize: 13,
                                    }}>
                                        <span style={{ color: 'var(--text-secondary)' }}>{k}</span>
                                        <span style={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600,
                                            color: 'var(--text)' }}>{v}</span>
                                    </div>
                                ))}
                                <div style={{
                                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                                    padding: '10px 14px', borderTop: '1px solid var(--border)', fontSize: 13,
                                }}>
                                    <span style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>NM</span>
                                    <NmCell v={detailRow.nm} bold />
                                </div>
                            </div>
                            <div style={{ marginTop: 10, fontSize: 11.5, color: 'var(--text-muted, #94a3b8)' }}>
                                NM = MSF − ICF − SF − PG (settlement currency).
                            </div>
                        </div>

                        <div style={{ padding: '14px 20px', borderTop: '1px solid var(--border)' }}>
                            <button onClick={() => navigate('/merchant/universe')} style={{
                                display: 'flex', alignItems: 'center', gap: 6, width: '100%',
                                justifyContent: 'center',
                                border: '1px solid var(--border)', background: 'var(--bg-card)',
                                borderRadius: 10, padding: '9px 14px', fontSize: 13, fontWeight: 600,
                                cursor: 'pointer', color: 'var(--text-secondary)',
                            }}>
                                <ExternalLink size={14} /> Open Merchant Universe
                            </button>
                        </div>
                    </div>
                )}
            </Drawer>
        </div>
    );
};

const tdText = { padding: '11px 14px', color: 'var(--text)',
    borderBottom: '1px solid var(--border-light, var(--border))' };
const tdNum = {
    padding: '11px 14px', textAlign: 'right', color: 'var(--text)',
    fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
    borderBottom: '1px solid var(--border-light, var(--border))',
};
const tdTotal = { ...tdNum, fontWeight: 700 };
const pagerBtn = (disabled) => ({
    border: '1px solid var(--border)', background: 'var(--bg-card)',
    borderRadius: 8, padding: 6, display: 'flex',
    cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.45 : 1,
    color: 'var(--text-secondary)',
});

export default DailyMerchantDashboard;
