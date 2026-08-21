import React, { useState, useEffect, useRef, useCallback } from 'react';
import api from '../api/axios';

const POLL_MS = 30_000;   // poll every 30s when bell is closed
const FAST_MS = 5_000;    // poll every 5s when panel is open

const STATUS_COLORS = {
    COMPLETED: { dot: '#10b981', bg: '#ecfdf5', text: '#065f46', label: 'Completed' },
    FAILED:    { dot: '#ef4444', bg: '#fef2f2', text: '#991b1b', label: 'Failed'    },
    STARTED:   { dot: 'var(--primary)', bg: 'var(--wash)', text: 'var(--primary)', label: 'Running'   },
    ABANDONED: { dot: '#f59e0b', bg: '#fef9ec', text: '#92400e', label: 'Abandoned' },
    STOPPED:   { dot: '#94a3b8', bg: '#f1f5f9', text: '#475569', label: 'Stopped'   },
};

const fmtTime = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
};
const fmtDur = (start, end) => {
    if (!start || !end) return null;
    const s = Math.round((new Date(end) - new Date(start)) / 1000);
    if (s < 60) return `${s}s`;
    return `${Math.floor(s / 60)}m ${s % 60}s`;
};

const NotificationBell = () => {
    const [open, setOpen]       = useState(false);
    const [jobs, setJobs]       = useState([]);
    const [loading, setLoading] = useState(false);
    const [unread, setUnread]   = useState(0);
    const panelRef              = useRef(null);
    const seenRef               = useRef(new Set(JSON.parse(localStorage.getItem('acquira_seen_jobs') || '[]')));

    const fetchJobs = useCallback(async (quiet = false) => {
        if (!quiet) setLoading(true);
        try {
            const res = await api.get('/batch/jobs?size=10');
            const data = res.data || [];
            setJobs(data);

            // Count jobs finished since last seen
            const newUnseen = data.filter(j =>
                (j.status === 'COMPLETED' || j.status === 'FAILED') &&
                !seenRef.current.has(String(j.executionId))
            ).length;
            setUnread(newUnseen);
        } catch { /* silent */ }
        finally { if (!quiet) setLoading(false); }
    }, []);

    // Mark all as seen when panel opens
    const markSeen = useCallback(() => {
        jobs.forEach(j => seenRef.current.add(String(j.executionId)));
        localStorage.setItem('acquira_seen_jobs', JSON.stringify([...seenRef.current].slice(-100)));
        setUnread(0);
    }, [jobs]);

    useEffect(() => { fetchJobs(); }, []);

    // Polling
    useEffect(() => {
        const ms = open ? FAST_MS : POLL_MS;
        const id = setInterval(() => fetchJobs(true), ms);
        return () => clearInterval(id);
    }, [open, fetchJobs]);

    // Close on outside click
    useEffect(() => {
        const h = (e) => { if (panelRef.current && !panelRef.current.contains(e.target)) setOpen(false); };
        document.addEventListener('mousedown', h);
        return () => document.removeEventListener('mousedown', h);
    }, []);

    const handleOpen = () => {
        setOpen(v => {
            if (!v) { setTimeout(markSeen, 400); fetchJobs(); }
            return !v;
        });
    };

    const liveCount = jobs.filter(j => j.status === 'STARTED' || j.status === 'STARTING').length;

    return (
        <div ref={panelRef} style={{ position: 'relative' }}>
            {/* Bell button */}
            <button
                onClick={handleOpen}
                title="Batch job notifications"
                style={{
                    width: 32, height: 32, borderRadius: 8, display: 'flex', alignItems: 'center', justifyContent: 'center',
                    background: open ? 'var(--color-background-info)' : 'var(--color-background-secondary)',
                    border: '0.5px solid var(--color-border-tertiary)', cursor: 'pointer', position: 'relative',
                    transition: 'all 0.15s',
                }}
                onMouseEnter={e => e.currentTarget.style.background = 'var(--color-background-secondary)'}
                onMouseLeave={e => e.currentTarget.style.background = open ? 'var(--color-background-info)' : 'var(--color-background-secondary)'}
            >
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke={open ? 'var(--primary)' : 'var(--color-text-secondary)'} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.73 21a2 2 0 0 1-3.46 0" />
                </svg>

                {/* Unread badge */}
                {unread > 0 && (
                    <span style={{
                        position: 'absolute', top: -3, right: -3,
                        width: 16, height: 16, borderRadius: '50%',
                        background: '#ef4444', color: '#fff',
                        fontSize: 9, fontWeight: 700,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        border: '2px solid var(--color-background-primary)',
                    }}>{unread > 9 ? '9+' : unread}</span>
                )}

                {/* Live pulse */}
                {liveCount > 0 && unread === 0 && (
                    <span style={{
                        position: 'absolute', top: -2, right: -2,
                        width: 8, height: 8, borderRadius: '50%', background: 'var(--primary)',
                    }} />
                )}
            </button>

            {/* Dropdown panel */}
            {open && (
                <div style={{
                    position: 'absolute', top: 'calc(100% + 8px)', right: 0,
                    width: 340, maxHeight: 420, overflowY: 'auto',
                    background: 'var(--color-background-primary)',
                    border: '0.5px solid var(--color-border-tertiary)',
                    borderRadius: 12, zIndex: 500,
                    boxShadow: '0 8px 32px rgba(0,0,0,0.12)',
                }}>
                    {/* Header */}
                    <div style={{ padding: '12px 14px 10px', borderBottom: '0.5px solid var(--color-border-tertiary)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)' }}>
                            Batch Jobs
                            {liveCount > 0 && <span style={{ marginLeft: 8, fontSize: 10, fontWeight: 700, padding: '2px 7px', borderRadius: 10, background: 'var(--wash)', color: 'var(--primary)' }}>{liveCount} running</span>}
                        </span>
                        <button onClick={() => fetchJobs()} style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 11, color: 'var(--color-text-secondary)', padding: 0 }}>
                            Refresh
                        </button>
                    </div>

                    {/* Job list */}
                    {loading && jobs.length === 0 ? (
                        <div style={{ padding: '24px', textAlign: 'center', color: 'var(--color-text-tertiary)', fontSize: 12 }}>Loading…</div>
                    ) : jobs.length === 0 ? (
                        <div style={{ padding: '24px', textAlign: 'center', color: 'var(--color-text-tertiary)', fontSize: 12 }}>No recent batch jobs</div>
                    ) : jobs.map(job => {
                        const sc = STATUS_COLORS[job.status] || STATUS_COLORS.STOPPED;
                        const isNew = !seenRef.current.has(String(job.executionId)) && (job.status === 'COMPLETED' || job.status === 'FAILED');
                        const dur = fmtDur(job.startTime, job.endTime);
                        return (
                            <div key={job.executionId} style={{
                                padding: '10px 14px', borderBottom: '0.5px solid var(--color-border-tertiary)',
                                background: isNew ? 'var(--color-background-secondary)' : 'transparent',
                                display: 'flex', gap: 10, alignItems: 'flex-start',
                            }}>
                                <div style={{ width: 8, height: 8, borderRadius: '50%', background: sc.dot, marginTop: 5, flexShrink: 0 }} />
                                <div style={{ flex: 1, minWidth: 0 }}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}>
                                        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-text-primary)' }}>Job #{job.executionId}</span>
                                        <span style={{ fontSize: 10, padding: '1px 6px', borderRadius: 4, background: sc.bg, color: sc.text, fontWeight: 600, flexShrink: 0 }}>{sc.label}</span>
                                    </div>
                                    <div style={{ fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 2 }}>
                                        {fmtTime(job.startTime)}
                                        {dur && <span style={{ marginLeft: 8 }}>· {dur}</span>}
                                        {job.readCount > 0 && <span style={{ marginLeft: 8 }}>· {job.readCount.toLocaleString()} rows</span>}
                                    </div>
                                    {job.status === 'STARTED' && job.progress >= 0 && (
                                        <div style={{ marginTop: 6, height: 3, borderRadius: 2, background: 'var(--color-border-tertiary)', overflow: 'hidden' }}>
                                            <div style={{ height: '100%', background: 'var(--primary)', width: `${job.progress}%`, transition: 'width 0.5s' }} />
                                        </div>
                                    )}
                                </div>
                            </div>
                        );
                    })}

                    {/* Footer */}
                    <div style={{ padding: '8px 14px', borderTop: '0.5px solid var(--color-border-tertiary)' }}>
                        <a href="/ops/batch-logs" style={{ fontSize: 11, color: 'var(--primary)', textDecoration: 'none', fontWeight: 600 }}>
                            View all in Batch Monitoring →
                        </a>
                    </div>
                </div>
            )}
        </div>
    );
};

export default NotificationBell;
