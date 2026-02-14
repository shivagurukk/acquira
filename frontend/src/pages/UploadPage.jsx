import React, { useState, useEffect } from 'react';
import FileDropzone from '../components/FileDropzone';
import FinancialLoader from '../components/FinancialLoader';
import { Upload, CheckCircle, AlertCircle, FileText, X } from 'lucide-react';
import api from '../api/axios';
import useNotifications from '../hooks/useNotifications';
import './UploadPage.css';

const UploadPage = () => {
    const [file, setFile] = useState(null);
    const [status, setStatus] = useState(null); // idle, uploading, processing, success, error
    const [msg, setMsg] = useState("");
    const [jobDetails, setJobDetails] = useState(null);
    const [showSummary, setShowSummary] = useState(false);
    const [uploadPercent, setUploadPercent] = useState(0);

    const { uploadProgress, isConnected, subscribeToJob } = useNotifications();

    // React to SSE progress updates
    useEffect(() => {
        if (!uploadProgress) return;

        setJobDetails(uploadProgress);
        const s = (uploadProgress.status || '').toUpperCase();

        if (s === 'COMPLETED' || s === 'FINISHED' || (uploadProgress.progress === 100 && s !== 'FAILED')) {
            setStatus('success');
            setMsg('Processing Complete!');
            setShowSummary(true);
        } else if (s === 'FAILED' || s === 'ABANDONED') {
            setStatus('error');
            setMsg(uploadProgress.exitCode === 'FAILED'
                ? 'Processing Failed — check Batch Logs for details.'
                : 'Processing Failed');
            setShowSummary(true);
        } else {
            setStatus('processing');
            const pct = uploadProgress.progress >= 0 ? uploadProgress.progress : 0;
            setMsg(`Processing... ${pct}%`);
        }
    }, [uploadProgress]);

    const uploadFile = async () => {
        if (!file) return;

        const formData = new FormData();
        formData.append('file', file);

        setStatus('uploading');
        setMsg("Uploading file...");
        setJobDetails(null);
        setUploadPercent(0);
        setShowSummary(false);

        try {
            const response = await api.post('/upload', formData, {
                headers: { 'Content-Type': 'multipart/form-data' },
                onUploadProgress: (progressEvent) => {
                    const percentCompleted = Math.round((progressEvent.loaded * 100) / progressEvent.total);
                    setUploadPercent(percentCompleted);
                }
            });

            setUploadPercent(100);
            const data = response.data;
            setJobDetails(data);
            setStatus('processing');
            setMsg("File uploaded. Processing...");

            // Subscribe to real-time job progress via SSE
            if (data.jobId) {
                subscribeToJob(data.jobId);
            }

        } catch (err) {
            setStatus('error');
            if (err.code === 'ERR_NETWORK' || err.message?.includes('Network Error')) {
                setMsg('Upload Error: Batch service (port 8085) is not running. Please start acquira-batch and retry.');
            } else {
                const errorMsg = err.response?.data?.message || err.response?.data || err.message;
                setMsg(`Upload Error: ${typeof errorMsg === 'object' ? JSON.stringify(errorMsg) : errorMsg}`);
            }
        }
    };

    const resetUpload = () => {
        setFile(null);
        setStatus(null);
        setMsg("");
        setJobDetails(null);
        setShowSummary(false);
        setUploadPercent(0);
    };

    return (
        <div className="upload-page">
            <header className="page-header">
                <h1>Smart Data Upload</h1>
                <p>Upload any operational file (Merchant Master or Transaction Log). The system will automatically detect the type and process it.</p>
            </header>

            <div className="upload-container" style={{ maxWidth: '800px', margin: '0 auto' }}>
                <div className="glass-panel card" style={{ padding: '40px', textAlign: 'center' }}>
                    <div style={{ marginBottom: '20px' }}>
                        <div style={{ background: '#e0f2fe', width: '60px', height: '60px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 15px' }}>
                            <FileText size={32} color="#0284c7" />
                        </div>
                        <h2 style={{ fontSize: '24px', marginBottom: '10px' }}>Universal File Uploader</h2>
                        <p style={{ color: '#64748b' }}>Drag & Drop your Excel/CSV file here.</p>
                    </div>

                    <FileDropzone type="unified" onFileSelect={setFile} />

                    {file && status !== 'uploading' && status !== 'processing' && status !== 'success' && (
                        <button className="btn-upload" onClick={uploadFile} style={{ marginTop: '20px', width: '100%', padding: '15px', fontSize: '16px' }}>
                            <Upload size={20} style={{ marginRight: '8px' }} /> Process File
                        </button>
                    )}

                    {/* Uploading state */}
                    {status === 'uploading' && (
                        <div style={{ marginTop: '30px', width: '100%' }}>
                            <FinancialLoader />
                            <p style={{ marginTop: '10px', color: '#64748b', fontWeight: '500' }}>Uploading...</p>
                            <div style={{ marginTop: '20px', textAlign: 'left' }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px', fontSize: '0.875rem', color: '#64748b' }}>
                                    <span>Upload Progress</span>
                                    <span style={{ fontWeight: 'bold', color: '#0f172a' }}>{uploadPercent}%</span>
                                </div>
                                <div style={{ width: '100%', height: '8px', background: '#e2e8f0', borderRadius: '4px', overflow: 'hidden' }}>
                                    <div style={{
                                        width: `${uploadPercent}%`,
                                        height: '100%',
                                        background: '#3b82f6',
                                        transition: 'width 0.2s ease-in-out'
                                    }}></div>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* Processing state */}
                    {status === 'processing' && (
                        <div style={{ marginTop: '30px', width: '100%' }}>
                            <FinancialLoader />
                            <p style={{ marginTop: '10px', color: '#64748b', fontWeight: '500' }}>Processing Records...</p>
                            {jobDetails && (
                                <div style={{ marginTop: '20px', textAlign: 'left' }}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px', fontSize: '0.875rem', color: '#64748b' }}>
                                        <span>Processing Progress</span>
                                        <span style={{ fontWeight: 'bold', color: '#0f172a' }}>
                                            {jobDetails.progress >= 0 ? jobDetails.progress : 0}%
                                        </span>
                                    </div>
                                    <div style={{ width: '100%', height: '8px', background: '#e2e8f0', borderRadius: '4px', overflow: 'hidden' }}>
                                        <div style={{
                                            width: `${Math.max(0, jobDetails.progress || 0)}%`,
                                            height: '100%',
                                            background: '#2563eb',
                                            transition: 'width 0.5s ease-in-out'
                                        }}></div>
                                    </div>
                                    <div style={{ fontSize: '0.8rem', color: '#94a3b8', marginTop: '8px', display: 'flex', justifyContent: 'space-between' }}>
                                        <span>Job ID: {jobDetails.jobId || jobDetails.executionId}</span>
                                        <span>
                                            {jobDetails.readCount || 0} / {jobDetails.totalRows > 0 ? jobDetails.totalRows : 'Calculating...'}
                                        </span>
                                    </div>
                                    {jobDetails.estimatedSecondsRemaining > 0 && (
                                        <div style={{ fontSize: '0.75rem', color: '#94a3b8', marginTop: '4px', textAlign: 'right' }}>
                                            ~{jobDetails.estimatedSecondsRemaining}s remaining
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    )}

                    {/* Success state (inline) */}
                    {status === 'success' && !showSummary && (
                        <div className="status-msg success" style={{ marginTop: '20px', padding: '20px', cursor: 'pointer' }} onClick={() => setShowSummary(true)}>
                            <CheckCircle size={24} />
                            <span style={{ marginLeft: '10px', fontSize: '16px' }}>Processing Complete! Click to view summary.</span>
                        </div>
                    )}

                    {/* Error state */}
                    {status === 'error' && (
                        <div className="status-msg error" style={{ marginTop: '20px', padding: '20px' }}>
                            <AlertCircle size={24} />
                            <span style={{ marginLeft: '10px', fontSize: '16px' }}>{msg}</span>
                        </div>
                    )}

                    {/* Upload another file button */}
                    {(status === 'success' || status === 'error') && (
                        <button onClick={resetUpload} style={{
                            marginTop: '16px', padding: '10px 24px', background: 'none', border: '1px solid #cbd5e1',
                            borderRadius: '8px', color: '#475569', cursor: 'pointer', fontSize: '0.875rem'
                        }}>
                            Upload Another File
                        </button>
                    )}
                </div>
            </div>

            {/* Batch Summary Modal */}
            {showSummary && jobDetails && (
                <div style={{
                    position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
                    backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 100,
                    display: 'flex', justifyContent: 'center', alignItems: 'center',
                    backdropFilter: 'blur(4px)'
                }}>
                    <div style={{
                        background: 'white', borderRadius: '16px', padding: '32px',
                        width: '500px', boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25)'
                    }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
                            <h2 style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#0f172a' }}>Batch Summary</h2>
                            <button onClick={() => setShowSummary(false)} style={{ background: 'none', border: 'none', cursor: 'pointer' }}>
                                <X size={24} color="#64748b" />
                            </button>
                        </div>

                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginBottom: '24px' }}>
                            <div style={{ padding: '16px', background: '#f8fafc', borderRadius: '12px' }}>
                                <div style={{ color: '#64748b', fontSize: '0.875rem', marginBottom: '4px' }}>Reads</div>
                                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#3b82f6' }}>{jobDetails.readCount || 0}</div>
                            </div>
                            <div style={{ padding: '16px', background: '#f8fafc', borderRadius: '12px' }}>
                                <div style={{ color: '#64748b', fontSize: '0.875rem', marginBottom: '4px' }}>Writes</div>
                                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#16a34a' }}>{jobDetails.writeCount || 0}</div>
                            </div>
                            <div style={{ padding: '16px', background: '#f8fafc', borderRadius: '12px' }}>
                                <div style={{ color: '#64748b', fontSize: '0.875rem', marginBottom: '4px' }}>Skips</div>
                                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#f59e0b' }}>{jobDetails.skipCount || 0}</div>
                            </div>
                            <div style={{ padding: '16px', background: '#f8fafc', borderRadius: '12px' }}>
                                <div style={{ color: '#64748b', fontSize: '0.875rem', marginBottom: '4px' }}>Time Taken</div>
                                <div style={{ fontSize: '1rem', fontWeight: '600', color: '#0f172a' }}>
                                    {jobDetails.endTime && jobDetails.startTime
                                        ? ((new Date(jobDetails.endTime) - new Date(jobDetails.startTime)) / 1000).toFixed(2) + 's'
                                        : '...'}
                                </div>
                            </div>
                        </div>

                        <div style={{
                            padding: '16px',
                            background: (jobDetails.status || '').toUpperCase() === 'COMPLETED' ? '#f0fdf4' : '#fef2f2',
                            borderRadius: '12px', display: 'flex', alignItems: 'center', gap: '12px'
                        }}>
                            {(jobDetails.status || '').toUpperCase() === 'COMPLETED'
                                ? <CheckCircle color="#16a34a" />
                                : <AlertCircle color="#dc2626" />}
                            <div>
                                <div style={{ fontWeight: 'bold', color: (jobDetails.status || '').toUpperCase() === 'COMPLETED' ? '#166534' : '#991b1b' }}>
                                    Job {jobDetails.status}
                                </div>
                                <div style={{ fontSize: '0.875rem', color: (jobDetails.status || '').toUpperCase() === 'COMPLETED' ? '#166534' : '#991b1b' }}>
                                    Exit Code: {jobDetails.exitCode || 'N/A'}
                                </div>
                            </div>
                        </div>

                        <div style={{ marginTop: '24px', textAlign: 'right' }}>
                            <button onClick={() => { setShowSummary(false); resetUpload(); }} style={{
                                padding: '10px 20px', background: '#0f172a', color: 'white',
                                borderRadius: '8px', border: 'none', fontWeight: '500', cursor: 'pointer'
                            }}>
                                Close
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default UploadPage;
