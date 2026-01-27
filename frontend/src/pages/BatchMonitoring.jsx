import React, { useState, useEffect } from 'react';
import { Activity, CheckCircle, XCircle, Clock, RefreshCw } from 'lucide-react';

const BatchMonitoring = () => {
    const [jobs, setJobs] = useState([]);
    const [loading, setLoading] = useState(true);

    const fetchJobs = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const response = await fetch('/api/batch/jobs?size=20', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (response.ok) {
                const data = await response.json();
                setJobs(data);
            }
        } catch (error) {
            console.error("Failed to fetch jobs", error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchJobs();
        const interval = setInterval(fetchJobs, 10000); // Poll every 10s
        return () => clearInterval(interval);
    }, []);

    const getStatusColor = (status) => {
        if (status === 'COMPLETED') return 'bg-green-100 text-green-800';
        if (status === 'FAILED') return 'bg-red-100 text-red-800';
        return 'bg-blue-100 text-blue-800';
    };

    return (
        <div style={{ padding: '24px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: 'bold', color: '#0f172a' }}>Batch Operations</h1>
                    <p style={{ color: '#64748b' }}>Monitor transaction processing jobs</p>
                </div>
                <button onClick={fetchJobs} style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 16px', background: 'white', border: '1px solid #e2e8f0', borderRadius: '6px', cursor: 'pointer' }}>
                    <RefreshCw size={16} /> Refresh
                </button>
            </div>

            <div style={{ background: 'white', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)', overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0' }}>
                        <tr>
                            <th style={{ padding: '16px', textAlign: 'left', fontSize: '12px', color: '#64748b', fontWeight: '600' }}>JOB ID</th>
                            <th style={{ padding: '16px', textAlign: 'left', fontSize: '12px', color: '#64748b', fontWeight: '600' }}>STATUS</th>
                            <th style={{ padding: '16px', textAlign: 'left', fontSize: '12px', color: '#64748b', fontWeight: '600' }}>START TIME</th>
                            <th style={{ padding: '16px', textAlign: 'left', fontSize: '12px', color: '#64748b', fontWeight: '600' }}>END TIME</th>
                            <th style={{ padding: '16px', textAlign: 'left', fontSize: '12px', color: '#64748b', fontWeight: '600' }}>EXIT CODE</th>
                        </tr>
                    </thead>
                    <tbody>
                        {loading && jobs.length === 0 ? (
                            <tr><td colSpan="5" style={{ padding: '24px', textAlign: 'center' }}>Loading...</td></tr>
                        ) : jobs.map((job) => (
                            <tr key={job.executionId} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                <td style={{ padding: '16px', fontWeight: '500' }}>#{job.executionId}</td>
                                <td style={{ padding: '16px' }}>
                                    <span className={`px-2 py-1 rounded-full text-xs font-medium ${getStatusColor(job.status)}`} style={{ padding: '4px 8px', borderRadius: '999px', fontSize: '12px', background: job.status === 'COMPLETED' ? '#dcfce7' : job.status === 'FAILED' ? '#fee2e2' : '#dbeafe', color: job.status === 'COMPLETED' ? '#166534' : job.status === 'FAILED' ? '#991b1b' : '#1e40af' }}>
                                        {job.status}
                                    </span>
                                </td>
                                <td style={{ padding: '16px', color: '#64748b' }}>{job.startTime ? new Date(job.startTime).toLocaleString() : '-'}</td>
                                <td style={{ padding: '16px', color: '#64748b' }}>{job.endTime ? new Date(job.endTime).toLocaleString() : '-'}</td>
                                <td style={{ padding: '16px', fontWeight: '500' }}>{job.exitCode}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default BatchMonitoring;
