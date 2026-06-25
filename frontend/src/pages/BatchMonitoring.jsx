import React, { useState, useEffect, useRef } from 'react';
import { Activity, CheckCircle, XCircle, Clock, RefreshCw, Loader2, Wifi, WifiOff } from 'lucide-react';
import api from '../api/axios';
import PageHeader from '../components/PageHeader';

const thStyle = {
    padding: '14px 16px',
    textAlign: 'left',
    fontSize: '0.72rem',
    color: 'var(--text-secondary, #64748b)',
    fontWeight: 700,
    letterSpacing: '0.02em',
};

const BatchMonitoring = () => {
    const [jobs, setJobs] = useState([]);
    const [liveJobs, setLiveJobs] = useState({});
    const [loading, setLoading] = useState(true);
    const [sseConnected, setSseConnected] = useState(false);
    const [sseFailed, setSseFailed] = useState(false);
    const eventSourceRef = useRef(null);
    const retryTimeoutRef = useRef(null);
    const sseFailTimerRef = useRef(null);

    const fetchJobs = async () => {
        setLoading(true);
        try {
            const res = await api.get('/batch/jobs?size=20');
            setJobs(res.data);
        } catch (error) {
            console.error('Failed to fetch jobs', error);
        } finally {
            setLoading(false);
        }
    };

    const connectSSE = () => {
        if (eventSourceRef.current) eventSourceRef.current.close();

        const token    = localStorage.getItem('token');
        const tenantId = localStorage.getItem('defaultTenantId');
        const url      = `/api/batch/jobs/live?token=${encodeURIComponent(token)}&tenantId=${tenantId}`;
        const es       = new EventSource(url);

        es.addEventListener('jobs', (event) => {
            try {
                const data = JSON.parse(event.data);
                const liveMap = {};
                data.forEach(job => { liveMap[job.executionId] = job; });
                setLiveJobs(liveMap);
                setSseConnected(true);
            } catch (e) {
                console.error('SSE parse error:', e);
            }
        });

        es.onerror = () => {
            setSseConnected(false);
            es.close();
            // Show a banner after 10s of failed SSE — polling is the fallback
            if (sseFailTimerRef.current) clearTimeout(sseFailTimerRef.current);
            sseFailTimerRef.current = setTimeout(() => setSseFailed(true), 10000);
            retryTimeoutRef.current = setTimeout(connectSSE, 5000);
        };

        es.onopen = () => setSseConnected(true);

        eventSourceRef.current = es;
    };

    useEffect(() => {
        fetchJobs();
        connectSSE();
        const interval = setInterval(fetchJobs, 30000);
        return () => {
            clearInterval(interval);
            if (eventSourceRef.current) eventSourceRef.current.close();
            if (retryTimeoutRef.current)  clearTimeout(retryTimeoutRef.current);
            if (sseFailTimerRef.current)  clearTimeout(sseFailTimerRef.current);
        };
    }, []);

    // Merge live data into historical list
    const mergedJobs = jobs.map(job => {
        const live = liveJobs[job.executionId];
        return live ? { ...job, ...live, isLive: true } : job;
    });

    // Add live jobs not yet in history
    const historyIds     = new Set(jobs.map(j => j.executionId));
    const extraLiveJobs  = Object.values(liveJobs)
        .filter(j => !historyIds.has(j.executionId))
        .map(j => ({ ...j, isLive: true }));
    const allJobs = [...extraLiveJobs, ...mergedJobs];

    const getStatusBadge = (status, isLive) => {
        const colors = {
            COMPLETED: { bg: '#dcfce7', text: '#166534' },
            FAILED:    { bg: '#fee2e2', text: '#991b1b' },
            STARTED:   { bg: '#dbeafe', text: '#1e40af' },
            STARTING:  { bg: '#dbeafe', text: '#1e40af' },
        };
        const c = colors[status] || colors.STARTED;
        return (
            <span style={{
                padding: '4px 10px', borderRadius: '999px', fontSize: '12px', fontWeight: 600,
                background: c.bg, color: c.text, display: 'inline-flex', alignItems: 'center', gap: '4px',
            }}>
                {isLive && status === 'STARTED' && (
                    <Loader2 size={12} style={{ animation: 'spin 1s linear infinite' }} />
                )}
                {status}
            </span>
        );
    };

    const formatTime = (seconds) => {
        if (!seconds || seconds <= 0) return '';
        if (seconds < 60) return `${seconds}s`;
        const m = Math.floor(seconds / 60);
        const s = seconds % 60;
        return `${m}m ${s}s`;
    };

    return (
        <div style={{ background: 'var(--bg, #f8fafc)', minHeight: '100vh' }}>
            <PageHeader
                title="Batch Operations"
                subtitle={
                    <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        Monitor transaction processing jobs
                        {sseConnected ? (
                            <span style={{
                                display: 'inline-flex', alignItems: 'center', gap: 4,
                                color: '#16a34a', fontSize: '0.75rem', fontWeight: 500,
                            }}>
                                <Wifi size={11} /> Live
                            </span>
                        ) : (
                            <span style={{
                                display: 'inline-flex', alignItems: 'center', gap: 4,
                                color: 'var(--text-secondary, #94a3b8)', fontSize: '0.75rem',
                            }}>
                                <WifiOff size={11} /> Polling
                            </span>
                        )}
                    </span>
                }
                icon={Activity}
                actions={
                    <button
                        onClick={fetchJobs}
                        style={{
                            display: 'flex', alignItems: 'center', gap: 6,
                            padding: '7px 14px',
                            background: 'var(--bg-card, #fff)',
                            border: '1px solid var(--border, #e2e8f0)',
                            borderRadius: 'var(--radius-md, 8px)',
                            cursor: 'pointer', fontSize: '12px', fontWeight: 500,
                            color: 'var(--text-secondary, #64748b)',
                        }}
                    >
                        <RefreshCw size={14} /> Refresh
                    </button>
                }
            />

            {/* ── Content wrapper ── */}
            {/* SSE fallback banner */}
            {sseFailed && !sseConnected && (
                <div style={{
                    margin: '0 0 16px',
                    padding: '10px 16px', borderRadius: 8,
                    background: '#fef9ec', border: '0.5px solid #fcd34d',
                    display: 'flex', alignItems: 'center', gap: 10, fontSize: 12,
                }}>
                    <WifiOff size={14} color="#b45309" />
                    <span style={{ color: '#92400e', fontWeight: 500 }}>
                        Live updates unavailable (SSE blocked or timed out) — falling back to 30s polling.
                        <button onClick={connectSSE} style={{ marginLeft: 8, background: 'none', border: 'none', cursor: 'pointer', color: '#1d4ed8', fontWeight: 600, fontSize: 12, padding: 0 }}>Retry live</button>
                    </span>
                </div>
            )}

            <div style={{ padding: 'var(--space-page, 24px)' }}>
                <div style={{
                    background: 'var(--bg-card, #fff)',
                    borderRadius: 'var(--radius-lg, 12px)',
                    border: '1px solid var(--border, #e2e8f0)',
                    boxShadow: 'var(--shadow-xs)',
                    overflow: 'hidden',
                }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead style={{ background: 'var(--bg, #f8fafc)', borderBottom: '1px solid var(--border, #e2e8f0)' }}>
                            <tr>
                                <th style={thStyle}>JOB ID</th>
                                <th style={thStyle}>STATUS</th>
                                <th style={thStyle}>PROGRESS</th>
                                <th style={thStyle}>RECORDS</th>
                                <th style={thStyle}>START TIME</th>
                                <th style={thStyle}>END TIME</th>
                            </tr>
                        </thead>
                        <tbody>
                            {loading && allJobs.length === 0 ? (
                                <tr>
                                    <td colSpan="6" style={{ padding: '24px', textAlign: 'center', color: '#94a3b8' }}>
                                        Loading...
                                    </td>
                                </tr>
                            ) : allJobs.length === 0 ? (
                                <tr>
                                    <td colSpan="6" style={{ padding: '24px', textAlign: 'center', color: '#94a3b8' }}>
                                        No batch jobs found
                                    </td>
                                </tr>
                            ) : allJobs.map((job) => (
                                <tr key={job.executionId} style={{ borderBottom: '1px solid #f1f5f9' }}>

                                    {/* Job ID */}
                                    <td style={{ padding: '16px', fontWeight: 500 }}>
                                        #{job.executionId}
                                    </td>

                                    {/* Status */}
                                    <td style={{ padding: '16px' }}>
                                        {getStatusBadge(job.status, job.isLive)}
                                    </td>

                                    {/* Progress */}
                                    <td style={{ padding: '16px', minWidth: '200px' }}>
                                        {job.isLive && job.status === 'STARTED' ? (
                                            <div>
                                                <div style={{
                                                    height: '8px', borderRadius: '4px',
                                                    background: '#e2e8f0', overflow: 'hidden', marginBottom: '4px',
                                                }}>
                                                    <div style={{
                                                        height: '100%', borderRadius: '4px',
                                                        background: 'linear-gradient(90deg, #3b82f6, #1d4ed8)',
                                                        width: `${job.progress >= 0 ? job.progress : 0}%`,
                                                        transition: 'width 0.5s ease',
                                                    }} />
                                                </div>
                                                <div style={{
                                                    display: 'flex', justifyContent: 'space-between',
                                                    fontSize: '0.75rem', color: '#64748b',
                                                }}>
                                                    <span>
                                                        {job.progress >= 0 ? `${job.progress}%` : 'Processing...'}
                                                    </span>
                                                    {job.estimatedSecondsRemaining > 0 && (
                                                        <span>~{formatTime(job.estimatedSecondsRemaining)} left</span>
                                                    )}
                                                </div>
                                            </div>
                                        ) : job.status === 'COMPLETED' ? (
                                            <span style={{ color: '#16a34a', fontWeight: 500, fontSize: '0.85rem' }}>
                                                100% ✓
                                            </span>
                                        ) : job.status === 'FAILED' ? (
                                            <span style={{ color: '#dc2626', fontSize: '0.85rem' }}>Failed</span>
                                        ) : (
                                            <span style={{ color: '#94a3b8', fontSize: '0.85rem' }}>—</span>
                                        )}
                                    </td>

                                    {/* Records */}
                                    <td style={{ padding: '16px' }}>
                                        {job.readCount !== undefined ? (
                                            <div style={{ fontSize: '0.85rem' }}>
                                                <span style={{ fontWeight: 500 }}>
                                                    {(job.readCount || 0).toLocaleString()}
                                                </span>
                                                <span style={{ color: '#94a3b8' }}>
                                                    {' / '}{(job.totalRows || '?').toLocaleString()}
                                                </span>
                                                {job.skipCount > 0 && (
                                                    <span style={{ color: '#f59e0b', marginLeft: '6px', fontSize: '0.75rem' }}>
                                                        ({job.skipCount} skipped)
                                                    </span>
                                                )}
                                            </div>
                                        ) : (
                                            <span style={{ color: '#94a3b8' }}>—</span>
                                        )}
                                    </td>

                                    {/* Start Time */}
                                    <td style={{ padding: '16px', color: '#64748b', fontSize: '0.85rem' }}>
                                        {job.startTime ? new Date(job.startTime).toLocaleString() : '—'}
                                    </td>

                                    {/* End Time */}
                                    <td style={{ padding: '16px', color: '#64748b', fontSize: '0.85rem' }}>
                                        {job.endTime ? (
                                            new Date(job.endTime).toLocaleString()
                                        ) : job.isLive ? (
                                            <span style={{
                                                color: '#3b82f6', display: 'flex',
                                                alignItems: 'center', gap: '4px',
                                            }}>
                                                <Loader2 size={12} style={{ animation: 'spin 1s linear infinite' }} />
                                                Running
                                            </span>
                                        ) : '—'}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>

                <style>{`
                    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
                `}</style>
            </div>
            {/* ── End content wrapper ── */}

        </div>
    );
};

export default BatchMonitoring;
