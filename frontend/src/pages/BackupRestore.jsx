import React, { useState, useEffect } from 'react';
import { Download, RefreshCw, Trash2, Database, AlertTriangle, CheckCircle, XCircle } from 'lucide-react';
import api from '../api/axios';

const BackupRestore = () => {
    const [backups, setBackups] = useState([]);
    const [loading, setLoading] = useState(true);
    const [actionLoading, setActionLoading] = useState(false);
    const [msg, setMsg] = useState({ type: '', text: '' });

    useEffect(() => {
        fetchBackups();
    }, []);

    const fetchBackups = async () => {
        try {
            setLoading(true);
            const res = await api.get('/admin/backups');
            setBackups(res.data);
        } catch (error) {
            setMsg({ type: 'error', text: 'Failed to fetch backups' });
        } finally {
            setLoading(false);
        }
    };

    const createBackup = async () => {
        if (!window.confirm("Are you sure you want to create a new database backup?")) return;
        try {
            setActionLoading(true);
            setMsg({ type: 'info', text: 'Creating backup...' });
            const res = await api.post('/admin/backups/create');
            setMsg({ type: 'success', text: `Backup created: ${res.data.fileName}` });
            fetchBackups();
        } catch (error) {
            const d = error.response?.data || {};
            // The backend returns { error, detail } where detail holds the real
            // pg_dump output (binary-not-found / version mismatch / auth / perms).
            const errMsg = [d.error || 'Unknown error', d.detail].filter(Boolean).join(' — ');
            setMsg({ type: 'error', text: `Backup failed: ${errMsg}` });
        } finally {
            setActionLoading(false);
        }
    };

    const restoreBackup = async (fileName) => {
        const confirm1 = window.confirm("WARNING: Restore is a DESTRUCTIVE operation.\nThis will overwrite the current database with the selected backup.\nAre you absolutely sure?");
        if (!confirm1) return;
        const confirm2 = window.confirm(`Please confirm AGAIN.\nRestore from: ${fileName}?`);
        if (!confirm2) return;

        try {
            setActionLoading(true);
            setMsg({ type: 'warning', text: 'Restoring database... This may take a while.' });
            await api.post(`/admin/backups/restore/${fileName}`);
            setMsg({ type: 'success', text: 'Restore completed successfully!' });
        } catch (error) {
            const d = error.response?.data || {};
            const errMsg = [d.error || 'Unknown error', d.detail].filter(Boolean).join(' — ');
            setMsg({ type: 'error', text: `Restore failed: ${errMsg}` });
        } finally {
            setActionLoading(false);
        }
    };

    const deleteBackup = async (fileName) => {
        if (!window.confirm(`Delete backup ${fileName}?`)) return;
        try {
            await api.delete(`/admin/backups/${fileName}`);
            setMsg({ type: 'success', text: 'Backup deleted' });
            fetchBackups();
        } catch (error) {
            setMsg({ type: 'error', text: 'Failed to delete backup' });
        }
    };

    const downloadBackup = async (fileName) => {
        try {
            const res = await api.get(`/admin/backups/download/${fileName}`, { responseType: 'blob' });
            const url = window.URL.createObjectURL(new Blob([res.data]));
            const a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
        } catch (err) {
            setMsg({ type: 'error', text: 'Download failed' });
        }
    };

    const formatSize = (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const formatDate = (ts) => new Date(ts).toLocaleString();

    return (
        <div style={{ padding: '24px', maxWidth: '1200px', margin: '0 auto' }}>
            <div style={{ marginBottom: '24px' }}>
                <h1 style={{ fontSize: '24px', fontWeight: 'bold', color: '#1e293b' }}>Backup & Restore</h1>
                <p style={{ color: '#64748b' }}>Manage database backups and perform system restoration.</p>
            </div>

            <div className="bg-white p-6 rounded-lg shadow-sm border border-slate-200">

                {msg.text && (
                    <div className={`mb-4 p-4 rounded-md flex items-center gap-2 ${
                        msg.type === 'error' ? 'bg-red-50 text-red-700 border border-red-200' :
                        msg.type === 'success' ? 'bg-green-50 text-green-700 border border-green-200' :
                        msg.type === 'warning' ? 'bg-yellow-50 text-yellow-700 border border-yellow-200' :
                        'bg-blue-50 text-blue-700 border border-blue-200'
                    }`}>
                        {msg.type === 'error' && <XCircle size={18} />}
                        {msg.type === 'success' && <CheckCircle size={18} />}
                        {msg.type === 'warning' && <AlertTriangle size={18} />}
                        {msg.type === 'info' && <RefreshCw size={18} className="animate-spin" />}
                        <span>{msg.text}</span>
                    </div>
                )}

                <div className="flex justify-between items-center mb-6">
                    <div className="flex items-center gap-3">
                        <div className="p-2 bg-blue-100 rounded-lg">
                            <Database className="text-blue-600" size={24} />
                        </div>
                        <div>
                            <h2 className="text-lg font-semibold text-slate-800">System Backups</h2>
                            <p className="text-sm text-slate-500">Manage database snapshots provided by pg_dump</p>
                        </div>
                    </div>
                    <button
                        onClick={createBackup}
                        disabled={actionLoading}
                        className="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-lg flex items-center gap-2 transition-colors disabled:opacity-50"
                    >
                        {actionLoading ? <RefreshCw className="animate-spin" size={18} /> : <Database size={18} />}
                        Create Backup
                    </button>
                </div>

                {loading ? (
                    <div className="text-center py-12 text-slate-500">Loading backups...</div>
                ) : backups.length === 0 ? (
                    <div className="text-center py-12 text-slate-500 bg-slate-50 rounded-lg border border-slate-200 border-dashed">
                        No backups found. Create one to get started.
                    </div>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-left">
                            <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 font-medium">
                                <tr>
                                    <th className="p-4">Filename</th>
                                    <th className="p-4">Created At</th>
                                    <th className="p-4">Size</th>
                                    <th className="p-4 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {backups.map((backup) => (
                                    <tr key={backup.name} className="hover:bg-slate-50">
                                        <td className="p-4 font-mono text-sm text-slate-700">{backup.name}</td>
                                        <td className="p-4 text-sm text-slate-600">{formatDate(backup.lastModified)}</td>
                                        <td className="p-4 text-sm text-slate-600">{formatSize(backup.size)}</td>
                                        <td className="p-4 text-right flex justify-end gap-2">
                                            <button onClick={() => downloadBackup(backup.name)} className="p-2 text-slate-400 hover:text-blue-600 hover:bg-blue-50 rounded-full transition-colors" title="Download">
                                                <Download size={18} />
                                            </button>
                                            <button onClick={() => restoreBackup(backup.name)} disabled={actionLoading} className="p-2 text-slate-400 hover:text-orange-600 hover:bg-orange-50 rounded-full transition-colors" title="Restore">
                                                <RefreshCw size={18} />
                                            </button>
                                            <button onClick={() => deleteBackup(backup.name)} className="p-2 text-slate-400 hover:text-red-600 hover:bg-red-50 rounded-full transition-colors" title="Delete">
                                                <Trash2 size={18} />
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>
        </div>
    );
};

export default BackupRestore;
