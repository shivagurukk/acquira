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

// ─── Leaderboard chrome: solid tinted heading, animated border, top-3 spotlight ───
// Decorative only — collapses to a plain static card under prefers-reduced-motion.
//
// Colour model: every board shares one hue (--lb-c, LB_HUE below) so the page
// reads as one set of tables, not a colour wheel. That hue drives four depths —
// a deep heading bar (hue darkened toward navy, white type on top), a faint
// card fill, a zebra tint, and the row hairlines — so a board reads as one
// object rather than a white box with a coloured strip stapled on. The
// travelling border line is a conic gradient painted on the border-box beneath
// a padding-box fill, so the card must carry a transparent border.
const LEADERBOARD_CSS = `
@property --lbAngle { syntax: '<angle>'; inherits: false; initial-value: 0deg; }

@keyframes lbSweep  { to { --lbAngle: 360deg; } }
@keyframes lbSheen  { 0% { transform: translateX(-60%) skewX(-18deg); } 55%, 100% { transform: translateX(420%) skewX(-18deg); } }
@keyframes lbRowIn  { from { opacity: 0; transform: translateX(-8px); } to { opacity: 1; transform: none; } }
@keyframes lbShine  { 0% { transform: translateX(-130%); } 55%, 100% { transform: translateX(320%); } }
@keyframes lbMedal  { 0%, 100% { transform: scale(1);   box-shadow: 0 0 0 0 color-mix(in srgb, var(--lb-m) 50%, transparent); }
                      50%      { transform: scale(1.1); box-shadow: 0 0 0 5px color-mix(in srgb, var(--lb-m) 0%, transparent); } }

.lb-card {
  --lb-fill: color-mix(in srgb, var(--lb-c) 6%, var(--bg-card));
  --lb-line: color-mix(in srgb, var(--lb-c) 16%, transparent);
  position: relative;
  border: 1.5px solid transparent !important;
  box-shadow: 0 1px 2px color-mix(in srgb, var(--lb-c) 10%, transparent),
              0 8px 24px color-mix(in srgb, var(--lb-c) 12%, transparent);
  background:
    linear-gradient(var(--lb-fill), var(--lb-fill)) padding-box,
    conic-gradient(from var(--lbAngle),
      var(--border) 0deg,
      var(--border) 250deg,
      color-mix(in srgb, var(--lb-c) 45%, var(--border)) 300deg,
      var(--lb-c) 330deg,
      color-mix(in srgb, var(--lb-c) 45%, var(--border)) 350deg,
      var(--border) 360deg) border-box;
  animation: lbSweep 6s linear infinite;
}

/* Heading: the hue darkened toward navy so white type clears AA in both
   schemes, with a slow specular sheen travelling across it. */
.lb-head {
  position: relative;
  overflow: hidden;
  border-bottom: none !important;
  background: linear-gradient(135deg,
    color-mix(in srgb, var(--lb-c) 76%, #0A1426) 0%,
    color-mix(in srgb, var(--lb-c) 54%, #0A1426) 52%,
    color-mix(in srgb, var(--lb-c) 72%, #0A1426) 100%);
}
.lb-head::before {
  content: '';
  position: absolute; top: 0; bottom: 0; left: 0; width: 22%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.16), transparent);
  animation: lbSheen 7s ease-in-out infinite;
  pointer-events: none;
}
/* Bright hairline along the bottom edge of the bar. */
.lb-head::after {
  content: '';
  position: absolute; left: 0; right: 0; bottom: 0; height: 2px;
  background: linear-gradient(90deg,
    color-mix(in srgb, var(--lb-c) 70%, #fff),
    color-mix(in srgb, var(--lb-c) 30%, transparent) 72%, transparent);
}
.lb-title { color: #fff; position: relative; }
.lb-icon  { background: rgba(255, 255, 255, 0.16); border: 1px solid rgba(255, 255, 255, 0.24); }
.lb-dl    { color: rgba(255, 255, 255, 0.72) !important; }
.lb-dl:hover { color: #fff !important; background: rgba(255, 255, 255, 0.14) !important; }

/* Rows: hue-tinted hairlines + a whisper of zebra so ten lines stay scannable. */
.lb-row {
  position: relative;
  border-bottom: 1px solid var(--lb-line) !important;
  transition: background-color 140ms ease;
  animation: lbRowIn 340ms ease-out both;
  animation-delay: var(--lb-d, 0ms);
}
.lb-row:last-of-type { border-bottom: none !important; }
.lb-row:nth-of-type(even) { background: color-mix(in srgb, var(--lb-c) 4%, transparent); }
.lb-row:hover { background: color-mix(in srgb, var(--lb-c) 11%, transparent); }

.lb-row-top {
  overflow: hidden;
  background: linear-gradient(90deg,
    color-mix(in srgb, var(--lb-m) 26%, transparent) 0%,
    color-mix(in srgb, var(--lb-m) 8%,  transparent) 52%,
    transparent 100%) !important;
}
.lb-row-top:hover {
  background: linear-gradient(90deg,
    color-mix(in srgb, var(--lb-m) 34%, transparent) 0%,
    color-mix(in srgb, var(--lb-m) 12%, transparent) 52%,
    transparent 100%) !important;
}
.lb-row-top::before {
  content: '';
  position: absolute; top: 0; bottom: 0; left: 0; width: 34%;
  background: linear-gradient(90deg, transparent,
    color-mix(in srgb, var(--lb-m) 30%, transparent), transparent);
  animation: lbShine 3.8s ease-in-out infinite;
  animation-delay: var(--lb-d, 0ms);
  pointer-events: none;
}
.lb-row-top::after {
  content: '';
  position: absolute; left: 0; top: 0; bottom: 0; width: 3px;
  background: linear-gradient(180deg, var(--lb-m), color-mix(in srgb, var(--lb-m) 40%, transparent));
}
.lb-medal {
  color: #fff;
  background: linear-gradient(135deg, var(--lb-m), color-mix(in srgb, var(--lb-m) 58%, #000));
  animation: lbMedal 2.6s ease-in-out infinite;
  animation-delay: var(--lb-d, 0ms);
}
.lb-chip {
  background: color-mix(in srgb, var(--lb-c) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--lb-c) 22%, transparent);
}

@media (prefers-reduced-motion: reduce) {
  .lb-card, .lb-head::before, .lb-row, .lb-row-top::before, .lb-medal { animation: none !important; }
  .lb-card { border: 1.5px solid var(--border) !important;
             background: linear-gradient(var(--lb-fill), var(--lb-fill)) padding-box; }
}
`;

// One hue for every leaderboard on the page — the navy of the shared table header.
const LB_HUE = 'var(--cat-1, #3F63B0)';

// Gold / silver / bronze for ranks 1–3; every other row keeps the quiet chip.
const MEDALS = ['#C9931B', '#8592A6', '#A9662F'];

// ─── Reusable ranked-list card ───────────────────────────────────────
const LeaderboardCard = ({ title, icon: Icon, color = LB_HUE, rows, primaryKey, secondaryKey,
    valueKey, valueFmt, badgeKey, emptyLabel, onExport }) => (
    <Paper className="lb-card" style={{ '--lb-c': color }}
        sx={{ ...premiumTableWrapper, display: 'flex', flexDirection: 'column' }}>
        <Box className="lb-head" sx={{
            px: 2.5, py: 1.75,
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
            <Stack direction="row" spacing={1.2} alignItems="center" sx={{ position: 'relative' }}>
                {Icon && (
                    <Box className="lb-icon" sx={{
                        width: 30, height: 30, borderRadius: '8px', display: 'flex',
                        alignItems: 'center', justifyContent: 'center',
                    }}>
                        <Icon size={15} color="#fff" />
                    </Box>
                )}
                <Typography className="lb-title" fontWeight={700} fontSize="0.88rem"
                    sx={{ letterSpacing: '0.015em' }}>{title}</Typography>
            </Stack>
            {onExport && rows?.length > 0 && (
                <Tooltip title="Export CSV">
                    <IconButton className="lb-dl" size="small" onClick={onExport} sx={{ position: 'relative' }}>
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
                {rows.map((r, i) => {
                    const medal = r.rank <= 3 ? MEDALS[r.rank - 1] : null;
                    return (
                        <Box key={i}
                            className={`lb-row${medal ? ' lb-row-top' : ''}`}
                            style={{ '--lb-d': `${i * 70}ms`, ...(medal ? { '--lb-m': medal } : {}) }}
                            sx={{
                                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                                px: 2.5, py: 1.25, gap: 1.5,
                            }}>
                            <Stack direction="row" spacing={1.5} alignItems="center" sx={{ minWidth: 0, position: 'relative' }}>
                                <Box className={medal ? 'lb-medal' : 'lb-chip'} sx={{
                                    width: 22, height: 22, borderRadius: '6px', flexShrink: 0,
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                    fontSize: '0.68rem', fontWeight: 700,
                                    fontFamily: 'var(--font-mono)',
                                    color: medal ? undefined : T.textSec,
                                }}>
                                    {r.rank}
                                </Box>
                                <Box sx={{ minWidth: 0 }}>
                                    <Typography noWrap fontWeight={medal ? 700 : 600} fontSize="0.82rem" color={T.text}>
                                        {r[primaryKey] || '—'}
                                    </Typography>
                                    {secondaryKey && (
                                        <Typography noWrap fontSize="0.7rem" color={T.textMut} fontFamily="var(--font-mono)">
                                            {r[secondaryKey] || (r.merchantCount != null ? `${r.merchantCount} merchants` : '')}
                                        </Typography>
                                    )}
                                </Box>
                            </Stack>
                            <Stack alignItems="flex-end" spacing={0.3} sx={{ flexShrink: 0, position: 'relative' }}>
                                <Typography fontWeight={700} fontSize="0.85rem" color={T.text}
                                    sx={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums' }}>
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
                    );
                })}
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
            <style>{LEADERBOARD_CSS}</style>
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
                        title="Top 10 Merchants — Volume" icon={Sparkles} color={LB_HUE}
                        rows={data.topMerchantsByVolume} primaryKey="name" secondaryKey="mid"
                        valueKey="volume" valueFmt={fmt.currency}
                        onExport={() => exportToCSV(data.topMerchantsByVolume, 'top_merchants_by_volume')}
                    />
                    <LeaderboardCard
                        title="Top 10 Merchants — Net Margin" icon={TrendingUp} color={LB_HUE}
                        rows={data.topMerchantsByNetRevenue} primaryKey="name" secondaryKey="mid"
                        valueKey="netRevenue" valueFmt={fmt.currency}
                        onExport={() => exportToCSV(data.topMerchantsByNetRevenue, 'top_merchants_by_net_revenue')}
                    />
                    <LeaderboardCard
                        title="Top 10 Merchants — Transactions" icon={Receipt} color={LB_HUE}
                        rows={data.topMerchantsByTxns} primaryKey="name" secondaryKey="mid"
                        valueKey="txns" valueFmt={fmt.number}
                        onExport={() => exportToCSV(data.topMerchantsByTxns, 'top_merchants_by_txns')}
                    />
                    <LeaderboardCard
                        title="Top 10 RMs — Volume" icon={Users} color={LB_HUE}
                        rows={data.topRmsByVolume} primaryKey="name" secondaryKey="salesUserId"
                        valueKey="volume" valueFmt={fmt.currency}
                        onExport={() => exportToCSV(data.topRmsByVolume, 'top_rms_by_volume')}
                    />
                    <LeaderboardCard
                        title="Top 10 RMs — Net Margin" icon={Trophy} color={LB_HUE}
                        rows={data.topRmsByNetRevenue} primaryKey="name" secondaryKey="salesUserId"
                        valueKey="netRevenue" valueFmt={fmt.currency}
                        onExport={() => exportToCSV(data.topRmsByNetRevenue, 'top_rms_by_net_revenue')}
                    />
                    <LeaderboardCard
                        title="Top 10 MCC — Volume" icon={Layers} color={LB_HUE}
                        rows={data.topMccs} primaryKey="name" secondaryKey="mcc"
                        valueKey="volume" valueFmt={fmt.currency}
                        emptyLabel="No MCC-level data for this window (sum_daily_full not yet populated)."
                        onExport={() => exportToCSV(data.topMccs, 'top_mccs_by_volume')}
                    />
                    <LeaderboardCard
                        title="Top 10 New Merchants" icon={Sparkles} color={LB_HUE}
                        rows={data.topNewMerchants} primaryKey="name" secondaryKey="mid"
                        valueKey="volume" valueFmt={fmt.currency}
                        emptyLabel="No merchants onboarded in this window."
                        onExport={() => exportToCSV(data.topNewMerchants, 'top_new_merchants')}
                    />

                    {/* Movers — split up/down within one card */}
                    <Paper className="lb-card" style={{ '--lb-c': LB_HUE }}
                        sx={{ ...premiumTableWrapper, display: 'flex', flexDirection: 'column' }}>
                        <Box className="lb-head" sx={{ px: 2.5, py: 1.75 }}>
                            <Stack direction="row" spacing={1.2} alignItems="center">
                                <Box className="lb-icon" sx={{
                                    width: 30, height: 30, borderRadius: '8px', display: 'flex',
                                    alignItems: 'center', justifyContent: 'center',
                                }}>
                                    <TrendingUp size={15} color="#fff" />
                                </Box>
                                <Typography className="lb-title" fontWeight={700} fontSize="0.88rem">Top Movers</Typography>
                            </Stack>
                            {data?.priorFrom && (
                                <Typography fontSize="0.68rem" sx={{ mt: 0.4, position: 'relative', color: 'rgba(255,255,255,0.72)' }}>
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
