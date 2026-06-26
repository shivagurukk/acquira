import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
    Box, Typography, Stack, Chip, CircularProgress, Autocomplete,
    TextField, Paper, Divider, Tooltip,
} from '@mui/material';
import {
    BarChart3, DollarSign, Hash, TrendingUp, Award, RefreshCw,
    Users, ArrowRight, Zap, Target, CreditCard,
} from 'lucide-react';
import {
    ResponsiveContainer, BarChart, Bar, LineChart, Line,
    XAxis, YAxis, CartesianGrid, Tooltip as RTooltip, Legend, Cell,
} from 'recharts';
import { merchantApi } from '../../api/merchants';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import { pageContainer } from '../../theme/dataGridStyles';

// ── Palette: one distinct colour per merchant slot ─────────────────
const PALETTE = ['#4f6ef7', '#00b37e', '#f59e0b', '#ef4444'];
const PALETTE_BG = ['#eef1ff', '#e6f8f2', '#fef9ec', '#fff0f0'];

// ── Formatters (currency-agnostic — fmt is built inside component from tenant) ────
const fmtCompact = (v) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(v || 0);
const fmtNum = (v) => new Intl.NumberFormat('en-US').format(v || 0);
const pct = (v) => `${(v || 0).toFixed(1)}%`;
// Local YYYY-MM-DD formatter (avoids toISOString() UTC shift). Module-scoped so
// computeRange() below can use it — it must NOT live inside the component, or
// computeRange (called in a useState initializer) throws "fmtLocal is not defined".
const fmtLocal = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

// ── Date preset helpers ─────────────────────────────────────────────
const PRESETS = [
    { key: 'MONTH',      label: 'This month' },
    { key: 'LAST_MONTH', label: 'Last month' },
    { key: 'Q',          label: 'Last 90D'   },
    { key: 'YEAR',       label: 'This year'  },
    { key: 'CUSTOM',     label: 'Custom'     },
];
const computeRange = (key) => {
    const now = new Date();
    switch (key) {
        case 'MONTH':      return { s: fmtLocal(new Date(now.getFullYear(), now.getMonth(), 1)), e: fmtLocal(now) };
        case 'LAST_MONTH': return { s: fmtLocal(new Date(now.getFullYear(), now.getMonth() - 1, 1)), e: fmtLocal(new Date(now.getFullYear(), now.getMonth(), 0)) };
        case 'Q':          return { s: fmtLocal(new Date(now.getFullYear(), now.getMonth() - 3, now.getDate())), e: fmtLocal(now) };
        case 'YEAR':       return { s: fmtLocal(new Date(now.getFullYear(), 0, 1)), e: fmtLocal(now) };
        default:           return null;
    }
};

// ── Tiny sub-components ────────────────────────────────────────────
const Pill = ({ label, color, bg }) => (
    <Box sx={{ display:'inline-flex', alignItems:'center', px:1, py:0.25, borderRadius:'6px', bgcolor: bg, color, fontSize:'11px', fontWeight:700, letterSpacing:'0.02em' }}>
        {label}
    </Box>
);

const KpiRow = ({ label, icon: Icon, values, colors, leader, delta, formatter }) => (
    <Box sx={{ display:'grid', gridTemplateColumns:`160px repeat(${values.length}, 1fr)`, alignItems:'center', gap:1, py:1.5, borderBottom:'0.5px solid var(--color-border-tertiary)' }}>
        <Stack direction="row" spacing={0.75} alignItems="center">
            {Icon && <Icon size={13} color="var(--color-text-secondary)" />}
            <Typography sx={{ fontSize:'12px', color:'var(--color-text-secondary)', fontWeight:500 }}>{label}</Typography>
        </Stack>
        {values.map((v, i) => {
            const isTop = leader === i;
            return (
                <Box key={i} sx={{ textAlign:'center' }}>
                    <Typography sx={{ fontSize:'15px', fontWeight: isTop ? 700 : 500, color: isTop ? colors[i] : 'var(--color-text-primary)' }}>
                        {formatter(v)}
                    </Typography>
                    {isTop && delta > 0 && (
                        <Typography sx={{ fontSize:'10px', color: colors[i], fontWeight:600 }}>
                            +{delta.toFixed(1)}% ahead
                        </Typography>
                    )}
                </Box>
            );
        })}
    </Box>
);

// ── Custom recharts tooltip ────────────────────────────────────────
const CustomTooltip = ({ active, payload, label }) => {
    if (!active || !payload?.length) return null;
    return (
        <Box sx={{ bgcolor:'var(--color-background-primary)', border:'0.5px solid var(--color-border-tertiary)', borderRadius:'8px', p:1.5, fontSize:'12px', minWidth:140 }}>
            <Typography sx={{ fontWeight:600, mb:0.5, fontSize:'12px' }}>{label}</Typography>
            {payload.map((p, i) => (
                <Stack key={i} direction="row" justifyContent="space-between" spacing={2}>
                    <Typography sx={{ color: p.color, fontSize:'11px' }}>{p.name}</Typography>
                    <Typography sx={{ fontWeight:600, fontSize:'11px' }}>{fmtCompact(p.value)}</Typography>
                </Stack>
            ))}
        </Box>
    );
};

// ── Empty state ────────────────────────────────────────────────────
const EmptyState = () => (
    <Box sx={{ display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', py:12, gap:2, opacity:0.5 }}>
        <Users size={56} strokeWidth={1} />
        <Typography sx={{ fontSize:'16px', fontWeight:600 }}>Select 2 or 3 merchants to compare</Typography>
        <Typography sx={{ fontSize:'13px', color:'var(--color-text-secondary)' }}>Use the search box above to add merchants</Typography>
    </Box>
);

// ── Main component ─────────────────────────────────────────────────
const MerchantComparison = () => {
    const { currencySymbol } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol).currency, [currencySymbol]);
    const [options, setOptions] = useState([]);
    const [loadingOptions, setLoadingOptions] = useState(false);
    const [selected, setSelected] = useState([]);
    const [preset, setPreset] = useState('MONTH');
    const [dateRange, setDateRange] = useState(() => { const r = computeRange('MONTH'); return { startDate: r.s, endDate: r.e }; });
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);

    // Load merchant list on mount
    useEffect(() => {
        (async () => {
            try {
                const res = await merchantApi.search('');
                setOptions(Array.from(new Map(res.map(m => [m.merchantId, m])).values()));
            } catch (e) { console.error(e); }
        })();
    }, []);

    const handleSearch = useCallback(async (q) => {
        if (!q) return;
        setLoadingOptions(true);
        try {
            const res = await merchantApi.search(q);
            setOptions(prev => Array.from(new Map([...prev, ...res].map(m => [m.merchantId, m])).values()));
        } catch (e) { console.error(e); }
        finally { setLoadingOptions(false); }
    }, []);

    const handlePreset = (key) => {
        setPreset(key);
        const r = computeRange(key);
        if (r) setDateRange({ startDate: r.s, endDate: r.e });
    };

    const runComparison = useCallback(async () => {
        if (selected.length < 2) return;
        setLoading(true); setError(null);
        try {
            const res = await merchantApi.compare(selected.map(m => m.merchantId), dateRange.startDate, dateRange.endDate);
            setData(res);
        } catch (e) {
            setError('Comparison failed. Please try again.');
            console.error(e);
        } finally { setLoading(false); }
    }, [selected, dateRange]);

    // Auto-run when selection or date changes (if ≥2 merchants)
    useEffect(() => {
        if (selected.length >= 2) runComparison();
    }, [selected, dateRange]); // eslint-disable-line react-hooks/exhaustive-deps

    const merchants = data?.merchants || [];
    const leaders = data?.comparison?.leaders || {};
    const deltas = data?.comparison?.deltas || {};

    // Find leader index for a KPI key
    const leaderIdx = (key) => {
        const leaderId = leaders[key];
        if (leaderId == null) return -1;
        return merchants.findIndex(m => m.merchantId === leaderId);
    };

    // Build monthly trend chart data (union of all months across merchants)
    const trendData = (() => {
        const monthSet = new Set();
        merchants.forEach(m => (m.monthlyTrend || []).forEach(t => monthSet.add(t.month)));
        return [...monthSet].sort().map(month => {
            const row = { month };
            merchants.forEach(m => {
                const found = (m.monthlyTrend || []).find(t => t.month === month);
                row[m.name] = found ? Number(found.volume) : 0;
            });
            return row;
        });
    })();

    // Build scheme bar chart data
    const schemeData = (() => {
        const schemeSet = new Set();
        merchants.forEach(m => (m.cardSchemeBreakdown || []).forEach(s => schemeSet.add(s.name)));
        return [...schemeSet].map(scheme => {
            const row = { scheme };
            merchants.forEach(m => {
                const found = (m.cardSchemeBreakdown || []).find(s => s.name === scheme);
                row[m.name] = found ? Number(found.volume) : 0;
            });
            return row;
        });
    })();

    // Build card type bar data
    const cardTypeData = (() => {
        const typeSet = new Set();
        merchants.forEach(m => (m.cardTypeBreakdown || []).forEach(c => typeSet.add(c.name)));
        return [...typeSet].map(type => {
            const row = { type };
            merchants.forEach(m => {
                const found = (m.cardTypeBreakdown || []).find(c => c.name === type);
                row[m.name] = found ? Number(found.volume) : 0;
            });
            return row;
        });
    })();

    return (
        <Box sx={pageContainer}>

            {/* ── Page header ── */}
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" sx={{ mb: 2.5 }}>
                <Stack direction="row" spacing={1.5} alignItems="center">
                    <Box sx={{ width:36, height:36, borderRadius:'8px', display:'flex', alignItems:'center', justifyContent:'center', bgcolor:'#eff6ff' }}>
                        <BarChart3 size={17} color="#3b82f6" />
                    </Box>
                    <Box>
                        <Typography sx={{ fontSize:'1.1rem', fontWeight:700, color:'var(--color-text-primary)', letterSpacing:'-0.02em' }}>
                            Merchant Comparison
                        </Typography>
                        <Typography sx={{ fontSize:'0.78rem', color:'var(--color-text-secondary)' }}>
                            Side-by-side performance analysis — up to 3 merchants
                        </Typography>
                    </Box>
                </Stack>

                {/* Run button */}
                <Box
                    onClick={() => !loading && selected.length >= 2 && runComparison()}
                    sx={{
                        display:'flex', alignItems:'center', gap:0.75, px:2, py:0.9,
                        borderRadius:'8px', cursor: selected.length < 2 || loading ? 'not-allowed' : 'pointer',
                        bgcolor: selected.length < 2 ? 'var(--color-background-secondary)' : '#3b82f6',
                        color: selected.length < 2 ? 'var(--color-text-tertiary)' : 'white',
                        fontSize:'12px', fontWeight:700, transition:'all 0.15s',
                        opacity: loading ? 0.7 : 1,
                        '&:hover': selected.length >= 2 && !loading ? { bgcolor:'#2563eb' } : {},
                    }}
                >
                    {loading ? <CircularProgress size={13} color="inherit" /> : <RefreshCw size={13} />}
                    {loading ? 'Comparing...' : 'Run comparison'}
                </Box>
            </Stack>

            {/* ── Controls bar ── */}
            <Paper elevation={0} sx={{ border:'0.5px solid var(--color-border-tertiary)', borderRadius:'12px', p:2, mb:2.5 }}>
                <Stack direction={{ xs:'column', md:'row' }} spacing={2} alignItems={{ md:'center' }}>

                    {/* Merchant picker */}
                    <Box sx={{ flex: 1.5 }}>
                        <Autocomplete
                            multiple
                            options={options}
                            getOptionLabel={(o) => o.mid ? `${o.mid} — ${o.name}` : o.name}
                            isOptionEqualToValue={(o, v) => o.merchantId === v.merchantId}
                            filterSelectedOptions
                            loading={loadingOptions}
                            value={selected}
                            onChange={(_, val) => { if (val.length <= 3) setSelected(val); }}
                            onInputChange={(_, val, reason) => { if (reason === 'input') handleSearch(val); }}
                            renderInput={(params) => (
                                <TextField
                                    {...params}
                                    size="small"
                                    placeholder="Search merchants by name or MID…"
                                    sx={{ '& .MuiOutlinedInput-root': { borderRadius:'8px', fontSize:'13px' } }}
                                    InputProps={{
                                        ...params.InputProps,
                                        startAdornment: (
                                            <>
                                                <Users size={14} color="var(--color-text-tertiary)" style={{ marginRight: 6 }} />
                                                {params.InputProps.startAdornment}
                                            </>
                                        ),
                                    }}
                                />
                            )}
                            renderOption={(props, option) => (
                                <li {...props} key={option.merchantId}>
                                    <Box>
                                        <Typography sx={{ fontSize:'13px', fontWeight:600 }}>{option.name}</Typography>
                                        <Typography sx={{ fontSize:'11px', color:'var(--color-text-secondary)' }}>
                                            MID: {option.mid || 'N/A'}{option.city ? ` · ${option.city}` : ''} · {option.status}
                                        </Typography>
                                    </Box>
                                </li>
                            )}
                            renderTags={(tagVal, getTagProps) =>
                                tagVal.map((option, index) => {
                                    const color = PALETTE[index];
                                    return (
                                        <Chip
                                            {...getTagProps({ index })}
                                            key={option.merchantId}
                                            label={option.name}
                                            size="small"
                                            sx={{ fontWeight:600, fontSize:'11px', bgcolor: PALETTE_BG[index], color, border:`1px solid ${color}40`, borderRadius:'6px', height:22 }}
                                        />
                                    );
                                })
                            }
                        />
                    </Box>

                    {/* Divider */}
                    <Divider orientation="vertical" flexItem sx={{ display:{ xs:'none', md:'block' } }} />

                    {/* Date presets */}
                    <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                        {PRESETS.map(p => (
                            <Box
                                key={p.key}
                                onClick={() => handlePreset(p.key)}
                                sx={{
                                    px:1.25, py:0.5, borderRadius:'6px', cursor:'pointer', fontSize:'11px', fontWeight:600,
                                    whiteSpace:'nowrap', transition:'all 0.12s',
                                    ...(preset === p.key
                                        ? { bgcolor:'#3b82f6', color:'white' }
                                        : { bgcolor:'var(--color-background-secondary)', color:'var(--color-text-secondary)', '&:hover': { color:'var(--color-text-primary)' } }),
                                }}
                            >
                                {p.label}
                            </Box>
                        ))}
                    </Stack>

                    {/* Custom date inputs (only when CUSTOM selected) */}
                    {preset === 'CUSTOM' && (
                        <Stack direction="row" spacing={0.75} alignItems="center">
                            <TextField type="date" size="small" value={dateRange.startDate}
                                onChange={e => setDateRange(p => ({ ...p, startDate: e.target.value }))}
                                sx={{ width:130, '& .MuiOutlinedInput-root': { borderRadius:'8px', fontSize:'12px', height:32 } }} />
                            <ArrowRight size={14} color="var(--color-text-tertiary)" />
                            <TextField type="date" size="small" value={dateRange.endDate}
                                onChange={e => setDateRange(p => ({ ...p, endDate: e.target.value }))}
                                sx={{ width:130, '& .MuiOutlinedInput-root': { borderRadius:'8px', fontSize:'12px', height:32 } }} />
                        </Stack>
                    )}
                </Stack>
            </Paper>

            {/* ── Error ── */}
            {error && (
                <Paper elevation={0} sx={{ p:2, mb:2, borderRadius:'10px', bgcolor:'#fff0f0', border:'0.5px solid #fca5a5' }}>
                    <Typography sx={{ fontSize:'13px', color:'#dc2626' }}>{error}</Typography>
                </Paper>
            )}

            {/* ── Loading spinner ── */}
            {loading && (
                <Box sx={{ display:'flex', justifyContent:'center', alignItems:'center', py:10, gap:1.5 }}>
                    <CircularProgress size={24} sx={{ color:'#3b82f6' }} />
                    <Typography sx={{ fontSize:'13px', color:'var(--color-text-secondary)' }}>Fetching merchant data…</Typography>
                </Box>
            )}

            {/* ── Empty state ── */}
            {!loading && !data && <EmptyState />}

            {/* ── Results ── */}
            {!loading && merchants.length > 0 && (
                <Stack spacing={2}>

                    {/* ── Merchant header cards ── */}
                    <Box sx={{ display:'grid', gridTemplateColumns:`repeat(${merchants.length}, 1fr)`, gap:1.5 }}>
                        {merchants.map((m, i) => (
                            <Paper key={m.merchantId} elevation={0} sx={{
                                border:`0.5px solid ${PALETTE[i]}40`,
                                borderTop:`3px solid ${PALETTE[i]}`,
                                borderRadius:'12px', p:2,
                            }}>
                                <Stack direction="row" spacing={1} alignItems="center" sx={{ mb:1 }}>
                                    <Box sx={{ width:28, height:28, borderRadius:'8px', bgcolor: PALETTE_BG[i], display:'flex', alignItems:'center', justifyContent:'center' }}>
                                        <Typography sx={{ fontSize:'11px', fontWeight:800, color: PALETTE[i] }}>
                                            {m.name?.[0]?.toUpperCase()}
                                        </Typography>
                                    </Box>
                                    <Typography sx={{ fontSize:'14px', fontWeight:700, color:'var(--color-text-primary)' }}>{m.name}</Typography>
                                </Stack>
                                <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
                                    <Pill label={m.status || 'UNKNOWN'} color={m.status === 'ACTIVE' ? '#059669' : '#dc2626'} bg={m.status === 'ACTIVE' ? '#ecfdf5' : '#fef2f2'} />
                                    {m.mid && <Pill label={`MID: ${m.mid}`} color="#475569" bg="#f1f5f9" />}
                                    {m.city && <Pill label={m.city} color="#6d28d9" bg="#f5f3ff" />}
                                </Stack>
                            </Paper>
                        ))}
                    </Box>

                    {/* ── KPI comparison table ── */}
                    <Paper elevation={0} sx={{ border:'0.5px solid var(--color-border-tertiary)', borderRadius:'12px', p:2.5 }}>
                        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb:1.5 }}>
                            <Typography sx={{ fontSize:'13px', fontWeight:700 }}>Key Performance Indicators</Typography>
                            <Stack direction="row" spacing={1}>
                                {merchants.map((m, i) => (
                                    <Stack key={i} direction="row" spacing={0.5} alignItems="center">
                                        <Box sx={{ width:8, height:8, borderRadius:'50%', bgcolor: PALETTE[i] }} />
                                        <Typography sx={{ fontSize:'11px', color:'var(--color-text-secondary)', fontWeight:500 }}>{m.name}</Typography>
                                    </Stack>
                                ))}
                            </Stack>
                        </Stack>

                        {/* Column headers */}
                        <Box sx={{ display:'grid', gridTemplateColumns:`160px repeat(${merchants.length}, 1fr)`, gap:1, pb:1, borderBottom:'0.5px solid var(--color-border-tertiary)', mb:0.5 }}>
                            <Box />
                            {merchants.map((m, i) => (
                                <Typography key={i} sx={{ fontSize:'11px', fontWeight:700, color: PALETTE[i], textAlign:'center', textTransform:'uppercase', letterSpacing:'0.04em' }}>
                                    {m.name.split(' ')[0]}
                                </Typography>
                            ))}
                        </Box>

                        <KpiRow label="Total Volume" icon={DollarSign}
                            values={merchants.map(m => m.totalVolume)}
                            colors={PALETTE} leader={leaderIdx('totalVolume')} delta={deltas.totalVolume} formatter={fmt} />
                        <KpiRow label="Transactions" icon={Hash}
                            values={merchants.map(m => m.totalTxns)}
                            colors={PALETTE} leader={leaderIdx('totalTxns')} delta={deltas.totalTxns}
                            formatter={fmtNum} />
                        <KpiRow label="Avg Ticket Size" icon={TrendingUp}
                            values={merchants.map(m => m.avgTxnValue)}
                            colors={PALETTE} leader={leaderIdx('avgTxnValue')} delta={deltas.avgTxnValue} formatter={fmt} />
                        <KpiRow label="Total Margin" icon={Target}
                            values={merchants.map(m => m.totalMargin)}
                            colors={PALETTE} leader={leaderIdx('totalMargin')} delta={deltas.totalMargin} formatter={fmt} />
                        <KpiRow label="DCC Opt-in Rate" icon={Zap}
                            values={merchants.map(m => m.dccOptinRate)}
                            colors={PALETTE} leader={-1} delta={0} formatter={pct} />
                        <KpiRow label="Volatility Index" icon={Award}
                            values={merchants.map(m => m.volatilityIndex || 0)}
                            colors={PALETTE} leader={-1} delta={0} formatter={(v) => v.toFixed(2)} />
                    </Paper>

                    {/* ── Charts row ── */}
                    <Box sx={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:1.5 }}>

                        {/* Volume trend */}
                        <Paper elevation={0} sx={{ border:'0.5px solid var(--color-border-tertiary)', borderRadius:'12px', p:2.5 }}>
                            <Typography sx={{ fontSize:'13px', fontWeight:700, mb:2 }}>Volume Trend</Typography>
                            {trendData.length > 0 ? (
                                <ResponsiveContainer width="100%" height={240}>
                                    <LineChart data={trendData}>
                                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--color-border-tertiary)" />
                                        <XAxis dataKey="month" tick={{ fontSize:11 }} tickLine={false} axisLine={false} />
                                        <YAxis tickFormatter={(v) => `${v/1000}k`} tick={{ fontSize:11 }} tickLine={false} axisLine={false} />
                                        <RTooltip content={<CustomTooltip />} />
                                        <Legend iconType="circle" iconSize={8} wrapperStyle={{ fontSize:11 }} />
                                        {merchants.map((m, i) => (
                                            <Line key={m.merchantId} type="monotone" dataKey={m.name}
                                                stroke={PALETTE[i]} strokeWidth={2.5} dot={{ r:3 }} activeDot={{ r:5 }} />
                                        ))}
                                    </LineChart>
                                </ResponsiveContainer>
                            ) : (
                                <Box sx={{ height:240, display:'flex', alignItems:'center', justifyContent:'center', opacity:0.4 }}>
                                    <Typography sx={{ fontSize:'12px' }}>No trend data for this period</Typography>
                                </Box>
                            )}
                        </Paper>

                        {/* Card scheme mix */}
                        <Paper elevation={0} sx={{ border:'0.5px solid var(--color-border-tertiary)', borderRadius:'12px', p:2.5 }}>
                            <Typography sx={{ fontSize:'13px', fontWeight:700, mb:2 }}>Card Scheme Mix</Typography>
                            {schemeData.length > 0 ? (
                                <ResponsiveContainer width="100%" height={240}>
                                    <BarChart data={schemeData} layout="vertical">
                                        <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="var(--color-border-tertiary)" />
                                        <XAxis type="number" tickFormatter={(v) => `${v/1000}k`} tick={{ fontSize:11 }} tickLine={false} axisLine={false} />
                                        <YAxis dataKey="scheme" type="category" width={90} tick={{ fontSize:11 }} tickLine={false} axisLine={false} />
                                        <RTooltip content={<CustomTooltip />} />
                                        <Legend iconType="square" iconSize={8} wrapperStyle={{ fontSize:11 }} />
                                        {merchants.map((m, i) => (
                                            <Bar key={m.merchantId} dataKey={m.name} fill={PALETTE[i]} radius={[0,3,3,0]} maxBarSize={18} />
                                        ))}
                                    </BarChart>
                                </ResponsiveContainer>
                            ) : (
                                <Box sx={{ height:240, display:'flex', alignItems:'center', justifyContent:'center', opacity:0.4 }}>
                                    <Typography sx={{ fontSize:'12px' }}>No scheme data</Typography>
                                </Box>
                            )}
                        </Paper>

                        {/* Card type mix */}
                        <Paper elevation={0} sx={{ border:'0.5px solid var(--color-border-tertiary)', borderRadius:'12px', p:2.5 }}>
                            <Typography sx={{ fontSize:'13px', fontWeight:700, mb:2 }}>
                                <Stack direction="row" spacing={0.75} alignItems="center" component="span">
                                    <CreditCard size={13} />
                                    <span>Credit vs Debit vs Prepaid</span>
                                </Stack>
                            </Typography>
                            {cardTypeData.length > 0 ? (
                                <ResponsiveContainer width="100%" height={240}>
                                    <BarChart data={cardTypeData}>
                                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--color-border-tertiary)" />
                                        <XAxis dataKey="type" tick={{ fontSize:11 }} tickLine={false} axisLine={false} />
                                        <YAxis tickFormatter={(v) => `${v/1000}k`} tick={{ fontSize:11 }} tickLine={false} axisLine={false} />
                                        <RTooltip content={<CustomTooltip />} />
                                        <Legend iconType="square" iconSize={8} wrapperStyle={{ fontSize:11 }} />
                                        {merchants.map((m, i) => (
                                            <Bar key={m.merchantId} dataKey={m.name} fill={PALETTE[i]} radius={[3,3,0,0]} maxBarSize={24} />
                                        ))}
                                    </BarChart>
                                </ResponsiveContainer>
                            ) : (
                                <Box sx={{ height:240, display:'flex', alignItems:'center', justifyContent:'center', opacity:0.4 }}>
                                    <Typography sx={{ fontSize:'12px' }}>No card type data</Typography>
                                </Box>
                            )}
                        </Paper>

                        {/* Winner summary */}
                        <Paper elevation={0} sx={{ border:'0.5px solid var(--color-border-tertiary)', borderRadius:'12px', p:2.5 }}>
                            <Typography sx={{ fontSize:'13px', fontWeight:700, mb:2 }}>
                                <Stack direction="row" spacing={0.75} alignItems="center" component="span">
                                    <Award size={13} />
                                    <span>Performance Summary</span>
                                </Stack>
                            </Typography>
                            <Stack spacing={1.5}>
                                {[
                                    { key:'totalVolume', label:'Highest Volume', icon: DollarSign },
                                    { key:'totalTxns',   label:'Most Transactions', icon: Hash },
                                    { key:'avgTxnValue', label:'Best Avg Ticket', icon: TrendingUp },
                                    { key:'totalMargin', label:'Highest Margin', icon: Target },
                                ].map(({ key, label, icon: Icon }) => {
                                    const idx = leaderIdx(key);
                                    const winner = merchants[idx];
                                    const d = deltas[key];
                                    if (!winner) return null;
                                    return (
                                        <Stack key={key} direction="row" justifyContent="space-between" alignItems="center"
                                            sx={{ p:1.25, borderRadius:'8px', bgcolor: PALETTE_BG[idx], border:`0.5px solid ${PALETTE[idx]}30` }}>
                                            <Stack direction="row" spacing={1} alignItems="center">
                                                <Icon size={13} color={PALETTE[idx]} />
                                                <Box>
                                                    <Typography sx={{ fontSize:'10px', color:'var(--color-text-secondary)', fontWeight:600, textTransform:'uppercase', letterSpacing:'0.04em' }}>{label}</Typography>
                                                    <Typography sx={{ fontSize:'13px', fontWeight:700, color: PALETTE[idx] }}>{winner.name}</Typography>
                                                </Box>
                                            </Stack>
                                            {d > 0 && (
                                                <Chip label={`+${d.toFixed(1)}% ahead`} size="small"
                                                    sx={{ bgcolor: PALETTE[idx], color:'white', fontWeight:700, fontSize:'10px', height:20, borderRadius:'5px' }} />
                                            )}
                                        </Stack>
                                    );
                                })}
                            </Stack>
                        </Paper>
                    </Box>

                </Stack>
            )}
        </Box>
    );
};

export default MerchantComparison;
