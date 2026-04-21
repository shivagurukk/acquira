import React, { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { Mail, Send, RefreshCw, AlertCircle, CheckCircle, Clock, Search, FileText } from 'lucide-react';
import api from '../api/axios';

const StatementEmails = () => {
    const [month, setMonth] = useState(new Date().toISOString().slice(0, 7)); // YYYY-MM
    const [stats, setStats] = useState({ sent: 0, failed: 0, total: 0 });
    const [logs, setLogs] = useState([]);
    const [loading, setLoading] = useState(false);
    const [sending, setSending] = useState(false);
    const [page, setPage] = useState(0);
    const [lastBatchId, setLastBatchId] = useState(null);
    const [batchStatus, setBatchStatus] = useState(null);

    useEffect(() => {
        fetchStats();
        fetchLogs();
    }, [month, page]);

    useEffect(() => {
        let interval;
        if (lastBatchId || (batchStatus && batchStatus.status === 'RUNNING')) {
            interval = setInterval(fetchBatchStatus, 2000);
        }
        return () => clearInterval(interval);
    }, [lastBatchId, batchStatus]);

    const fetchStats = async () => {
        try {
            const res = await api.get(`/email/stats?month=${month}`);
            setStats(res.data);
        } catch (e) { console.error(e); }
    };

    const fetchLogs = async () => {
        setLoading(true);
        try {
            const res = await api.get(`/email/logs?month=${month}&page=${page}&size=50`);
            setLogs(res.data.content || []);
        } catch (e) { console.error(e); } finally { setLoading(false); }
    };

    const fetchBatchStatus = async () => {
        if (!lastBatchId) return;
        try {
            const res = await api.get(`/email/batch-status/${lastBatchId}`);
            setBatchStatus(res.data);
            if (res.data.status === 'COMPLETED') {
                setLastBatchId(null);
                fetchStats();
                fetchLogs();
            }
        } catch (e) { console.error(e); }
    };

    const handleSendBulk = async () => {
        if (!window.confirm(`Are you sure you want to send emails to ALL merchants for ${month}?`)) return;
        setSending(true);
        try {
            await api.post(`/email/send-bulk?month=${month}`);
            fetchLogs();
            alert("Bulk send initiated! Check logs for progress.");
        } catch (e) { console.error(e); } finally { setSending(false); }
    };

    const handleRetry = async (merchantId) => {
        try {
            await api.post(`/email/send/${merchantId}?month=${month}`);
            fetchLogs();
            fetchStats();
        } catch (e) { console.error(e); }
    };

    const getStatusColor = (status) => {
        switch (status) {
            case 'SENT': return '#10b981';
            case 'FAILED': return '#ef4444';
            case 'PENDING': return '#f59e0b';
            default: return '#64748b';
        }
    };

    return (
        <div className="page-container" style={{ padding: '40px', color: '#1e293b' }}>
            <div className="header-row" style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '30px' }}>
                <div>
                    <h1 style={{ fontWeight: 'bold', fontSize: '24px', display: 'flex', alignItems: 'center', gap: '10px' }}>
                        <Mail size={28} /> Email Manager
                    </h1>
                    <p style={{ color: '#64748b', marginTop: '5px' }}>Monitor and manage merchant statement delivery</p>
                </div>
                <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                    <input
                        type="month"
                        value={month}
                        onChange={(e) => setMonth(e.target.value)}
                        style={{ padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }}
                    />
                    <button className="primary-btn" onClick={handleSendBulk} disabled={sending} style={{
                        background: sending ? '#94a3b8' : '#3b82f6', color: 'white', padding: '10px 20px',
                        borderRadius: '8px', display: 'flex', gap: '8px', alignItems: 'center', cursor: sending ? 'not-allowed' : 'pointer', border: 'none'
                    }}>
                        {sending ? <RefreshCw className="spin" size={18} /> : <Send size={18} />}
                        {sending ? 'Sending...' : 'Send Bulk Emails'}
                    </button>
                </div>
            </div>

            {/* KPIs */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '20px', marginBottom: '30px' }}>
                <div style={{ background: 'white', padding: '20px', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', display: 'flex', alignItems: 'center', gap: '15px' }}>
                    <div style={{ padding: '12px', borderRadius: '12px', background: '#dcfce7', color: '#166534' }}><CheckCircle size={24} /></div>
                    <div><div style={{ fontSize: '24px', fontWeight: 'bold' }}>{stats.sent || 0}</div><div style={{ color: '#64748b' }}>Delivered</div></div>
                </div>
                <div style={{ background: 'white', padding: '20px', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', display: 'flex', alignItems: 'center', gap: '15px' }}>
                    <div style={{ padding: '12px', borderRadius: '12px', background: '#fee2e2', color: '#991b1b' }}><AlertCircle size={24} /></div>
                    <div><div style={{ fontSize: '24px', fontWeight: 'bold' }}>{stats.failed || 0}</div><div style={{ color: '#64748b' }}>Failed</div></div>
                </div>
                <div style={{ background: 'white', padding: '20px', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', display: 'flex', alignItems: 'center', gap: '15px' }}>
                    <div style={{ padding: '12px', borderRadius: '12px', background: '#e0f2fe', color: '#075985' }}><FileText size={24} /></div>
                    <div><div style={{ fontSize: '24px', fontWeight: 'bold' }}>{batchStatus ? batchStatus.status : 'Idle'}</div><div style={{ color: '#64748b' }}>Batch Status</div></div>
                </div>
            </div>

            {/* Logs Table */}
            <div style={{ background: 'white', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', overflow: 'hidden' }}>
                <div style={{ padding: '20px', borderBottom: '1px solid #e2e8f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <h3 style={{ fontWeight: 'bold' }}>Email Logs</h3>
                    <button onClick={() => { fetchLogs(); fetchStats(); }} style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: '#64748b' }}><RefreshCw size={18} /></button>
                </div>
                {loading ? (
                    <div style={{ padding: '40px', textAlign: 'center', color: '#64748b' }}>Loading logs...</div>
                ) : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0' }}>
                            <tr>
                                <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Time</th>
                                <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Merchant</th>
                                <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Recipient</th>
                                <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Status</th>
                                <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Message</th>
                                <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            {logs.map(log => (
                                <tr key={log.id} style={{ borderBottom: '1px solid #e2e8f0' }}>
                                    <td style={{ padding: '16px', fontSize: '14px' }}>{new Date(log.sentAt).toLocaleString()}</td>
                                    <td style={{ padding: '16px', fontWeight: '500' }}>{log.merchantName}</td>
                                    <td style={{ padding: '16px', color: '#64748b' }}>{log.recipientEmail}</td>
                                    <td style={{ padding: '16px' }}>
                                        <span style={{
                                            background: getStatusColor(log.status) + '20', color: getStatusColor(log.status),
                                            padding: '4px 12px', borderRadius: '999px', fontSize: '12px', fontWeight: 'bold'
                                        }}>
                                            {log.status}
                                        </span>
                                    </td>
                                    <td style={{ padding: '16px', color: '#ef4444', fontSize: '12px', maxWidth: '300px' }}>{log.errorMessage || '-'}</td>
                                    <td style={{ padding: '16px' }}>
                                        {log.status === 'FAILED' && (
                                            <button onClick={() => handleRetry(log.merchantId)} style={{
                                                padding: '6px 12px', borderRadius: '6px', border: '1px solid #cbd5e1',
                                                background: 'white', cursor: 'pointer', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '4px'
                                            }}>
                                                <RefreshCw size={12} /> Retry
                                            </button>
                                        )}
                                    </td>
                                </tr>
                            ))}
                            {logs.length === 0 && (
                                <tr><td colSpan="6" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>No logs found for this period</td></tr>
                            )}
                        </tbody>
                    </table>
                )}
            </div>

            <style>{`
                .spin { animation: spin 1s linear infinite; }
                @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
            `}</style>
        </div>
    );
};

export default StatementEmails;
