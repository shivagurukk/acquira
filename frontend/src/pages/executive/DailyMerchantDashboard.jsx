import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Drawer, IconButton } from '@mui/material';
import {
    RefreshCw, Search, Download, ChevronLeft, ChevronRight,
    ChevronUp, ChevronDown, X, Check, ExternalLink,
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
   Executive Daily Merchant Dashboard — the acquiring day, read as a
   ledger: pick a month, pick one or many of its loaded business dates,
   and see per-(MID, SID) performance with the full fee stack.

   Two devices carry the page:
     · MONTH RIBBON — the date picker IS the month's volume chart. One
       bar per loaded day, height = that day's volume, a danger rule
       under any day that lost money. Click to include/exclude.
     · FEE RIBBON  — MSF decomposed into ICF / SF / PG / NM in one bar,
       because that decomposition is the business.

   Data: POST /api/business/executive-daily-merchant (sum_daily_full —
   summary read only, never fact_transaction). NM is the batch-computed
   total_net_revenue (MSF − ICF − SF − PG), never recomputed client-side.
   Derived ratios reuse the existing Acquira definitions: margin % =
   NM/Vol, MSF rate = MSF/Vol in bps, avg ticket = Vol/Count.

   Distinct page from /business/daily-dashboard (the month heat-grid).
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));
const fullNum = (v, sym = '') => {
    if (!sym) return Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 0 });
    const d = resolveDecimals();
    return sym + ' ' + Number(v || 0).toLocaleString('en-US',
        { minimumFractionDigits: d, maximumFractionDigits: d });
};

/* Fixed column set — never configurable, per the report contract. */
const COLUMNS = [
    { key: 'sid',    label: 'SID',   align: 'left',  sticky: 1 },
    { key: 'mid',    label: 'MID',   align: 'left',  sticky: 2 },
    { key: 'name',   label: 'Name',  align: 'left',  sticky: 3 },
    { key: 'volume', label: 'Vol',   align: 'right' },
    { key: 'count',  label: 'Count', align: 'right' },
    { key: 'msf',    label: 'MSF',   align: 'right' },
    // Fee columns carry their full names; they wrap to two lines so the column
    // stays as narrow as the figures it holds.
    { key: 'icf',    label: 'Interchange Fee',      align: 'right', wrap: true },
    { key: 'sf',     label: 'Scheme Fee',           align: 'right', wrap: true },
    { key: 'pg',     label: 'Payment Gateway Fee',  align: 'right', wrap: true },
    { key: 'nm',     label: 'NM',    align: 'right' },
];

/* Fee vocabulary — one source for the table, the fee ribbon and the export. */
const FEE_LABELS = {
    msf: 'MSF',
    icf: 'Interchange Fee',
    sf:  'Scheme Fee',
    pg:  'Payment Gateway Fee',
    nm:  'NM',
};

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
const dayNum = (iso) => (iso || '').slice(8, 10).replace(/^0/, '');
/* Gulf market week: Friday + Saturday are the weekend, Sunday opens the week. */
const WEEKEND_DAYS = [5, 6]; // getDay(): 5 = Friday, 6 = Saturday
const isWeekend = (iso) => {
    const d = parseDay(iso);
    return d ? WEEKEND_DAYS.includes(d.getDay()) : false;
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

/* ── Multi-select dropdown, drawn entirely from Meridian tokens (the shared
   components/MultiSelect.jsx is hardcoded to Tailwind blues). ── */
const FilterSelect = ({ label, options, selected, onChange }) => {
    const [open, setOpen] = useState(false);
    const [q, setQ] = useState('');
    const ref = useRef(null);

    useEffect(() => {
        const onDoc = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
        const onEsc = (e) => { if (e.key === 'Escape') setOpen(false); };
        document.addEventListener('mousedown', onDoc);
        document.addEventListener('keydown', onEsc);
        return () => {
            document.removeEventListener('mousedown', onDoc);
            document.removeEventListener('keydown', onEsc);
        };
    }, []);

    const shown = useMemo(() => {
        const list = options || [];
        if (!q.trim()) return list;
        const needle = q.trim().toLowerCase();
        return list.filter(o => String(o).toLowerCase().includes(needle));
    }, [options, q]);

    const toggle = (opt) =>
        onChange(selected.includes(opt) ? selected.filter(v => v !== opt) : [...selected, opt]);
    const active = selected.length > 0;

    // The value line reads like a report parameter: "All", the single choice,
    // or how many are combined.
    const valueText = !active ? 'All'
        : selected.length === 1 ? String(selected[0])
        : `${selected.length} selected`;

    return (
        <div ref={ref} style={{ position: 'relative', display: 'flex' }}>
            <button className={`edm-focus edm-fbtn${active ? ' edm-fbtn-on' : ''}`}
                onClick={() => setOpen(o => !o)}
                aria-expanded={open} aria-haspopup="listbox">
                <span className="edm-fbtn-label">{label}</span>
                <span className="edm-fbtn-value">
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 148 }}>
                        {valueText}
                    </span>
                    <ChevronDown size={13} style={{
                        flexShrink: 0, opacity: 0.7,
                        transition: 'transform .15s', transform: open ? 'rotate(180deg)' : 'none' }} />
                </span>
            </button>

            {open && (
                <div role="listbox" style={{
                    position: 'absolute', top: 'calc(100% + 5px)', left: 0, zIndex: 60,
                    minWidth: 224, maxWidth: 320, background: 'var(--bg-card)',
                    border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
                    boxShadow: 'var(--shadow-pop)', overflow: 'hidden',
                }}>
                    {(options?.length || 0) > 8 && (
                        <div style={{ padding: 8, borderBottom: '1px solid var(--border-light, var(--border))' }}>
                            <input autoFocus value={q} onChange={e => setQ(e.target.value)}
                                placeholder={`Search ${label.toLowerCase()}`}
                                style={{
                                    width: '100%', boxSizing: 'border-box', padding: '6px 9px',
                                    fontSize: 12, background: 'var(--bg-subtle, var(--bg))',
                                    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
                                    color: 'var(--text)', outline: 'none',
                                }} />
                        </div>
                    )}
                    <div style={{ maxHeight: 244, overflowY: 'auto', padding: '4px 0' }}>
                        {shown.map(opt => {
                            const on = selected.includes(opt);
                            return (
                                <div key={opt} role="option" aria-selected={on} onClick={() => toggle(opt)}
                                    className="edm-opt" style={{
                                        display: 'flex', alignItems: 'center', gap: 9,
                                        padding: '7px 12px', fontSize: 12.5, cursor: 'pointer',
                                        color: on ? 'var(--primary)' : 'var(--text)',
                                        fontWeight: on ? 600 : 400,
                                        background: on ? 'var(--wash)' : 'transparent',
                                    }}>
                                    <span style={{
                                        width: 14, height: 14, borderRadius: 3, flexShrink: 0,
                                        border: `1.5px solid ${on ? 'var(--primary)' : 'var(--border)'}`,
                                        background: on ? 'var(--primary)' : 'transparent',
                                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                                    }}>
                                        {on && <Check size={10} color="#fff" strokeWidth={3.2} />}
                                    </span>
                                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                        {String(opt)}
                                    </span>
                                </div>
                            );
                        })}
                        {!shown.length && (
                            <div style={{ padding: '14px 12px', fontSize: 12, color: 'var(--text-muted)', textAlign: 'center' }}>
                                Nothing matches
                            </div>
                        )}
                    </div>
                    {active && (
                        <button onClick={() => onChange([])} style={{
                            display: 'block', width: '100%', textAlign: 'left',
                            padding: '8px 12px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer',
                            color: 'var(--danger)', background: 'transparent',
                            border: 'none', borderTop: '1px solid var(--border-light, var(--border))',
                        }}>
                            Clear {label}
                        </button>
                    )}
                </div>
            )}
        </div>
    );
};

/* ── Metric: micro-label, mono value, one line of context ── */
const Metric = ({ label, value, sub, tone, title, wide = false }) => (
    <div title={title} style={{ padding: wide ? '15px 20px' : '13px 18px', minWidth: 0 }}>
        <div className="edm-eyebrow">{label}</div>
        <div style={{
            marginTop: 7, fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
            fontSize: wide ? 23 : 18, fontWeight: 600, letterSpacing: '-0.02em',
            color: tone === 'danger' ? 'var(--danger-text)'
                : tone === 'success' ? 'var(--success-text)' : 'var(--text)',
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>
            {value}
        </div>
        {sub && (
            <div style={{ marginTop: 3, fontSize: 11, color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                {sub}
            </div>
        )}
    </div>
);

/* ── Fee ribbon: MSF decomposed into what is paid away and what is kept.
   Segments are proportional to the gross fee pool; a loss is shown as a
   distinct overflow segment rather than a negative width. ── */
const FeeRibbon = ({ totals, money, share, compact = false }) => {
    const icf = num(totals?.icf), sf = num(totals?.sf), pg = num(totals?.pg), nm = num(totals?.nm);
    const pool = icf + sf + pg + Math.max(nm, 0);
    if (pool <= 0) return null;
    const segs = [
        { key: 'icf', label: FEE_LABELS.icf, value: icf, color: 'var(--mix-interchange)' },
        { key: 'sf',  label: FEE_LABELS.sf,  value: sf,  color: 'var(--mix-scheme)' },
        { key: 'pg',  label: FEE_LABELS.pg,  value: pg,  color: 'var(--mix-pg)' },
        ...(nm >= 0
            ? [{ key: 'nm', label: FEE_LABELS.nm, value: nm, color: 'var(--mix-margin)' }]
            : [{ key: 'loss', label: 'Loss', value: Math.abs(nm), color: 'var(--danger)', overflow: true }]),
    ];
    // The bar only draws components that exist; the legend lists all four, so a
    // zero fee reads as "none charged" instead of a missing part of the stack.
    const drawn = segs.filter(s => s.value > 0);

    return (
        <div>
            <div style={{
                display: 'flex', height: compact ? 8 : 10, borderRadius: 999,
                overflow: 'hidden', background: 'var(--border-light, var(--border))',
            }}>
                {drawn.map(s => (
                    <div key={s.key}
                        title={`${s.label} · ${money(s.value)} · ${share(s.value, pool)}`}
                        style={{
                            width: `${(s.value / pool) * 100}%`,
                            background: s.color,
                            opacity: s.overflow ? 0.9 : 1,
                            borderRight: '1px solid var(--bg-card)',
                        }} />
                ))}
            </div>
            <div style={{
                display: 'flex', flexWrap: 'wrap', gap: compact ? '4px 12px' : '5px 18px', marginTop: 9,
            }}>
                {segs.map(s => (
                    <span key={s.key} style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6,
                        fontSize: 11, color: 'var(--text-secondary)',
                        opacity: s.value > 0 ? 1 : 0.45,
                    }}>
                        <span style={{ width: 8, height: 8, borderRadius: 2, background: s.color, flexShrink: 0 }} />
                        <span style={{ fontWeight: 600, color: 'var(--text)' }}>{s.label}</span>
                        <span className="edm-num">{money(s.value)}</span>
                        <span style={{ opacity: 0.72 }}>{share(s.value, pool)}</span>
                    </span>
                ))}
            </div>
        </div>
    );
};

/* ── Mix strip: hue encodes the dimension, opacity encodes rank ── */
const MixStrip = ({ title, hue, rows, money, share }) => {
    const items = (rows || []).slice(0, 5);
    const total = (rows || []).reduce((a, r) => a + num(r.volume), 0);
    if (!items.length || total <= 0) return null;
    const rest = (rows || []).slice(5).reduce((a, r) => a + num(r.volume), 0);
    const max = num(items[0].volume) || 1;

    return (
        <div style={{ minWidth: 0 }}>
            <div className="edm-eyebrow" style={{ marginBottom: 10 }}>{title}</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
                {items.map((r, i) => (
                    <div key={r.label}>
                        <div style={{
                            display: 'flex', justifyContent: 'space-between', gap: 10,
                            fontSize: 11.5, marginBottom: 4,
                        }}>
                            <span style={{
                                color: 'var(--text)', fontWeight: 500,
                                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                            }} title={r.label}>{r.label}</span>
                            <span className="edm-num" style={{ color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                                {share(r.volume, total)}
                            </span>
                        </div>
                        <div style={{ height: 5, borderRadius: 999, background: 'var(--border-light, var(--border))' }}>
                            <div title={`${r.label} · ${money(r.volume)}`} style={{
                                width: `${Math.max((num(r.volume) / max) * 100, 1.5)}%`,
                                height: '100%', borderRadius: 999, background: hue,
                                opacity: 1 - i * 0.16,
                            }} />
                        </div>
                    </div>
                ))}
                {rest > 0 && (
                    <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                        + {(rows.length - 5)} more · {share(rest, total)}
                    </div>
                )}
            </div>
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

    // Month-driven date picker: pick a month -> its loaded dates become ribbon
    // bars. Any number can be on; NONE selected = the whole month.
    const [months, setMonths] = useState([]);
    const [latest, setLatest] = useState('');
    const [month, setMonth] = useState('');
    const [monthDates, setMonthDates] = useState([]);
    const [selectedDates, setSelectedDates] = useState([]);
    const [filters, setFilters] = useState(EMPTY_FILTERS);
    const [options, setOptions] = useState({});
    const [page, setPage] = useState(0);
    const [pageSize, setPageSize] = useState(50);
    const [sort, setSort] = useState('volume');
    const [dir, setDir] = useState('desc');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [exporting, setExporting] = useState(false);
    const [detailRow, setDetailRow] = useState(null);
    const [detailMix, setDetailMix] = useState(null);

    /* Money at tenant precision (BHD is 3dp — never assume 2). */
    const dp = resolveDecimals(currencyDecimals, currencyCode);
    const money = useCallback((v) => Number(v || 0).toLocaleString('en-US',
        { minimumFractionDigits: dp, maximumFractionDigits: dp }), [dp]);
    const share = useCallback((v, total) =>
        !total ? '—' : `${((num(v) / total) * 100).toFixed(1)}%`, []);

    /* ── Reference data: months that hold data (+ the latest date) ── */
    useEffect(() => {
        let cancelled = false;
        api.get('/business/executive-daily-merchant/calendar')
            .then(res => {
                if (cancelled) return;
                const ms = res.data?.months || [];
                const lt = res.data?.latest || '';
                setMonths(ms); setLatest(lt);
                if (lt) { setMonth(lt.slice(0, 7)); setSelectedDates([lt]); }
            })
            .catch(() => { /* the table still loads on the backend default */ });
        cachedGet('/business/filter-options')
            .then(res => { if (!cancelled) setOptions(res.data || {}); })
            .catch(() => {});
        return () => { cancelled = true; };
    }, [tenantVersion]);

    /* Month change -> that month's actual data dates become the ribbon. */
    useEffect(() => {
        if (!month) { setMonthDates([]); return; }
        let cancelled = false;
        api.get('/business/executive-daily-merchant/calendar', { params: { month } })
            .then(res => { if (!cancelled) setMonthDates(res.data?.dates || []); })
            .catch(() => { if (!cancelled) setMonthDates([]); });
        return () => { cancelled = true; };
    }, [month, tenantVersion]);

    /* Selected days win; none selected = the whole month. */
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
                params: { ...dateParams, page, size: pageSize, sort, dir },
            });
            setData(res.data);
        } catch (e) {
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            setError(e?.response?.data?.message || 'Could not load the dashboard.');
        } finally {
            setLoading(false);
        }
    }, [filters, dateParams, page, pageSize, sort, dir]);

    useEffect(() => {
        const ac = new AbortController();
        load(ac.signal);
        return () => ac.abort();
    }, [load, tenantVersion]);

    useEffect(() => {
        setPage(0); setMonth(''); setSelectedDates([]); setDetailRow(null);
    }, [tenantVersion]);

    /* Drilldown: pull the merchant's scheme / card / destination split. */
    useEffect(() => {
        if (!detailRow?.merchantId) { setDetailMix(null); return; }
        let cancelled = false;
        setDetailMix(null);
        api.post('/business/executive-daily-merchant/breakdown', filters, {
            params: { ...dateParams, merchantId: detailRow.merchantId },
        })
            .then(res => { if (!cancelled) setDetailMix(res.data?.mix || null); })
            .catch(() => { if (!cancelled) setDetailMix(null); });
        return () => { cancelled = true; };
    }, [detailRow, filters, dateParams]);

    const toggleDate = (iso) => {
        setSelectedDates(l => l.includes(iso) ? l.filter(d => d !== iso) : [...l, iso].sort());
        setPage(0); setDetailRow(null);
    };
    const pickMonth = (ym) => {
        if (!ym || ym === month) return;
        setMonth(ym); setSelectedDates([]); setPage(0); setDetailRow(null);
    };
    const selectAllDays = () => { setSelectedDates([]); setPage(0); setDetailRow(null); };

    /* ── Range selection on the ribbon ──────────────────────────────
       Three ways to grab many days at once:
         · DRAG across the bars — paints the range; starting the drag on an
           already-selected day erases instead (mouse only; touch keeps taps).
         · SHIFT-CLICK — extends from the last clicked day.
         · Quick picks — Last 7 / Weekdays / Weekends. */
    const dragRef = useRef(null);            // {start, end, mode} while painting
    const [dragRange, setDragRange] = useState(null); // [lo, hi] preview indices
    const lastClickRef = useRef(null);       // shift-click anchor index
    const suppressClickRef = useRef(false);  // a pointer gesture already handled the press

    const applyDates = (next) => {
        setSelectedDates([...new Set(next)].sort());
        setPage(0); setDetailRow(null);
    };

    /* Ranges run over ISO date strings (lexical order = chronological), so the
       calendar grid's gaps and lead-in blanks never confuse a sweep. */
    const handleDayPointerDown = (d, e) => {
        if (e.button !== 0) return;
        if (e.shiftKey && lastClickRef.current) {
            e.preventDefault();
            suppressClickRef.current = true;
            const [lo, hi] = [lastClickRef.current, d].sort();
            applyDates([...selectedDates, ...allMonthDays.filter(x => x >= lo && x <= hi)]);
            lastClickRef.current = d;
            return;
        }
        if (e.pointerType !== 'mouse') return;   // touch keeps plain taps
        e.preventDefault();
        suppressClickRef.current = true;
        dragRef.current = { start: d, end: d, mode: !selectedDates.includes(d) };
        setDragRange([d, d]);
        const dates = allMonthDays;              // frozen for this gesture
        window.addEventListener('pointerup', () => {
            const drag = dragRef.current;
            dragRef.current = null;
            setDragRange(null);
            if (!drag) return;
            const [lo, hi] = [drag.start, drag.end].sort();
            const range = dates.filter(x => x >= lo && x <= hi);
            setSelectedDates(l => {
                if (lo === hi) return l.includes(lo)
                    ? l.filter(x => x !== lo) : [...l, lo].sort();
                return drag.mode
                    ? [...new Set([...l, ...range])].sort()
                    : l.filter(x => !range.includes(x));
            });
            setPage(0); setDetailRow(null);
            lastClickRef.current = hi;
        }, { once: true });
    };

    const handleDayPointerEnter = (d) => {
        if (!dragRef.current) return;
        dragRef.current.end = d;
        setDragRange([dragRef.current.start, d].sort());
    };

    const handleDayClick = (d) => {
        if (suppressClickRef.current) { suppressClickRef.current = false; return; }
        toggleDate(d);   // keyboard activation and touch taps land here
        lastClickRef.current = d;
    };

    const setFilter = (key, values) => { setFilters(f => ({ ...f, [key]: values })); setPage(0); };
    const clearFilters = () => { setFilters(EMPTY_FILTERS()); setPage(0); };

    const onSort = (key) => {
        if (sort === key) setDir(d => (d === 'desc' ? 'asc' : 'desc'));
        else { setSort(key); setDir('desc'); }
        setPage(0);
    };

    const activeChips = useMemo(() => {
        const chips = [];
        FILTER_DEFS.forEach(({ key }) => (filters[key] || []).forEach(v =>
            chips.push({ key, value: v, label: `${CHIP_LABELS[key]} ${v}` })));
        return chips;
    }, [filters]);

    const exportCsv = async () => {
        setExporting(true);
        try {
            const res = await api.post('/business/executive-daily-merchant', filters, {
                params: { ...dateParams, sort, dir, export: true },
            });
            const rows = res.data?.content || [];
            const totals = res.data?.totals;
            const label = res.data?.selection || '';
            // Excel evaluates unquoted =,+,-,@ leads; merchant names come from
            // ingested master data, so force them to text.
            const esc = (v) => {
                const s = String(v ?? '');
                return `"${(/^[=+\-@]/.test(s) ? `'${s}` : s).replace(/"/g, '""')}"`;
            };
            const msfDp = Math.max(4, dp);
            const lines = [
                `Currency,${currencyCode || currencySymbol || 'UNKNOWN'}`,
                `Business Date,${res.data?.month ? res.data.month + ' (full month)'
                    : (res.data?.dates || []).join(' ') || label}`,
                ['SID', 'MID', 'Name', 'Vol', 'Count', FEE_LABELS.msf, FEE_LABELS.icf,
                    FEE_LABELS.sf, FEE_LABELS.pg, FEE_LABELS.nm].join(','),
            ];
            rows.forEach(r => lines.push([
                esc(r.sid), esc(r.mid), esc(r.merchantName),
                num(r.volume).toFixed(dp), num(r.count),
                num(r.msf).toFixed(msfDp), num(r.icf).toFixed(dp),
                num(r.sf).toFixed(dp), num(r.pg).toFixed(dp), num(r.nm).toFixed(dp),
            ].join(',')));
            // The server's own selection total, so the file verifies itself.
            if (totals) {
                lines.push([
                    esc('TOTAL'), esc(''), esc(`${rows.length} rows`),
                    num(totals.volume).toFixed(dp), num(totals.count),
                    num(totals.msf).toFixed(msfDp), num(totals.icf).toFixed(dp),
                    num(totals.sf).toFixed(dp), num(totals.pg).toFixed(dp),
                    num(totals.nm).toFixed(dp),
                ].join(','));
            }
            const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `daily-merchant-${String(label || 'latest').replace(/[^0-9A-Za-z-]+/g, '_')}.csv`;
            a.click();
            URL.revokeObjectURL(a.href);
        } catch (e) {
            showToast(
                e?.response?.status === 429
                    ? 'Export throttled. Try a shorter selection.'
                    : 'Export failed. Try again.',
                'error', 5000);
        } finally {
            setExporting(false);
        }
    };

    const rows = data?.content || [];
    const totals = data?.totals;
    const trend = data?.trend || [];
    const mix = data?.mix;
    const totalRows = num(data?.totalElements);
    const totalPages = Math.max(1, Math.ceil(totalRows / pageSize));
    const latestMonth = latest ? latest.slice(0, 7) : '';
    const behindLatest = latest && month && month !== latestMonth;

    const selectionText = selectedDates.length
        ? (selectedDates.length === 1 ? longDate(selectedDates[0])
            : `${selectedDates.length} days in ${monthLabel(month)}`)
        : month ? `${monthLabel(month)} · all days`
        : data?.businessDate ? longDate(data.businessDate) : '—';

    const monthOptions = useMemo(
        () => months.map(m => ({ value: m, label: monthLabel(m) })), [months]);

    /* Ribbon geometry: bar heights are a share of the month's biggest day, so
       the shape reads as the month's rhythm rather than absolute scale. */
    const trendByDate = useMemo(() => {
        const m = new Map();
        trend.forEach(t => m.set(t.date, t));
        return m;
    }, [trend]);
    const trendMax = useMemo(
        () => trend.reduce((a, t) => Math.max(a, num(t.volume)), 0) || 1, [trend]);

    /* Calendar weeks for the selected month — Sunday-first (Gulf week), so the
       Fri + Sat weekend lands on the last two columns. null = lead/tail blank. */
    const monthDateSet = useMemo(() => new Set(monthDates), [monthDates]);
    /* Every calendar day of the month — a day with no transactions is still a
       real business date, so it stays selectable and reports its own emptiness
       rather than being unclickable. */
    const allMonthDays = useMemo(() => {
        const m = /^(\d{4})-(\d{2})$/.exec(month || '');
        if (!m) return [];
        const daysIn = new Date(Number(m[1]), Number(m[2]), 0).getDate();
        return Array.from({ length: daysIn },
            (_, i) => `${m[1]}-${m[2]}-${String(i + 1).padStart(2, '0')}`);
    }, [month]);
    const calWeeks = useMemo(() => {
        const m = /^(\d{4})-(\d{2})$/.exec(month || '');
        if (!m) return [];
        const y = Number(m[1]), mo = Number(m[2]) - 1;
        const daysIn = new Date(y, mo + 1, 0).getDate();
        const cells = Array(new Date(y, mo, 1).getDay()).fill(null);
        for (let d = 1; d <= daysIn; d++)
            cells.push(`${m[1]}-${m[2]}-${String(d).padStart(2, '0')}`);
        while (cells.length % 7) cells.push(null);
        const weeks = [];
        for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
        return weeks;
    }, [month]);

    /* Derived ratios — existing Acquira definitions, computed from the server's
       own totals (never a second opinion on the fee maths). */
    const marginPct = totals && num(totals.volume) ? (num(totals.nm) / num(totals.volume)) * 100 : null;
    const msfBps = totals && num(totals.volume) ? (num(totals.msf) / num(totals.volume)) * 10000 : null;
    const avgTicket = totals && num(totals.count) ? num(totals.volume) / num(totals.count) : null;
    const costTotal = totals ? num(totals.icf) + num(totals.sf) + num(totals.pg) : 0;
    const costRatio = totals && num(totals.msf) ? (costTotal / num(totals.msf)) * 100 : null;
    const daysCovered = selectedDates.length || trend.length;

    /* Every selected day is one with no transactions loaded — say so plainly
       instead of blaming the filters. */
    const noTxnSelection = selectedDates.length > 0
        && selectedDates.every(d => !monthDateSet.has(d));

    /* In-cell volume bar is scaled to the biggest row on THIS page. */
    const pageMaxVolume = useMemo(
        () => rows.reduce((a, r) => Math.max(a, num(r.volume)), 0) || 1, [rows]);

    const NmCell = ({ v, bold = false }) => {
        const n = num(v);
        const pos = n >= 0;
        return (
            <span title={fullNum(v, currencySymbol)} style={{
                display: 'inline-flex', alignItems: 'center', gap: 5, justifyContent: 'flex-end',
                fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
                fontWeight: bold ? 700 : 600,
                color: pos ? 'var(--success-text)' : 'var(--danger-text)',
            }}>
                <span aria-hidden="true" style={{ fontSize: 10, opacity: 0.9 }}>{pos ? '▲' : '▼'}</span>
                {money(n)}
                <span className="edm-sr">{pos ? 'positive margin' : 'negative margin'}</span>
            </span>
        );
    };

    return (
        <div style={{ padding: '22px 26px 32px', width: '100%', maxWidth: '100%', boxSizing: 'border-box' }}>
            <style>{`
                .edm-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }

                /* ── Masthead: the page opens on the same navy the grid header and
                   rail already wear — one identity, carried at full weight. ── */
                /* The whole header block wears the navy; the bands inside are
                   translucent overlays on it. Two-class selector on purpose —
                   .edm-panel sets a white background later in this sheet and
                   would otherwise win on equal specificity. */
                .edm-panel.edm-hdrblock { background: var(--table-head-bg,
                        linear-gradient(135deg, #24386B 0%, #16264A 55%, #0A1426 100%));
                    border-color: transparent; overflow: visible; }
                .edm-mast { background: transparent;
                    padding: 20px 24px 18px; display: flex; justify-content: space-between;
                    align-items: flex-end; gap: 18px; flex-wrap: wrap; }
                /* Round the block's corners on the children (the section itself must
                   not clip — open dropdowns hang below it). */
                .edm-hdrblock > :first-child { border-radius: calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px) 0 0; }
                .edm-hdrblock > :last-child { border-radius: 0 0 calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px); }
                .edm-mast-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.18em; text-transform: uppercase;
                    color: var(--table-head-muted, #93A3C6); }
                .edm-mast h1 { margin: 8px 0 0; font-size: 26px; font-weight: 700;
                    letter-spacing: -0.025em; line-height: 1.08;
                    color: var(--table-head-text, #EEF3FC); }
                .edm-mast-sub { margin: 6px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .edm-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.22);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .edm-mast-btn:hover { background: rgba(255,255,255,0.14); }
                .edm-mast-btn:disabled { opacity: 0.5; cursor: default; }

                /* ── Command deck: the filter row is part of the navy header, not a
                   tray of light boxes under it. Cells are divided by hairlines —
                   one continuous instrument strip. ── */
                .edm-cmdbar { display: flex; align-items: stretch; flex-wrap: wrap;
                    background: rgba(0,0,0,0.20);
                    border-top: 1px solid rgba(255,255,255,0.13); }
                .edm-fbtn { position: relative; display: flex; flex-direction: column;
                    align-items: stretch; gap: 5px; min-width: 150px; flex: 1 1 150px;
                    padding: 12px 18px; text-align: left; cursor: pointer;
                    background: transparent; border: 0;
                    border-right: 1px solid rgba(255,255,255,0.10);
                    transition: background .12s ease; }
                .edm-fbtn:hover { background: rgba(255,255,255,0.07); }
                .edm-fbtn-on { background: rgba(255,255,255,0.09);
                    box-shadow: inset 0 -2px 0 var(--chart-4, #7191CE); }
                .edm-fbtn-label { font-family: var(--font-mono); font-size: 9px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase;
                    color: rgba(238,243,252,0.55); }
                .edm-fbtn-value { display: flex; align-items: center; justify-content: space-between;
                    gap: 10px; font-size: 13px; font-weight: 600; color: #EEF3FC;
                    white-space: nowrap; overflow: hidden; }
                .edm-fbtn-on .edm-fbtn-value { color: var(--chart-4, #8AA5E0); }
                .edm-cmdbar .edm-focus:focus-visible { outline: 2px solid #EEF3FC; outline-offset: -2px; }
                .edm-chipstrip { display: flex; flex-wrap: wrap; align-items: center; gap: 6px;
                    padding: 10px 18px; background: rgba(0,0,0,0.30);
                    border-top: 1px solid rgba(255,255,255,0.10); }
                .edm-chip { display: inline-flex; align-items: center; gap: 6px;
                    padding: 3px 10px; border-radius: var(--radius-pill, 999px);
                    font-size: 11px; font-weight: 600; color: #EEF3FC;
                    background: rgba(255,255,255,0.13); border: 1px solid rgba(255,255,255,0.16); }
                .edm-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .edm-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); }
                .edm-sr { position: absolute; width: 1px; height: 1px; overflow: hidden;
                    clip: rect(0 0 0 0); white-space: nowrap; }
                .edm-focus:focus-visible, .edm-cell:focus-visible, .edm-th:focus-visible {
                    outline: 2px solid var(--primary); outline-offset: 2px; }
                .edm-opt:hover { background: var(--bg-hover) !important; }

                /* ── Business-date calendar: a real month grid. Cell tint deepens
                   with that day's volume; the red baseline marks a loss day. ── */
                .edm-cal { display: grid; grid-template-columns: repeat(7, minmax(36px, 46px));
                    gap: 4px; user-select: none; -webkit-user-select: none; }
                .edm-cal-h { font-family: var(--font-mono); font-size: 9px; font-weight: 600;
                    letter-spacing: 0.1em; text-transform: uppercase; text-align: center;
                    color: var(--text-muted); padding-bottom: 2px; }
                .edm-cal-h-we { color: var(--chart-alt, #64748B); font-weight: 700; }
                .edm-cell { position: relative; height: 38px; border: 1px solid transparent;
                    border-radius: var(--radius-sm); background: var(--bg-subtle, var(--bg));
                    cursor: pointer; padding: 0; display: flex; align-items: center;
                    justify-content: center; transition: border-color .1s ease, background .12s ease; }
                .edm-cell:hover { border-color: var(--primary); }
                .edm-cell-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums;
                    font-size: 11.5px; font-weight: 600; color: var(--text-secondary); }
                /* Spelled-out fee headers wrap instead of stretching the column */
                .edm-th-wrap { white-space: normal !important; max-width: 96px;
                    line-height: 1.25; }
                .edm-cell-blank { background: transparent; border: 0; cursor: default; }
                /* Selectable, but visibly empty: no transactions were loaded that day. */
                .edm-cell-nodata { background: transparent;
                    border: 1px dashed var(--border-light, var(--border)); }
                .edm-cell-nodata .edm-cell-num { color: var(--text-muted); font-weight: 400; }
                .edm-cell-nodata:hover { border-color: var(--primary); border-style: solid; }
                .edm-cell-on { background: var(--primary) !important; border-color: var(--primary); }
                .edm-cell-on .edm-cell-num { color: #fff; }
                .edm-cell-drag { outline: 2px dashed var(--primary); outline-offset: 1px; }
                .edm-cell-loss { position: absolute; left: 5px; right: 5px; bottom: 3px;
                    height: 2.5px; border-radius: 2px; background: var(--danger); }
                .edm-cell-on .edm-cell-loss { background: #fff; opacity: 0.85; }

                /* ── Table ── */
                .edm-table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: 13px; }
                .edm-table thead th { position: sticky; top: 0; z-index: 2; background: var(--table-head-bg,
                    var(--bg-subtle)); color: var(--table-head-text, var(--text-secondary));
                    font-family: var(--font-mono); font-size: 10px; font-weight: 600;
                    letter-spacing: 0.1em; text-transform: uppercase; padding: 11px 14px;
                    white-space: nowrap; cursor: pointer; user-select: none;
                    box-shadow: inset 0 -2px 0 var(--table-head-edge, var(--border)); }
                .edm-table tbody tr { transition: background .1s ease; cursor: pointer; }
                .edm-table tbody tr:hover { background: var(--bg-hover); }
                .edm-table td { padding: 10px 14px; color: var(--text);
                    border-bottom: 1px solid var(--border-light, var(--border)); }
                .edm-table .edm-c1 { position: sticky; left: 0;     min-width: 98px;  max-width: 98px; }
                .edm-table .edm-c2 { position: sticky; left: 98px;  min-width: 118px; max-width: 118px; }
                .edm-table .edm-c3 { position: sticky; left: 216px; min-width: 210px; max-width: 250px;
                    box-shadow: 6px 0 8px -6px rgba(15,23,42,0.16); }
                .edm-table tbody .edm-c1, .edm-table tbody .edm-c2, .edm-table tbody .edm-c3 {
                    background: var(--bg-card); z-index: 1; }
                .edm-table tbody tr:hover .edm-c1, .edm-table tbody tr:hover .edm-c2,
                .edm-table tbody tr:hover .edm-c3 { background: var(--bg-hover); }
                .edm-table thead .edm-c1, .edm-table thead .edm-c2, .edm-table thead .edm-c3 { z-index: 3; }
                .edm-cell-num { text-align: right; font-family: var(--font-mono);
                    font-variant-numeric: tabular-nums; white-space: nowrap; }
                /* Volume carries a share bar behind the figure — magnitude at a glance,
                   scaled to the largest merchant on this page. */
                .edm-vol { position: relative; }
                .edm-vol-bar { position: absolute; right: 0; bottom: 3px; height: 3px;
                    border-radius: 999px; background: var(--cat-1); opacity: 0.5; }
                /* Totals stay pinned to the bottom of the scroll box — the figure
                   you are checking a row against should never scroll away. */
                .edm-total-row td { position: sticky; bottom: 0; z-index: 2;
                    background: var(--wash); font-weight: 700;
                    border-top: 1px solid var(--border); border-bottom: none; }

                @media (max-width: 900px) {
                    .edm-table .edm-c3 { position: static; box-shadow: none; min-width: 150px; }
                }
                @media (prefers-reduced-motion: reduce) {
                    .edm-cell, .edm-table tbody tr { transition: none; }
                }
            `}</style>

            {/* ── Masthead + command bar: one executive header block.
                NO overflow:hidden here — it would clip the filter dropdowns;
                the corner rounding is done per child instead. ── */}
            <section className="edm-panel edm-hdrblock" style={{ marginBottom: 12 }}>
                <div className="edm-mast">
                    <div>
                        <div className="edm-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <span style={{
                                width: 6, height: 6, borderRadius: '50%', background: 'var(--rail-fresh, #34B98A)',
                                boxShadow: '0 0 0 3px rgba(52,185,138,0.22)',
                            }} />
                            Executive · Daily ledger
                        </div>
                        <h1>Daily Merchant Dashboard</h1>
                        <p className="edm-mast-sub">
                            Daily merchant volume, transaction and profitability performance
                        </p>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                        <div style={{ textAlign: 'right', marginRight: 6 }}>
                            <div className="edm-mast-eyebrow">Showing</div>
                            <div className="edm-num" style={{
                                fontSize: 14, fontWeight: 600, marginTop: 4,
                                color: 'var(--table-head-text, #EEF3FC)',
                            }}>
                                {selectionText}
                            </div>
                        </div>
                        <button className="edm-focus edm-mast-btn" onClick={exportCsv}
                            disabled={exporting || !rows.length}>
                            <Download size={13} /> {exporting ? 'Exporting' : 'Export'}
                        </button>
                        {/* () => load() — load's first argument is an AbortSignal */}
                        <button className="edm-focus edm-mast-btn" onClick={() => load()}
                            title="Refresh" aria-label="Refresh" style={{ padding: '9px 11px' }}>
                            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
                        </button>
                    </div>
                </div>

                <div className="edm-cmdbar">
                    {FILTER_DEFS.map(({ key, label, optKey }) => (
                        <FilterSelect key={key} label={label}
                            options={options?.[optKey] || []}
                            selected={filters[key] || []}
                            onChange={vals => setFilter(key, vals)} />
                    ))}
                </div>

                {activeChips.length > 0 && (
                    <div className="edm-chipstrip">
                        {activeChips.map((c, i) => (
                            <span key={`${c.key}-${c.value}-${i}`} className="edm-chip">
                                {c.label}
                                <button onClick={() => setFilter(c.key, filters[c.key].filter(v => v !== c.value))}
                                    aria-label={`Remove ${c.label}`}
                                    style={{ background: 'none', border: 'none', cursor: 'pointer',
                                        padding: 0, display: 'flex', opacity: 0.8 }}>
                                    <X size={11} color="#EEF3FC" />
                                </button>
                            </span>
                        ))}
                        <button onClick={clearFilters} style={{
                            fontSize: 11, color: 'rgba(238,243,252,0.7)', textDecoration: 'underline',
                            border: 'none', background: 'none', cursor: 'pointer', fontWeight: 500,
                        }}>
                            Clear all filters
                        </button>
                    </div>
                )}
            </section>

            {/* ── Month ribbon: the picker IS the month's volume shape ── */}
            <section className="edm-panel" style={{ padding: '14px 18px 12px', marginBottom: 12 }}>
                <div style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    gap: 12, flexWrap: 'wrap', marginBottom: 12,
                }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                        <span className="edm-eyebrow">Business date</span>
                        {/* UI face, not mono: a month name is a word, and mono at this
                            size crowded the native select arrow. */}
                        <select className="edm-focus" value={month} onChange={e => pickMonth(e.target.value)}
                            aria-label="Select month"
                            style={{
                                padding: '6px 28px 6px 10px', minWidth: 124,
                                fontSize: 12.5, fontWeight: 600, fontFamily: 'var(--font-ui)',
                                borderRadius: 'var(--radius-sm)', background: 'var(--bg-card)',
                                color: 'var(--text)', border: '1px solid var(--border)',
                                cursor: 'pointer', outline: 'none',
                            }}>
                            {!month && <option value="">Select month</option>}
                            {monthOptions.map(o => (
                                <option key={o.value} value={o.value}>{o.label}</option>
                            ))}
                        </select>
                        <button className="edm-focus" onClick={selectAllDays}
                            aria-pressed={selectedDates.length === 0}
                            style={{
                                padding: '6px 11px', fontSize: 12, fontWeight: 600,
                                borderRadius: 'var(--radius-sm)', cursor: 'pointer',
                                background: selectedDates.length === 0 ? 'var(--primary)' : 'var(--bg-card)',
                                color: selectedDates.length === 0 ? '#fff' : 'var(--text-secondary)',
                                border: `1px solid ${selectedDates.length === 0 ? 'var(--primary)' : 'var(--border)'}`,
                            }}>
                            All days
                        </button>
                    </div>
                    {behindLatest && (
                        <button className="edm-focus"
                            onClick={() => { setMonth(latestMonth); setSelectedDates([latest]); setPage(0); }}
                            style={{
                                display: 'flex', alignItems: 'center', gap: 6,
                                border: '1px solid var(--warning)', background: 'var(--warning-bg)',
                                color: 'var(--warning-text)', borderRadius: 'var(--radius-sm)',
                                padding: '6px 11px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer',
                            }}>
                            Latest data is {pillLabel(latest)} — go there
                        </button>
                    )}
                </div>

                {calWeeks.length > 0 ? (
                    <div style={{ display: 'flex', gap: 28, flexWrap: 'wrap', alignItems: 'flex-start' }}>
                        {/* ── The calendar itself ── */}
                        <div className="edm-cal" role="grid" aria-label={`Business dates in ${monthLabel(month)}`}>
                            {['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map((h, i) => (
                                <span key={h} className={`edm-cal-h${i >= 5 ? ' edm-cal-h-we' : ''}`}>{h}</span>
                            ))}
                            {calWeeks.flat().map((d, idx) => {
                                if (!d) return <span key={`b${idx}`} className="edm-cell edm-cell-blank" />;
                                // Every day of the month is selectable. A day with no
                                // transactions is still a business date — it just reports
                                // an empty result, which is itself the answer.
                                const hasData = monthDateSet.has(d);
                                const picked = selectedDates.includes(d);
                                const dimmed = selectedDates.length > 0 && !picked;
                                const inDrag = dragRange && d >= dragRange[0] && d <= dragRange[1];
                                const t = trendByDate.get(d);
                                const vol = num(t?.volume);
                                const loss = hasData && t ? num(t.nm) < 0 : false;
                                // Heat: tint deepens with the day's share of the month's peak.
                                const heat = Math.round(8 + (vol / trendMax) * 40);
                                return (
                                    <button key={d}
                                        className={`edm-cell${picked ? ' edm-cell-on' : ''}${inDrag ? ' edm-cell-drag' : ''}${hasData ? '' : ' edm-cell-nodata'}`}
                                        style={!picked && hasData ? {
                                            background: `color-mix(in srgb, var(--cat-1) ${dimmed ? Math.max(heat - 6, 3) : heat}%, var(--bg-card))`,
                                        } : undefined}
                                        onClick={() => handleDayClick(d)}
                                        onPointerDown={e => handleDayPointerDown(d, e)}
                                        onPointerEnter={() => handleDayPointerEnter(d)}
                                        aria-pressed={picked}
                                        aria-label={`${longDate(d)}${isWeekend(d) ? ', weekend' : ''}${hasData ? (loss ? ', closed at a loss' : '') : ', no transactions'}`}
                                        title={hasData
                                            ? `${longDate(d)}${isWeekend(d) ? ' · weekend' : ''}\nVol ${money(vol)}\nTxns ${num(t?.count).toLocaleString()}\nNM ${money(num(t?.nm))}`
                                            : `${longDate(d)}${isWeekend(d) ? ' · weekend' : ''}\nNo transactions loaded`}>
                                        <span className="edm-cell-num"
                                            style={dimmed && hasData ? { color: 'var(--text-muted)', fontWeight: 400 } : undefined}>
                                            {dayNum(d)}
                                        </span>
                                        {loss && <span className="edm-cell-loss" aria-hidden="true" />}
                                    </button>
                                );
                            })}
                        </div>

                        {/* ── Selection rail ── */}
                        <div style={{
                            display: 'flex', flexDirection: 'column', gap: 12,
                            minWidth: 200, flex: '1 1 200px', maxWidth: 320, paddingTop: 2,
                        }}>
                            <div>
                                <div className="edm-eyebrow">Selection</div>
                                <div style={{
                                    marginTop: 5, fontSize: 13.5, fontWeight: 600, color: 'var(--text)',
                                }}>
                                    {selectedDates.length === 0 ? 'Whole month'
                                        : `${selectedDates.length} day${selectedDates.length === 1 ? '' : 's'}`}
                                </div>
                                <div style={{ marginTop: 3, fontSize: 11.5, color: 'var(--text-secondary)' }}>
                                    Click a day · drag to sweep a range · ⇧-click extends
                                </div>
                            </div>
                            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                                {[
                                    ['Weekdays', () => applyDates(allMonthDays.filter(d => !isWeekend(d)))],
                                    ['Weekends', () => applyDates(allMonthDays.filter(isWeekend))],
                                ].map(([lbl, fn]) => (
                                    <button key={lbl} className="edm-focus" onClick={fn}
                                        disabled={!allMonthDays.length}
                                        style={{
                                            padding: '6px 11px', fontSize: 12, fontWeight: 600,
                                            borderRadius: 'var(--radius-sm)',
                                            cursor: allMonthDays.length ? 'pointer' : 'default',
                                            background: 'var(--bg-card)', color: 'var(--text-secondary)',
                                            border: '1px solid var(--border)',
                                            opacity: allMonthDays.length ? 1 : 0.5,
                                        }}>
                                        {lbl}
                                    </button>
                                ))}
                            </div>
                            <div style={{
                                display: 'flex', flexDirection: 'column', gap: 6,
                                paddingTop: 10, borderTop: '1px solid var(--border-light, var(--border))',
                                fontSize: 10.5, color: 'var(--text-muted)',
                            }}>
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                                    <span style={{
                                        width: 10, height: 10, borderRadius: 2,
                                        background: 'color-mix(in srgb, var(--cat-1) 40%, var(--bg-card))',
                                        border: '1px solid var(--border-light, var(--border))',
                                    }} />
                                    deeper tint = higher volume that day
                                </span>
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                                    <span style={{ width: 10, height: 2.5, background: 'var(--danger)', borderRadius: 2 }} />
                                    day closed at a loss
                                </span>
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                                    <span style={{
                                        width: 10, height: 10, borderRadius: 2,
                                        border: '1px dashed var(--border)',
                                    }} />
                                    no transactions · <span style={{ color: 'var(--chart-alt, #64748B)', fontWeight: 700 }}>Fr Sa</span> = weekend
                                </span>
                            </div>
                        </div>
                    </div>
                ) : (
                    <div style={{ fontSize: 12, color: 'var(--text-muted)', padding: '18px 0' }}>
                        {month ? 'No business dates were loaded in this month.' : 'Loading business dates'}
                    </div>
                )}
            </section>

            {loading ? <SkeletonLoader variant="table" rows={10} cols={10} /> : error ? (
                <EmptyState title="Could not load the dashboard" message={error}
                    action={{ label: 'Try again', onClick: () => load() }} />
            ) : !rows.length ? (
                totalRows > 0 ? (
                    <EmptyState title="Page out of range"
                        message={`This page sits past the end of ${totalRows.toLocaleString()} result${totalRows === 1 ? '' : 's'}.`}
                        action={{ label: 'Back to first page', onClick: () => setPage(0) }} />
                ) : (
                    <EmptyState variant="table"
                        title={noTxnSelection ? 'No transactions on this date' : 'No merchant activity'}
                        message={noTxnSelection
                            ? `Nothing was processed on ${selectionText}. Pick another day, or use All days for the month.`
                            : selectionText !== '—'
                                ? `Nothing matches these filters for ${selectionText}. Widen the date selection or clear a filter.`
                                : 'No daily data has been loaded for this tenant yet.'} />
                )
            ) : (
                <>
                    {/* ── Ledger summary: headline figures, the fee ribbon, ratios ── */}
                    {totals && (
                        <section className="edm-panel" style={{ marginBottom: 12, overflow: 'hidden' }}>
                            <div style={{
                                display: 'grid',
                                gridTemplateColumns: 'repeat(auto-fit, minmax(168px, 1fr))',
                                borderBottom: '1px solid var(--border-light, var(--border))',
                            }}>
                                <div style={cellDiv}>
                                    <Metric wide label="Volume" value={fmt.currency(num(totals.volume))}
                                        sub={`${currencyCode || currencySymbol || ''} · ${daysCovered} day${daysCovered === 1 ? '' : 's'}`}
                                        title={fullNum(totals.volume, currencySymbol)} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="Transactions" value={num(totals.count).toLocaleString()}
                                        sub={`${totalRows.toLocaleString()} merchant row${totalRows === 1 ? '' : 's'}`} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="MSF" value={fmt.currency(num(totals.msf))}
                                        sub="gross fee income" title={formatMsf(totals.msf, currencySymbol)} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="Cost of sale" value={fmt.currency(costTotal)}
                                        sub="interchange + scheme + gateway"
                                        title={fullNum(costTotal, currencySymbol)} />
                                </div>
                                <div>
                                    <Metric wide label="Net margin" value={fmt.currency(num(totals.nm))}
                                        sub={marginPct == null ? 'no volume' : `${marginPct.toFixed(2)}% of volume`}
                                        tone={num(totals.nm) >= 0 ? 'success' : 'danger'}
                                        title={fullNum(totals.nm, currencySymbol)} />
                                </div>
                            </div>

                            <div style={{ padding: '15px 20px 16px' }}>
                                <div style={{
                                    display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 10,
                                    flexWrap: 'wrap',
                                }}>
                                    <span className="edm-eyebrow">Where the MSF went</span>
                                    <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                        every {currencyCode || 'unit'} of fee income, split into what was paid away and what was kept
                                    </span>
                                </div>
                                <FeeRibbon totals={totals} money={money} share={share} />
                            </div>

                            <div style={{
                                display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                                borderTop: '1px solid var(--border-light, var(--border))',
                            }}>
                                <div style={cellDiv}>
                                    <Metric label="Net margin rate"
                                        value={marginPct == null ? '—' : `${marginPct.toFixed(2)}%`}
                                        sub="NM ÷ volume"
                                        tone={marginPct == null ? undefined : marginPct >= 0 ? 'success' : 'danger'} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric label="MSF rate"
                                        value={msfBps == null ? '—' : `${msfBps.toFixed(1)} bps`}
                                        sub="MSF ÷ volume" />
                                </div>
                                <div style={cellDiv}>
                                    <Metric label="Cost ratio"
                                        value={costRatio == null ? '—' : `${costRatio.toFixed(1)}%`}
                                        sub="costs ÷ MSF" />
                                </div>
                                <div style={cellDiv}>
                                    <Metric label="Average ticket"
                                        value={avgTicket == null ? '—' : money(avgTicket)}
                                        sub="volume ÷ transactions" />
                                </div>
                                <div>
                                    <Metric label="Active merchants"
                                        value={totalRows.toLocaleString()}
                                        sub="rows in this selection" />
                                </div>
                            </div>
                        </section>
                    )}

                    {/* ── Mix: hue per dimension, opacity per rank ── */}
                    {mix && (mix.scheme?.length || mix.cardType?.length || mix.destination?.length) ? (
                        <section className="edm-panel" style={{
                            display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))',
                            gap: 26, padding: '16px 20px', marginBottom: 12,
                        }}>
                            <MixStrip title="Scheme" hue="var(--cat-1)" rows={mix.scheme} money={money} share={share} />
                            <MixStrip title="Card type" hue="var(--chart-4, #7191CE)" rows={mix.cardType} money={money} share={share} />
                            <MixStrip title="Destination" hue="var(--chart-alt, #64748B)" rows={mix.destination} money={money} share={share} />
                        </section>
                    ) : null}

                    {/* ── Merchant table ── */}
                    <div className="edm-panel" style={{ overflow: 'hidden' }}>
                        <div style={{ overflowX: 'auto', maxHeight: '62vh', overflowY: 'auto' }}>
                            <table className="edm-table">
                                <thead>
                                    <tr>
                                        {COLUMNS.map(c => {
                                            const active = sort === c.key;
                                            return (
                                                <th key={c.key}
                                                    className={`edm-th ${c.sticky ? `edm-c${c.sticky}` : ''}${c.wrap ? ' edm-th-wrap' : ''}`}
                                                    onClick={() => onSort(c.key)} tabIndex={0}
                                                    onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSort(c.key); } }}
                                                    aria-sort={active ? (dir === 'desc' ? 'descending' : 'ascending') : 'none'}
                                                    style={{ textAlign: c.align, color: active ? 'var(--table-head-text, var(--text))' : undefined }}>
                                                    {c.label}
                                                    {active && (dir === 'desc'
                                                        ? <ChevronDown size={11} style={{ verticalAlign: '-1px', marginLeft: 3 }} />
                                                        : <ChevronUp size={11} style={{ verticalAlign: '-1px', marginLeft: 3 }} />)}
                                                </th>
                                            );
                                        })}
                                    </tr>
                                </thead>
                                <tbody>
                                    {rows.map((r, i) => (
                                        <tr key={`${r.mid}-${r.sid}-${i}`} onClick={() => setDetailRow(r)}>
                                            <td className="edm-c1 edm-num" style={{ fontSize: 12.5 }}>{r.sid || '—'}</td>
                                            <td className="edm-c2 edm-num" style={{ fontSize: 12.5 }}>{r.mid || '—'}</td>
                                            <td className="edm-c3" style={{
                                                overflow: 'hidden', textOverflow: 'ellipsis',
                                                whiteSpace: 'nowrap', fontWeight: 500,
                                            }} title={r.merchantName}>{r.merchantName || '—'}</td>
                                            <td className="edm-cell-num edm-vol" title={fullNum(r.volume, currencySymbol)}>
                                                {money(r.volume)}
                                                <span className="edm-vol-bar" style={{
                                                    // max() keeps the width valid when the inset exceeds the share
                                                    width: `max(2px, calc(${Math.max((num(r.volume) / pageMaxVolume) * 100, 1).toFixed(1)}% - 28px))`,
                                                }} />
                                            </td>
                                            <td className="edm-cell-num" title={fullNum(r.count)}>{num(r.count).toLocaleString()}</td>
                                            <td className="edm-cell-num" title={formatMsf(r.msf, currencySymbol)}>{money(r.msf)}</td>
                                            <td className="edm-cell-num" title={fullNum(r.icf, currencySymbol)}>{money(r.icf)}</td>
                                            <td className="edm-cell-num" title={fullNum(r.sf, currencySymbol)}>{money(r.sf)}</td>
                                            <td className="edm-cell-num" title={fullNum(r.pg, currencySymbol)}>{money(r.pg)}</td>
                                            <td className="edm-cell-num"><NmCell v={r.nm} /></td>
                                        </tr>
                                    ))}
                                    {totals && (
                                        <tr className="edm-total-row" onClick={e => e.stopPropagation()}
                                            style={{ cursor: 'default' }}>
                                            {/* No edm-c1 here: its fixed 98px width would squash a 3-column span */}
                                            <td colSpan={3}>
                                                {selectedDates.length === 1 ? 'Day total'
                                                    : selectedDates.length > 1 ? 'Selection total' : 'Month total'}
                                                {' · '}{totalRows.toLocaleString()} rows
                                            </td>
                                            <td className="edm-cell-num" title={fullNum(totals.volume, currencySymbol)}>{money(totals.volume)}</td>
                                            <td className="edm-cell-num">{num(totals.count).toLocaleString()}</td>
                                            <td className="edm-cell-num" title={formatMsf(totals.msf, currencySymbol)}>{money(totals.msf)}</td>
                                            <td className="edm-cell-num" title={fullNum(totals.icf, currencySymbol)}>{money(totals.icf)}</td>
                                            <td className="edm-cell-num" title={fullNum(totals.sf, currencySymbol)}>{money(totals.sf)}</td>
                                            <td className="edm-cell-num" title={fullNum(totals.pg, currencySymbol)}>{money(totals.pg)}</td>
                                            <td className="edm-cell-num"><NmCell v={totals.nm} bold /></td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <div style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                        gap: 10, marginTop: 12, fontSize: 12.5, color: 'var(--text-secondary)',
                        flexWrap: 'wrap',
                    }}>
                        <span className="edm-num">
                            {(page * pageSize + 1).toLocaleString()}–{Math.min((page + 1) * pageSize, totalRows).toLocaleString()} of {totalRows.toLocaleString()}
                        </span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
                                Rows
                                <select className="edm-focus" value={pageSize}
                                    onChange={e => { setPageSize(Number(e.target.value)); setPage(0); }}
                                    style={{
                                        padding: '4px 7px', fontSize: 12, fontFamily: 'var(--font-mono)',
                                        borderRadius: 'var(--radius-sm)', background: 'var(--bg-card)',
                                        color: 'var(--text)', border: '1px solid var(--border)',
                                        cursor: 'pointer', outline: 'none',
                                    }}>
                                    {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
                                </select>
                            </label>
                            <span className="edm-num">Page {page + 1} / {totalPages}</span>
                            <button className="edm-focus" disabled={page === 0} onClick={() => setPage(p => p - 1)}
                                aria-label="Previous page" style={pagerBtn(page === 0)}>
                                <ChevronLeft size={15} />
                            </button>
                            <button className="edm-focus" disabled={page + 1 >= totalPages} onClick={() => setPage(p => p + 1)}
                                aria-label="Next page" style={pagerBtn(page + 1 >= totalPages)}>
                                <ChevronRight size={15} />
                            </button>
                        </div>
                    </div>
                </>
            )}

            {/* ── Merchant drilldown ────────────────────────────────── */}
            <Drawer anchor="right" open={!!detailRow} onClose={() => setDetailRow(null)}
                PaperProps={{ sx: {
                    width: { xs: '100%', sm: 440 }, bgcolor: 'var(--bg-card)',
                    borderLeft: '1px solid var(--border)', backgroundImage: 'none',
                } }}>
                {detailRow && (
                    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
                        <div style={{
                            padding: '18px 20px', borderBottom: '1px solid var(--border)',
                            display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 10,
                        }}>
                            <div style={{ minWidth: 0 }}>
                                <div className="edm-eyebrow">Merchant</div>
                                <div style={{
                                    marginTop: 5, fontSize: 16, fontWeight: 700, color: 'var(--text)',
                                    overflow: 'hidden', textOverflow: 'ellipsis',
                                }}>
                                    {detailRow.merchantName || '—'}
                                </div>
                                <div className="edm-num" style={{
                                    marginTop: 4, fontSize: 12, color: 'var(--text-secondary)',
                                }}>
                                    MID {detailRow.mid || '—'} · SID {detailRow.sid || '—'}
                                </div>
                                <div style={{ marginTop: 3, fontSize: 11.5, color: 'var(--text-muted)' }}>
                                    {selectionText !== '—' ? selectionText : ''}
                                </div>
                            </div>
                            <IconButton size="small" onClick={() => setDetailRow(null)}
                                sx={{ color: 'var(--text-secondary)' }} aria-label="Close">
                                <X size={16} />
                            </IconButton>
                        </div>

                        <div style={{ padding: 20, flex: 1, overflowY: 'auto' }}>
                            <div style={{
                                display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1,
                                background: 'var(--border-light, var(--border))',
                                border: '1px solid var(--border-light, var(--border))',
                                borderRadius: 'var(--radius-md)', overflow: 'hidden',
                            }}>
                                {[
                                    ['Volume', money(detailRow.volume)],
                                    ['Transactions', num(detailRow.count).toLocaleString()],
                                    ['MSF', money(detailRow.msf)],
                                    ['Average ticket', num(detailRow.count)
                                        ? money(num(detailRow.volume) / num(detailRow.count)) : '—'],
                                ].map(([k, v]) => (
                                    <div key={k} style={{ background: 'var(--bg-card)', padding: '11px 13px' }}>
                                        <div className="edm-eyebrow">{k}</div>
                                        <div className="edm-num" style={{
                                            marginTop: 5, fontSize: 14, fontWeight: 600, color: 'var(--text)',
                                        }}>{v}</div>
                                    </div>
                                ))}
                            </div>

                            <div style={{ marginTop: 20 }}>
                                <div className="edm-eyebrow" style={{ marginBottom: 10 }}>Fee stack</div>
                                <FeeRibbon totals={detailRow} money={money} share={share} compact />
                                <div style={{
                                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                                    marginTop: 14, paddingTop: 12,
                                    borderTop: '1px solid var(--border-light, var(--border))',
                                }}>
                                    <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                        Net margin
                                    </span>
                                    <NmCell v={detailRow.nm} bold />
                                </div>
                                <div style={{ marginTop: 6, fontSize: 11, color: 'var(--text-muted)' }}>
                                    NM = MSF − interchange fee − scheme fee − payment gateway fee,
                                    in settlement currency.
                                </div>
                            </div>

                            {detailMix ? (
                                <div style={{ marginTop: 22, display: 'flex', flexDirection: 'column', gap: 20 }}>
                                    <MixStrip title="Scheme" hue="var(--cat-1)" rows={detailMix.scheme} money={money} share={share} />
                                    <MixStrip title="Card type" hue="var(--chart-4, #7191CE)" rows={detailMix.cardType} money={money} share={share} />
                                    <MixStrip title="Destination" hue="var(--chart-alt, #64748B)" rows={detailMix.destination} money={money} share={share} />
                                </div>
                            ) : (
                                <div style={{ marginTop: 22, fontSize: 11.5, color: 'var(--text-muted)' }}>
                                    Loading the scheme, card and destination split
                                </div>
                            )}
                        </div>

                        <div style={{ padding: '13px 20px', borderTop: '1px solid var(--border)' }}>
                            <button className="edm-focus" onClick={() => navigate('/merchant/universe')}
                                style={{ ...btn(false), width: '100%', justifyContent: 'center' }}>
                                <ExternalLink size={13} /> Open in Merchant Universe
                            </button>
                        </div>
                    </div>
                )}
            </Drawer>
        </div>
    );
};

const cellDiv = { borderRight: '1px solid var(--border-light, var(--border))' };
const btn = (disabled) => ({
    display: 'flex', alignItems: 'center', gap: 6,
    border: '1px solid var(--border)', background: 'var(--bg-card)',
    borderRadius: 'var(--radius-sm)', padding: '7px 13px',
    fontSize: 12.5, fontWeight: 600,
    cursor: disabled ? 'default' : 'pointer',
    color: 'var(--text-secondary)', opacity: disabled ? 0.55 : 1,
});
const pagerBtn = (disabled) => ({
    border: '1px solid var(--border)', background: 'var(--bg-card)',
    borderRadius: 'var(--radius-sm)', padding: 5, display: 'flex',
    cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.45 : 1,
    color: 'var(--text-secondary)',
});

export default DailyMerchantDashboard;
