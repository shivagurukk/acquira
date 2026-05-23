import React, { useState, useEffect } from 'react';
import FileDropzone from '../components/FileDropzone';
import FinancialLoader from '../components/FinancialLoader';
import PageHeader from '../components/PageHeader';
import { Upload, CheckCircle, AlertCircle, FileText, X, Zap, BarChart2, Activity } from 'lucide-react';
import api from '../api/axios';
import useNotifications from '../hooks/useNotifications';

const UploadPage = () => {
    const [file, setFile] = useState(null);
    const [status, setStatus] = useState(null);
    const [msg, setMsg] = useState('');
    const [jobDetails, setJobDetails] = useState(null);
    const [showSummary, setShowSummary] = useState(false);
    const [uploadPercent, setUploadPercent] = useState(0);

    const { uploadProgress, subscribeToJob } = useNotifications();

    useEffect(() => {
        if (!uploadProgress) return;
        setJobDetails(uploadProgress);
        const s = (uploadProgress.status || '').toUpperCase();
        if (s === 'COMPLETED' || s === 'FINISHED' || (uploadProgress.progress === 100 && s !== 'FAILED')) {
            setStatus('success'); setMsg('Processing Complete!'); setShowSummary(true);
        } else if (s === 'FAILED' || s === 'ABANDONED') {
            setStatus('error');
            setMsg(uploadProgress.exitCode === 'FAILED' ? 'Processing Failed — check Batch Logs.' : 'Processing Failed');
            setShowSummary(true);
        } else {
            setStatus('processing');
            setMsg(`Processing... ${uploadProgress.progress >= 0 ? uploadProgress.progress : 0}%`);
        }
    }, [uploadProgress]);

    const uploadFile = async () => {
        if (!file) return;
        const formData = new FormData();
        formData.append('file', file);
        setStatus('uploading'); setMsg('Uploading file...'); setJobDetails(null); setUploadPercent(0); setShowSummary(false);
        try {
            const response = await api.post('/upload', formData, {
                headers: { 'Content-Type': 'multipart/form-data' },
                onUploadProgress: (e) => setUploadPercent(Math.round((e.loaded * 100) / e.total)),
            });
            setUploadPercent(100);
            setJobDetails(response.data);
            setStatus('processing');
            setMsg('File uploaded. Processing...');
            if (response.data.jobId) subscribeToJob(response.data.jobId);
        } catch (err) {
            setStatus('error');
            const errMsg = err.code === 'ERR_NETWORK'
                ? 'Batch service is not running. Please start acquira-core (port 8081) and retry.'
                : err.response?.data?.message || err.response?.data || err.message;
            setMsg(`Upload Error: ${typeof errMsg === 'object' ? JSON.stringify(errMsg) : errMsg}`);
        }
    };

    const reset = () => { setFile(null); setStatus(null); setMsg(''); setJobDetails(null); setShowSummary(false); setUploadPercent(0); };

    const stages = [
        { label: 'Splitting',   icon: FileText,  range: [0, 10],  desc: 'Splitting file into chunks' },
        { label: 'Reading',     icon: Activity,  range: [10, 40], desc: 'Validating rows' },
        { label: 'Processing',  icon: Zap,       range: [40, 70], desc: 'Resolving merchants' },
        { label: 'Loading',     icon: BarChart2, range: [70, 90], desc: 'Writing to database' },
        { label: 'Summarizing', icon: BarChart2, range: [90, 100],desc: 'Building summaries' },
    ];

    return (
        <div style={{ background: 'var(--bg,#f9fafb)', minHeight: '100vh' }}>
            <PageHeader
                title="Data Upload"
                subtitle="Upload merchant master or transaction files for processing"
                icon={Upload}
            />

            <div style={{ padding: '28px', maxWidth: 780, margin: '0 auto' }}>

                {/* Drop Zone Card */}
                <div style={{
                    background: 'var(--bg-card,#fff)',
                    border: '1px solid var(--border,#e5e7eb)',
                    borderRadius: 'var(--radius-xl,18px)',
                    overflow: 'hidden',
                }}>
                    {/* Card header */}
                    <div style={{
                        padding: '24px 28px',
                        display: 'flex', alignItems: 'center', gap: 14,
                        borderBottom: '1px solid var(--border-light,#f3f4f6)',
                    }}>
                        <div style={{
                            width: 44, height: 44, borderRadius: 12,
                            background: 'var(--brand-50,#eff6ff)',
                            border: '1px solid rgba(37,99,235,0.1)',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                        }}>
                            <FileText size={20} style={{ color: 'var(--brand,#2563eb)' }} strokeWidth={1.8} />
                        </div>
                        <div>
                            <h2 style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--text,#111827)', margin: 0, lineHeight: 1.3, letterSpacing: '-0.02em' }}>
                                Universal File Uploader
                            </h2>
                            <p style={{ fontSize: '0.82rem', color: 'var(--text-muted,#9ca3af)', margin: '3px 0 0' }}>
                                Supports Excel (.xlsx) and CSV files · Auto-detects file type
                            </p>
                        </div>
                    </div>

                    {/* Body */}
                    <div style={{ padding: '28px' }}>
                        {!status && <FileDropzone type="unified" onFileSelect={setFile} />}

                        {file && !status && (
                            <button
                                onClick={uploadFile}
                                style={{
                                    marginTop: 20, width: '100%', padding: '14px',
                                    background: '#2563eb',
                                    color: '#fff', border: 'none',
                                    borderRadius: '12px',
                                    fontWeight: 600, fontSize: '0.95rem',
                                    cursor: 'pointer', fontFamily: 'inherit',
                                    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                                    transition: 'all 0.15s ease',
                                }}
                                onMouseEnter={e => { e.currentTarget.style.background = '#1d4ed8'; e.currentTarget.style.transform = 'translateY(-1px)'; e.currentTarget.style.boxShadow = '0 4px 12px rgba(37,99,235,0.25)'; }}
                                onMouseLeave={e => { e.currentTarget.style.background = '#2563eb'; e.currentTarget.style.transform = ''; e.currentTarget.style.boxShadow = 'none'; }}
                            >
                                <Upload size={18} /> Process File
                            </button>
                        )}

                        {status === 'uploading' && (
                            <div>
                                <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 16 }}>
                                    <FinancialLoader />
                                </div>
                                <ProgressBar value={uploadPercent} label="Uploading" color="#2563eb" />
                            </div>
                        )}

                        {status === 'processing' && jobDetails && (
                            <div>
                                <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 16 }}>
                                    <FinancialLoader />
                                </div>
                                <StageTracker stages={stages} progress={jobDetails.progress || 0} />
                                <div style={{ marginTop: 16 }}>
                                    <ProgressBar
                                        value={Math.max(0, jobDetails.progress || 0)}
                                        label="Overall progress"
                                        color="#2563eb"
                                    />
                                    <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 10, fontSize: '0.78rem', color: 'var(--text-secondary,#6b7280)' }}>
                                        <span>Job: {jobDetails.jobId || jobDetails.executionId || '—'}</span>
                                        <span>{(jobDetails.readCount || 0).toLocaleString()} / {jobDetails.totalRows > 0 ? jobDetails.totalRows.toLocaleString() : '...'} rows</span>
                                    </div>
                                    {jobDetails.readCount > 0 && jobDetails.startTime && (
                                        <div style={{ marginTop: 4, fontSize: '0.72rem', color: 'var(--text-muted,#9ca3af)' }}>
                                            ⚡ {Math.round(jobDetails.readCount / Math.max(1, (Date.now() - new Date(jobDetails.startTime).getTime()) / 1000))} rows/sec
                                        </div>
                                    )}
                                </div>
                            </div>
                        )}

                        {status === 'success' && !showSummary && (
                            <StatusBanner type="success" message="Processing Complete!" onView={() => setShowSummary(true)} />
                        )}

                        {status === 'error' && <StatusBanner type="error" message={msg} />}

                        {(status === 'success' || status === 'error') && (
                            <button
                                onClick={reset}
                                style={{
                                    marginTop: 14, width: '100%', padding: '11px',
                                    background: 'none',
                                    border: '1px solid var(--border,#e5e7eb)',
                                    borderRadius: '10px',
                                    color: 'var(--text-secondary,#6b7280)',
                                    cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500,
                                    fontFamily: 'inherit', transition: 'all 0.15s',
                                }}
                                onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--brand,#2563eb)'; e.currentTarget.style.color = 'var(--brand,#2563eb)'; }}
                                onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border,#e5e7eb)'; e.currentTarget.style.color = 'var(--text-secondary,#6b7280)'; }}
                            >
                                Upload Another File
                            </button>
                        )}
                    </div>
                </div>

                {/* Tips */}
                {!status && (
                    <div style={{
                        marginTop: 16, padding: '16px 20px',
                        background: 'var(--bg-card,#fff)',
                        border: '1px solid var(--border,#e5e7eb)',
                        borderRadius: 'var(--radius-lg,14px)',
                        display: 'flex', gap: 28, flexWrap: 'wrap',
                    }}>
                        {[
                            ['Merchant Master', 'MID, name, category, contact info'],
                            ['Transaction Log', 'Date, MID, amount, scheme, channel'],
                            ['Max file size', 'Up to 2 GB supported'],
                        ].map(([title, desc]) => (
                            <div key={title} style={{ flex: 1, minWidth: 140 }}>
                                <p style={{ fontSize: '0.72rem', fontWeight: 600, color: 'var(--brand,#2563eb)', margin: '0 0 3px', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                                    {title}
                                </p>
                                <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary,#6b7280)', margin: 0, lineHeight: 1.5 }}>
                                    {desc}
                                </p>
                            </div>
                        ))}
                    </div>
                )}
            </div>

            {showSummary && (jobDetails || uploadProgress) && (
                <SummaryModal
                    jobDetails={uploadProgress || jobDetails}
                    onClose={() => { setShowSummary(false); reset(); }}
                />
            )}
        </div>
    );
};

/* ── Sub-components ─────────────────────────────────────────── */

const ProgressBar = ({ value, label, color = '#2563eb' }) => (
    <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6, fontSize: '0.78rem', color: 'var(--text-secondary,#6b7280)' }}>
            <span>{label}</span>
            <span style={{ fontWeight: 600, color: 'var(--text,#111827)', fontVariantNumeric: 'tabular-nums' }}>{value}%</span>
        </div>
        <div style={{ height: 5, borderRadius: 999, background: 'var(--bg-subtle,#f3f4f6)', overflow: 'hidden' }}>
            <div style={{
                height: '100%', borderRadius: 999,
                background: color, transition: 'width 0.4s ease',
                width: `${value}%`,
            }} />
        </div>
    </div>
);

const StageTracker = ({ stages, progress }) => {
    const activeIdx = stages.findIndex(s => progress >= s.range[0] && progress < s.range[1]);
    return (
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 4, marginBottom: 16 }}>
            {stages.map((s, i) => {
                const done   = progress >= s.range[1];
                const active = i === activeIdx;
                const StageIcon = s.icon;
                return (
                    <div key={s.label} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                        <div style={{
                            width: 32, height: 32, borderRadius: '50%',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            background: done ? '#059669' : active ? '#2563eb' : 'var(--bg-subtle,#f3f4f6)',
                            border: active ? '2px solid rgba(37,99,235,0.2)' : done ? 'none' : '1px solid var(--border,#e5e7eb)',
                            transition: 'all 0.3s',
                        }}>
                            {done
                                ? <CheckCircle size={14} color="#fff" />
                                : <StageIcon size={13} color={active ? '#fff' : 'var(--text-muted,#9ca3af)'} />
                            }
                        </div>
                        <span style={{
                            fontSize: '10px', fontWeight: active ? 600 : 400,
                            color: done ? '#059669' : active ? '#2563eb' : 'var(--text-muted,#9ca3af)',
                            textAlign: 'center',
                        }}>
                            {s.label}
                        </span>
                    </div>
                );
            })}
        </div>
    );
};

const StatusBanner = ({ type, message, onView }) => {
    const isSuccess = type === 'success';
    return (
        <div style={{
            display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px',
            borderRadius: '12px',
            background: isSuccess ? 'var(--success-bg,#ecfdf5)' : 'var(--danger-bg,#fef2f2)',
            border: `1px solid ${isSuccess ? 'rgba(5,150,105,0.15)' : 'rgba(220,38,38,0.15)'}`,
        }}>
            {isSuccess
                ? <CheckCircle size={18} color="#059669" />
                : <AlertCircle size={18} color="#dc2626" />
            }
            <span style={{ flex: 1, fontSize: '0.85rem', fontWeight: 500, color: isSuccess ? '#065f46' : '#991b1b' }}>
                {message}
            </span>
            {isSuccess && onView && (
                <button onClick={onView}
                    style={{ fontSize: '0.82rem', fontWeight: 600, color: '#059669', background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'inherit', padding: 0, flexShrink: 0 }}>
                    View Summary →
                </button>
            )}
        </div>
    );
};

const SummaryModal = ({ jobDetails, onClose }) => {
    const rawStatus = (jobDetails.status || '').toUpperCase();
    const isSuccess = rawStatus === 'COMPLETED';
    const isFailed  = rawStatus === 'FAILED' || rawStatus === 'ABANDONED'
                   || rawStatus === 'STOPPED' || rawStatus === 'POLL_ERROR'
                   || rawStatus === 'TIMEOUT';
    const isRunning = !isSuccess && !isFailed;  // STARTED, STARTING, or unknown

    // Three-state theme: running = neutral blue, success = green, failed = red.
    const theme = isSuccess
        ? { bg: 'var(--success-bg,#ecfdf5)', border: 'rgba(5,150,105,0.15)', title: '#065f46', sub: '#047857' }
        : isFailed
        ? { bg: 'var(--danger-bg,#fef2f2)',  border: 'rgba(220,38,38,0.15)', title: '#991b1b', sub: '#b91c1c' }
        : { bg: 'var(--brand-50,#eff6ff)',   border: 'rgba(37,99,235,0.15)', title: '#1e40af', sub: '#1d4ed8' };

    const statusLabel = isRunning ? 'Job in progress' : `Job ${jobDetails.status}`;
    const exitText = isRunning
        ? 'Still processing — this will update automatically.'
        : `Exit code: ${jobDetails.exitCode || 'N/A'}`;

    const elapsed = jobDetails.endTime && jobDetails.startTime
        ? ((new Date(jobDetails.endTime) - new Date(jobDetails.startTime)) / 1000).toFixed(1) + 's'
        : '—';

    const stats = [
        { label: 'Rows read',   value: (jobDetails.readCount  || 0).toLocaleString(), color: '#2563eb' },
        { label: 'Rows written',value: (jobDetails.writeCount || 0).toLocaleString(), color: '#059669' },
        { label: 'Skipped',     value: (jobDetails.skipCount  || 0).toLocaleString(), color: '#d97706' },
        { label: 'Time taken',  value: elapsed,                                        color: '#7c3aed' },
    ];

    return (
        <div style={{
            position: 'fixed', inset: 0, zIndex: 9999,
            background: 'rgba(0,0,0,0.4)',
            backdropFilter: 'blur(6px)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            padding: 20,
        }}>
            <div style={{
                background: 'var(--bg-card,#fff)',
                borderRadius: '18px',
                padding: 32,
                width: '100%', maxWidth: 480,
                boxShadow: '0 24px 48px -8px rgba(0,0,0,0.2)',
                border: '1px solid var(--border,#e5e7eb)',
                animation: 'pageIn 0.2s ease-out',
            }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
                    <h2 style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--text,#111827)', margin: 0, letterSpacing: '-0.02em' }}>
                        Batch Summary
                    </h2>
                    <button onClick={onClose} style={{
                        background: 'none', border: 'none', cursor: 'pointer', padding: 6,
                        color: 'var(--text-muted,#9ca3af)', borderRadius: 8, transition: 'all 0.15s',
                    }}
                    onMouseEnter={e => { e.currentTarget.style.background = 'var(--bg-subtle,#f3f4f6)'; e.currentTarget.style.color = 'var(--text,#111827)'; }}
                    onMouseLeave={e => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = 'var(--text-muted,#9ca3af)'; }}
                    >
                        <X size={18} />
                    </button>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 18 }}>
                    {stats.map(({ label, value, color }) => (
                        <div key={label} style={{
                            padding: '16px 18px',
                            background: 'var(--bg-subtle,#f3f4f6)',
                            borderRadius: '14px',
                            border: '1px solid var(--border-light,#f3f4f6)',
                        }}>
                            <p style={{ fontSize: '0.72rem', color: 'var(--text-muted,#9ca3af)', margin: '0 0 6px', fontWeight: 500 }}>
                                {label}
                            </p>
                            <p style={{ fontSize: '1.4rem', fontWeight: 700, color, margin: 0, letterSpacing: '-0.03em' }}>
                                {value}
                            </p>
                        </div>
                    ))}
                </div>

                <div style={{
                    display: 'flex', alignItems: 'center', gap: 10, padding: '13px 16px',
                    borderRadius: '12px',
                    background: theme.bg,
                    border: `1px solid ${theme.border}`,
                    marginBottom: 22,
                }}>
                    {isSuccess
                        ? <CheckCircle size={18} color="#059669" />
                        : isFailed
                        ? <AlertCircle size={18} color="#dc2626" />
                        : <Activity size={18} color="#2563eb" />}
                    <div>
                        <p style={{ margin: 0, fontWeight: 600, fontSize: '0.88rem', color: theme.title }}>
                            {statusLabel}
                        </p>
                        <p style={{ margin: 0, fontSize: '0.75rem', color: theme.sub }}>
                            {exitText}
                        </p>
                    </div>
                </div>

                <button
                    onClick={onClose}
                    style={{
                        width: '100%', padding: '12px',
                        background: 'var(--text,#111827)', color: '#fff',
                        border: 'none', borderRadius: '12px',
                        fontWeight: 600, fontSize: '0.9rem', cursor: 'pointer', fontFamily: 'inherit',
                        transition: 'opacity 0.15s',
                    }}
                    onMouseEnter={e => e.currentTarget.style.opacity = '0.85'}
                    onMouseLeave={e => e.currentTarget.style.opacity = '1'}
                >
                    Close
                </button>
            </div>
        </div>
    );
};

export default UploadPage;
