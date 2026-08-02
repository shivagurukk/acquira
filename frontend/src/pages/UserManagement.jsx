import React, { useState, useEffect, useCallback } from 'react';
import {
  Plus, Edit2, X, Unlock, KeyRound, User as UserIcon,
  Eye, EyeOff, Building2, Trash2, Star, Globe, Clock,
  CheckCircle, XCircle, Users, Inbox, Download,
} from 'lucide-react';
import api from '../api/axios';
import { useAuth } from '../contexts/AuthContext';
import { showToast } from '../contexts/ToastContext';
import {
  Page, Stack, Card, Button, Badge, StatusBadge, Alert, Tabs, DataTable, Modal,
  FormField, FormGrid, Input, Textarea, Select, Checkbox, Switch, useConfirm,
} from '../components/ui';

/**
 * Users & access.
 *
 * Renders as the /users route and as the "Users & Access" panel inside
 * SettingsHub. Two concerns:
 *   1. Users — CRUD, activation, account expiry, password reset, unlock and
 *      per-user tenant access grants.
 *   2. Access requests — approve (creates the user) or reject SSO sign-in
 *      requests.
 *
 * Tenant and group option lists come straight from the tenant-scoped
 * /banks and /admin/rbac/groups endpoints. The server is the authority on what
 * the caller may grant; nothing here widens those lists.
 */

const PAGE_SIZE = 25;

/**
 * Input with a trailing icon button (reveal / clear). Sits directly inside a
 * FormField and forwards the id / aria-* / invalid props FormField injects
 * down to the real <input>, so they never land on the positioning wrapper.
 */
const AffixField = ({ id, invalid, action, 'aria-describedby': describedBy, 'aria-invalid': ariaInvalid, ...inputProps }) => (
  <div style={{ position: 'relative' }}>
    <Input
      id={id}
      invalid={invalid}
      aria-describedby={describedBy}
      aria-invalid={ariaInvalid}
      style={{ paddingRight: action ? 36 : undefined }}
      {...inputProps}
    />
    {action}
  </div>
);

const affixButtonStyle = { position: 'absolute', right: 4, top: '50%', transform: 'translateY(-50%)' };

const UserManagement = ({ embedded = false }) => {
  const { tenantVersion } = useAuth();
  const confirm = useConfirm();

  const [users, setUsers] = useState([]);
  const [banks, setBanks] = useState([]);
  const [groups, setGroups] = useState([]);
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('users'); // users | requests
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [exporting, setExporting] = useState(false);

  // Modal state
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [modalUser, setModalUser] = useState(null);
  const [formData, setFormData] = useState({ username: '', email: '', displayName: '', password: '', active: true, tenantAssignments: [{ tenantId: '', groupId: '', isDefault: true }] });
  const [formErrors, setFormErrors] = useState({});
  const [savingUser, setSavingUser] = useState(false);

  // Pagination
  const [currentPage, setCurrentPage] = useState(1);

  // Tenant assignment state
  const [accessUser, setAccessUser] = useState(null); // user whose grants are open
  const [userAccesses, setUserAccesses] = useState([]);
  const [accessLoading, setAccessLoading] = useState(false);
  const [addingAccess, setAddingAccess] = useState(false);
  const [newAccess, setNewAccess] = useState({ tenantId: '', groupId: '', isDefault: false });

  // Reset password modal
  const [resetModal, setResetModal] = useState(null);
  const [resetPw, setResetPw] = useState('');
  const [showResetPw, setShowResetPw] = useState(false);
  const [resetting, setResetting] = useState(false);

  // Approve modal
  const [approveModal, setApproveModal] = useState(null);
  const [approveData, setApproveData] = useState({ tenantId: '', groupId: '', reviewNotes: '' });
  const [approving, setApproving] = useState(false);

  // Reject modal (replaces window.prompt)
  const [rejectModal, setRejectModal] = useState(null);
  const [rejectNotes, setRejectNotes] = useState('');
  const [rejecting, setRejecting] = useState(false);

  const [showPassword, setShowPassword] = useState(false);

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

  useEffect(() => { fetchAll(); }, [fetchAll, tenantVersion]);

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
  // DataTable only sorts — filtering and paging stay here.
  const pagedUsers = filteredUsers.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

  const isFiltered = !!searchQuery || statusFilter !== 'ALL';

  // ─── Export users (server-side CSV, tenant-scoped) ─────
  // The backend applies the same tenant isolation as the list, so we don't
  // build the CSV client-side (that would only cover the current page / the
  // loaded set). We stream the file as a blob and trigger a download.
  const handleExportCsv = async () => {
    setExporting(true);
    try {
      const res = await api.get('/users/export/csv', { responseType: 'blob' });
      const blob = new Blob([res.data], { type: 'text/csv;charset=utf-8' });
      const url = window.URL.createObjectURL(blob);
      // Prefer the server's filename from Content-Disposition; fall back to a dated default.
      const cd = res.headers?.['content-disposition'] || '';
      const match = /filename="?([^"]+)"?/.exec(cd);
      const filename = match ? match[1] : `users-${new Date().toISOString().slice(0, 10)}.csv`;
      const a = document.createElement('a');
      a.href = url; a.download = filename;
      document.body.appendChild(a); a.click();
      a.remove(); window.URL.revokeObjectURL(url);
    } catch (e) {
      showToast(e.response?.data?.error || 'Failed to export users', 'error');
    } finally {
      setExporting(false);
    }
  };

  // ─── User CRUD ─────────────────────────────────────────
  const openCreateModal = () => {
    setModalUser(null);
    // If the admin only has one tenant available (typical for a bank admin now
    // that /banks is tenant-scoped), pre-select it so they don't have to.
    const onlyTenantId = banks.length === 1 ? String(banks[0].tenantId) : '';
    setFormData({ username: '', email: '', displayName: '', password: '', active: true, accountExpiresAt: '', tenantAssignments: [{ tenantId: onlyTenantId, groupId: '', isDefault: true }] });
    setFormErrors({});
    setShowPassword(false);
    setIsModalOpen(true);
  };

  const openEditModal = (user) => {
    setModalUser(user);
    setFormData({ username: user.username, email: user.email || '', displayName: user.displayName || '', password: '', active: user.active, accountExpiresAt: toLocalInput(user.accountExpiresAt) });
    setFormErrors({});
    setShowPassword(false);
    setIsModalOpen(true);
  };

  const handleSaveUser = async (e) => {
    e?.preventDefault();
    const errors = {};
    if (!formData.username?.trim()) errors.username = 'Required';
    // Email is optional; only validate format when something was entered.
    if (formData.email?.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email.trim())) errors.email = 'Invalid email';
    if (!modalUser && !formData.password?.trim()) errors.password = 'Required for new user';
    if (!modalUser) {
      const validAssignments = (formData.tenantAssignments || []).filter(a => a.tenantId && a.groupId);
      if (validAssignments.length === 0) errors.tenants = 'At least one tenant assignment is required';
    }
    setFormErrors(errors);
    if (Object.keys(errors).length) return;

    setSavingUser(true);
    try {
      // datetime-local (no timezone) → ISO for the backend; blank → null (no expiry).
      const payloadExpiry = formData.accountExpiresAt ? new Date(formData.accountExpiresAt).toISOString() : null;
      if (modalUser) {
        await api.put(`/users/${modalUser.id}`, { ...formData, id: modalUser.id, accountExpiresAt: payloadExpiry });
        showToast('User updated', 'success');
      } else {
        // Create user then assign all selected tenants.
        const res = await api.post('/users', { ...formData, accountExpiresAt: payloadExpiry });
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
          showToast(`User created, but tenant assignment failed: ${failed.join(', ')}. ` +
                    `You may not have permission to assign those tenants.`, 'error');
        } else if (failed.length > 0) {
          showToast(`User created. Some tenant assignments failed: ${failed.join(', ')}.`, 'error');
        } else {
          showToast('User created', 'success');
        }
      }
      setIsModalOpen(false);
      fetchAll();
    } catch (e) {
      setFormErrors({ _: e.response?.data?.error || 'Failed to save' });
    } finally {
      setSavingUser(false);
    }
  };

  // Focused payload (no longer spreads tenants/role into the PUT)
  const doToggleActive = async (user) => {
    try {
      await api.put(`/users/${user.id}`, {
        id: user.id, username: user.username, email: user.email,
        displayName: user.displayName, active: !user.active, password: '',
        // Preserve expiry — the controller applies it unconditionally, so omitting
        // it here would wipe the stored date on a simple activate/deactivate.
        accountExpiresAt: user.accountExpiresAt || null
      });
      showToast(user.active ? 'User deactivated' : 'User activated', 'success');
      fetchAll();
    } catch (e) { showToast(e.response?.data?.error || 'Failed to update status', 'error'); }
  };

  const requestToggleActive = async (user) => {
    const who = user.displayName || user.username;
    const ok = await confirm({
      title: user.active ? 'Deactivate user' : 'Activate user',
      message: user.active
        ? `${who} will be unable to sign in until reactivated.`
        : `${who} will be able to sign in again.`,
      confirmLabel: user.active ? 'Deactivate' : 'Activate',
      tone: user.active ? 'danger' : 'info',
    });
    if (!ok) return;
    doToggleActive(user);
  };

  // ─── Tenant Access ─────────────────────────────────────
  const openAccessPanel = async (user) => {
    setAccessUser(user);
    setNewAccess({ tenantId: '', groupId: '', isDefault: false });
    setUserAccesses([]);
    setAccessLoading(true);
    try {
      const res = await api.get(`/users/${user.id}/tenant-access`);
      setUserAccesses(res.data);
    } catch (e) {
      console.error(e);
      showToast('Failed to load tenant access', 'error');
    } finally {
      setAccessLoading(false);
    }
  };

  const addTenantAccess = async (userId) => {
    if (!newAccess.tenantId || !newAccess.groupId) return;
    setAddingAccess(true);
    try {
      await api.post(`/users/${userId}/tenant-access`, newAccess);
      showToast('Tenant access added', 'success');
      const res = await api.get(`/users/${userId}/tenant-access`);
      setUserAccesses(res.data);
      setNewAccess({ tenantId: '', groupId: '', isDefault: false });
      fetchAll();
    } catch (e) { showToast(e.response?.data?.error || 'Failed', 'error'); }
    finally { setAddingAccess(false); }
  };

  const doRemoveTenantAccess = async (userId, accessId) => {
    try {
      await api.delete(`/users/${userId}/tenant-access/${accessId}`);
      const res = await api.get(`/users/${userId}/tenant-access`);
      setUserAccesses(res.data);
      fetchAll();
    } catch (e) { showToast(e.response?.data?.error || 'Failed to remove access', 'error'); }
  };

  const requestRemoveTenantAccess = async (userId, accessId, tenantName) => {
    const ok = await confirm({
      title: 'Remove tenant access',
      message: `Remove access to ${tenantName || 'this tenant'}? The user will lose visibility of its data.`,
      confirmLabel: 'Remove',
      tone: 'danger',
    });
    if (!ok) return;
    doRemoveTenantAccess(userId, accessId);
  };

  // ─── Password Reset ────────────────────────────────────
  const handleResetPassword = async (e) => {
    e?.preventDefault();
    if (!resetPw) return;
    setResetting(true);
    try {
      await api.post(`/users/${resetModal.id}/reset-password`, { newPassword: resetPw });
      showToast('Password reset', 'success');
      setResetModal(null); setResetPw('');
    } catch (e) { showToast(e.response?.data?.error || 'Failed', 'error'); }
    finally { setResetting(false); }
  };

  const handleUnlock = async (user) => {
    try { await api.post(`/users/${user.id}/unlock`); showToast('Account unlocked', 'success'); fetchAll(); } catch (e) { console.error(e); }
  };

  // ─── Access Requests ───────────────────────────────────
  const handleApprove = async (e) => {
    e?.preventDefault();
    if (!approveData.tenantId || !approveData.groupId) { showToast('Select tenant and group', 'error'); return; }
    // Approving provisions an account and grants tenant access — confirm first.
    const bank = banks.find(b => String(b.tenantId) === String(approveData.tenantId));
    const group = groups.find(g => String(g.groupId || g.id) === String(approveData.groupId));
    const who = approveModal.displayName || approveModal.email;
    const ok = await confirm({
      title: 'Approve access request',
      message: `${who} will get an account with ${group?.groupName || 'the selected group'} access to ${bank?.bankName || 'the selected tenant'}.`,
      confirmLabel: 'Approve and create user',
      tone: 'warning',
    });
    if (!ok) return;

    setApproving(true);
    try {
      await api.post(`/admin/access-requests/${approveModal.requestId}/approve`, approveData);
      showToast('Request approved, user created', 'success');
      setApproveModal(null);
      fetchAll();
    } catch (e) { showToast(e.response?.data?.error || 'Failed', 'error'); }
    finally { setApproving(false); }
  };

  const handleReject = async (e) => {
    e?.preventDefault();
    setRejecting(true);
    try {
      await api.post(`/admin/access-requests/${rejectModal.requestId}/reject`, { reviewNotes: rejectNotes || '' });
      showToast('Request rejected', 'success');
      setRejectModal(null); setRejectNotes('');
      fetchAll();
    } catch (e) { showToast(e.response?.data?.error || 'Failed to reject', 'error'); }
    finally { setRejecting(false); }
  };

  const isLocked = (u) => u.lockedUntil && new Date(u.lockedUntil) > new Date();
  const isExpired = (u) => u.accountExpiresAt && new Date(u.accountExpiresAt) <= new Date();
  // ISO/string → value for <input type="datetime-local"> (local time, no seconds/zone).
  const toLocalInput = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d)) return '';
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };

  // ─── Password strength ─────────────────────────────────
  const pw = formData.password || '';
  const pwChecks = [
    { label: '8+ chars', ok: pw.length >= 8 }, { label: 'Upper', ok: /[A-Z]/.test(pw) },
    { label: 'Lower', ok: /[a-z]/.test(pw) }, { label: 'Number', ok: /[0-9]/.test(pw) },
    { label: 'Special', ok: /[^A-Za-z0-9]/.test(pw) },
  ];

  const tenantOptions = banks.map(b => ({ value: b.tenantId, label: b.bankName }));
  const groupOptions = groups.map(g => ({ value: g.groupId || g.id, label: g.groupName }));

  // ─── Column definitions ────────────────────────────────
  const userColumns = [
    {
      key: 'displayName',
      header: 'User',
      sortable: true,
      sortValue: u => u.displayName || u.username || '',
      render: (user) => (
        <div className="ui-row" style={{ gap: 10, flexWrap: 'nowrap', minWidth: 0 }}>
          <div
            aria-hidden="true"
            style={{
              width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 13, fontWeight: 700,
              background: user.ssoProvider ? 'var(--brand-50)' : 'var(--bg-subtle)',
              color: user.ssoProvider ? 'var(--brand)' : 'var(--text-secondary)',
            }}
          >
            {user.ssoProvider ? <Globe size={15} /> : (user.username?.[0]?.toUpperCase() || '?')}
          </div>
          <div style={{ minWidth: 0 }}>
            <div className="ui-row" style={{ gap: 6 }}>
              <strong>{user.displayName || user.username}</strong>
              {user.ssoProvider && <Badge tone="brand">SSO</Badge>}
              {user.mustChangePassword && !user.ssoProvider && <Badge tone="warning">Must change password</Badge>}
              {isLocked(user) && <Badge tone="danger">Locked</Badge>}
              {isExpired(user) && <Badge tone="danger">Expired</Badge>}
              {!isExpired(user) && user.accountExpiresAt && (
                <Badge tone="warning" icon={Clock} title={new Date(user.accountExpiresAt).toLocaleString()}>
                  Expires {new Date(user.accountExpiresAt).toLocaleDateString()}
                </Badge>
              )}
            </div>
            <div className="ui-td--muted" style={{ fontSize: '0.76rem' }}>{user.email || user.username}</div>
          </div>
        </div>
      ),
    },
    {
      key: 'tenants',
      header: 'Tenants',
      render: (user) => (
        (user.tenants || []).length === 0
          ? <span className="ui-td--muted">No tenant</span>
          : (
            <div className="ui-row" style={{ gap: 4 }}>
              {(user.tenants || []).map((t, i) => (
                <Badge key={i} tone="info" icon={Building2}>
                  {t.tenantName?.substring(0, 15)}
                  {t.isDefault && <Star size={9} fill="var(--warning)" color="var(--warning)" />}
                </Badge>
              ))}
            </div>
          )
      ),
    },
    {
      key: 'role',
      header: 'Role',
      sortable: true,
      // Read-only. Roles are granted through group membership, not from here.
      render: (user) => <Badge>{user.role?.replace('ROLE_', '') || 'USER'}</Badge>,
    },
    {
      key: 'active',
      header: 'Status',
      sortable: true,
      nowrap: true,
      render: (user) => (
        <Switch
          checked={!!user.active}
          onChange={() => requestToggleActive(user)}
          label={user.active ? 'Active' : 'Inactive'}
          aria-label={user.active ? `Deactivate ${user.username}` : `Activate ${user.username}`}
        />
      ),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (user) => (
        <>
          <Button
            variant="ghost" size="sm" iconOnly icon={Building2}
            onClick={() => openAccessPanel(user)}
            aria-label={`Tenant access for ${user.username}`} title="Tenant access"
          />
          <Button
            variant="ghost" size="sm" iconOnly icon={Edit2}
            onClick={() => openEditModal(user)}
            aria-label={`Edit ${user.username}`} title="Edit"
          />
          {/* GAP-14: Hide Reset PW for SSO-only users */}
          {!user.ssoProvider && (
            <Button
              variant="ghost" size="sm" iconOnly icon={KeyRound}
              onClick={() => { setResetModal(user); setResetPw(''); setShowResetPw(false); }}
              aria-label={`Reset password for ${user.username}`} title="Reset password"
            />
          )}
          {isLocked(user) && (
            <Button
              variant="danger-ghost" size="sm" iconOnly icon={Unlock}
              onClick={() => handleUnlock(user)}
              aria-label={`Unlock ${user.username}`} title="Unlock"
            />
          )}
        </>
      ),
    },
  ];

  const requestColumns = [
    {
      key: 'displayName',
      header: 'Requester',
      sortable: true,
      sortValue: r => r.displayName || r.email || '',
      render: (r) => (
        <div style={{ minWidth: 0 }}>
          <div className="ui-row" style={{ gap: 6 }}>
            <strong>{r.displayName || r.email}</strong>
            {r.ssoProvider && <Badge tone="brand" icon={Globe}>{r.ssoProvider}</Badge>}
          </div>
          <div className="ui-td--muted" style={{ fontSize: '0.76rem' }}>{r.email}</div>
        </div>
      ),
    },
    { key: 'status', header: 'Status', sortable: true, render: (r) => <StatusBadge status={r.status} /> },
    {
      key: 'tenantName',
      header: 'Requested tenant',
      sortable: true,
      muted: true,
      render: (r) => r.tenantName || '—',
    },
    {
      key: 'message',
      header: 'Message',
      muted: true,
      render: (r) => (
        <span style={{ display: 'block', maxWidth: 260, wordBreak: 'break-word' }}>
          {r.message ? <em>&ldquo;{r.message}&rdquo;</em> : (r.reviewNotes ? `Note: ${r.reviewNotes}` : '—')}
        </span>
      ),
    },
    {
      key: 'createdAt',
      header: 'Submitted',
      sortable: true,
      nowrap: true,
      muted: true,
      render: (r) => (r.createdAt ? new Date(r.createdAt).toLocaleString() : '—'),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (r) => (
        r.status === 'PENDING' ? (
          <>
            <Button
              size="sm" variant="primary" icon={CheckCircle}
              onClick={() => { setApproveModal(r); setApproveData({ tenantId: r.tenantId || '', groupId: '', reviewNotes: '' }); }}
            >
              Approve
            </Button>
            <Button
              size="sm" variant="danger-ghost" icon={XCircle}
              onClick={() => { setRejectModal(r); setRejectNotes(''); }}
            >
              Reject
            </Button>
          </>
        ) : null
      ),
    },
  ];

  const accessColumns = [
    { key: 'tenantName', header: 'Tenant', sortable: true },
    {
      key: 'groupName',
      header: 'Group',
      render: (a) => <Badge tone="success">{a.groupName || '—'}</Badge>,
    },
    {
      key: 'isDefault',
      header: 'Default',
      render: (a) => (a.isDefault
        ? <Star size={14} fill="var(--warning)" color="var(--warning)" aria-label="Default tenant" />
        : <span className="ui-td--muted">—</span>),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      width: 60,
      render: (a) => (
        <Button
          variant="danger-ghost" size="sm" iconOnly icon={Trash2}
          onClick={() => requestRemoveTenantAccess(accessUser.id, a.accessId, a.tenantName)}
          aria-label={`Remove access to ${a.tenantName}`} title="Remove access"
        />
      ),
    },
  ];

  const tabs = [
    { key: 'users', label: 'Users', icon: UserIcon, count: users.length },
    { key: 'requests', label: 'Access requests', icon: Clock, count: pendingCount },
  ];

  return (
    <Page
      flush={embedded}
      title="Users and access"
      subtitle="Users, tenant assignments, SSO access and approval requests."
      icon={Users}
      actions={
        <Button variant="primary" icon={Plus} onClick={openCreateModal}>Create user</Button>
      }
    >
      <Tabs tabs={tabs} active={activeTab} onChange={setActiveTab} />

      {/* ═══════ USERS TAB ═══════ */}
      {activeTab === 'users' && (
        <Stack gap="sm">
          <Card>
            <DataTable
              columns={userColumns}
              rows={pagedUsers}
              rowKey={u => u.id}
              loading={loading}
              search={{ value: searchQuery, onChange: setSearchQuery, placeholder: 'Search users' }}
              toolbarLeft={
                <Select
                  value={statusFilter}
                  onChange={e => setStatusFilter(e.target.value)}
                  aria-label="Filter by status"
                  style={{ width: 160 }}
                  options={[
                    { value: 'ALL', label: 'All users' },
                    { value: 'ACTIVE', label: 'Active' },
                    { value: 'INACTIVE', label: 'Inactive' },
                    { value: 'SSO', label: 'SSO users' },
                    { value: 'PENDING', label: 'Pending approval' },
                  ]}
                />
              }
              toolbarRight={
                /* Download the full (tenant-scoped) user list as CSV — server-side so it
                   covers every user, not just the loaded page. */
                <Button
                  icon={Download}
                  onClick={handleExportCsv}
                  disabled={exporting || loading}
                  loading={exporting}
                  title="Download users as CSV"
                >
                  {exporting ? 'Exporting' : 'Download'}
                </Button>
              }
              empty={
                <div style={{ padding: 'var(--space-3xl)', textAlign: 'center' }}>
                  <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 14 }}>
                    {isFiltered
                      ? 'No users match the current search or filter.'
                      : 'No users yet. Create the first one to get started.'}
                  </p>
                  {isFiltered
                    ? <Button variant="subtle" onClick={() => { setSearchQuery(''); setStatusFilter('ALL'); }}>Clear filters</Button>
                    : <Button variant="subtle" icon={Plus} onClick={openCreateModal}>Create user</Button>}
                </div>
              }
            />
          </Card>

          {/* GAP-20: Pagination — kept on the page, DataTable does not paginate. */}
          {!loading && filteredUsers.length > PAGE_SIZE && (
            <div className="ui-row" style={{ justifyContent: 'center' }}>
              <Button size="sm" onClick={() => setCurrentPage(p => Math.max(1, p - 1))} disabled={currentPage === 1}>
                Previous
              </Button>
              <span style={{ fontSize: '0.78rem', color: 'var(--text-secondary)' }}>
                Page {currentPage} of {totalPages} ({filteredUsers.length} users)
              </span>
              <Button size="sm" onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))} disabled={currentPage >= totalPages}>
                Next
              </Button>
            </div>
          )}
        </Stack>
      )}

      {/* ═══════ ACCESS REQUESTS TAB ═══════ */}
      {activeTab === 'requests' && (
        <Card>
          <DataTable
            columns={requestColumns}
            rows={requests}
            rowKey={r => r.requestId}
            loading={loading}
            defaultSort={{ key: 'createdAt', dir: 'desc' }}
            empty={
              <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                <Inbox size={22} style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
                <p style={{ marginTop: 10 }}>No access requests. Approval requests from SSO sign-ins appear here.</p>
              </div>
            }
          />
        </Card>
      )}

      {/* ═══════ CREATE / EDIT USER MODAL ═══════ */}
      <Modal
        as="form"
        onSubmit={handleSaveUser}
        open={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title={modalUser ? 'Edit user' : 'Create user'}
        footer={
          <>
            <Button type="button" onClick={() => setIsModalOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={savingUser}>Save</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          {formErrors._ && <Alert tone="danger">{formErrors._}</Alert>}

          <FormField label="Username" required error={formErrors.username}>
            <Input
              value={formData.username}
              disabled={!!modalUser}
              onChange={e => setFormData({ ...formData, username: e.target.value })}
            />
          </FormField>

          <FormGrid cols={2}>
            <FormField label="Email" error={formErrors.email}>
              <Input
                type="email"
                value={formData.email}
                onChange={e => setFormData({ ...formData, email: e.target.value })}
                placeholder="Optional"
              />
            </FormField>
            <FormField label="Display name">
              <Input
                value={formData.displayName}
                onChange={e => setFormData({ ...formData, displayName: e.target.value })}
                placeholder="Optional"
              />
            </FormField>
          </FormGrid>

          {/* Account expiry — optional. After this moment the user is blocked
              at login and auto-deactivated. Empty = never expires. */}
          <FormField
            label="Account expiry"
            hint="Optional. Leave empty for no expiry. After this time the account is blocked and deactivated."
          >
            <AffixField
              type="datetime-local"
              value={formData.accountExpiresAt || ''}
              onChange={e => setFormData({ ...formData, accountExpiresAt: e.target.value })}
              action={formData.accountExpiresAt ? (
                <Button
                  type="button" variant="ghost" size="sm" iconOnly icon={X}
                  onClick={() => setFormData({ ...formData, accountExpiresAt: '' })}
                  aria-label="Clear expiry" title="Clear expiry"
                  style={affixButtonStyle}
                />
              ) : null}
            />
          </FormField>

          {/* Multi-tenant assignment for new users. The tenant list is exactly
              what /banks returned for this admin — never widened here. */}
          {!modalUser && (
            <div className="ui-stack ui-stack--sm">
              {formErrors.tenants && <Alert tone="danger">{formErrors.tenants}</Alert>}
              <div className="ui-row ui-row--between">
                <span className="ui-field__label" style={{ marginBottom: 0 }}>Tenant assignments</span>
                <Button
                  type="button" variant="ghost" size="sm" icon={Plus}
                  onClick={() => setFormData({ ...formData, tenantAssignments: [...formData.tenantAssignments, { tenantId: '', groupId: '', isDefault: false }] })}
                >
                  Add tenant
                </Button>
              </div>
              {formData.tenantAssignments.map((assignment, idx) => (
                <div
                  key={idx}
                  style={{
                    display: 'grid', gridTemplateColumns: '1fr 1fr auto auto',
                    gap: 8, alignItems: 'center',
                    padding: '8px 10px', background: 'var(--bg-subtle)',
                    borderRadius: 'var(--radius-md)', border: '1px solid var(--border)',
                  }}
                >
                  <Select
                    aria-label="Tenant"
                    value={assignment.tenantId}
                    onChange={e => {
                      const updated = [...formData.tenantAssignments];
                      updated[idx] = { ...updated[idx], tenantId: e.target.value };
                      setFormData({ ...formData, tenantAssignments: updated });
                    }}
                    placeholder="Select tenant"
                    options={banks
                      .filter(b => !formData.tenantAssignments.some((a, i) => i !== idx && a.tenantId === String(b.tenantId)))
                      .map(b => ({ value: b.tenantId, label: b.bankName }))}
                  />
                  <Select
                    aria-label="Group"
                    value={assignment.groupId}
                    onChange={e => {
                      const updated = [...formData.tenantAssignments];
                      updated[idx] = { ...updated[idx], groupId: e.target.value };
                      setFormData({ ...formData, tenantAssignments: updated });
                    }}
                    placeholder="Select group"
                    options={groupOptions}
                  />
                  <label style={{ fontSize: '0.72rem', display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer', whiteSpace: 'nowrap' }}>
                    <input
                      type="radio"
                      name="defaultTenant"
                      checked={assignment.isDefault}
                      onChange={() => {
                        const updated = formData.tenantAssignments.map((a, i) => ({ ...a, isDefault: i === idx }));
                        setFormData({ ...formData, tenantAssignments: updated });
                      }}
                    /> Default
                  </label>
                  {formData.tenantAssignments.length > 1 && (
                    <Button
                      type="button" variant="danger-ghost" size="sm" iconOnly icon={Trash2}
                      aria-label="Remove tenant assignment"
                      onClick={() => {
                        const updated = formData.tenantAssignments.filter((_, i) => i !== idx);
                        if (assignment.isDefault && updated.length > 0) updated[0].isDefault = true;
                        setFormData({ ...formData, tenantAssignments: updated });
                      }}
                    />
                  )}
                </div>
              ))}
            </div>
          )}

          {!modalUser && (
            <FormField label="Password" required error={formErrors.password}>
              <AffixField
                type={showPassword ? 'text' : 'password'}
                value={formData.password}
                onChange={e => setFormData({ ...formData, password: e.target.value })}
                placeholder="Enter password"
                action={
                  <Button
                    type="button" variant="ghost" size="sm" iconOnly
                    icon={showPassword ? EyeOff : Eye}
                    onClick={() => setShowPassword(!showPassword)}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                    style={affixButtonStyle}
                  />
                }
              />
            </FormField>
          )}

          {!modalUser && pw.length > 0 && (
            <div className="ui-row" style={{ gap: 5 }}>
              {pwChecks.map((c, i) => (
                <Badge key={i} tone={c.ok ? 'success' : 'neutral'}>
                  {c.ok ? '✓' : '○'} {c.label}
                </Badge>
              ))}
            </div>
          )}
        </div>
      </Modal>

      {/* ═══════ TENANT ACCESS MODAL ═══════ */}
      <Modal
        open={!!accessUser}
        onClose={() => setAccessUser(null)}
        size="lg"
        title="Tenant access"
        subtitle={accessUser ? `Grants for ${accessUser.username}` : undefined}
        footer={<Button onClick={() => setAccessUser(null)}>Close</Button>}
      >
        <div className="ui-stack ui-stack--sm">
          <DataTable
            columns={accessColumns}
            rows={userAccesses}
            rowKey={a => a.accessId}
            loading={accessLoading}
            compact
            empty={
              <div style={{ padding: 'var(--space-2xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.82rem' }}>
                No tenant access yet. Without a grant the user cannot see any data.
              </div>
            }
          />

          <div className="ui-row">
            <Select
              aria-label="Select tenant"
              value={newAccess.tenantId}
              onChange={e => setNewAccess({ ...newAccess, tenantId: e.target.value })}
              placeholder="Select tenant"
              options={tenantOptions}
              style={{ minWidth: 170 }}
            />
            <Select
              aria-label="Select group"
              value={newAccess.groupId}
              onChange={e => setNewAccess({ ...newAccess, groupId: e.target.value })}
              placeholder="Select group"
              options={groupOptions}
              style={{ minWidth: 170 }}
            />
            <Checkbox
              checked={newAccess.isDefault}
              onChange={e => setNewAccess({ ...newAccess, isDefault: e.target.checked })}
              label="Default"
            />
            <Button
              variant="primary" size="sm" icon={Plus}
              onClick={() => addTenantAccess(accessUser.id)}
              disabled={!newAccess.tenantId || !newAccess.groupId}
              loading={addingAccess}
            >
              Add
            </Button>
          </div>
        </div>
      </Modal>

      {/* ═══════ RESET PASSWORD MODAL ═══════ */}
      <Modal
        as="form"
        onSubmit={handleResetPassword}
        open={!!resetModal}
        onClose={() => setResetModal(null)}
        size="sm"
        title="Reset password"
        footer={
          <>
            <Button type="button" onClick={() => setResetModal(null)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={!resetPw} loading={resetting}>Reset</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          <Alert tone="warning">
            Setting a new password for <strong>{resetModal?.username}</strong>.
          </Alert>
          <FormField label="New password" required>
            <AffixField
              type={showResetPw ? 'text' : 'password'}
              value={resetPw}
              onChange={e => setResetPw(e.target.value)}
              placeholder="New password"
              autoFocus
              action={
                <Button
                  type="button" variant="ghost" size="sm" iconOnly
                  icon={showResetPw ? EyeOff : Eye}
                  onClick={() => setShowResetPw(!showResetPw)}
                  aria-label={showResetPw ? 'Hide password' : 'Show password'}
                  style={affixButtonStyle}
                />
              }
            />
          </FormField>
        </div>
      </Modal>

      {/* ═══════ APPROVE REQUEST MODAL ═══════ */}
      <Modal
        as="form"
        onSubmit={handleApprove}
        open={!!approveModal}
        onClose={() => setApproveModal(null)}
        title="Approve access request"
        subtitle="Approving creates the account and grants the selected access."
        footer={
          <>
            <Button type="button" onClick={() => setApproveModal(null)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={approving}>Approve and create user</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          <Alert tone="info" title={approveModal?.displayName || approveModal?.email}>
            {approveModal?.email}
            {approveModal?.message && (
              <div style={{ marginTop: 6, fontStyle: 'italic' }}>&ldquo;{approveModal.message}&rdquo;</div>
            )}
          </Alert>

          <FormField label="Assign to tenant" required>
            <Select
              value={approveData.tenantId}
              onChange={e => setApproveData({ ...approveData, tenantId: e.target.value })}
              placeholder="Select tenant"
              options={tenantOptions}
            />
          </FormField>
          <FormField label="Assign group" required>
            <Select
              value={approveData.groupId}
              onChange={e => setApproveData({ ...approveData, groupId: e.target.value })}
              placeholder="Select group"
              options={groupOptions}
            />
          </FormField>
          <FormField label="Notes" hint="Optional.">
            <Input
              value={approveData.reviewNotes}
              onChange={e => setApproveData({ ...approveData, reviewNotes: e.target.value })}
              placeholder="Approval notes"
            />
          </FormField>
        </div>
      </Modal>

      {/* ═══════ REJECT REQUEST MODAL ═══════ */}
      <Modal
        as="form"
        onSubmit={handleReject}
        open={!!rejectModal}
        onClose={() => setRejectModal(null)}
        size="sm"
        title="Reject request"
        footer={
          <>
            <Button type="button" onClick={() => setRejectModal(null)}>Cancel</Button>
            <Button type="submit" variant="danger" loading={rejecting}>Reject</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          <p style={{ fontSize: '0.82rem', color: 'var(--text-secondary)', margin: 0 }}>
            Rejecting the request from <strong style={{ color: 'var(--text)' }}>{rejectModal?.displayName || rejectModal?.email}</strong>.
          </p>
          <FormField label="Reason" hint="Optional.">
            <Textarea
              value={rejectNotes}
              onChange={e => setRejectNotes(e.target.value)}
              rows={3}
              autoFocus
              placeholder="Let the requester know why"
            />
          </FormField>
        </div>
      </Modal>
    </Page>
  );
};

export default UserManagement;
