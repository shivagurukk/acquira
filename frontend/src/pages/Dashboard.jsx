import React, { useState, useEffect, useMemo, useCallback } from 'react';
import api from '../api/axios';
import {
    RefreshCw, TrendingUp, TrendingDown, Receipt, Wallet,
    Percent, Coins, BarChart3, CalendarRange, ArrowDownRight, Layers,
} from 'lucide-react';
import {
    ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid,
    Tooltip as ReTooltip, ResponsiveContainer, ReferenceLine, Cell,
} from 'recharts';
import EmptyState from '../components/EmptyState';
import SkeletonLoader from '../components/SkeletonLoader';
import { useAuth } from '../contexts/AuthContext';
import { createFmt } from '../utils/formatters';

/* ════════════════════════════════════════════════════════════════════
   CEO Landing Dashboard — MTD (weeks 1–5) / YTD (month-wise).
   Metrics per period: Volume, Transactions, Avg Ticket, MSF,
   Interchange, Scheme Fee, Net Revenue, Net Margin %. Compact B/M/K,
   delta chips vs prior period, MTD run-rate projection.
   Data: /api/business/ceo-summary (sum_daily_bank weekly buckets +
   sum_monthly_bank month rows, settlement currency).
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

const deltaPct = (cur, prev) => {
    const c = num(cur), p = num(prev);
    if (!p) return null;
    return ((c - p) / Math.abs(p)) * 100;
};

const fullNum = (v, sym = '') =>
    (sym ? sym + ' ' : '') + Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 2 });

/* ─── Delta chip ─── */
const DeltaChip = ({ pct, compareLabel, invert }) => {
    if (pct == null) return null;
    const good = invert ? pct <= 0 : pct >= 0;    // for costs, down is good
    const color = good ? '#059669' : '#dc2626';
    const bg = good ? 'rgba(5,150,105,0.10)' : 'rgba(220,38,38,0.10)';
    const Icon = pct >= 0 ? TrendingUp : TrendingDown;
    return (
        <span title={compareLabel} style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            fontSize: 12, fontWeight: 600, color, background: bg,
            borderRadius: 999, padding: '2px 8px', whiteSpace: 'nowrap',
        }}>
            <Icon size={12} />
            {(pct >= 0 ? '+' : '') + pct.toFixed(1)}%
        </span>
    );
};

/* ─── Hero KPI tile — top accent hairline, restrained register ─── */
const KpiTile = ({ label, value, fullValue, deltaPct: dp, compareLabel, invertDelta, icon: Icon, accent }) => (
    <div style={{
        position: 'relative', overflow: 'hidden',
        background: 'var(--bg-card)', border: '1px solid var(--border)',
        borderRadius: 14, padding: '18px 18px 16px', minWidth: 0,
        display: 'flex', flexDirection: 'column', gap: 10,
        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))',
    }}>
        <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 3,
            background: accent, opacity: 0.9 }} />
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 11.5, fontWeight: 600, letterSpacing: '0.05em',
                textTransform: 'uppercase', color: 'var(--text-secondary)' }}>{label}</span>
            <span style={{ display: 'inline-flex', padding: 5, borderRadius: 8,
                background: accent + '18' }}>
                <Icon size={14} style={{ color: accent }} />
            </span>
        </div>
        <div title={fullValue} style={{
            fontSize: 25, fontWeight: 700, color: 'var(--text)',
            fontVariantNumeric: 'tabular-nums', lineHeight: 1.1, letterSpacing: '-0.01em',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>{value}</div>
        <div style={{ minHeight: 20 }}>
            <DeltaChip pct={dp} compareLabel={compareLabel} invert={invertDelta} />
        </div>
    </div>
);

/* ─── Recharts tooltip ─── */
const BucketTooltip = ({ active, payload, label, fmt }) => {
    if (!active || !payload || !payload.length) return null;
    const d = payload[0].payload;
    return (
        <div style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: 10, padding: '11px 14px',
            boxShadow: 'var(--shadow-md, 0 4px 12px rgba(16,24,40,0.10))',
            fontSize: 12.5, color: 'var(--text)', minWidth: 200,
        }}>
            <div style={{ fontWeight: 700, marginBottom: 7 }}>
                {label}{d.partial ? ' · partial' : ''}
            </div>
            {[
                ['Volume', fmt.currency(d.volume)],
                ['Transactions', num(d.txns).toLocaleString()],
                ['Avg Ticket', fmt.currency(d.avgTicket)],
                ['MSF', fmt.currency(d.msf)],
                ['Interchange', fmt.currency(d.interchange)],
                ['Scheme Fee', fmt.currency(d.schemeFee)],
                ['Net Revenue', fmt.currency(d.netRevenue)],
                ['Net Margin', `${num(d.marginPct).toFixed(2)}%`],
            ].map(([k, v]) => (
                <div key={k} style={{ display: 'flex', justifyContent: 'space-between', gap: 18, padding: '1.5px 0' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>{k}</span>
                    <span style={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>{v}</span>
                </div>
            ))}
        </div>
    );
};

const Dashboard = () => {
    const { currencySymbol, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);

    const [mode, setMode] = useState('MTD');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    const load = useCallback(async () => {
        setLoading(true); setError(null);
        try {
            const res = await api.get('/business/ceo-summary');
            setData(res.data);
        } catch (e) {
            setError(e?.response?.data?.message || 'Failed to load summary');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load, tenantVersion]);

    const period = mode === 'MTD' ? data?.mtd : data?.ytd;
    const buckets = mode === 'MTD' ? (data?.mtd?.weeks || []) : (data?.ytd?.months || []);
    const totals = period?.totals;
    const prev = period?.prev;
    const compareLabel = mode === 'MTD'
        ? 'vs last month, same days elapsed'
        : 'vs last year, same period';

    const chartData = useMemo(() => buckets.map(b => ({
        ...b,
        volume: num(b.volume),
        msf: num(b.msf),
        interchange: num(b.interchange),
        schemeFee: num(b.schemeFee),
        netRevenue: num(b.netRevenue),
        avgTicket: num(b.avgTicket),
        marginPct: num(b.marginPct),
        txns: num(b.txns),
    })), [buckets]);

    const [bestIdx, worstIdx] = useMemo(() => {
        let bi = -1, wi = -1, bv = -Infinity, wv = Infinity;
        chartData.forEach((b, i) => {
            if (b.volume <= 0) return;
            if (b.marginPct > bv) { bv = b.marginPct; bi = i; }
            if (b.marginPct < wv) { wv = b.marginPct; wi = i; }
        });
        return chartData.filter(b => b.volume > 0).length > 1 ? [bi, wi] : [-1, -1];
    }, [chartData]);

    const runRate = data?.mtd?.runRate;

    if (loading) return <SkeletonLoader type="dashboard" />;

    if (error) return (
        <div style={{ padding: 32 }}>
            <EmptyState title="Could not load dashboard" description={error}
                action={<button className="btn btn-primary" onClick={load}>Retry</button>} />
        </div>
    );

    const hasData = totals && num(totals.txns) > 0;

    const TABLE_HEADS = [mode === 'MTD' ? 'Week' : 'Month', 'Transactions', 'Volume',
        'Avg Ticket', 'MSF', 'Interchange', 'Scheme Fee', 'Net Revenue', 'Net Margin %'];

    return (
        <div style={{ padding: '24px 28px', maxWidth: 1440, margin: '0 auto' }}>

            {/* ── Header ── */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                flexWrap: 'wrap', gap: 14, marginBottom: 20 }}>
                <div>
                    <h1 style={{ margin: 0, fontSize: 22, fontWeight: 700, color: 'var(--text)',
                        letterSpacing: '-0.01em' }}>
                        Executive Summary
                    </h1>
                    <div style={{ marginTop: 5, fontSize: 12.5, color: 'var(--text-secondary)',
                        display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                        <CalendarRange size={13} />
                        {mode === 'MTD' ? (data?.mtd?.label || '') : (data?.ytd?.label || '')}
                        {data?.effectiveDate ? ` · through ${data.effectiveDate}` : ''}
                        <span style={{ color: 'var(--border)' }}>·</span>
                        settlement currency
                    </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <div style={{ display: 'inline-flex', background: 'var(--bg-card)',
                        border: '1px solid var(--border)', borderRadius: 10, padding: 3 }}>
                        {['MTD', 'YTD'].map(m => (
                            <button key={m} onClick={() => setMode(m)} style={{
                                border: 'none', cursor: 'pointer', borderRadius: 8,
                                padding: '6px 18px', fontSize: 13, fontWeight: 600,
                                background: mode === m ? 'var(--brand, #3b82f6)' : 'transparent',
                                color: mode === m ? '#fff' : 'var(--text-secondary)',
                                transition: 'background 0.15s, color 0.15s',
                            }}>{m}</button>
                        ))}
                    </div>
                    <button onClick={load} title="Refresh" style={{
                        border: '1px solid var(--border)', background: 'var(--bg-card)',
                        borderRadius: 10, padding: 8, cursor: 'pointer',
                        color: 'var(--text-secondary)', display: 'flex',
                    }}>
                        <RefreshCw size={15} />
                    </button>
                </div>
            </div>

            {!hasData ? (
                <EmptyState title={`No ${mode} data`}
                    description="No transactions found for this period yet. Upload data to populate the dashboard." />
            ) : (
                <>
                    {/* ── Hero KPI strip (8 tiles) ── */}
                    <div style={{ display: 'grid', gap: 14, marginBottom: 22,
                        gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))' }}>
                        <KpiTile label="Volume" icon={BarChart3} accent="#3b82f6"
                            value={fmt.currency(num(totals.volume))}
                            fullValue={fullNum(totals.volume, currencySymbol)}
                            deltaPct={deltaPct(totals.volume, prev?.volume)} compareLabel={compareLabel} />
                        <KpiTile label="Transactions" icon={Receipt} accent="#8b5cf6"
                            value={fmt.number(num(totals.txns))}
                            fullValue={fullNum(totals.txns)}
                            deltaPct={deltaPct(totals.txns, prev?.txns)} compareLabel={compareLabel} />
                        <KpiTile label="Avg Ticket" icon={Coins} accent="#06b6d4"
                            value={fmt.currency(num(totals.avgTicket))}
                            fullValue={fullNum(totals.avgTicket, currencySymbol)}
                            deltaPct={deltaPct(totals.avgTicket, prev?.avgTicket)} compareLabel={compareLabel} />
                        <KpiTile label="MSF" icon={Wallet} accent="#f59e0b"
                            value={fmt.currency(num(totals.msf))}
                            fullValue={fullNum(totals.msf, currencySymbol)}
                            deltaPct={deltaPct(totals.msf, prev?.msf)} compareLabel={compareLabel} />
                        <KpiTile label="Interchange" icon={ArrowDownRight} accent="#0ea5e9"
                            value={fmt.currency(num(totals.interchange))}
                            fullValue={fullNum(totals.interchange, currencySymbol)}
                            deltaPct={deltaPct(totals.interchange, prev?.interchange)}
                            compareLabel={`${compareLabel} · lower is better`} invertDelta />
                        <KpiTile label="Scheme Fee" icon={Layers} accent="#a855f7"
                            value={fmt.currency(num(totals.schemeFee))}
                            fullValue={fullNum(totals.schemeFee, currencySymbol)}
                            deltaPct={deltaPct(totals.schemeFee, prev?.schemeFee)}
                            compareLabel={`${compareLabel} · lower is better`} invertDelta />
                        <KpiTile label="Net Revenue" icon={TrendingUp} accent="#10b981"
                            value={fmt.currency(num(totals.netRevenue))}
                            fullValue={fullNum(totals.netRevenue, currencySymbol)}
                            deltaPct={deltaPct(totals.netRevenue, prev?.netRevenue)} compareLabel={compareLabel} />
                        <KpiTile label="Net Margin" icon={Percent} accent="#ef4444"
                            value={`${num(totals.marginPct).toFixed(2)}%`}
                            fullValue={`${num(totals.marginPct).toFixed(4)}% of volume`}
                            deltaPct={prev && num(prev.marginPct) !== 0
                                ? num(totals.marginPct) - num(prev.marginPct) : null}
                            compareLabel={`${compareLabel} (pp change)`} />
                    </div>

                    {/* ── MTD run-rate strip ── */}
                    {mode === 'MTD' && runRate && num(runRate.elapsedDays) > 0 && (
                        <div style={{
                            background: 'var(--bg-card)', border: '1px solid var(--border)',
                            borderRadius: 12, padding: '12px 18px', marginBottom: 22,
                            display: 'flex', flexWrap: 'wrap', gap: 24, alignItems: 'center',
                            fontSize: 13, color: 'var(--text-secondary)',
                        }}>
                            <span style={{ fontWeight: 600, color: 'var(--text)' }}>
                                Month run-rate · day {runRate.elapsedDays} of {runRate.daysInMonth}
                            </span>
                            <span title={fullNum(runRate.projectedVolume, currencySymbol)}>
                                Volume tracking to <b style={{ color: 'var(--text)' }}>{fmt.currency(num(runRate.projectedVolume))}</b>
                            </span>
                            <span title={fullNum(runRate.projectedNetRevenue, currencySymbol)}>
                                Net revenue tracking to <b style={{ color: 'var(--text)' }}>{fmt.currency(num(runRate.projectedNetRevenue))}</b>
                            </span>
                            <span title={fullNum(runRate.projectedTxns)}>
                                Txns tracking to <b style={{ color: 'var(--text)' }}>{fmt.number(num(runRate.projectedTxns))}</b>
                            </span>
                        </div>
                    )}

                    {/* ── Breakdown chart ── */}
                    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
                        borderRadius: 14, padding: '18px 18px 8px', marginBottom: 22,
                        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))' }}>
                        <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--text)', marginBottom: 12 }}>
                            {mode === 'MTD' ? 'Week-by-week' : 'Month-by-month'}
                            <span style={{ fontWeight: 500, color: 'var(--text-secondary)' }}> · Volume vs Net Margin %</span>
                        </div>
                        <ResponsiveContainer width="100%" height={300}>
                            <ComposedChart data={chartData} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                                <CartesianGrid strokeDasharray="2 4" stroke="var(--border)" vertical={false} />
                                <XAxis dataKey="label" tick={{ fontSize: 12, fill: 'var(--text-secondary)' }}
                                    axisLine={false} tickLine={false} />
                                <YAxis yAxisId="vol" tickFormatter={(v) => fmt.number(v)}
                                    tick={{ fontSize: 11, fill: 'var(--text-secondary)' }}
                                    axisLine={false} tickLine={false} width={56} />
                                <YAxis yAxisId="pct" orientation="right"
                                    tickFormatter={(v) => `${v.toFixed(1)}%`}
                                    tick={{ fontSize: 11, fill: 'var(--text-secondary)' }}
                                    axisLine={false} tickLine={false} width={50} />
                                <ReTooltip content={<BucketTooltip fmt={fmt} />}
                                    cursor={{ fill: 'var(--border)', fillOpacity: 0.25 }} />
                                <Bar yAxisId="vol" dataKey="volume" name="Volume"
                                    radius={[6, 6, 0, 0]} maxBarSize={52}>
                                    {chartData.map((b, i) => (
                                        <Cell key={i} fill="#3b82f6"
                                            fillOpacity={b.partial ? 0.4 : 0.82} />
                                    ))}
                                </Bar>
                                <Line yAxisId="pct" type="monotone" dataKey="marginPct" name="Net Margin %"
                                    stroke="#10b981" strokeWidth={2.2}
                                    dot={{ r: 3.5, strokeWidth: 0, fill: '#10b981' }} />
                                {mode === 'MTD' && runRate && num(runRate.projectedVolume) > 0 && (
                                    <ReferenceLine yAxisId="vol"
                                        y={num(runRate.projectedVolume) / Math.max(chartData.length, 1)}
                                        stroke="#94a3b8" strokeDasharray="5 4"
                                        label={{ value: 'avg pace', position: 'insideTopRight',
                                            fontSize: 10, fill: 'var(--text-secondary)' }} />
                                )}
                            </ComposedChart>
                        </ResponsiveContainer>
                        {mode === 'MTD' && chartData.some(b => b.partial) && (
                            <div style={{ fontSize: 11.5, color: 'var(--text-secondary)', padding: '4px 2px 8px' }}>
                                Lighter bar = week in progress.
                            </div>
                        )}
                    </div>

                    {/* ── Breakdown table ── */}
                    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
                        borderRadius: 14, overflow: 'hidden',
                        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))' }}>
                        <div style={{ overflowX: 'auto' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                                <thead>
                                    <tr style={{ borderBottom: '1px solid var(--border)' }}>
                                        {TABLE_HEADS.map((h, i) => (
                                            <th key={h} style={{
                                                textAlign: i === 0 ? 'left' : 'right',
                                                padding: '12px 16px', fontSize: 11, fontWeight: 600,
                                                letterSpacing: '0.05em', textTransform: 'uppercase',
                                                color: 'var(--text-secondary)', whiteSpace: 'nowrap',
                                            }}>{h}</th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {chartData.map((b, i) => {
                                        const tint = i === bestIdx ? 'rgba(5,150,105,0.06)'
                                            : i === worstIdx ? 'rgba(220,38,38,0.05)' : 'transparent';
                                        return (
                                            <tr key={b.label} style={{
                                                borderBottom: '1px solid var(--border)', background: tint,
                                            }}>
                                                <td style={{ padding: '11px 16px', fontWeight: 600, color: 'var(--text)', whiteSpace: 'nowrap' }}>
                                                    {b.label}
                                                    {b.partial && <span style={{
                                                        marginLeft: 8, fontSize: 10.5, fontWeight: 600,
                                                        color: '#b45309', background: 'rgba(180,83,9,0.10)',
                                                        borderRadius: 999, padding: '1px 7px',
                                                    }}>partial</span>}
                                                    {mode === 'MTD' && b.from && (
                                                        <span style={{ marginLeft: 8, fontSize: 11, color: 'var(--text-secondary)' }}>
                                                            {fmt.date(b.from)}–{fmt.date(b.to)}
                                                        </span>
                                                    )}
                                                </td>
                                                <td style={tdNum} title={fullNum(b.txns)}>{num(b.txns).toLocaleString()}</td>
                                                <td style={tdNum} title={fullNum(b.volume, currencySymbol)}>{fmt.currency(b.volume)}</td>
                                                <td style={tdNum} title={fullNum(b.avgTicket, currencySymbol)}>{fmt.currency(b.avgTicket)}</td>
                                                <td style={tdNum} title={fullNum(b.msf, currencySymbol)}>{fmt.currency(b.msf)}</td>
                                                <td style={tdNum} title={fullNum(b.interchange, currencySymbol)}>{fmt.currency(b.interchange)}</td>
                                                <td style={tdNum} title={fullNum(b.schemeFee, currencySymbol)}>{fmt.currency(b.schemeFee)}</td>
                                                <td style={{ ...tdNum, fontWeight: 600,
                                                    color: b.netRevenue >= 0 ? 'var(--text)' : '#dc2626' }}
                                                    title={fullNum(b.netRevenue, currencySymbol)}>{fmt.currency(b.netRevenue)}</td>
                                                <td style={{ ...tdNum, fontWeight: 700,
                                                    color: b.marginPct >= 0 ? '#059669' : '#dc2626' }}>
                                                    {b.marginPct.toFixed(2)}%
                                                </td>
                                            </tr>
                                        );
                                    })}
                                    <tr style={{ background: 'var(--bg-hover, rgba(148,163,184,0.06))' }}>
                                        <td style={{ padding: '12px 16px', fontWeight: 700, color: 'var(--text)' }}>
                                            {mode} Total
                                        </td>
                                        <td style={tdTotal} title={fullNum(totals.txns)}>{num(totals.txns).toLocaleString()}</td>
                                        <td style={tdTotal} title={fullNum(totals.volume, currencySymbol)}>{fmt.currency(num(totals.volume))}</td>
                                        <td style={tdTotal} title={fullNum(totals.avgTicket, currencySymbol)}>{fmt.currency(num(totals.avgTicket))}</td>
                                        <td style={tdTotal} title={fullNum(totals.msf, currencySymbol)}>{fmt.currency(num(totals.msf))}</td>
                                        <td style={tdTotal} title={fullNum(totals.interchange, currencySymbol)}>{fmt.currency(num(totals.interchange))}</td>
                                        <td style={tdTotal} title={fullNum(totals.schemeFee, currencySymbol)}>{fmt.currency(num(totals.schemeFee))}</td>
                                        <td style={tdTotal} title={fullNum(totals.netRevenue, currencySymbol)}>{fmt.currency(num(totals.netRevenue))}</td>
                                        <td style={{ ...tdTotal,
                                            color: num(totals.marginPct) >= 0 ? '#059669' : '#dc2626' }}>
                                            {num(totals.marginPct).toFixed(2)}%
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
};

const tdNum = {
    padding: '11px 16px', textAlign: 'right', color: 'var(--text)',
    fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
};
const tdTotal = { ...tdNum, fontWeight: 700 };

export default Dashboard;
