import React, { useState, useEffect, useRef } from 'react';
import { Activity, CheckCircle, XCircle, Clock, RefreshCw, Loader2, Wifi, WifiOff } from 'lucide-react';
import api from '../api/axios';

const BatchMonitoring = () => {
    const [jobs, setJobs] = useState([]);
    const [liveJobs, setLiveJobs] = useState({});
    const [loading, setLoading] = useState(true);
    const [sseConnected, setSseConnected] = useState(false);
    const eventSourceRef = useRef(null);
    const retryTimeoutRef = useRef(null);

    const fetchJobs = async () => {
        setLoading(true);
        try {
            const res = await api.get('/batch/jobs?size=20');
            setJobs(res.data);
        } catch (error) {
            console.error("Failed to fetch jobs", error);
        } finally {
            setLoading(false);
        }
    };

    const connectSSE = () => {
        // Close existing connection
        if (eventSourceRef.current) {
            eventSourceRef.current.close();
        }

        const token = localStorage.getItem('token');
        const tenantId = localStorage.getItem('defaultTenantId');

        // Use EventSource with token in query param
        // In production, you may want to use fetch-event-source for proper headers
        const url = `/api/batch/jobs/live?token=${encodeURIComponent(token)}&tenantId=${tenantId}`;
        const es = new EventSource(url);

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
            // Retry after 5s
            retryTimeoutRef.current = setTimeout(connectSSE, 5000);
        };

        es.onopen = () => {
            setSseConnected(true);
        };

        eventSourceRef.current = es;
    };

    useEffect(() => {
        fetchJobs();
        connectSSE();

        // Fallback polling every 30s (in case SSE fails)
        const interval = setInterval(fetchJobs, 30000);

        return () => {
            clearInterval(interval);
            if (eventSourceRef.current) eventSourceRef.current.close();
            if (retryTimeoutRef.current) clearTimeout(retryTimeoutRef.current);
        };
    }, []);

    // Merge live data into historical list
    const mergedJobs = jobs.map(job => {
        const live = liveJobs[job.executionId];
        return live ? { ...job, ...live, isLive: true } : job;
    });

    // Also add any live jobs not yet in history
    const historyIds = new Set(jobs.map(j => j.executionId));
    const extraLiveJobs = Object.values(liveJobs).filter(j => !historyIds.has(j.executionId)).map(j => ({ ...j, isLive: true }));
    const allJobs = [...extraLiveJobs, ...mergedJobs];

    const getStatusBadge = (status, isLive) => {
        const colors = {
            COMPLETED: { bg: '#dcfce7', text: '#166534' },
            FAILED: { bg: '#fee2e2', text: '#991b1b' },
            STARTED: { bg: '#dbeafe', text: '#1e40af' },
            STARTING: { bg: '#dbeafe', text: '#1e40af' },
        };
        const c = colors[status] || colors.STARTED;
        return (
            <span style={{
                padding: '4px 10px', borderRadius: '999px', fontSize: '12px', fontWeight: 600,
                background: c.bg, color: c.text, display: 'inline-flex', alignItems: 'center', gap: '4px',
            }}>
                {isLive && status === 'STARTED' && <Loader2 size={12} style={{ animation: 'spin 1s linear infinite' }} />}
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
        <div style={{ padding: '24px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: 'bold', color: '#0f172a' }}>Batch Operations</h1>
                    <p style={{ color: '#64748b', display: 'flex', alignItems: 'center', gap: '6px', marginTop: '4px' }}>
                        Monitor transaction processing jobs
                        {sseConnected ? (
                            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', color: '#16a34a', fontSize: '0.8rem' }}>
                                <Wifi size={12} /> Live
                            </span>
                        ) : (
                            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', color: '#94a3b8', fontSize: '0.8rem' }}>
                                <WifiOff size={12} /> Polling
                            </span>
                        )}
                    </p>
                </div>
                <button onClick={fetchJobs} style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 16px', background: 'white', border: '1px solid #e2e8f0', borderRadius: '6px', cursor: 'pointer' }}>
                    <RefreshCw size={16} /> Refresh
                </button>
            </div>

            <div style={{ background: 'white', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)', overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0' }}>
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
                            <tr><td colSpan="6" style={{ padding: '24px', textAlign: 'center', color: '#94a3b8' }}>Loading...</td></tr>
                        ) : allJobs.length === 0 ? (
                            <tr><td colSpan="6" style={{ padding: '24px', textAlign: 'center', color: '#94a3b8' }}>No batch jobs found</td></tr>
                        ) : allJobs.map((job) => (
                            <tr key={job.executionId} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                <td style={{ padding: '16px', fontWeight: 500 }}>#{job.executionId}</td>
                                <td style={{ padding: '16px' }}>
                                    {getStatusBadge(job.status, job.isLive)}
                                </td>
                                <td style={{ padding: '16px', minWidth: '200px' }}>
                                    {job.isLive && job.status === 'STARTED' ? (
                                        <div>
                                            {/* Progress bar */}
                                            <div style={{
                                                height: '8px', borderRadius: '4px', background: '#e2e8f0',
                                                overflow: 'hidden', marginBottom: '4px',
                                            }}>
                                                <div style={{
                                                    height: '100%', borderRadius: '4px',
                                                    background: 'linear-gradient(90deg, #3b82f6, #1d4ed8)',
                                                    width: `${job.progress >= 0 ? job.progress : 0}%`,
                                                    transition: 'width 0.5s ease',
                                                }} />
                                            </div>
                                            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem', color: '#64748b' }}>
                                                <span>{job.progress >= 0 ? `${job.progress}%` : 'Processing...'}</span>
                                                {job.estimatedSecondsRemaining > 0 && (
                                                    <span>~{formatTime(job.estimatedSecondsRemaining)} left</span>
                                                )}
                                            </div>
                                        </div>
                                    ) : job.status === 'COMPLETED' ? (
                                        <span style={{ color: '#16a34a', fontWeight: 500, fontSize: '0.85rem' }}>100% ✓</span>
                                    ) : job.status === 'FAILED' ? (
                                        <span style={{ color: '#dc2626', fontSize: '0.85rem' }}>Failed</span>
                                    ) : (
                                        <span style={{ color: '#94a3b8', fontSize: '0.85rem' }}>—</span>
                                    )}
                                </td>
                                <td style={{ padding: '16px' }}>
                                    {(job.readCount !== undefined) ? (
                                        <div style={{ fontSize: '0.85rem' }}>
                                            <span style={{ fontWeight: 500 }}>{(job.readCount || 0).toLocaleString()}</span>
                                            <span style={{ color: '#94a3b8' }}> / {(job.totalRows || '?').toLocaleString()}</span>
                                            {job.skipCount > 0 && (
                                                <span style={{ color: '#f59e0b', marginLeft: '6px', fontSize: '0.75rem' }}>({job.skipCount} skipped)</span>
                                            )}
                                        </div>
                                    ) : <span style={{ color: '#94a3b8' }}>—</span>}
                                </td>
                                <td style={{ padding: '16px', color: '#64748b', fontSize: '0.85rem' }}>
                                    {job.startTime ? new Date(job.startTime).toLocaleString() : '—'}
                                </td>
                                <td style={{ padding: '16px', color: '#64748b', fontSize: '0.85rem' }}>
                                    {job.endTime ? new Date(job.endTime).toLocaleString() : job.isLive ? (
                                        <span style={{ color: '#3b82f6', display: 'flex', alignItems: 'center', gap: '4px' }}>
                                            <Loader2 size={12} style={{ animation: 'spin 1s linear infinite' }} /> Running
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
    );
};

const thStyle = { padding: '16px', textAlign: 'left', fontSize: '12px', color: '#64748b', fontWeight: 600 };

export default BatchMonitoring;
