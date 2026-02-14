import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus, Edit2, X, Shield, Power, Unlock, KeyRound, Mail, User as UserIcon, Eye, EyeOff, Check, AlertTriangle } from 'lucide-react';
import api from '../api/axios';

const UserManagement = () => {
    const [users, setUsers] = useState([]);
    const [banks, setBanks] = useState([]);
    const [groups, setGroups] = useState([]);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [isResetModalOpen, setIsResetModalOpen] = useState(false);
    const [resetUser, setResetUser] = useState(null);
    const [resetPassword, setResetPassword] = useState('');
    const [showResetPw, setShowResetPw] = useState(false);
    const [currentUser, setCurrentUser] = useState({ username: '', email: '', active: true, password: '', id: null });
    const [selectedBankId, setSelectedBankId] = useState('');
    const [selectedGroupId, setSelectedGroupId] = useState('');
    const [error, setError] = useState(null);
    const [successMsg, setSuccessMsg] = useState(null);
    const [showPassword, setShowPassword] = useState(false);

    useEffect(() => {
        fetchUsers();
        fetchBanks();
        fetchGroups();
    }, []);

    useEffect(() => {
        if (successMsg) {
            const t = setTimeout(() => setSuccessMsg(null), 4000);
            return () => clearTimeout(t);
        }
    }, [successMsg]);

    const fetchUsers = async () => {
        try {
            const res = await api.get('/users');
            setUsers(res.data);
        } catch (e) { console.error(e); }
    };

    const fetchBanks = async () => {
        try { const res = await api.get('/banks'); setBanks(res.data); } catch (e) { console.error(e); }
    };

    const fetchGroups = async () => {
        try { const res = await api.get('/admin/rbac/groups'); setGroups(res.data); } catch (e) { console.error(e); }
    };

    const openModal = (user = null) => {
        setError(null);
        if (user) {
            setCurrentUser({ ...user, password: '' });
        } else {
            setCurrentUser({ username: '', email: '', active: true, password: '', id: null });
        }
        setSelectedGroupId('');
        setSelectedBankId('');
        setIsModalOpen(true);
    };

    const handleSave = async (e) => {
        e.preventDefault();
        setError(null);
        const method = currentUser.id ? 'put' : 'post';
        const url = currentUser.id ? `/users/${currentUser.id}` : '/users';

        const payload = { ...currentUser };
        delete payload.sysUserGroup;
        if (currentUser.id) delete payload.role; // Don't change role on edit

        try {
            const res = await api[method](url, payload);
            const savedUser = res.data;

            if (selectedBankId && selectedGroupId) {
                await api.post(`/users/${savedUser.id}/assign`, {
                    bankId: selectedBankId,
                    groupId: selectedGroupId
                });
            }

            setSuccessMsg(currentUser.id ? 'User updated successfully' : 'User created successfully');
            fetchUsers();
            setIsModalOpen(false);
        } catch (err) {
            setError(err.response?.data?.error || 'Failed to save user');
        }
    };

    const handleToggleActive = async (user) => {
        try {
            await api.put(`/users/${user.id}`, { ...user, active: !user.active });
            fetchUsers();
        } catch (error) { console.error(error); }
    };

    // ===== ADMIN RESET PASSWORD =====
    const openResetModal = (user) => {
        setResetUser(user);
        setResetPassword('');
        setShowResetPw(false);
        setError(null);
        setIsResetModalOpen(true);
    };

    const handleAdminReset = async (e) => {
        e.preventDefault();
        setError(null);
        try {
            const res = await api.post(`/users/${resetUser.id}/reset-password`, {
                newPassword: resetPassword
            });
            setSuccessMsg(res.data.message);
            setIsResetModalOpen(false);
            fetchUsers();
        } catch (err) {
            setError(err.response?.data?.error || 'Failed to reset password');
        }
    };

    // ===== UNLOCK ACCOUNT =====
    const handleUnlock = async (user) => {
        try {
            const res = await api.post(`/users/${user.id}/unlock`);
            setSuccessMsg(res.data.message);
            fetchUsers();
        } catch (err) {
            console.error(err);
        }
    };

    const isLocked = (user) => user.lockedUntil && new Date(user.lockedUntil) > new Date();

    // Password strength checks
    const pw = currentUser.password || '';
    const pwChecks = [
        { label: '8+ chars', valid: pw.length >= 8 },
        { label: 'Uppercase', valid: /[A-Z]/.test(pw) },
        { label: 'Lowercase', valid: /[a-z]/.test(pw) },
        { label: 'Number', valid: /[0-9]/.test(pw) },
        { label: 'Special', valid: /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(pw) },
    ];

    return (
        <div style={{ padding: '40px', color: '#1e293b' }}>
            {/* Success Toast */}
            <AnimatePresence>
                {successMsg && (
                    <motion.div
                        initial={{ opacity: 0, y: -20 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, y: -20 }}
                        style={{
                            position: 'fixed', top: '20px', right: '20px', zIndex: 100,
                            background: '#f0fdf4', color: '#16a34a', padding: '14px 20px',
                            borderRadius: '10px', border: '1px solid #bbf7d0',
                            display: 'flex', alignItems: 'center', gap: '8px',
                            boxShadow: '0 4px 12px rgba(0,0,0,0.1)', fontSize: '0.9rem', fontWeight: 500,
                        }}
                    >
                        <Check size={18} /> {successMsg}
                    </motion.div>
                )}
            </AnimatePresence>

            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '30px' }}>
                <h1 style={{ fontWeight: 'bold', fontSize: '24px' }}>User Management</h1>
                <button onClick={() => openModal()} style={{ background: '#0f172a', color: 'white', padding: '10px 20px', borderRadius: '8px', display: 'flex', gap: '8px', alignItems: 'center', border: 'none', cursor: 'pointer' }}>
                    <Plus size={18} /> Add User
                </button>
            </div>

            {/* User Table */}
            <div style={{ background: 'white', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0' }}>
                        <tr>
                            <th style={thStyle}>Username</th>
                            <th style={thStyle}>Email</th>
                            <th style={thStyle}>Role</th>
                            <th style={thStyle}>Status</th>
                            <th style={thStyle}>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {users.map(user => (
                            <tr key={user.id} style={{ borderBottom: '1px solid #e2e8f0' }}>
                                <td style={tdStyle}>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                                        <div style={{ width: '32px', height: '32px', background: '#e2e8f0', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '14px', fontWeight: 600 }}>
                                            {user.username?.[0]?.toUpperCase()}
                                        </div>
                                        <div>
                                            <span style={{ fontWeight: 500 }}>{user.username}</span>
                                            {user.mustChangePassword && (
                                                <span style={{ display: 'block', fontSize: '0.7rem', color: '#f59e0b' }}>Must change password</span>
                                            )}
                                        </div>
                                    </div>
                                </td>
                                <td style={tdStyle}>
                                    <span style={{ color: '#64748b', fontSize: '0.85rem' }}>{user.email || '—'}</span>
                                </td>
                                <td style={tdStyle}>
                                    <span style={{ padding: '4px 12px', borderRadius: '999px', fontSize: '12px', fontWeight: 600, background: '#f1f5f9', color: '#475569' }}>
                                        {user.role || 'ROLE_USER'}
                                    </span>
                                </td>
                                <td style={tdStyle}>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                        <div
                                            onClick={() => handleToggleActive(user)}
                                            style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px', color: user.active ? '#10b981' : '#ef4444', fontWeight: 500, fontSize: '13px' }}
                                        >
                                            <Power size={14} /> {user.active ? 'Active' : 'Inactive'}
                                        </div>
                                        {isLocked(user) && (
                                            <span style={{ padding: '2px 8px', borderRadius: '999px', fontSize: '11px', fontWeight: 600, background: '#fef2f2', color: '#dc2626', border: '1px solid #fecaca' }}>
                                                LOCKED
                                            </span>
                                        )}
                                    </div>
                                </td>
                                <td style={tdStyle}>
                                    <div style={{ display: 'flex', gap: '4px' }}>
                                        <button onClick={() => openModal(user)} style={actionBtn} title="Edit User">
                                            <Edit2 size={15} />
                                        </button>
                                        <button onClick={() => openResetModal(user)} style={{ ...actionBtn, color: '#f59e0b' }} title="Reset Password">
                                            <KeyRound size={15} />
                                        </button>
                                        {isLocked(user) && (
                                            <button onClick={() => handleUnlock(user)} style={{ ...actionBtn, color: '#dc2626' }} title="Unlock Account">
                                                <Unlock size={15} />
                                            </button>
                                        )}
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            {/* ===== CREATE / EDIT USER MODAL ===== */}
            <AnimatePresence>
                {isModalOpen && (
                    <div style={overlayStyle}>
                        <motion.div initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.95, opacity: 0 }}
                            style={{ background: 'white', padding: '30px', borderRadius: '16px', width: '100%', maxWidth: '480px', maxHeight: '90vh', overflowY: 'auto' }}
                        >
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                                <h2 style={{ fontSize: '20px', fontWeight: 'bold' }}>{currentUser.id ? 'Edit User' : 'New User'}</h2>
                                <button onClick={() => setIsModalOpen(false)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#64748b' }}><X size={20} /></button>
                            </div>

                            {error && <div style={errorStyle}>{error}</div>}

                            <form onSubmit={handleSave} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                                {/* Username */}
                                <div>
                                    <label style={labelStyle}>Username</label>
                                    <div style={inputWrapStyle}>
                                        <UserIcon size={16} style={iconStyle} />
                                        <input value={currentUser.username}
                                            onChange={e => setCurrentUser({ ...currentUser, username: e.target.value })}
                                            style={inputStyle} required disabled={!!currentUser.id}
                                            placeholder="Enter username"
                                        />
                                    </div>
                                </div>

                                {/* Email */}
                                <div>
                                    <label style={labelStyle}>Email</label>
                                    <div style={inputWrapStyle}>
                                        <Mail size={16} style={iconStyle} />
                                        <input type="email" value={currentUser.email || ''}
                                            onChange={e => setCurrentUser({ ...currentUser, email: e.target.value })}
                                            style={inputStyle} required
                                            placeholder="Enter email address"
                                        />
                                    </div>
                                </div>

                                {/* Password (only for create or if admin wants to change) */}
                                <div>
                                    <label style={labelStyle}>
                                        Password {currentUser.id && <span style={{ color: '#94a3b8', fontWeight: 400 }}>(use Reset button instead)</span>}
                                    </label>
                                    <div style={inputWrapStyle}>
                                        <KeyRound size={16} style={iconStyle} />
                                        <input
                                            type={showPassword ? 'text' : 'password'}
                                            value={currentUser.password}
                                            onChange={e => setCurrentUser({ ...currentUser, password: e.target.value })}
                                            style={inputStyle}
                                            placeholder={currentUser.id ? 'Leave empty to keep current' : 'Enter password'}
                                            required={!currentUser.id}
                                        />
                                        <button type="button" onClick={() => setShowPassword(!showPassword)}
                                            style={{ position: 'absolute', right: '10px', background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8' }}>
                                            {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                                        </button>
                                    </div>

                                    {/* Strength meter for new users */}
                                    {!currentUser.id && pw.length > 0 && (
                                        <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginTop: '8px' }}>
                                            {pwChecks.map((c, i) => (
                                                <span key={i} style={{
                                                    fontSize: '0.7rem', padding: '2px 8px', borderRadius: '999px',
                                                    background: c.valid ? '#f0fdf4' : '#f8fafc',
                                                    color: c.valid ? '#16a34a' : '#94a3b8',
                                                    border: `1px solid ${c.valid ? '#bbf7d0' : '#e2e8f0'}`,
                                                }}>
                                                    {c.valid ? '✓' : '○'} {c.label}
                                                </span>
                                            ))}
                                        </div>
                                    )}
                                </div>

                                {/* Group */}
                                <div>
                                    <label style={labelStyle}>Group</label>
                                    <select value={selectedGroupId} onChange={e => setSelectedGroupId(e.target.value)}
                                        style={{ ...inputStyle, paddingLeft: '12px' }}>
                                        <option value="">Select Group...</option>
                                        {groups.map(g => (
                                            <option key={g.id || g.groupId} value={g.id || g.groupId}>{g.groupName}</option>
                                        ))}
                                    </select>
                                </div>

                                {/* Bank/Tenant */}
                                <div>
                                    <label style={labelStyle}>Assign Bank (Tenant)</label>
                                    <select value={selectedBankId} onChange={e => setSelectedBankId(e.target.value)}
                                        style={{ ...inputStyle, paddingLeft: '12px' }}>
                                        <option value="">Select Bank...</option>
                                        {banks.map(b => (
                                            <option key={b.tenantId} value={b.tenantId}>{b.bankName}</option>
                                        ))}
                                    </select>
                                </div>

                                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '10px' }}>
                                    <button type="button" onClick={() => setIsModalOpen(false)}
                                        style={{ padding: '10px 20px', borderRadius: '8px', background: '#f1f5f9', border: 'none', cursor: 'pointer' }}>Cancel</button>
                                    <button type="submit"
                                        style={{ padding: '10px 20px', borderRadius: '8px', background: '#0f172a', color: 'white', border: 'none', cursor: 'pointer' }}>Save</button>
                                </div>
                            </form>
                        </motion.div>
                    </div>
                )}
            </AnimatePresence>

            {/* ===== ADMIN RESET PASSWORD MODAL ===== */}
            <AnimatePresence>
                {isResetModalOpen && resetUser && (
                    <div style={overlayStyle}>
                        <motion.div initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.95, opacity: 0 }}
                            style={{ background: 'white', padding: '30px', borderRadius: '16px', width: '100%', maxWidth: '420px' }}
                        >
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                                <h2 style={{ fontSize: '18px', fontWeight: 'bold', display: 'flex', alignItems: 'center', gap: '8px' }}>
                                    <KeyRound size={20} color="#f59e0b" /> Reset Password
                                </h2>
                                <button onClick={() => setIsResetModalOpen(false)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#64748b' }}><X size={20} /></button>
                            </div>

                            <div style={{ background: '#fffbeb', padding: '12px 16px', borderRadius: '8px', marginBottom: '16px', border: '1px solid #fde68a' }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#92400e', fontSize: '0.85rem' }}>
                                    <AlertTriangle size={16} />
                                    <span>Setting a new password for <strong>{resetUser.username}</strong>. They will be required to change it on next login.</span>
                                </div>
                            </div>

                            {error && <div style={errorStyle}>{error}</div>}

                            <form onSubmit={handleAdminReset} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                                <div>
                                    <label style={labelStyle}>New Password</label>
                                    <div style={inputWrapStyle}>
                                        <KeyRound size={16} style={iconStyle} />
                                        <input
                                            type={showResetPw ? 'text' : 'password'}
                                            value={resetPassword}
                                            onChange={e => { setResetPassword(e.target.value); setError(null); }}
                                            style={inputStyle}
                                            placeholder="Enter new password"
                                            required
                                            autoFocus
                                        />
                                        <button type="button" onClick={() => setShowResetPw(!showResetPw)}
                                            style={{ position: 'absolute', right: '10px', background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8' }}>
                                            {showResetPw ? <EyeOff size={16} /> : <Eye size={16} />}
                                        </button>
                                    </div>
                                </div>

                                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px' }}>
                                    <button type="button" onClick={() => setIsResetModalOpen(false)}
                                        style={{ padding: '10px 20px', borderRadius: '8px', background: '#f1f5f9', border: 'none', cursor: 'pointer' }}>Cancel</button>
                                    <button type="submit"
                                        style={{ padding: '10px 20px', borderRadius: '8px', background: '#f59e0b', color: 'white', border: 'none', cursor: 'pointer', fontWeight: 600 }}>
                                        Reset Password
                                    </button>
                                </div>
                            </form>
                        </motion.div>
                    </div>
                )}
            </AnimatePresence>
        </div>
    );
};

// ===== Shared Styles =====
const thStyle = { padding: '16px', textAlign: 'left', color: '#64748b', fontSize: '12px', fontWeight: 600 };
const tdStyle = { padding: '16px' };
const actionBtn = { background: 'transparent', border: 'none', cursor: 'pointer', color: '#3b82f6', padding: '6px', borderRadius: '6px' };
const overlayStyle = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 50 };
const labelStyle = { display: 'block', marginBottom: '6px', fontSize: '0.82rem', fontWeight: 500, color: '#334155' };
const inputWrapStyle = { position: 'relative', display: 'flex', alignItems: 'center' };
const iconStyle = { position: 'absolute', left: '12px', color: '#94a3b8', pointerEvents: 'none' };
const inputStyle = { width: '100%', padding: '10px 12px 10px 38px', borderRadius: '8px', border: '1px solid #cbd5e1', boxSizing: 'border-box', fontSize: '0.9rem', outline: 'none' };
const errorStyle = { background: '#fef2f2', color: '#dc2626', padding: '10px 14px', borderRadius: '8px', fontSize: '0.85rem', border: '1px solid #fecaca', marginBottom: '12px' };

export default UserManagement;
