import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
    Box, Card, Typography, Button, LinearProgress, Chip, Stack,
    useTheme, Paper, CircularProgress, Avatar, Container, Grid
} from '@mui/material';
import {
    PlayArrow, CheckCircle, Error as ErrorIcon, Refresh,
    AccessTime, Assessment, Bolt, AutoGraph
} from '@mui/icons-material';
import { FileText, Zap, Clock, FileCheck } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';

const GlassCard = ({ children, sx, ...props }) => (
    <Card sx={{
        background: 'rgba(255, 255, 255, 0.92)', backdropFilter: 'blur(20px)',
        borderRadius: 4, boxShadow: '0 8px 32px 0 rgba(31, 38, 135, 0.12)',
        border: '1px solid rgba(226, 232, 240, 0.8)', overflow: 'visible', ...sx
    }} {...props}>{children}</Card>
);

const StatBadge = ({ icon, label, value, color }) => (
    <Box sx={{
        display: 'flex', alignItems: 'center', gap: 2, p: 2, borderRadius: 3,
        bgcolor: 'white', boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
        border: '1px solid #e2e8f0', transition: 'transform 0.2s',
        '&:hover': { transform: 'translateY(-2px)', boxShadow: '0 6px 16px rgba(0,0,0,0.06)' }
    }}>
        <Avatar variant="rounded" sx={{ bgcolor: `${color}.50`, color: `${color}.main`, width: 48, height: 48 }}>{icon}</Avatar>
        <Box>
            <Typography variant="h6" fontWeight="800" lineHeight={1.2}>{value}</Typography>
            <Typography variant="caption" color="text.secondary" fontWeight="600" sx={{ textTransform: 'uppercase', letterSpacing: 0.5 }}>{label}</Typography>
        </Box>
    </Box>
);

const PremiumButton = ({ children, onClick, color = 'primary', startIcon, ...props }) => (
    <Button variant="contained" onClick={onClick} startIcon={startIcon}
        sx={{
            py: 1.8, px: 4, borderRadius: 3, fontWeight: 700,
            backgroundImage: color === 'warning'
                ? 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)'
                : 'linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)',
            boxShadow: color === 'warning'
                ? '0 10px 20px -5px rgba(245, 158, 11, 0.4)'
                : '0 10px 20px -5px rgba(99, 102, 241, 0.4)',
            textTransform: 'none', fontSize: '1rem',
            '&:hover': {
                backgroundImage: color === 'warning'
                    ? 'linear-gradient(135deg, #d97706 0%, #b45309 100%)'
                    : 'linear-gradient(135deg, #4f46e5 0%, #4338ca 100%)',
            }
        }} {...props}>{children}</Button>
);

const MerchantReportManager = () => {
    const theme = useTheme();
    const [merchants, setMerchants] = useState([]);
    const [status, setStatus] = useState('idle');
    const [progress, setProgress] = useState({ current: 0, total: 0, success: 0, failed: 0 });
    const [logs, setLogs] = useState([]);
    const [existingReportCount, setExistingReportCount] = useState(0);
    const logsEndRef = useRef(null);

    useEffect(() => { if (logsEndRef.current) logsEndRef.current.scrollIntoView({ behavior: "smooth" }); }, [logs]);
    useEffect(() => { fetchMerchants(); }, []);

    const fetchMerchants = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const res = await fetch('/api/merchants', { headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId } });
            if (res.ok) {
                const data = await res.json();
                const list = data.content || data;
                setMerchants(list);
                setProgress(prev => ({ ...prev, total: list.length }));
            }
        } catch (error) { console.error("Failed to fetch merchants", error); }
    };

    const handleStartClick = async () => {
        setStatus('checking');
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/insights/check-status', { headers: { 'Authorization': `Bearer ${token}` } });
            if (res.ok) {
                const data = await res.json();
                if (data.exists) { setExistingReportCount(data.count); setStatus('confirming'); return; }
            }
        } catch (e) { console.error("Check status failed", e); }
        startBatch();
    };

    const startBatch = async () => {
        setStatus('running'); setLogs([]);
        setProgress({ current: 0, total: merchants.length, success: 0, failed: 0 });
        const token = localStorage.getItem('token');
        const tenantId = localStorage.getItem('tenantId');
        const headers = { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId };
        try {
            const res = await fetch('/api/business/insights/generate-all', {
                method: 'POST', headers
            });
            if (res.ok) {
                const result = await res.json();
                const jobId = result.jobId;
                if (!jobId) {
                    // Fallback: old synchronous response format
                    const generated = result.generated || 0;
                    const failed = result.failed || 0;
                    setProgress({ current: generated + failed, total: merchants.length, success: generated, failed });
                    if (result.errors?.length > 0) setLogs(result.errors);
                    if (generated > 0) setLogs(prev => [...prev, `✅ Generated ${generated} reports`, `📁 Saved to: ${result.folder || 'reports folder'}`]);
                    setStatus('completed');
                    return;
                }

                // Async pipeline: poll for status
                setLogs([`🚀 Batch started — Job: ${jobId}`, `📊 Processing ${result.totalMerchants} merchants...`]);

                const pollInterval = setInterval(async () => {
                    try {
                        const statusRes = await fetch(`/api/business/insights/batch-status/${jobId}`, { headers });
                        if (!statusRes.ok) return;
                        const st = await statusRes.json();

                        setProgress({
                            current: st.completed || 0,
                            total: st.totalMerchants || merchants.length,
                            success: st.succeeded || 0,
                            failed: st.failed || 0
                        });

                        // Update logs with latest errors
                        const newLogs = [`🚀 Batch started — Job: ${jobId}`, `📊 Processing ${st.totalMerchants} merchants...`];
                        if (st.avgRenderMs > 0) newLogs.push(`⚡ Avg render: ${st.avgRenderMs}ms/report`);
                        if (st.completed > 0) newLogs.push(`📈 Progress: ${st.completed}/${st.totalMerchants} (${st.progressPercent}%)`);
                        if (st.estimatedRemainingSeconds && typeof st.estimatedRemainingSeconds === 'number') {
                            newLogs.push(`⏱️ ETA: ${st.estimatedRemainingSeconds}s remaining`);
                        }
                        if (st.errors?.length > 0) {
                            st.errors.forEach(e => newLogs.push(`❌ ${e}`));
                        }

                        if (st.phase === 'COMPLETED' || st.phase === 'FAILED' || st.phase === 'CANCELLED') {
                            clearInterval(pollInterval);
                            if (st.succeeded > 0) newLogs.push(`✅ Generated ${st.succeeded} reports in ${st.totalSeconds?.toFixed(1) || '?'}s`);
                            if (st.failed > 0) newLogs.push(`⚠️ ${st.failed} reports failed`);
                            setLogs(newLogs);
                            setStatus('completed');
                        } else {
                            setLogs(newLogs);
                        }
                    } catch (pollErr) {
                        console.error('Poll error:', pollErr);
                    }
                }, 2000); // Poll every 2 seconds

            } else {
                const errorData = await res.json();
                setLogs([`❌ Batch failed: ${errorData.error || 'Unknown error'}`]);
                setProgress(prev => ({ ...prev, failed: merchants.length }));
                setStatus('completed');
            }
        } catch (err) {
            setLogs([`❌ Critical error: ${err.message}`]);
            setProgress(prev => ({ ...prev, failed: merchants.length }));
            setStatus('completed');
        }
    };

    const pct = progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : 0;
    const estimatedTime = (merchants.length * 1.5 / 60).toFixed(1);

    const kpis = useMemo(() => [
        { title: 'Merchants Ready', value: merchants.length.toString(), icon: Zap, color: '#6366f1' },
        { title: 'Est. Duration', value: `~${estimatedTime} min`, icon: Clock, color: '#f59e0b' },
        { title: 'Report Type', value: 'PDF Insight', icon: FileCheck, color: '#10b981' },
        ...(status === 'completed' ? [{ title: 'Success Rate', value: `${progress.total > 0 ? Math.round((progress.success / progress.total) * 100) : 0}%`, icon: FileText, color: progress.failed > 0 ? '#ef4444' : '#10b981', trend: progress.failed > 0 ? -(progress.failed / progress.total * 100) : 100 }] : []),
    ], [merchants.length, estimatedTime, status, progress]);

    return (
        <Box sx={{ p: 3, bgcolor: '#F8FAFC', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
            <PremiumReportHeader
                title="Merchant Report Manager" subtitle="Enterprise batch PDF generation system"
                icon={FileText} hideDatePresets
            />
            <KpiCards cards={kpis} />

            <Container maxWidth="lg" disableGutters sx={{ flex: 1 }}>
                <GlassCard>
                    <Box sx={{ p: 0, display: 'flex' }}>
                        <Box sx={{ width: 6, borderRadius: '16px 0 0 16px', bgcolor: status === 'running' ? 'warning.main' : status === 'completed' ? 'success.main' : 'primary.main', transition: 'background-color 0.5s' }} />
                        <Box sx={{ p: 5, width: '100%' }}>
                            <Box display="flex" justifyContent="space-between" alignItems="center" mb={4}>
                                <Box display="flex" alignItems="center" gap={2}>
                                    <Avatar sx={{ bgcolor: 'white', color: 'primary.main', boxShadow: '0 4px 12px rgba(0,0,0,0.1)' }}><Assessment /></Avatar>
                                    <Typography variant="h6" fontWeight="800" color="text.primary">Merchant Insights</Typography>
                                </Box>
                                <AnimatePresence mode="wait">
                                    {status !== 'idle' && (
                                        <motion.div initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }}>
                                            <Chip label={status.toUpperCase()} color={status === 'completed' ? 'success' : status === 'running' ? 'warning' : 'default'} sx={{ fontWeight: 'bold', borderRadius: 2 }} />
                                        </motion.div>
                                    )}
                                </AnimatePresence>
                            </Box>

                            <AnimatePresence mode="wait">
                                {status === 'idle' && (
                                    <motion.div key="idle" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, position: 'absolute' }} style={{ width: '100%' }}>
                                        <Grid container spacing={3} mb={6}>
                                            <Grid item xs={12} sm={4}><StatBadge icon={<Bolt />} label="Merchants Ready" value={merchants.length} color="primary" /></Grid>
                                            <Grid item xs={12} sm={4}><StatBadge icon={<AccessTime />} label="Est. Duration" value={`~${estimatedTime} min`} color="secondary" /></Grid>
                                            <Grid item xs={12} sm={4}><StatBadge icon={<AutoGraph />} label="Report Type" value="PDF Insight" color="info" /></Grid>
                                        </Grid>
                                        <Box textAlign="center" py={4}>
                                            <PremiumButton onClick={handleStartClick} startIcon={<PlayArrow />} size="large">Initialize Batch Process</PremiumButton>
                                            <Typography variant="body2" color="text.secondary" mt={2}>Generates individual reports for {merchants.length} active merchants</Typography>
                                        </Box>
                                    </motion.div>
                                )}

                                {(status === 'checking' || status === 'confirming') && (
                                    <motion.div key="check" initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -20 }} style={{ width: '100%', textAlign: 'center' }}>
                                        {status === 'checking' ? (
                                            <Box py={8}><CircularProgress size={60} thickness={4} /><Typography variant="h6" mt={3} color="text.secondary">Verifying existing artifacts...</Typography></Box>
                                        ) : (
                                            <Box py={4} maxWidth={500} mx="auto">
                                                <Avatar sx={{ width: 80, height: 80, bgcolor: 'warning.50', color: 'warning.main', mx: 'auto', mb: 3 }}><ErrorIcon sx={{ fontSize: 40 }} /></Avatar>
                                                <Typography variant="h5" fontWeight="800" gutterBottom>Artifacts Detected</Typography>
                                                <Typography color="text.secondary" mb={4}>Found <strong>{existingReportCount}</strong> existing reports. Running this batch will overwrite them.</Typography>
                                                <Stack direction="row" spacing={2} justifyContent="center">
                                                    <Button variant="outlined" color="inherit" onClick={() => setStatus('idle')} sx={{ borderRadius: 3, px: 4 }}>Cancel</Button>
                                                    <PremiumButton onClick={startBatch} color="warning" startIcon={<Refresh />}>Overwrite & Proceed</PremiumButton>
                                                </Stack>
                                            </Box>
                                        )}
                                    </motion.div>
                                )}

                                {(status === 'running' || status === 'completed') && (
                                    <motion.div key="running" initial={{ opacity: 0 }} animate={{ opacity: 1 }} style={{ width: '100%' }}>
                                        <Box mb={5}>
                                            <Box display="flex" justifyContent="space-between" mb={1}>
                                                <Typography variant="subtitle2" fontWeight="700" color="text.secondary">BATCH PROGRESS</Typography>
                                                <Typography variant="subtitle2" fontWeight="700" color="primary">{pct}%</Typography>
                                            </Box>
                                            <LinearProgress variant="determinate" value={pct} sx={{
                                                height: 14, borderRadius: 7, bgcolor: 'grey.100',
                                                '& .MuiLinearProgress-bar': { borderRadius: 7, background: status === 'completed' ? theme.palette.success.main : `linear-gradient(90deg, ${theme.palette.primary.main}, ${theme.palette.secondary.main})` }
                                            }} />
                                        </Box>
                                        <Grid container spacing={3} mb={4}>
                                            <Grid item xs={6}>
                                                <Paper elevation={0} sx={{ p: 2, bgcolor: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: 3, textAlign: 'center' }}>
                                                    <Typography variant="h4" fontWeight="800" color="success.main">{progress.success}</Typography>
                                                    <Typography variant="subtitle2" fontWeight="bold" color="success.dark">SUCCESSFUL</Typography>
                                                </Paper>
                                            </Grid>
                                            <Grid item xs={6}>
                                                <Paper elevation={0} sx={{ p: 2, bgcolor: '#fef2f2', border: '1px solid #fecaca', borderRadius: 3, textAlign: 'center' }}>
                                                    <Typography variant="h4" fontWeight="800" color="error.main">{progress.failed}</Typography>
                                                    <Typography variant="subtitle2" fontWeight="bold" color="error.dark">FAILED</Typography>
                                                </Paper>
                                            </Grid>
                                        </Grid>
                                        <Paper elevation={0} sx={{ bgcolor: '#0f172a', borderRadius: 3, overflow: 'hidden', boxShadow: 'inset 0 2px 10px rgba(0,0,0,0.5)' }}>
                                            <Box sx={{ px: 2, py: 1.5, bgcolor: '#1e293b', borderBottom: '1px solid #334155', display: 'flex', gap: 1 }}>
                                                <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#ef4444' }} />
                                                <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#f59e0b' }} />
                                                <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#22c55e' }} />
                                                <Typography sx={{ ml: 2, color: '#94a3b8', fontSize: 12, fontFamily: 'monospace' }}>batch_process.log</Typography>
                                            </Box>
                                            <Box sx={{ p: 2.5, maxHeight: 250, overflowY: 'auto', fontFamily: "'Fira Code', monospace", fontSize: '0.85rem' }}>
                                                <AnimatePresence>
                                                    {logs.map((log, i) => (
                                                        <motion.div key={i} initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }} transition={{ duration: 0.2 }}>
                                                            <Typography component="div" sx={{ color: log.includes('❌') ? '#f87171' : '#4ade80', mb: 0.5, display: 'flex', gap: 1.5 }}>
                                                                <span style={{ opacity: 0.5 }}>{new Date().toLocaleTimeString('en-US', { hour12: false })}</span>
                                                                <span>{log}</span>
                                                            </Typography>
                                                        </motion.div>
                                                    ))}
                                                    <div ref={logsEndRef} />
                                                </AnimatePresence>
                                            </Box>
                                        </Paper>
                                        {status === 'completed' && (
                                            <Box mt={4} textAlign="center">
                                                <Button onClick={() => setStatus('idle')} sx={{ color: 'text.secondary', fontWeight: 'bold' }}>Start New Batch</Button>
                                            </Box>
                                        )}
                                    </motion.div>
                                )}
                            </AnimatePresence>
                        </Box>
                    </Box>
                </GlassCard>
            </Container>
        </Box>
    );
};

export default MerchantReportManager;
