import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import {
    Box, Card, Typography, Button, LinearProgress, Chip, Stack,
    useTheme, Paper, CircularProgress, Avatar, Container, Grid,
    FormControlLabel, Switch, Dialog, DialogContent
} from '@mui/material';
import {
    FileText, Zap, Clock, FileCheck, Building2, AlertTriangle, Shield,
    // Aliased to the former @mui/icons-material names so usage below is unchanged.
    Play as PlayArrow, CheckCircle2 as CheckCircle, XCircle as ErrorIcon,
    RefreshCw as Refresh, Clock as AccessTime, BarChart3 as Assessment,
    Zap as Bolt, TrendingUp as AutoGraph,
} from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import { PulseMark } from '../../components/Loaders';
import KpiCards from '../../components/KpiCards';
import api, { UPLOAD_TIMEOUT, isTimeoutError } from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';

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
        bgcolor: 'var(--bg-card)', boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
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
                : 'linear-gradient(135deg, var(--projected) 0%, #4f46e5 100%)',
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

const TenantConfirmDialog = ({ open, onClose, onConfirm, activeTenant, tenants, merchantCount, scopeText }) => {
    const theme = useTheme();
    return (
        <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth
            PaperProps={{ sx: { borderRadius: 4, overflow: 'hidden', boxShadow: '0 25px 60px rgba(0,0,0,0.25)' } }}
        >
            <Box sx={{
                background: 'linear-gradient(135deg, #4f46e5 0%, var(--projected) 50%, var(--projected) 100%)',
                px: 3.5, py: 3, display: 'flex', alignItems: 'center', gap: 2
            }}>
                <Avatar sx={{ bgcolor: 'rgba(255,255,255,0.2)', width: 52, height: 52, backdropFilter: 'blur(10px)' }}>
                    <Shield size={24} color="white" />
                </Avatar>
                <Box>
                    <Typography variant="h5" fontWeight="800" color="white" lineHeight={1.2}>
                        Confirm Report Generation
                    </Typography>
                    <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.75)', mt: 0.5 }}>
                        Reports are generated per tenant — please verify the active organization
                    </Typography>
                </Box>
            </Box>
            <DialogContent sx={{ px: 3.5, py: 3 }}>
                <Paper elevation={0} sx={{ p: 2.5, borderRadius: 3, border: '2px solid var(--chart-5)', bgcolor: '#eef2ff', display: 'flex', alignItems: 'center', gap: 2 }}>
                    <Avatar variant="rounded" sx={{ bgcolor: '#4f46e5', width: 56, height: 56 }}>
                        <Building2 size={26} color="white" />
                    </Avatar>
                    <Box flex={1}>
                        <Typography variant="h6" fontWeight="800" color="#312e81">{activeTenant?.bankName || 'Unknown'}</Typography>
                        <Typography variant="body2" color="text.secondary">
                            {[activeTenant?.bankShortCode, activeTenant?.country, activeTenant?.baseCurrency].filter(Boolean).join(' · ')}
                        </Typography>
                    </Box>
                    <Chip label="ACTIVE" size="small" sx={{ bgcolor: '#4f46e5', color: 'white', fontWeight: 800, fontSize: 11, letterSpacing: 0.5 }} />
                </Paper>
                {tenants?.length > 1 && (
                    <Box sx={{ mt: 2.5, p: 2, borderRadius: 2.5, bgcolor: '#fffbeb', border: '1px solid #fde68a', display: 'flex', alignItems: 'flex-start', gap: 1.5 }}>
                        <AlertTriangle size={18} color="#b45309" style={{ marginTop: 1, flexShrink: 0 }} />
                        <Box>
                            <Typography variant="body2" fontWeight="700" color="#92400e">You have access to {tenants.length} organizations</Typography>
                            <Typography variant="caption" color="#a16207">
                                Reports will <strong>only</strong> include merchants belonging to "{activeTenant?.bankName}".
                            </Typography>
                        </Box>
                    </Box>
                )}
                <Box sx={{ mt: 2.5, p: 2, borderRadius: 2.5, bgcolor: '#f0fdf4', border: '1px solid #bbf7d0', display: 'flex', alignItems: 'center', gap: 1.5 }}>
                    <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: '#22c55e', flexShrink: 0 }} />
                    <Typography variant="body2" fontWeight="600" color="#166534">
                        {scopeText || `${merchantCount} merchants found`} · PDFs will be generated under "{activeTenant?.bankName || 'tenant'}"
                    </Typography>
                </Box>
            </DialogContent>
            <Box sx={{ px: 3.5, py: 2.5, bgcolor: '#f8fafc', borderTop: '1px solid #e2e8f0', display: 'flex', justifyContent: 'flex-end', gap: 2 }}>
                <Button variant="outlined" color="inherit" onClick={onClose} sx={{ borderRadius: 3, px: 4, textTransform: 'none', fontWeight: 600 }}>Cancel</Button>
                <PremiumButton onClick={onConfirm} startIcon={<PlayArrow />}>
                    Generate for {activeTenant?.bankShortCode || 'Tenant'}
                </PremiumButton>
            </Box>
        </Dialog>
    );
};

const POLL_INTERVAL = 2000;

const MerchantReportManager = () => {
    const theme = useTheme();
    const { activeTenant, activeTenantId, tenants } = useAuth();
    const [merchants, setMerchants] = useState([]);
    const [status, setStatus] = useState('idle');
    const [showTenantConfirm, setShowTenantConfirm] = useState(false);
    const [progress, setProgress] = useState({ current: 0, total: 0, success: 0, failed: 0 });
    const [logs, setLogs] = useState([]);
    const [existingReportCount, setExistingReportCount] = useState(0);
    const [sendEmail, setSendEmail] = useState(false);
    const [sendS3, setSendS3] = useState(false);
    const [generatedReports, setGeneratedReports] = useState([]);
    // Generation scope: ALL (whole tenant) | ONE (single MID) | FILE (CSV/TXT of MIDs)
    const [scope, setScope] = useState('ALL');
    const [midInput, setMidInput] = useState('');
    const [midFile, setMidFile] = useState(null);
    const fileInputRef = useRef(null);
    const logsEndRef = useRef(null);
    const pollRef = useRef(null);
    const jobIdRef = useRef(null);

    useEffect(() => { if (logsEndRef.current) logsEndRef.current.scrollIntoView({ behavior: 'smooth' }); }, [logs]);
    useEffect(() => { fetchMerchants(); return () => { if (pollRef.current) clearInterval(pollRef.current); }; }, []);
    useEffect(() => { fetchMerchants(); }, [activeTenantId]);

    const startPolling = useCallback((jobId) => {
        if (pollRef.current) clearInterval(pollRef.current);
        jobIdRef.current = jobId;
        const poll = async () => {
            try {
                const res = await api.get(`/business/insights/batch-status/${jobId}`);
                const st = res.data;
                const completed = st.completed || 0;
                const total = st.totalMerchants || merchants.length;
                const succeeded = st.succeeded || 0;
                const failed = st.failed || 0;
                setProgress({ current: completed, total, success: succeeded, failed });
                const newLogs = [`🚀 Batch Job: ${jobId}`, `📊 Processing ${total} merchants...`];
                if (st.avgRenderMs > 0) newLogs.push(`⚡ Avg render: ${st.avgRenderMs}ms/report`);
                if (completed > 0) newLogs.push(`📈 Progress: ${completed}/${total} (${st.progressPercent || Math.round(completed / total * 100)}%)`);
                if (st.estimatedRemainingMs > 0) newLogs.push(`⏱️ ETA: ${(st.estimatedRemainingMs / 1000).toFixed(0)}s remaining`);
                if (st.errors?.length > 0) st.errors.forEach(e => newLogs.push(`❌ ${e}`));
                const phase = (st.phase || st.status || '').toUpperCase();
                if (phase === 'COMPLETED' || phase === 'FAILED' || phase === 'CANCELLED') {
                    if (succeeded > 0) newLogs.push(`✅ Generated ${succeeded} reports in ${(st.totalSeconds || 0).toFixed(1)}s`);
                    if (failed > 0) newLogs.push(`⚠️ ${failed} reports failed`);
                    setLogs(newLogs); setStatus('completed');
                    clearInterval(pollRef.current); pollRef.current = null;
                    fetchGeneratedReports();
                } else { setLogs(newLogs); setStatus('running'); }
            } catch (err) { console.error('Poll error:', err); }
        };
        poll();
        pollRef.current = setInterval(poll, POLL_INTERVAL);
    }, [merchants.length]);

    const fetchMerchants = async () => {
        try {
            const res = await api.get('/merchants?page=0&size=10000');
            const list = res.data.content || res.data;
            setMerchants(list);
            setProgress(prev => ({ ...prev, total: list.length }));
        } catch (error) { console.error('Failed to fetch merchants', error); }
    };

    const fetchGeneratedReports = async () => {
        try {
            const res = await api.get('/business/insights/list-reports');
            setGeneratedReports(res.data?.reports || []);
        } catch (e) { console.error('Failed to fetch report list', e); }
    };

    const formatFileSize = (bytes) => {
        if (!bytes || bytes === 0) return '0 B';
        const k = 1024, sizes = ['B', 'KB', 'MB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    };

    const handleDownloadAll = async () => {
        try {
            const res = await api.get('/business/insights/download-all-reports', { responseType: 'blob' });
            const url = window.URL.createObjectURL(new Blob([res.data], { type: 'application/zip' }));
            const link = document.createElement('a');
            link.href = url; link.download = `Merchant_Reports_${activeTenant?.bankShortCode || 'ALL'}.zip`;
            document.body.appendChild(link); link.click(); document.body.removeChild(link);
            window.URL.revokeObjectURL(url);
        } catch (e) { console.error('Download all failed:', e); }
    };

    const handleDownloadSingle = async (downloadUrl) => {
        try {
            const res = await api.get(downloadUrl.replace(/^\/api/, ''), { responseType: 'blob' });
            const filename = (res.headers['content-disposition'] || '').match(/filename="?([^"]+)"?/)?.[1] || 'report.pdf';
            const url = window.URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
            const link = document.createElement('a');
            link.href = url; link.download = filename;
            document.body.appendChild(link); link.click(); document.body.removeChild(link);
            window.URL.revokeObjectURL(url);
        } catch (e) { console.error('Download failed:', e); }
    };

    const handleStartClick = () => {
        // Validate scope-specific input before opening the tenant confirm dialog.
        if (scope === 'ONE' && !midInput.trim()) return;
        if (scope === 'FILE' && !midFile) return;
        setShowTenantConfirm(true);
    };

    const handleTenantConfirmed = async () => {
        setShowTenantConfirm(false); setStatus('checking');
        try {
            const res = await api.get('/business/insights/check-status');
            if (res.data?.exists) { setExistingReportCount(res.data.count); setStatus('confirming'); return; }
        } catch (e) { console.error('Check status failed', e); }
        startBatch();
    };

    const startBatch = async () => {
        setStatus('running'); setLogs([]);
        setProgress({ current: 0, total: merchants.length, success: 0, failed: 0 });
        try {
            // Unified multipart call to /generate-by-mid:
            //   ALL  → scope=ALL (every merchant in the tenant)
            //   ONE  → mid=<bank MID>
            //   FILE → file=<CSV/TXT of MIDs>
            const form = new FormData();
            form.append('sendEmail', String(sendEmail));
            form.append('sendS3', String(sendS3));
            if (scope === 'ALL') {
                form.append('scope', 'ALL');
            } else if (scope === 'ONE') {
                form.append('mid', midInput.trim());
            } else if (scope === 'FILE') {
                form.append('file', midFile);
            }

            // scope=ALL enumerates every merchant in the tenant before it can
            // answer with a jobId — comfortably past 60s on a large book.
            const res = await api.post('/business/insights/generate-by-mid', form, { timeout: UPLOAD_TIMEOUT });
            const result = res.data;
            const jobId = result.jobId;
            if (!jobId) {
                if (result.status === 'PDF_MODULE_NOT_LOADED') {
                    setLogs(['⚠️ PDF module not loaded.', '💡 Add acquira-pdf dependency to acquira-core.']); setStatus('completed'); return;
                }
                if (result.status === 'PDF_ENGINE_NOT_READY') {
                    setLogs(['⚠️ PDF engine not ready.', '💡 Run: mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=install']); setStatus('completed'); return;
                }
                const generated = result.generated || 0, failed = result.failed || 0;
                setProgress({ current: generated + failed, total: merchants.length, success: generated, failed });
                setLogs([`✅ Generated ${generated} reports`]); setStatus('completed'); return;
            }
            const startLogs = [
                `🏛️ Tenant: ${activeTenant?.bankName || 'Unknown'}`,
                `🚀 Batch started — Job: ${jobId}`,
                `📊 Processing ${result.totalMerchants} merchants...`,
                `📦 Mode: ${!sendEmail && !sendS3 ? 'Local Only' : !sendEmail && sendS3 ? 'S3 Upload Only' : sendEmail && !sendS3 ? 'Email Only' : 'Email + S3 Upload'}`,
            ];
            if (typeof result.matchedMidCount === 'number') {
                startLogs.push(`🎯 Matched ${result.matchedMidCount}/${result.requestedMidCount} MID(s)`);
            }
            if (result.unmatchedMids?.length > 0) {
                startLogs.push(`⚠️ Unmatched MIDs (skipped): ${result.unmatchedMids.join(', ')}`);
            }
            setLogs(startLogs);
            startPolling(jobId);
        } catch (err) {
            // The batch may well have started — say so rather than implying it died.
            const msg = isTimeoutError(err)
                ? 'The server did not respond in time. The batch may still be running — check Batch Monitoring before starting it again.'
                : (err?.response?.data?.message || err.message);
            setLogs([`❌ ${msg}`]);
            setStatus('completed');
        }
    };

    const pct = progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : 0;
    const estimatedTime = (merchants.length * 1.5 / 60).toFixed(1);

    const kpis = useMemo(() => [
        { title: 'Merchants Ready', value: merchants.length.toString(), icon: Zap, color: 'var(--projected)' },
        { title: 'Est. Duration', value: `~${estimatedTime} min`, icon: Clock, color: '#f59e0b' },
        { title: 'Report Type', value: 'PDF Insight', icon: FileCheck, color: '#10b981' },
        ...(status === 'completed' ? [{ title: 'Success Rate', value: `${progress.total > 0 ? Math.round((progress.success / progress.total) * 100) : 0}%`, icon: FileText, color: progress.failed > 0 ? '#ef4444' : '#10b981' }] : []),
    ], [merchants.length, estimatedTime, status, progress]);

    return (
        <Box sx={{ p: 3, bgcolor: '#F8FAFC', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
            <PremiumReportHeader title="Merchant Report Manager" subtitle="Enterprise batch PDF generation system" icon={FileText} hideDatePresets />

            <Box sx={{ mb: 2, px: 2, py: 1.5, borderRadius: 3, bgcolor: '#eef2ff', border: '1px solid var(--chart-5)', display: 'flex', alignItems: 'center', gap: 2 }}>
                <Building2 size={18} color="#4f46e5" />
                <Typography variant="body2" fontWeight="700" color="#312e81">Generating for: {activeTenant?.bankName || 'Unknown Tenant'}</Typography>
                <Chip label={activeTenant?.bankShortCode || '?'} size="small" sx={{ bgcolor: '#4f46e5', color: 'white', fontWeight: 700, fontSize: 11, ml: 'auto' }} />
                {activeTenant?.baseCurrency && <Chip label={activeTenant.baseCurrency} size="small" variant="outlined" sx={{ fontWeight: 600, fontSize: 11 }} />}
            </Box>

            <KpiCards cards={kpis} />

            <TenantConfirmDialog open={showTenantConfirm} onClose={() => setShowTenantConfirm(false)} onConfirm={handleTenantConfirmed} activeTenant={activeTenant} tenants={tenants} merchantCount={merchants.length} scopeText={scope === 'ALL' ? `${merchants.length} merchants found` : scope === 'ONE' ? `MID ${midInput.trim()}` : midFile ? `MIDs from ${midFile.name}` : 'selected MIDs'} />

            <Container maxWidth="lg" disableGutters sx={{ flex: 1 }}>
                <GlassCard>
                    <Box sx={{ p: 0, display: 'flex' }}>
                        <Box sx={{ width: 6, borderRadius: '16px 0 0 16px', bgcolor: status === 'running' ? 'warning.main' : status === 'completed' ? 'success.main' : 'primary.main', transition: 'background-color 0.5s' }} />
                        <Box sx={{ p: 5, width: '100%' }}>
                            <Box display="flex" justifyContent="space-between" alignItems="center" mb={4}>
                                <Box display="flex" alignItems="center" gap={2}>
                                    <Avatar sx={{ bgcolor: 'var(--bg-card)', color: 'primary.main', boxShadow: '0 4px 12px rgba(0,0,0,0.1)' }}><Assessment /></Avatar>
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
                                {/* ── IDLE STATE ── */}
                                {status === 'idle' && (
                                    <motion.div key="idle" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, position: 'absolute' }} style={{ width: '100%' }}>
                                        <Grid container spacing={3} mb={6}>
                                            <Grid item xs={12} sm={4}><StatBadge icon={<Bolt />} label="Merchants Ready" value={merchants.length} color="primary" /></Grid>
                                            <Grid item xs={12} sm={4}><StatBadge icon={<AccessTime />} label="Est. Duration" value={`~${estimatedTime} min`} color="secondary" /></Grid>
                                            <Grid item xs={12} sm={4}><StatBadge icon={<AutoGraph />} label="Report Type" value="PDF Insight" color="info" /></Grid>
                                        </Grid>

                                        <Box textAlign="center" py={3}>
                                            {/* ── Generation scope ── */}
                                            <Typography variant="subtitle2" fontWeight="700" color="text.secondary" mb={2}
                                                sx={{ textTransform: 'uppercase', letterSpacing: 0.5, fontSize: 11 }}>
                                                Who to generate for
                                            </Typography>
                                            <Box sx={{ display: 'flex', justifyContent: 'center', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>
                                                {[
                                                    { key: 'ALL', title: 'All Merchants', desc: `Every merchant in ${activeTenant?.bankShortCode || 'tenant'}` },
                                                    { key: 'ONE', title: 'Single MID', desc: 'One merchant by MID' },
                                                    { key: 'FILE', title: 'Upload MID File', desc: 'CSV/TXT list of MIDs' },
                                                ].map(opt => (
                                                    <Box key={opt.key} onClick={() => setScope(opt.key)} sx={{
                                                        px: 2.5, py: 1.5, borderRadius: 3, cursor: 'pointer', minWidth: 180, userSelect: 'none', textAlign: 'left', transition: 'all 0.2s',
                                                        border: `2px solid ${scope === opt.key ? 'var(--projected)' : '#e2e8f0'}`,
                                                        background: scope === opt.key ? '#eef2ff' : '#f8fafc',
                                                        '&:hover': { borderColor: 'var(--projected)', background: '#eef2ff' },
                                                    }}>
                                                        <Typography fontWeight="700" fontSize={13} color={scope === opt.key ? '#312e81' : 'text.secondary'}>{opt.title}</Typography>
                                                        <Typography fontSize={11} color="text.secondary">{opt.desc}</Typography>
                                                    </Box>
                                                ))}
                                            </Box>

                                            {scope === 'ONE' && (
                                                <Box sx={{ maxWidth: 360, mx: 'auto', mb: 2.5 }}>
                                                    <input
                                                        value={midInput}
                                                        onChange={(e) => setMidInput(e.target.value)}
                                                        placeholder="Enter merchant MID (e.g. 400000287650000)"
                                                        style={{ width: '100%', padding: '12px 14px', borderRadius: 10, border: '1px solid #cbd5e1', fontSize: 14, outline: 'none', boxSizing: 'border-box' }}
                                                    />
                                                </Box>
                                            )}

                                            {scope === 'FILE' && (
                                                <Box sx={{ maxWidth: 460, mx: 'auto', mb: 2.5 }}>
                                                    <input ref={fileInputRef} type="file" accept=".csv,.txt,.tsv" hidden
                                                        onChange={(e) => setMidFile(e.target.files?.[0] || null)} />
                                                    <Button variant="outlined" onClick={() => fileInputRef.current?.click()}
                                                        sx={{ borderRadius: 3, textTransform: 'none', fontWeight: 600 }}>
                                                        {midFile ? 'Change file' : 'Choose CSV / TXT file'}
                                                    </Button>
                                                    {midFile && (
                                                        <Chip label={midFile.name} onDelete={() => { setMidFile(null); if (fileInputRef.current) fileInputRef.current.value = ''; }} sx={{ ml: 1.5, fontWeight: 600 }} />
                                                    )}
                                                    <Typography fontSize={11} color="text.secondary" mt={1}>
                                                        One MID per line, or a column headed “MID”. Excel not supported — export to CSV.
                                                    </Typography>
                                                </Box>
                                            )}

                                            {/* ── Delivery Options label ── */}
                                            <Typography variant="subtitle2" fontWeight="700" color="text.secondary" mb={2}
                                                sx={{ textTransform: 'uppercase', letterSpacing: 0.5, fontSize: 11 }}>
                                                Delivery Options
                                            </Typography>

                                            {/* ── Two toggle cards ── */}
                                            <Box sx={{ display: 'flex', justifyContent: 'center', gap: 2, mb: 2.5, flexWrap: 'wrap' }}>

                                                {/* Email toggle */}
                                                <Box onClick={() => setSendEmail(v => !v)} sx={{
                                                    display: 'flex', alignItems: 'center', gap: 1.5,
                                                    px: 2.5, py: 1.5, borderRadius: 3, cursor: 'pointer',
                                                    border: `2px solid ${sendEmail ? 'var(--projected)' : '#e2e8f0'}`,
                                                    background: sendEmail ? '#eef2ff' : '#f8fafc',
                                                    transition: 'all 0.2s', minWidth: 200, userSelect: 'none',
                                                    '&:hover': { borderColor: 'var(--projected)', background: '#eef2ff' },
                                                }}>
                                                    <Switch
                                                        checked={sendEmail}
                                                        onChange={(e) => { e.stopPropagation(); setSendEmail(e.target.checked); }}
                                                        color="secondary" size="small"
                                                    />
                                                    <Box textAlign="left">
                                                        <Typography fontWeight="700" fontSize={13} color={sendEmail ? '#312e81' : 'text.secondary'}>
                                                            Send Emails
                                                        </Typography>
                                                        <Typography fontSize={11} color="text.secondary">
                                                            Email PDF to each merchant
                                                        </Typography>
                                                    </Box>
                                                </Box>

                                                {/* S3 toggle */}
                                                <Box onClick={() => setSendS3(v => !v)} sx={{
                                                    display: 'flex', alignItems: 'center', gap: 1.5,
                                                    px: 2.5, py: 1.5, borderRadius: 3, cursor: 'pointer',
                                                    border: `2px solid ${sendS3 ? '#06b6d4' : '#e2e8f0'}`,
                                                    background: sendS3 ? '#ecfeff' : '#f8fafc',
                                                    transition: 'all 0.2s', minWidth: 200, userSelect: 'none',
                                                    '&:hover': { borderColor: '#06b6d4', background: '#ecfeff' },
                                                }}>
                                                    <Switch
                                                        checked={sendS3}
                                                        onChange={(e) => { e.stopPropagation(); setSendS3(e.target.checked); }}
                                                        size="small"
                                                        sx={{ '& .MuiSwitch-thumb': { bgcolor: sendS3 ? '#06b6d4' : undefined } }}
                                                    />
                                                    <Box textAlign="left">
                                                        <Typography fontWeight="700" fontSize={13} color={sendS3 ? '#164e63' : 'text.secondary'}>
                                                            Upload to S3
                                                        </Typography>
                                                        <Typography fontSize={11} color="text.secondary">
                                                            Archive PDFs to AWS bucket
                                                        </Typography>
                                                    </Box>
                                                </Box>
                                            </Box>

                                            {/* ── Mode badge ── */}
                                            <Box sx={{
                                                display: 'inline-flex', px: 2, py: 0.6, borderRadius: 999, mb: 2.5,
                                                ...(
                                                    !sendEmail && !sendS3 ? { border: '1px solid #e2e8f0', background: '#f1f5f9' } :
                                                    !sendEmail && sendS3  ? { border: '1px solid #a5f3fc', background: '#ecfeff' } :
                                                    sendEmail && !sendS3  ? { border: '1px solid var(--chart-5)', background: '#eef2ff' } :
                                                    { border: '1px solid #a7f3d0', background: '#ecfdf5' }
                                                )
                                            }}>
                                                <Typography fontSize={12} fontWeight={700} color="text.secondary">
                                                    Mode:&nbsp;
                                                    {!sendEmail && !sendS3 ? 'Local Only (save to disk)'
                                                        : !sendEmail && sendS3 ? 'S3 Upload Only'
                                                        : sendEmail && !sendS3 ? 'Email Only'
                                                        : 'Email + S3 Upload'}
                                                </Typography>
                                            </Box>

                                            {/* ── Generate button ── */}
                                            <Box>
                                                <PremiumButton onClick={handleStartClick} startIcon={<PlayArrow />} size="large"
                                                    disabled={(scope === 'ONE' && !midInput.trim()) || (scope === 'FILE' && !midFile)}>
                                                    {scope === 'ONE' ? 'Generate Report (1 MID)'
                                                        : scope === 'FILE' ? 'Generate Reports (from file)'
                                                        : !sendEmail && !sendS3 ? 'Generate PDFs (Local)'
                                                        : !sendEmail && sendS3 ? 'Generate & Upload to S3'
                                                        : sendEmail && !sendS3 ? 'Generate & Send Emails'
                                                        : 'Generate, Email & Upload to S3'}
                                                </PremiumButton>
                                            </Box>
                                            <Typography variant="body2" color="text.secondary" mt={2}>
                                                {scope === 'ALL' ? `Generates individual reports for ${merchants.length} active merchants`
                                                    : scope === 'ONE' ? 'Generates a report for the entered MID (this tenant only)'
                                                    : 'Generates reports for the MIDs in the uploaded file (this tenant only)'}
                                            </Typography>
                                        </Box>
                                    </motion.div>
                                )}

                                {(status === 'checking' || status === 'confirming') && (
                                    <motion.div key="check" initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -20 }} style={{ width: '100%', textAlign: 'center' }}>
                                        {status === 'checking' ? (
                                            <Box py={8}><Box display="inline-block"><PulseMark size={64} /></Box><Typography variant="h6" mt={3} color="text.secondary">Verifying existing artifacts...</Typography></Box>
                                        ) : (
                                            <Box py={4} maxWidth={500} mx="auto">
                                                <Avatar sx={{ width: 80, height: 80, bgcolor: 'warning.50', color: 'warning.main', mx: 'auto', mb: 3 }}><ErrorIcon sx={{ fontSize: 40 }} /></Avatar>
                                                <Typography variant="h5" fontWeight="800" gutterBottom>Artifacts Detected</Typography>
                                                <Typography color="text.secondary" mb={1}>Found <strong>{existingReportCount}</strong> existing reports for <strong>{activeTenant?.bankName || 'this tenant'}</strong>.</Typography>
                                                <Typography color="text.secondary" mb={4}>Running this batch will overwrite them.</Typography>
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
                                                            <Typography component="div" sx={{ color: log.includes('❌') || log.includes('⚠️') ? '#f87171' : '#4ade80', mb: 0.5, display: 'flex', gap: 1.5 }}>
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
                                            <Box mt={4}>
                                                {generatedReports.length > 0 && (
                                                    <Box mb={3} textAlign="center">
                                                        <Button variant="contained" size="large" onClick={handleDownloadAll}
                                                            sx={{ borderRadius: 3, px: 5, py: 1.5, fontWeight: 'bold', background: `linear-gradient(135deg, ${theme.palette.primary.main}, ${theme.palette.secondary.main})`, boxShadow: '0 4px 14px rgba(0,0,0,0.15)', '&:hover': { boxShadow: '0 6px 20px rgba(0,0,0,0.25)' } }}>
                                                            ⬇ Download All ({generatedReports.length} PDFs as ZIP)
                                                        </Button>
                                                    </Box>
                                                )}
                                                {generatedReports.length > 0 && (
                                                    <Paper elevation={0} sx={{ borderRadius: 3, border: '1px solid #e2e8f0', overflow: 'hidden', mb: 3 }}>
                                                        <Box sx={{ px: 2.5, py: 1.5, bgcolor: '#f8fafc', borderBottom: '1px solid #e2e8f0' }}>
                                                            <Typography variant="subtitle2" fontWeight="bold" color="text.secondary">GENERATED REPORTS ({generatedReports.length})</Typography>
                                                        </Box>
                                                        <Box sx={{ maxHeight: 320, overflowY: 'auto' }}>
                                                            {generatedReports.map((report, i) => (
                                                                <Box key={i} sx={{ px: 2.5, py: 1.5, display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid #f1f5f9', '&:hover': { bgcolor: 'var(--wash)' }, transition: 'background 0.15s' }}>
                                                                    <Box display="flex" alignItems="center" gap={1.5} flex={1} minWidth={0}>
                                                                        <FileCheck size={18} style={{ color: theme.palette.success.main, flexShrink: 0 }} />
                                                                        <Typography variant="body2" fontWeight="600" noWrap>
                                                                            {report.filename.replace(/^Insight_/, '').replace(/_\d{4}-\d{2}\.pdf$/, '').replace(/_/g, ' ')}
                                                                        </Typography>
                                                                    </Box>
                                                                    <Box display="flex" alignItems="center" gap={2}>
                                                                        <Typography variant="caption" color="text.secondary">{formatFileSize(report.size)}</Typography>
                                                                        <Button size="small" variant="outlined" onClick={() => handleDownloadSingle(report.downloadUrl)}
                                                                            sx={{ borderRadius: 2, minWidth: 'auto', px: 2, fontSize: '0.75rem', textTransform: 'none' }}>
                                                                            Download
                                                                        </Button>
                                                                    </Box>
                                                                </Box>
                                                            ))}
                                                        </Box>
                                                    </Paper>
                                                )}
                                                <Box textAlign="center">
                                                    <Button onClick={() => { setStatus('idle'); setLogs([]); setGeneratedReports([]); setSendEmail(false); setSendS3(false); setProgress({ current: 0, total: merchants.length, success: 0, failed: 0 }); }} sx={{ color: 'text.secondary', fontWeight: 'bold' }}>
                                                        Start New Batch
                                                    </Button>
                                                </Box>
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
