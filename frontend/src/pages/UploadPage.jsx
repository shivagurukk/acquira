import React, { useState, useEffect } from 'react';
import FileDropzone from '../components/FileDropzone';
import FinancialLoader from '../components/FinancialLoader';
import PageHeader from '../components/PageHeader';
import { Upload, CheckCircle, AlertCircle, FileText, X, Zap, BarChart2, Activity } from 'lucide-react';
import api, { UPLOAD_TIMEOUT, isTimeoutError } from '../api/axios';
import useNotifications from '../hooks/useNotifications';

// Friendly labels for the batch step bean names returned by
// /api/batch/jobs/{id}/status (currentStep). Falls back to the raw name.
const STEP_LABELS = {
    ensurePartitionsStep: 'Preparing partitions',
    ensureMerchantPartitionsStep: 'Preparing',
    splitExcelStep: 'Splitting file',
    merchantSplitStep: 'Splitting file',
    cleanTargetDayStep: 'Clearing staging',
    cleanMerchantStagingStep: 'Clearing staging',
    masterIngestStep: 'Ingesting rows',
    merchantIngestStep: 'Ingesting merchants',
    autoCreateDimensionsStep: 'Resolving merchants',
    stagingToFactStep: 'Writing transactions',
    upsertAndSummarizeStep: 'Updating master data',
    populateSummaryStep: 'Building summaries',
    calculateBusinessMetricsStep: 'Computing metrics',
    calculateDailyDashboardMetricsStep: 'Finalizing dashboards',
};
const stepLabel = (name) => STEP_LABELS[name] || name || 'Processing';

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
            // Derive real progress from stepNumber/totalSteps when available,
            // falling back to the progress field, then 0. Previously this showed
            // a time-based fake percentage unrelated to actual batch progress.
            // Clamped: stepNumber can exceed a stale totalSteps.
            const realPct = uploadProgress.stepNumber && uploadProgress.totalSteps
                ? Math.min(100, Math.round((uploadProgress.stepNumber / uploadProgress.totalSteps) * 100))
                : (uploadProgress.progress >= 0 ? uploadProgress.progress : 0);
            // Accepted but not yet running: Spring Batch persists the execution as
            // STARTING and only then hands it to batchTaskExecutor, so a job waiting
            // for a free slot has zero step executions. Say that plainly rather than
            // implying work is underway — a backlog looked identical to a hang.
            if (!uploadProgress.currentStep && !uploadProgress.stepNumber) {
                setMsg('Queued — waiting for a free batch slot. Check Batch Monitoring for jobs already running.');
                setUploadPercent(0);
            } else {
                setMsg(`Processing... ${realPct}%`);
                setUploadPercent(realPct);
            }
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
                timeout: UPLOAD_TIMEOUT,
                onUploadProgress: (e) => setUploadPercent(Math.round((e.loaded * 100) / e.total)),
            });
            setUploadPercent(100);
            setJobDetails(response.data);
            setStatus('processing');
            setMsg('File uploaded. Processing...');
            if (response.data.jobId) subscribeToJob(response.data.jobId);
        } catch (err) {
            setStatus('error');
            // A timeout is NOT a failed upload — the server may have taken the
            // file and still be ingesting it. Telling the user to retry here is
            // how the same file gets loaded twice.
            if (isTimeoutError(err)) {
                setMsg('The server did not respond in time. The file may still be processing — '
                     + 'check Batch Monitoring before uploading it again.');
                return;
            }
            const errMsg = err.code === 'ERR_NETWORK'
                ? 'Batch service is not running. Please start acquira-core (port 8081) and retry.'
                : err.response?.data?.message || err.response?.data || err.message;
            setMsg(`Upload Error: ${typeof errMsg === 'object' ? JSON.stringify(errMsg) : errMsg}`);
        }
    };

    const reset = () => { setFile(null); setStatus(null); setMsg(''); setJobDetails(null); setShowSummary(false); setUploadPercent(0); };

    const stages = [
        { label: 'Splitting',   icon: FileText,  range: [0, 10],  stepKeys: ['split', 'partition', 'ensure'] },
        { label: 'Reading',     icon: Activity,  range: [10, 40], stepKeys: ['ingest', 'read', 'clean', 'staging'] },
        { label: 'Processing',  icon: Zap,       range: [40, 70], stepKeys: ['dimension', 'resolve', 'auto'] },
        { label: 'Loading',     icon: BarChart2, range: [70, 90], stepKeys: ['fact', 'upsert', 'write'] },
        { label: 'Summarizing', icon: BarChart2, range: [90, 100],stepKeys: ['summary', 'metric', 'dashboard', 'finalize'] },
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
                            border: '1px solid rgba(164, 78, 31,0.1)',
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
                                    background: 'var(--primary)',
                                    color: '#fff', border: 'none',
                                    borderRadius: '12px',
                                    fontWeight: 600, fontSize: '0.95rem',
                                    cursor: 'pointer', fontFamily: 'inherit',
                                    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                                    transition: 'all 0.15s ease',
                                }}
                                onMouseEnter={e => { e.currentTarget.style.background = 'var(--primary)'; e.currentTarget.style.transform = 'translateY(-1px)'; e.currentTarget.style.boxShadow = '0 4px 12px rgba(164, 78, 31,0.25)'; }}
                                onMouseLeave={e => { e.currentTarget.style.background = 'var(--primary)'; e.currentTarget.style.transform = ''; e.currentTarget.style.boxShadow = 'none'; }}
                            >
                                <Upload size={18} /> Process File
                            </button>
                        )}

                        {status === 'uploading' && (
                            <div>
                                <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 16 }}>
                                    <FinancialLoader />
                                </div>
                                <ProgressBar value={uploadPercent} label="Uploading" color="var(--primary)" />
                            </div>
                        )}

                        {status === 'processing' && jobDetails && (
                        <div>
                        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 16 }}>
                        <FinancialLoader />
                        </div>
                        {jobDetails.currentStep ? (
                        <div style={{
                        textAlign: 'center', marginBottom: 14,
                        fontSize: '0.9rem', fontWeight: 600, color: 'var(--text,#111827)',
                        }}>
                        {stepLabel(jobDetails.currentStep)}
                        {jobDetails.stepNumber && jobDetails.totalSteps
                        ? <span style={{ color: 'var(--text-muted,#9ca3af)', fontWeight: 500 }}>
                        {' '}· step {jobDetails.stepNumber} of {jobDetails.totalSteps}
                        </span>
                        : null}
                        </div>
                        ) : (
                        /* No step executions yet: the job is accepted but waiting for a
                           batch slot. Say so — msg is only rendered in the error state, so
                           without this the queued case shows a bare spinner at 0% and reads
                           as a hang. */
                        <div style={{
                        textAlign: 'center', marginBottom: 14,
                        fontSize: '0.9rem', fontWeight: 600, color: 'var(--text,#111827)',
                        }}>
                        Queued
                        <span style={{ display: 'block', marginTop: 4, fontSize: '0.78rem', fontWeight: 500, color: 'var(--text-muted,#9ca3af)' }}>
                        Waiting for a free batch slot — check Batch Monitoring for jobs already running.
                        </span>
                        </div>
                        )}
                        <StageTracker stages={stages} currentStepName={jobDetails.currentStep} stepNumber={jobDetails.stepNumber} totalSteps={jobDetails.totalSteps} progress={uploadPercent} />
                        <div style={{ marginTop: 16 }}>
                        <ProgressBar
                        value={uploadPercent}
                        label={jobDetails.currentStep ? stepLabel(jobDetails.currentStep) : 'Overall progress'}
                        color="var(--primary)"
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

const ProgressBar = ({ value, label, color = 'var(--primary)' }) => (
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

const StageTracker = ({ stages, currentStepName, stepNumber, totalSteps, progress }) => {
    // Determine the active stage from the real currentStepName when available,
    // falling back to the progress-based range detection.
    //
    // A job that Spring Batch has accepted but not yet given a thread to reports
    // no step executions at all, so currentStepName is undefined and progress is
    // 0 — which the range fallback used to resolve to stage 0, lighting up
    // "Splitting" for a job that had not started. That made a queued job
    // indistinguishable from one genuinely splitting a file. Light nothing
    // instead; the caller shows an explicit "Queued" message.
    const notStarted = !currentStepName && !stepNumber;
    const activeIdx = notStarted
        ? -1
        : currentStepName
            ? stages.findIndex(s => s.stepKeys && s.stepKeys.some(k => currentStepName.toLowerCase().includes(k)))
            : stages.findIndex(s => progress >= s.range[0] && progress < s.range[1]);
    return (
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 4, marginBottom: 16 }}>
            {stages.map((s, i) => {
                const done   = stepNumber ? i < activeIdx : progress >= s.range[1];
                const active = i === activeIdx;
                const StageIcon = s.icon;
                return (
                    <div key={s.label} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                        <div style={{
                            width: 32, height: 32, borderRadius: '50%',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            background: done ? '#059669' : active ? 'var(--primary)' : 'var(--bg-subtle,#f3f4f6)',
                            border: active ? '2px solid rgba(164, 78, 31,0.2)' : done ? 'none' : '1px solid var(--border,#e5e7eb)',
                            transition: 'all 0.3s',
                        }}>
                            {done
                                ? <CheckCircle size={14} color="#fff" />
                                : <StageIcon size={13} color={active ? '#fff' : 'var(--text-muted,#9ca3af)'} />
                            }
                        </div>
                        <span style={{
                            fontSize: '10px', fontWeight: active ? 600 : 400,
                            color: done ? '#059669' : active ? 'var(--primary)' : 'var(--text-muted,#9ca3af)',
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
        : { bg: 'var(--brand-50,#eff6ff)',   border: 'rgba(164, 78, 31,0.15)', title: 'var(--primary)', sub: 'var(--primary)' };

    const statusLabel = isRunning ? 'Job in progress' : `Job ${jobDetails.status}`;
    const exitText = isRunning
        ? 'Still processing — this will update automatically.'
        : `Exit code: ${jobDetails.exitCode || 'N/A'}`;

    const elapsed = jobDetails.endTime && jobDetails.startTime
        ? ((new Date(jobDetails.endTime) - new Date(jobDetails.startTime)) / 1000).toFixed(1) + 's'
        : '—';

    const dq = jobDetails.dataQuality;
    const dqUnresolvedPct = dq && dq.total > 0
        ? Math.round((dq.unresolvedMerchant / dq.total) * 100)
        : 0;

    // Sales-agent reassignment summary from a merchant master upload. Only shown
    // when the file actually moved merchants or raised a warning — an upload that
    // changed no assignments has nothing to report here.
    const ra = jobDetails.salesReassignment;
    const raHasNews = ra && (ra.reassigned > 0 || ra.conflicts > 0 || ra.unknownAgents > 0);

    const stats = [
        { label: 'Rows read',   value: (jobDetails.readCount  || 0).toLocaleString(), color: 'var(--primary)' },
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

                {dq && (
                    <div style={{
                        padding: '14px 16px', borderRadius: '12px', marginBottom: 16,
                        background: 'var(--bg-subtle,#f9fafb)',
                        border: '1px solid var(--border,#e5e7eb)',
                    }}>
                        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
                            <span style={{ fontSize: '0.8rem', fontWeight: 700, color: 'var(--text,#111827)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                                Data quality
                            </span>
                            <span style={{
                                fontSize: '0.68rem', fontWeight: 700, padding: '2px 8px', borderRadius: 999,
                                background: dq.loadMode === 'APPEND' ? 'var(--wash)' : '#f3f4f6',
                                color: dq.loadMode === 'APPEND' ? 'var(--primary)' : '#6b7280',
                            }}>
                                {dq.loadMode === 'APPEND' ? 'APPEND (scheme-scoped)' : 'REPLACE'}
                            </span>
                        </div>

                        <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', fontSize: '0.8rem', color: 'var(--text-secondary,#6b7280)' }}>
                            <span><strong style={{ color: 'var(--text,#111827)' }}>{(dq.total || 0).toLocaleString()}</strong> rows loaded</span>
                            <span><strong style={{ color: 'var(--text,#111827)' }}>{dq.dates || 0}</strong> date{dq.dates === 1 ? '' : 's'}</span>
                            <span>
                                <strong style={{ color: dq.unresolvedMerchant > 0 ? '#b45309' : '#059669' }}>
                                    {(dq.unresolvedMerchant || 0).toLocaleString()}
                                </strong> unresolved{dq.unresolvedMerchant > 0 ? ` (${dqUnresolvedPct}%)` : ''}
                            </span>
                        </div>

                        {Array.isArray(dq.schemes) && dq.schemes.length > 0 && (
                            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10 }}>
                                {dq.schemes.map(s => (
                                    <span key={s} style={{
                                        fontSize: '0.7rem', fontWeight: 600, padding: '2px 8px', borderRadius: 6,
                                        background: 'var(--bg-card,#fff)', border: '1px solid var(--border,#e5e7eb)',
                                        color: 'var(--text-secondary,#6b7280)',
                                    }}>{s}</span>
                                ))}
                            </div>
                        )}

                        {dq.unresolvedMerchant > 0 && (
                            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginTop: 12, padding: '10px 12px', borderRadius: 8, background: '#fffbeb', border: '1px solid rgba(180,83,9,0.15)' }}>
                                <AlertCircle size={15} color="#b45309" style={{ flexShrink: 0, marginTop: 1 }} />
                                <span style={{ fontSize: '0.76rem', color: '#92400e', lineHeight: 1.45 }}>
                                    {dqUnresolvedPct}% of rows couldn't be matched to a merchant (SID not in the master data).
                                    Upload the merchant master file for these stores, then re-run, or check for an SID format mismatch.
                                </span>
                            </div>
                        )}
                    </div>
                )}

                {raHasNews && (
                    <div style={{
                        padding: '14px 16px', borderRadius: '12px', marginBottom: 16,
                        background: 'var(--bg-subtle,#f9fafb)',
                        border: '1px solid var(--border,#e5e7eb)',
                    }}>
                        <div style={{ fontSize: '0.8rem', fontWeight: 700, color: 'var(--text,#111827)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 10 }}>
                            Sales agent changes
                        </div>

                        <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', fontSize: '0.8rem', color: 'var(--text-secondary,#6b7280)' }}>
                            <span><strong style={{ color: 'var(--text,#111827)' }}>{(ra.reassigned || 0).toLocaleString()}</strong> merchant{ra.reassigned === 1 ? '' : 's'} reassigned</span>
                            {ra.conflicts > 0 && (
                                <span><strong style={{ color: '#b45309' }}>{ra.conflicts}</strong> conflicting</span>
                            )}
                            {ra.unknownAgents > 0 && (
                                <span><strong style={{ color: '#b45309' }}>{ra.unknownAgents}</strong> new agent{ra.unknownAgents === 1 ? '' : 's'}</span>
                            )}
                        </div>

                        {ra.reassigned > 0 && (
                            <div style={{ fontSize: '0.76rem', color: 'var(--text-secondary,#6b7280)', marginTop: 10, lineHeight: 1.45 }}>
                                Each merchant's full transaction history now counts towards its new agent. The previous
                                assignment is kept in the merchant's assignment history.
                            </div>
                        )}

                        {Array.isArray(ra.warnings) && ra.warnings.length > 0 && (
                            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginTop: 12, padding: '10px 12px', borderRadius: 8, background: '#fffbeb', border: '1px solid rgba(180,83,9,0.15)' }}>
                                <AlertCircle size={15} color="#b45309" style={{ flexShrink: 0, marginTop: 1 }} />
                                <ul style={{ margin: 0, paddingLeft: 16, fontSize: '0.76rem', color: '#92400e', lineHeight: 1.5 }}>
                                    {ra.warnings.map((w, i) => <li key={i}>{w}</li>)}
                                </ul>
                            </div>
                        )}
                    </div>
                )}

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
                        : <Activity size={18} color="var(--primary)" />}
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
