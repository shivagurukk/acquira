import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Drawer, IconButton } from '@mui/material';
import {
    RefreshCw, Search, Download, ChevronLeft, ChevronRight,
    ChevronUp, ChevronDown, X, ExternalLink, LifeBuoy,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import api from '../../api/axios';
import EmptyState from '../../components/EmptyState';
import MarginGlossaryHint from '../../components/MarginGlossary';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    createFmt, formatMsf, resolveDecimals,
    isUsdDisplay, convertForDisplay, displayCurrencyCode, usdRateInfo,
} from '../../utils/formatters';
import { weekRules } from '../../utils/weekRules';

/* ════════════════════════════════════════════════════════════════════
   Net Spread Dashboard — a replica of the Executive Daily Merchant
   layout at MERCHANT grain, extended with the ancillary revenue legs:

     Net Spread = Net Margin (MSF − ICF − SF − PG)
                + DCC acquirer share + rental income

   The page's story: merchants who look loss-making on interchange
   spread alone often turn positive once DCC and rentals are counted —
   those rows carry a "rescued" badge, and the header counts them.

   Data: POST /api/business/net-spread (sum_daily_merchant — summary
   read only; the ancillary columns are maintained by AncillarySql from
   fact_dcc_revenue / fact_rental). NM is the batch-computed
   total_margin; the DCC merchant share is informational only and is
   never added to the spread. Original /executive/daily-merchant page
   is untouched — this is its sibling, not a change to it.
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));
const fullNum = (v, sym = '') => {
    if (!sym) return Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 0 });
    const d = isUsdDisplay(sym) ? 2 : resolveDecimals();
    return displayCurrencyCode(sym) + ' ' + convertForDisplay(v, sym).toLocaleString('en-US',
        { minimumFractionDigits: d, maximumFractionDigits: d });
};

/* Fixed column set — merchant grain, so no SID column; the three revenue
   columns land after Net Margin and Net Spread closes the row. */
const COLUMNS = [
    { key: 'mid',    label: 'MID',   align: 'left',  sticky: 1 },
    { key: 'name',   label: 'Name',  align: 'left',  sticky: 2 },
    { key: 'volume', label: 'Vol',   align: 'right' },
    { key: 'count',  label: 'Count', align: 'right' },
    { key: 'msf',    label: 'MSF',   align: 'right' },
    { key: 'icf',    label: 'Interchange Fee',   align: 'right', wrap: true },
    { key: 'sf',     label: 'Scheme Fee',        align: 'right', wrap: true },
    { key: 'pg',     label: 'Payment Gateway Fee', align: 'right', wrap: true },
    { key: 'nm',     label: 'Net Margin',        align: 'right', wrap: true },
    { key: 'dcc',    label: 'DCC (Acquirer)',    align: 'right', wrap: true },
    { key: 'rental', label: 'Rental',            align: 'right' },
    { key: 'spread', label: 'Net Spread',        align: 'right', wrap: true },
];

const FEE_LABELS = {
    msf: 'MSF',
    icf: 'Interchange Fee',
    sf:  'Scheme Fee',
    pg:  'Payment Gateway Fee',
    nm:  'Net Margin',
    dcc: 'DCC (Acquirer Share)',
    rental: 'Rental Income',
    spread: 'Net Spread',
};

const PAGE_SIZES = [25, 50, 100];

const costOf = (t) => num(t.icf) + num(t.sf) + num(t.pg);

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

/* ── Motion (identical conventions to the Daily Merchant page) ── */
const REDUCED_MOTION = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false;

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

/* ── Spread ribbon: the Daily Merchant fee ribbon EXTENDED. The pool is
   MSF + DCC + rentals; the pay-away fees step down one ramp, the kept
   margin breaks to jade, and the two ancillary legs continue in their own
   green shades — the bar walks left-to-right from cost to Net Spread. ── */
const SpreadRibbon = ({ totals, money, share, compact = false, animKey }) => {
    const icf = num(totals?.icf), sf = num(totals?.sf), pg = num(totals?.pg);
    const nm = num(totals?.nm), dcc = num(totals?.dcc), rental = num(totals?.rental);
    const pool = icf + sf + pg + Math.max(nm, 0) + dcc + rental;
    if (pool <= 0) return null;
    const segs = [
        { key: 'icf', label: FEE_LABELS.icf, value: icf, color: 'var(--imp-1)' },
        { key: 'sf',  label: FEE_LABELS.sf,  value: sf,  color: 'var(--imp-2)' },
        { key: 'pg',  label: FEE_LABELS.pg,  value: pg,  color: 'var(--imp-3)' },
        ...(nm >= 0
            ? [{ key: 'nm', label: FEE_LABELS.nm, value: nm, color: 'var(--mix-margin)' }]
            : [{ key: 'loss', label: 'Margin Loss', value: Math.abs(nm), color: 'var(--danger)', overflow: true }]),
        { key: 'dcc',    label: FEE_LABELS.dcc,    value: dcc,    color: 'var(--success, #2E9E6B)' },
        { key: 'rental', label: FEE_LABELS.rental, value: rental, color: 'var(--cat-5, #4E8D7C)' },
    ];
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

/* ── Month shape: bars = volume, line = NET SPREAD (this page's headline
   measure, where the Daily Merchant page draws Net Margin). Same click
   targets as the calendar. ── */
const DayTrendChart = ({ days, trendByDate, selectedDates, onToggle, money, week, height = 132, animKey }) => {
    const picked = useMemo(() => new Set(selectedDates), [selectedDates]);
    const [hover, setHover] = useState(null);
    const rows = useMemo(() => days.map((d) => {
        const t = trendByDate.get(d);
        return { date: d, vol: num(t?.volume), count: num(t?.count), spread: num(t?.spread), has: !!t };
    }), [days, trendByDate]);

    if (!rows.length) return null;

    const maxVol = rows.reduce((a, r) => Math.max(a, r.vol), 0) || 1;
    const withData = rows.filter(r => r.has);
    const spHi = withData.reduce((a, r) => Math.max(a, r.spread), 0);
    const spLo = withData.reduce((a, r) => Math.min(a, r.spread), 0);
    const spPad = ((spHi - spLo) || Math.abs(spHi) || 1) * 0.08;
    const spSpan = ((spHi + spPad) - (spLo - spPad)) || 1;
    const spY = (v) => 100 - ((v - (spLo - spPad)) / spSpan) * 100;
    const xAt = (i) => ((i + 0.5) / rows.length) * 100;

    const segments = [];
    let run = [];
    rows.forEach((r, i) => {
        if (r.has) run.push(`${xAt(i).toFixed(3)},${spY(r.spread).toFixed(3)}`);
        else { if (run.length > 1) segments.push(run); run = []; }
    });
    if (run.length > 1) segments.push(run);

    const anySelected = selectedDates.length > 0;
    const zeroY = spLo < 0 && spHi > 0 ? spY(0) : null;
    const hov = hover != null ? rows[hover] : null;
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
                        <span style={{ width: 12, height: 2, borderRadius: 2, background: 'var(--success, #2E9E6B)' }} />
                        Net Spread
                    </span>
                </span>
            </div>

            <div className="edm-chart" style={{ height }} onMouseLeave={() => setHover(null)}>
                <div key={animKey} className="edm-chart-bars">
                    {rows.map((r, i) => {
                        const on = picked.has(r.date);
                        const dim = anySelected && !on;
                        const loss = r.has && r.spread < 0;
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
                                    ? `${longDate(r.date)}${week.isWeekend(r.date) ? ', weekend' : ''}, volume ${money(r.vol)}, net spread ${money(r.spread)}`
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
                            stroke="var(--success, #2E9E6B)" strokeWidth="2"
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
                                    <span>Net Spread</span>
                                    <span className="edm-num" style={{ color: hov.spread >= 0 ? 'var(--success-text)' : 'var(--danger-text)' }}>
                                        {money(hov.spread)}
                                    </span>
                                </div>
                            </>
                        ) : (
                            <div className="edm-tip-row" style={{ color: 'var(--text-muted)' }}>No transactions loaded</div>
                        )}
                    </div>
                )}
            </div>

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

/* ── Weekday vs weekend on the tenant's working week, measured on spread ── */
const WeekSplit = ({ days, trendByDate, week, money, share, animKey }) => {
    const stats = useMemo(() => {
        const blank = () => ({ days: 0, volume: 0, count: 0, spread: 0 });
        const acc = { weekday: blank(), weekend: blank() };
        days.forEach(d => {
            const t = trendByDate.get(d);
            if (!t) return;
            const b = acc[week.isWeekend(d) ? 'weekend' : 'weekday'];
            b.days += 1;
            b.volume += num(t.volume);
            b.count += num(t.count);
            b.spread += num(t.spread);
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
                            {b.days ? ` · Net Spread ${money(b.spread)}` : ''}
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

/* ── First-load skeleton (same geometry as the Daily Merchant page) ── */
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
                    {Array.from({ length: 6 }).map((_, i) => <Bone key={i} w={120} h={10} />)}
                </div>
            </div>
        </section>
        <section className="edm-panel" style={{ overflow: 'hidden' }}>
            <div style={{ display: 'flex', gap: 14, padding: '13px 14px', background: 'var(--bg-subtle)' }}>
                {Array.from({ length: 12 }).map((_, i) => <Bone key={i} w={i < 2 ? 90 : 64} h={10} />)}
            </div>
            {Array.from({ length: 8 }).map((_, i) => (
                <div key={i} style={{
                    display: 'flex', gap: 14, padding: '12px 14px',
                    borderBottom: '1px solid var(--border-light, var(--border))',
                }}>
                    {Array.from({ length: 12 }).map((_, j) => <Bone key={j} w={j < 2 ? 90 : 64} h={12} />)}
                </div>
            ))}
        </section>
    </div>
);

const NetSpreadDashboard = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion, homeCountryCode } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const navigate = useNavigate();

    const week = useMemo(() => weekRules(homeCountryCode), [homeCountryCode]);

    const [months, setMonths] = useState([]);
    const [latest, setLatest] = useState('');
    const [month, setMonth] = useState('');
    const [monthDates, setMonthDates] = useState([]);
    const [selectedDates, setSelectedDates] = useState([]);
    const [search, setSearch] = useState('');
    const [searchDraft, setSearchDraft] = useState('');
    const [lossOnly, setLossOnly] = useState(false);
    const [page, setPage] = useState(0);
    const [pageSize, setPageSize] = useState(50);
    const [sort, setSort] = useState('spread');
    const [dir, setDir] = useState('desc');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [exporting, setExporting] = useState(false);
    const [detailRow, setDetailRow] = useState(null);
    const [lastRefresh, setLastRefresh] = useState(null);

    const dp = isUsdDisplay(currencyCode) ? 2 : resolveDecimals(currencyDecimals, currencyCode);
    const money = useCallback((v) => convertForDisplay(Number(v || 0), currencyCode).toLocaleString('en-US',
        { minimumFractionDigits: dp, maximumFractionDigits: dp }), [dp, currencyCode]);
    const share = useCallback((v, total) =>
        !total ? '—' : `${((num(v) / total) * 100).toFixed(1)}%`, []);

    /* The table load waits for the calendar to resolve the default month/date;
       firing it immediately produced a second, thrown-away request (the first
       ran on the backend-default date, then the calendar answer changed the
       params) — the abort cancels the HTTP call but Postgres still does the
       work, doubling the cold-cache cost of every open. Same fix as the
       Daily Merchant page. */
    const [bootstrapped, setBootstrapped] = useState(false);
    const [searchParams, setSearchParams] = useSearchParams();

    /* ── Reference data: months that hold data (+ the latest date) ──
       A deep link (?month=YYYY-MM&dates=a,b,c&q=text — the Daily Merchant
       page's "Net Spread for these days" button) wins over the latest-date
       default on first load only; the params are then dropped from the URL
       so the tenant-switch reset below is not re-seeded from them. */
    useEffect(() => {
        let cancelled = false;
        const qpMonth = /^\d{4}-\d{2}$/.test(searchParams.get('month') || '') ? searchParams.get('month') : '';
        const qpDates = (searchParams.get('dates') || '').split(',').filter(d => /^\d{4}-\d{2}-\d{2}$/.test(d));
        const qpQ = (searchParams.get('q') || '').trim();
        const deepLinked = !!(qpMonth || qpDates.length);
        api.get('/business/net-spread/calendar')
            .then(res => {
                if (cancelled) return;
                const ms = res.data?.months || [];
                const lt = res.data?.latest || '';
                setMonths(ms); setLatest(lt);
                if (deepLinked) {
                    setMonth(qpMonth || qpDates[0].slice(0, 7));
                    setSelectedDates(qpDates);
                    if (qpQ) { setSearch(qpQ); setSearchDraft(qpQ); }
                    setSearchParams({}, { replace: true });
                } else if (lt) { setMonth(lt.slice(0, 7)); setSelectedDates([lt]); }
            })
            .catch(() => { /* the table still loads on the backend default */ })
            .finally(() => { if (!cancelled) setBootstrapped(true); });
        return () => { cancelled = true; };
    }, [tenantVersion]);

    useEffect(() => {
        if (!month) { setMonthDates([]); return; }
        let cancelled = false;
        api.get('/business/net-spread/calendar', { params: { month } })
            .then(res => { if (!cancelled) setMonthDates(res.data?.dates || []); })
            .catch(() => { if (!cancelled) setMonthDates([]); });
        return () => { cancelled = true; };
    }, [month, tenantVersion]);

    const dateParams = useMemo(() => {
        if (selectedDates.length) return { dates: selectedDates.join(',') };
        if (month) return { month };
        return {};
    }, [selectedDates, month]);

    const load = useCallback(async (signal) => {
        setLoading(true); setError(null);
        try {
            const res = await api.post('/business/net-spread', {}, {
                signal,
                params: {
                    ...dateParams, page, size: pageSize, sort, dir, lossOnly,
                    ...(search.trim() ? { search: search.trim() } : {}),
                },
            });
            setData(res.data);
            setLastRefresh(new Date());
        } catch (e) {
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            setError(e?.response?.data?.message || 'Could not load the dashboard.');
        } finally {
            setLoading(false);
        }
    }, [dateParams, page, pageSize, sort, dir, lossOnly, search]);

    useEffect(() => {
        if (!bootstrapped) return;
        const ac = new AbortController();
        load(ac.signal);
        return () => ac.abort();
    }, [load, tenantVersion, bootstrapped]);

    useEffect(() => {
        setPage(0); setMonth(''); setSelectedDates([]); setDetailRow(null);
        setSearch(''); setSearchDraft(''); setLossOnly(false);
        setBootstrapped(false);   // wait for the new tenant's calendar
    }, [tenantVersion]);

    const toggleDate = (iso) => {
        setSelectedDates(l => l.includes(iso) ? l.filter(d => d !== iso) : [...l, iso].sort());
        setPage(0); setDetailRow(null);
    };
    const pickMonth = (ym) => {
        if (!ym || ym === month) return;
        setMonth(ym); setSelectedDates([]); setPage(0); setDetailRow(null);
    };
    const selectAllDays = () => { setSelectedDates([]); setPage(0); setDetailRow(null); };

    /* Range selection on the calendar — drag / shift-click / quick picks,
       identical mechanics to the Daily Merchant page. */
    const dragRef = useRef(null);
    const [dragRange, setDragRange] = useState(null);
    const lastClickRef = useRef(null);
    const suppressClickRef = useRef(false);

    const applyDates = (next) => {
        setSelectedDates([...new Set(next)].sort());
        setPage(0); setDetailRow(null);
    };

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
        if (e.pointerType !== 'mouse') return;
        e.preventDefault();
        suppressClickRef.current = true;
        dragRef.current = { start: d, end: d, mode: !selectedDates.includes(d) };
        setDragRange([d, d]);
        const dates = allMonthDays;
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
        toggleDate(d);
        lastClickRef.current = d;
    };

    const onSort = (key) => {
        if (sort === key) setDir(d => (d === 'desc' ? 'asc' : 'desc'));
        else { setSort(key); setDir('desc'); }
        setPage(0);
    };

    const exportCsv = async () => {
        setExporting(true);
        try {
            const res = await api.post('/business/net-spread', {}, {
                params: {
                    ...dateParams, sort, dir, export: true, lossOnly,
                    ...(search.trim() ? { search: search.trim() } : {}),
                },
            });
            const rows = res.data?.content || [];
            const totals = res.data?.totals;
            const label = res.data?.selection || '';
            const esc = (v) => {
                const s = String(v ?? '');
                return `"${(/^[=+\-@]/.test(s) ? `'${s}` : s).replace(/"/g, '""')}"`;
            };
            const msfDp = Math.max(4, dp);
            const cv = (v) => convertForDisplay(num(v), currencyCode);
            const fx = usdRateInfo(currencyCode);
            const lines = [
                `Currency,${displayCurrencyCode(currencyCode) || currencySymbol || 'UNKNOWN'}`,
                ...(fx ? [`FX Rate,1 ${fx.base} = ${fx.rate} USD (indicative; as of ${fx.asOf})`] : []),
                `Business Date,${res.data?.month ? res.data.month + ' (full month)'
                    : (res.data?.dates || []).join(' ') || label}`,
                ['MID', 'Name', 'Vol', 'Count', FEE_LABELS.msf, FEE_LABELS.icf,
                    FEE_LABELS.sf, FEE_LABELS.pg, FEE_LABELS.nm, FEE_LABELS.dcc,
                    'DCC (Merchant Share)', FEE_LABELS.rental, FEE_LABELS.spread, 'Rescued'].join(','),
            ];
            rows.forEach(r => lines.push([
                esc(r.mid), esc(r.name),
                cv(r.volume).toFixed(dp), num(r.count),
                cv(r.msf).toFixed(msfDp), cv(r.icf).toFixed(dp),
                cv(r.sf).toFixed(dp), cv(r.pg).toFixed(dp), cv(r.nm).toFixed(dp),
                cv(r.dcc).toFixed(dp), cv(r.dccMerchant).toFixed(dp),
                cv(r.rental).toFixed(dp), cv(r.spread).toFixed(dp),
                r.rescued ? 'YES' : '',
            ].join(',')));
            if (totals) {
                lines.push([
                    esc('TOTAL'), esc(`${rows.length} rows`),
                    cv(totals.volume).toFixed(dp), num(totals.count),
                    cv(totals.msf).toFixed(msfDp), cv(totals.icf).toFixed(dp),
                    cv(totals.sf).toFixed(dp), cv(totals.pg).toFixed(dp),
                    cv(totals.nm).toFixed(dp), cv(totals.dcc).toFixed(dp),
                    cv(totals.dccMerchant).toFixed(dp), cv(totals.rental).toFixed(dp),
                    cv(totals.spread).toFixed(dp), '',
                ].join(','));
            }
            const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `net-spread-${String(label || 'latest').replace(/[^0-9A-Za-z-]+/g, '_')}.csv`;
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

    const firstLoad = loading && !data;
    const refreshing = loading && !!data;

    const rows = data?.content || [];
    const totals = data?.totals;
    const trend = data?.trend || [];
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

    const trendByDate = useMemo(() => {
        const m = new Map();
        trend.forEach(t => m.set(t.date, t));
        return m;
    }, [trend]);
    const trendMax = useMemo(
        () => trend.reduce((a, t) => Math.max(a, num(t.volume)), 0) || 1, [trend]);

    const monthDateSet = useMemo(() => new Set(monthDates), [monthDates]);
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

    /* Derived ratios from the server's own totals. */
    const spreadPct = totals && num(totals.volume) ? (num(totals.spread) / num(totals.volume)) * 100 : null;
    const marginPct = totals && num(totals.volume) ? (num(totals.nm) / num(totals.volume)) * 100 : null;
    const ancillaryTotal = totals ? num(totals.dcc) + num(totals.rental) : 0;
    const daysCovered = selectedDates.length || trend.length;

    const animKey = `${dateParams.dates || dateParams.month || ''}|${search}|${lossOnly}`;

    const loadedDays = useMemo(
        () => allMonthDays.map(d => trendByDate.get(d)).filter(Boolean), [allMonthDays, trendByDate]);
    const kpiSeries = useMemo(() => ({
        volume: loadedDays.map(t => num(t.volume)),
        nm:     loadedDays.map(t => num(t.nm)),
        dcc:    loadedDays.map(t => num(t.dcc)),
        rental: loadedDays.map(t => num(t.rental)),
        spread: loadedDays.map(t => num(t.spread)),
        cost:   loadedDays.map(costOf),
    }), [loadedDays]);

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
            nm:     pct(t => num(t.nm)),
            dcc:    pct(t => num(t.dcc)),
            rental: pct(t => num(t.rental)),
            spread: pct(t => num(t.spread)),
        };
    }, [selectedDates, loadedDays]);

    const dayTone = (d) => {
        const t = trendByDate.get(d);
        if (!t) return 'none';
        return num(t.spread) >= 0 ? 'good' : 'bad';
    };
    const refreshedAt = lastRefresh
        ? lastRefresh.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) : null;

    const noTxnSelection = selectedDates.length > 0
        && selectedDates.every(d => !monthDateSet.has(d));

    const pageMaxVolume = useMemo(
        () => rows.reduce((a, r) => Math.max(a, num(r.volume)), 0) || 1, [rows]);
    const pageMaxSpread = useMemo(
        () => rows.reduce((a, r) => Math.max(a, num(r.spread)), 0) || 1, [rows]);
    const spreadHeat = (v) => (num(v) <= 0 ? 0 : 4 + Math.round((num(v) / pageMaxSpread) * 14));
    const rowsKey = `${animKey}|${page}|${sort}|${dir}|${pageSize}`;

    const SignedCell = ({ v, bold = false, kind = 'spread' }) => {
        const n = num(v);
        const pos = n >= 0;
        return (
            <span title={fullNum(v, currencySymbol)} style={{
                position: 'relative',
                display: 'inline-flex', alignItems: 'center', gap: 5, justifyContent: 'flex-end',
                fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
                fontWeight: bold ? 700 : 600,
                color: pos ? 'var(--success-text)' : 'var(--danger-text)',
            }}>
                <span aria-hidden="true" style={{ fontSize: 10, opacity: 0.9 }}>{pos ? '▲' : '▼'}</span>
                {money(n)}
                <span className="edm-sr">{pos ? `positive ${kind}` : `negative ${kind}`}</span>
            </span>
        );
    };

    const applySearch = () => { setSearch(searchDraft); setPage(0); setDetailRow(null); };

    return (
        <div className="edm-page"
            style={{ padding: '22px 26px 32px', width: '100%', maxWidth: '100%', boxSizing: 'border-box' }}>
            <style>{`
                .edm-page {
                    --edm-mast-bg: linear-gradient(150deg,
                        color-mix(in srgb, var(--primary) 26%, #171D28) 0%,
                        color-mix(in srgb, var(--primary) 18%, #141922) 55%,
                        color-mix(in srgb, var(--primary) 24%, #11161E) 100%);
                }
                .edm-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }
                .edm-panel.edm-hdrblock { background: var(--edm-mast-bg,
                        linear-gradient(150deg, #212F4B 0%, #1C263C 55%, #1C2841 100%));
                    border-color: transparent; overflow: visible;
                    position: relative; z-index: 40; }
                .edm-mast { background: transparent;
                    padding: 16px 24px 14px; display: flex; justify-content: space-between;
                    align-items: flex-end; gap: 18px; flex-wrap: wrap; }
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
                .edm-mast-sub { margin: 5px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 70%, transparent); }
                .edm-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.18);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .edm-mast-btn:hover { background: rgba(255,255,255,0.15); }
                .edm-mast-btn:disabled { opacity: 0.5; cursor: default; }
                .edm-mast-btn[data-on="true"] { background: rgba(255,255,255,0.22);
                    box-shadow: inset 0 -2px 0 var(--success, #2E9E6B); }
                .edm-cmdbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
                    padding: 10px 16px; background: rgba(0,0,0,0.10);
                    border-top: 1px solid rgba(255,255,255,0.08); }
                .edm-search { display: flex; align-items: center; gap: 7px; flex: 1 1 260px;
                    max-width: 380px; padding: 7px 11px; border-radius: var(--radius-sm);
                    background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.16); }
                .edm-search input { flex: 1; min-width: 0; background: transparent; border: 0;
                    outline: none; font-size: 12.5px; color: #EEF3FC; }
                .edm-search input::placeholder { color: rgba(238,243,252,0.5); }
                .edm-mast-ctx { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 0;
                    margin-top: 10px; font-family: var(--font-mono); font-size: 10.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .edm-mast-ctx > span + span::before { content: '·'; margin: 0 8px; opacity: .6; }
                .edm-mast-ctx b { font-weight: 600; color: var(--table-head-text, #EEF3FC); }

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
                .edm-bar-we { position: absolute; left: 1px; right: 1px; bottom: -3px;
                    height: 2px; border-radius: 2px; background: var(--chart-alt, #64748B);
                    opacity: 0.5; }
                .edm-chart-line { position: absolute; inset: 0; width: 100%; height: 100%;
                    pointer-events: none; overflow: visible; }
                .edm-chart-axis { display: flex; gap: 2px; margin-top: 7px;
                    font-family: var(--font-mono); font-size: 8px; letter-spacing: -0.02em;
                    color: var(--text-muted); }
                .edm-chart-axis > span { flex: 1 1 0; min-width: 0; text-align: center;
                    overflow: hidden; white-space: nowrap; }
                .edm-refreshing { opacity: 0.62; pointer-events: none;
                    transition: opacity .18s ease; }

                .edm-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .edm-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); box-shadow: var(--shadow-card, 0 1px 2px rgba(0,0,0,.04));
                    transition: box-shadow .2s ease, transform .2s ease; }
                .edm-panel-lift:hover { box-shadow: var(--shadow-hover, 0 10px 28px rgba(0,0,0,.10));
                    transform: translateY(-1px); }

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
                @property --edm-a { syntax: '<angle>'; inherits: false; initial-value: 0deg; }
                @keyframes edm-border-run { to { --edm-a: 360deg; } }
                .edm-enter { animation: edm-fadeup .55s cubic-bezier(.2,.8,.2,1) both;
                    animation-delay: calc(var(--i, 0) * 70ms); }
                .edm-live { animation: edm-pulse 2.4s ease-in-out infinite; }
                .edm-eyebrow-rule { display: inline-flex; align-items: center; gap: 7px; }
                .edm-eyebrow-rule::before { content: ''; width: 14px; height: 2px; border-radius: 2px;
                    background: var(--cat-2, #CA5F28); flex-shrink: 0; }

                .edm-tile { position: relative; min-width: 0; overflow: hidden;
                    transition: background .15s ease; }
                .edm-tile:hover { background: var(--bg-hover); }
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
                .edm-tile { --edm-ring: color-mix(in srgb, var(--primary) 55%, var(--border)); }
                .edm-tile:hover::before { opacity: .9; animation-duration: 2.8s; }
                .edm-ratiorow .edm-tile::before { content: none; }
                .edm-ratiorow { background: color-mix(in srgb, var(--bg-subtle) 55%, var(--bg-card)); }
                .edm-ratiorow .edm-tile-value { font-size: 16px !important; }
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
                .edm-pill-tint { color: var(--text);
                    background: color-mix(in srgb, var(--pill-ink, var(--text)) 13%, var(--bg-card));
                    border-color: color-mix(in srgb, var(--pill-ink, var(--text)) 40%, transparent); }

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

                .edm-rank { font-family: var(--font-mono); font-size: 9.5px; font-weight: 700;
                    color: var(--text-muted); letter-spacing: .04em; width: 18px; flex-shrink: 0; }
                .edm-track { height: 6px; border-radius: 999px; overflow: hidden;
                    background: var(--border-light, var(--border)); }
                .edm-mix-fill { height: 100%; border-radius: 999px; transform-origin: left center;
                    animation: edm-growx .6s cubic-bezier(.2,.8,.2,1) both;
                    animation-delay: calc(var(--i, 0) * 60ms); }
                .edm-mix-row { animation: edm-fadeup .4s ease-out both;
                    animation-delay: calc(var(--i, 0) * 50ms); }

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

                .edm-drawer-head { background: var(--edm-mast-bg,
                        linear-gradient(150deg, #212F4B 0%, #1C263C 55%, #1C2841 100%));
                    color: var(--table-head-text, #EEF3FC); padding: 18px 20px;
                    display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
                .edm-badge { display: inline-flex; align-items: center; gap: 5px; padding: 2px 8px;
                    border-radius: 5px; font-family: var(--font-mono); font-size: 10.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC); background: rgba(255,255,255,.10);
                    border: 1px solid rgba(255,255,255,.18); }
                .edm-badge small { font-size: 8.5px; letter-spacing: .12em; opacity: .6; font-weight: 700; }

                .edm-bone { background: linear-gradient(90deg, var(--bg-subtle) 25%,
                        color-mix(in srgb, var(--primary) 12%, var(--bg-subtle)) 50%, var(--bg-subtle) 75%);
                    background-size: 400% 100%; animation: edm-shimmer 1.8s ease-in-out infinite; }

                .edm-bar-fill { background: linear-gradient(180deg, var(--cat-1),
                        color-mix(in srgb, var(--cat-1) 55%, transparent));
                    transform-origin: bottom center;
                    animation: edm-growy .55s cubic-bezier(.2,.8,.2,1) both;
                    animation-delay: calc(var(--i, 0) * 14ms); }
                .edm-bar-loss { background: linear-gradient(180deg, var(--danger),
                        color-mix(in srgb, var(--danger) 55%, transparent)); }
                .edm-bar-hov .edm-bar-fill { filter: brightness(1.12); }
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
                .edm-th-wrap { white-space: normal !important; max-width: 96px;
                    line-height: 1.25; }
                .edm-cell-blank { background: transparent; border: 0; cursor: default; }
                .edm-cell-nodata { background: transparent;
                    border: 1px dashed var(--border-light, var(--border)); }
                .edm-cell-nodata .edm-cell-num { color: var(--text-muted); font-weight: 400; }
                .edm-cell-nodata:hover { border-color: var(--primary); border-style: solid; }
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
                .edm-cell-we:not(.edm-cell-on) { box-shadow: inset 0 0 0 1px
                    color-mix(in srgb, var(--chart-alt, #64748B) 22%, transparent); }
                .edm-cell-loss { position: absolute; left: 5px; right: 5px; bottom: 3px;
                    height: 2.5px; border-radius: 2px; background: var(--danger); }
                .edm-cell-on .edm-cell-loss { background: var(--danger); opacity: 0.9; }

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
                .edm-table .edm-c1 { position: sticky; left: 0;     min-width: 118px; max-width: 118px; }
                .edm-table .edm-c2 { position: sticky; left: 118px; min-width: 210px; max-width: 250px;
                    box-shadow: 6px 0 8px -6px rgba(15,23,42,0.16); }
                .edm-table tbody .edm-c1, .edm-table tbody .edm-c2 {
                    background: var(--bg-card); z-index: 1; }
                .edm-table tbody tr:hover .edm-c1, .edm-table tbody tr:hover .edm-c2 { background: var(--bg-hover); }
                .edm-table thead .edm-c1, .edm-table thead .edm-c2 { z-index: 3; }
                .edm-cell-num { text-align: right; font-family: var(--font-mono);
                    font-variant-numeric: tabular-nums; white-space: nowrap; }
                .edm-vol { position: relative; }
                .edm-vol-bar { position: absolute; right: 0; bottom: 3px; height: 3px;
                    border-radius: 999px; opacity: 0.6; transform-origin: right center;
                    background: linear-gradient(90deg, color-mix(in srgb, var(--cat-1) 35%, transparent), var(--cat-1));
                    animation: edm-growx .5s cubic-bezier(.2,.8,.2,1) both; }
                .edm-table tbody tr:nth-child(even):not(.edm-total-row) td { background:
                    color-mix(in srgb, var(--text) 3%, var(--bg-card)); }
                .edm-table tbody tr:nth-child(even):not(.edm-total-row) .edm-c1,
                .edm-table tbody tr:nth-child(even):not(.edm-total-row) .edm-c2 { background:
                    color-mix(in srgb, var(--text) 3%, var(--bg-card)); }
                .edm-table tbody tr:hover td, .edm-table tbody tr:hover .edm-c1,
                .edm-table tbody tr:hover .edm-c2 { background: var(--bg-hover); }
                .edm-nm-cell { box-shadow: inset 0 0 0 100vw
                    color-mix(in srgb, var(--success) calc(var(--heat, 0) * 1%), transparent); }
                .edm-nm-loss { box-shadow: inset 0 0 0 100vw color-mix(in srgb, var(--danger) 14%, transparent); }
                .edm-row-in { animation: edm-fadeup .35s ease-out both;
                    animation-delay: calc(min(var(--i, 0), 18) * 22ms); }
                .edm-total-row td { position: sticky; bottom: 0; z-index: 2;
                    background: var(--wash); font-weight: 700;
                    border-top: 1px solid var(--border); border-bottom: none; }

                /* Rescued badge — a merchant negative on margin, positive on spread. */
                .edm-rescued { display: inline-flex; align-items: center; gap: 4px;
                    margin-left: 7px; padding: 1px 7px; border-radius: 999px;
                    font-family: var(--font-mono); font-size: 9.5px; font-weight: 700;
                    letter-spacing: .06em; text-transform: uppercase;
                    color: var(--success-text); background: var(--success-bg);
                    border: 1px solid color-mix(in srgb, var(--success) 45%, transparent); }
                .edm-rescue-band { display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
                    padding: 12px 20px; border-top: 1px solid var(--border-light, var(--border));
                    background: color-mix(in srgb, var(--success) 5%, var(--bg-card));
                    font-size: 12.5px; color: var(--text-secondary); }
                .edm-rescue-band b { color: var(--text); font-weight: 700; }

                @media (max-width: 900px) {
                    .edm-table .edm-c2 { position: static; box-shadow: none; min-width: 150px; }
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

            {/* ── Masthead + search / lens bar ── */}
            <section className="edm-panel edm-hdrblock edm-enter" style={{ marginBottom: 12, '--i': 0 }}>
                <div className="edm-mast">
                    <div>
                        <div className="edm-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <span className="edm-live" style={{
                                width: 6, height: 6, borderRadius: '50%', background: 'var(--rail-fresh, #34B98A)',
                                boxShadow: '0 0 0 3px rgba(52,185,138,0.22)',
                            }} />
                            Executive · Total take
                        </div>
                        <h1>Net Spread Dashboard</h1>
                        <p className="edm-mast-sub" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                            <span>Net margin + DCC acquirer share + rental income, per merchant</span>
                            <MarginGlossaryHint compact light />
                        </p>
                        <div className="edm-mast-ctx">
                            <span>Latest loaded <b>{latest ? longDate(latest) : '—'}</b></span>
                            {month && allMonthDays.length > 0 && (
                                <span><b>{monthDates.length}</b> of {allMonthDays.length} days loaded in {monthLabel(month)}</span>
                            )}
                            {refreshedAt && <span>Refreshed <b>{refreshedAt}</b></span>}
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
                    <div className="edm-search">
                        <Search size={13} style={{ color: 'rgba(238,243,252,0.6)', flexShrink: 0 }} />
                        <input value={searchDraft}
                            onChange={e => setSearchDraft(e.target.value)}
                            onKeyDown={e => { if (e.key === 'Enter') applySearch(); }}
                            onBlur={applySearch}
                            placeholder="Search merchant name or MID"
                            aria-label="Search merchant name or MID" />
                        {search && (
                            <button onClick={() => { setSearchDraft(''); setSearch(''); setPage(0); }}
                                aria-label="Clear search"
                                style={{ background: 'none', border: 0, cursor: 'pointer', display: 'flex', padding: 0 }}>
                                <X size={12} color="rgba(238,243,252,0.7)" />
                            </button>
                        )}
                    </div>
                    <button className="edm-focus edm-mast-btn" data-on={lossOnly ? 'true' : 'false'}
                        onClick={() => { setLossOnly(v => !v); setPage(0); setDetailRow(null); }}
                        title="Only merchants negative on net margin — see which ones ancillary revenue rescues">
                        <LifeBuoy size={13} /> Margin-loss merchants
                    </button>
                </div>
            </section>

            {/* ── Month ribbon: the picker IS the month's volume shape ── */}
            <section className="edm-panel edm-enter" style={{ padding: '14px 18px 12px', marginBottom: 12, '--i': 1 }}>
                <div style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    gap: 12, flexWrap: 'wrap', marginBottom: 12,
                }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                        <span className="edm-eyebrow edm-eyebrow-rule">Business date</span>
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
                        <div className="edm-cal" role="grid" aria-label={`Business dates in ${monthLabel(month)}`}>
                            {week.headers.map(h => (
                                <span key={h.key} className={`edm-cal-h${h.weekend ? ' edm-cal-h-we' : ''}`}>
                                    {h.label}
                                </span>
                            ))}
                            {calWeeks.flat().map((d, idx) => {
                                if (!d) return <span key={`b${idx}`} className="edm-cell edm-cell-blank" />;
                                const hasData = monthDateSet.has(d);
                                const picked = selectedDates.includes(d);
                                const dimmed = selectedDates.length > 0 && !picked;
                                const inDrag = dragRange && d >= dragRange[0] && d <= dragRange[1];
                                const t = trendByDate.get(d);
                                const vol = num(t?.volume);
                                const loss = hasData && t ? num(t.spread) < 0 : false;
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
                                        aria-label={`${longDate(d)}${wknd ? ', weekend' : ''}${hasData ? (loss ? ', negative net spread' : '') : ', no transactions'}`}
                                        title={hasData
                                            ? `${longDate(d)}${wknd ? ' · weekend' : ''}\nVolume ${money(vol)}\nTransactions ${num(t?.count).toLocaleString()}\nNet Spread ${money(num(t?.spread))}`
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
                                    day closed spread-negative
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

                        <DayTrendChart days={allMonthDays} trendByDate={trendByDate}
                            selectedDates={selectedDates} onToggle={toggleDate}
                            money={money} week={week} animKey={`${month}|${search}`} />
                    </div>
                ) : (
                    <div style={{ fontSize: 12, color: 'var(--text-muted)', padding: '18px 0' }}>
                        {month ? 'No business dates were loaded in this month.' : 'Loading business dates'}
                    </div>
                )}
            </section>

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
                        title={noTxnSelection ? 'No transactions on this date'
                            : lossOnly ? 'No margin-loss merchants' : 'No merchant activity'}
                        message={noTxnSelection
                            ? `Nothing was processed on ${selectionText}. Pick another day, or use All days for the month.`
                            : lossOnly
                                ? `Every merchant in ${selectionText} is margin-positive — switch the lens off to see them all.`
                                : selectionText !== '—'
                                    ? `Nothing matches this search for ${selectionText}. Widen the date selection or clear the search.`
                                    : 'No daily data has been loaded for this tenant yet.'} />
                )
            ) : (
                <div className={refreshing ? 'edm-refreshing' : undefined} aria-busy={refreshing}>
                    {/* ── The equation, read left to right: NM + DCC + Rental = Spread ── */}
                    {totals && (
                        <section className="edm-panel edm-enter" style={{ marginBottom: 12, overflow: 'hidden', '--i': 2 }}>
                            <div style={{
                                display: 'grid',
                                gridTemplateColumns: 'minmax(220px, 1.35fr) repeat(auto-fit, minmax(160px, 1fr))',
                                borderBottom: '1px solid var(--border-light, var(--border))',
                            }}>
                                <div style={cellDiv}>
                                    <Metric hero label="Net spread"
                                        raw={num(totals.spread)} format={fmt.currency}
                                        sub={spreadPct == null ? 'no volume' : `${spreadPct.toFixed(2)}% of volume · ${daysCovered} day${daysCovered === 1 ? '' : 's'}`}
                                        tone={num(totals.spread) >= 0 ? 'success' : 'danger'}
                                        title={fullNum(totals.spread, currencySymbol)}
                                        delta={deltas?.spread} deltaLabel={deltas?.label}
                                        series={kpiSeries.spread} animKey={animKey}
                                        sparkColor={num(totals.spread) >= 0 ? 'var(--success)' : 'var(--danger)'} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="Net margin"
                                        raw={num(totals.nm)} format={fmt.currency}
                                        sub={marginPct == null ? 'transactions only' : `${marginPct.toFixed(2)}% of volume`}
                                        tone={num(totals.nm) >= 0 ? 'success' : 'danger'}
                                        title={fullNum(totals.nm, currencySymbol)}
                                        delta={deltas?.nm} deltaLabel={deltas?.label}
                                        series={kpiSeries.nm} animKey={animKey}
                                        sparkColor={num(totals.nm) >= 0 ? 'var(--success)' : 'var(--danger)'} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="+ DCC (acquirer)"
                                        raw={num(totals.dcc)} format={fmt.currency}
                                        sub={`merchant share ${money(totals.dccMerchant)} (not added)`}
                                        title={fullNum(totals.dcc, currencySymbol)}
                                        delta={deltas?.dcc} deltaLabel={deltas?.label}
                                        series={kpiSeries.dcc} sparkColor="var(--cat-4, #B08C1E)" animKey={animKey} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric wide label="+ Rentals"
                                        raw={num(totals.rental)} format={fmt.currency}
                                        sub="terminal, store & merchant charges"
                                        title={fullNum(totals.rental, currencySymbol)}
                                        delta={deltas?.rental} deltaLabel={deltas?.label}
                                        series={kpiSeries.rental} sparkColor="var(--cat-5, #4E8D7C)" animKey={animKey} />
                                </div>
                                <div>
                                    <Metric wide label="Volume"
                                        raw={num(totals.volume)} format={fmt.currency}
                                        sub={`${displayCurrencyCode(currencyCode) || currencySymbol || ''} · ${num(totals.count).toLocaleString()} txns`}
                                        title={fullNum(totals.volume, currencySymbol)}
                                        delta={deltas?.volume} deltaLabel={deltas?.label}
                                        series={kpiSeries.volume} sparkColor="var(--cat-1)" animKey={animKey} />
                                </div>
                            </div>

                            <div style={{ padding: '15px 20px 16px' }}>
                                <div style={{
                                    display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 12,
                                    flexWrap: 'wrap',
                                }}>
                                    <span className="edm-eyebrow edm-eyebrow-rule">From MSF to Net Spread</span>
                                    <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                        fee income split into what was paid away and what was kept, then the ancillary legs on top
                                    </span>
                                </div>
                                <SpreadRibbon totals={totals} money={money} share={share} animKey={animKey} />
                            </div>

                            {/* ── The rescue story, stated in numbers ── */}
                            <div className="edm-rescue-band">
                                <LifeBuoy size={15} style={{ color: 'var(--success-text)', flexShrink: 0 }} />
                                <span>
                                    <b>{num(totals.lossOnMargin).toLocaleString()}</b> merchant{num(totals.lossOnMargin) === 1 ? '' : 's'} negative
                                    on net margin alone · <b>{num(totals.rescued).toLocaleString()}</b> turn{num(totals.rescued) === 1 ? 's' : ''} positive
                                    with DCC + rentals · <b>{num(totals.lossOnSpread).toLocaleString()}</b> still
                                    negative on net spread
                                </span>
                                <span style={{ marginLeft: 'auto', fontSize: 11.5, color: 'var(--text-muted)' }}>
                                    ancillary revenue this selection: {money(ancillaryTotal)}
                                </span>
                            </div>

                            <div className="edm-ratiorow" style={{
                                display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                                borderTop: '1px solid var(--border-light, var(--border))',
                            }}>
                                <div style={cellDiv}>
                                    <Metric label="Net spread rate"
                                        value={spreadPct == null ? '—' : `${spreadPct.toFixed(2)}%`}
                                        sub="net spread ÷ volume"
                                        tone={spreadPct == null ? undefined : spreadPct >= 0 ? 'success' : 'danger'} />
                                </div>
                                <div style={cellDiv}>
                                    <Metric label="Ancillary share"
                                        value={num(totals.spread) > 0
                                            ? `${((ancillaryTotal / num(totals.spread)) * 100).toFixed(1)}%`
                                            : '—'}
                                        sub="DCC + rentals ÷ net spread" />
                                </div>
                                <div style={cellDiv}>
                                    <Metric label="Margin-loss merchants"
                                        value={num(totals.lossOnMargin).toLocaleString()}
                                        sub="negative before ancillary"
                                        tone={num(totals.lossOnMargin) > 0 ? 'danger' : undefined} />
                                </div>
                                <div>
                                    <Metric label="Active merchants"
                                        value={num(totals.merchants).toLocaleString()}
                                        sub="rows in this selection" />
                                </div>
                            </div>
                        </section>
                    )}

                    {/* ── Weekday/weekend split on spread ── */}
                    {loadedDays.length > 1 && (
                        <section className="edm-panel edm-panel-lift edm-enter" style={{
                            display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))',
                            gap: 26, padding: '16px 20px', marginBottom: 12, '--i': 3,
                        }}>
                            <WeekSplit days={allMonthDays} trendByDate={trendByDate}
                                week={week} money={money} share={share} animKey={animKey} />
                        </section>
                    )}

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
                                        <tr key={`${r.merchantId}-${i}`} onClick={() => setDetailRow(r)}
                                            className="edm-row-in" style={{ '--i': i }}>
                                            <td className="edm-c1 edm-num" style={{ fontSize: 12.5 }}>{r.mid || '—'}</td>
                                            <td className="edm-c2" style={{
                                                overflow: 'hidden', textOverflow: 'ellipsis',
                                                whiteSpace: 'nowrap', fontWeight: 500,
                                            }} title={r.name}>
                                                {r.name || '—'}
                                                {r.rescued && (
                                                    <span className="edm-rescued"
                                                        title="Negative on net margin, positive on net spread — rescued by DCC + rental revenue">
                                                        <LifeBuoy size={9} /> rescued
                                                    </span>
                                                )}
                                            </td>
                                            <td className="edm-cell-num edm-vol" title={fullNum(r.volume, currencySymbol)}>
                                                {money(r.volume)}
                                                <span className="edm-vol-bar" style={{
                                                    width: `max(2px, calc(${Math.max((num(r.volume) / pageMaxVolume) * 100, 1).toFixed(1)}% - 28px))`,
                                                }} />
                                            </td>
                                            <td className="edm-cell-num" title={fullNum(r.count)}>{num(r.count).toLocaleString()}</td>
                                            <td className="edm-cell-num" title={formatMsf(r.msf, currencySymbol)}>{money(r.msf)}</td>
                                            <td className="edm-cell-num" title={fullNum(r.icf, currencySymbol)}>{money(r.icf)}</td>
                                            <td className="edm-cell-num" title={fullNum(r.sf, currencySymbol)}>{money(r.sf)}</td>
                                            <td className="edm-cell-num" title={fullNum(r.pg, currencySymbol)}>{money(r.pg)}</td>
                                            <td className="edm-cell-num">
                                                <SignedCell v={r.nm} kind="margin" />
                                            </td>
                                            <td className="edm-cell-num"
                                                title={`Acquirer share ${fullNum(r.dcc, currencySymbol)} · merchant share ${fullNum(r.dccMerchant, currencySymbol)} (not added)`}>
                                                {money(r.dcc)}
                                            </td>
                                            <td className="edm-cell-num" title={fullNum(r.rental, currencySymbol)}>{money(r.rental)}</td>
                                            <td className={`edm-cell-num ${num(r.spread) < 0 ? 'edm-nm-loss' : 'edm-nm-cell'}`}
                                                style={{ '--heat': spreadHeat(r.spread) }}>
                                                <SignedCell v={r.spread} bold />
                                            </td>
                                        </tr>
                                    ))}
                                    {totals && (
                                        <tr className="edm-total-row" onClick={e => e.stopPropagation()}
                                            style={{ cursor: 'default' }}>
                                            <td colSpan={2}>
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
                                            <td className="edm-cell-num"><SignedCell v={totals.nm} bold kind="margin" /></td>
                                            <td className="edm-cell-num" title={fullNum(totals.dcc, currencySymbol)}>{money(totals.dcc)}</td>
                                            <td className="edm-cell-num" title={fullNum(totals.rental, currencySymbol)}>{money(totals.rental)}</td>
                                            <td className="edm-cell-num"><SignedCell v={totals.spread} bold /></td>
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

            {/* ── Merchant drilldown: the spread equation for one merchant ── */}
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
                                    {detailRow.name || '—'}
                                </div>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
                                    <span className="edm-badge"><small>MID</small>{detailRow.mid || '—'}</span>
                                    {detailRow.rescued && (
                                        <span className="edm-badge" style={{
                                            borderColor: 'color-mix(in srgb, var(--success) 60%, transparent)',
                                        }}><small>◆</small>RESCUED</span>
                                    )}
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
                                <div className="edm-eyebrow edm-eyebrow-rule" style={{ marginBottom: 10 }}>Spread stack</div>
                                <SpreadRibbon totals={detailRow} money={money} share={share} compact
                                    animKey={`${detailRow.merchantId}`} />
                                {/* The equation, line by line */}
                                <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px solid var(--border-light, var(--border))' }}>
                                    {[
                                        ['Net margin', detailRow.nm, 'margin'],
                                        ['+ DCC (acquirer share)', detailRow.dcc],
                                        ['+ Rental income', detailRow.rental],
                                    ].map(([k, v, kind]) => (
                                        <div key={k} style={{
                                            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                                            padding: '4px 0', fontSize: 12.5,
                                        }}>
                                            <span style={{ fontWeight: 600, color: 'var(--text-secondary)' }}>{k}</span>
                                            {kind ? <SignedCell v={v} kind={kind} />
                                                : <span className="edm-num" style={{ fontWeight: 600 }}>{money(v)}</span>}
                                        </div>
                                    ))}
                                    <div style={{
                                        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                                        marginTop: 8, paddingTop: 10,
                                        borderTop: '1px solid var(--border)',
                                    }}>
                                        <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text)' }}>
                                            Net spread
                                        </span>
                                        <SignedCell v={detailRow.spread} bold />
                                    </div>
                                </div>
                                <div style={{ marginTop: 6, fontSize: 11, color: 'var(--text-muted)' }}>
                                    Net spread = net margin (MSF − interchange − scheme − gateway)
                                    + DCC acquirer share + rental income, in settlement currency.
                                    DCC merchant share ({money(detailRow.dccMerchant)}) is the merchant's money
                                    and is never added.
                                </div>
                            </div>
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

export default NetSpreadDashboard;
