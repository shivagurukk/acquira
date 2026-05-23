import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, Chip, Stack } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { Lightbulb, Target, TrendingUp, Users } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const OpportunityIntelligence = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [errorMsg, setErrorMsg] = useState(null);

    useEffect(() => { fetchData(); }, []);

    const fetchData = async () => {
        setLoading(true);
        setErrorMsg(null);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId') || localStorage.getItem('defaultTenantId');
            const res = await fetch('/api/business/opportunity', {
                headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
            });
            if (res.ok) {
                const result = await res.json();
                setData(result.map((r, i) => ({ id: r.scoreId || r.id || i, ...r })));
            } else {
                // Previously this branch did nothing - a failed request just left
                // the screen blank, indistinguishable from "no opportunities".
                const text = await res.text().catch(() => '');
                setErrorMsg(`Failed to load opportunities (HTTP ${res.status}). ${text}`.trim());
                setData([]);
            }
        } catch (error) {
            console.error('Failed to load opportunities', error);
            setErrorMsg(`Failed to load opportunities: ${error.message}`);
            setData([]);
        }
        finally { setLoading(false); }
    };

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const highScore = data.filter(d => (d.score || 0) >= 80).length;
        const medScore = data.filter(d => (d.score || 0) >= 50 && (d.score || 0) < 80).length;
        const avgScore = data.length > 0 ? (data.reduce((s, d) => s + (d.score || 0), 0) / data.length).toFixed(1) : 0;
        return [
            { title: 'Total Opportunities', value: data.length.toLocaleString(), icon: Users, color: '#6366f1' },
            { title: 'High Score (80+)', value: highScore.toLocaleString(), icon: TrendingUp, color: '#10b981' },
            { title: 'Medium Score (50–79)', value: medScore.toLocaleString(), icon: Target, color: '#f59e0b' },
            { title: 'Average Score', value: avgScore, icon: Lightbulb, color: '#3b82f6' },
        ];
    }, [data]);

    const getScoreColor = (score) => {
        if (score >= 80) return { bg: '#dcfce7', color: '#166534', label: 'High' };
        if (score >= 50) return { bg: '#fef3c7', color: '#92400e', label: 'Medium' };
        return { bg: '#fee2e2', color: '#991b1b', label: 'Low' };
    };

    const columns = [
        {
            field: 'merchantId', headerName: 'MERCHANT ID', flex: 1, minWidth: 140,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: '"Roboto Mono", monospace', fontSize: '13px', color: '#475569', bgcolor: '#f1f5f9', px: 1, py: 0.5, borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                    {params.value}
                </Typography>
            )
        },
        {
            field: 'score', headerName: 'SCORE', type: 'number', width: 130, align: 'center', headerAlign: 'center',
            renderCell: (params) => {
                const s = getScoreColor(params.value || 0);
                return (
                    <Chip
                        label={`${params.value || 0} — ${s.label}`}
                        size="small"
                        sx={{ fontWeight: 700, bgcolor: s.bg, color: s.color, border: 'none', fontSize: '12px', minWidth: 90 }}
                    />
                );
            }
        },
        {
            field: 'reasonTags', headerName: 'REASON TAGS', flex: 2, minWidth: 250,
            renderCell: (params) => {
                if (!params.value) return <Typography variant="body2" color="#94a3b8">—</Typography>;
                const tags = String(params.value).split(',').map(t => t.trim()).filter(Boolean);
                return (
                    <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                        {tags.map((tag, i) => (
                            <Chip key={i} label={tag} size="small" variant="outlined"
                                sx={{ fontSize: '11px', fontWeight: 600, borderColor: '#e2e8f0', color: '#475569' }} />
                        ))}
                    </Stack>
                );
            }
        },
        {
            field: 'calcDate', headerName: 'CALC DATE', width: 130,
            renderCell: (params) => (
                <Typography variant="body2" color="#64748b" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {params.value || '—'}
                </Typography>
            )
        },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Opportunity Intelligence" subtitle="Merchant growth potential and upsell scoring"
                icon={Lightbulb}
                onExport={() => exportToCSV(data, 'opportunity_intelligence')}
                onRunReport={fetchData}
                loading={loading}
                hideDatePresets
            />
            <KpiCards cards={kpis} />
            {errorMsg && (
                <Paper elevation={0} sx={{ p: 2, mb: 2, borderRadius: 2, bgcolor: '#fef2f2', border: '1px solid #fecaca' }}>
                    <Typography variant="body2" fontWeight="600" color="#991b1b">Could not load opportunity scores</Typography>
                    <Typography variant="caption" color="#7f1d1d">{errorMsg}</Typography>
                </Paper>
            )}
            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={data} columns={columns} loading={loading} rowHeight={55}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    initialState={{ pagination: { paginationModel: { pageSize: 25 } }, sorting: { sortModel: [{ field: 'score', sort: 'desc' }] } }}
                    pageSizeOptions={[25, 50, 100]}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default OpportunityIntelligence;
