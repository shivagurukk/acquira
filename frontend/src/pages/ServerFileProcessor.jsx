import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
    Box, Card, Typography, Button, TextField, LinearProgress, Chip, Stack,
    Paper, CircularProgress, Avatar, Container, Grid, Alert, Divider,
    Table, TableHead, TableRow, TableCell, TableBody
} from '@mui/material';
import {
    PlayArrow, CheckCircle, Error as ErrorIcon, Folder, Storage, Cancel
} from '@mui/icons-material';
import { HardDrive, Server, FolderOpen, FileText, AlertTriangle } from 'lucide-react';
import api from '../api/axios';

const POLL_INTERVAL = 3000;
const MAX_POLL_DURATION_MS = 30 * 60 * 1000;  // 30-minute cap per batch
const MAX_CONSECUTIVE_ERRORS = 5;             // surface persistent failures

// Status colour map.
// IMPORTANT: SUCCESS is the legacy backend pre-completion state — it now means
// "submitted to batch, not yet COMPLETED". The Chip is therefore styled the
// same as SUBMITTED/STARTING (blue, in-flight) so users don't see a misleading
// green tick while the job is actually still running. Real completion is shown
// only when the polling loop reports COMPLETED.
const STATUS_COLORS = {
    SUBMITTED:  { bg: '#eff6ff', color: '#2563eb', border: '#bfdbfe' },
    SUCCESS:    { bg: '#eff6ff', color: '#2563eb', border: '#bfdbfe' }, // legacy = in-flight
    COMPLETED:  { bg: '#f0fdf4', color: '#16a34a', border: '#bbf7d0' },
    FAILED:     { bg: '#fef2f2', color: '#dc2626', border: '#fecaca' },
    ABANDONED:  { bg: '#fef2f2', color: '#dc2626', border: '#fecaca' },
    STARTED:    { bg: '#eff6ff', color: '#2563eb', border: '#bfdbfe' },
    STARTING:   { bg: '#eff6ff', color: '#2563eb', border: '#bfdbfe' },
    RUNNING:    { bg: '#fefce8', color: '#ca8a04', border: '#fef08a' },
};

const getStatusStyle = (status) => STATUS_COLORS[status] || STATUS_COLORS.RUNNING;

const ServerFileProcessor = () => {
    const [serverPath, setServerPath] = useState('');
    const [phase, setPhase] = useState('input'); // input, scanning, processing, done
    const [scanResult, setScanResult] = useState(null);
    const [fileStatuses, setFileStatuses] = useState({}); // jobId -> { status, read, write, skip, progress }
    const [logs, setLogs] = useState([]);
    const [errorMsg, setErrorMsg] = useState('');
    const pollRef = useRef(null);
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
        return () => { if (pollRef.current) clearInterval(pollRef.current); };
    }, []);

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

        setPhase('scanning');
        setLogs([]);
        setScanResult(null);
        setFileStatuses({});
        setErrorMsg('');
        addLog(`🔍 Scanning path: ${serverPath}`);

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

            // Log each file result.
            // FIX: the backend marks a successfully-queued file as status "SUBMITTED"
            // (an earlier audit renamed it from "SUCCESS") and sets a jobId ONLY on the
            // submit path — a FAILED file has no jobId. The old check `fr.status === 'SUCCESS'`
            // therefore matched NOTHING: every submitted file fell into the else branch, was
            // logged as ❌ "… — undefined", and its jobId was never collected — so polling never
            // started and the rows sat looking failed while the batch actually ran in the
            // background. Key off jobId presence instead (matches the per-file table render).
            const jobIds = [];
            if (data.fileResults) {
                data.fileResults.forEach(fr => {
                    if (fr.jobId) {
                        addLog(`🚀 ${fr.type}: ${fr.file} — Job #${fr.jobId} started (${fr.sizeMB} MB, Tenant: ${fr.entity})`);
                        jobIds.push(fr.jobId);
                    } else {
                        addLog(`❌ ${fr.type}: ${fr.file} — ${fr.error || 'failed to submit'}`);
                    }
                });
            }

            if (jobIds.length > 0) {
                setPhase('processing');
                startPolling(jobIds);
            } else if (data.failed > 0) {
                setPhase('done');
                addLog('❌ All files failed to process');
            } else {
                setPhase('done');
                addLog('⚠️ No processable files found');
            }

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
        if (pollRef.current) clearInterval(pollRef.current);
    };

    // Compute overall progress
    const overallProgress = (() => {
        if (!scanResult?.fileResults) return 0;
        const jobResults = scanResult.fileResults.filter(fr => fr.jobId);
        if (jobResults.length === 0) return 0;
        let total = 0;
        jobResults.forEach(fr => {
            const js = fileStatuses[fr.jobId];
            total += js ? js.progress : 0;
        });
        return Math.round(total / jobResults.length);
    })();

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
                                    : '#6366f1',
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
                                            backgroundImage: 'linear-gradient(135deg, #6366f1, #4f46e5)',
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
                                            sx={{ cursor: 'pointer', fontSize: '0.75rem', '&:hover': { bgcolor: '#f0f9ff' } }}
                                        />
                                    ))}
                                </Stack>
                            )}

                            {/* Progress Bar */}
                            {phase === 'processing' && (
                                <Box mb={2}>
                                    <Box display="flex" justifyContent="space-between" mb={0.5}>
                                        <Typography variant="caption" fontWeight="700" color="text.secondary">OVERALL PROGRESS</Typography>
                                        <Typography variant="caption" fontWeight="700" color="primary">{overallProgress}%</Typography>
                                    </Box>
                                    <LinearProgress
                                        variant={overallProgress > 0 ? "determinate" : "indeterminate"}
                                        value={overallProgress}
                                        sx={{
                                            height: 8, borderRadius: 4, bgcolor: 'grey.100',
                                            '& .MuiLinearProgress-bar': { borderRadius: 4, background: 'linear-gradient(90deg, #6366f1, #8b5cf6)' }
                                        }}
                                    />
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
                                        <Paper elevation={0} sx={{ p: 1.5, bgcolor: '#f0f9ff', borderRadius: 2, textAlign: 'center' }}>
                                            <Typography variant="h5" fontWeight="800" color="#2563eb">{scanResult.totalFiles}</Typography>
                                            <Typography variant="caption" fontWeight="600" color="text.secondary">Total</Typography>
                                        </Paper>
                                    </Grid>
                                    <Grid item xs={4}>
                                        <Paper elevation={0} sx={{ p: 1.5, bgcolor: '#f0fdf4', borderRadius: 2, textAlign: 'center' }}>
                                            <Typography variant="h5" fontWeight="800" color="#16a34a">{scanResult.success}</Typography>
                                            <Typography variant="caption" fontWeight="600" color="text.secondary">Submitted</Typography>
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
                                                const js = fr.jobId ? fileStatuses[fr.jobId] : null;
                                                // FIX: previously fell back to fr.status ("SUCCESS" from backend
                                                // = "submitted, not done"), which painted a green tick the moment
                                                // the response arrived — before any poll happened. Now: if a job
                                                // was launched, ALWAYS use the polled status. fr.status only shows
                                                // for failures (no jobId).
                                                let displayStatus;
                                                if (fr.jobId) {
                                                    displayStatus = js?.status || 'SUBMITTED';
                                                } else {
                                                    displayStatus = fr.status === 'SUCCESS' ? 'SUBMITTED' : (fr.status || 'UNKNOWN');
                                                }
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
                                                                    bgcolor: fr.type === 'MERCHANT' ? '#dbeafe' : '#fce7f3',
                                                                    color: fr.type === 'MERCHANT' ? '#1d4ed8' : '#be185d',
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
                                                            {js && js.progress > 0 && js.status !== 'COMPLETED' && (
                                                                <LinearProgress
                                                                    variant="determinate" value={js.progress}
                                                                    sx={{ mt: 0.5, height: 3, borderRadius: 2, bgcolor: '#f1f5f9',
                                                                        '& .MuiLinearProgress-bar': { bgcolor: style.color }
                                                                    }}
                                                                />
                                                            )}
                                                        </TableCell>
                                                        <TableCell sx={{ fontSize: '0.78rem', color: '#0f172a', fontWeight: 500 }}>
                                                            {js ? `${(js.writeCount || 0).toLocaleString()}` : (fr.error ? '—' : '...')}
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
                                    { icon: '📊', text: 'Reporting & dashboards are updated automatically at the end' },
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
