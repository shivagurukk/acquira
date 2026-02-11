import React, { useState } from 'react';
import { Box, Typography, Stack, IconButton, Tooltip, Collapse, TextField, Chip } from '@mui/material';
import { Download, Filter, RefreshCw, Calendar, X, ChevronDown } from 'lucide-react';
import ActiveFilterChips from './ActiveFilterChips';

// ─── Premium Date Presets ────────────────────────────────────────────
const DATE_PRESETS = [
    { key: 'TODAY', label: 'Today' },
    { key: 'MONTH', label: 'This Month' },
    { key: 'LAST_MONTH', label: 'Last Month' },
    { key: 'YEAR', label: 'This Year' },
    { key: 'PY', label: 'Prev Year' },
    { key: 'CUSTOM', label: 'Custom' },
];

const computeDateRange = (preset) => {
    const now = new Date();
    const fmt = (d) => d.toISOString().split('T')[0];

    switch (preset) {
        case 'TODAY': return { startDate: fmt(now), endDate: fmt(now) };
        case 'MONTH': return { startDate: fmt(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmt(now) };
        case 'LAST_MONTH': return {
            startDate: fmt(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
            endDate: fmt(new Date(now.getFullYear(), now.getMonth(), 0))
        };
        case 'YEAR': return { startDate: fmt(new Date(now.getFullYear(), 0, 1)), endDate: fmt(now) };
        case 'PY': return {
            startDate: fmt(new Date(now.getFullYear() - 1, 0, 1)),
            endDate: fmt(new Date(now.getFullYear() - 1, 11, 31))
        };
        default: return {};
    }
};

// ─── Component ───────────────────────────────────────────────────────
const PremiumReportHeader = ({
    title,
    subtitle,
    icon: Icon,
    onExport,
    onRunReport,
    onFilterChange,
    loading = false,
    showFilters,
    onToggleFilters,
    filters = {},
    hideDatePresets = false,
    children, // slot for extra controls (e.g. year picker on heatmap)
}) => {
    const [activePreset, setActivePreset] = useState(filters?.datePreset || 'MONTH');
    const [showCustom, setShowCustom] = useState(false);

    const handlePreset = (preset) => {
        setActivePreset(preset);
        setShowCustom(preset === 'CUSTOM');
        if (preset !== 'CUSTOM' && onFilterChange) {
            const range = computeDateRange(preset);
            onFilterChange({ ...range, datePreset: preset });
        } else if (preset === 'CUSTOM' && onFilterChange) {
            onFilterChange({ datePreset: 'CUSTOM' });
        }
    };

    const handleRemoveFilter = (key, value) => {
        if (!filters || !onFilterChange) return;
        if (key === 'ALL') {
            onFilterChange({
                startDate: '', endDate: '', openDateStart: '', openDateEnd: '',
                partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
                sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
                merchantName: '', midList: [], sidList: [],
            });
            return;
        }
        if (['startDate', 'endDate', 'openDateStart', 'openDateEnd', 'merchantName'].includes(key)) {
            onFilterChange({ [key]: '' });
        } else if (Array.isArray(filters[key])) {
            onFilterChange({ [key]: filters[key].filter(item => item !== value) });
        }
    };

    const activeCount = [
        ...(filters.partnerList || []), ...(filters.mccList || []), ...(filters.industryList || []),
        ...(filters.rmList || []), ...(filters.teamLeaderList || []), ...(filters.sectorList || []),
        ...(filters.destinationList || []), ...(filters.schemeList || []), ...(filters.cardTypeList || []),
        ...(filters.channelList || []), ...(filters.midList || []), ...(filters.sidList || []),
    ].length + (filters.merchantName ? 1 : 0);

    return (
        <Box sx={{ mb: 3 }}>
            {/* ── Row 1: Title + Actions ── */}
            <Box sx={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start',
                flexWrap: 'wrap', gap: 2, mb: 2,
            }}>
                {/* Left: Title */}
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                    {Icon && (
                        <Box sx={{
                            width: 42, height: 42, borderRadius: '12px', display: 'flex',
                            alignItems: 'center', justifyContent: 'center',
                            background: 'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
                            boxShadow: '0 4px 12px rgba(99, 102, 241, 0.3)',
                        }}>
                            <Icon size={20} color="white" />
                        </Box>
                    )}
                    <Box>
                        <Typography variant="h5" fontWeight={800} color="#0f172a" sx={{ letterSpacing: '-0.02em', lineHeight: 1.2 }}>
                            {title}
                        </Typography>
                        {subtitle && (
                            <Typography variant="body2" color="#64748b" sx={{ mt: 0.25 }}>
                                {subtitle}
                            </Typography>
                        )}
                    </Box>
                </Box>

                {/* Right: Actions */}
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                    {/* Date Presets */}
                    {!hideDatePresets && (
                        <Box sx={{
                            display: 'flex', alignItems: 'center', gap: '2px',
                            bgcolor: '#f1f5f9', borderRadius: '10px', p: '3px',
                        }}>
                            {DATE_PRESETS.map(p => (
                                <Box
                                    key={p.key}
                                    onClick={() => handlePreset(p.key)}
                                    sx={{
                                        px: 1.5, py: 0.6, borderRadius: '8px', fontSize: '12px', fontWeight: 600,
                                        cursor: 'pointer', transition: 'all 0.15s ease',
                                        userSelect: 'none', whiteSpace: 'nowrap',
                                        ...(activePreset === p.key ? {
                                            bgcolor: 'white', color: '#0f172a',
                                            boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
                                        } : {
                                            bgcolor: 'transparent', color: '#64748b',
                                            '&:hover': { color: '#334155', bgcolor: 'rgba(255,255,255,0.5)' },
                                        }),
                                    }}
                                >
                                    {p.label}
                                </Box>
                            ))}
                        </Box>
                    )}

                    {/* Custom Date Inputs */}
                    <Collapse in={showCustom || activePreset === 'CUSTOM'} orientation="horizontal" unmountOnExit>
                        <Stack direction="row" spacing={1} alignItems="center" sx={{ pl: 0.5 }}>
                            <TextField
                                type="date" size="small" value={filters?.startDate || ''}
                                onChange={(e) => onFilterChange && onFilterChange({ startDate: e.target.value })}
                                sx={{ width: 145, '& .MuiOutlinedInput-root': { borderRadius: '8px', fontSize: '12px' } }}
                            />
                            <Typography variant="caption" color="#94a3b8" sx={{ px: 0.25 }}>→</Typography>
                            <TextField
                                type="date" size="small" value={filters?.endDate || ''}
                                onChange={(e) => onFilterChange && onFilterChange({ endDate: e.target.value })}
                                sx={{ width: 145, '& .MuiOutlinedInput-root': { borderRadius: '8px', fontSize: '12px' } }}
                            />
                        </Stack>
                    </Collapse>

                    {/* Extra Children (e.g. year picker) */}
                    {children}

                    {/* Filters Toggle */}
                    {onToggleFilters && (
                        <Tooltip title="Advanced Filters">
                            <Box
                                onClick={onToggleFilters}
                                sx={{
                                    display: 'flex', alignItems: 'center', gap: 0.75,
                                    px: 1.5, py: 0.75, borderRadius: '10px', cursor: 'pointer',
                                    fontSize: '13px', fontWeight: 600, transition: 'all 0.15s',
                                    ...(showFilters ? {
                                        bgcolor: '#eff6ff', color: '#3b82f6', border: '1px solid #bfdbfe',
                                    } : {
                                        bgcolor: '#f1f5f9', color: '#64748b', border: '1px solid transparent',
                                        '&:hover': { bgcolor: '#e2e8f0', color: '#334155' },
                                    }),
                                }}
                            >
                                <Filter size={15} />
                                Filters
                                {activeCount > 0 && (
                                    <Box sx={{
                                        ml: 0.5, minWidth: 18, height: 18, borderRadius: '9px',
                                        bgcolor: '#3b82f6', color: 'white', fontSize: '10px', fontWeight: 700,
                                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                                    }}>
                                        {activeCount}
                                    </Box>
                                )}
                            </Box>
                        </Tooltip>
                    )}

                    {/* Run Report */}
                    {onRunReport && (
                        <Box
                            onClick={() => !loading && onRunReport()}
                            sx={{
                                display: 'flex', alignItems: 'center', gap: 0.75,
                                px: 2, py: 0.75, borderRadius: '10px', cursor: loading ? 'not-allowed' : 'pointer',
                                fontSize: '13px', fontWeight: 700, transition: 'all 0.15s',
                                bgcolor: '#0f172a', color: 'white',
                                boxShadow: '0 2px 8px rgba(15, 23, 42, 0.2)',
                                opacity: loading ? 0.7 : 1,
                                '&:hover': { bgcolor: '#1e293b', boxShadow: '0 4px 12px rgba(15, 23, 42, 0.3)' },
                            }}
                        >
                            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
                            {loading ? 'Loading...' : 'Run Report'}
                        </Box>
                    )}

                    {/* Export */}
                    {onExport && (
                        <Tooltip title="Export CSV">
                            <Box
                                onClick={onExport}
                                sx={{
                                    display: 'flex', alignItems: 'center', gap: 0.75,
                                    px: 1.5, py: 0.75, borderRadius: '10px', cursor: 'pointer',
                                    fontSize: '13px', fontWeight: 600, transition: 'all 0.15s',
                                    bgcolor: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
                                    '&:hover': { bgcolor: '#e2e8f0', color: '#0f172a' },
                                }}
                            >
                                <Download size={14} />
                                Export
                            </Box>
                        </Tooltip>
                    )}
                </Stack>
            </Box>

            {/* ── Row 2: Active Filter Chips ── */}
            {filters && !loading && (
                <ActiveFilterChips filters={filters} onRemove={handleRemoveFilter} />
            )}
        </Box>
    );
};

export default PremiumReportHeader;
