import React, { useState, useEffect, useCallback } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import {
  Plus, Edit2, X, Shield, Power, Unlock, KeyRound, Mail, User as UserIcon,
  Eye, EyeOff, Check, AlertTriangle, Search, Building2, ChevronDown, ChevronUp,
  Trash2, Star, Globe, Clock, CheckCircle, XCircle, Filter, Download
} from 'lucide-react';
import api from '../api/axios';

const card = { background: '#fff', borderRadius: 12, boxShadow: '0 1px 3px rgba(0,0,0,.06)', border: '1px solid #e5e7eb' };
const badge = (bg, fg) => ({ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 10px', borderRadius: 12, fontSize: 11, fontWeight: 600, background: bg, color: fg, whiteSpace: 'nowrap' });

const UserManagement = () => {
  const [users, setUsers] = useState([]);
  const [banks, setBanks] = useState([]);
  const [groups, setGroups] = useState([]);
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('users'); // users | requests
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Modal state
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [modalUser, setModalUser] = useState(null);
  const [formData, setFormData] = useState({ username: '', email: '', displayName: '', password: '', active: true, tenantId: '', groupId: '' });
  const [formErrors, setFormErrors] = useState({});

  // Pagination
  const [currentPage, setCurrentPage] = useState(1);
  const PAGE_SIZE = 25;

  // Tenant assignment state
  const [editingAccess, setEditingAccess] = useState(null); // userId being edited
  const [userAccesses, setUserAccesses] = useState([]);
  const [newAccess, setNewAccess] = useState({ tenantId: '', groupId: '', isDefault: false });

  // Reset password modal
  const [resetModal, setResetModal] = useState(null);
  const [resetPw, setResetPw] = useState('');
  const [showResetPw, setShowResetPw] = useState(false);

  // Approve modal
  const [approveModal, setApproveModal] = useState(null);
  const [approveData, setApproveData] = useState({ tenantId: '', groupId: '', reviewNotes: '' });

  const [notification, setNotification] = useState(null);
  const [showPassword, setShowPassword] = useState(false);

  const notify = (msg, type = 'success') => { setNotification({ msg, type }); setTimeout(() => setNotification(null), 4000); };

  const fetchAll = useCallback(async () => {
    setLoading(true);
    try {
      const [u, b, g, r] = await Promise.all([
        api.get('/users/enriched'),
        api.get('/banks'),
        api.get('/admin/rbac/groups'),
        api.get('/admin/access-requests').catch(() => ({ data: [] }))
      ]);
      setUsers(u.data); setBanks(b.data); setGroups(g.data); setRequests(r.data);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { fetchAll(); }, [fetchAll]);

  // ─── Filter users ─────────────────────────────────────
  const filteredUsers = users.filter(u => {
    const q = searchQuery.toLowerCase();
    const matchesSearch = !q || u.username?.toLowerCase().includes(q) || u.email?.toLowerCase().includes(q) || u.displayName?.toLowerCase().includes(q);
    const matchesStatus = statusFilter === 'ALL'
      || (statusFilter === 'ACTIVE' && u.active)
      || (statusFilter === 'INACTIVE' && !u.active)
      || (statusFilter === 'SSO' && u.ssoProvider)
      || (statusFilter === 'PENDING' && u.approvalStatus === 'PENDING');
    return matchesSearch && matchesStatus;
  });

  const pendingCount = requests.filter(r => r.status === 'PENDING').length;

  // ─── User CRUD ─────────────────────────────────────────
  const openCreateModal = () => {
    setModalUser(null);
    setFormData({ username: '', email: '', displayName: '', password: '', active: true, tenantId: '', groupId: '' });
    setFormErrors({});
    setIsModalOpen(true);
  };

  const openEditModal = (user) => {
    setModalUser(user);
    setFormData({ username: user.username, email: user.email || '', displayName: user.displayName || '', password: '', active: user.active });
    setFormErrors({});
    setIsModalOpen(true);
  };

  const handleSaveUser = async () => {
    const errors = {};
    if (!formData.username?.trim()) errors.username = 'Required';
    if (!formData.email?.trim()) errors.email = 'Required';
    if (!modalUser && !formData.password?.trim()) errors.password = 'Required for new user';
    setFormErrors(errors);
    if (Object.keys(errors).length) return;

    try {
      if (modalUser) {
        await api.put(`/users/${modalUser.id}`, { ...formData, id: modalUser.id });
        notify('User updated');
      } else {
        // GAP-6: Create user then assign tenant if selected
        const res = await api.post('/users', formData);
        const newUserId = res.data?.id;
        if (newUserId && formData.tenantId && formData.groupId) {
          try {
            await api.post(`/users/${newUserId}/tenant-access`, {
              tenantId: formData.tenantId, groupId: formData.groupId, isDefault: true
            });
          } catch (te) { console.warn('Tenant assignment failed:', te); }
        }
        notify('User created');
      }
      setIsModalOpen(false);
      fetchAll();
    } catch (e) { setFormErrors({ _: e.response?.data?.error || 'Failed to save' }); }
  };

  const handleToggleActive = async (user) => {
    try {
      await api.put(`/users/${user.id}`, { ...user, active: !user.active, password: '' });
      fetchAll();
    } catch (e) { console.error(e); }
  };

  // ─── Tenant Access ─────────────────────────────────────
  const openAccessPanel = async (userId) => {
    if (editingAccess === userId) { setEditingAccess(null); return; }
    try {
      const res = await api.get(`/users/${userId}/tenant-access`);
      setUserAccesses(res.data);
      setEditingAccess(userId);
      setNewAccess({ tenantId: '', groupId: '', isDefault: false });
    } catch (e) { console.error(e); }
  };

  const addTenantAccess = async (userId) => {
    if (!newAccess.tenantId || !newAccess.groupId) return;
    try {
      await api.post(`/users/${userId}/tenant-access`, newAccess);
      notify('Tenant access added');
      const res = await api.get(`/users/${userId}/tenant-access`);
      setUserAccesses(res.data);
      setNewAccess({ tenantId: '', groupId: '', isDefault: false });
      fetchAll();
    } catch (e) { notify(e.response?.data?.error || 'Failed', 'error'); }
  };

  const removeTenantAccess = async (userId, accessId) => {
    if (!window.confirm('Remove this tenant access?')) return;
    try {
      await api.delete(`/users/${userId}/tenant-access/${accessId}`);
      const res = await api.get(`/users/${userId}/tenant-access`);
      setUserAccesses(res.data);
      fetchAll();
    } catch (e) { console.error(e); }
  };

  // ─── Password Reset ────────────────────────────────────
  const handleResetPassword = async () => {
    try {
      await api.post(`/users/${resetModal.id}/reset-password`, { newPassword: resetPw });
      notify('Password reset');
      setResetModal(null); setResetPw('');
    } catch (e) { notify(e.response?.data?.error || 'Failed', 'error'); }
  };

  const handleUnlock = async (user) => {
    try { await api.post(`/users/${user.id}/unlock`); notify('Account unlocked'); fetchAll(); } catch (e) { console.error(e); }
  };

  // ─── Access Requests ───────────────────────────────────
  const handleApprove = async () => {
    if (!approveData.tenantId || !approveData.groupId) { notify('Select tenant and group', 'error'); return; }
    try {
      await api.post(`/admin/access-requests/${approveModal.requestId}/approve`, approveData);
      notify('Request approved — user created');
      setApproveModal(null);
      fetchAll();
    } catch (e) { notify(e.response?.data?.error || 'Failed', 'error'); }
  };

  const handleReject = async (requestId) => {
    const notes = window.prompt('Rejection reason (optional):');
    try {
      await api.post(`/admin/access-requests/${requestId}/reject`, { reviewNotes: notes || '' });
      notify('Request rejected');
      fetchAll();
    } catch (e) { console.error(e); }
  };

  const isLocked = (u) => u.lockedUntil && new Date(u.lockedUntil) > new Date();

  // ─── Password strength ─────────────────────────────────
  const pw = formData.password || '';
  const pwChecks = [
    { label: '8+ chars', ok: pw.length >= 8 }, { label: 'Upper', ok: /[A-Z]/.test(pw) },
    { label: 'Lower', ok: /[a-z]/.test(pw) }, { label: 'Number', ok: /[0-9]/.test(pw) },
    { label: 'Special', ok: /[^A-Za-z0-9]/.test(pw) },
  ];

  return (
    <div style={{ padding: '24px 32px', color: '#1e293b', maxWidth: 1400, margin: '0 auto' }}>
      {/* Notification */}
      <AnimatePresence>
        {notification && (
          <motion.div initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -20 }}
            style={{ position: 'fixed', top: 20, right: 20, zIndex: 100, padding: '12px 20px', borderRadius: 10,
              background: notification.type === 'error' ? '#fef2f2' : '#f0fdf4',
              color: notification.type === 'error' ? '#dc2626' : '#16a34a',
              border: `1px solid ${notification.type === 'error' ? '#fecaca' : '#bbf7d0'}`,
              boxShadow: '0 4px 12px rgba(0,0,0,.1)', fontSize: 13, fontWeight: 500, display: 'flex', alignItems: 'center', gap: 8 }}>
            {notification.type === 'error' ? <XCircle size={16} /> : <Check size={16} />} {notification.msg}
          </motion.div>
        )}
      </AnimatePresence>

      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 24, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0 }}>User & Access Management</h1>
          <p style={{ fontSize: 13, color: '#64748b', margin: '4px 0 0' }}>Manage users, tenant assignments, SSO access, and approval requests</p>
        </div>
        <button onClick={openCreateModal} style={{ display: 'flex', alignItems: 'center', gap: 6, background: '#0f172a', color: '#fff', padding: '10px 18px', borderRadius: 10, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
          <Plus size={16} /> Create User
        </button>
      </div>

      {/* Tabs */}
      <div style={{ display: 'flex', gap: 2, marginBottom: 20, background: '#f3f4f6', borderRadius: 12, padding: 4 }}>
        {[
          { key: 'users', label: 'Users', icon: UserIcon, count: users.length },
          { key: 'requests', label: 'Access Requests', icon: Clock, count: pendingCount },
        ].map(tab => (
          <button key={tab.key} onClick={() => setActiveTab(tab.key)} style={{
            flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            padding: '10px 16px', borderRadius: 10, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
            background: activeTab === tab.key ? '#fff' : 'transparent', color: activeTab === tab.key ? '#2563eb' : '#6b7280',
            boxShadow: activeTab === tab.key ? '0 1px 3px rgba(0,0,0,.1)' : 'none', transition: 'all .2s', position: 'relative' }}>
            <tab.icon size={16} /> {tab.label}
            {tab.key === 'requests' && pendingCount > 0 && (
              <span style={{ background: '#ef4444', color: '#fff', fontSize: 10, fontWeight: 700, padding: '1px 6px', borderRadius: 10, minWidth: 18, textAlign: 'center' }}>{pendingCount}</span>
            )}
            {tab.key === 'users' && <span style={{ color: '#9ca3af', fontSize: 12 }}>({tab.count})</span>}
          </button>
        ))}
      </div>

      {/* ═══════ USERS TAB ═══════ */}
      {activeTab === 'users' && (
        <>
          {/* Toolbar */}
          <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1, minWidth: 220 }}>
              <Search size={14} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: '#94a3b8' }} />
              <input placeholder="Search users..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
                style={{ width: '100%', padding: '9px 12px 9px 34px', borderRadius: 10, border: '1px solid #e2e8f0', fontSize: 13, outline: 'none', boxSizing: 'border-box' }} />
            </div>
            <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)}
              style={{ padding: '9px 12px', borderRadius: 10, border: '1px solid #e2e8f0', fontSize: 13, background: '#fff', minWidth: 130 }}>
              <option value="ALL">All Users</option>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
              <option value="SSO">SSO Users</option>
              <option value="PENDING">Pending Approval</option>
            </select>
          </div>

          {/* User List */}
          <div style={{ ...card, overflow: 'hidden' }}>
            {loading ? (
              <div style={{ padding: 60, textAlign: 'center', color: '#9ca3af' }}>Loading...</div>
            ) : filteredUsers.length === 0 ? (
              <div style={{ padding: 60, textAlign: 'center', color: '#9ca3af' }}>No users match your search</div>
            ) : filteredUsers.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE).map(user => {
              const isExpanded = editingAccess === user.id;
              return (
                <div key={user.id} style={{ borderBottom: '1px solid #f1f5f9' }}>
                  {/* User Row */}
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 160px 140px 100px 140px', alignItems: 'center', padding: '14px 20px', gap: 12 }}>
                    {/* Name/Email */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <div style={{ width: 36, height: 36, borderRadius: '50%', background: user.ssoProvider ? '#e0e7ff' : '#f1f5f9', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, fontWeight: 700, color: user.ssoProvider ? '#4338ca' : '#475569', flexShrink: 0 }}>
                        {user.ssoProvider ? <Globe size={16} /> : (user.username?.[0]?.toUpperCase() || '?')}
                      </div>
                      <div style={{ overflow: 'hidden' }}>
                        <div style={{ fontSize: 14, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6 }}>
                          {user.displayName || user.username}
                          {user.ssoProvider && <span style={badge('#e0e7ff', '#4338ca')}>SSO</span>}
                          {user.mustChangePassword && !user.ssoProvider && <span style={badge('#fef9c3', '#854d0e')}>Must change PW</span>}
                          {isLocked(user) && <span style={badge('#fef2f2', '#dc2626')}>LOCKED</span>}
                        </div>
                        <div style={{ fontSize: 12, color: '#94a3b8' }}>{user.email || user.username}</div>
                      </div>
                    </div>

                    {/* Tenants */}
                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                      {(user.tenants || []).length === 0 && <span style={{ fontSize: 11, color: '#d1d5db' }}>No tenant</span>}
                      {(user.tenants || []).map((t, i) => (
                        <span key={i} style={{ ...badge('#f0f9ff', '#0369a1'), gap: 3 }}>
                          <Building2 size={10} /> {t.tenantName?.substring(0, 15)}
                          {t.isDefault && <Star size={9} fill="#f59e0b" color="#f59e0b" />}
                        </span>
                      ))}
                    </div>

                    {/* Role */}
                    <span style={badge('#f1f5f9', '#475569')}>{user.role?.replace('ROLE_', '') || 'USER'}</span>

                    {/* Status */}
                    <div onClick={() => handleToggleActive(user)} style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, fontSize: 13, fontWeight: 500, color: user.active ? '#10b981' : '#ef4444' }}>
                      <Power size={14} /> {user.active ? 'Active' : 'Inactive'}
                    </div>

                    {/* Actions */}
                    <div style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                      <button onClick={() => openAccessPanel(user.id)} title="Tenant assignments" style={actionBtnStyle(isExpanded ? '#2563eb' : '#64748b')}>
                        <Building2 size={15} />
                      </button>
                      <button onClick={() => openEditModal(user)} title="Edit" style={actionBtnStyle('#3b82f6')}>
                        <Edit2 size={15} />
                      </button>
                      {/* GAP-14: Hide Reset PW for SSO-only users */}
                      {!user.ssoProvider && (
                        <button onClick={() => { setResetModal(user); setResetPw(''); setShowResetPw(false); }} title="Reset PW" style={actionBtnStyle('#f59e0b')}>
                          <KeyRound size={15} />
                        </button>
                      )}
                      {isLocked(user) && (
                        <button onClick={() => handleUnlock(user)} title="Unlock" style={actionBtnStyle('#dc2626')}>
                          <Unlock size={15} />
                        </button>
                      )}
                    </div>
                  </div>

                  {/* Expanded Tenant Access Panel */}
                  {isExpanded && (
                    <div style={{ background: '#f8fafc', borderTop: '1px solid #e5e7eb', padding: '16px 20px 16px 68px' }}>
                      <div style={{ fontSize: 12, fontWeight: 700, color: '#374151', marginBottom: 10, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                        Tenant Assignments — {user.username}
                      </div>

                      {userAccesses.length > 0 && (
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginBottom: 12 }}>
                          <thead>
                            <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
                              <th style={thSm}>Tenant</th>
                              <th style={thSm}>Group</th>
                              <th style={thSm}>Default</th>
                              <th style={{ ...thSm, width: 60 }}></th>
                            </tr>
                          </thead>
                          <tbody>
                            {userAccesses.map(a => (
                              <tr key={a.accessId} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                <td style={tdSm}>{a.tenantName}</td>
                                <td style={tdSm}><span style={badge('#f0fdf4', '#166534')}>{a.groupName || '—'}</span></td>
                                <td style={tdSm}>{a.isDefault ? <Star size={14} fill="#f59e0b" color="#f59e0b" /> : '—'}</td>
                                <td style={tdSm}>
                                  <button onClick={() => removeTenantAccess(user.id, a.accessId)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#ef4444', padding: 4 }}>
                                    <Trash2 size={14} />
                                  </button>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}

                      {/* Add new access */}
                      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                        <select value={newAccess.tenantId} onChange={e => setNewAccess({ ...newAccess, tenantId: e.target.value })}
                          style={selectSm}><option value="">Select Tenant...</option>
                          {banks.map(b => <option key={b.tenantId} value={b.tenantId}>{b.bankName}</option>)}
                        </select>
                        <select value={newAccess.groupId} onChange={e => setNewAccess({ ...newAccess, groupId: e.target.value })}
                          style={selectSm}><option value="">Select Group...</option>
                          {groups.map(g => <option key={g.groupId || g.id} value={g.groupId || g.id}>{g.groupName}</option>)}
                        </select>
                        <label style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
                          <input type="checkbox" checked={newAccess.isDefault} onChange={e => setNewAccess({ ...newAccess, isDefault: e.target.checked })} /> Default
                        </label>
                        <button onClick={() => addTenantAccess(user.id)} disabled={!newAccess.tenantId || !newAccess.groupId}
                          style={{ padding: '6px 14px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600, background: '#2563eb', color: '#fff', opacity: (!newAccess.tenantId || !newAccess.groupId) ? 0.5 : 1 }}>
                          <Plus size={12} /> Add
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {/* GAP-20: Pagination */}
          {filteredUsers.length > PAGE_SIZE && (
            <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 8, padding: '14px 20px', borderTop: '1px solid #e5e7eb' }}>
              <button onClick={() => setCurrentPage(p => Math.max(1, p - 1))} disabled={currentPage === 1}
                style={{ padding: '6px 14px', borderRadius: 8, border: '1px solid #e2e8f0', background: '#fff', cursor: currentPage === 1 ? 'default' : 'pointer', fontSize: 12, fontWeight: 600, opacity: currentPage === 1 ? 0.4 : 1 }}>← Prev</button>
              <span style={{ fontSize: 12, color: '#6b7280' }}>Page {currentPage} of {Math.ceil(filteredUsers.length / PAGE_SIZE)} ({filteredUsers.length} users)</span>
              <button onClick={() => setCurrentPage(p => Math.min(Math.ceil(filteredUsers.length / PAGE_SIZE), p + 1))} disabled={currentPage >= Math.ceil(filteredUsers.length / PAGE_SIZE)}
                style={{ padding: '6px 14px', borderRadius: 8, border: '1px solid #e2e8f0', background: '#fff', cursor: currentPage >= Math.ceil(filteredUsers.length / PAGE_SIZE) ? 'default' : 'pointer', fontSize: 12, fontWeight: 600, opacity: currentPage >= Math.ceil(filteredUsers.length / PAGE_SIZE) ? 0.4 : 1 }}>Next →</button>
            </div>
          )}
        </>
      )}

      {/* ═══════ ACCESS REQUESTS TAB ═══════ */}
      {activeTab === 'requests' && (
        <div style={card}>
          {requests.length === 0 ? (
            <div style={{ padding: 60, textAlign: 'center', color: '#9ca3af' }}>No access requests</div>
          ) : requests.map(r => (
            <div key={r.requestId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '16px 20px', borderBottom: '1px solid #f1f5f9', gap: 16, flexWrap: 'wrap' }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 14, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
                  {r.displayName || r.email}
                  <span style={badge(
                    r.status === 'PENDING' ? '#fef9c3' : r.status === 'APPROVED' ? '#dcfce7' : '#fef2f2',
                    r.status === 'PENDING' ? '#854d0e' : r.status === 'APPROVED' ? '#166534' : '#991b1b'
                  )}>{r.status}</span>
                  {r.ssoProvider && <span style={badge('#e0e7ff', '#4338ca')}><Globe size={10} /> {r.ssoProvider}</span>}
                </div>
                <div style={{ fontSize: 12, color: '#64748b', marginTop: 2 }}>{r.email}</div>
                {r.tenantName && <div style={{ fontSize: 12, color: '#94a3b8', marginTop: 2 }}>Requested: {r.tenantName}</div>}
                {r.message && <div style={{ fontSize: 12, color: '#64748b', marginTop: 4, fontStyle: 'italic' }}>"{r.message}"</div>}
                <div style={{ fontSize: 11, color: '#d1d5db', marginTop: 4 }}>{new Date(r.createdAt).toLocaleString()}</div>
              </div>

              {r.status === 'PENDING' && (
                <div style={{ display: 'flex', gap: 8 }}>
                  <button onClick={() => { setApproveModal(r); setApproveData({ tenantId: r.tenantId || '', groupId: '', reviewNotes: '' }); }}
                    style={{ padding: '8px 16px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600, background: '#10b981', color: '#fff' }}>
                    <CheckCircle size={14} /> Approve
                  </button>
                  <button onClick={() => handleReject(r.requestId)}
                    style={{ padding: '8px 16px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600, background: '#ef4444', color: '#fff' }}>
                    <XCircle size={14} /> Reject
                  </button>
                </div>
              )}
              {r.status !== 'PENDING' && r.reviewNotes && (
                <div style={{ fontSize: 12, color: '#94a3b8', maxWidth: 200 }}>Note: {r.reviewNotes}</div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* ═══════ CREATE / EDIT USER MODAL ═══════ */}
      <AnimatePresence>
        {isModalOpen && (
          <div style={overlayStyle}>
            <motion.div initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.95, opacity: 0 }}
              style={{ background: '#fff', padding: 28, borderRadius: 16, width: '100%', maxWidth: 480, maxHeight: '90vh', overflowY: 'auto' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2 style={{ fontSize: 18, fontWeight: 700, margin: 0 }}>{modalUser ? 'Edit User' : 'Create User'}</h2>
                <button onClick={() => setIsModalOpen(false)} style={closeBtnStyle}><X size={18} /></button>
              </div>

              {formErrors._ && <div style={errorBoxStyle}>{formErrors._}</div>}

              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <Field label="Username" icon={UserIcon} value={formData.username} disabled={!!modalUser}
                  onChange={v => setFormData({ ...formData, username: v })} error={formErrors.username} />
                <Field label="Email" icon={Mail} type="email" value={formData.email}
                  onChange={v => setFormData({ ...formData, email: v })} error={formErrors.email} />
                <Field label="Display Name" icon={UserIcon} value={formData.displayName}
                  onChange={v => setFormData({ ...formData, displayName: v })} placeholder="Optional" />

                {/* GAP-6: Tenant assignment for new users */}
                {!modalUser && (
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                    <div>
                      <label style={labelStyle}>Assign Tenant</label>
                      <select value={formData.tenantId} onChange={e => setFormData({ ...formData, tenantId: e.target.value })}
                        style={{ ...inputStyle, paddingLeft: 12 }}>
                        <option value="">Select Tenant...</option>
                        {banks.map(b => <option key={b.tenantId} value={b.tenantId}>{b.bankName}</option>)}
                      </select>
                    </div>
                    <div>
                      <label style={labelStyle}>Assign Group</label>
                      <select value={formData.groupId} onChange={e => setFormData({ ...formData, groupId: e.target.value })}
                        style={{ ...inputStyle, paddingLeft: 12 }}>
                        <option value="">Select Group...</option>
                        {groups.map(g => <option key={g.groupId || g.id} value={g.groupId || g.id}>{g.groupName}</option>)}
                      </select>
                    </div>
                  </div>
                )}

                {!modalUser && (
                  <div>
                    <label style={labelStyle}>Password</label>
                    <div style={{ position: 'relative' }}>
                      <KeyRound size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: '#94a3b8' }} />
                      <input type={showPassword ? 'text' : 'password'} value={formData.password}
                        onChange={e => setFormData({ ...formData, password: e.target.value })}
                        style={{ ...inputStyle, borderColor: formErrors.password ? '#ef4444' : '#e2e8f0' }} placeholder="Enter password" />
                      <button type="button" onClick={() => setShowPassword(!showPassword)}
                        style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8' }}>
                        {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                      </button>
                    </div>
                    {pw.length > 0 && (
                      <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', marginTop: 6 }}>
                        {pwChecks.map((c, i) => (
                          <span key={i} style={{ fontSize: 10, padding: '2px 6px', borderRadius: 10, background: c.ok ? '#f0fdf4' : '#f8fafc', color: c.ok ? '#16a34a' : '#94a3b8', border: `1px solid ${c.ok ? '#bbf7d0' : '#e2e8f0'}` }}>
                            {c.ok ? '✓' : '○'} {c.label}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 8 }}>
                  <button onClick={() => setIsModalOpen(false)} style={cancelBtnStyle}>Cancel</button>
                  <button onClick={handleSaveUser} style={primaryBtnStyle}>Save</button>
                </div>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* ═══════ RESET PASSWORD MODAL ═══════ */}
      <AnimatePresence>
        {resetModal && (
          <div style={overlayStyle}>
            <motion.div initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.95, opacity: 0 }}
              style={{ background: '#fff', padding: 28, borderRadius: 16, width: '100%', maxWidth: 420 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <KeyRound size={18} color="#f59e0b" /> Reset Password
                </h2>
                <button onClick={() => setResetModal(null)} style={closeBtnStyle}><X size={18} /></button>
              </div>
              <div style={{ background: '#fffbeb', padding: '10px 14px', borderRadius: 8, marginBottom: 14, border: '1px solid #fde68a', fontSize: 12, color: '#92400e', display: 'flex', alignItems: 'center', gap: 8 }}>
                <AlertTriangle size={14} /> Setting a new password for <strong>{resetModal.username}</strong>
              </div>
              <div style={{ position: 'relative', marginBottom: 14 }}>
                <input type={showResetPw ? 'text' : 'password'} value={resetPw} onChange={e => setResetPw(e.target.value)}
                  style={{ ...inputStyle, paddingLeft: 12 }} placeholder="New password" autoFocus />
                <button type="button" onClick={() => setShowResetPw(!showResetPw)}
                  style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8' }}>
                  {showResetPw ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
                <button onClick={() => setResetModal(null)} style={cancelBtnStyle}>Cancel</button>
                <button onClick={handleResetPassword} disabled={!resetPw}
                  style={{ ...primaryBtnStyle, background: '#f59e0b', opacity: resetPw ? 1 : 0.5 }}>Reset</button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* ═══════ APPROVE REQUEST MODAL ═══════ */}
      <AnimatePresence>
        {approveModal && (
          <div style={overlayStyle}>
            <motion.div initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.95, opacity: 0 }}
              style={{ background: '#fff', padding: 28, borderRadius: 16, width: '100%', maxWidth: 480 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Approve Access Request</h2>
                <button onClick={() => setApproveModal(null)} style={closeBtnStyle}><X size={18} /></button>
              </div>

              <div style={{ background: '#f0fdf4', padding: '10px 14px', borderRadius: 8, marginBottom: 16, border: '1px solid #bbf7d0', fontSize: 13 }}>
                <strong>{approveModal.displayName || approveModal.email}</strong><br />
                <span style={{ color: '#64748b' }}>{approveModal.email}</span>
                {approveModal.message && <div style={{ marginTop: 6, fontStyle: 'italic', color: '#6b7280' }}>"{approveModal.message}"</div>}
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div>
                  <label style={labelStyle}>Assign to Tenant *</label>
                  <select value={approveData.tenantId} onChange={e => setApproveData({ ...approveData, tenantId: e.target.value })}
                    style={{ ...inputStyle, paddingLeft: 12 }}>
                    <option value="">Select Tenant...</option>
                    {banks.map(b => <option key={b.tenantId} value={b.tenantId}>{b.bankName}</option>)}
                  </select>
                </div>
                <div>
                  <label style={labelStyle}>Assign Group *</label>
                  <select value={approveData.groupId} onChange={e => setApproveData({ ...approveData, groupId: e.target.value })}
                    style={{ ...inputStyle, paddingLeft: 12 }}>
                    <option value="">Select Group...</option>
                    {groups.map(g => <option key={g.groupId || g.id} value={g.groupId || g.id}>{g.groupName}</option>)}
                  </select>
                </div>
                <div>
                  <label style={labelStyle}>Notes (optional)</label>
                  <input value={approveData.reviewNotes} onChange={e => setApproveData({ ...approveData, reviewNotes: e.target.value })}
                    style={{ ...inputStyle, paddingLeft: 12 }} placeholder="Approval notes..." />
                </div>
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
                  <button onClick={() => setApproveModal(null)} style={cancelBtnStyle}>Cancel</button>
                  <button onClick={handleApprove} style={{ ...primaryBtnStyle, background: '#10b981' }}>Approve & Create User</button>
                </div>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
};

// ─── Reusable Field Component ────────────────────────────
const Field = ({ label, icon: Icon, value, onChange, error, type = 'text', disabled, placeholder }) => (
  <div>
    <label style={labelStyle}>{label}</label>
    <div style={{ position: 'relative' }}>
      {Icon && <Icon size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: '#94a3b8' }} />}
      <input type={type} value={value} onChange={e => onChange(e.target.value)} disabled={disabled}
        style={{ ...inputStyle, borderColor: error ? '#ef4444' : '#e2e8f0', background: disabled ? '#f8fafc' : '#fff' }} placeholder={placeholder} />
    </div>
    {error && <div style={{ fontSize: 11, color: '#ef4444', marginTop: 2 }}>{error}</div>}
  </div>
);

// ─── Styles ──────────────────────────────────────────────
const overlayStyle = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 50 };
const labelStyle = { display: 'block', marginBottom: 5, fontSize: 12, fontWeight: 600, color: '#374151' };
const inputStyle = { width: '100%', padding: '10px 12px 10px 38px', borderRadius: 10, border: '1px solid #e2e8f0', boxSizing: 'border-box', fontSize: 13, outline: 'none' };
const errorBoxStyle = { background: '#fef2f2', color: '#dc2626', padding: '10px 14px', borderRadius: 8, fontSize: 13, border: '1px solid #fecaca', marginBottom: 12 };
const closeBtnStyle = { background: 'none', border: 'none', cursor: 'pointer', color: '#64748b', padding: 4 };
const cancelBtnStyle = { padding: '10px 20px', borderRadius: 10, background: '#f1f5f9', border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 500 };
const primaryBtnStyle = { padding: '10px 20px', borderRadius: 10, background: '#0f172a', color: '#fff', border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600 };
const actionBtnStyle = (color) => ({ background: 'transparent', border: 'none', cursor: 'pointer', color, padding: 6, borderRadius: 6 });
const thSm = { padding: '6px 10px', textAlign: 'left', fontSize: 11, fontWeight: 600, color: '#64748b', textTransform: 'uppercase' };
const tdSm = { padding: '8px 10px', fontSize: 13 };
const selectSm = { padding: '7px 10px', borderRadius: 8, border: '1px solid #e2e8f0', fontSize: 12, background: '#fff', minWidth: 140 };

export default UserManagement;
