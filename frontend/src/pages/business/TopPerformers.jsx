import React, { useState, useEffect, useMemo } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import api from '../../api/axios';
import { Box, Paper, Typography, Stack, IconButton, Tooltip } from '@mui/material';
import { Trophy, TrendingUp, Users, Sparkles, Download, ArrowUpRight, ArrowDownRight, Receipt, Layers } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import SkeletonLoader from '../../components/SkeletonLoader';
import { exportToCSV } from '../../utils/exportUtils';
import { pageContainer, premiumTableWrapper } from '../../theme/dataGridStyles';

// ─── Local design tokens (matches Daily Merchant Dashboard / Attrition Report) ───
const T = {
    card:     'var(--bg-card, #ffffff)',
    subtle:   'var(--bg-subtle, #f8fafc)',
    border:   'var(--border, #e2e8f0)',
    borderLt: 'var(--border-light, #eef2f7)',
    text:     'var(--text, #0f172a)',
    textSec:  'var(--text-secondary, #475569)',
    textMut:  'var(--text-muted, #94a3b8)',
    brand:    'var(--brand, #2563eb)',
    brandAlt: 'var(--brand-alt, #3b82f6)',
    success:  'var(--success, #059669)',
    danger:   'var(--danger, #dc2626)',
    warning:  'var(--warning, #d97706)',
};

const currentMonthRange = () => {
    const now = new Date();
    const fmt = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    return { startDate: fmt(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmt(now) };
};

const emptyFilters = () => ({
    ...currentMonthRange(),
    datePreset: 'MONTH',
    partnerList: [], rmList: [], teamLeaderList: [], mccList: [], industryList: [], sectorList: [],
    midList: [], sidList: [], merchantName: '',
    destinationList: [], schemeList: [], cardTypeList: [], channelList: [], terminalTypeList: [],
    openDateStart: '', openDateEnd: '',
});

// ─── Reusable ranked-list card ───────────────────────────────────────
const LeaderboardCard = ({ title, icon: Icon, color = T.brand, rows, primaryKey, secondaryKey,
    valueKey, valueFmt, badgeKey, emptyLabel, onExport }) => (
    <Paper sx={{ ...premiumTableWrapper, display: 'flex', flexDirection: 'column' }}>
        <Box sx={{
            px: 2.5, py: 1.75, borderBottom: `1px solid ${T.borderLt}`,
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
            <Stack direction="row" spacing={1.2} alignItems="center">
                {Icon && (
                    <Box sx={{
                        width: 30, height: 30, borderRadius: '8px', display: 'flex',
                        alignItems: 'center', justifyContent: 'center',
                        background: `color-mix(in srgb, ${color} 10%, transparent)`,
                    }}>
                        <Icon size={15} color={color} />
                    </Box>
                )}
                <Typography fontWeight={700} fontSize="0.88rem" color={T.text}>{title}</Typography>
            </Stack>
            {onExport && rows?.length > 0 && (
                <Tooltip title="Export CSV">
                    <IconButton size="small" onClick={onExport} sx={{ color: T.textMut }}>
                        <Download size={14} />
                    </IconButton>
                </Tooltip>
            )}
        </Box>
        {(!rows || rows.length === 0) ? (
            <Box sx={{ p: 3 }}>
                <Typography variant="body2" color={T.textMut}>{emptyLabel || 'No data for this period.'}</Typography>
            </Box>
        ) : (
            <Box sx={{ flex: 1 }}>
                {rows.map((r, i) => (
                    <Box key={i} sx={{
                        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                        px: 2.5, py: 1.25, gap: 1.5,
                        borderBottom: i < rows.length - 1 ? `1px solid ${T.borderLt}` : 'none',
                    }}>
                        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ minWidth: 0 }}>
                            <Box sx={{
                                width: 22, height: 22, borderRadius: '6px', flexShrink: 0,
                                bgcolor: r.rank <= 3 ? `color-mix(in srgb, ${color} 14%, transparent)` : T.subtle,
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                fontSize: '0.68rem', fontWeight: 700,
                                color: r.rank <= 3 ? color : T.textSec,
                            }}>
                                {r.rank}
                            </Box>
                            <Box sx={{ minWidth: 0 }}>
                                <Typography noWrap fontWeight={600} fontSize="0.82rem" color={T.text}>
                                    {r[primaryKey] || '—'}
                                </Typography>
                                {secondaryKey && (
                                    <Typography noWrap fontSize="0.7rem" color={T.textMut} fontFamily="monospace">
                                        {r[secondaryKey] || (r.merchantCount != null ? `${r.merchantCount} merchants` : '')}
                                    </Typography>
                                )}
                            </Box>
                        </Stack>
                        <Stack alignItems="flex-end" spacing={0.3} sx={{ flexShrink: 0 }}>
                            <Typography fontWeight={700} fontSize="0.85rem" color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                                {valueFmt(r[valueKey])}
                            </Typography>
                            {badgeKey && r[badgeKey] != null && (
                                <Box sx={{
                                    display: 'inline-flex', alignItems: 'center', gap: 0.3,
                                    px: 0.7, py: 0.1, borderRadius: '6px', fontSize: '0.62rem', fontWeight: 700,
                                    bgcolor: r[badgeKey] >= 0 ? 'var(--success-bg, #d1fae5)' : 'var(--danger-bg, #fee2e2)',
                                    color: r[badgeKey] >= 0 ? T.success : T.danger,
                                }}>
                                    {r[badgeKey] >= 0 ? <ArrowUpRight size={10} /> : <ArrowDownRight size={10} />}
                                    {Math.abs(r[badgeKey]).toFixed(1)}%
                                </Box>
                            )}
                        </Stack>
                    </Box>
                ))}
            </Box>
        )}
    </Paper>
);

const TopPerformers = () => {
    const { currencySymbol, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);

    const [filters, setFilters] = useState(emptyFilters());
    const [showFilters, setShowFilters] = useState(false);
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);

    // Re-running the report (header button, filter apply, date preset) can leave two
    // requests in flight. Without a guard the slower one wins whenever it lands last,
    // so the board could show results for a window the user had already moved off.
    const reqSeq = React.useRef(0);

    const fetchData = async (explicitFilters) => {
        const f = explicitFilters || filters;
        const seq = ++reqSeq.current;
        setLoading(true);
        try {
            const params = new URLSearchParams();
            if (f.startDate) params.set('from', f.startDate);
            if (f.endDate) params.set('to', f.endDate);
            const body = { ...f, startDate: undefined, endDate: undefined, datePreset: undefined };
            const res = await api.post(`/business/top-performers-filtered?${params.toString()}`, body);
            if (seq === reqSeq.current) setData(res.data);
        } catch (e) {
            console.error('Failed to fetch top performers', e);
        } finally {
            if (seq === reqSeq.current) setLoading(false);
        }
    };

    useEffect(() => { fetchData(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [tenantVersion]);

    const concentrationCards = data ? [
        {
            title: 'Total Volume', value: fmt.currency(data.concentration.totalVolume),
            icon: Sparkles, color: 'var(--brand, #2563eb)',
        },
        {
            title: 'Total Net Margin', value: fmt.currency(data.concentration.totalNetRevenue),
            icon: TrendingUp, color: 'var(--success, #059669)',
        },
        {
            title: 'Active Merchants', value: fmt.number(data.concentration.activeMerchantCount),
            icon: Users, color: 'var(--brand-alt, #3b82f6)',
        },
        {
            title: 'Top 10 Concentration', value: `${data.concentration.top10SharePct}%`,
            subtitle: 'of total volume from the top 10 merchants',
            icon: Trophy, color: 'var(--warning, #d97706)',
        },
    ] : [];

    const movers = data?.topMovers;
    const grainNote = data?.grain === 'insight'
        ? 'Card-level filters active — showing cardholder-currency volume; net margin approximated as MSF.'
        : null;

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Top Performers"
                subtitle={data ? `${data.from} → ${data.to} · settlement volume unless card filters are applied` : 'Loading…'}
                icon={Trophy}
                onExport={() => data && exportToCSV(data.topMerchantsByVolume, 'top_merchants_by_volume')}
                onRunReport={() => fetchData()} loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(s => !s)}
                filters={filters}
                onFilterChange={(patch) => setFilters(prev => ({ ...prev, ...patch }))}
                onApplyAfterDatePreset={(next) => fetchData(next)}
            />

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={() => fetchData()}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            {grainNote && !loading && (
                <Paper sx={{
                    p: 1.5, borderRadius: 'var(--radius-lg, 14px)',
                    border: '1px solid var(--warning-border, #fde68a)', bgcolor: 'var(--warning-bg, #fffbeb)',
                }}>
                    <Typography variant="caption" fontWeight={600} color="var(--warning-text, #92400e)">
                        {grainNote}
                    </Typography>
                </Paper>
            )}

            {loading ? (
                <SkeletonLoader variant="kpi-row" count={4} />
            ) : (
                <KpiCards cards={concentrationCards} />
            )}

            {loading ? (
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 2.5 }}>
                    {Array.from({ length: 6 }).map((_, i) => <SkeletonLoader key={i} variant="table" rows={5} cols={2} />)}
                </Box>
            ) : (
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 2.5 }}>
                    <LeaderboardCard
                        title="Top 10 Merchants — Volume" icon={Sparkles} color="var(--brand, #2563eb)"
                        rows={data.topMerchantsByVolume} primaryKey="name" secondaryKey="mid"
                        valueKey="volume" valueFmt={fmt.currency}
                        onExport={() => exportToCSV(data.topMerchantsByVolume, 'top_merchants_by_volume')}
                    />
                    <LeaderboardCard
                        title="Top 10 Merchants — Net Margin" icon={TrendingUp} color="var(--success, #059669)"
                        rows={data.topMerchantsByNetRevenue} primaryKey="name" secondaryKey="mid"
                        valueKey="netRevenue" valueFmt={fmt.currency}
                        onExport={() => exportToCSV(data.topMerchantsByNetRevenue, 'top_merchants_by_net_revenue')}
                    />
                    <LeaderboardCard
                        title="Top 10 Merchants — Transactions" icon={Receipt} color="var(--warning, #d97706)"
                        rows={data.topMerchantsByTxns} primaryKey="name" secondaryKey="mid"
                        valueKey="txns" valueFmt={fmt.number}
                        onExport={() => exportToCSV(data.topMerchantsByTxns, 'top_merchants_by_txns')}
                    />
                    <LeaderboardCard
                        title="Top 10 RMs — Volume" icon={Users} color="var(--brand-alt, #3b82f6)"
                        rows={data.topRmsByVolume} primaryKey="salesUserId" secondaryKey="salesEmail"
                        valueKey="volume" valueFmt={fmt.currency}
                        onExport={() => exportToCSV(data.topRmsByVolume, 'top_rms_by_volume')}
                    />
                    <LeaderboardCard
                        title="Top 10 RMs — Net Margin" icon={Trophy} color="var(--warning, #d97706)"
                        rows={data.topRmsByNetRevenue} primaryKey="salesUserId" secondaryKey="salesEmail"
                        valueKey="netRevenue" valueFmt={fmt.currency}
                        onExport={() => exportToCSV(data.topRmsByNetRevenue, 'top_rms_by_net_revenue')}
                    />
                    <LeaderboardCard
                        title="Top 10 MCC — Volume" icon={Layers} color="var(--brand, #2563eb)"
                        rows={data.topMccs} primaryKey="name" secondaryKey="mcc"
                        valueKey="volume" valueFmt={fmt.currency}
                        emptyLabel="No MCC-level data for this window (sum_daily_full not yet populated)."
                        onExport={() => exportToCSV(data.topMccs, 'top_mccs_by_volume')}
                    />
                    <LeaderboardCard
                        title="Top 10 New Merchants" icon={Sparkles} color="var(--brand-alt, #3b82f6)"
                        rows={data.topNewMerchants} primaryKey="name" secondaryKey="mid"
                        valueKey="volume" valueFmt={fmt.currency}
                        emptyLabel="No merchants onboarded in this window."
                        onExport={() => exportToCSV(data.topNewMerchants, 'top_new_merchants')}
                    />

                    {/* Movers — split up/down within one card */}
                    <Paper sx={{ ...premiumTableWrapper, display: 'flex', flexDirection: 'column' }}>
                        <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${T.borderLt}` }}>
                            <Stack direction="row" spacing={1.2} alignItems="center">
                                <Box sx={{
                                    width: 30, height: 30, borderRadius: '8px', display: 'flex',
                                    alignItems: 'center', justifyContent: 'center',
                                    background: 'color-mix(in srgb, var(--brand, #2563eb) 10%, transparent)',
                                }}>
                                    <TrendingUp size={15} color={T.brand} />
                                </Box>
                                <Typography fontWeight={700} fontSize="0.88rem" color={T.text}>Top Movers</Typography>
                            </Stack>
                            {data?.priorFrom && (
                                <Typography fontSize="0.68rem" color={T.textMut} sx={{ mt: 0.4 }}>
                                    vs {data.priorFrom} → {data.priorTo}
                                </Typography>
                            )}
                        </Box>
                        {!movers ? (
                            <Box sx={{ p: 3 }}>
                                <Typography variant="body2" color={T.textMut}>
                                    No prior-period data available for comparison yet.
                                </Typography>
                            </Box>
                        ) : (
                            <Box>
                                <Box sx={{ px: 2, py: 1, bgcolor: T.subtle }}>
                                    <Stack direction="row" spacing={0.5} alignItems="center">
                                        <TrendingUp size={12} color={T.success} />
                                        <Typography fontSize="0.68rem" fontWeight={700} color={T.success}>SURGING</Typography>
                                    </Stack>
                                </Box>
                                {movers.up.length === 0 ? (
                                    <Box sx={{ p: 2 }}><Typography variant="caption" color={T.textMut}>None above the noise floor.</Typography></Box>
                                ) : movers.up.map((r, i) => (
                                    <Box key={i} sx={{ px: 2, py: 1, borderBottom: `1px solid ${T.borderLt}` }}>
                                        <Typography noWrap fontSize="0.78rem" fontWeight={600} color={T.text}>{r.name}</Typography>
                                        <Typography fontSize="0.68rem" color={T.textMut}>{fmt.currency(r.volume)}</Typography>
                                    </Box>
                                ))}
                            </Box>
                        )}
                    </Paper>
                </Box>
            )}
        </Box>
    );
};

export default TopPerformers;
