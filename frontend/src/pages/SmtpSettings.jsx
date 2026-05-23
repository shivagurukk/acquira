import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus, Edit2, Trash, X, Check, Shield, Power, Server, Mail, Save, RefreshCw } from 'lucide-react';
import api from '../api/axios';

const SmtpSettings = () => {
    const [configs, setConfigs] = useState([]);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [currentConfig, setCurrentConfig] = useState({
        configName: '', host: '', port: 587, username: '', password: '',
        authEnabled: true, starttlsEnabled: true, sslEnabled: false,
        fromAddress: '', fromName: '', isActive: false
    });
    const [testPool, setTestPool] = useState({}); // id -> status
    const [loading, setLoading] = useState(false);
    // Tracks whether the admin typed into the password field this session.
    // Used so an untouched field on edit does NOT overwrite the stored password.
    const [pwTouched, setPwTouched] = useState(false);

    useEffect(() => {
        fetchConfigs();
    }, []);

    const fetchConfigs = async () => {
        try {
            const res = await api.get('/email/smtp-configs');
            setConfigs(res.data);
        } catch (e) { console.error(e); }
    };

    const handleSave = async (e) => {
        e.preventDefault();
        try {
            // Password handling: the backend never sends the stored password
            // back (it returns a "__UNCHANGED__" sentinel). So on edit, only
            // include a password if the admin actually typed a new one in this
            // session. An empty field on edit => omit it => backend keeps the
            // stored (encrypted) password untouched.
            const payload = { ...currentConfig };
            if (currentConfig.id && (!pwTouched || !payload.password)) {
                payload.password = '__UNCHANGED__';
            }
            if (currentConfig.id) {
                await api.put(`/email/smtp-configs/${currentConfig.id}`, payload);
            } else {
                await api.post('/email/smtp-configs', payload);
            }
            fetchConfigs();
            setIsModalOpen(false);
        } catch (error) {
            console.error(error);
            alert('Failed to save SMTP config: ' + (error?.response?.data?.error || error.message));
        }
    };

    const handleDelete = async (id) => {
        if (!window.confirm("Are you sure you want to delete this configuration?")) return;
        try {
            await api.delete(`/email/smtp-configs/${id}`);
            fetchConfigs();
        } catch (error) { console.error(error); }
    };

    const handleActivate = async (id) => {
        try {
            await api.post(`/email/smtp-configs/${id}/activate`);
            fetchConfigs();
        } catch (error) { console.error(error); }
    };

    const handleTest = async (id) => {
        setTestPool({ ...testPool, [id]: 'TESTING' });
        try {
            const res = await api.post(`/email/smtp-configs/${id}/test`);
            setTestPool({ ...testPool, [id]: res.data.status === 'SUCCESS' ? 'SUCCESS' : 'FAILED' });
            if (res.data.status !== 'SUCCESS') alert("Connection Failed: " + res.data.message);
        } catch (error) {
            setTestPool({ ...testPool, [id]: 'FAILED' });
        }
    };

    const openModal = (config = null) => {
        setPwTouched(false);
        if (config) {
            // Never carry the password sentinel into the editable form field.
            // Blank it; an empty field on edit means "keep the stored password".
            setCurrentConfig({ ...config, password: '' });
        } else {
            setCurrentConfig({
                configName: '', host: '', port: 587, username: '', password: '',
                authEnabled: true, starttlsEnabled: true, sslEnabled: false,
                fromAddress: '', fromName: '', isActive: false,
                rateLimitMs: 200, maxRetries: 3
            });
        }
        setIsModalOpen(true);
    };

    return (
        <div className="page-container" style={{ padding: '40px', color: '#1e293b' }}>
            <div className="header-row" style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '30px' }}>
                <div>
                    <h1 style={{ fontWeight: 'bold', fontSize: '24px', display: 'flex', alignItems: 'center', gap: '10px' }}>
                        <Server size={28} /> SMTP Configuration
                    </h1>
                    <p style={{ color: '#64748b', marginTop: '5px' }}>Manage email server settings for merchant statements</p>
                </div>
                <button className="primary-btn" onClick={() => openModal()} style={{ background: '#0f172a', color: 'white', padding: '10px 20px', borderRadius: '8px', display: 'flex', gap: '8px', alignItems: 'center' }}>
                    <Plus size={18} /> Add Config
                </button>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(350px, 1fr))', gap: '20px' }}>
                {configs.map(config => (
                    <div key={config.id} style={{
                        background: 'white', borderRadius: '12px', padding: '24px',
                        boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)',
                        border: config.isActive ? '2px solid #10b981' : '1px solid #e2e8f0',
                        position: 'relative'
                    }}>
                        {config.isActive && (
                            <div style={{
                                position: 'absolute', top: '12px', right: '12px',
                                background: '#dcfce7', color: '#166534', padding: '4px 12px',
                                borderRadius: '999px', fontSize: '12px', fontWeight: 'bold', display: 'flex', alignItems: 'center', gap: '4px'
                            }}>
                                <Check size={14} /> ACTIVE
                            </div>
                        )}

                        <div style={{ marginBottom: '20px' }}>
                            <h3 style={{ fontWeight: 'bold', fontSize: '18px', marginBottom: '5px' }}>{config.configName}</h3>
                            <div style={{ color: '#64748b', fontSize: '14px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                                <Server size={14} /> {config.host}:{config.port}
                            </div>
                            <div style={{ color: '#64748b', fontSize: '14px', display: 'flex', alignItems: 'center', gap: '6px', marginTop: '4px' }}>
                                <Mail size={14} /> {config.fromAddress}
                            </div>
                        </div>

                        <div style={{ display: 'flex', gap: '10px', marginTop: '20px', borderTop: '1px solid #f1f5f9', paddingTop: '20px' }}>
                            {!config.isActive && (
                                <button onClick={() => handleActivate(config.id)} style={{ flex: 1, padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', background: 'white', cursor: 'pointer', fontSize: '14px' }}>
                                    Activate
                                </button>
                            )}
                            <button onClick={() => handleTest(config.id)} style={{ flex: 1, padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', background: 'white', cursor: 'pointer', fontSize: '14px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '5px' }}>
                                {testPool[config.id] === 'TESTING' ? <RefreshCw className="spin" size={14} /> : <Shield size={14} />}
                                {testPool[config.id] === 'SUCCESS' ? 'Passed' : testPool[config.id] === 'FAILED' ? 'Failed' : 'Test'}
                            </button>
                            <button onClick={() => openModal(config)} style={{ padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', background: 'white', cursor: 'pointer', color: '#3b82f6' }}>
                                <Edit2 size={18} />
                            </button>
                            <button onClick={() => handleDelete(config.id)} style={{ padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', background: 'white', cursor: 'pointer', color: '#ef4444' }}>
                                <Trash size={18} />
                            </button>
                        </div>
                    </div>
                ))}
            </div>

            <AnimatePresence>
                {isModalOpen && (
                    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 100 }}>
                        <motion.div
                            initial={{ scale: 0.95, opacity: 0 }}
                            animate={{ scale: 1, opacity: 1 }}
                            exit={{ scale: 0.95, opacity: 0 }}
                            style={{ background: 'white', padding: '30px', borderRadius: '16px', width: '100%', maxWidth: '600px', maxHeight: '90vh', overflowY: 'auto' }}
                        >
                            <h2 style={{ fontSize: '20px', fontWeight: 'bold', marginBottom: '20px' }}>{currentConfig.id ? 'Edit Config' : 'New Config'}</h2>
                            <form onSubmit={handleSave} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                                <div style={{ gridColumn: 'span 2' }}>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Configuration Name</label>
                                    <input value={currentConfig.configName} onChange={e => setCurrentConfig({ ...currentConfig, configName: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} required placeholder="e.g. Production Mail Server" />
                                </div>

                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>SMTP Host</label>
                                    <input value={currentConfig.host} onChange={e => setCurrentConfig({ ...currentConfig, host: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} required />
                                </div>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Port</label>
                                    <input type="number" value={currentConfig.port} onChange={e => setCurrentConfig({ ...currentConfig, port: parseInt(e.target.value) })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} required />
                                </div>

                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Username</label>
                                    <input value={currentConfig.username} onChange={e => setCurrentConfig({ ...currentConfig, username: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} />
                                </div>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Password</label>
                                    <input type="password" value={currentConfig.password}
                                        onChange={e => { setCurrentConfig({ ...currentConfig, password: e.target.value }); setPwTouched(true); }}
                                        autoComplete="new-password"
                                        placeholder={currentConfig.id ? 'Leave blank to keep current password' : ''}
                                        style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} />
                                    {currentConfig.id && (
                                        <span style={{ fontSize: '12px', color: '#94a3b8' }}>
                                            Stored encrypted. Only enter a value to change it.
                                        </span>
                                    )}
                                </div>

                                <div style={{ gridColumn: 'span 2', display: 'flex', gap: '20px', padding: '10px 0' }}>
                                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                        <input type="checkbox" checked={currentConfig.authEnabled} onChange={e => setCurrentConfig({ ...currentConfig, authEnabled: e.target.checked })} /> Use Auth
                                    </label>
                                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                        <input type="checkbox" checked={currentConfig.starttlsEnabled} onChange={e => setCurrentConfig({ ...currentConfig, starttlsEnabled: e.target.checked })} /> STARTTLS
                                    </label>
                                    <label style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                        <input type="checkbox" checked={currentConfig.sslEnabled} onChange={e => setCurrentConfig({ ...currentConfig, sslEnabled: e.target.checked })} /> SSL
                                    </label>
                                </div>

                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>From Email</label>
                                    <input type="email" value={currentConfig.fromAddress} onChange={e => setCurrentConfig({ ...currentConfig, fromAddress: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} />
                                </div>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>From Name</label>
                                    <input value={currentConfig.fromName} onChange={e => setCurrentConfig({ ...currentConfig, fromName: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} />
                                </div>

                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Rate Limit (ms)</label>
                                    <input type="number" value={currentConfig.rateLimitMs || 200} onChange={e => setCurrentConfig({ ...currentConfig, rateLimitMs: parseInt(e.target.value) })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} />
                                </div>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Max Retries</label>
                                    <input type="number" value={currentConfig.maxRetries || 3} onChange={e => setCurrentConfig({ ...currentConfig, maxRetries: parseInt(e.target.value) })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} />
                                </div>

                                <div style={{ gridColumn: 'span 2', display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '20px' }}>
                                    <button type="button" onClick={() => setIsModalOpen(false)} style={{ padding: '10px 20px', borderRadius: '8px', background: '#f1f5f9', border: 'none', cursor: 'pointer' }}>Cancel</button>
                                    <button type="submit" style={{ padding: '10px 20px', borderRadius: '8px', background: '#0f172a', color: 'white', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px' }}>
                                        <Save size={18} /> Save Config
                                    </button>
                                </div>
                            </form>
                        </motion.div>
                    </div>
                )}
            </AnimatePresence>

            <style>{`
                .spin { animation: spin 1s linear infinite; }
                @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
            `}</style>
        </div>
    );
};

export default SmtpSettings;
