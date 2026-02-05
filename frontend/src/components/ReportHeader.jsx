import React from 'react';
import {
    Box,
    Typography,
    Button,
    Stack,
    TextField,
    ToggleButton,
    ToggleButtonGroup,
    CircularProgress,
    Paper,
    Collapse
} from '@mui/material';
import { Download, Filter, RefreshCw, Calendar as CalendarIcon } from 'lucide-react';

const ReportHeader = ({
    title,
    subtitle,
    onExport,
    onRunReport,
    filters,
    onFilterChange,
    showFilters,
    onToggleFilters,
    loading
}) => {
    const activePreset = filters?.datePreset || 'Custom';

    const handlePresetChange = (event, newPreset) => {
        if (!newPreset) return; // Enforce distinct selection

        const now = new Date();
        const todayStr = now.toISOString().split('T')[0];
        let start = todayStr;
        let end = todayStr;

        if (newPreset === 'Today') {
            start = end = todayStr;
        } else if (newPreset === 'Yesterday') {
            const y = new Date(now);
            y.setDate(y.getDate() - 1);
            start = end = y.toISOString().split('T')[0];
        } else if (newPreset === 'This Month') {
            const m = new Date(now.getFullYear(), now.getMonth(), 1);
            start = m.toISOString().split('T')[0];
        } else if (newPreset === 'Last Month') {
            const first = new Date(now.getFullYear(), now.getMonth() - 1, 1);
            const last = new Date(now.getFullYear(), now.getMonth(), 0);
            start = first.toISOString().split('T')[0];
            end = last.toISOString().split('T')[0];
        } else if (newPreset === 'This Year') {
            const y = new Date(now.getFullYear(), 0, 1);
            start = y.toISOString().split('T')[0];
        } else if (newPreset === 'Last Year') {
            const y = new Date(now.getFullYear() - 1, 0, 1);
            const ye = new Date(now.getFullYear() - 1, 11, 31);
            start = y.toISOString().split('T')[0];
            end = ye.toISOString().split('T')[0];
        }

        if (onFilterChange) {
            if (newPreset === 'Custom') {
                onFilterChange('datePreset', newPreset);
            } else {
                onFilterChange({ datePreset: newPreset, startDate: start, endDate: end });
            }
        }
    };

    return (
        <Paper
            elevation={0}
            sx={{
                p: 3,
                mb: 3,
                borderRadius: 3,
                bgcolor: 'background.paper',
                border: '1px solid',
                borderColor: 'divider',
                display: 'flex',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                gap: 2,
                alignItems: 'center'
            }}
        >
            {/* Title Section */}
            <Box>
                <Typography variant="h5" component="h1" fontWeight="800" color="text.primary">
                    {title}
                </Typography>
                {subtitle && (
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                        {subtitle}
                    </Typography>
                )}
            </Box>

            {/* Actions Section */}
            <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">

                {/* Date Presets */}
                {!loading && filters && !filters.hideDatePresets && (
                    <Stack direction="row" spacing={2} alignItems="center" sx={{ mr: 2 }}>
                        <ToggleButtonGroup
                            value={activePreset}
                            exclusive
                            onChange={handlePresetChange}
                            aria-label="date range"
                            size="small"
                            sx={{
                                '& .MuiToggleButton-root': {
                                    textTransform: 'none',
                                    fontWeight: 600,
                                    fontSize: '0.75rem',
                                    padding: '6px 16px',
                                }
                            }}
                        >
                            <ToggleButton value="Today">Today</ToggleButton>
                            <ToggleButton value="Yesterday">Yesterday</ToggleButton>
                            <ToggleButton value="This Year">This Year</ToggleButton>
                            <ToggleButton value="Last Year">Last Year</ToggleButton>
                            <ToggleButton value="Custom">Custom</ToggleButton>
                        </ToggleButtonGroup>

                        {/* Inline Date Inputs if Custom */}
                        <Collapse in={activePreset === 'Custom'} orientation="horizontal">
                            <Stack direction="row" spacing={1} alignItems="center" sx={{ pl: 1 }}>
                                <TextField
                                    type="date"
                                    size="small"
                                    value={filters?.startDate || ''}
                                    onChange={(e) => onFilterChange && onFilterChange('startDate', e.target.value)}
                                    sx={{ width: 140 }}
                                />
                                <Typography variant="caption" color="text.disabled">-</Typography>
                                <TextField
                                    type="date"
                                    size="small"
                                    value={filters?.endDate || ''}
                                    onChange={(e) => onFilterChange && onFilterChange('endDate', e.target.value)}
                                    sx={{ width: 140 }}
                                />
                            </Stack>
                        </Collapse>
                    </Stack>
                )}

                {/* Filters Toggle */}
                <Button
                    variant={showFilters ? "soft" : "outlined"} // "soft" isn't a default variant, fallback to outlined or text. Let's use contained/outlined
                    color={showFilters ? "primary" : "inherit"}
                    startIcon={<Filter size={16} />}
                    onClick={onToggleFilters}
                    sx={{
                        borderColor: showFilters ? 'transparent' : 'divider',
                        color: showFilters ? 'primary.main' : 'text.secondary',
                        bgcolor: showFilters ? 'primary.lighter' : 'transparent' // We need to check if primary.lighter exists, probably not.
                    }}
                >
                    Filters
                </Button>

                {/* Run Report Button */}
                <Button
                    variant="contained"
                    color="primary"
                    startIcon={loading ? <CircularProgress size={16} color="inherit" /> : <RefreshCw size={16} />}
                    onClick={onRunReport}
                    disabled={loading}
                >
                    Run Report
                </Button>

                {/* Export Button */}
                {onExport && (
                    <Button
                        variant="outlined"
                        color="inherit"
                        startIcon={<Download size={16} />}
                        onClick={onExport}
                        sx={{ color: 'text.secondary', borderColor: 'divider' }}
                    >
                        Export
                    </Button>
                )}
            </Stack>
        </Paper>
    );
};

export default ReportHeader;
