import React, { useState, useEffect, useCallback } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import {
  Plus, Edit2, X, Unlock, KeyRound, Mail, User as UserIcon,
  Eye, EyeOff, Check, AlertTriangle, Search, Building2,
  Trash2, Star, Globe, Clock, CheckCircle, XCircle, Users, Inbox
} from 'lucide-react';
import api from '../api/axios';

/* ─────────────────────────────────────────────────────────────
   Design tokens — single source of truth. Every colour routes
   through a CSS variable with a sensible light-mode fallback, so
   the page stays consistent and adapts cleanly in dark mode.
   ───────────────────────────────────────────────────────────── */
const T = {
  brand:      'var(--brand, #2563eb)',
  brandText:  '#ffffff',
  success:    'var(--success, #10b981)',
  successBg:  'var(--success-bg, #f0fdf4)',
  successBd:  'var(--success-border, #bbf7d0)',
  successTx:  'var(--success-text, #166534)',
  danger:     'var(--danger, #ef4444)',
  dangerBg:   'var(--danger-bg, #fef2f2)',
  dangerBd:   'var(--danger-border, #fecaca)',
  dangerTx:   'var(--danger-text, #dc2626)',
  warning:    'var(--warning, #f59e0b)',
  warningBg:  'var(--warning-bg, #fffbeb)',
  warningBd:  'var(--warning-border, #fde68a)',
  warningTx:  'var(--warning-text, #92400e)',
  infoBg:     'var(--info-bg, #f0f9ff)',
  bg:         'var(--bg, #f1f5f9)',
  card:       'var(--bg-card, #ffffff)',
  subtle:     'var(--bg-subtle, #f8fafc)',
  border:     'var(--border, #e2e8f0)',
  text:       'var(--text, #1e293b)',
  textSec:    'var(--text-secondary, #64748b)',
  textMut:    'var(--text-muted, #94a3b8)',
  radius:     'var(--radius-md, 10px)',
  radiusLg:   'var(--radius-lg, 14px)',
};

const card = { background: T.card, borderRadius: T.radiusLg, boxShadow: 'var(--shadow-xs, 0 1px 2px rgba(16,23,38,.05))', border: `1px solid ${T.border}` };
const badge = (bg, fg) => ({ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 10px', borderRadius: 999, fontSize: 11, fontWeight: 600, background: bg, color: fg, whiteSpace: 'nowrap' });

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
  const [formData, setFormData] = useState({ username: '', email: '', displayName: '', password: '', active: true, tenantAssignments: [{ tenantId: '', groupId: '', isDefault: true }] });
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

  // Reject modal (replaces window.prompt)
  const [rejectModal, setRejectModal] = useState(null);
  const [rejectNotes, setRejectNotes] = useState('');

  // Generic confirm modal (replaces window.confirm)
  const [confirmState, setConfirmState] = useState(null); // { title, message, confirmLabel, danger, onConfirm }

  const [notification, setNotification] = useState(null);
  const [showPassword, setShowPassword] = useState(false);

  const notify = (msg, type = 'success') => { setNotification({ msg, type }); setTimeout(() => setNotification(null), 4000); };

  const closeAllOverlays = useCallback(() => {
    setIsModalOpen(false); setResetModal(null); setApproveModal(null);
    setRejectModal(null); setConfirmState(null);
  }, []);

  // Esc closes whichever overlay is open
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') closeAllOverlays(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [closeAllOverlays]);

  // Reset to page 1 whenever the result set changes (fixes empty-page bug)
  useEffect(() => { setCurrentPage(1); }, [searchQuery, statusFilter, activeTab]);

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
  const totalPages = Math.ceil(filteredUsers.length / PAGE_SIZE);

  // ─── User CRUD ─────────────────────────────────────────
  const openCreateModal = () => {
    setModalUser(null);
    // If the admin only has one tenant available (typical for a bank admin now
    // that /banks is tenant-scoped), pre-select it so they don't have to.
    const onlyTenantId = banks.length === 1 ? String(banks[0].tenantId) : '';
    setFormData({ username: '', email: '', displayName: '', password: '', active: true, tenantAssignments: [{ tenantId: onlyTenantId, groupId: '', isDefault: true }] });
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
    if (!modalUser) {
      const validAssignments = (formData.tenantAssignments || []).filter(a => a.tenantId && a.groupId);
      if (validAssignments.length === 0) errors.tenants = 'At least one tenant assignment is required';
    }
    setFormErrors(errors);
    if (Object.keys(errors).length) return;

    try {
      if (modalUser) {
        await api.put(`/users/${modalUser.id}`, { ...formData, id: modalUser.id });
        notify('User updated');
      } else {
        // Create user then assign all selected tenants.
        const res = await api.post('/users', formData);
        const newUserId = res.data?.id;
        const validAssignments = (formData.tenantAssignments || []).filter(a => a.tenantId && a.groupId);
        let assignedOk = 0;
        const failed = [];
        if (newUserId) {
          for (const assignment of validAssignments) {
            try {
              await api.post(`/users/${newUserId}/tenant-access`, {
                tenantId: assignment.tenantId,
                groupId: assignment.groupId,
                isDefault: assignment.isDefault || false
              });
              assignedOk++;
            } catch (te) {
              // Tenant-isolation: the server rejects assigning a tenant the
              // caller doesn't administer. Surface it instead of silently
              // leaving an orphaned (tenant-less, invisible) user.
              const bank = banks.find(b => String(b.tenantId) === String(assignment.tenantId));
              failed.push(bank?.bankName || `tenant ${assignment.tenantId}`);
            }
          }
        }

        if (validAssignments.length > 0 && assignedOk === 0) {
          // Every assignment failed → the user exists but has no tenant access
          // and won't appear in a bank admin's scoped list. Tell the truth.
          notify(`User created, but tenant assignment failed: ${failed.join(', ')}. ` +
                 `You may not have permission to assign those tenants.`, 'error');
        } else if (failed.length > 0) {
          notify(`User created. Some tenant assignments failed: ${failed.join(', ')}.`, 'error');
        } else {
          notify('User created');
        }
      }
      setIsModalOpen(false);
      fetchAll();
    } catch (e) { setFormErrors({ _: e.response?.data?.error || 'Failed to save' }); }
  };

  // Focused payload (no longer spreads tenants/role into the PUT)
  const doToggleActive = async (user) => {
    try {
      await api.put(`/users/${user.id}`, {
        id: user.id, username: user.username, email: user.email,
        displayName: user.displayName, active: !user.active, password: ''
      });
      notify(user.active ? 'User deactivated' : 'User activated');
      fetchAll();
    } catch (e) { notify(e.response?.data?.error || 'Failed to update status', 'error'); }
  };

  const requestToggleActive = (user) => {
    setConfirmState({
      title: user.active ? 'Deactivate user' : 'Activate user',
      message: user.active
        ? `${user.displayName || user.username} will be unable to sign in until reactivated.`
        : `${user.displayName || user.username} will be able to sign in again.`,
      confirmLabel: user.active ? 'Deactivate' : 'Activate',
      danger: user.active,
      onConfirm: () => { doToggleActive(user); setConfirmState(null); }
    });
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

  const doRemoveTenantAccess = async (userId, accessId) => {
    try {
      await api.delete(`/users/${userId}/tenant-access/${accessId}`);
      const res = await api.get(`/users/${userId}/tenant-access`);
      setUserAccesses(res.data);
      fetchAll();
    } catch (e) { notify(e.response?.data?.error || 'Failed to remove access', 'error'); }
  };

  const requestRemoveTenantAccess = (userId, accessId, tenantName) => {
    setConfirmState({
      title: 'Remove tenant access',
      message: `Remove access to ${tenantName || 'this tenant'}? The user will lose visibility of its data.`,
      confirmLabel: 'Remove',
      danger: true,
      onConfirm: () => { doRemoveTenantAccess(userId, accessId); setConfirmState(null); }
    });
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

  const handleReject = async () => {
    try {
      await api.post(`/admin/access-requests/${rejectModal.requestId}/reject`, { reviewNotes: rejectNotes || '' });
      notify('Request rejected');
      setRejectModal(null); setRejectNotes('');
      fetchAll();
    } catch (e) { notify(e.response?.data?.error || 'Failed to reject', 'error'); }
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
    <div style={{ padding: 'var(--space-page, 24px)', color: T.text, maxWidth: 1400, margin: '0 auto' }}>
      {/* Scoped styles: hover/focus/media-queries/keyframes can't live in inline styles */}
      <style>{`
        .um-user-row{display:grid;grid-template-columns:1fr 180px 130px 120px 150px;align-items:center;padding:14px 20px;gap:12px;transition:background .15s}
        .um-user-row:hover{background:${T.subtle}}
        .um-action{background:transparent;border:none;cursor:pointer;padding:7px;border-radius:8px;display:inline-flex;align-items:center;justify-content:center;transition:background .15s}
        .um-action:hover{background:${T.subtle}}
        .um-action:focus-visible,.um-btn:focus-visible,.um-input:focus-visible,.um-tab:focus-visible{outline:2px solid ${T.brand};outline-offset:2px}
        .um-input:focus{border-color:${T.brand}}
        .um-switch{width:42px;height:24px;border-radius:999px;border:none;cursor:pointer;position:relative;padding:0;transition:background .2s;flex-shrink:0}
        .um-switch span{position:absolute;top:3px;left:3px;width:18px;height:18px;border-radius:50%;background:#fff;transition:transform .2s;box-shadow:0 1px 2px rgba(0,0,0,.2)}
        .um-switch[data-on="true"] span{transform:translateX(18px)}
        .um-skel{background:linear-gradient(90deg,${T.subtle} 25%,${T.border} 37%,${T.subtle} 63%);background-size:400% 100%;animation:umShimmer 1.4s ease infinite;border-radius:6px}
        @keyframes umShimmer{0%{background-position:100% 50%}100%{background-position:0 50%}}
        @media (max-width:860px){
          .um-user-row{grid-template-columns:1fr;gap:10px}
          .um-user-cell-actions{justify-content:flex-start !important}
        }
      `}</style>

      {/* Notification */}
      <AnimatePresence>
        {notification && (
          <motion.div role="status" aria-live="polite" initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -20 }}
            style={{ position: 'fixed', top: 20, right: 20, zIndex: 100, padding: '12px 20px', borderRadius: 12,
              background: notification.type === 'error' ? T.dangerBg : T.successBg,
              color: notification.type === 'error' ? T.dangerTx : T.successTx,
              border: `1px solid ${notification.type === 'error' ? T.dangerBd : T.successBd}`,
              boxShadow: '0 8px 24px rgba(0,0,0,.12)', fontSize: 13, fontWeight: 500, display: 'flex', alignItems: 'center', gap: 8 }}>
            {notification.type === 'error' ? <XCircle size={16} /> : <Check size={16} />} {notification.msg}
          </motion.div>
        )}
      </AnimatePresence>

      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 24, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0 }}>User &amp; Access Management</h1>
          <p style={{ fontSize: 13, color: T.textSec, margin: '4px 0 0' }}>Manage users, tenant assignments, SSO access, and approval requests</p>
        </div>
        <button className="um-btn" onClick={openCreateModal} style={{ display: 'flex', alignItems: 'center', gap: 6, background: T.brand, color: T.brandText, padding: '10px 16px', borderRadius: T.radius, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
          <Plus size={16} /> Create User
        </button>
      </div>

      {/* Tabs */}
      <div role="tablist" aria-label="User management sections" style={{ display: 'flex', gap: 2, marginBottom: 20, background: T.bg, borderRadius: T.radius, padding: 3 }}>
        {[
          { key: 'users', label: 'Users', icon: UserIcon, count: users.length },
          { key: 'requests', label: 'Access Requests', icon: Clock, count: pendingCount },
        ].map(tab => (
          <button key={tab.key} className="um-tab" role="tab" aria-selected={activeTab === tab.key} onClick={() => setActiveTab(tab.key)} style={{
            flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            padding: '10px 16px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
            background: activeTab === tab.key ? T.card : 'transparent', color: activeTab === tab.key ? T.brand : T.textSec,
            boxShadow: activeTab === tab.key ? 'var(--shadow-xs, 0 1px 2px rgba(16,23,38,.05))' : 'none', transition: 'all .2s' }}>
            <tab.icon size={16} /> {tab.label}
            {tab.key === 'requests' && pendingCount > 0 && (
              <span style={{ background: T.danger, color: '#fff', fontSize: 10, fontWeight: 700, padding: '1px 6px', borderRadius: 999, minWidth: 18, textAlign: 'center' }}>{pendingCount}</span>
            )}
            {tab.key === 'users' && <span style={{ color: T.textMut, fontSize: 12 }}>({tab.count})</span>}
          </button>
        ))}
      </div>

      {/* ═══════ USERS TAB ═══════ */}
      {activeTab === 'users' && (
        <>
          {/* Toolbar */}
          <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1, minWidth: 220 }}>
              <Search size={14} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: T.textMut }} />
              <input className="um-input" aria-label="Search users" placeholder="Search users..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
                style={{ width: '100%', padding: '9px 12px 9px 34px', borderRadius: T.radius, border: `1px solid ${T.border}`, fontSize: 13, outline: 'none', boxSizing: 'border-box', background: T.card, color: T.text }} />
            </div>
            <select className="um-input" aria-label="Filter by status" value={statusFilter} onChange={e => setStatusFilter(e.target.value)}
              style={{ padding: '9px 12px', borderRadius: T.radius, border: `1px solid ${T.border}`, fontSize: 13, background: T.card, color: T.text, minWidth: 130 }}>
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
              // Skeleton rows
              [...Array(6)].map((_, i) => (
                <div key={i} className="um-user-row" style={{ borderBottom: `1px solid ${T.border}` }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <div className="um-skel" style={{ width: 36, height: 36, borderRadius: '50%' }} />
                    <div style={{ flex: 1 }}>
                      <div className="um-skel" style={{ width: '55%', height: 12, marginBottom: 7 }} />
                      <div className="um-skel" style={{ width: '40%', height: 10 }} />
                    </div>
                  </div>
                  <div className="um-skel" style={{ height: 18, width: 110 }} />
                  <div className="um-skel" style={{ height: 18, width: 70 }} />
                  <div className="um-skel" style={{ height: 18, width: 60 }} />
                  <div className="um-skel" style={{ height: 18, width: 120 }} />
                </div>
              ))
            ) : filteredUsers.length === 0 ? (
              <EmptyState
                icon={Users}
                title={searchQuery || statusFilter !== 'ALL' ? 'No matching users' : 'No users yet'}
                hint={searchQuery || statusFilter !== 'ALL' ? 'Try a different search or filter.' : 'Create your first user to get started.'}
                action={(searchQuery || statusFilter !== 'ALL')
                  ? { label: 'Clear filters', onClick: () => { setSearchQuery(''); setStatusFilter('ALL'); } }
                  : { label: 'Create User', onClick: openCreateModal }}
              />
            ) : filteredUsers.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE).map(user => {
              const isExpanded = editingAccess === user.id;
              return (
                <div key={user.id} style={{ borderBottom: `1px solid ${T.border}` }}>
                  {/* User Row */}
                  <div className="um-user-row">
                    {/* Name/Email */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, minWidth: 0 }}>
                      <div style={{ width: 36, height: 36, borderRadius: '50%', background: user.ssoProvider ? '#e0e7ff' : T.subtle, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, fontWeight: 700, color: user.ssoProvider ? '#4338ca' : T.textSec, flexShrink: 0 }}>
                        {user.ssoProvider ? <Globe size={16} /> : (user.username?.[0]?.toUpperCase() || '?')}
                      </div>
                      <div style={{ overflow: 'hidden' }}>
                        <div style={{ fontSize: 14, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                          {user.displayName || user.username}
                          {user.ssoProvider && <span style={badge('#e0e7ff', '#4338ca')}>SSO</span>}
                          {user.mustChangePassword && !user.ssoProvider && <span style={badge('#fef9c3', '#854d0e')}>Must change PW</span>}
                          {isLocked(user) && <span style={badge(T.dangerBg, T.dangerTx)}>LOCKED</span>}
                        </div>
                        <div style={{ fontSize: 12, color: T.textMut, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{user.email || user.username}</div>
                      </div>
                    </div>

                    {/* Tenants */}
                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                      {(user.tenants || []).length === 0 && <span style={{ fontSize: 11, color: T.textMut }}>No tenant</span>}
                      {(user.tenants || []).map((t, i) => (
                        <span key={i} style={{ ...badge(T.infoBg, '#0369a1'), gap: 3 }}>
                          <Building2 size={10} /> {t.tenantName?.substring(0, 15)}
                          {t.isDefault && <Star size={9} fill={T.warning} color={T.warning} />}
                        </span>
                      ))}
                    </div>

                    {/* Role */}
                    <span style={badge(T.subtle, T.textSec)}>{user.role?.replace('ROLE_', '') || 'USER'}</span>

                    {/* Status — real toggle with confirmation */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <button className="um-switch" data-on={!!user.active} aria-label={user.active ? 'Deactivate user' : 'Activate user'} aria-pressed={!!user.active}
                        onClick={() => requestToggleActive(user)} style={{ background: user.active ? T.success : T.border }}>
                        <span />
                      </button>
                      <span style={{ fontSize: 12, fontWeight: 500, color: user.active ? T.success : T.textMut }}>{user.active ? 'Active' : 'Inactive'}</span>
                    </div>

                    {/* Actions */}
                    <div className="um-user-cell-actions" style={{ display: 'flex', gap: 2, justifyContent: 'flex-end' }}>
                      <button className="um-action" onClick={() => openAccessPanel(user.id)} aria-label="Tenant assignments" title="Tenant assignments" style={{ color: isExpanded ? T.brand : T.textSec }}>
                        <Building2 size={15} />
                      </button>
                      <button className="um-action" onClick={() => openEditModal(user)} aria-label="Edit user" title="Edit" style={{ color: T.brand }}>
                        <Edit2 size={15} />
                      </button>
                      {/* GAP-14: Hide Reset PW for SSO-only users */}
                      {!user.ssoProvider && (
                        <button className="um-action" onClick={() => { setResetModal(user); setResetPw(''); setShowResetPw(false); }} aria-label="Reset password" title="Reset PW" style={{ color: T.warning }}>
                          <KeyRound size={15} />
                        </button>
                      )}
                      {isLocked(user) && (
                        <button className="um-action" onClick={() => handleUnlock(user)} aria-label="Unlock account" title="Unlock" style={{ color: T.danger }}>
                          <Unlock size={15} />
                        </button>
                      )}
                    </div>
                  </div>

                  {/* Expanded Tenant Access Panel */}
                  {isExpanded && (
                    <div style={{ background: T.subtle, borderTop: `1px solid ${T.border}`, padding: '16px 20px 16px 68px' }}>
                      <div style={{ fontSize: 12, fontWeight: 700, color: T.textSec, marginBottom: 10, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                        Tenant Assignments — {user.username}
                      </div>

                      {userAccesses.length > 0 && (
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginBottom: 12 }}>
                          <thead>
                            <tr style={{ borderBottom: `2px solid ${T.border}` }}>
                              <th style={thSm}>Tenant</th>
                              <th style={thSm}>Group</th>
                              <th style={thSm}>Default</th>
                              <th style={{ ...thSm, width: 60 }}></th>
                            </tr>
                          </thead>
                          <tbody>
                            {userAccesses.map(a => (
                              <tr key={a.accessId} style={{ borderBottom: `1px solid ${T.border}` }}>
                                <td style={tdSm}>{a.tenantName}</td>
                                <td style={tdSm}><span style={badge(T.successBg, T.successTx)}>{a.groupName || '—'}</span></td>
                                <td style={tdSm}>{a.isDefault ? <Star size={14} fill={T.warning} color={T.warning} /> : '—'}</td>
                                <td style={tdSm}>
                                  <button className="um-action" onClick={() => requestRemoveTenantAccess(user.id, a.accessId, a.tenantName)} aria-label="Remove access" style={{ color: T.danger }}>
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
                        <select className="um-input" aria-label="Select tenant" value={newAccess.tenantId} onChange={e => setNewAccess({ ...newAccess, tenantId: e.target.value })}
                          style={selectSm}><option value="">Select Tenant...</option>
                          {banks.map(b => <option key={b.tenantId} value={b.tenantId}>{b.bankName}</option>)}
                        </select>
                        <select className="um-input" aria-label="Select group" value={newAccess.groupId} onChange={e => setNewAccess({ ...newAccess, groupId: e.target.value })}
                          style={selectSm}><option value="">Select Group...</option>
                          {groups.map(g => <option key={g.groupId || g.id} value={g.groupId || g.id}>{g.groupName}</option>)}
                        </select>
                        <label style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
                          <input type="checkbox" checked={newAccess.isDefault} onChange={e => setNewAccess({ ...newAccess, isDefault: e.target.checked })} /> Default
                        </label>
                        <button className="um-btn" onClick={() => addTenantAccess(user.id)} disabled={!newAccess.tenantId || !newAccess.groupId}
                          style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '7px 14px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600, background: T.brand, color: '#fff', opacity: (!newAccess.tenantId || !newAccess.groupId) ? 0.5 : 1 }}>
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
          {!loading && filteredUsers.length > PAGE_SIZE && (
            <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 8, padding: '16px 20px' }}>
              <button className="um-btn" onClick={() => setCurrentPage(p => Math.max(1, p - 1))} disabled={currentPage === 1}
                style={pagerBtn(currentPage === 1)}>← Prev</button>
              <span style={{ fontSize: 12, color: T.textSec }}>Page {currentPage} of {totalPages} ({filteredUsers.length} users)</span>
              <button className="um-btn" onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))} disabled={currentPage >= totalPages}
                style={pagerBtn(currentPage >= totalPages)}>Next →</button>
            </div>
          )}
        </>
      )}

      {/* ═══════ ACCESS REQUESTS TAB ═══════ */}
      {activeTab === 'requests' && (
        <div style={card}>
          {loading ? (
            <div style={{ padding: 24 }}>{[...Array(3)].map((_, i) => <div key={i} className="um-skel" style={{ height: 56, marginBottom: 10 }} />)}</div>
          ) : requests.length === 0 ? (
            <EmptyState icon={Inbox} title="No access requests" hint="Approval requests from SSO sign-ins will appear here." />
          ) : requests.map(r => (
            <div key={r.requestId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '16px 20px', borderBottom: `1px solid ${T.border}`, gap: 16, flexWrap: 'wrap' }}>
              <div style={{ flex: 1, minWidth: 200 }}>
                <div style={{ fontSize: 14, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  {r.displayName || r.email}
                  <span style={badge(
                    r.status === 'PENDING' ? '#fef9c3' : r.status === 'APPROVED' ? T.successBg : T.dangerBg,
                    r.status === 'PENDING' ? '#854d0e' : r.status === 'APPROVED' ? T.successTx : '#991b1b'
                  )}>{r.status}</span>
                  {r.ssoProvider && <span style={badge('#e0e7ff', '#4338ca')}><Globe size={10} /> {r.ssoProvider}</span>}
                </div>
                <div style={{ fontSize: 12, color: T.textSec, marginTop: 2 }}>{r.email}</div>
                {r.tenantName && <div style={{ fontSize: 12, color: T.textMut, marginTop: 2 }}>Requested: {r.tenantName}</div>}
                {r.message && <div style={{ fontSize: 12, color: T.textSec, marginTop: 4, fontStyle: 'italic' }}>"{r.message}"</div>}
                <div style={{ fontSize: 11, color: T.textMut, marginTop: 4 }}>{new Date(r.createdAt).toLocaleString()}</div>
              </div>

              {r.status === 'PENDING' && (
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className="um-btn" onClick={() => { setApproveModal(r); setApproveData({ tenantId: r.tenantId || '', groupId: '', reviewNotes: '' }); }}
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 16px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600, background: T.success, color: '#fff' }}>
                    <CheckCircle size={14} /> Approve
                  </button>
                  <button className="um-btn" onClick={() => { setRejectModal(r); setRejectNotes(''); }}
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 16px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600, background: T.danger, color: '#fff' }}>
                    <XCircle size={14} /> Reject
                  </button>
                </div>
              )}
              {r.status !== 'PENDING' && r.reviewNotes && (
                <div style={{ fontSize: 12, color: T.textMut, maxWidth: 200 }}>Note: {r.reviewNotes}</div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* ═══════ CREATE / EDIT USER MODAL ═══════ */}
      <AnimatePresence>
        {isModalOpen && (
          <Overlay onClose={() => setIsModalOpen(false)}>
            <ModalCard maxWidth={480} label={modalUser ? 'Edit user' : 'Create user'}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2 style={{ fontSize: 18, fontWeight: 700, margin: 0 }}>{modalUser ? 'Edit User' : 'Create User'}</h2>
                <button className="um-action" onClick={() => setIsModalOpen(false)} aria-label="Close"><X size={18} /></button>
              </div>

              {formErrors._ && <div style={errorBoxStyle}>{formErrors._}</div>}

              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <Field id="um-username" label="Username" icon={UserIcon} value={formData.username} disabled={!!modalUser}
                  onChange={v => setFormData({ ...formData, username: v })} error={formErrors.username} />
                <Field id="um-email" label="Email" icon={Mail} type="email" value={formData.email}
                  onChange={v => setFormData({ ...formData, email: v })} error={formErrors.email} />
                <Field id="um-display" label="Display Name" icon={UserIcon} value={formData.displayName}
                  onChange={v => setFormData({ ...formData, displayName: v })} placeholder="Optional" />

                {/* Multi-tenant assignment for new users */}
                {!modalUser && (
                  <div>
                    {formErrors.tenants && <div style={{ ...errorBoxStyle, marginBottom: 8, padding: '8px 12px', fontSize: 12 }}>{formErrors.tenants}</div>}
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                      <label style={{ ...labelStyle, marginBottom: 0 }}>Tenant Assignments</label>
                      <button type="button" className="um-btn" onClick={() => setFormData({ ...formData, tenantAssignments: [...formData.tenantAssignments, { tenantId: '', groupId: '', isDefault: false }] })}
                        style={{ background: 'none', border: `1px dashed ${T.border}`, borderRadius: 6, padding: '3px 10px', cursor: 'pointer', fontSize: 11, fontWeight: 600, color: T.brand, display: 'flex', alignItems: 'center', gap: 4 }}>
                        <Plus size={12} /> Add Tenant
                      </button>
                    </div>
                    {formData.tenantAssignments.map((assignment, idx) => (
                      <div key={idx} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto auto', gap: 8, alignItems: 'center', marginBottom: 8, padding: '8px 10px', background: T.subtle, borderRadius: 8, border: `1px solid ${T.border}` }}>
                        <select className="um-input" aria-label="Tenant" value={assignment.tenantId} onChange={e => {
                          const updated = [...formData.tenantAssignments];
                          updated[idx] = { ...updated[idx], tenantId: e.target.value };
                          setFormData({ ...formData, tenantAssignments: updated });
                        }} style={{ ...inputStyle, paddingLeft: 12, fontSize: 12 }}>
                          <option value="">Select Tenant...</option>
                          {banks.filter(b => !formData.tenantAssignments.some((a, i) => i !== idx && a.tenantId === String(b.tenantId)))
                            .map(b => <option key={b.tenantId} value={b.tenantId}>{b.bankName}</option>)}
                        </select>
                        <select className="um-input" aria-label="Group" value={assignment.groupId} onChange={e => {
                          const updated = [...formData.tenantAssignments];
                          updated[idx] = { ...updated[idx], groupId: e.target.value };
                          setFormData({ ...formData, tenantAssignments: updated });
                        }} style={{ ...inputStyle, paddingLeft: 12, fontSize: 12 }}>
                          <option value="">Select Group...</option>
                          {groups.map(g => <option key={g.groupId || g.id} value={g.groupId || g.id}>{g.groupName}</option>)}
                        </select>
                        <label style={{ fontSize: 11, display: 'flex', alignItems: 'center', gap: 3, cursor: 'pointer', whiteSpace: 'nowrap' }}>
                          <input type="radio" name="defaultTenant" checked={assignment.isDefault}
                            onChange={() => {
                              const updated = formData.tenantAssignments.map((a, i) => ({ ...a, isDefault: i === idx }));
                              setFormData({ ...formData, tenantAssignments: updated });
                            }} /> Default
                        </label>
                        {formData.tenantAssignments.length > 1 && (
                          <button type="button" className="um-action" aria-label="Remove tenant assignment" onClick={() => {
                            const updated = formData.tenantAssignments.filter((_, i) => i !== idx);
                            if (assignment.isDefault && updated.length > 0) updated[0].isDefault = true;
                            setFormData({ ...formData, tenantAssignments: updated });
                          }} style={{ color: T.danger }}>
                            <Trash2 size={14} />
                          </button>
                        )}
                      </div>
                    ))}
                  </div>
                )}

                {!modalUser && (
                  <div>
                    <label htmlFor="um-password" style={labelStyle}>Password</label>
                    <div style={{ position: 'relative' }}>
                      <KeyRound size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: T.textMut }} />
                      <input id="um-password" className="um-input" type={showPassword ? 'text' : 'password'} value={formData.password}
                        onChange={e => setFormData({ ...formData, password: e.target.value })}
                        style={{ ...inputStyle, borderColor: formErrors.password ? T.danger : T.border }} placeholder="Enter password" />
                      <button type="button" className="um-action" onClick={() => setShowPassword(!showPassword)} aria-label={showPassword ? 'Hide password' : 'Show password'}
                        style={{ position: 'absolute', right: 6, top: '50%', transform: 'translateY(-50%)', color: T.textMut }}>
                        {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                      </button>
                    </div>
                    {pw.length > 0 && (
                      <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', marginTop: 6 }}>
                        {pwChecks.map((c, i) => (
                          <span key={i} style={{ fontSize: 10, padding: '2px 6px', borderRadius: 999, background: c.ok ? T.successBg : T.subtle, color: c.ok ? T.successTx : T.textMut, border: `1px solid ${c.ok ? T.successBd : T.border}` }}>
                            {c.ok ? '✓' : '○'} {c.label}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 8 }}>
                  <button className="um-btn" onClick={() => setIsModalOpen(false)} style={cancelBtnStyle}>Cancel</button>
                  <button className="um-btn" onClick={handleSaveUser} style={primaryBtnStyle}>Save</button>
                </div>
              </div>
            </ModalCard>
          </Overlay>
        )}
      </AnimatePresence>

      {/* ═══════ RESET PASSWORD MODAL ═══════ */}
      <AnimatePresence>
        {resetModal && (
          <Overlay onClose={() => setResetModal(null)}>
            <ModalCard maxWidth={420} label="Reset password">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <KeyRound size={18} color={T.warning} /> Reset Password
                </h2>
                <button className="um-action" onClick={() => setResetModal(null)} aria-label="Close"><X size={18} /></button>
              </div>
              <div style={{ background: T.warningBg, padding: '10px 14px', borderRadius: 8, marginBottom: 14, border: `1px solid ${T.warningBd}`, fontSize: 12, color: T.warningTx, display: 'flex', alignItems: 'center', gap: 8 }}>
                <AlertTriangle size={14} /> Setting a new password for <strong>{resetModal.username}</strong>
              </div>
              <div style={{ position: 'relative', marginBottom: 14 }}>
                <input className="um-input" type={showResetPw ? 'text' : 'password'} value={resetPw} onChange={e => setResetPw(e.target.value)}
                  style={{ ...inputStyle, paddingLeft: 12 }} placeholder="New password" autoFocus aria-label="New password" />
                <button type="button" className="um-action" onClick={() => setShowResetPw(!showResetPw)} aria-label={showResetPw ? 'Hide password' : 'Show password'}
                  style={{ position: 'absolute', right: 6, top: '50%', transform: 'translateY(-50%)', color: T.textMut }}>
                  {showResetPw ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
                <button className="um-btn" onClick={() => setResetModal(null)} style={cancelBtnStyle}>Cancel</button>
                <button className="um-btn" onClick={handleResetPassword} disabled={!resetPw}
                  style={{ ...primaryBtnStyle, background: T.warning, opacity: resetPw ? 1 : 0.5 }}>Reset</button>
              </div>
            </ModalCard>
          </Overlay>
        )}
      </AnimatePresence>

      {/* ═══════ APPROVE REQUEST MODAL ═══════ */}
      <AnimatePresence>
        {approveModal && (
          <Overlay onClose={() => setApproveModal(null)}>
            <ModalCard maxWidth={480} label="Approve access request">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0 }}>Approve Access Request</h2>
                <button className="um-action" onClick={() => setApproveModal(null)} aria-label="Close"><X size={18} /></button>
              </div>

              <div style={{ background: T.successBg, padding: '10px 14px', borderRadius: 8, marginBottom: 16, border: `1px solid ${T.successBd}`, fontSize: 13 }}>
                <strong>{approveModal.displayName || approveModal.email}</strong><br />
                <span style={{ color: T.textSec }}>{approveModal.email}</span>
                {approveModal.message && <div style={{ marginTop: 6, fontStyle: 'italic', color: T.textSec }}>"{approveModal.message}"</div>}
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div>
                  <label htmlFor="um-approve-tenant" style={labelStyle}>Assign to Tenant *</label>
                  <select id="um-approve-tenant" className="um-input" value={approveData.tenantId} onChange={e => setApproveData({ ...approveData, tenantId: e.target.value })}
                    style={{ ...inputStyle, paddingLeft: 12 }}>
                    <option value="">Select Tenant...</option>
                    {banks.map(b => <option key={b.tenantId} value={b.tenantId}>{b.bankName}</option>)}
                  </select>
                </div>
                <div>
                  <label htmlFor="um-approve-group" style={labelStyle}>Assign Group *</label>
                  <select id="um-approve-group" className="um-input" value={approveData.groupId} onChange={e => setApproveData({ ...approveData, groupId: e.target.value })}
                    style={{ ...inputStyle, paddingLeft: 12 }}>
                    <option value="">Select Group...</option>
                    {groups.map(g => <option key={g.groupId || g.id} value={g.groupId || g.id}>{g.groupName}</option>)}
                  </select>
                </div>
                <div>
                  <label htmlFor="um-approve-notes" style={labelStyle}>Notes (optional)</label>
                  <input id="um-approve-notes" className="um-input" value={approveData.reviewNotes} onChange={e => setApproveData({ ...approveData, reviewNotes: e.target.value })}
                    style={{ ...inputStyle, paddingLeft: 12 }} placeholder="Approval notes..." />
                </div>
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
                  <button className="um-btn" onClick={() => setApproveModal(null)} style={cancelBtnStyle}>Cancel</button>
                  <button className="um-btn" onClick={handleApprove} style={{ ...primaryBtnStyle, background: T.success }}>Approve &amp; Create User</button>
                </div>
              </div>
            </ModalCard>
          </Overlay>
        )}
      </AnimatePresence>

      {/* ═══════ REJECT REQUEST MODAL (replaces window.prompt) ═══════ */}
      <AnimatePresence>
        {rejectModal && (
          <Overlay onClose={() => setRejectModal(null)}>
            <ModalCard maxWidth={420} label="Reject access request">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                <h2 style={{ fontSize: 16, fontWeight: 700, margin: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <XCircle size={18} color={T.danger} /> Reject Request
                </h2>
                <button className="um-action" onClick={() => setRejectModal(null)} aria-label="Close"><X size={18} /></button>
              </div>
              <div style={{ fontSize: 13, color: T.textSec, marginBottom: 12 }}>
                Rejecting the request from <strong style={{ color: T.text }}>{rejectModal.displayName || rejectModal.email}</strong>.
              </div>
              <label htmlFor="um-reject-notes" style={labelStyle}>Reason (optional)</label>
              <textarea id="um-reject-notes" className="um-input" value={rejectNotes} onChange={e => setRejectNotes(e.target.value)} rows={3} autoFocus
                style={{ ...inputStyle, paddingLeft: 12, resize: 'vertical', minHeight: 70 }} placeholder="Let the requester know why..." />
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 14 }}>
                <button className="um-btn" onClick={() => setRejectModal(null)} style={cancelBtnStyle}>Cancel</button>
                <button className="um-btn" onClick={handleReject} style={{ ...primaryBtnStyle, background: T.danger }}>Reject</button>
              </div>
            </ModalCard>
          </Overlay>
        )}
      </AnimatePresence>

      {/* ═══════ GENERIC CONFIRM MODAL (replaces window.confirm) ═══════ */}
      <AnimatePresence>
        {confirmState && (
          <Overlay onClose={() => setConfirmState(null)}>
            <ModalCard maxWidth={400} label={confirmState.title}>
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 18 }}>
                <div style={{ width: 36, height: 36, borderRadius: '50%', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: confirmState.danger ? T.dangerBg : T.subtle, color: confirmState.danger ? T.danger : T.brand }}>
                  <AlertTriangle size={18} />
                </div>
                <div>
                  <h2 style={{ fontSize: 16, fontWeight: 700, margin: '2px 0 6px' }}>{confirmState.title}</h2>
                  <p style={{ fontSize: 13, color: T.textSec, margin: 0 }}>{confirmState.message}</p>
                </div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
                <button className="um-btn" onClick={() => setConfirmState(null)} style={cancelBtnStyle}>Cancel</button>
                <button className="um-btn" onClick={confirmState.onConfirm}
                  style={{ ...primaryBtnStyle, background: confirmState.danger ? T.danger : T.brand }}>{confirmState.confirmLabel || 'Confirm'}</button>
              </div>
            </ModalCard>
          </Overlay>
        )}
      </AnimatePresence>
    </div>
  );
};

/* ─── Reusable presentational components ─────────────────── */
const Overlay = ({ children, onClose }) => (
  <div style={overlayStyle} onMouseDown={onClose}>
    <div onMouseDown={e => e.stopPropagation()} style={{ width: '100%', display: 'flex', justifyContent: 'center' }}>
      {children}
    </div>
  </div>
);

const ModalCard = ({ children, maxWidth, label }) => (
  <motion.div role="dialog" aria-modal="true" aria-label={label}
    initial={{ scale: 0.96, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.96, opacity: 0 }}
    style={{ background: T.card, color: T.text, padding: 28, borderRadius: 16, width: '100%', maxWidth, boxShadow: '0 20px 60px rgba(0,0,0,.25)' }}>
    {children}
  </motion.div>
);

const EmptyState = ({ icon: Icon, title, hint, action }) => (
  <div style={{ padding: '56px 24px', textAlign: 'center' }}>
    <div style={{ width: 52, height: 52, borderRadius: 14, background: T.subtle, color: T.textMut, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', marginBottom: 14 }}>
      <Icon size={24} />
    </div>
    <div style={{ fontSize: 15, fontWeight: 600, color: T.text }}>{title}</div>
    {hint && <div style={{ fontSize: 13, color: T.textMut, marginTop: 4 }}>{hint}</div>}
    {action && (
      <button className="um-btn" onClick={action.onClick}
        style={{ marginTop: 16, padding: '8px 16px', borderRadius: T.radius, border: `1px solid ${T.border}`, background: T.card, color: T.brand, cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
        {action.label}
      </button>
    )}
  </div>
);

const Field = ({ id, label, icon: Icon, value, onChange, error, type = 'text', disabled, placeholder }) => (
  <div>
    <label htmlFor={id} style={labelStyle}>{label}</label>
    <div style={{ position: 'relative' }}>
      {Icon && <Icon size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: T.textMut }} />}
      <input id={id} className="um-input" type={type} value={value} onChange={e => onChange(e.target.value)} disabled={disabled}
        style={{ ...inputStyle, borderColor: error ? T.danger : T.border, background: disabled ? T.subtle : T.card }} placeholder={placeholder} />
    </div>
    {error && <div style={{ fontSize: 11, color: T.danger, marginTop: 2 }}>{error}</div>}
  </div>
);

/* ─── Styles ─────────────────────────────────────────────── */
const overlayStyle = { position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)', display: 'flex', justifyContent: 'center', alignItems: 'flex-start', overflowY: 'auto', padding: '5vh 16px', boxSizing: 'border-box', zIndex: 50 };
const labelStyle = { display: 'block', marginBottom: 5, fontSize: 12, fontWeight: 600, color: T.textSec };
const inputStyle = { width: '100%', padding: '10px 12px 10px 38px', borderRadius: T.radius, border: `1px solid ${T.border}`, boxSizing: 'border-box', fontSize: 13, outline: 'none', background: T.card, color: T.text };
const errorBoxStyle = { background: T.dangerBg, color: T.dangerTx, padding: '10px 14px', borderRadius: 8, fontSize: 13, border: `1px solid ${T.dangerBd}`, marginBottom: 12 };
const cancelBtnStyle = { padding: '10px 20px', borderRadius: T.radius, background: T.subtle, color: T.text, border: `1px solid ${T.border}`, cursor: 'pointer', fontSize: 13, fontWeight: 500 };
const primaryBtnStyle = { padding: '10px 20px', borderRadius: T.radius, background: T.brand, color: '#fff', border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600 };
const pagerBtn = (disabled) => ({ padding: '7px 14px', borderRadius: 8, border: `1px solid ${T.border}`, background: T.card, color: T.text, cursor: disabled ? 'default' : 'pointer', fontSize: 12, fontWeight: 600, opacity: disabled ? 0.4 : 1 });
const thSm = { padding: '6px 10px', textAlign: 'left', fontSize: 11, fontWeight: 600, color: T.textSec, textTransform: 'uppercase' };
const tdSm = { padding: '8px 10px', fontSize: 13, color: T.text };
const selectSm = { padding: '7px 10px', borderRadius: 8, border: `1px solid ${T.border}`, fontSize: 12, background: T.card, color: T.text, minWidth: 140 };

export default UserManagement;
