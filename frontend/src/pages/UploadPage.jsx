import React, { useState, useEffect } from 'react';
import FileDropzone from '../components/FileDropzone';
import FinancialLoader from '../components/FinancialLoader';
import { Upload, CheckCircle, AlertCircle, FileText, Activity, Layers, X } from 'lucide-react';
import './UploadPage.css';

const UploadPage = () => {
    const [file, setFile] = useState(null);
    const [status, setStatus] = useState(null); // idle, uploading, processing, success, error
    const [msg, setMsg] = useState("");
    const [jobDetails, setJobDetails] = useState(null);
    const [showSummary, setShowSummary] = useState(false);

    const uploadFile = async () => {
        if (!file) return;

        const formData = new FormData();
        formData.append('file', file);

        setStatus('uploading');
        setMsg("Uploading file...");
        setJobDetails(null);

        try {
            const token = localStorage.getItem('token');
            const response = await fetch(`/api/upload`, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${token}`
                },
                body: formData,
            });

            if (response.ok) {
                const data = await response.json();
                setJobDetails(data);
                setStatus('processing');
                setMsg("File uploaded. processing...");
                pollJobStatus(data.jobId);
            } else {
                const text = await response.text();
                setStatus('error');
                setMsg(`Error: ${text}`);
            }
        } catch (err) {
            setStatus('error');
            setMsg(`Network Error: ${err.message}`);
        }
    };

    const pollJobStatus = async (jobId) => {
        const token = localStorage.getItem('token');
        const interval = setInterval(async () => {
            try {
                const res = await fetch(`/api/batch/jobs/${jobId}`, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (res.ok) {
                    const job = await res.json();
                    setJobDetails(job);

                    if (job.status === 'COMPLETED' || job.status === 'FAILED') {
                        clearInterval(interval);
                        setStatus(job.status === 'COMPLETED' ? 'success' : 'error');
                        setMsg(job.status === 'COMPLETED' ? 'Processing Complete!' : 'Processing Failed');
                        setShowSummary(true);
                    }
                }
            } catch (e) {
                console.error("Polling failed", e);
            }
        }, 2000);
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

                    {file && status !== 'uploading' && status !== 'processing' && (
                        <button className="btn-upload" onClick={uploadFile} style={{ marginTop: '20px', width: '100%', padding: '15px', fontSize: '16px' }}>
                            <Upload size={20} style={{ marginRight: '8px' }} /> Process File
                        </button>
                    )}

                    {(status === 'uploading' || status === 'processing') && (
                        <div style={{ marginTop: '30px' }}>
                            <FinancialLoader />
                            <p style={{ marginTop: '10px', color: '#64748b' }}>
                                {status === 'uploading' ? 'Uploading...' : 'Processing Records...'}
                            </p>
                            {jobDetails && (
                                <div style={{ fontSize: '0.8rem', color: '#94a3b8', marginTop: '5px' }}>
                                    Job ID: {jobDetails.jobId} | Status: {jobDetails.status}
                                </div>
                            )}
                        </div>
                    )}

                    {status === 'success' && !showSummary && (
                        <div className="status-msg success" style={{ marginTop: '20px', padding: '20px', cursor: 'pointer' }} onClick={() => setShowSummary(true)}>
                            <CheckCircle size={24} />
                            <span style={{ marginLeft: '10px', fontSize: '16px' }}>Processing Complete! Click to view summary.</span>
                        </div>
                    )}

                    {status === 'error' && (
                        <div className="status-msg error" style={{ marginTop: '20px', padding: '20px' }}>
                            <AlertCircle size={24} />
                            <span style={{ marginLeft: '10px', fontSize: '16px' }}>{msg}</span>
                        </div>
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
                                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#3b82f6' }}>{jobDetails.readCount}</div>
                            </div>
                            <div style={{ padding: '16px', background: '#f8fafc', borderRadius: '12px' }}>
                                <div style={{ color: '#64748b', fontSize: '0.875rem', marginBottom: '4px' }}>Writes</div>
                                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#16a34a' }}>{jobDetails.writeCount}</div>
                            </div>
                            <div style={{ padding: '16px', background: '#f8fafc', borderRadius: '12px' }}>
                                <div style={{ color: '#64748b', fontSize: '0.875rem', marginBottom: '4px' }}>Skips</div>
                                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#f59e0b' }}>{jobDetails.skipCount}</div>
                            </div>
                            <div style={{ padding: '16px', background: '#f8fafc', borderRadius: '12px' }}>
                                <div style={{ color: '#64748b', fontSize: '0.875rem', marginBottom: '4px' }}>Time Taken</div>
                                <div style={{ fontSize: '1rem', fontWeight: '600', color: '#0f172a' }}>
                                    {jobDetails.endTime ?
                                        ((new Date(jobDetails.endTime) - new Date(jobDetails.startTime)) / 1000).toFixed(2) + 's'
                                        : '...'}
                                </div>
                            </div>
                        </div>

                        <div style={{ padding: '16px', background: jobDetails.status === 'COMPLETED' ? '#f0fdf4' : '#fef2f2', borderRadius: '12px', display: 'flex', alignItems: 'center', gap: '12px' }}>
                            {jobDetails.status === 'COMPLETED' ? <CheckCircle color="#16a34a" /> : <AlertCircle color="#dc2626" />}
                            <div>
                                <div style={{ fontWeight: 'bold', color: jobDetails.status === 'COMPLETED' ? '#166534' : '#991b1b' }}>
                                    Job {jobDetails.status}
                                </div>
                                <div style={{ fontSize: '0.875rem', color: jobDetails.status === 'COMPLETED' ? '#166534' : '#991b1b' }}>
                                    Exit Code: {jobDetails.exitCode}
                                </div>
                            </div>
                        </div>

                        <div style={{ marginTop: '24px', textAlign: 'right' }}>
                            <button onClick={() => setShowSummary(false)} style={{
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
