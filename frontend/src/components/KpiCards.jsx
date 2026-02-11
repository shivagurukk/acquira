import React from 'react';
import { Box, Typography, Stack } from '@mui/material';
import { TrendingUp, TrendingDown } from 'lucide-react';

// ─── Mini Sparkline SVG ──────────────────────────────────────────────
const MiniSparkline = ({ data = [], color = '#6366f1', height = 32, width = 80 }) => {
    if (!data || data.length < 2) return null;
    const max = Math.max(...data);
    const min = Math.min(...data);
    const range = max - min || 1;
    const points = data.map((v, i) => {
        const x = (i / (data.length - 1)) * width;
        const y = height - ((v - min) / range) * (height - 4) - 2;
        return `${x},${y}`;
    }).join(' ');

    return (
        <svg width={width} height={height} style={{ overflow: 'visible', opacity: 0.7 }}>
            <defs>
                <linearGradient id={`spark-${color.replace('#', '')}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity="0.3" />
                    <stop offset="100%" stopColor={color} stopOpacity="0" />
                </linearGradient>
            </defs>
            <polygon
                points={`0,${height} ${points} ${width},${height}`}
                fill={`url(#spark-${color.replace('#', '')})`}
            />
            <polyline points={points} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
    );
};

// ─── Single KPI Card ─────────────────────────────────────────────────
const KpiCard = ({ title, value, subtitle, trend, trendLabel, icon: Icon, color = '#6366f1', sparkData }) => {
    const isPositive = trend > 0;
    const isNeutral = !trend || trend === 0;
    const trendColor = isNeutral ? '#94a3b8' : isPositive ? '#10b981' : '#ef4444';

    return (
        <Box sx={{
            flex: 1, minWidth: 200, maxWidth: 320,
            p: 2.5, borderRadius: '16px',
            bgcolor: 'white', border: '1px solid #e2e8f0',
            position: 'relative', overflow: 'hidden',
            transition: 'all 0.2s ease',
            '&:hover': { borderColor: color, boxShadow: `0 4px 20px ${color}15`, transform: 'translateY(-1px)' },
        }}>
            {/* Sparkline BG */}
            {sparkData && sparkData.length > 1 && (
                <Box sx={{ position: 'absolute', bottom: 0, right: 0, opacity: 0.5 }}>
                    <MiniSparkline data={sparkData} color={color} height={40} width={120} />
                </Box>
            )}

            <Stack spacing={1.5} sx={{ position: 'relative', zIndex: 1 }}>
                {/* Header */}
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Typography variant="caption" fontWeight={600} color="#94a3b8" sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', fontSize: '0.65rem' }}>
                        {title}
                    </Typography>
                    {Icon && (
                        <Box sx={{
                            width: 30, height: 30, borderRadius: '8px', display: 'flex',
                            alignItems: 'center', justifyContent: 'center',
                            bgcolor: `${color}12`,
                        }}>
                            <Icon size={15} color={color} />
                        </Box>
                    )}
                </Stack>

                {/* Value */}
                <Typography variant="h5" fontWeight={800} color="#0f172a" sx={{ letterSpacing: '-0.02em', lineHeight: 1 }}>
                    {value}
                </Typography>

                {/* Bottom: Trend + Subtitle */}
                <Stack direction="row" alignItems="center" spacing={1}>
                    {!isNeutral && (
                        <Stack direction="row" alignItems="center" spacing={0.3}
                            sx={{
                                px: 0.75, py: 0.25, borderRadius: '6px', fontSize: '11px', fontWeight: 700,
                                bgcolor: isPositive ? 'rgba(16, 185, 129, 0.08)' : 'rgba(239, 68, 68, 0.08)',
                                color: trendColor,
                            }}
                        >
                            {isPositive ? <TrendingUp size={12} /> : <TrendingDown size={12} />}
                            <span>{Math.abs(trend).toFixed(1)}%</span>
                        </Stack>
                    )}
                    {(subtitle || trendLabel) && (
                        <Typography variant="caption" color="#94a3b8" sx={{ fontSize: '0.65rem' }}>
                            {trendLabel || subtitle}
                        </Typography>
                    )}
                </Stack>
            </Stack>
        </Box>
    );
};

// ─── KPI Row Container ───────────────────────────────────────────────
const KpiCards = ({ cards = [] }) => {
    if (!cards || cards.length === 0) return null;

    return (
        <Box sx={{
            display: 'flex', gap: 2, mb: 3, flexWrap: 'wrap',
        }}>
            {cards.map((card, i) => (
                <KpiCard key={i} {...card} />
            ))}
        </Box>
    );
};

export default KpiCards;
export { KpiCard, MiniSparkline };
