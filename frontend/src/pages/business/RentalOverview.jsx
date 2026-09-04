import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import {
    Receipt, RefreshCw, Download, AlertTriangle, Search,
    ArrowUp, ArrowDown, CheckCircle2, CircleSlash, Copy,
} from 'lucide-react';
import { createFmt, formatNumber } from '../../utils/formatters';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

/* ════════════════════════════════════════════════════════════════════
   Rentals — terminal / store / merchant rental charges from the
   DEDICATED rental feed (fact_rental). The visible levels are
   tenant-driven: CMM-format tenants get Store only, AMS-format tenants
   get Merchant / Store / Terminal — the backend's /overview response
   says which (`levels`), the page never hardcodes it.

   Each charge is a dated record (payment date), so the page is a
   date-ranged ledger, not a snapshot. Exceptions (REJECTED id combos,
   UNMATCHED ids the dims don't know yet, DUPLICATE re-uploads) come
   from the latest load's staging rows.

   Rental is a ONE-TIME fee (business decision 2026-09-04), so the page
   also carries a COVERAGE panel over all history: every entity at the
   tenant's finest level should have exactly one charge — "never
   billed" is missed revenue, "billed 2+ times" is double-charging.
   The monthly trend shows collection cadence, not recurrence. ═══════ */

const num = (v) => (v == null ? 0 : Number(v));

const fmtDate = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

const PRESETS = [
    { key: 'MTD', label: 'This month' },
    { key: 'LM', label: 'Previous month' },
    { key: 'D90', label: 'Last 90 days' },
    { key: 'YTD', label: 'This year' },
    { key: 'CUSTOM', label: 'Custom' },
];
const computeRange = (preset) => {
    const a = new Date();
    switch (preset) {
        case 'LM': return {
            from: fmtDate(new Date(a.getFullYear(), a.getMonth() - 1, 1)),
            to: fmtDate(new Date(a.getFullYear(), a.getMonth(), 0)),
        };
        case 'D90': { const d = new Date(a); d.setDate(d.getDate() - 89); return { from: fmtDate(d), to: fmtDate(a) }; }
        case 'YTD': return { from: fmtDate(new Date(a.getFullYear(), 0, 1)), to: fmtDate(a) };
        case 'MTD':
        default: return { from: fmtDate(new Date(a.getFullYear(), a.getMonth(), 1)), to: fmtDate(a) };
    }
};

const LEVEL_LABELS = { MERCHANT: 'Merchant (MID)', STORE: 'Store (SID)', TERMINAL: 'Terminal (TID)' };
const LEVEL_COLORS = {
    MERCHANT: 'var(--cat-1, #5E82D2)',
    STORE: 'var(--cat-2, #4E8D7C)',
    TERMINAL: 'var(--cat-3, #B08A3E)',
};
const PAGE_SIZE = 50;

const Tile = ({ label, value, sub }) => (
    <div style={{
        padding: '15px 20px', minWidth: 170, borderRadius: 'var(--radius-lg)',
        border: '1px solid var(--border)', background: 'var(--surface)',
    }}>
        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
            {label}
        </div>
        <div style={{
            marginTop: 7, fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
            fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em', color: 'var(--text)', whiteSpace: 'nowrap',
        }}>
            {value}
        </div>
        {sub && <div style={{ marginTop: 3, fontSize: 11, color: 'var(--text-secondary)' }}>{sub}</div>}
    </div>
);

/** Coverage tile — the one-time-fee buckets. Clickable when it has rows. */
const CoverageTile = ({ icon: Icon, label, value, sub, tone, active, onClick }) => {
    const tones = {
        ok:    { border: 'var(--border)', bg: 'var(--surface)', fg: 'var(--text)' },
        warn:  { border: 'var(--danger-border, #fecaca)', bg: 'var(--danger-bg, #fef2f2)', fg: 'var(--danger-text, #991b1b)' },
    };
    const t = tones[tone] || tones.ok;
    return (
        <button onClick={onClick} disabled={!onClick} style={{
            padding: '15px 20px', minWidth: 170, textAlign: 'left',
            cursor: onClick ? 'pointer' : 'default',
            borderRadius: 'var(--radius-lg)',
            border: `1px solid ${active ? 'var(--accent)' : t.border}`,
            background: t.bg,
        }}>
            <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: t.fg, display: 'flex', alignItems: 'center', gap: 6 }}>
                <Icon size={12} /> {label}
            </div>
            <div style={{ marginTop: 7, fontFamily: 'var(--font-mono)', fontSize: 22, fontWeight: 600, color: t.fg }}>
                {value}
            </div>
            {sub && <div style={{ marginTop: 3, fontSize: 11, color: t.fg, opacity: 0.85 }}>{sub}</div>}
        </button>
    );
};

const RentalOverview = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);

    const [preset, setPreset] = useState('MTD');
    const [range, setRange] = useState(computeRange('MTD'));
    const [draftRange, setDraftRange] = useState(computeRange('MTD'));
    const [overview, setOverview] = useState(null);
    const [overviewErr, setOverviewErr] = useState(null);

    const [level, setLevel] = useState(null);
    const [rows, setRows] = useState([]);
    const [total, setTotal] = useState(0);
    const [totalAmount, setTotalAmount] = useState(0);
    const [page, setPage] = useState(0);
    const [listLoading, setListLoading] = useState(false);
    const [search, setSearch] = useState('');
    const [searchDraft, setSearchDraft] = useState('');
    const [sort, setSort] = useState({ key: 'date', dir: 'desc' });

    const [trend, setTrend] = useState([]);
    const [coverage, setCoverage] = useState(null);
    const [coverageOpen, setCoverageOpen] = useState(null); // 'never' | 'multi' | null

    const [exceptions, setExceptions] = useState([]);
    const [showExceptions, setShowExceptions] = useState(false);

    const levels = overview?.levels || [];

    const loadOverview = useCallback(async () => {
        setOverviewErr(null);
        try {
            const res = await api.get('/business/rentals/overview', { params: range });
            setOverview(res.data);
            // Keep the active tab valid for this tenant (CMM only has STORE).
            setLevel((prev) => (prev && res.data.levels.includes(prev)) ? prev : res.data.levels[0]);
        } catch (e) {
            setOverviewErr(e?.response?.data?.message || 'Could not load rental overview');
        }
    }, [range]);

    useEffect(() => { loadOverview(); }, [loadOverview, tenantVersion]);

    useEffect(() => {
        if (!level) return;
        let cancelled = false;
        setListLoading(true);
        api.get('/business/rentals/list', {
            params: {
                level, ...range, search: search || undefined,
                sort: sort.key, dir: sort.dir, page, size: PAGE_SIZE,
            },
        })
            .then((res) => {
                if (cancelled) return;
                setRows(res.data.rows || []);
                setTotal(num(res.data.total));
                setTotalAmount(num(res.data.totalAmount));
            })
            .catch(() => { if (!cancelled) { setRows([]); setTotal(0); setTotalAmount(0); } })
            .finally(() => { if (!cancelled) setListLoading(false); });
        return () => { cancelled = true; };
    }, [level, range, search, sort, page, tenantVersion]);

    // Monthly trend for the selected range (server defaults to 12 months when
    // the range is narrower than a month it still returns that month's bar).
    useEffect(() => {
        let cancelled = false;
        api.get('/business/rentals/trend', { params: range })
            .then((res) => { if (!cancelled) setTrend(res.data || []); })
            .catch(() => { if (!cancelled) setTrend([]); });
        return () => { cancelled = true; };
    }, [range, tenantVersion]);

    // One-time-fee coverage — all-time, so independent of the range.
    useEffect(() => {
        let cancelled = false;
        api.get('/business/rentals/coverage')
            .then((res) => { if (!cancelled) setCoverage(res.data); })
            .catch(() => { if (!cancelled) setCoverage(null); });
        return () => { cancelled = true; };
    }, [tenantVersion]);

    useEffect(() => {
        api.get('/business/rentals/exceptions')
            .then((res) => setExceptions(res.data || []))
            .catch(() => setExceptions([]));
    }, [tenantVersion, overview]);

    const pickPreset = (key) => {
        setPreset(key);
        if (key !== 'CUSTOM') {
            const r = computeRange(key);
            setRange(r);
            setDraftRange(r);
            setPage(0);
        }
    };

    const applyCustomRange = (e) => {
        e.preventDefault();
        if (!draftRange.from || !draftRange.to || draftRange.from > draftRange.to) return;
        setRange({ ...draftRange });
        setPage(0);
    };

    const toggleSort = (key) => {
        setSort((s) => (s.key === key
            ? { key, dir: s.dir === 'desc' ? 'asc' : 'desc' }
            : { key, dir: 'desc' }));
        setPage(0);
    };

    const exportCsv = async () => {
        try {
            const res = await api.get('/business/rentals/export', {
                params: { level, ...range }, responseType: 'blob',
            });
            const url = URL.createObjectURL(res.data);
            const a = document.createElement('a');
            a.href = url;
            a.download = `rentals_${level.toLowerCase()}_${range.from}_${range.to}.csv`;
            a.click();
            URL.revokeObjectURL(url);
        } catch { /* surface nothing — button stays usable */ }
    };

    const perLevelMap = useMemo(() => {
        const m = {};
        (overview?.perLevel || []).forEach((r) => { m[r.level] = r; });
        return m;
    }, [overview]);

    // Pivot the /trend rows (month × level) into one object per month for the
    // stacked bars. Missing level-months stay absent — recharts treats them
    // as 0 without inventing rows.
    const trendData = useMemo(() => {
        const byMonth = new Map();
        trend.forEach((r) => {
            const m = byMonth.get(r.month) || { month: r.month };
            m[r.level] = num(r.total_amount);
            m[`${r.level}_count`] = num(r.charge_count);
            byMonth.set(r.month, m);
        });
        return Array.from(byMonth.values()).sort((a, b) => a.month.localeCompare(b.month));
    }, [trend]);
    const trendLevels = useMemo(
        () => levels.filter((l) => trendData.some((m) => m[l] != null)),
        [levels, trendData]);

    const grandTotal = (overview?.perLevel || []).reduce((s, r) => s + num(r.total_amount), 0);
    const exCounts = overview?.exceptions || {};
    const exTotal = num(exCounts.rejected) + num(exCounts.unmatched);

    const cov = coverage?.counts || {};
    const covLevelLabel = coverage?.level === 'TERMINAL' ? 'terminals' : 'stores';
    const coverageRows = coverageOpen === 'never' ? (coverage?.neverRows || [])
        : coverageOpen === 'multi' ? (coverage?.multiRows || []) : [];

    const lastPage = Math.max(Math.ceil(total / PAGE_SIZE) - 1, 0);

    const th = { textAlign: 'left', padding: '9px 14px', fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--text-secondary)', borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' };
    const td = { padding: '9px 14px', fontSize: 13, color: 'var(--text)', borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' };
    const tdMono = { ...td, fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums' };

    /** Clickable, sort-aware header cell. */
    const SortTh = ({ k, children, align }) => (
        <th style={{ ...th, textAlign: align || 'left', cursor: 'pointer', userSelect: 'none' }}
            onClick={() => toggleSort(k)}
            title="Sort">
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: sort.key === k ? 'var(--accent)' : undefined }}>
                {children}
                {sort.key === k && (sort.dir === 'desc' ? <ArrowDown size={11} /> : <ArrowUp size={11} />)}
            </span>
        </th>
    );

    const dateInputStyle = {
        padding: '5px 8px', fontSize: 12, borderRadius: 'var(--radius-sm)',
        border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)',
        colorScheme: 'light dark',
    };

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            {/* Masthead */}
            <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
                <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <Receipt size={20} style={{ color: 'var(--accent)' }} />
                        <h1 style={{ margin: 0, fontSize: 20, fontWeight: 700, color: 'var(--text)' }}>Rentals</h1>
                    </div>
                    <div style={{ marginTop: 4, fontSize: 12.5, color: 'var(--text-secondary)' }}>
                        One-time rental charges from the dedicated rental feed — {levels.length > 1
                            ? 'merchant, store and terminal level'
                            : 'store level'} · {currencyCode}
                        {overview?.lastLoad?.last_load_time && (
                            <> · last file loaded {String(overview.lastLoad.last_load_time).slice(0, 16).replace('T', ' ')}</>
                        )}
                    </div>
                </div>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                    {PRESETS.map((p) => (
                        <button key={p.key} onClick={() => pickPreset(p.key)} style={{
                            padding: '6px 12px', fontSize: 12, fontWeight: 600, cursor: 'pointer',
                            borderRadius: 'var(--radius-sm)',
                            border: `1px solid ${preset === p.key ? 'var(--accent)' : 'var(--border)'}`,
                            background: preset === p.key ? 'var(--accent-soft, rgba(94,130,210,0.12))' : 'var(--surface)',
                            color: preset === p.key ? 'var(--accent)' : 'var(--text-secondary)',
                        }}>
                            {p.label}
                        </button>
                    ))}
                    {preset === 'CUSTOM' && (
                        <form onSubmit={applyCustomRange} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                            <input type="date" value={draftRange.from} max={draftRange.to || undefined}
                                onChange={(e) => setDraftRange((r) => ({ ...r, from: e.target.value }))}
                                style={dateInputStyle} />
                            <span style={{ color: 'var(--text-secondary)', fontSize: 12 }}>→</span>
                            <input type="date" value={draftRange.to} min={draftRange.from || undefined}
                                onChange={(e) => setDraftRange((r) => ({ ...r, to: e.target.value }))}
                                style={dateInputStyle} />
                            <button type="submit" style={{
                                padding: '6px 12px', fontSize: 12, fontWeight: 700, cursor: 'pointer',
                                borderRadius: 'var(--radius-sm)', border: '1px solid var(--accent)',
                                background: 'var(--accent)', color: '#fff',
                            }}>
                                Apply
                            </button>
                        </form>
                    )}
                    <button onClick={loadOverview} title="Refresh" style={{
                        padding: '6px 10px', cursor: 'pointer', borderRadius: 'var(--radius-sm)',
                        border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)',
                    }}>
                        <RefreshCw size={14} />
                    </button>
                </div>
            </div>

            {overviewErr && (
                <div style={{
                    padding: '14px 18px', borderRadius: 'var(--radius-lg)', display: 'flex', alignItems: 'center', gap: 10,
                    border: '1px solid var(--danger-border, #fecaca)', background: 'var(--danger-bg, #fef2f2)',
                    fontSize: 13, fontWeight: 600, color: 'var(--danger-text, #991b1b)',
                }}>
                    <AlertTriangle size={16} /> {overviewErr}
                </div>
            )}

            {/* Tiles */}
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                <Tile label="Total rentals in range" value={fmt.currency(grandTotal)} sub={`${range.from} → ${range.to}`} />
                {levels.map((l) => (
                    <Tile key={l}
                        label={LEVEL_LABELS[l] || l}
                        value={fmt.currency(num(perLevelMap[l]?.total_amount))}
                        sub={`${formatNumber(num(perLevelMap[l]?.charge_count))} charges · ${formatNumber(num(perLevelMap[l]?.entity_count))} entities`} />
                ))}
                {exTotal > 0 && (
                    <button onClick={() => setShowExceptions((v) => !v)} style={{
                        padding: '15px 20px', minWidth: 170, cursor: 'pointer', textAlign: 'left',
                        borderRadius: 'var(--radius-lg)', border: '1px solid var(--danger-border, #fecaca)',
                        background: 'var(--danger-bg, #fef2f2)',
                    }}>
                        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--danger-text, #991b1b)', display: 'flex', alignItems: 'center', gap: 6 }}>
                            <AlertTriangle size={12} /> Exceptions
                        </div>
                        <div style={{ marginTop: 7, fontFamily: 'var(--font-mono)', fontSize: 22, fontWeight: 600, color: 'var(--danger-text, #991b1b)' }}>
                            {formatNumber(exTotal)}
                        </div>
                        <div style={{ marginTop: 3, fontSize: 11, color: 'var(--danger-text, #991b1b)' }}>
                            {num(exCounts.rejected)} rejected · {num(exCounts.unmatched)} unmatched — click to {showExceptions ? 'hide' : 'view'}
                        </div>
                    </button>
                )}
            </div>

            {/* One-time-fee coverage — all-time, not range-scoped */}
            {coverage && num(cov.total) > 0 && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                    <div style={{ fontSize: 11.5, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
                        One-time fee coverage · all {formatNumber(num(cov.total))} {covLevelLabel}, full history
                    </div>
                    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                        <CoverageTile icon={CheckCircle2} label="Billed once" tone="ok"
                            value={formatNumber(num(cov.billed_once))}
                            sub={`${covLevelLabel} charged exactly once — correct`} />
                        <CoverageTile icon={CircleSlash} label="Never billed"
                            tone={num(cov.never_billed) > 0 ? 'warn' : 'ok'}
                            value={formatNumber(num(cov.never_billed))}
                            sub={num(cov.never_billed) > 0 ? 'missed revenue — click to view' : 'nothing missed'}
                            active={coverageOpen === 'never'}
                            onClick={num(cov.never_billed) > 0
                                ? () => setCoverageOpen((v) => (v === 'never' ? null : 'never')) : undefined} />
                        <CoverageTile icon={Copy} label="Billed 2+ times"
                            tone={num(cov.billed_multi) > 0 ? 'warn' : 'ok'}
                            value={formatNumber(num(cov.billed_multi))}
                            sub={num(cov.billed_multi) > 0 ? 'possible double-charge — click to view' : 'no duplicates'}
                            active={coverageOpen === 'multi'}
                            onClick={num(cov.billed_multi) > 0
                                ? () => setCoverageOpen((v) => (v === 'multi' ? null : 'multi')) : undefined} />
                    </div>
                    {coverageOpen && coverageRows.length > 0 && (
                        <div style={{ borderRadius: 'var(--radius-lg)', border: '1px solid var(--danger-border, #fecaca)', overflow: 'auto' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                                <thead><tr>
                                    <th style={th}>{coverage.level === 'TERMINAL' ? 'TID' : 'SID'}</th>
                                    <th style={th}>{coverage.level === 'TERMINAL' ? 'Device' : 'Store'}</th>
                                    <th style={th}>Status</th>
                                    {coverageOpen === 'multi' && <>
                                        <th style={{ ...th, textAlign: 'right' }}>Charges</th>
                                        <th style={{ ...th, textAlign: 'right' }}>Total charged</th>
                                        <th style={th}>First</th>
                                        <th style={th}>Last</th>
                                    </>}
                                </tr></thead>
                                <tbody>
                                    {coverageRows.map((r, i) => (
                                        <tr key={`${r.entity}-${i}`}>
                                            <td style={tdMono}>{r.entity || '—'}</td>
                                            <td style={td}>{r.label || '—'}</td>
                                            <td style={td}>{r.status || '—'}</td>
                                            {coverageOpen === 'multi' && <>
                                                <td style={{ ...tdMono, textAlign: 'right', fontWeight: 700, color: 'var(--danger-text, #991b1b)' }}>{formatNumber(num(r.charges))}</td>
                                                <td style={{ ...tdMono, textAlign: 'right' }}>{fmt.money(num(r.total_amount))}</td>
                                                <td style={tdMono}>{r.first_charge}</td>
                                                <td style={tdMono}>{r.last_charge}</td>
                                            </>}
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                            <div style={{ padding: '8px 14px', fontSize: 11.5, color: 'var(--text-secondary)' }}>
                                {coverageRows.length >= 200 ? 'First 200 shown — export the level CSV for the full set.' : `${coverageRows.length} ${covLevelLabel}`}
                                {coverageOpen === 'never' && ' · Status comes from the merchant master: a closed entity that was never billed may be expected.'}
                            </div>
                        </div>
                    )}
                </div>
            )}

            {/* Monthly collection trend */}
            {trendData.length > 0 && (
                <div style={{ borderRadius: 'var(--radius-lg)', border: '1px solid var(--border)', background: 'var(--surface)', padding: '14px 18px 6px' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', flexWrap: 'wrap', gap: 8 }}>
                        <div style={{ fontSize: 11.5, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
                            Collected per month
                        </div>
                        <div style={{ display: 'flex', gap: 14 }}>
                            {trendLevels.map((l) => (
                                <span key={l} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, color: 'var(--text-secondary)' }}>
                                    <span style={{ width: 9, height: 9, borderRadius: 2, background: LEVEL_COLORS[l] }} />
                                    {LEVEL_LABELS[l] || l}
                                </span>
                            ))}
                        </div>
                    </div>
                    <ResponsiveContainer width="100%" height={190}>
                        <BarChart data={trendData} margin={{ top: 12, right: 4, left: 4, bottom: 0 }}>
                            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
                            <XAxis dataKey="month" tick={{ fontSize: 11, fill: 'var(--text-secondary)' }}
                                axisLine={{ stroke: 'var(--border)' }} tickLine={false} />
                            <YAxis tick={{ fontSize: 11, fill: 'var(--text-secondary)' }}
                                tickFormatter={(v) => formatNumber(v)}
                                axisLine={false} tickLine={false} width={64} />
                            <Tooltip
                                cursor={{ fill: 'var(--accent-soft, rgba(94,130,210,0.08))' }}
                                contentStyle={{
                                    background: 'var(--surface)', border: '1px solid var(--border)',
                                    borderRadius: 8, fontSize: 12, color: 'var(--text)',
                                }}
                                formatter={(v, name) => [fmt.currency(num(v)), LEVEL_LABELS[name] || name]}
                            />
                            {trendLevels.map((l) => (
                                <Bar key={l} dataKey={l} stackId="a" fill={LEVEL_COLORS[l]}
                                    radius={l === trendLevels[trendLevels.length - 1] ? [3, 3, 0, 0] : 0}
                                    maxBarSize={44} />
                            ))}
                        </BarChart>
                    </ResponsiveContainer>
                </div>
            )}

            {/* Exceptions panel */}
            {showExceptions && exceptions.length > 0 && (
                <div style={{ borderRadius: 'var(--radius-lg)', border: '1px solid var(--danger-border, #fecaca)', overflow: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead><tr>
                            <th style={th}>Status</th><th style={th}>Reason</th><th style={th}>MID</th>
                            <th style={th}>SID</th><th style={th}>TID</th>
                            <th style={{ ...th, textAlign: 'right' }}>Amount</th><th style={th}>Payment date</th>
                        </tr></thead>
                        <tbody>
                            {exceptions.map((r) => (
                                <tr key={r.raw_id}>
                                    <td style={{ ...td, fontWeight: 700, color: 'var(--danger-text, #991b1b)' }}>{r.status}</td>
                                    <td style={{ ...td, whiteSpace: 'normal' }}>{r.error_message}</td>
                                    <td style={tdMono}>{r.mid || '—'}</td>
                                    <td style={tdMono}>{r.sid || '—'}</td>
                                    <td style={tdMono}>{r.tid || '—'}</td>
                                    <td style={{ ...tdMono, textAlign: 'right' }}>{r.rental_amount != null ? fmt.money(num(r.rental_amount)) : '—'}</td>
                                    <td style={tdMono}>{r.payment_date || '—'}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {/* Level tabs + search + export */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
                <div style={{ display: 'flex', gap: 4 }}>
                    {levels.map((l) => (
                        <button key={l} onClick={() => { setLevel(l); setPage(0); }} style={{
                            padding: '7px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer',
                            borderRadius: 'var(--radius-sm)', border: 'none',
                            background: level === l ? 'var(--accent)' : 'transparent',
                            color: level === l ? '#fff' : 'var(--text-secondary)',
                        }}>
                            {LEVEL_LABELS[l] || l}
                        </button>
                    ))}
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <form onSubmit={(e) => { e.preventDefault(); setSearch(searchDraft.trim()); setPage(0); }}
                        style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '5px 10px', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--surface)' }}>
                        <Search size={13} style={{ color: 'var(--text-secondary)' }} />
                        <input value={searchDraft} onChange={(e) => setSearchDraft(e.target.value)}
                            placeholder="MID / SID / TID / name…"
                            style={{ border: 'none', outline: 'none', background: 'transparent', fontSize: 12.5, color: 'var(--text)', width: 180 }} />
                    </form>
                    <button onClick={exportCsv} disabled={!level} style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer',
                        padding: '7px 13px', fontSize: 12, fontWeight: 700, borderRadius: 'var(--radius-sm)',
                        border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)',
                    }}>
                        <Download size={13} /> CSV
                    </button>
                </div>
            </div>

            {/* Charges table */}
            <div style={{ borderRadius: 'var(--radius-lg)', border: '1px solid var(--border)', background: 'var(--surface)', overflow: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead><tr>
                        {level === 'MERCHANT' && <SortTh k="id">MID</SortTh>}
                        {level !== 'MERCHANT' && <SortTh k="id">SID</SortTh>}
                        {level === 'TERMINAL' && <th style={th}>TID</th>}
                        <SortTh k="merchant">Merchant</SortTh>
                        {level !== 'MERCHANT' && <SortTh k="store">Store</SortTh>}
                        <SortTh k="amount" align="right">Rental amount</SortTh>
                        <SortTh k="date">Payment date</SortTh>
                    </tr></thead>
                    <tbody>
                        {listLoading && (
                            <tr><td style={{ ...td, textAlign: 'center', color: 'var(--text-secondary)' }} colSpan={7}>Loading…</td></tr>
                        )}
                        {!listLoading && rows.length === 0 && (
                            <tr><td style={{ ...td, textAlign: 'center', color: 'var(--text-secondary)' }} colSpan={7}>
                                No rental charges in this range.
                            </td></tr>
                        )}
                        {!listLoading && rows.map((r) => (
                            <tr key={r.rental_id}>
                                {level === 'MERCHANT' && <td style={tdMono}>{r.mid}</td>}
                                {level !== 'MERCHANT' && <td style={tdMono}>{r.sid || '—'}</td>}
                                {level === 'TERMINAL' && <td style={tdMono}>{r.tid}</td>}
                                <td style={td}>{r.merchant_name || '—'}</td>
                                {level !== 'MERCHANT' && <td style={td}>{r.store_name || '—'}</td>}
                                <td style={{ ...tdMono, textAlign: 'right', fontWeight: 600 }}>{fmt.money(num(r.rental_amount))}</td>
                                <td style={tdMono}>{r.payment_date}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {/* Footer: totals + pager */}
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '9px 14px', fontSize: 12, color: 'var(--text-secondary)' }}>
                    <span>
                        {formatNumber(total)} charges · total <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 700, color: 'var(--text)' }}>{fmt.money(totalAmount)}</span>
                    </span>
                    <span style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                        <button disabled={page <= 0} onClick={() => setPage((p) => p - 1)}
                            style={{ cursor: page > 0 ? 'pointer' : 'default', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)', borderRadius: 'var(--radius-sm)', padding: '3px 10px', fontSize: 12 }}>
                            Prev
                        </button>
                        page {page + 1} / {lastPage + 1}
                        <button disabled={page >= lastPage} onClick={() => setPage((p) => p + 1)}
                            style={{ cursor: page < lastPage ? 'pointer' : 'default', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)', borderRadius: 'var(--radius-sm)', padding: '3px 10px', fontSize: 12 }}>
                            Next
                        </button>
                    </span>
                </div>
            </div>
        </div>
    );
};

export default RentalOverview;
