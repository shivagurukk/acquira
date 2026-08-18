import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
    Box, Card, Typography, Button, TextField, LinearProgress, Chip, Stack,
    Paper, CircularProgress, Avatar, Container, Grid, Alert, Divider,
    Table, TableHead, TableRow, TableCell, TableBody
} from '@mui/material';
import {
    HardDrive, Server, FolderOpen, FileText, AlertTriangle,
    // Aliased to the former @mui/icons-material names so usage below is unchanged.
    Play as PlayArrow, CheckCircle2 as CheckCircle, XCircle as ErrorIcon,
    Folder, Database as Storage, Ban as Cancel,
} from 'lucide-react';
import api from '../api/axios';

const POLL_INTERVAL = 3000;
const MAX_POLL_DURATION_MS = 30 * 60 * 1000;  // 30-minute cap per batch
const MAX_CONSECUTIVE_ERRORS = 5;             // surface persistent failures

// Status colour map.
// Server File Processor is SEQUENTIAL: the backend processes one file at a time
// and only returns once every file has reached a terminal state, so the per-file
// status handed back is FINAL. SUCCESS therefore means genuinely done (green).
const STATUS_COLORS = {
    SUCCESS:    { bg: '#f0fdf4', color: '#16a34a', border: '#bbf7d0' },
    COMPLETED:  { bg: '#f0fdf4', color: '#16a34a', border: '#bbf7d0' },
    SUBMITTED:  { bg: 'var(--wash)', color: 'var(--primary)', border: 'var(--border)' },
    FAILED:     { bg: '#fef2f2', color: '#dc2626', border: '#fecaca' },
    ABANDONED:  { bg: '#fef2f2', color: '#dc2626', border: '#fecaca' },
    SKIPPED:    { bg: '#fffbeb', color: '#b45309', border: '#fde68a' },
    RUNNING:    { bg: '#fefce8', color: '#ca8a04', border: '#fef08a' },
};

const getStatusStyle = (status) => STATUS_COLORS[status] || STATUS_COLORS.RUNNING;

// The fixed ingest pipeline shown in the stepper. The Server File Processor runs
// strictly one file at a time; the request blocks until every file is done, so we
// surface the STAGES of the pipeline rather than a fake per-file percentage.
const PIPELINE_STEPS = [
    { key: 'validate', label: 'Validate path', desc: 'Check the folder is under an allowed data directory' },
    { key: 'scan',     label: 'Scan files',    desc: 'Detect each file as Merchant or Transaction' },
    { key: 'merchant', label: 'Process merchants', desc: 'Load merchant master files first (one at a time)' },
    { key: 'txn',      label: 'Process transactions', desc: 'Load transaction files next (one at a time)' },
    { key: 'report',   label: 'Update reporting', desc: 'Aggregate summary tables & dashboards' },
    { key: 'done',     label: 'Complete',       desc: 'All files finished' },
];

const fmtElapsed = (secs) => {
    const m = Math.floor(secs / 60), s = secs % 60;
    return m > 0 ? `${m}m ${String(s).padStart(2, '0')}s` : `${s}s`;
};

const ServerFileProcessor = () => {
    const [serverPath, setServerPath] = useState('');
    const [phase, setPhase] = useState('input'); // input, scanning, processing, done
    const [scanResult, setScanResult] = useState(null);
    const [fileStatuses, setFileStatuses] = useState({}); // jobId -> { status, read, write, skip, progress }
    const [logs, setLogs] = useState([]);
    const [errorMsg, setErrorMsg] = useState('');
    // Sequential pipeline: which named step is active (0-based index into PIPELINE_STEPS).
    // While the (blocking) request is in flight we cannot know sub-file progress, so we
    // advance the stepper optimistically to the "processing" stage and reconcile to
    // "done" when the response lands.
    const [activeStep, setActiveStep] = useState(0);
    const [elapsed, setElapsed] = useState(0); // seconds since processing began
    const pollRef = useRef(null);
    const timerRef = useRef(null);
    const logsEndRef = useRef(null);
    // Guard against double-click / React StrictMode double-invoke firing
    // POST /process-server-file twice. setPhase('scanning') is async, so the
    // button can fire two requests in rapid succession before the disabled
    // state actually renders. A ref flips synchronously and blocks the second.
    const inFlightRef = useRef(false);

    useEffect(() => {
        if (logsEndRef.current) logsEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }, [logs]);

    useEffect(() => {
        return () => {
            if (pollRef.current) clearInterval(pollRef.current);
            if (timerRef.current) clearInterval(timerRef.current);
        };
    }, []);

    // Live elapsed-time ticker while the sequential batch runs.
    useEffect(() => {
        if (phase === 'processing') {
            const t0 = Date.now();
            setElapsed(0);
            timerRef.current = setInterval(() => setElapsed(Math.floor((Date.now() - t0) / 1000)), 1000);
        } else if (timerRef.current) {
            clearInterval(timerRef.current);
            timerRef.current = null;
        }
        return () => { if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; } };
    }, [phase]);

    const addLog = (msg) => {
        const ts = new Date().toLocaleTimeString('en-US', { hour12: false });
        setLogs(prev => [...prev, { ts, msg }]);
    };

    // Poll multiple job IDs.
    // Termination conditions (mirrors useNotifications):
    //   - all jobs reach a terminal state (COMPLETED / FAILED / ABANDONED)
    //   - 30-minute wall-clock cap from when polling started
    //   - 5 consecutive errors across the whole batch
    const startedAtRef = useRef(null);
    const errorCountRef = useRef(0);

    const startPolling = useCallback((jobIds) => {
        if (pollRef.current) clearInterval(pollRef.current);
        if (!jobIds || jobIds.length === 0) return;

        startedAtRef.current = Date.now();
        errorCountRef.current = 0;

        const stopWith = (msg, isError = false) => {
            clearInterval(pollRef.current);
            pollRef.current = null;
            setPhase('done');
            addLog(`${isError ? '⚠️' : '✅'} ${msg}`);
        };

        const poll = async () => {
            // Wall-clock guard.
            if (Date.now() - startedAtRef.current > MAX_POLL_DURATION_MS) {
                stopWith('Polling timed out after 30 minutes. Check Batch Monitoring for live status.', true);
                return;
            }

            let allDone = true;
            let pollErrorThisRound = false;

            for (const jobId of jobIds) {
                try {
                    const res = await api.get(`/batch/jobs/${jobId}`);
                    const d = res.data;
                    const s = (d.status || '').toUpperCase();

                    setFileStatuses(prev => ({
                        ...prev,
                        [jobId]: {
                            status: s,
                            readCount: d.readCount || 0,
                            writeCount: d.writeCount || 0,
                            skipCount: d.skipCount || 0,
                            progress: d.progress || 0,
                            totalRows: d.totalRows || 0,
                            jobName: d.jobName || '',
                        }
                    }));

                    if (s !== 'COMPLETED' && s !== 'FAILED' && s !== 'ABANDONED') {
                        allDone = false;
                    }
                } catch (err) {
                    pollErrorThisRound = true;
                    allDone = false; // Keep polling on transient errors
                }
            }

            if (pollErrorThisRound) {
                errorCountRef.current += 1;
                if (errorCountRef.current >= MAX_CONSECUTIVE_ERRORS) {
                    stopWith('Lost connection to batch service. Check Batch Monitoring for actual status.', true);
                    return;
                }
            } else {
                errorCountRef.current = 0;  // any clean round resets the counter
            }

            if (allDone) {
                stopWith('All jobs completed');
            }
        };

        poll();
        pollRef.current = setInterval(poll, POLL_INTERVAL);
    }, []);

    const handleProcess = async () => {
        if (!serverPath.trim()) return;
        if (inFlightRef.current) return;  // double-click guard
        inFlightRef.current = true;

        setPhase('processing');
        setActiveStep(0);      // Validate path
        setLogs([]);
        setScanResult(null);
        setFileStatuses({});
        setErrorMsg('');
        addLog(`🔍 Validating & scanning path: ${serverPath}`);
        addLog('⏳ Files are processed ONE AT A TIME — this request stays open until the whole batch finishes.');

        // Optimistically advance the stepper into the "processing" stages while the
        // (blocking) request runs. We can't know which individual file is mid-flight
        // from a single blocking call, so we sit on the transaction stage until the
        // response lands and then reconcile to the true per-file outcomes.
        setActiveStep(3); // Process transactions (the longest stage)

        try {
            const res = await api.post(`/upload/process-server-file?path=${encodeURIComponent(serverPath.trim())}`);
            const data = res.data;

            if (data.error) {
                setPhase('input');
                setErrorMsg(data.error);
                addLog(`❌ ${data.error}`);
                return;
            }

            setScanResult(data);

            // Log scan results
            addLog(`📂 Found ${data.totalFiles} file(s): ${data.merchantFiles} merchant, ${data.transactionFiles} transaction`);
            if (data.skipped && data.skipped.length > 0) {
                data.skipped.forEach(s => addLog(`⚠️ Skipped: ${s.file} — ${s.reason}`));
            }

            // Surface backend's aggregated failure summary as a top-level error
            // banner so the user sees ONE reason rather than having to scan rows.
            if (data.errorSummary) {
                setErrorMsg(data.errorSummary);
                addLog(`❌ ${data.errorSummary}`);
            }

            // SEQUENTIAL backend: every file has already reached a terminal state by the
            // time this response arrives, so fr.status is FINAL (SUCCESS / FAILED). No
            // polling needed — log each outcome in order and mark the pipeline complete.
            if (data.fileResults) {
                data.fileResults.forEach((fr, idx) => {
                    const ok = fr.status === 'SUCCESS' || fr.status === 'COMPLETED';
                    const icon = ok ? '✅' : '❌';
                    const detail = ok
                        ? `done (${fr.sizeMB} MB, Tenant: ${fr.entity || '—'})`
                        : (fr.error || 'failed');
                    addLog(`${icon} [${idx + 1}/${data.fileResults.length}] ${fr.type}: ${fr.file} — ${detail}`);
                });
            }

            if (data.transactionFiles > 0) {
                addLog('📊 Reporting & dashboards updated for affected tenant(s).');
            }

            const okCount = (data.success || 0);
            const failCount = (data.failed || 0);
            addLog(`🏁 Batch complete — ${okCount} succeeded, ${failCount} failed${data.skipped?.length ? `, ${data.skipped.length} skipped` : ''}.`);

            setActiveStep(PIPELINE_STEPS.length - 1); // Complete
            setPhase('done');

        } catch (err) {
            setPhase('input');
            const msg = err.response?.data?.error || err.response?.data || err.message;
            setErrorMsg(typeof msg === 'object' ? JSON.stringify(msg) : String(msg));
            addLog(`❌ ${typeof msg === 'object' ? JSON.stringify(msg) : String(msg)}`);
        } finally {
            inFlightRef.current = false;
        }
    };

    const reset = () => {
        setPhase('input');
        setLogs([]);
        setScanResult(null);
        setFileStatuses({});
        setErrorMsg('');
        setActiveStep(0);
        setElapsed(0);
        if (pollRef.current) clearInterval(pollRef.current);
        if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
    };

    // Completed-file counts (sequential backend returns final per-file status).
    const doneCount = scanResult?.fileResults
        ? scanResult.fileResults.filter(fr => fr.status === 'SUCCESS' || fr.status === 'COMPLETED').length
        : 0;
    const totalProcessable = scanResult?.fileResults ? scanResult.fileResults.length : 0;
    // On completion the bar is full; while processing we can't know sub-file % (blocking
    // call), so the bar is indeterminate and the stepper + elapsed timer carry the signal.
    const overallProgress = phase === 'done'
        ? (totalProcessable > 0 ? Math.round((doneCount / totalProcessable) * 100) : 100)
        : 0;

    return (
        <Box sx={{ p: 3, bgcolor: '#F8FAFC', minHeight: '100vh' }}>
            {/* Header */}
            <Box sx={{ mb: 3 }}>
                <Box display="flex" alignItems="center" gap={1.5} mb={0.5}>
                    <Server size={28} color="#4f46e5" />
                    <Typography variant="h5" fontWeight="800" color="#0f172a">Server File Processor</Typography>
                </Box>
                <Typography variant="body2" color="text.secondary">
                    Process large data files (CSV/XLSX) directly from the server. Supports folders with multiple files — merchants are processed first, then transactions.
                </Typography>
            </Box>

            <Grid container spacing={3}>
                {/* Left Panel: Input + Logs */}
                <Grid item xs={12} md={7}>
                    <Card sx={{ borderRadius: 3, boxShadow: '0 4px 20px rgba(0,0,0,0.06)', overflow: 'hidden' }}>
                        {/* Status Bar */}
                        <Box sx={{
                            height: 4,
                            bgcolor: phase === 'done' ? (scanResult?.failed > 0 ? '#f87171' : '#4ade80')
                                : phase === 'processing' ? '#fbbf24'
                                    : 'var(--projected)',
                            transition: 'background-color 0.5s'
                        }} />

                        <Box sx={{ p: 4 }}>
                            {/* Path Input */}
                            <Box display="flex" gap={1.5} mb={2}>
                                <TextField
                                    fullWidth size="small"
                                    label="Server Path (file or folder)"
                                    placeholder="/opt/acquira/data/uploads/"
                                    value={serverPath}
                                    onChange={(e) => setServerPath(e.target.value)}
                                    disabled={phase !== 'input'}
                                    InputProps={{
                                        startAdornment: <FolderOpen size={16} style={{ marginRight: 8, color: '#94a3b8' }} />,
                                    }}
                                    sx={{ '& .MuiOutlinedInput-root': { borderRadius: 2 } }}
                                />
                                {phase === 'input' ? (
                                    <Button
                                        variant="contained" onClick={handleProcess}
                                        disabled={!serverPath.trim()}
                                        startIcon={<PlayArrow />}
                                        sx={{
                                            minWidth: 140, borderRadius: 2, fontWeight: 700, textTransform: 'none',
                                            backgroundImage: 'linear-gradient(135deg, var(--projected), #4f46e5)',
                                        }}
                                    >
                                        Process
                                    </Button>
                                ) : phase === 'done' ? (
                                    <Button variant="outlined" onClick={reset} sx={{ minWidth: 140, borderRadius: 2, fontWeight: 600, textTransform: 'none' }}>
                                        New Batch
                                    </Button>
                                ) : (
                                    <Button disabled variant="contained" sx={{ minWidth: 140, borderRadius: 2 }}>
                                        <CircularProgress size={20} sx={{ mr: 1 }} /> Running
                                    </Button>
                                )}
                            </Box>

                            {/* Quick path chips */}
                            {phase === 'input' && (
                                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 2 }}>
                                    {['/opt/acquira/data/uploads/', '/home/acquira/uploads/', '/tmp/'].map(p => (
                                        <Chip key={p} label={p} size="small" variant="outlined"
                                            icon={<Folder sx={{ fontSize: 14 }} />}
                                            onClick={() => setServerPath(p)}
                                            sx={{ cursor: 'pointer', fontSize: '0.75rem', '&:hover': { bgcolor: 'var(--wash)' } }}
                                        />
                                    ))}
                                </Stack>
                            )}

                            {/* Pipeline stepper + progress (processing & done) */}
                            {(phase === 'processing' || phase === 'done') && (
                                <Box mb={2}>
                                    <Box display="flex" justifyContent="space-between" alignItems="center" mb={0.5}>
                                        <Typography variant="caption" fontWeight="700" color="text.secondary">
                                            {phase === 'done'
                                                ? `COMPLETE — ${doneCount}/${totalProcessable} FILE${totalProcessable === 1 ? '' : 'S'} PROCESSED`
                                                : 'PROCESSING — ONE FILE AT A TIME'}
                                        </Typography>
                                        <Typography variant="caption" fontWeight="700" color={phase === 'done' ? 'success.main' : 'primary'}>
                                            {phase === 'done' ? `${overallProgress}%` : `⏱ ${fmtElapsed(elapsed)}`}
                                        </Typography>
                                    </Box>
                                    <LinearProgress
                                        variant={phase === 'done' ? 'determinate' : 'indeterminate'}
                                        value={overallProgress}
                                        sx={{
                                            height: 8, borderRadius: 4, bgcolor: 'grey.100', mb: 2,
                                            '& .MuiLinearProgress-bar': {
                                                borderRadius: 4,
                                                background: phase === 'done'
                                                    ? (scanResult?.failed > 0
                                                        ? 'linear-gradient(90deg, #f59e0b, #ef4444)'
                                                        : 'linear-gradient(90deg, #22c55e, #16a34a)')
                                                    : 'linear-gradient(90deg, var(--projected), #8b5cf6)'
                                            }
                                        }}
                                    />

                                    {/* Step checklist */}
                                    <Stack spacing={0.75}>
                                        {PIPELINE_STEPS.map((step, i) => {
                                            const isDone = phase === 'done' || i < activeStep;
                                            const isActive = phase !== 'done' && i === activeStep;
                                            const color = isDone ? '#16a34a' : isActive ? 'var(--projected)' : '#cbd5e1';
                                            return (
                                                <Box key={step.key} display="flex" alignItems="center" gap={1.25}>
                                                    <Box sx={{
                                                        width: 18, height: 18, borderRadius: '50%', flexShrink: 0,
                                                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                        bgcolor: isDone ? '#dcfce7' : isActive ? '#eef2ff' : '#f1f5f9',
                                                        border: `1.5px solid ${color}`
                                                    }}>
                                                        {isDone
                                                            ? <CheckCircle sx={{ fontSize: 12, color: '#16a34a' }} />
                                                            : isActive
                                                                ? <CircularProgress size={10} thickness={6} sx={{ color: 'var(--projected)' }} />
                                                                : <Box sx={{ width: 5, height: 5, borderRadius: '50%', bgcolor: '#cbd5e1' }} />}
                                                    </Box>
                                                    <Box>
                                                        <Typography variant="body2" fontWeight={isActive ? 700 : 600}
                                                            sx={{ color: isDone ? '#0f172a' : isActive ? '#4f46e5' : '#94a3b8', lineHeight: 1.3 }}>
                                                            {step.label}
                                                        </Typography>
                                                        {isActive && (
                                                            <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1.2 }}>
                                                                {step.desc}
                                                            </Typography>
                                                        )}
                                                    </Box>
                                                </Box>
                                            );
                                        })}
                                    </Stack>
                                </Box>
                            )}

                            {/* Error */}
                            {errorMsg && (
                                <Alert severity="error" sx={{ mb: 2, borderRadius: 2 }}>{errorMsg}</Alert>
                            )}

                            {/* Log Terminal */}
                            {logs.length > 0 && (
                                <Paper elevation={0} sx={{ bgcolor: '#0f172a', borderRadius: 2, overflow: 'hidden' }}>
                                    <Box sx={{ px: 2, py: 1, bgcolor: '#1e293b', borderBottom: '1px solid #334155', display: 'flex', gap: 0.8, alignItems: 'center' }}>
                                        <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: '#ef4444' }} />
                                        <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: '#f59e0b' }} />
                                        <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: '#22c55e' }} />
                                        <Typography sx={{ ml: 1.5, color: '#64748b', fontSize: 11, fontFamily: 'monospace' }}>processing.log</Typography>
                                    </Box>
                                    <Box sx={{ p: 2, maxHeight: 260, overflowY: 'auto', fontFamily: "'Fira Code', monospace", fontSize: '0.8rem' }}>
                                        {logs.map((log, i) => (
                                            <Typography key={i} component="div" sx={{
                                                color: log.msg.includes('❌') ? '#f87171' : log.msg.includes('✅') ? '#4ade80' : log.msg.includes('⚠️') ? '#fbbf24' : '#94a3b8',
                                                mb: 0.3, display: 'flex', gap: 1.5, lineHeight: 1.6
                                            }}>
                                                <span style={{ opacity: 0.4, flexShrink: 0 }}>{log.ts}</span>
                                                <span>{log.msg}</span>
                                            </Typography>
                                        ))}
                                        <div ref={logsEndRef} />
                                    </Box>
                                </Paper>
                            )}
                        </Box>
                    </Card>
                </Grid>

                {/* Right Panel: File Results */}
                <Grid item xs={12} md={5}>
                    {/* Scan Summary */}
                    {scanResult && (
                        <Card sx={{ borderRadius: 3, boxShadow: '0 4px 20px rgba(0,0,0,0.06)', mb: 2 }}>
                            <Box sx={{ p: 3 }}>
                                <Typography variant="subtitle2" fontWeight="800" color="text.secondary" mb={2}>
                                    BATCH SUMMARY
                                </Typography>
                                <Grid container spacing={1.5}>
                                    <Grid item xs={4}>
                                        <Paper elevation={0} sx={{ p: 1.5, bgcolor: 'var(--wash)', borderRadius: 2, textAlign: 'center' }}>
                                            <Typography variant="h5" fontWeight="800" color="var(--primary)">{scanResult.totalFiles}</Typography>
                                            <Typography variant="caption" fontWeight="600" color="text.secondary">Total</Typography>
                                        </Paper>
                                    </Grid>
                                    <Grid item xs={4}>
                                        <Paper elevation={0} sx={{ p: 1.5, bgcolor: '#f0fdf4', borderRadius: 2, textAlign: 'center' }}>
                                            <Typography variant="h5" fontWeight="800" color="#16a34a">{scanResult.success}</Typography>
                                            <Typography variant="caption" fontWeight="600" color="text.secondary">Succeeded</Typography>
                                        </Paper>
                                    </Grid>
                                    <Grid item xs={4}>
                                        <Paper elevation={0} sx={{ p: 1.5, bgcolor: '#fef2f2', borderRadius: 2, textAlign: 'center' }}>
                                            <Typography variant="h5" fontWeight="800" color="#dc2626">{scanResult.failed}</Typography>
                                            <Typography variant="caption" fontWeight="600" color="text.secondary">Failed</Typography>
                                        </Paper>
                                    </Grid>
                                </Grid>
                            </Box>
                        </Card>
                    )}

                    {/* Per-File Status Table */}
                    {scanResult?.fileResults && scanResult.fileResults.length > 0 && (
                        <Card sx={{ borderRadius: 3, boxShadow: '0 4px 20px rgba(0,0,0,0.06)' }}>
                            <Box sx={{ p: 3 }}>
                                <Typography variant="subtitle2" fontWeight="800" color="text.secondary" mb={2}>
                                    FILE RESULTS
                                </Typography>
                                <Box sx={{ overflowX: 'auto' }}>
                                    <Table size="small">
                                        <TableHead>
                                            <TableRow>
                                                <TableCell sx={{ fontWeight: 700, fontSize: '0.7rem', color: '#64748b' }}>FILE</TableCell>
                                                <TableCell sx={{ fontWeight: 700, fontSize: '0.7rem', color: '#64748b' }}>TYPE</TableCell>
                                                <TableCell sx={{ fontWeight: 700, fontSize: '0.7rem', color: '#64748b' }}>SIZE</TableCell>
                                                <TableCell sx={{ fontWeight: 700, fontSize: '0.7rem', color: '#64748b' }}>STATUS</TableCell>
                                                <TableCell sx={{ fontWeight: 700, fontSize: '0.7rem', color: '#64748b' }}>ROWS</TableCell>
                                            </TableRow>
                                        </TableHead>
                                        <TableBody>
                                            {scanResult.fileResults.map((fr, i) => {
                                                // Sequential backend: fr.status is FINAL (SUCCESS / FAILED).
                                                const displayStatus = fr.status || 'UNKNOWN';
                                                const style = getStatusStyle(displayStatus);

                                                return (
                                                    <TableRow key={i} sx={{ '&:last-child td': { borderBottom: 0 } }}>
                                                        <TableCell sx={{ fontSize: '0.78rem', maxWidth: 180 }}>
                                                            <Box display="flex" alignItems="center" gap={0.8}>
                                                                <FileText size={14} color="#94a3b8" />
                                                                <Typography variant="body2" fontWeight="500" noWrap title={fr.file}>
                                                                    {fr.file}
                                                                </Typography>
                                                            </Box>
                                                        </TableCell>
                                                        <TableCell>
                                                            <Chip label={fr.type} size="small"
                                                                sx={{
                                                                    fontSize: '0.65rem', fontWeight: 700, height: 22,
                                                                    bgcolor: fr.type === 'MERCHANT' ? 'var(--wash)' : '#fce7f3',
                                                                    color: fr.type === 'MERCHANT' ? 'var(--primary)' : '#be185d',
                                                                }}
                                                            />
                                                        </TableCell>
                                                        <TableCell sx={{ fontSize: '0.78rem', color: '#64748b' }}>
                                                            {fr.sizeMB > 0 ? `${fr.sizeMB} MB` : '<1 MB'}
                                                        </TableCell>
                                                        <TableCell>
                                                            <Chip
                                                                label={displayStatus}
                                                                size="small"
                                                                sx={{
                                                                    fontSize: '0.6rem', fontWeight: 700, height: 20,
                                                                    bgcolor: style.bg, color: style.color,
                                                                    border: `1px solid ${style.border}`
                                                                }}
                                                            />
                                                        </TableCell>
                                                        <TableCell sx={{ fontSize: '0.78rem', color: '#0f172a', fontWeight: 500 }}>
                                                            {(fr.status === 'SUCCESS' || fr.status === 'COMPLETED') ? '✓' : (fr.error ? '—' : '')}
                                                        </TableCell>
                                                    </TableRow>
                                                );
                                            })}
                                        </TableBody>
                                    </Table>
                                </Box>
                            </Box>

                            {/* Skipped files */}
                            {scanResult.skipped && scanResult.skipped.length > 0 && (
                                <Box sx={{ px: 3, pb: 3 }}>
                                    <Divider sx={{ mb: 2 }} />
                                    <Typography variant="caption" fontWeight="700" color="text.secondary" mb={1} display="block">
                                        SKIPPED FILES
                                    </Typography>
                                    {scanResult.skipped.map((s, i) => (
                                        <Box key={i} display="flex" alignItems="center" gap={1} mb={0.5}>
                                            <AlertTriangle size={13} color="#f59e0b" />
                                            <Typography variant="caption" color="text.secondary">
                                                <strong>{s.file}</strong> — {s.reason}
                                            </Typography>
                                        </Box>
                                    ))}
                                </Box>
                            )}
                        </Card>
                    )}

                    {/* Helpful info when idle */}
                    {phase === 'input' && (
                        <Card sx={{ borderRadius: 3, boxShadow: '0 4px 20px rgba(0,0,0,0.06)' }}>
                            <Box sx={{ p: 3 }}>
                                <Typography variant="subtitle2" fontWeight="800" color="text.secondary" mb={2}>
                                    HOW IT WORKS
                                </Typography>
                                {[
                                    { icon: '📁', text: 'Point to a folder or single file on the server' },
                                    { icon: '🔍', text: 'Auto-detects file type (Merchant / Transaction) and format (XLSX / CSV)' },
                                    { icon: '🏪', text: 'Merchant files are processed FIRST (so dimension tables exist)' },
                                    { icon: '💳', text: 'Transaction files are processed NEXT (links to merchants)' },
                                    { icon: '📊', text: 'Files run ONE AT A TIME; reporting & dashboards update at the end' },
                                ].map((step, i) => (
                                    <Box key={i} display="flex" alignItems="flex-start" gap={1.5} mb={1.5}>
                                        <Typography fontSize="1.1rem">{step.icon}</Typography>
                                        <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.6 }}>{step.text}</Typography>
                                    </Box>
                                ))}
                                <Divider sx={{ my: 2 }} />
                                <Typography variant="caption" color="text.secondary">
                                    <strong>Tip:</strong> For files &gt; 500MB, use SCP/SFTP to upload to the server first, then enter the path here.
                                    Supports <strong>.xlsx</strong>, <strong>.csv</strong>, <strong>.tsv</strong> formats.
                                </Typography>
                            </Box>
                        </Card>
                    )}
                </Grid>
            </Grid>
        </Box>
    );
};

export default ServerFileProcessor;
