import React, { useState } from 'react';
import { Box, Typography, Stack, Tooltip, Collapse, TextField } from '@mui/material';
import { Download, Filter, RefreshCw, ChevronDown } from 'lucide-react';
import ActiveFilterChips from './ActiveFilterChips';

const DATE_PRESETS = [
    { key: 'TODAY', label: 'Today' },
    { key: 'MONTH', label: 'This month' },
    { key: 'LAST_MONTH', label: 'Last month' },
    { key: 'YEAR', label: 'This year' },
    { key: 'PY', label: 'Prev year' },
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

const PremiumReportHeader = ({
    title, subtitle, icon: Icon,
    onExport, onRunReport, onFilterChange,
    loading = false, showFilters, onToggleFilters,
    filters = {}, hideDatePresets = false, children,
}) => {
    const [activePreset, setActivePreset] = useState(filters?.datePreset || 'MONTH');

    const handlePreset = (preset) => {
        setActivePreset(preset);
        if (preset !== 'CUSTOM' && onFilterChange) {
            onFilterChange({ ...computeDateRange(preset), datePreset: preset });
        } else if (onFilterChange) {
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
        <Box sx={{ mb: 2.5 }}>
            {/* ── Row 1: Title + Action buttons ── */}
            <Box sx={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                flexWrap: 'wrap', gap: 1.5, mb: 1.5,
            }}>
                {/* Left: Icon + Title */}
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                    {Icon && (
                        <Box sx={{
                            width: 36, height: 36, borderRadius: 'var(--radius-md, 8px)', display: 'flex',
                            alignItems: 'center', justifyContent: 'center',
                            bgcolor: 'var(--brand-light, #eff6ff)',
                        }}>
                            <Icon size={17} style={{ color: 'var(--brand, #3b82f6)' }} />
                        </Box>
                    )}
                    <Box>
                        <Typography sx={{
                            fontSize: '1.15rem', fontWeight: 700, letterSpacing: '-0.02em',
                            lineHeight: 1.2, color: 'var(--text, #0f172a)',
                        }}>
                            {title}
                        </Typography>
                        {subtitle && (
                            <Typography sx={{ fontSize: '0.8rem', mt: 0.2, color: 'var(--text-secondary, #64748b)' }}>
                                {subtitle}
                            </Typography>
                        )}
                    </Box>
                </Box>

                {/* Right: Action buttons only (compact) */}
                <Stack direction="row" spacing={0.75} alignItems="center">
                    {/* Filters Toggle */}
                    {onToggleFilters && (
                        <Box onClick={onToggleFilters} sx={{
                            display: 'flex', alignItems: 'center', gap: 0.5,
                            px: 1.2, py: 0.6, borderRadius: 'var(--radius-md, 8px)', cursor: 'pointer',
                            fontSize: '12px', fontWeight: 600, transition: 'all 0.15s',
                            ...(showFilters ? {
                                bgcolor: 'var(--brand-light, #eff6ff)', color: 'var(--brand, #3b82f6)',
                                border: '1px solid #bfdbfe',
                            } : {
                                bgcolor: 'var(--bg-card, #fff)', color: 'var(--text-secondary, #64748b)',
                                border: '1px solid var(--border, #e2e8f0)',
                                '&:hover': { borderColor: 'var(--brand, #3b82f6)', color: 'var(--text, #334155)' },
                            }),
                        }}>
                            <Filter size={13} />
                            Filters
                            {activeCount > 0 && (
                                <Box sx={{
                                    ml: 0.3, minWidth: 16, height: 16, borderRadius: '8px',
                                    bgcolor: 'var(--brand, #3b82f6)', color: 'white', fontSize: '9px', fontWeight: 700,
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                }}>{activeCount}</Box>
                            )}
                        </Box>
                    )}

                    {/* Run Report */}
                    {onRunReport && (
                        <Box onClick={() => !loading && onRunReport()} sx={{
                            display: 'flex', alignItems: 'center', gap: 0.5,
                            px: 1.5, py: 0.6, borderRadius: 'var(--radius-md, 8px)',
                            cursor: loading ? 'not-allowed' : 'pointer',
                            fontSize: '12px', fontWeight: 600, transition: 'all 0.15s',
                            bgcolor: 'var(--brand, #3b82f6)', color: 'white',
                            opacity: loading ? 0.7 : 1,
                            '&:hover': { bgcolor: 'var(--brand-dark, #2563eb)' },
                        }}>
                            <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
                            {loading ? 'Loading...' : 'Run report'}
                        </Box>
                    )}

                    {/* Export */}
                    {onExport && (
                        <Tooltip title="Export CSV">
                            <Box onClick={onExport} sx={{
                                display: 'flex', alignItems: 'center', gap: 0.5,
                                px: 1.2, py: 0.6, borderRadius: 'var(--radius-md, 8px)', cursor: 'pointer',
                                fontSize: '12px', fontWeight: 600, transition: 'all 0.15s',
                                bgcolor: 'var(--bg-card, #fff)', color: 'var(--text-secondary, #475569)',
                                border: '1px solid var(--border, #e2e8f0)',
                                '&:hover': { borderColor: 'var(--brand, #3b82f6)', color: 'var(--text, #0f172a)' },
                            }}>
                                <Download size={13} />
                                Export
                            </Box>
                        </Tooltip>
                    )}
                </Stack>
            </Box>

            {/* ── Row 2: Date presets + custom dates (separated from title for breathing room) ── */}
            {!hideDatePresets && (
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5, flexWrap: 'wrap' }}>
                    <Box sx={{
                        display: 'flex', alignItems: 'center', gap: '2px',
                        bgcolor: 'var(--bg-card, #fff)', borderRadius: 'var(--radius-md, 8px)',
                        border: '1px solid var(--border, #e2e8f0)', p: '3px',
                    }}>
                        {DATE_PRESETS.map(p => (
                            <Box key={p.key} onClick={() => handlePreset(p.key)} sx={{
                                px: 1.2, py: 0.45, borderRadius: 'var(--radius-sm, 6px)',
                                fontSize: '11px', fontWeight: 600, cursor: 'pointer',
                                transition: 'all 0.12s ease', userSelect: 'none', whiteSpace: 'nowrap',
                                ...(activePreset === p.key ? {
                                    bgcolor: 'var(--brand, #3b82f6)', color: '#fff',
                                } : {
                                    bgcolor: 'transparent', color: 'var(--text-secondary, #64748b)',
                                    '&:hover': { color: 'var(--text, #334155)', bgcolor: 'var(--border-light, #f1f5f9)' },
                                }),
                            }}>
                                {p.label}
                            </Box>
                        ))}
                    </Box>

                    {/* Custom Date Inputs */}
                    <Collapse in={activePreset === 'CUSTOM'} orientation="horizontal" unmountOnExit>
                        <Stack direction="row" spacing={0.75} alignItems="center">
                            <TextField type="date" size="small" value={filters?.startDate || ''}
                                onChange={(e) => onFilterChange && onFilterChange({ startDate: e.target.value })}
                                sx={{ width: 140, '& .MuiOutlinedInput-root': { borderRadius: '6px', fontSize: '11px', height: 30 } }}
                            />
                            <Typography sx={{ fontSize: '11px', color: 'var(--text-secondary, #94a3b8)' }}>to</Typography>
                            <TextField type="date" size="small" value={filters?.endDate || ''}
                                onChange={(e) => onFilterChange && onFilterChange({ endDate: e.target.value })}
                                sx={{ width: 140, '& .MuiOutlinedInput-root': { borderRadius: '6px', fontSize: '11px', height: 30 } }}
                            />
                        </Stack>
                    </Collapse>

                    {children}
                </Box>
            )}

            {/* ── Row 3: Active Filter Chips ── */}
            {filters && !loading && (
                <ActiveFilterChips filters={filters} onRemove={handleRemoveFilter} />
            )}
        </Box>
    );
};

export default PremiumReportHeader;
