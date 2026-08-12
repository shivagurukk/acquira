import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Box, Paper, Typography, Grid } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    BarChart, Bar, ComposedChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
    ResponsiveContainer, Cell,
} from 'recharts';
import {
    CreditCard, DollarSign, Hash, Percent, Receipt, Globe, Monitor, Award,
} from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';
import { formatMsf, formatCurrency as fmtMoney, formatCompactCurrency } from '../../utils/formatters';
import { useDataBounds } from '../../hooks/useDataBounds';
import DataBoundsBanner from '../../components/DataBoundsBanner';

/* ────────────────────────────────────────────────────────────────────────────
   Debit & Prepaid Metrics
   Answers: "how much of my book is debit/prepaid, what does it earn, and how
   is it composed (bucket / destination / channel / scheme)?"

   Design pass: the four composition tiles now share the same premium card
   language as the top KPI row (icon chip + a prominent percentage headline +
   a segmented share bar + a compact legend), so the whole page reads as one
   restrained financial-instrument surface — no flat "just a value" cards.
   Every composition tile leads with the share PERCENTAGE (the number that
   actually matters here), with the currency figure as supporting context. The
   Row 1 KPI tiles gain a colored share-% chip in their subtitle so pricing
   efficiency and segment share read at a glance.
   ──────────────────────────────────────────────────────────────────────────── */

const computeDateRange = (preset) => {
    const now = new Date();
    const fmt = (d) => {
        const yr = d.getFullYear();
        const mo = String(d.getMonth() + 1).padStart(2, '0');
        const dy = String(d.getDate()).padStart(2, '0');
        return `${yr}-${mo}-${dy}`;
    };
    switch (preset) {
        case 'TODAY':      return { startDate: fmt(now), endDate: fmt(now) };
        case 'MONTH':      return { startDate: fmt(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmt(now) };
        case 'LAST_MONTH': return { startDate: fmt(new Date(now.getFullYear(), now.getMonth() - 1, 1)), endDate: fmt(new Date(now.getFullYear(), now.getMonth(), 0)) };
        case 'YEAR':       return { startDate: fmt(new Date(now.getFullYear(), 0, 1)), endDate: fmt(now) };
        case 'PY':         return { startDate: fmt(new Date(now.getFullYear() - 1, 0, 1)), endDate: fmt(new Date(now.getFullYear() - 1, 11, 31)) };
        default:           return {};
    }
};

const monthLabel = (ym) => {
    if (!ym) return '';
    const [y, m] = String(ym).split('-');
    const d = new Date(Number(y), Number(m) - 1, 1);
    return d.toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
};

const CustomTooltip = ({ active, payload, label, formatCurrency, formatNumber }) => {
    if (!active || !payload || !payload.length) return null;
    return (
        <Box sx={{ bgcolor: '#0f172a', borderRadius: '8px', px: 2, py: 1.5, boxShadow: '0 8px 24px rgba(0,0,0,0.2)' }}>
            <Typography variant="caption" color="#94a3b8" fontWeight={600}>{label}</Typography>
            {payload.map((p, i) => (
                <Typography key={i} variant="body2" fontWeight={700} sx={{ color: p.color || '#fff', mt: 0.5 }}>
                    {p.name}: {p.dataKey === 'count' ? formatNumber(p.value) : formatCurrency(p.value)}
                </Typography>
            ))}
        </Box>
    );
};

/* Chart card shell — follows the shared dashboard ChartCard conventions
   so this page sits visually consistent with the rest of Business Analytics. */
const ChartCard = ({ title, subtitle, accent = '#6366f1', empty, children }) => (
    <Paper sx={{
        position: 'relative', overflow: 'hidden',
        width: '100%',
        p: '18px 22px', height: 340, borderRadius: '16px',
        border: '1px solid var(--border)',
        bgcolor: 'var(--bg-card)', boxShadow: 'var(--shadow-card)',
        display: 'flex', flexDirection: 'column',
    }}>
        <Box sx={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 3, background: `linear-gradient(${accent}, ${accent}55)` }} />
        <Box sx={{ mb: 1.5 }}>
            <Typography sx={{ fontSize: '0.92rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.01em' }}>{title}</Typography>
            {subtitle && <Typography sx={{ fontSize: '0.76rem', color: 'var(--text-muted)', mt: 0.3 }}>{subtitle}</Typography>}
        </Box>
        <Box sx={{ flex: 1, minHeight: 0 }}>
            {empty ? (
                <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: 'var(--text-muted)', fontSize: '0.85rem' }}>
                    No data for this window
                </Box>
            ) : children}
        </Box>
    </Paper>
);

/* ─── Share pill / subtitle ────────────────────────────────────────────────
   A compact, colored chip used inside the Row 1 KPI subtitles to surface the
   share PERCENTAGE (or the MSF-rate delta vs book). KpiCard renders `subtitle`
   as a child node, so we can pass this JSX straight through without touching
   the shared KpiCards component. Tones map to design-system status tokens. */
const TONE = {
    brand:   { bg: 'var(--brand-bg, #eff6ff)',   fg: 'var(--brand, #2563eb)' },
    success: { bg: 'var(--success-bg, #ecfdf5)', fg: 'var(--success, #059669)' },
    danger:  { bg: 'var(--danger-bg, #fef2f2)',  fg: 'var(--danger, #dc2626)' },
};
const ShareSubtitle = ({ badge, text }) => (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        {badge && (
            <span style={{
                display: 'inline-flex', alignItems: 'center',
                padding: '2px 7px', borderRadius: 7,
                fontSize: '0.68rem', fontWeight: 700, letterSpacing: '0.01em',
                fontVariantNumeric: 'tabular-nums',
                background: (TONE[badge.tone] || TONE.brand).bg,
                color: (TONE[badge.tone] || TONE.brand).fg,
            }}>
                {badge.text}
            </span>
        )}
        {text && <span style={{ color: 'var(--text-muted, #9ca3af)' }}>{text}</span>}
    </span>
);

/* ─── Composition tile ─────────────────────────────────────────────────────
   One unified premium card for the four "split" tiles. Leads with the share
   PERCENTAGE of the leading segment as the headline, backs it with a hairline
   segmented bar and a compact legend. Same icon-chip / border / hover
   language as KpiCard, so Row 1 and Row 2 read as a single system.

   `headline`   — the big number (usually the leading segment's share %)
   `caption`    — supporting line under the headline
   `segments`   — [{ label, value, color }]  (bar + legend)
   `formatCompact` — currency compactor for the legend amounts
   `single`     — optional { value, sub } to render a single-value tile
                  (Top Scheme) in the same shell instead of a split. */
const CompositionCard = ({ title, icon: Icon, accent, headline, caption, segments, formatCompact, single }) => {
    const total = (segments || []).reduce((s, x) => s + (Number(x.value) || 0), 0);

    return (
        <div style={{
            position: 'relative', display: 'flex', flexDirection: 'column', height: '100%',
            background: 'var(--bg-card, #fff)',
            border: '1px solid var(--border, #e5e7eb)',
            borderRadius: 'var(--radius-lg, 14px)',
            padding: '20px 22px',
            overflow: 'hidden',
            transition: 'box-shadow 0.2s ease, transform 0.2s ease, border-color 0.2s ease',
        }}
            onMouseEnter={e => {
                e.currentTarget.style.boxShadow = 'var(--shadow-hover, 0 6px 20px rgba(15,23,42,0.08))';
                e.currentTarget.style.transform = 'translateY(-2px)';
                e.currentTarget.style.borderColor = 'var(--border-strong, #d1d5db)';
            }}
            onMouseLeave={e => {
                e.currentTarget.style.boxShadow = 'none';
                e.currentTarget.style.transform = '';
                e.currentTarget.style.borderColor = 'var(--border, #e5e7eb)';
            }}
        >
            {/* header: icon chip + label */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
                <div style={{
                    width: 34, height: 34, borderRadius: 10,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    background: `color-mix(in srgb, ${accent} 8%, transparent)`,
                    border: `1px solid color-mix(in srgb, ${accent} 16%, transparent)`,
                }}>
                    <Icon size={16} color={accent} strokeWidth={1.9} />
                </div>
                <span style={{
                    fontSize: '0.72rem', fontWeight: 600, letterSpacing: '0.04em',
                    textTransform: 'uppercase', color: 'var(--text-muted, #9ca3af)',
                }}>
                    {title}
                </span>
            </div>

            {/* headline number */}
            <div style={{
                fontSize: '1.5rem', fontWeight: 700, letterSpacing: '-0.03em', lineHeight: 1,
                fontVariantNumeric: 'tabular-nums', color: 'var(--text, #111827)',
            }}>
                {single ? single.value : (headline ?? '—')}
            </div>
            {(single ? single.sub : caption) && (
                <div style={{ marginTop: 6, fontSize: '0.74rem', color: 'var(--text-muted, #9ca3af)' }}>
                    {single ? single.sub : caption}
                </div>
            )}

            {/* split bar + legend (skipped for single-value tiles) */}
            {!single && (
                total === 0 ? (
                    <div style={{ marginTop: 'auto', paddingTop: 14, fontSize: '0.76rem', color: 'var(--text-muted, #9ca3af)' }}>
                        No volume in this window
                    </div>
                ) : (
                    <div style={{ marginTop: 'auto', paddingTop: 16 }}>
                        <div style={{
                            display: 'flex', height: 7, borderRadius: 999, overflow: 'hidden',
                            marginBottom: 12, border: '1px solid var(--border-light, #f1f5f9)',
                        }}>
                            {segments.map((s, i) => (
                                <div key={i} style={{
                                    width: `${(Number(s.value) / total) * 100}%`,
                                    background: s.color,
                                    minWidth: Number(s.value) > 0 ? 2 : 0,
                                }} />
                            ))}
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
                            {segments.map((s, i) => {
                                const pct = total > 0 ? (Number(s.value) / total) * 100 : 0;
                                return (
                                    <div key={i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 7, minWidth: 0 }}>
                                            <span style={{ width: 8, height: 8, borderRadius: 3, background: s.color, flexShrink: 0 }} />
                                            <span style={{
                                                fontSize: '0.76rem', fontWeight: 600, color: 'var(--text-secondary, #475569)',
                                                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                            }}>
                                                {s.label}
                                            </span>
                                        </div>
                                        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, flexShrink: 0 }}>
                                            <span style={{ fontSize: '0.78rem', fontWeight: 700, color: 'var(--text, #0f172a)', fontVariantNumeric: 'tabular-nums' }}>
                                                {pct.toFixed(1)}%
                                            </span>
                                            <span style={{ fontSize: '0.7rem', color: 'var(--text-muted, #9ca3af)', fontWeight: 500 }}>
                                                {formatCompact(s.value)}
                                            </span>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                )
            )}
        </div>
    );
};

const DebitPrepaidMetrics = () => {
    // No 'AED' default — unknown currency renders unlabelled rather than wrong.
    const { currencyCode, formatCurrency: fmtCurr, tenantVersion } = useAuth() || {};

    const formatCurrency = useCallback((val) => (fmtCurr ? fmtCurr(val) : fmtMoney(val)), [fmtCurr]);

    const formatNumber  = (val) => new Intl.NumberFormat('en-US').format(val || 0);
    // Money compaction with the tenant's currency + precision (BHD keeps fils).
    const formatCompact = (val) => formatCompactCurrency(val);
    const formatBps     = (val) => (val === null || val === undefined ? '—' : `${Number(val).toFixed(1)} bps`);

    const [filters, setFilters] = useState(() => ({ datePreset: 'MONTH', startDate: '', endDate: '' }));
    const [data, setData] = useState([]);
    const [summary, setSummary] = useState(null);
    const [loading, setLoading] = useState(false);
    const [summaryLoading, setSummaryLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [fetchError, setFetchError] = useState(null);

    const { startDate: boundsStart, endDate: boundsEnd, boundsLoaded, latest } = useDataBounds(tenantVersion);

    // Latest filters, readable from an effect without making that effect depend
    // on `filters` (which would re-fire the report on every drawer keystroke —
    // this page runs on an explicit Run Report action).
    const filtersRef = useRef(filters);
    filtersRef.current = filters;

    const resolveBody = (payload) => {
        const body = { ...payload };
        if (body.datePreset && body.datePreset !== 'CUSTOM') {
            const range = computeDateRange(body.datePreset);
            if (range.startDate && range.endDate) {
                body.startDate = range.startDate;
                body.endDate = range.endDate;
            }
        }
        delete body.datePreset;
        return body;
    };

    const authHeaders = () => {
        const token = localStorage.getItem('token');
        const tenantId = localStorage.getItem('defaultTenantId');
        return {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
            ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}),
        };
    };

    const fetchData = useCallback(async (overrideFilters) => {
        setLoading(true);
        setFetchError(null);
        try {
            const body = resolveBody(overrideFilters || filters);
            const res = await fetch('/api/business/debit-prepaid-metrics', {
                method: 'POST', headers: authHeaders(), body: JSON.stringify(body),
            });
            if (res.ok) {
                const result = await res.json();
                if (result.length === 0) {
                    setFetchError('No data found for selected filters. Try expanding your date range or removing filters.');
                }
                setData(result.map((r, i) => ({ id: `${r.mid || ''}-${r.sid || ''}-${i}`, ...r })));
            } else {
                const errorText = await res.text();
                console.error('Debit-Prepaid API error:', res.status, errorText);
                setFetchError(`API returned ${res.status}. Check server logs.`);
                setData([]);
            }
        } catch (error) {
            console.error('Failed to fetch debit/prepaid metrics:', error);
            setFetchError(`Network error: ${error.message}`);
            setData([]);
        } finally {
            setLoading(false);
        }
    }, [filters]);

    const fetchSummary = useCallback(async (overrideFilters) => {
        setSummaryLoading(true);
        try {
            const body = resolveBody(overrideFilters || filters);
            const res = await fetch('/api/business/debit-prepaid-summary', {
                method: 'POST', headers: authHeaders(), body: JSON.stringify(body),
            });
            if (res.ok) {
                setSummary(await res.json());
            } else {
                console.error('Debit-Prepaid summary API error:', res.status);
                setSummary(null);
            }
        } catch (error) {
            console.error('Failed to fetch debit/prepaid summary:', error);
            setSummary(null);
        } finally {
            setSummaryLoading(false);
        }
    }, [filters]);

    // Adopt the resolved data bounds AND run the first report with them, in one
    // effect and from one object.
    //
    // These used to be two effects: one that setFilters(...bounds) and one that
    // called fetchData() with no argument. Both fire in the same commit when
    // boundsLoaded flips, so the fetch ran BEFORE the state update landed and
    // read `filters` from its own stale closure — still the initial
    // { datePreset: 'MONTH' }. resolveBody then recomputed that preset into the
    // CURRENT CALENDAR MONTH, so the page always opened on this month rather
    // than the data window, and showed "No data found" whenever the feed's
    // latest business_date was in an earlier month. The fetch effect keyed only
    // on [boundsLoaded], so the corrected filters never triggered a re-run.
    // Passing `next` explicitly is what fetchData's override parameter is for.
    // (Same stale-closure shape already fixed for the date-preset chips in
    // PremiumReportHeader.)
    useEffect(() => {
        if (!boundsLoaded) return;
        const next = { ...filtersRef.current, datePreset: 'CUSTOM', startDate: boundsStart, endDate: boundsEnd };
        setFilters(next);
        fetchData(next);
        fetchSummary(next);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [boundsLoaded, boundsStart, boundsEnd, tenantVersion]);

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const runReport = (next) => { fetchData(next); fetchSummary(next); };

    /* ── Row 1: segment vs. book tiles ──
       The two "share" tiles surface their share PERCENTAGE as a colored chip in
       the subtitle, and the MSF-rate tile carries a chip for its delta in bps
       vs the blended book rate. These are shares/deltas (not period-over-period
       growth), so we render them as a neutral/status chip via ShareSubtitle
       rather than the KpiCard arrow pill (which implies directional movement). */
    const segmentKpis = useMemo(() => {
        if (!summary) return [];
        const seg = summary.segment || {};
        const book = summary.book || {};
        const segLabel = summary.segmentLabel === 'CUSTOM' ? 'Selected Card Types' : 'Debit/Prepaid';
        const monthSpark = (summary.byMonth || []).map(m => Number(m.volume) || 0);
        const monthSparkCnt = (summary.byMonth || []).map(m => Number(m.count) || 0);

        const volShare = Number(seg.volumeSharePct ?? 0);
        const cntShare = Number(seg.countSharePct ?? 0);
        const msfDeltaBps = (seg.msfRateBps != null && book.msfRateBps != null)
            ? Number(seg.msfRateBps) - Number(book.msfRateBps) : null;

        return [
            {
                title: `${segLabel} Volume`, value: formatCompact(seg.volume), icon: DollarSign, color: '#3b82f6',
                subtitle: <ShareSubtitle badge={{ text: `${volShare.toFixed(1)}% of book`, tone: 'brand' }}
                    text={`of ${formatCompact(book.volume)} total`} />,
                sparkData: monthSpark, trendLabel: 'MONTHLY TREND',
            },
            {
                title: `${segLabel} Transactions`, value: formatNumber(seg.count), icon: Hash, color: '#10b981',
                subtitle: <ShareSubtitle badge={{ text: `${cntShare.toFixed(1)}% of txns`, tone: 'success' }}
                    text={`of ${formatNumber(book.count)} total`} />,
                sparkData: monthSparkCnt, trendLabel: 'MONTHLY TREND',
            },
            {
                title: 'Effective MSF Rate', value: formatBps(seg.msfRateBps), icon: Percent, color: '#0ea5e9',
                subtitle: <ShareSubtitle
                    badge={msfDeltaBps == null ? null : {
                        text: `${msfDeltaBps >= 0 ? '+' : ''}${msfDeltaBps.toFixed(1)} bps vs book`,
                        tone: msfDeltaBps >= 0 ? 'success' : 'danger',
                    }}
                    text={`book ${formatBps(book.msfRateBps)}`} />,
            },
            {
                title: 'Avg Ticket', value: formatCurrency(seg.avgTicket), icon: Receipt, color: '#8b5cf6',
                subtitle: <ShareSubtitle text={`vs ${formatCurrency(book.avgTicket)} blended book average`} />,
            },
        ];
    }, [summary, currencyCode]); // eslint-disable-line react-hooks/exhaustive-deps

    /* ── Row 2: composition splits ── */
    const bucketSplit = useMemo(() => {
        const rows = summary?.byBucket || [];
        const debit = rows.find(r => r.bucket === 'DEBIT') || { volume: 0 };
        const prepaid = rows.find(r => r.bucket === 'PREPAID') || { volume: 0 };
        return [
            { label: 'Debit', value: debit.volume, color: '#3b82f6' },
            { label: 'Prepaid', value: prepaid.volume, color: '#f59e0b' },
        ];
    }, [summary]);

    const destinationSplit = useMemo(() => {
        const rows = summary?.byDestination || [];
        const palette = { DOMESTIC: '#10b981', INTERNATIONAL: '#6366f1' };
        return rows.slice(0, 2).map(r => ({ label: r.key, value: r.volume, color: palette[r.key] || '#94a3b8' }));
    }, [summary]);

    const channelSplit = useMemo(() => {
        const rows = summary?.byChannel || [];
        const palette = { POS: '#0ea5e9', ECOM: '#8b5cf6', MOTO: '#f97316' };
        return rows.slice(0, 3).map(r => ({ label: r.key, value: r.volume, color: palette[r.key] || '#94a3b8' }));
    }, [summary]);

    const topScheme = summary?.byScheme?.[0];

    /* Leading-segment share % becomes each composition tile's headline. */
    const leadShare = (segments) => {
        const total = (segments || []).reduce((s, x) => s + (Number(x.value) || 0), 0);
        if (!total || !segments.length) return { pct: null, label: null };
        const lead = [...segments].sort((a, b) => Number(b.value) - Number(a.value))[0];
        return { pct: (Number(lead.value) / total) * 100, label: lead.label };
    };

    const bucketLead = useMemo(() => leadShare(bucketSplit), [bucketSplit]);
    const destLead   = useMemo(() => leadShare(destinationSplit), [destinationSplit]);
    const chanLead   = useMemo(() => leadShare(channelSplit), [channelSplit]);

    /* ── Charts ── */
    const monthChartData = useMemo(() => (summary?.byMonth || []).map(m => ({
        month: monthLabel(m.month), volume: Number(m.volume) || 0, count: Number(m.count) || 0,
    })), [summary]);

    const schemeChartData = useMemo(() => (summary?.byScheme || []).map(r => ({
        scheme: r.key, volume: Number(r.volume) || 0,
    })), [summary]);

    const SCHEME_COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#8b5cf6', '#ef4444'];

    /* ── Table ── */
    const columns = [
        {
            field: 'mid', headerName: 'MID', width: 150,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: '"Roboto Mono", monospace', fontSize: '13px', color: 'var(--text-secondary, #475569)', bgcolor: 'var(--border-light, #f1f5f9)', px: 1, py: 0.5, borderRadius: '4px', border: '1px solid var(--border, #e2e8f0)' }}>
                    {params.value}
                </Typography>
            )
        },
        {
            field: 'sid', headerName: 'SID', width: 150,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: '"Roboto Mono", monospace', fontSize: '13px', color: 'var(--text-secondary, #475569)', bgcolor: 'var(--border-light, #f1f5f9)', px: 1, py: 0.5, borderRadius: '4px', border: '1px solid var(--border, #e2e8f0)' }}>
                    {params.value || '-'}
                </Typography>
            )
        },
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', flex: 1, minWidth: 180,
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color="var(--text, #1e293b)">{params.value}</Typography>
        },
        {
            field: 'count', headerName: 'COUNT', type: 'number', width: 100, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color="var(--text-secondary, #64748b)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatNumber(params.value)}</Typography>
        },
        {
            field: 'volume', headerName: `VOLUME${currencyCode ? ` (${currencyCode})` : ''}`, type: 'number', width: 160, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="700" color="var(--text, #0f172a)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        },
        {
            field: 'debitVolume', headerName: 'DEBIT VOL', type: 'number', width: 140, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color="#3b82f6" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        },
        {
            field: 'prepaidVolume', headerName: 'PREPAID VOL', type: 'number', width: 140, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color="#f59e0b" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        },
        {
            field: 'msf', headerName: 'MSF', type: 'number', width: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color="var(--text-secondary, #64748b)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatMsf(params.value)}</Typography>
        },
        {
            field: 'msfRateBps', headerName: 'MSF BPS', type: 'number', width: 110, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color="var(--text-secondary, #64748b)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatBps(params.value)}</Typography>
        },
        {
            field: 'avgTicket', headerName: 'AVG TICKET', type: 'number', flex: 1, minWidth: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight={600} color="var(--text, #0f172a)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Debit & Prepaid Metrics" subtitle="Segment share, pricing efficiency, and composition of the debit/prepaid book"
                icon={CreditCard}
                onExport={() => exportToCSV(data, 'debit_prepaid_metrics')}
                onRunReport={() => runReport()} onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={(next) => runReport(next)}
                loading={loading || summaryLoading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={() => runReport()} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <DataBoundsBanner
                latest={latest}
                boundsLoaded={boundsLoaded}
                currentEnd={filters.endDate}
                onJumpToLatest={() => { const next = { ...filters, datePreset: 'CUSTOM', startDate: boundsStart, endDate: boundsEnd }; setFilters(next); runReport(next); }}
            />

            {/* Row 1 — segment vs. book */}
            <KpiCards cards={segmentKpis} loading={summaryLoading && !summary} />

            {/* Row 2 — composition */}
            <Grid container spacing={2} sx={{ mb: 3 }}>
                <Grid item xs={12} sm={6} md={3}>
                    <CompositionCard
                        title="Debit vs Prepaid" icon={CreditCard} accent="#3b82f6"
                        headline={bucketLead.pct == null ? '—' : `${bucketLead.pct.toFixed(1)}%`}
                        caption={bucketLead.label ? `${bucketLead.label} leads the segment` : 'No volume in this window'}
                        segments={bucketSplit} formatCompact={formatCompact}
                    />
                </Grid>
                <Grid item xs={12} sm={6} md={3}>
                    <CompositionCard
                        title="Domestic vs Intl" icon={Globe} accent="#10b981"
                        headline={destLead.pct == null ? '—' : `${destLead.pct.toFixed(1)}%`}
                        caption={destLead.label ? `${destLead.label} share of segment` : 'No volume in this window'}
                        segments={destinationSplit} formatCompact={formatCompact}
                    />
                </Grid>
                <Grid item xs={12} sm={6} md={3}>
                    <CompositionCard
                        title="Channel Mix" icon={Monitor} accent="#8b5cf6"
                        headline={chanLead.pct == null ? '—' : `${chanLead.pct.toFixed(1)}%`}
                        caption={chanLead.label ? `${chanLead.label} share of segment` : 'No volume in this window'}
                        segments={channelSplit} formatCompact={formatCompact}
                    />
                </Grid>
                <Grid item xs={12} sm={6} md={3}>
                    <CompositionCard
                        title="Top Scheme" icon={Award} accent="#f59e0b"
                        formatCompact={formatCompact}
                        single={topScheme ? {
                            value: topScheme.key,
                            sub: `${Number(topScheme.sharePct ?? 0).toFixed(1)}% of segment · ${formatCompact(topScheme.volume)}`,
                        } : { value: '—', sub: 'No scheme data' }}
                    />
                </Grid>
            </Grid>

            {fetchError && !loading && data.length === 0 && (
                <Paper sx={{ p: 3, mb: 2, borderRadius: 2, bgcolor: 'var(--warning-bg, #fffbeb)', border: '1px solid var(--warning-border, #fde68a)' }}>
                    <Typography variant="body2" color="var(--warning-text, #92400e)" fontWeight="600">{fetchError}</Typography>
                    <Typography variant="caption" color="var(--warning-text, #a16207)" sx={{ mt: 1, display: 'block' }}>
                        This report shows transactions where card_type is DEBIT or PREPAID (any destination unless you've narrowed it via filters).
                        If this is empty, the underlying data may not have card_type populated — check the server log for the
                        "[DebitPrepaid] EMPTY result" diagnostic line which lists the actual card_type values present.
                    </Typography>
                </Paper>
            )}

            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={data} columns={columns} loading={loading} rowHeight={55}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
                    pageSizeOptions={[25, 50, 100]}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default DebitPrepaidMetrics;
