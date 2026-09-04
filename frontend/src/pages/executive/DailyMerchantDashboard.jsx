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
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    createFmt, formatMsf, resolveDecimals,
    isUsdDisplay, convertForDisplay, displayCurrencyCode, usdRateInfo,
} from '../../utils/formatters';
import { weekRules } from '../../utils/weekRules';

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
    // Executive display-currency toggle: convert + relabel when USD is active.
    const d = isUsdDisplay(sym) ? 2 : resolveDecimals();
    return displayCurrencyCode(sym) + ' ' + convertForDisplay(v, sym).toLocaleString('en-US',
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
    { key: 'nm',     label: 'Net Margin',           align: 'right', wrap: true },
];

/* Fee vocabulary — one source for the table, the fee ribbon and the export.
   Every term is spelled out: this page is read by people who do not live in
   the abbreviations, so "Net Margin" never appears as "NM". */
const FEE_LABELS = {
    msf: 'MSF',
    icf: 'Interchange Fee',
    sf:  'Scheme Fee',
    pg:  'Payment Gateway Fee',
    nm:  'Net Margin',
};

const PAGE_SIZES = [25, 50, 100];

/* Cost of sale for one trend day — the three pay-away fees. */
const costOf = (t) => num(t.icf) + num(t.sf) + num(t.pg);

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
   components/MultiSelect.jsx is hardcoded to Tailwind blues).

   Ticks are collected into a DRAFT and applied once — on Apply, or when the
   panel is dismissed. Committing per tick meant picking six MCCs fired six
   full dashboard reloads and the figures thrashed while you were still
   choosing. Escape abandons the draft.

   Options already chosen are pinned to the top of the list, but only as the
   panel OPENS: re-sorting live would make rows jump out from under the
   cursor mid-tick. ── */
const sameSet = (a, b) => a.length === b.length && a.every(v => b.includes(v));

const FilterSelect = ({ label, options, selected, onChange }) => {
    const [open, setOpen] = useState(false);
    const [q, setQ] = useState('');
    const [draft, setDraft] = useState(selected);
    const [pinned, setPinned] = useState([]);
    const ref = useRef(null);

    // The document listeners below fire outside React's render cycle, so they
    // read the live values through refs rather than a stale closure.
    const draftRef = useRef(draft);   draftRef.current = draft;
    const selRef = useRef(selected);  selRef.current = selected;

    const commit = useCallback(() => {
        if (!sameSet(draftRef.current, selRef.current)) onChange(draftRef.current);
    }, [onChange]);

    const openPanel = () => {
        setDraft(selected); setPinned(selected); setQ(''); setOpen(true);
    };
    const applyAndClose = () => { setOpen(false); commit(); };
    const cancelAndClose = () => { setOpen(false); setDraft(selRef.current); };

    useEffect(() => {
        if (!open) return;
        const onDoc = (e) => {
            if (ref.current && !ref.current.contains(e.target)) { setOpen(false); commit(); }
        };
        const onEsc = (e) => {
            if (e.key === 'Escape') { setOpen(false); setDraft(selRef.current); }
        };
        document.addEventListener('mousedown', onDoc);
        document.addEventListener('keydown', onEsc);
        return () => {
            document.removeEventListener('mousedown', onDoc);
            document.removeEventListener('keydown', onEsc);
        };
    }, [open, commit]);

    const all = useMemo(() => options || [], [options]);
    const shown = useMemo(() => {
        const needle = q.trim().toLowerCase();
        const list = needle ? all.filter(o => String(o).toLowerCase().includes(needle)) : all;
        if (!pinned.length) return list;
        const isPinned = new Set(pinned);
        return [...list.filter(o => isPinned.has(o)), ...list.filter(o => !isPinned.has(o))];
    }, [all, q, pinned]);

    const toggle = (opt) =>
        setDraft(d => (d.includes(opt) ? d.filter(v => v !== opt) : [...d, opt]));
    const selectAllShown = () => setDraft(d => [...new Set([...d, ...shown])]);
    const allShownOn = shown.length > 0 && shown.every(o => draft.includes(o));

    const active = selected.length > 0;
    const dirty = !sameSet(draft, selected);

    // The value line reads like a report parameter: "All", the single choice,
    // or how many are combined.
    const valueText = !active ? 'All'
        : selected.length === 1 ? String(selected[0])
        : `${selected.length} selected`;

    return (
        <div ref={ref} className="edm-fwrap">
            <button className={`edm-focus edm-fbtn${active ? ' edm-fbtn-on' : ''}`}
                onClick={() => (open ? applyAndClose() : openPanel())}
                aria-expanded={open} aria-haspopup="listbox"
                title={active ? `${label}: ${selected.join(', ')}` : `${label}: all`}>
                <span className="edm-fbtn-label">
                    {label}
                    {active && <span className="edm-fbtn-count">{selected.length}</span>}
                </span>
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
                <div role="listbox" aria-multiselectable="true" className="edm-pop" style={{
                    position: 'absolute', top: 'calc(100% + 5px)', left: 0, zIndex: 60,
                    width: 320, maxWidth: '90vw', background: 'var(--bg-card)',
                    border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
                    boxShadow: 'var(--shadow-pop)', overflow: 'hidden',
                }}>
                    <div style={{ padding: 9, borderBottom: '1px solid var(--border-light, var(--border))' }}>
                        <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
                            <Search size={12} style={{
                                position: 'absolute', left: 9, color: 'var(--text-muted)', pointerEvents: 'none' }} />
                            <input autoFocus value={q} onChange={e => setQ(e.target.value)}
                                placeholder={`Search ${label.toLowerCase()}`}
                                aria-label={`Search ${label}`}
                                style={{
                                    width: '100%', boxSizing: 'border-box', padding: '7px 9px 7px 26px',
                                    fontSize: 12.5, background: 'var(--bg-subtle, var(--bg))',
                                    border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
                                    color: 'var(--text)', outline: 'none',
                                }} />
                        </div>
                        <div style={{
                            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                            gap: 8, marginTop: 8,
                        }}>
                            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                {draft.length} of {all.length} selected
                            </span>
                            <span style={{ display: 'flex', gap: 12 }}>
                                <button className="edm-link" onClick={selectAllShown} disabled={allShownOn}
                                    style={{ opacity: allShownOn ? 0.4 : 1 }}>
                                    {q.trim() ? 'Select matches' : 'Select all'}
                                </button>
                                <button className="edm-link edm-link-danger" onClick={() => setDraft([])}
                                    disabled={!draft.length} style={{ opacity: draft.length ? 1 : 0.4 }}>
                                    Clear
                                </button>
                            </span>
                        </div>
                    </div>

                    <div style={{ maxHeight: 268, overflowY: 'auto', padding: '4px 0' }}>
                        {shown.map(opt => {
                            const on = draft.includes(opt);
                            return (
                                <div key={opt} role="option" aria-selected={on} onClick={() => toggle(opt)}
                                    className="edm-opt" title={String(opt)} style={{
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
                                    <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                        {String(opt)}
                                    </span>
                                </div>
                            );
                        })}
                        {!shown.length && (
                            <div style={{ padding: '14px 12px', fontSize: 12, color: 'var(--text-muted)', textAlign: 'center' }}>
                                {all.length ? 'Nothing matches' : 'No options for this tenant'}
                            </div>
                        )}
                    </div>

                    <div style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8,
                        padding: '9px 10px', background: 'var(--bg-subtle, var(--bg))',
                        borderTop: '1px solid var(--border-light, var(--border))',
                    }}>
                        <button className="edm-popbtn" onClick={cancelAndClose}>Cancel</button>
                        <button className="edm-popbtn edm-popbtn-primary" onClick={applyAndClose}>
                            {dirty ? 'Apply' : 'Done'}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
};

/* ── Metric: micro-label, mono value, one line of context ── */
/* ── Motion ──────────────────────────────────────────────────────────
   Every animation on this page is decorative: it tells you WHAT changed
   (a figure rolling to its new value, a bar growing to its new share)
   without ever moving layout. All of it is switched off under
   prefers-reduced-motion, both here and in the stylesheet. ── */
const REDUCED_MOTION = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false;

/* Count-up: the displayed number rolls from the previous value to the new
   one over ~650ms with an ease-out, so a selection change reads as a
   movement rather than a swap. A non-finite target (no data) shows as-is. */
const useCountUp = (target, duration = 650) => {
    const [shown, setShown] = useState(target);
    const shownRef = useRef(target);
    useEffect(() => {
        const from = shownRef.current;
        if (REDUCED_MOTION || typeof requestAnimationFrame !== 'function'
            || !Number.isFinite(target) || !Number.isFinite(from)) {
            shownRef.current = target; setShown(target); return undefined;
        }
        const delta = target - from;
        if (delta === 0) return undefined;
        let raf;
        const t0 = performance.now();
        const tick = (now) => {
            const p = Math.min((now - t0) / duration, 1);
            const eased = 1 - Math.pow(1 - p, 3);
            const v = p >= 1 ? target : from + delta * eased;
            shownRef.current = v;
            setShown(v);
            if (p < 1) raf = requestAnimationFrame(tick);
        };
        raf = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(raf);
    }, [target, duration]);
    return shown;
};

/* Sparkline: the metric across the month's loaded days. Drawn with
   pathLength=1 so the stroke can "write itself" in CSS on mount. */
const Sparkline = ({ series, color = 'var(--cat-1)', width = 84, height = 26, animKey }) => {
    const pts = (series || []).filter(v => Number.isFinite(v));
    if (pts.length < 2) return null;
    const lo = Math.min(...pts, 0), hi = Math.max(...pts, 0);
    const span = (hi - lo) || 1;
    const x = (i) => (i / (pts.length - 1)) * width;
    const y = (v) => height - 2 - ((v - lo) / span) * (height - 4);
    const d = pts.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    const area = `${d} L${width.toFixed(1)},${height} L0,${height} Z`;
    const last = pts.length - 1;
    return (
        <svg key={animKey} className="edm-spark" width={width} height={height}
            viewBox={`0 0 ${width} ${height}`} aria-hidden="true" focusable="false">
            <path d={area} fill={color} className="edm-spark-area" />
            <path d={d} fill="none" stroke={color} strokeWidth="1.6" strokeLinejoin="round"
                strokeLinecap="round" pathLength="1" className="edm-spark-line" />
            <circle cx={x(last)} cy={y(pts[last])} r="2.2" fill={color} className="edm-spark-dot" />
        </svg>
    );
};

/* Delta chip: ▲ 4.2% / ▼ 1.8% against a stated baseline. `invert` flips the
   colouring for figures where up is bad (cost of sale). */
const DeltaChip = ({ pct, label, invert = false }) => {
    if (pct == null || !Number.isFinite(pct)) return null;
    const up = pct >= 0;
    const flat = Math.abs(pct) < 0.05;
    const good = invert ? !up : up;
    const tone = flat ? 'flat' : good ? 'good' : 'bad';
    const capped = Math.min(Math.abs(pct), 999);
    return (
        <span className={`edm-delta edm-delta-${tone}`} title={`${up ? '+' : '−'}${capped.toFixed(1)}% ${label}`}>
            <span aria-hidden="true">{flat ? '•' : up ? '▲' : '▼'}</span>
            {capped.toFixed(1)}%
            <span className="edm-delta-lbl">{label}</span>
        </span>
    );
};

/* KPI tile. `raw` + `format` gives a count-up figure; `value` is a
   pre-formatted fallback for text-only tiles. `hero` promotes the tile to
   the headline treatment (bigger numeral, accent rule, tinted ground). */
const Metric = ({
    label, value, raw, format, sub, tone, title, wide = false, hero = false,
    delta, deltaLabel, invertDelta = false, series, sparkColor, animKey,
}) => {
    const animated = useCountUp(Number.isFinite(raw) ? raw : null);
    const text = Number.isFinite(raw) && format ? format(animated) : value;
    const colour = tone === 'danger' ? 'var(--danger-text)'
        : tone === 'success' ? 'var(--success-text)' : 'var(--text)';
    return (
        <div title={title}
            className={`edm-tile${hero ? ' edm-tile-hero' : ''}${hero && tone ? ` edm-tile-${tone}` : ''}`}
            style={{ padding: hero ? '16px 22px 14px' : wide ? '15px 20px' : '13px 18px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                <div className="edm-eyebrow">{label}</div>
                <DeltaChip pct={delta} label={deltaLabel} invert={invertDelta} />
            </div>
            <div className="edm-tile-value" style={{
                marginTop: hero ? 8 : 7,
                fontSize: hero ? 32 : wide ? 23 : 18,
                fontWeight: hero ? 700 : 600,
                color: colour,
            }}>
                {text}
            </div>
            {sub && (
                <div style={{
                    marginTop: 3, fontSize: 11, color: 'var(--text-secondary)', whiteSpace: 'nowrap',
                    overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {sub}
                </div>
            )}
            {series && (
                <div className="edm-tile-spark">
                    <Sparkline series={series} color={sparkColor || colour} animKey={animKey}
                        width={hero ? 112 : 84} height={hero ? 32 : 26} />
                </div>
            )}
        </div>
    );
};

/* ── Fee ribbon: MSF decomposed into what is paid away and what is kept.
   Segments are proportional to the gross fee pool; a loss is shown as a
   distinct overflow segment rather than a negative width. Segments wide
   enough to hold it carry their own share label. ── */
const FeeRibbon = ({ totals, money, share, compact = false, animKey }) => {
    const icf = num(totals?.icf), sf = num(totals?.sf), pg = num(totals?.pg), nm = num(totals?.nm);
    const pool = icf + sf + pg + Math.max(nm, 0);
    if (pool <= 0) return null;
    // The three paid-away fees step down ONE ramp (imperial 1→3): they are the
    // same kind of thing — money leaving — so they should read as one block that
    // shades, not three unrelated hues. Only the kept margin breaks to jade, and
    // that break is the whole point of the bar.
    const segs = [
        { key: 'icf', label: FEE_LABELS.icf, value: icf, color: 'var(--imp-1)' },
        { key: 'sf',  label: FEE_LABELS.sf,  value: sf,  color: 'var(--imp-2)' },
        { key: 'pg',  label: FEE_LABELS.pg,  value: pg,  color: 'var(--imp-3)' },
        ...(nm >= 0
            ? [{ key: 'nm', label: FEE_LABELS.nm, value: nm, color: 'var(--mix-margin)' }]
            : [{ key: 'loss', label: 'Loss', value: Math.abs(nm), color: 'var(--danger)', overflow: true }]),
    ];
    // The bar only draws components that exist; the legend lists all four, so a
    // zero fee reads as "none charged" instead of a missing part of the stack.
    const drawn = segs.filter(s => s.value > 0);

    return (
        <div>
            <div key={animKey} className="edm-ribbon" style={{ height: compact ? 10 : 16 }}>
                {drawn.map(s => {
                    const pct = (s.value / pool) * 100;
                    return (
                        <div key={s.key} className="edm-ribbon-seg"
                            title={`${s.label} · ${money(s.value)} · ${share(s.value, pool)}`}
                            style={{ width: `${pct}%`, background: s.color, opacity: s.overflow ? 0.9 : 1 }}>
                            {!compact && pct >= 11 && (
                                <span className="edm-ribbon-lbl">{pct.toFixed(0)}%</span>
                            )}
                        </div>
                    );
                })}
            </div>
            <div style={{
                display: 'flex', flexWrap: 'wrap', gap: compact ? '4px 12px' : '6px 18px', marginTop: 10,
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
                        <span className="edm-pill edm-pill-tint" style={{ '--pill-ink': s.color }}>
                            {share(s.value, pool)}
                        </span>
                    </span>
                ))}
            </div>
        </div>
    );
};

/* ── Mix strip: hue encodes the dimension, opacity encodes rank. Bars grow
   in from the left, staggered top-to-bottom, whenever the selection changes. ── */
const MixStrip = ({ title, hue, rows, money, share, animKey }) => {
    const items = (rows || []).slice(0, 5);
    const total = (rows || []).reduce((a, r) => a + num(r.volume), 0);
    if (!items.length || total <= 0) return null;
    const rest = (rows || []).slice(5).reduce((a, r) => a + num(r.volume), 0);
    const max = num(items[0].volume) || 1;

    return (
        <div style={{ minWidth: 0 }}>
            <div className="edm-eyebrow edm-eyebrow-rule" style={{ marginBottom: 10 }}>{title}</div>
            <div key={animKey} style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
                {items.map((r, i) => (
                    <div key={r.label} className="edm-mix-row" style={{ '--i': i }}>
                        <div style={{
                            display: 'flex', alignItems: 'center', gap: 8,
                            fontSize: 11.5, marginBottom: 4,
                        }}>
                            <span className="edm-rank">{String(i + 1).padStart(2, '0')}</span>
                            <span style={{
                                flex: 1, color: 'var(--text)', fontWeight: 500,
                                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                            }} title={r.label}>{r.label}</span>
                            <span className="edm-pill" title={money(r.volume)}>
                                {share(r.volume, total)}
                            </span>
                        </div>
                        <div className="edm-track">
                            <div className="edm-mix-fill" title={`${r.label} · ${money(r.volume)}`} style={{
                                width: `${Math.max((num(r.volume) / max) * 100, 1.5)}%`,
                                background: `linear-gradient(90deg, ${hue}, color-mix(in srgb, ${hue} 70%, #fff))`,
                                opacity: 1 - i * 0.14,
                            }} />
                        </div>
                    </div>
                ))}
                {rest > 0 && (
                    <div style={{ fontSize: 11, color: 'var(--text-muted)', paddingLeft: 26 }}>
                        + {(rows.length - 5)} more · {share(rest, total)}
                    </div>
                )}
            </div>
        </div>
    );
};

/* ── Month shape: one bar per day of the month (volume) with the net margin
   drawn over it as a line. The bars are the SAME control as the calendar —
   clicking one includes/excludes that date — so the month can be read either
   as a grid or as a curve without learning a second interaction.

   The server always returns `trend` for the WHOLE month (see the controller's
   ctxStart/ctxEnd), never just the selection, so the shape stays stable while
   days are picked and dropped. Hovering a bar lifts a card with that day's
   figures; the bars themselves rise in when the month changes. ── */
const DayTrendChart = ({ days, trendByDate, selectedDates, onToggle, money, week, height = 132, animKey }) => {
    const picked = useMemo(() => new Set(selectedDates), [selectedDates]);
    const [hover, setHover] = useState(null);
    const rows = useMemo(() => days.map((d) => {
        const t = trendByDate.get(d);
        return { date: d, vol: num(t?.volume), count: num(t?.count), nm: num(t?.nm), has: !!t };
    }), [days, trendByDate]);

    if (!rows.length) return null;

    const maxVol = rows.reduce((a, r) => Math.max(a, r.vol), 0) || 1;
    const withData = rows.filter(r => r.has);
    const nmHi = withData.reduce((a, r) => Math.max(a, r.nm), 0);
    const nmLo = withData.reduce((a, r) => Math.min(a, r.nm), 0);
    /* 8% headroom top and bottom: without it the highest and lowest days sit
       exactly on the plot edges and the stroke is clipped in half. */
    const nmPad = ((nmHi - nmLo) || Math.abs(nmHi) || 1) * 0.08;
    const nmSpan = ((nmHi + nmPad) - (nmLo - nmPad)) || 1;
    /* Net margin is drawn in a 0–100 box that the SVG stretches to fit; the
       stroke is non-scaling so the distortion never thickens the line. */
    const nmY = (v) => 100 - ((v - (nmLo - nmPad)) / nmSpan) * 100;
    const xAt = (i) => ((i + 0.5) / rows.length) * 100;

    /* Break the line wherever a day has no data — joining across a gap would
       draw a margin trend through days that were never processed. */
    const segments = [];
    let run = [];
    rows.forEach((r, i) => {
        if (r.has) run.push(`${xAt(i).toFixed(3)},${nmY(r.nm).toFixed(3)}`);
        else { if (run.length > 1) segments.push(run); run = []; }
    });
    if (run.length > 1) segments.push(run);

    const anySelected = selectedDates.length > 0;
    const zeroY = nmLo < 0 && nmHi > 0 ? nmY(0) : null;
    const hov = hover != null ? rows[hover] : null;
    // Card anchored to the bar; flips to the left past the two-thirds mark
    // so it never hangs outside the chart.
    const hovLeft = hover != null ? xAt(hover) : 0;
    const hovFlip = hovLeft > 66;

    return (
        <div style={{ minWidth: 0, flex: '1 1 320px' }}>
            <div style={{
                display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
                gap: 10, marginBottom: 10, flexWrap: 'wrap',
            }}>
                <span className="edm-eyebrow edm-eyebrow-rule">Month shape</span>
                <span style={{ display: 'flex', gap: 14, fontSize: 10.5, color: 'var(--text-muted)' }}>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                        <span style={{ width: 9, height: 9, borderRadius: 2, background: 'var(--cat-1)' }} />
                        Volume
                    </span>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                        <span style={{ width: 12, height: 2, borderRadius: 2, background: 'var(--chart-4, #7191CE)' }} />
                        Net Margin
                    </span>
                </span>
            </div>

            <div className="edm-chart" style={{ height }} onMouseLeave={() => setHover(null)}>
                <div key={animKey} className="edm-chart-bars">
                    {rows.map((r, i) => {
                        const on = picked.has(r.date);
                        const dim = anySelected && !on;
                        const loss = r.has && r.nm < 0;
                        const pct = r.has ? Math.max((r.vol / maxVol) * 100, 1.5) : 0;
                        return (
                            <button key={r.date} type="button"
                                className={`edm-bar${on ? ' edm-bar-on' : ''}${hover === i ? ' edm-bar-hov' : ''}`}
                                onClick={() => onToggle(r.date)}
                                onMouseEnter={() => setHover(i)}
                                onFocus={() => setHover(i)}
                                onBlur={() => setHover(h => (h === i ? null : h))}
                                aria-pressed={on}
                                aria-label={r.has
                                    ? `${longDate(r.date)}${week.isWeekend(r.date) ? ', weekend' : ''}, volume ${money(r.vol)}, net margin ${money(r.nm)}`
                                    : `${longDate(r.date)}${week.isWeekend(r.date) ? ', weekend' : ''}, no transactions loaded`}>
                                <span className={`edm-bar-fill${loss ? ' edm-bar-loss' : ''}`} style={{
                                    height: `${pct}%`,
                                    opacity: dim ? 0.3 : 1,
                                    '--i': i,
                                }} />
                                {week.isWeekend(r.date) && <span className="edm-bar-we" aria-hidden="true" />}
                            </button>
                        );
                    })}
                </div>
                <svg key={`line-${animKey}`} className="edm-chart-line edm-chart-line-draw" viewBox="0 0 100 100"
                    preserveAspectRatio="none" aria-hidden="true" focusable="false">
                    {zeroY != null && (
                        <line x1="0" y1={zeroY} x2="100" y2={zeroY}
                            stroke="var(--danger)" strokeWidth="1" strokeDasharray="3 3"
                            vectorEffect="non-scaling-stroke" opacity="0.45" />
                    )}
                    {segments.map((pts, i) => (
                        <polyline key={i} points={pts.join(' ')} fill="none"
                            stroke="var(--chart-4, #7191CE)" strokeWidth="2"
                            strokeLinejoin="round" strokeLinecap="round"
                            vectorEffect="non-scaling-stroke" />
                    ))}
                </svg>
                {hov && (
                    <div className={`edm-tip${hovFlip ? ' edm-tip-flip' : ''}`}
                        style={{ left: `${hovLeft}%` }} role="presentation">
                        <div className="edm-tip-date">
                            {longDate(hov.date)}{week.isWeekend(hov.date) ? ' · weekend' : ''}
                        </div>
                        {hov.has ? (
                            <>
                                <div className="edm-tip-row"><span>Volume</span><span className="edm-num">{money(hov.vol)}</span></div>
                                <div className="edm-tip-row"><span>Transactions</span><span className="edm-num">{hov.count.toLocaleString()}</span></div>
                                <div className="edm-tip-row">
                                    <span>Net Margin</span>
                                    <span className="edm-num" style={{ color: hov.nm >= 0 ? 'var(--success-text)' : 'var(--danger-text)' }}>
                                        {money(hov.nm)}
                                    </span>
                                </div>
                            </>
                        ) : (
                            <div className="edm-tip-row" style={{ color: 'var(--text-muted)' }}>No transactions loaded</div>
                        )}
                    </div>
                )}
            </div>

            {/* Every day carries its own date on the axis — reading the shape
                against the calendar should never need counting. Labels are
                narrow mono numerals; the hovered day is bolded. */}
            <div className="edm-chart-axis">
                {rows.map((r, i) => (
                    <span key={r.date} className={hover === i ? 'edm-axis-hov' : undefined}
                        title={longDate(r.date)}>
                        {dayNum(r.date)}
                    </span>
                ))}
            </div>
        </div>
    );
};

/* ── Weekday vs weekend, using the tenant's own working week. Two days out of
   seven carry a very different mix in this market, and the split is the
   quickest read on whether a month's shortfall is trading or calendar. ── */
const WeekSplit = ({ days, trendByDate, week, money, share, animKey }) => {
    const stats = useMemo(() => {
        const blank = () => ({ days: 0, volume: 0, count: 0, nm: 0 });
        const acc = { weekday: blank(), weekend: blank() };
        days.forEach(d => {
            const t = trendByDate.get(d);
            if (!t) return;                       // a day with no data is not a trading day
            const b = acc[week.isWeekend(d) ? 'weekend' : 'weekday'];
            b.days += 1;
            b.volume += num(t.volume);
            b.count += num(t.count);
            b.nm += num(t.nm);
        });
        return acc;
    }, [days, trendByDate, week]);

    const total = stats.weekday.volume + stats.weekend.volume;
    if (total <= 0) return null;

    const bars = [
        { key: 'weekday', label: 'Weekdays', hue: 'var(--cat-1)',   ...stats.weekday },
        { key: 'weekend', label: 'Weekends', hue: 'var(--cat-3, var(--chart-alt, #64748B))', ...stats.weekend },
    ];

    return (
        <div style={{ minWidth: 0 }}>
            <div className="edm-eyebrow edm-eyebrow-rule" style={{ marginBottom: 10 }}>
                Weekday vs weekend
            </div>
            <div key={animKey} style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
                {bars.map((b, i) => (
                    <div key={b.key} className="edm-mix-row" style={{ '--i': i }}>
                        <div style={{
                            display: 'flex', justifyContent: 'space-between', gap: 10,
                            fontSize: 11.5, marginBottom: 4,
                        }}>
                            <span style={{ color: 'var(--text)', fontWeight: 500 }}
                                title={b.key === 'weekend' ? week.longLabel : `all days except ${week.longLabel}`}>
                                {b.label}
                                <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>
                                    {' · '}{b.days} day{b.days === 1 ? '' : 's'}
                                </span>
                            </span>
                            <span className="edm-pill">{share(b.volume, total)}</span>
                        </div>
                        <div className="edm-track">
                            <div className="edm-mix-fill" title={`${b.label} · ${money(b.volume)}`} style={{
                                width: `${Math.max((b.volume / total) * 100, 1.5)}%`,
                                background: `linear-gradient(90deg, ${b.hue}, color-mix(in srgb, ${b.hue} 70%, #fff))`,
                            }} />
                        </div>
                        <div className="edm-num" style={{ marginTop: 4, fontSize: 10.5, color: 'var(--text-muted)' }}>
                            {b.days ? `${money(b.volume / b.days)} / day` : 'no trading days'}
                            {b.days ? ` · Net Margin ${money(b.nm)}` : ''}
                        </div>
                    </div>
                ))}
            </div>
            <div style={{ marginTop: 9, fontSize: 10.5, color: 'var(--text-muted)' }}>
                Weekend here is {week.longLabel}, per this bank's country.
            </div>
        </div>
    );
};

/* ── First-load skeleton that mirrors the real layout — five tiles, the fee
   ribbon, the ratio row, the mix panel and the table — so the page does not
   jump when the figures arrive. ── */
const Bone = ({ w = '100%', h = 12, r = 5, style }) => (
    <div className="edm-bone" style={{ width: w, height: h, borderRadius: r, ...style }} />
);
const LedgerSkeleton = () => (
    <div aria-busy="true" aria-label="Loading the dashboard">
        <section className="edm-panel" style={{ marginBottom: 12, overflow: 'hidden' }}>
            <div style={{
                display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(168px, 1fr))',
                borderBottom: '1px solid var(--border-light, var(--border))',
            }}>
                {Array.from({ length: 5 }).map((_, i) => (
                    <div key={i} style={{ padding: '15px 20px', ...(i < 4 ? cellDiv : {}) }}>
                        <Bone w={70} h={9} />
                        <Bone w="62%" h={22} style={{ marginTop: 10 }} />
                        <Bone w="45%" h={9} style={{ marginTop: 8 }} />
                    </div>
                ))}
            </div>
            <div style={{ padding: '15px 20px 16px' }}>
                <Bone w={120} h={9} style={{ marginBottom: 12 }} />
                <Bone w="100%" h={16} r={999} />
                <div style={{ display: 'flex', gap: 18, marginTop: 12 }}>
                    {Array.from({ length: 4 }).map((_, i) => <Bone key={i} w={120} h={10} />)}
                </div>
            </div>
            <div style={{
                display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                borderTop: '1px solid var(--border-light, var(--border))',
            }}>
                {Array.from({ length: 5 }).map((_, i) => (
                    <div key={i} style={{ padding: '13px 18px', ...(i < 4 ? cellDiv : {}) }}>
                        <Bone w={80} h={9} />
                        <Bone w="50%" h={18} style={{ marginTop: 9 }} />
                    </div>
                ))}
            </div>
        </section>
        <section className="edm-panel" style={{
            display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))',
            gap: 26, padding: '16px 20px', marginBottom: 12,
        }}>
            {Array.from({ length: 4 }).map((_, i) => (
                <div key={i}>
                    <Bone w={90} h={9} style={{ marginBottom: 14 }} />
                    {Array.from({ length: 4 }).map((_, j) => (
                        <div key={j} style={{ marginBottom: 11 }}>
                            <Bone w={`${78 - j * 12}%`} h={10} style={{ marginBottom: 5 }} />
                            <Bone w={`${90 - j * 18}%`} h={5} r={999} />
                        </div>
                    ))}
                </div>
            ))}
        </section>
        <section className="edm-panel" style={{ overflow: 'hidden' }}>
            <div style={{ display: 'flex', gap: 14, padding: '13px 14px', background: 'var(--bg-subtle)' }}>
                {Array.from({ length: 10 }).map((_, i) => <Bone key={i} w={i < 3 ? 90 : 64} h={10} />)}
            </div>
            {Array.from({ length: 8 }).map((_, i) => (
                <div key={i} style={{
                    display: 'flex', gap: 14, padding: '12px 14px',
                    borderBottom: '1px solid var(--border-light, var(--border))',
                }}>
                    {Array.from({ length: 10 }).map((_, j) => <Bone key={j} w={j < 3 ? 90 : 64} h={12} />)}
                </div>
            ))}
        </section>
    </div>
);

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
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion, homeCountryCode } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const navigate = useNavigate();

    /* The tenant's working week — UAE weekends on Sat+Sun, Bahrain/Oman/Egypt
       on Fri+Sat. Drives the calendar's first column, the weekend tint, the
       quick picks and the weekday/weekend split. */
    const week = useMemo(() => weekRules(homeCountryCode), [homeCountryCode]);

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
    const [lastRefresh, setLastRefresh] = useState(null);
    /* The table load waits for the calendar to resolve the default month/date;
       firing it immediately produced a second, thrown-away request (the first
       ran on the backend-default date, then the calendar answer changed the
       params) — the abort cancels the HTTP call but Postgres still does the
       work, doubling the cold-cache cost of every open. */
    const [bootstrapped, setBootstrapped] = useState(false);

    /* Money at tenant precision (BHD is 3dp — never assume 2), or 2dp USD when
       the executive display-currency toggle is on (values are converted). */
    const dp = isUsdDisplay(currencyCode) ? 2 : resolveDecimals(currencyDecimals, currencyCode);
    const money = useCallback((v) => convertForDisplay(Number(v || 0), currencyCode).toLocaleString('en-US',
        { minimumFractionDigits: dp, maximumFractionDigits: dp }), [dp, currencyCode]);
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
            .catch(() => { /* the table still loads on the backend default */ })
            .finally(() => { if (!cancelled) setBootstrapped(true); });
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
            setLastRefresh(new Date());
        } catch (e) {
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            setError(e?.response?.data?.message || 'Could not load the dashboard.');
        } finally {
            setLoading(false);
        }
    }, [filters, dateParams, page, pageSize, sort, dir]);

    useEffect(() => {
        if (!bootstrapped) return;
        const ac = new AbortController();
        load(ac.signal);
        return () => ac.abort();
    }, [load, tenantVersion, bootstrapped]);

    useEffect(() => {
        setPage(0); setMonth(''); setSelectedDates([]); setDetailRow(null);
        setBootstrapped(false);   // wait for the new tenant's calendar
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
            // Executive display-currency toggle: money cells are converted with
            // the same rate the screen uses, and the file states that rate.
            const cv = (v) => convertForDisplay(num(v), currencyCode);
            const fx = usdRateInfo(currencyCode);
            const lines = [
                `Currency,${displayCurrencyCode(currencyCode) || currencySymbol || 'UNKNOWN'}`,
                ...(fx ? [`FX Rate,1 ${fx.base} = ${fx.rate} USD (indicative; as of ${fx.asOf})`] : []),
                `Business Date,${res.data?.month ? res.data.month + ' (full month)'
                    : (res.data?.dates || []).join(' ') || label}`,
                ['SID', 'MID', 'Name', 'Vol', 'Count', FEE_LABELS.msf, FEE_LABELS.icf,
                    FEE_LABELS.sf, FEE_LABELS.pg, FEE_LABELS.nm].join(','),
            ];
            rows.forEach(r => lines.push([
                esc(r.sid), esc(r.mid), esc(r.merchantName),
                cv(r.volume).toFixed(dp), num(r.count),
                cv(r.msf).toFixed(msfDp), cv(r.icf).toFixed(dp),
                cv(r.sf).toFixed(dp), cv(r.pg).toFixed(dp), cv(r.nm).toFixed(dp),
            ].join(',')));
            // The server's own selection total, so the file verifies itself.
            if (totals) {
                lines.push([
                    esc('TOTAL'), esc(''), esc(`${rows.length} rows`),
                    cv(totals.volume).toFixed(dp), num(totals.count),
                    cv(totals.msf).toFixed(msfDp), cv(totals.icf).toFixed(dp),
                    cv(totals.sf).toFixed(dp), cv(totals.pg).toFixed(dp),
                    cv(totals.nm).toFixed(dp),
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

    /* Two different loading states. The FIRST load has nothing to show, so it
       gets the skeleton. Every load after that already has figures on screen —
       those stay put and dim, because replacing them with a placeholder is what
       made the page flash on every click. */
    const firstLoad = loading && !data;
    const refreshing = loading && !!data;

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

    /* Calendar weeks for the selected month, started on the day that OPENS the
       tenant's week, so its weekend always lands on the last two columns.
       null = lead/tail blank. */
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
        const cells = Array(week.leadBlanks(new Date(y, mo, 1).getDay())).fill(null);
        for (let d = 1; d <= daysIn; d++)
            cells.push(`${m[1]}-${m[2]}-${String(d).padStart(2, '0')}`);
        while (cells.length % 7) cells.push(null);
        const weeks = [];
        for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
        return weeks;
    }, [month, week]);

    /* Which quick pick the current selection actually IS. Derived, never
       stored: hand-picking a day on the calendar has to drop the highlight
       back to "Custom" on its own, or the control lies about what is loaded. */
    const dayMode = useMemo(() => {
        if (!selectedDates.length) return 'all';
        if (!allMonthDays.length) return 'custom';
        const sel = [...selectedDates].sort().join(',');
        const wd = allMonthDays.filter(d => !week.isWeekend(d)).sort().join(',');
        if (sel === wd) return 'weekdays';
        const we = allMonthDays.filter(week.isWeekend).sort().join(',');
        if (sel === we) return 'weekends';
        return 'custom';
    }, [selectedDates, allMonthDays, week]);

    /* Derived ratios — existing Acquira definitions, computed from the server's
       own totals (never a second opinion on the fee maths). */
    const marginPct = totals && num(totals.volume) ? (num(totals.nm) / num(totals.volume)) * 100 : null;
    const msfBps = totals && num(totals.volume) ? (num(totals.msf) / num(totals.volume)) * 10000 : null;
    const avgTicket = totals && num(totals.count) ? num(totals.volume) / num(totals.count) : null;
    const costTotal = totals ? num(totals.icf) + num(totals.sf) + num(totals.pg) : 0;
    const daysCovered = selectedDates.length || trend.length;

    /* One key per SELECTION (dates + filters). Anything that re-animates —
       ribbon wipe, bar growth, sparkline draw — is keyed on this, so a sort or
       page turn never replays motion on figures that did not change. */
    const animKey = `${dateParams.dates || dateParams.month || ''}|${JSON.stringify(filters)}`;

    /* Per-metric daily series across the month's loaded days, for the tile
       sparklines. Cost of sale is the three pay-away fees summed per day. */
    const loadedDays = useMemo(
        () => allMonthDays.map(d => trendByDate.get(d)).filter(Boolean), [allMonthDays, trendByDate]);
    const kpiSeries = useMemo(() => ({
        volume: loadedDays.map(t => num(t.volume)),
        count:  loadedDays.map(t => num(t.count)),
        msf:    loadedDays.map(t => num(t.msf)),
        cost:   loadedDays.map(costOf),
        nm:     loadedDays.map(t => num(t.nm)),
    }), [loadedDays]);

    /* Period-over-period deltas, computed on a per-day basis from the month
       trend (same filters as the figures on screen):
         · a selection compares to the SAME NUMBER of loaded days immediately
           before its first day — "vs prior 5 days";
         · if the month has no earlier days, it compares to the month's own
           per-day average;
         · the whole month carries no delta — there is nothing in the payload
           to compare it to, and a fake baseline is worse than none. */
    const deltas = useMemo(() => {
        if (!selectedDates.length || !loadedDays.length) return null;
        const first = [...selectedDates].sort()[0];
        const sel = loadedDays.filter(t => selectedDates.includes(t.date));
        if (!sel.length) return null;
        const prior = loadedDays.filter(t => t.date < first).slice(-selectedDates.length);
        const base = prior.length ? prior : loadedDays;
        const label = prior.length
            ? `vs prior ${prior.length} day${prior.length === 1 ? '' : 's'}`
            : 'vs month avg/day';
        const avg = (arr, f) => arr.reduce((a, t) => a + f(t), 0) / arr.length;
        const pct = (f) => {
            const b = avg(base, f);
            if (!b) return null;
            return ((avg(sel, f) - b) / Math.abs(b)) * 100;
        };
        return {
            label,
            volume: pct(t => num(t.volume)),
            count:  pct(t => num(t.count)),
            msf:    pct(t => num(t.msf)),
            cost:   pct(costOf),
            nm:     pct(t => num(t.nm)),
        };
    }, [selectedDates, loadedDays]);

    /* Per-day net margin tone for the selection chips. */
    const dayTone = (d) => {
        const t = trendByDate.get(d);
        if (!t) return 'none';
        return num(t.nm) >= 0 ? 'good' : 'bad';
    };
    const refreshedAt = lastRefresh
        ? lastRefresh.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) : null;

    /* Every selected day is one with no transactions loaded — say so plainly
       instead of blaming the filters. */
    const noTxnSelection = selectedDates.length > 0
        && selectedDates.every(d => !monthDateSet.has(d));

    /* In-cell volume bar is scaled to the biggest row on THIS page. */
    const pageMaxVolume = useMemo(
        () => rows.reduce((a, r) => Math.max(a, num(r.volume)), 0) || 1, [rows]);
    /* Net margin heat is scaled to the strongest positive row on this page:
       4% tint at the bottom, 18% at the top, so the column reads as a scale. */
    const pageMaxNm = useMemo(
        () => rows.reduce((a, r) => Math.max(a, num(r.nm)), 0) || 1, [rows]);
    const nmHeat = (v) => (num(v) <= 0 ? 0 : 4 + Math.round((num(v) / pageMaxNm) * 14));
    /* Rows re-stagger only when the result set itself changes. */
    const rowsKey = `${animKey}|${page}|${sort}|${dir}|${pageSize}`;

    const NmCell = ({ v, bold = false }) => {
        const n = num(v);
        const pos = n >= 0;
        return (
            <span title={fullNum(v, currencySymbol)} style={{
                // Anchors the .edm-sr note below: absolutely positioned with no
                // positioned ancestor, it resolved against the viewport, escaped
                // the table's horizontal scroll box and gave the whole PAGE a
                // horizontal scrollbar on narrow screens.
                position: 'relative',
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
        <div className="edm-page"
            style={{ padding: '22px 26px 32px', width: '100%', maxWidth: '100%', boxSizing: 'border-box' }}>
            <style>{`
                /* ── Page-local masthead ground ───────────────────────────────
                   The app-wide --table-head-bg is a saturated steel navy: right
                   for a 32px table header strip, too loud for a 150px slab that
                   opens the page. This page mixes far less --primary into a
                   graphite base, so the header reads as a calm dark surface
                   rather than a brand banner — still derived from the token, so
                   it retints with the brand and follows dark mode. Scoped here
                   on purpose: table headers elsewhere keep the full-weight navy. */
                .edm-page {
                    --edm-mast-bg: linear-gradient(150deg,
                        color-mix(in srgb, var(--primary) 26%, #171D28) 0%,
                        color-mix(in srgb, var(--primary) 18%, #141922) 55%,
                        color-mix(in srgb, var(--primary) 24%, #11161E) 100%);
                }
                .edm-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }

                /* ── Masthead: the page opens on the same navy the grid header and
                   rail already wear — one identity, carried at full weight. ── */
                /* The whole header block wears the navy; the bands inside are
                   translucent overlays on it. Two-class selector on purpose —
                   .edm-panel sets a white background later in this sheet and
                   would otherwise win on equal specificity. */
                .edm-panel.edm-hdrblock { background: var(--edm-mast-bg,
                        linear-gradient(150deg, #212F4B 0%, #1C263C 55%, #1C2841 100%));
                    border-color: transparent; overflow: visible;
                    /* The edm-enter fill-mode animation makes every panel its own
                       stacking context, so the filter popover's z-index can't escape
                       this block — later sibling panels painted over it. Lift the
                       whole header above them so open dropdowns hang in front. */
                    position: relative; z-index: 40; }
                .edm-mast { background: transparent;
                    padding: 16px 24px 14px; display: flex; justify-content: space-between;
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
                .edm-mast h1 { margin: 7px 0 0; font-size: 23px; font-weight: 700;
                    letter-spacing: -0.025em; line-height: 1.08;
                    color: var(--table-head-text, #EEF3FC); }
                /* The ground is duller now, so supporting type is lifted a notch —
                   less contrast under it means it would otherwise go gray-flat. */
                .edm-mast-sub { margin: 5px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 70%, transparent); }
                .edm-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.18);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .edm-mast-btn:hover { background: rgba(255,255,255,0.15); }
                .edm-mast-btn:disabled { opacity: 0.5; cursor: default; }

                /* ── Command deck: the filter row is part of the navy header, not a
                   tray of light boxes under it. Cells are divided by hairlines —
                   one continuous instrument strip. ── */
                /* Bands are shallower than they were: on the duller ground a .20/.30
                   step reads as muddy grey rather than as depth. */
                .edm-cmdbar { display: flex; align-items: stretch; flex-wrap: wrap;
                    background: rgba(0,0,0,0.10);
                    border-top: 1px solid rgba(255,255,255,0.08); }
                /* The flex child is FilterSelect's positioning wrapper, not the
                   button — so the grow factor has to live here, or the cells stop
                   at their basis and leave dead ground across the rest of the row. */
                .edm-fwrap { position: relative; display: flex; flex: 1 1 170px; min-width: 0; }
                .edm-fbtn { position: relative; display: flex; flex-direction: column;
                    align-items: stretch; gap: 5px; width: 100%; min-width: 0;
                    padding: 10px 16px; text-align: left; cursor: pointer;
                    background: transparent; border: 0;
                    border-right: 1px solid rgba(255,255,255,0.08);
                    transition: background .12s ease; }
                /* The strip ends at the last cell — no divider dangling into space. */
                .edm-fwrap:last-child .edm-fbtn { border-right: 0; }
                .edm-fbtn:hover { background: rgba(255,255,255,0.06); }
                .edm-fbtn-on { background: rgba(255,255,255,0.08);
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
                    padding: 9px 16px; background: rgba(0,0,0,0.16);
                    border-top: 1px solid rgba(255,255,255,0.07); }
                .edm-chip { display: inline-flex; align-items: center; gap: 6px;
                    padding: 3px 10px; border-radius: var(--radius-pill, 999px);
                    font-size: 11px; font-weight: 600; color: #EEF3FC;
                    background: rgba(255,255,255,0.13); border: 1px solid rgba(255,255,255,0.16); }
                /* Count badge — a filtered column says how many, not just "on". */
                .edm-fbtn-count { display: inline-flex; align-items: center; justify-content: center;
                    min-width: 15px; height: 15px; margin-left: 6px; padding: 0 4px;
                    border-radius: 999px; font-size: 9px; font-weight: 700;
                    font-family: var(--font-mono); letter-spacing: 0;
                    color: #12203E; background: var(--chart-4, #8AA5E0); }

                /* ── Filter popover ── */
                .edm-link { border: none; background: none; padding: 0; cursor: pointer;
                    font-size: 11px; font-weight: 600; color: var(--primary); }
                .edm-link:disabled { cursor: default; }
                .edm-link-danger { color: var(--danger); }
                .edm-popbtn { padding: 6px 13px; font-size: 12px; font-weight: 600;
                    border-radius: var(--radius-sm); cursor: pointer;
                    color: var(--text-secondary); background: var(--bg-card);
                    border: 1px solid var(--border); }
                .edm-popbtn-primary { color: #fff; background: var(--primary);
                    border-color: var(--primary); }

                /* ── Day quick picks: one segmented control, one lit segment ── */
                .edm-seg { display: inline-flex; align-items: stretch; overflow: hidden;
                    border: 1px solid var(--border); border-radius: var(--radius-sm);
                    background: var(--bg-card); }
                .edm-seg-btn { padding: 6px 12px; font-size: 12px; font-weight: 600;
                    font-family: var(--font-ui); border: 0; cursor: pointer;
                    color: var(--text-secondary); background: transparent;
                    border-right: 1px solid var(--border); transition: background .12s ease; }
                .edm-seg-btn:last-child { border-right: 0; }
                .edm-seg-btn:hover:not(:disabled)[data-on="false"] { background: var(--bg-hover); }
                .edm-seg-btn:disabled { opacity: 0.45; cursor: default; }
                .edm-seg-btn[data-on="true"] { color: var(--cal-pick-ink); background: var(--cal-pick);
                    border-color: var(--cal-pick-edge); }
                .edm-seg-custom { cursor: default; display: inline-flex; align-items: center; }

                /* ── Month shape chart ── */
                .edm-chart { position: relative; width: 100%; }
                .edm-chart-bars { position: absolute; inset: 0; display: flex;
                    align-items: flex-end; gap: 2px; }
                .edm-bar { position: relative; flex: 1 1 0; min-width: 0; height: 100%;
                    display: flex; align-items: flex-end; padding: 0; border: 0;
                    background: transparent; cursor: pointer; border-radius: 2px; }
                .edm-bar:hover { background: var(--bg-hover); }
                .edm-bar-fill { display: block; width: 100%; border-radius: 2px 2px 0 0;
                    transition: opacity .12s ease; }
                .edm-bar-on .edm-bar-fill { background: linear-gradient(180deg,
                        var(--select-green, #A8E6C9),
                        color-mix(in srgb, var(--select-green, #A8E6C9) 62%, transparent)) !important; }
                /* Weekend days carry a foot mark, so the week's rhythm is legible
                   in the curve as well as the grid. */
                .edm-bar-we { position: absolute; left: 1px; right: 1px; bottom: -3px;
                    height: 2px; border-radius: 2px; background: var(--chart-alt, #64748B);
                    opacity: 0.5; }
                .edm-chart-line { position: absolute; inset: 0; width: 100%; height: 100%;
                    pointer-events: none; overflow: visible; }
                .edm-chart-axis { display: flex; gap: 2px; margin-top: 7px;
                    font-family: var(--font-mono); font-size: 8px; letter-spacing: -0.02em;
                    color: var(--text-muted); }
                /* A label per day means 28-31 columns: clip rather than wrap, so a
                   narrow viewport thins the axis instead of stacking it. */
                .edm-chart-axis > span { flex: 1 1 0; min-width: 0; text-align: center;
                    overflow: hidden; white-space: nowrap; }

                /* Refetch in progress: the figures on screen are one selection
                   out of date, so they fade slightly and stop taking clicks.
                   No pulse, no layout change — the page must not move. */
                .edm-refreshing { opacity: 0.62; pointer-events: none;
                    transition: opacity .18s ease; }

                .edm-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .edm-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); box-shadow: var(--shadow-card, 0 1px 2px rgba(0,0,0,.04));
                    transition: box-shadow .2s ease, transform .2s ease; }
                .edm-panel-lift:hover { box-shadow: var(--shadow-hover, 0 10px 28px rgba(0,0,0,.10));
                    transform: translateY(-1px); }

                /* ── Entrance: panels rise in once, staggered top to bottom. ── */
                @keyframes edm-fadeup { from { opacity: 0; transform: translateY(8px); }
                    to { opacity: 1; transform: none; } }
                @keyframes edm-growx { from { transform: scaleX(0); } to { transform: scaleX(1); } }
                @keyframes edm-growy { from { transform: scaleY(0); } to { transform: scaleY(1); } }
                @keyframes edm-draw { from { stroke-dashoffset: 1; } to { stroke-dashoffset: 0; } }
                @keyframes edm-wipex { from { clip-path: inset(0 100% -20% 0); }
                    to { clip-path: inset(0 0 -20% 0); } }
                @keyframes edm-wipe { from { clip-path: inset(0 100% 0 0 round 999px); }
                    to { clip-path: inset(0 0 0 0 round 999px); } }
                @keyframes edm-pop { from { opacity: 0; transform: scale(.85); } to { opacity: 1; transform: none; } }
                @keyframes edm-sheen { 0% { background-position: 0% 50%; } 50% { background-position: 100% 50%; }
                    100% { background-position: 0% 50%; } }
                @keyframes edm-shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
                @keyframes edm-pulse { 0%, 100% { box-shadow: 0 0 0 3px rgba(52,185,138,0.22); }
                    50% { box-shadow: 0 0 0 6px rgba(52,185,138,0.08); } }
                /* Registered so the angle can be interpolated — an unregistered
                   custom property would jump, not sweep. */
                @property --edm-a { syntax: '<angle>'; inherits: false; initial-value: 0deg; }
                @keyframes edm-border-run { to { --edm-a: 360deg; } }
                .edm-enter { animation: edm-fadeup .55s cubic-bezier(.2,.8,.2,1) both;
                    animation-delay: calc(var(--i, 0) * 70ms); }
                .edm-live { animation: edm-pulse 2.4s ease-in-out infinite; }

                /* ── Section eyebrows carry a copper hairline — the Meridian mark. ── */
                .edm-eyebrow-rule { display: inline-flex; align-items: center; gap: 7px; }
                .edm-eyebrow-rule::before { content: ''; width: 14px; height: 2px; border-radius: 2px;
                    background: var(--cat-2, #CA5F28); flex-shrink: 0; }

                /* ── KPI tiles ── */
                .edm-tile { position: relative; min-width: 0; overflow: hidden;
                    transition: background .15s ease; }
                .edm-tile:hover { background: var(--bg-hover); }

                /* ── Card border: a copper light travels each tile's edge. ──
                   The ring is a conic gradient clipped to a 1px frame by an
                   xor mask, so it lights the BORDER only — no wash across the
                   numerals, which have to stay reconciliation-legible.
                   The gradient is mostly transparent, so what reads is a short
                   arc drifting round the card rather than a lit outline.
                   Angle comes from a registered custom property because a bare
                   custom property cannot be interpolated; the var() fallback of
                   0deg keeps the ring valid (just static) where @property is
                   unsupported. Tiles share one duration but differ in aspect
                   ratio, so the arcs drift out of step on their own — no
                   staggered delays needed, and nothing marches in lockstep.
                   Gated on mask-composite: without it the gradient would paint
                   across the whole tile instead of its rim, so a browser that
                   cannot clip the ring simply gets no ring. */
                @supports ((mask-composite: exclude) or (-webkit-mask-composite: xor)) {
                .edm-tile::before {
                    content: ''; position: absolute; inset: 0; padding: 1.5px;
                    border-radius: inherit; pointer-events: none; z-index: 0;
                    background: conic-gradient(from var(--edm-a, 0deg),
                        transparent 0 50%,
                        color-mix(in srgb, var(--edm-ring) 26%, transparent) 62%,
                        var(--edm-ring) 72%,
                        color-mix(in srgb, var(--edm-ring) 26%, transparent) 82%,
                        transparent 92%);
                    -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
                            mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
                    -webkit-mask-composite: xor; mask-composite: exclude;
                    opacity: .55; transition: opacity .25s ease;
                    animation: edm-border-run 7s linear infinite; }
                /* Supporting tiles share ONE muted tone. They used to each catch a
                   copper arc, which — next to the green hero, the coloured delta
                   chips and the tinted sparklines — turned the strip into a row of
                   different-coloured cards. Colour now identifies the hero only;
                   the rest are told apart by their sparkline, not their frame. */
                .edm-tile { --edm-ring: color-mix(in srgb, var(--primary) 55%, var(--border)); }
                /* Hover brings the light forward and speeds it up — the tile
                   answers the pointer without moving or resizing. */
                .edm-tile:hover::before { opacity: .9; animation-duration: 2.8s; }
                /* The ratio row is context, not headline: no travelling light at all,
                   a recessed ground, and numerals a step below the strip above it. */
                .edm-ratiorow .edm-tile::before { content: none; }
                .edm-ratiorow { background: color-mix(in srgb, var(--bg-subtle) 55%, var(--bg-card)); }
                .edm-ratiorow .edm-tile-value { font-size: 16px !important; }
                /* The headline tile runs slower and reads in its own tone, so it
                   stays the calmest thing on the panel rather than the busiest. */
                .edm-tile-hero::before { animation-duration: 11s;
                    background: conic-gradient(from var(--edm-a, 0deg),
                        transparent 0 50%,
                        color-mix(in srgb, var(--success) 28%, transparent) 62%,
                        var(--success) 72%,
                        color-mix(in srgb, var(--success) 28%, transparent) 82%,
                        transparent 92%); }
                .edm-tile-hero.edm-tile-danger::before {
                    background: conic-gradient(from var(--edm-a, 0deg),
                        transparent 0 50%,
                        color-mix(in srgb, var(--danger) 28%, transparent) 62%,
                        var(--danger) 72%,
                        color-mix(in srgb, var(--danger) 28%, transparent) 82%,
                        transparent 92%); }
                }
                .edm-tile-value { font-family: var(--font-mono); font-variant-numeric: tabular-nums;
                    letter-spacing: -0.02em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
                    position: relative; z-index: 1; }
                .edm-tile-spark { position: absolute; right: 14px; bottom: 10px; opacity: .55;
                    pointer-events: none; transition: opacity .2s ease; }
                .edm-tile:hover .edm-tile-spark { opacity: .9; }
                .edm-tile-hero { background: color-mix(in srgb, var(--success) 7%, var(--bg-card));
                    box-shadow: inset 4px 0 0 var(--success); }
                .edm-tile-hero.edm-tile-danger { background: color-mix(in srgb, var(--danger) 7%, var(--bg-card));
                    box-shadow: inset 4px 0 0 var(--danger); }
                /* A slow sheen drifts across the headline tile — ambient, not alarming. */
                .edm-tile-hero::after { content: ''; position: absolute; inset: 0; pointer-events: none;
                    background: linear-gradient(115deg, transparent 30%,
                        color-mix(in srgb, var(--success) 10%, transparent) 50%, transparent 70%);
                    background-size: 250% 100%; animation: edm-sheen 9s ease-in-out infinite; }
                .edm-tile-hero.edm-tile-danger::after { background-image: linear-gradient(115deg, transparent 30%,
                    color-mix(in srgb, var(--danger) 10%, transparent) 50%, transparent 70%); }
                .edm-tile-hero .edm-tile-spark { opacity: .7; }
                .edm-spark-area { opacity: .12; }
                .edm-spark-line { stroke-dasharray: 1; stroke-dashoffset: 0;
                    animation: edm-draw 1s cubic-bezier(.2,.8,.2,1) both; }
                .edm-spark-dot { animation: edm-pop .3s ease-out both; animation-delay: .9s; }

                /* ── Delta chips ── */
                .edm-delta { display: inline-flex; align-items: center; gap: 4px;
                    padding: 2px 7px; border-radius: 999px; font-family: var(--font-mono);
                    font-variant-numeric: tabular-nums; font-size: 10.5px; font-weight: 700;
                    white-space: nowrap; animation: edm-pop .3s ease-out both; }
                .edm-delta-good { color: var(--success-text); background: var(--success-bg); }
                .edm-delta-bad { color: var(--danger-text); background: var(--danger-bg); }
                .edm-delta-flat { color: var(--text-secondary); background: var(--bg-subtle); }
                .edm-delta-lbl { font-family: var(--font-ui); font-weight: 500; font-size: 9.5px;
                    opacity: .75; margin-left: 1px; }
                .edm-pill { display: inline-flex; align-items: center; padding: 1px 7px;
                    border-radius: 999px; font-family: var(--font-mono); font-variant-numeric: tabular-nums;
                    font-size: 11px; font-weight: 700; color: var(--text);
                    background: color-mix(in srgb, var(--text) 7%, var(--bg-card));
                    border: 1px solid color-mix(in srgb, var(--text) 16%, transparent);
                    letter-spacing: .01em; white-space: nowrap; }
                /* Legend pills carry the hue of their own segment, so the interchange /
                   scheme / gateway share reads as part of the ribbon rather than as
                   greyed-out chrome — which is how text-secondary on bg-subtle read. */
                .edm-pill-tint { color: var(--text);
                    background: color-mix(in srgb, var(--pill-ink, var(--text)) 13%, var(--bg-card));
                    border-color: color-mix(in srgb, var(--pill-ink, var(--text)) 40%, transparent); }

                /* ── Fee ribbon ── */
                .edm-ribbon { display: flex; border-radius: 999px; overflow: hidden;
                    background: var(--border-light, var(--border));
                    animation: edm-wipe .8s cubic-bezier(.2,.8,.2,1) both; }
                .edm-ribbon-seg { position: relative; display: flex; align-items: center; justify-content: center;
                    border-right: 1px solid var(--bg-card); min-width: 0;
                    box-shadow: inset 0 1px 0 rgba(255,255,255,.28), inset 0 -1px 0 rgba(0,0,0,.10);
                    transition: filter .15s ease; }
                .edm-ribbon-seg:hover { filter: brightness(1.08); }
                .edm-ribbon-lbl { font-family: var(--font-mono); font-size: 10px; font-weight: 700;
                    color: #fff; text-shadow: 0 1px 2px rgba(0,0,0,.55), 0 0 3px rgba(0,0,0,.4);
                    letter-spacing: .02em; }

                /* ── Mix strips ── */
                .edm-rank { font-family: var(--font-mono); font-size: 9.5px; font-weight: 700;
                    color: var(--text-muted); letter-spacing: .04em; width: 18px; flex-shrink: 0; }
                .edm-track { height: 6px; border-radius: 999px; overflow: hidden;
                    background: var(--border-light, var(--border)); }
                .edm-mix-fill { height: 100%; border-radius: 999px; transform-origin: left center;
                    animation: edm-growx .6s cubic-bezier(.2,.8,.2,1) both;
                    animation-delay: calc(var(--i, 0) * 60ms); }
                .edm-mix-row { animation: edm-fadeup .4s ease-out both;
                    animation-delay: calc(var(--i, 0) * 50ms); }

                /* ── Selection chips: border tone = that day's net margin ── */
                .edm-daychip { display: inline-flex; align-items: center; gap: 5px; padding: 2px 8px;
                    border-radius: 999px; font-family: var(--font-mono); font-size: 10.5px; font-weight: 600;
                    color: var(--text); background: var(--bg-card); border: 1px solid var(--border);
                    cursor: pointer; animation: edm-pop .25s ease-out both; transition: background .12s ease; }
                .edm-daychip:hover { background: var(--bg-hover); }
                .edm-daychip::before { content: ''; width: 6px; height: 6px; border-radius: 50%;
                    background: var(--border); }
                .edm-daychip-good { border-color: color-mix(in srgb, var(--success) 55%, var(--border)); }
                .edm-daychip-good::before { background: var(--success); }
                .edm-daychip-bad { border-color: color-mix(in srgb, var(--danger) 55%, var(--border)); }
                .edm-daychip-bad::before { background: var(--danger); }

                /* ── Masthead context strip ── */
                .edm-mast-ctx { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 0;
                    margin-top: 10px; font-family: var(--font-mono); font-size: 10.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .edm-mast-ctx > span + span::before { content: '·'; margin: 0 8px; opacity: .6; }
                .edm-mast-ctx b { font-weight: 600; color: var(--table-head-text, #EEF3FC); }

                /* ── Drawer head: same navy as the page masthead ── */
                .edm-drawer-head { background: var(--edm-mast-bg,
                        linear-gradient(150deg, #212F4B 0%, #1C263C 55%, #1C2841 100%));
                    color: var(--table-head-text, #EEF3FC); padding: 18px 20px;
                    display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
                .edm-badge { display: inline-flex; align-items: center; gap: 5px; padding: 2px 8px;
                    border-radius: 5px; font-family: var(--font-mono); font-size: 10.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC); background: rgba(255,255,255,.10);
                    border: 1px solid rgba(255,255,255,.18); }
                .edm-badge small { font-size: 8.5px; letter-spacing: .12em; opacity: .6; font-weight: 700; }

                /* ── Skeleton bones ── */
                .edm-bone { background: linear-gradient(90deg, var(--bg-subtle) 25%,
                        color-mix(in srgb, var(--primary) 12%, var(--bg-subtle)) 50%, var(--bg-subtle) 75%);
                    background-size: 400% 100%; animation: edm-shimmer 1.8s ease-in-out infinite; }

                /* ── Chart: gradient bars that rise in; hover card ── */
                .edm-bar-fill { background: linear-gradient(180deg, var(--cat-1),
                        color-mix(in srgb, var(--cat-1) 55%, transparent));
                    transform-origin: bottom center;
                    animation: edm-growy .55s cubic-bezier(.2,.8,.2,1) both;
                    animation-delay: calc(var(--i, 0) * 14ms); }
                .edm-bar-loss { background: linear-gradient(180deg, var(--danger),
                        color-mix(in srgb, var(--danger) 55%, transparent)); }
                .edm-bar-hov .edm-bar-fill { filter: brightness(1.12); }
                /* No stroke-dasharray here: with vectorEffect="non-scaling-stroke"
                   the dash pattern is measured in screen units, so a pathLength=1
                   dash renders the margin line as a 1px dotted smear. The draw-in
                   is a clip wipe on the SVG instead, which leaves the stroke solid. */
                .edm-chart-line-draw { animation: edm-wipex 1.1s ease-out both; }
                .edm-axis-hov { color: var(--text); font-weight: 700; }
                .edm-tip { position: absolute; bottom: calc(100% + 8px); transform: translateX(-50%);
                    min-width: 168px; padding: 9px 11px; border-radius: var(--radius-md);
                    background: var(--bg-card); border: 1px solid var(--border);
                    box-shadow: var(--shadow-pop); z-index: 5; pointer-events: none;
                    animation: edm-pop .15s ease-out both; font-size: 11px; }
                .edm-tip-flip { transform: translateX(-100%); }
                .edm-tip-date { font-family: var(--font-mono); font-size: 9.5px; font-weight: 700;
                    letter-spacing: .1em; text-transform: uppercase; color: var(--text-muted);
                    margin-bottom: 5px; }
                .edm-tip-row { display: flex; justify-content: space-between; gap: 14px;
                    color: var(--text-secondary); padding: 1.5px 0; }
                .edm-tip-row .edm-num { color: var(--text); font-weight: 600; }
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
                /* A picked day is GREEN, not the page's navy primary: selection is
                   a "this is included" signal and reads faster in a colour the
                   rest of the chrome does not already use. One token so the
                   grid, the drag preview and the hover border stay in step.

                   A LIGHT green fill with dark green ink on top: the pale
                   ground reads as "included" without shouting, and the ink
                   (#0B4A36 on #A8E6C9) clears 4.5:1 in both themes — a white
                   numeral on this fill would not. */
                .edm-cal, .edm-seg {
                    --cal-pick: var(--select-green, #A8E6C9);
                    --cal-pick-ink: var(--select-green-ink, #0B4A36);
                    --cal-pick-edge: color-mix(in srgb, var(--cal-pick) 72%, #0B4A36); }
                .edm-cell-on { background: var(--cal-pick) !important;
                    border-color: var(--cal-pick-edge) !important;
                    box-shadow: 0 0 0 1px var(--cal-pick-edge); }
                .edm-cell-on .edm-cell-num { color: var(--cal-pick-ink); }
                .edm-cell:hover { border-color: var(--cal-pick); }
                .edm-cell-nodata:hover { border-color: var(--cal-pick); }
                .edm-cell-drag { outline: 2px dashed var(--cal-pick); outline-offset: 1px; }
                /* Weekend columns sit on a faint ground so the tenant's own
                   working week is visible without reading the headers. */
                .edm-cell-we:not(.edm-cell-on) { box-shadow: inset 0 0 0 1px
                    color-mix(in srgb, var(--chart-alt, #64748B) 22%, transparent); }
                .edm-cell-loss { position: absolute; left: 5px; right: 5px; bottom: 3px;
                    height: 2.5px; border-radius: 2px; background: var(--danger); }
                .edm-cell-on .edm-cell-loss { background: var(--danger); opacity: 0.9; }

                /* ── Table ──
                   The grid is a nested scroll box, so it needs a scrollbar you can
                   actually see and grab: overlay bars on Windows fade out and the
                   box then reads as "stuck". Gutter is reserved so the sticky
                   header does not shift when the bar appears. */
                .edm-scroll { overflow: auto; max-height: 72vh; overscroll-behavior: contain;
                    scrollbar-gutter: stable; scrollbar-width: thin;
                    scrollbar-color: color-mix(in srgb, var(--text) 34%, transparent) transparent; }
                .edm-scroll::-webkit-scrollbar { width: 12px; height: 12px; }
                .edm-scroll::-webkit-scrollbar-track { background: color-mix(in srgb, var(--text) 4%, transparent); }
                .edm-scroll::-webkit-scrollbar-thumb {
                    background: color-mix(in srgb, var(--text) 30%, transparent);
                    border-radius: 999px; border: 3px solid transparent; background-clip: content-box; }
                .edm-scroll::-webkit-scrollbar-thumb:hover {
                    background: color-mix(in srgb, var(--text) 50%, transparent); background-clip: content-box; }
                .edm-scroll::-webkit-scrollbar-corner { background: transparent; }
                .edm-scroll:focus-visible { outline: 2px solid var(--focus, var(--cat-1)); outline-offset: -2px; }
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
                    border-radius: 999px; opacity: 0.6; transform-origin: right center;
                    background: linear-gradient(90deg, color-mix(in srgb, var(--cat-1) 35%, transparent), var(--cat-1));
                    animation: edm-growx .5s cubic-bezier(.2,.8,.2,1) both; }
                /* Zebra at 3% keeps a 10-column row readable across the scroll box. */
                .edm-table tbody tr:nth-child(even):not(.edm-total-row) td { background:
                    color-mix(in srgb, var(--text) 3%, var(--bg-card)); }
                .edm-table tbody tr:nth-child(even):not(.edm-total-row) .edm-c1,
                .edm-table tbody tr:nth-child(even):not(.edm-total-row) .edm-c2,
                .edm-table tbody tr:nth-child(even):not(.edm-total-row) .edm-c3 { background:
                    color-mix(in srgb, var(--text) 3%, var(--bg-card)); }
                .edm-table tbody tr:hover td, .edm-table tbody tr:hover .edm-c1,
                .edm-table tbody tr:hover .edm-c2, .edm-table tbody tr:hover .edm-c3 { background: var(--bg-hover); }
                /* Net margin cells are tinted on a diverging scale — the table scans
                   as a heatmap: deeper green = stronger margin, red = a loss row. */
                .edm-nm-cell { box-shadow: inset 0 0 0 100vw
                    color-mix(in srgb, var(--success) calc(var(--heat, 0) * 1%), transparent); }
                .edm-nm-loss { box-shadow: inset 0 0 0 100vw color-mix(in srgb, var(--danger) 14%, transparent); }
                .edm-row-in { animation: edm-fadeup .35s ease-out both;
                    animation-delay: calc(min(var(--i, 0), 18) * 22ms); }
                /* Totals stay pinned to the bottom of the scroll box — the figure
                   you are checking a row against should never scroll away. */
                .edm-total-row td { position: sticky; bottom: 0; z-index: 2;
                    background: var(--wash); font-weight: 700;
                    border-top: 1px solid var(--border); border-bottom: none; }

                @media (max-width: 900px) {
                    .edm-table .edm-c3 { position: static; box-shadow: none; min-width: 150px; }
                }
                @media (prefers-reduced-motion: reduce) {
                    .edm-cell, .edm-table tbody tr, .edm-panel, .edm-tile { transition: none; }
                    .edm-enter, .edm-live, .edm-spark-line, .edm-spark-dot, .edm-delta, .edm-ribbon,
                    .edm-mix-fill, .edm-mix-row, .edm-daychip, .edm-bone, .edm-bar-fill,
                    .edm-chart-line-draw, .edm-tip, .edm-vol-bar, .edm-row-in, .edm-tile-hero::after,
                    .edm-tile::before {
                        animation: none; }
                    .edm-panel-lift:hover { transform: none; }
                }
            `}</style>

            {/* ── Masthead + command bar: one executive header block.
                NO overflow:hidden here — it would clip the filter dropdowns;
                the corner rounding is done per child instead. ── */}
            <section className="edm-panel edm-hdrblock edm-enter" style={{ marginBottom: 12, '--i': 0 }}>
                <div className="edm-mast">
                    <div>
                        <div className="edm-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <span className="edm-live" style={{
                                width: 6, height: 6, borderRadius: '50%', background: 'var(--rail-fresh, #34B98A)',
                                boxShadow: '0 0 0 3px rgba(52,185,138,0.22)',
                            }} />
                            Executive · Daily ledger
                        </div>
                        <h1>Daily Merchant Dashboard</h1>
                        <p className="edm-mast-sub">
                            Daily merchant volume, transaction and profitability performance
                        </p>
                        {/* Freshness first: how current the figures are, before what they say. */}
                        <div className="edm-mast-ctx">
                            <span>Latest loaded <b>{latest ? longDate(latest) : '—'}</b></span>
                            {month && allMonthDays.length > 0 && (
                                <span><b>{monthDates.length}</b> of {allMonthDays.length} days loaded in {monthLabel(month)}</span>
                            )}
                            {refreshedAt && <span>Refreshed <b>{refreshedAt}</b></span>}
                            {/* This ledger is sliced by card dimensions, which DCC and
                                rental (booked per merchant) cannot follow. Net Spread
                                for the same days lives on its merchant-grain twin. */}
                            <button type="button" className="edm-focus"
                                onClick={() => navigate('/executive/net-spread?' + new URLSearchParams({
                                    ...(month ? { month } : {}),
                                    ...(selectedDates.length ? { dates: selectedDates.join(',') } : {}),
                                }).toString())}
                                title="Open the Net Spread page (net margin + DCC + rental) for the same month and days"
                                style={{ background: 'rgba(241,245,249,0.10)', border: '1px solid rgba(241,245,249,0.22)',
                                    color: 'var(--table-head-text, #EEF3FC)', borderRadius: 999, padding: '2px 10px',
                                    fontSize: 11, fontWeight: 600, cursor: 'pointer' }}>
                                Net Spread for these days →
                            </button>
                        </div>
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
            <section className="edm-panel edm-enter" style={{ padding: '14px 18px 12px', marginBottom: 12, '--i': 1 }}>
                <div style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    gap: 12, flexWrap: 'wrap', marginBottom: 12,
                }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                        <span className="edm-eyebrow edm-eyebrow-rule">Business date</span>
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
                        {/* One control, one truth: whichever segment is lit is
                            what the table below is actually showing. "Custom"
                            is not clickable — it only reports a hand-picked
                            selection, and lights up on its own. */}
                        <div className="edm-seg" role="group" aria-label="Day selection">
                            {[
                                { id: 'all',      label: 'All days',  run: selectAllDays,
                                  title: `Every day in ${monthLabel(month) || 'the month'}` },
                                { id: 'weekdays', label: 'Weekdays',  title: `All days except ${week.longLabel}`,
                                  run: () => applyDates(allMonthDays.filter(d => !week.isWeekend(d))) },
                                { id: 'weekends', label: 'Weekends',  title: week.longLabel,
                                  run: () => applyDates(allMonthDays.filter(week.isWeekend)) },
                            ].map(s => (
                                <button key={s.id} className="edm-focus edm-seg-btn" onClick={s.run}
                                    title={s.title}
                                    disabled={s.id !== 'all' && !allMonthDays.length}
                                    aria-pressed={dayMode === s.id}
                                    data-on={dayMode === s.id ? 'true' : 'false'}>
                                    {s.label}
                                </button>
                            ))}
                            {/* Reports a hand-picked selection rather than offering
                                one — it lights up on its own and carries the count,
                                which is more use than the word "Custom". */}
                            {dayMode === 'custom' && (
                                <span className="edm-seg-btn edm-seg-custom" data-on="true"
                                    title="Days picked by hand on the calendar or the chart">
                                    {selectedDates.length} day{selectedDates.length === 1 ? '' : 's'}
                                </span>
                            )}
                        </div>
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
                            {week.headers.map(h => (
                                <span key={h.key} className={`edm-cal-h${h.weekend ? ' edm-cal-h-we' : ''}`}>
                                    {h.label}
                                </span>
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
                                const wknd = week.isWeekend(d);
                                return (
                                    <button key={d}
                                        className={`edm-cell${picked ? ' edm-cell-on' : ''}${inDrag ? ' edm-cell-drag' : ''}${hasData ? '' : ' edm-cell-nodata'}${wknd ? ' edm-cell-we' : ''}`}
                                        style={!picked && hasData ? {
                                            background: `color-mix(in srgb, var(--cat-1) ${dimmed ? Math.max(heat - 6, 3) : heat}%, var(--bg-card))`,
                                        } : undefined}
                                        onClick={() => handleDayClick(d)}
                                        onPointerDown={e => handleDayPointerDown(d, e)}
                                        onPointerEnter={() => handleDayPointerEnter(d)}
                                        aria-pressed={picked}
                                        aria-label={`${longDate(d)}${wknd ? ', weekend' : ''}${hasData ? (loss ? ', closed at a loss' : '') : ', no transactions'}`}
                                        title={hasData
                                            ? `${longDate(d)}${wknd ? ' · weekend' : ''}\nVolume ${money(vol)}\nTransactions ${num(t?.count).toLocaleString()}\nNet Margin ${money(num(t?.nm))}`
                                            : `${longDate(d)}${wknd ? ' · weekend' : ''}\nNo transactions loaded`}>
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
                            minWidth: 190, flex: '0 1 220px', maxWidth: 260, paddingTop: 2,
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
                                {/* The picked days as chips, each wearing its own margin tone —
                                    the selection tells its story before the figures load. */}
                                {selectedDates.length > 0 && (
                                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, marginTop: 8 }}>
                                        {selectedDates.slice(0, 8).map((d, i) => (
                                            <button key={d} type="button"
                                                className={`edm-daychip edm-daychip-${dayTone(d)}`}
                                                style={{ animationDelay: `${i * 30}ms` }}
                                                onClick={() => toggleDate(d)}
                                                title={`Remove ${longDate(d)} from the selection`}
                                                aria-label={`Remove ${longDate(d)} from the selection`}>
                                                {pillLabel(d)}
                                                <X size={9} style={{ opacity: .6 }} />
                                            </button>
                                        ))}
                                        {selectedDates.length > 8 && (
                                            <span className="edm-pill">+{selectedDates.length - 8}</span>
                                        )}
                                    </div>
                                )}
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
                                    no transactions · <span style={{ color: 'var(--chart-alt, #64748B)', fontWeight: 700 }}>
                                        {week.label}</span> = weekend
                                </span>
                            </div>
                        </div>

                        {/* The month as a curve — same click target as the grid. */}
                        <DayTrendChart days={allMonthDays} trendByDate={trendByDate}
                            selectedDates={selectedDates} onToggle={toggleDate}
                            money={money} week={week} animKey={`${month}|${JSON.stringify(filters)}`} />
                    </div>
                ) : (
                    <div style={{ fontSize: 12, color: 'var(--text-muted)', padding: '18px 0' }}>
                        {month ? 'No business dates were loaded in this month.' : 'Loading business dates'}
                    </div>
                )}
            </section>

            {/* The skeleton is for the FIRST load only. Swapping the whole
                summary + mix + table out for a pulsing placeholder on every
                refetch made the page flash on each day click, filter change,
                sort and page turn — very visible against the dark theme. Once
                there is something to show, the existing figures stay on screen
                and simply dim while the next set arrives. */}
            {firstLoad ? <LedgerSkeleton /> : error ? (
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
                <div className={refreshing ? 'edm-refreshing' : undefined} aria-busy={refreshing}>
                    {/* ── Ledger summary: headline figures, the fee ribbon, ratios ── */}
                    {totals && (
                        <section className="edm-panel edm-enter" style={{ marginBottom: 12, overflow: 'hidden', '--i': 2 }}>
                            {/* Net margin leads: the one figure an executive reads first
                                gets the hero treatment; the other four support it. */}
                            <div style={{
                                display: 'grid',
                                gridTemplateColumns: 'minmax(220px, 1.35fr) repeat(auto-fit, minmax(160px, 1fr))',
                                borderBottom: '1px solid var(--border-light, var(--border))',
                            }}>
                                <div style={cellDiv}>
                                    <Metric hero label="Net margin"
                                        raw={num(totals.nm)} format={fmt.currency}
                                        sub={marginPct == null ? 'no volume' : `${marginPct.toFixed(2)}% of volume · ${daysCovered} day${daysCovered === 1 ? '' : 's'}`}
                                        tone={num(totals.nm) >= 0 ? 'success' : 'danger'}
                                        title={fullNum(totals.nm, currencySymbol)}
                                        delta={deltas?.nm} deltaLabel={deltas?.label}
                                        series={kpiSeries.nm} animKey={animKey}
                                        sparkColor={num(totals.nm) >= 0 ? 'var(--success)' : 'var(--danger)'} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="Volume"
                                        raw={num(totals.volume)} format={fmt.currency}
                                        sub={`${displayCurrencyCode(currencyCode) || currencySymbol || ''} · ${daysCovered} day${daysCovered === 1 ? '' : 's'}`}
                                        title={fullNum(totals.volume, currencySymbol)}
                                        delta={deltas?.volume} deltaLabel={deltas?.label}
                                        series={kpiSeries.volume} sparkColor="var(--cat-1)" animKey={animKey} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="Transactions"
                                        raw={num(totals.count)} format={(v) => Math.round(v).toLocaleString()}
                                        sub={`${totalRows.toLocaleString()} merchant row${totalRows === 1 ? '' : 's'}`}
                                        delta={deltas?.count} deltaLabel={deltas?.label}
                                        series={kpiSeries.count} sparkColor="var(--chart-4, #7191CE)" animKey={animKey} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="MSF"
                                        raw={num(totals.msf)} format={fmt.currency}
                                        sub="gross fee income" title={formatMsf(totals.msf, currencySymbol)}
                                        delta={deltas?.msf} deltaLabel={deltas?.label}
                                        series={kpiSeries.msf} sparkColor="var(--cat-4, #B08C1E)" animKey={animKey} />
                                </div>
                                <div>
                                    <Metric wide label="Cost of sale"
                                        raw={costTotal} format={fmt.currency}
                                        sub="interchange + scheme + gateway"
                                        title={fullNum(costTotal, currencySymbol)}
                                        delta={deltas?.cost} deltaLabel={deltas?.label} invertDelta
                                        series={kpiSeries.cost} sparkColor="var(--cat-2, #CA5F28)" animKey={animKey} />
                                </div>
                            </div>

                            <div style={{ padding: '15px 20px 16px' }}>
                                <div style={{
                                    display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 12,
                                    flexWrap: 'wrap',
                                }}>
                                    <span className="edm-eyebrow edm-eyebrow-rule">Where the MSF went</span>
                                    <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                        every {displayCurrencyCode(currencyCode) || currencySymbol || 'unit'} of fee income, split into what was paid away and what was kept
                                    </span>
                                </div>
                                <FeeRibbon totals={totals} money={money} share={share} animKey={animKey} />
                            </div>

                            <div className="edm-ratiorow" style={{
                                display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                                borderTop: '1px solid var(--border-light, var(--border))',
                            }}>
                                <div style={cellDiv}>
                                    <Metric label="Net margin rate"
                                        value={marginPct == null ? '—' : `${marginPct.toFixed(2)}%`}
                                        sub="net margin ÷ volume"
                                        tone={marginPct == null ? undefined : marginPct >= 0 ? 'success' : 'danger'} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric label="MSF rate"
                                        value={msfBps == null ? '—' : `${msfBps.toFixed(1)} bps`}
                                        sub="MSF ÷ volume" />
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
                        <section className="edm-panel edm-panel-lift edm-enter" style={{
                            display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))',
                            gap: 26, padding: '16px 20px', marginBottom: 12, '--i': 3,
                        }}>
                            <MixStrip title="Scheme" hue="var(--cat-1)" rows={mix.scheme} money={money} share={share} animKey={animKey} />
                            <MixStrip title="Card type" hue="var(--chart-4, #7191CE)" rows={mix.cardType} money={money} share={share} animKey={animKey} />
                            <MixStrip title="Destination" hue="var(--chart-alt, #64748B)" rows={mix.destination} money={money} share={share} animKey={animKey} />
                            <WeekSplit days={allMonthDays} trendByDate={trendByDate}
                                week={week} money={money} share={share} animKey={animKey} />
                        </section>
                    ) : null}

                    {/* ── Merchant table ── */}
                    <div className="edm-panel edm-enter" style={{ overflow: 'hidden', '--i': 4 }}>
                        <div className="edm-scroll" tabIndex={0} role="region"
                            aria-label="Merchant rows — scrollable">
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
                                <tbody key={rowsKey}>
                                    {rows.map((r, i) => (
                                        <tr key={`${r.mid}-${r.sid}-${i}`} onClick={() => setDetailRow(r)}
                                            className="edm-row-in" style={{ '--i': i }}>
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
                                            <td className={`edm-cell-num ${num(r.nm) < 0 ? 'edm-nm-loss' : 'edm-nm-cell'}`}
                                                style={{ '--heat': nmHeat(r.nm) }}>
                                                <NmCell v={r.nm} />
                                            </td>
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
                </div>
            )}

            {/* ── Merchant drilldown ────────────────────────────────── */}
            <Drawer anchor="right" open={!!detailRow} onClose={() => setDetailRow(null)}
                PaperProps={{ sx: {
                    width: { xs: '100%', sm: 440 }, bgcolor: 'var(--bg-card)',
                    borderLeft: '1px solid var(--border)', backgroundImage: 'none',
                } }}>
                {detailRow && (
                    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
                        <div className="edm-drawer-head">
                            <div style={{ minWidth: 0 }}>
                                <div className="edm-mast-eyebrow">Merchant</div>
                                <div style={{
                                    marginTop: 6, fontSize: 17, fontWeight: 700, letterSpacing: '-0.015em',
                                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                                }}>
                                    {detailRow.merchantName || '—'}
                                </div>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
                                    <span className="edm-badge"><small>MID</small>{detailRow.mid || '—'}</span>
                                    <span className="edm-badge"><small>SID</small>{detailRow.sid || '—'}</span>
                                </div>
                                <div style={{
                                    marginTop: 8, fontSize: 11.5,
                                    color: 'color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent)',
                                }}>
                                    {selectionText !== '—' ? selectionText : ''}
                                </div>
                            </div>
                            <IconButton size="small" onClick={() => setDetailRow(null)}
                                sx={{ color: 'var(--table-head-text, #EEF3FC)', opacity: .8 }} aria-label="Close">
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
                                <div className="edm-eyebrow edm-eyebrow-rule" style={{ marginBottom: 10 }}>Fee stack</div>
                                <FeeRibbon totals={detailRow} money={money} share={share} compact
                                    animKey={`${detailRow.mid}-${detailRow.sid}`} />
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
                                    Net margin = MSF − interchange fee − scheme fee − payment gateway fee,
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
