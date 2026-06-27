import React, { useState, useEffect, useMemo } from 'react';
import {
    Box, Typography, Grid, Card, CardContent, Button, IconButton,
    Drawer, TextField, Chip, Avatar, Stack, CircularProgress, Alert,
    Snackbar, InputAdornment, Select, MenuItem, Divider, Tooltip
} from '@mui/material';
import {
    Search, RefreshCw, Edit2, Building2, Mail, Phone, Target, Filter,
    UserCircle, X, Eye, Users, Globe
} from 'lucide-react';
import api from '../../api/axios';
import SalesPortfolioPanel from '../../components/SalesPortfolioPanel';

const fmtM = (v) => {
    const n = Number(v || 0);
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return n.toFixed(0);
};

const SalesAgentDirectory = () => {
    const [agents, setAgents] = useState([]);
    const [loading, setLoading] = useState(true);
    const [syncing, setSyncing] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [statusFilter, setStatusFilter] = useState('ALL');
    const [notification, setNotification] = useState({ open: false, message: '', severity: 'success' });
    const [editAgent, setEditAgent] = useState(null);
    const [editForm, setEditForm] = useState({});
    const [saving, setSaving] = useState(false);
    const [selectedAgent, setSelectedAgent] = useState(null);

    useEffect(() => { fetchAgents(); }, []);

    const fetchAgents = async () => {
        setLoading(true);
        try {
            const res = await api.get('/sales-agents');
            setAgents(res.data);
        } catch (e) { console.error('Failed to fetch agents', e); }
        finally { setLoading(false); }
    };

    const handleSync = async () => {
        setSyncing(true);
        try {
            const res = await api.post('/sales-agents/sync');
            const { created, updated } = res.data || {};
            await fetchAgents();
            setNotification({ open: true, message: `Sync complete — ${created || 0} added, ${updated || 0} updated`, severity: 'success' });
        } catch (e) {
            setNotification({ open: true, message: 'Sync failed', severity: 'error' });
        } finally { setSyncing(false); }
    };

    const openEdit = (agent) => {
        setEditAgent(agent);
        setEditForm({
            displayName: agent.displayName || '',
            phone: agent.phone || '',
            countryCode: agent.countryCode || '',
            hireDate: agent.hireDate ? String(agent.hireDate).slice(0, 10) : '',
            monthlyTarget: agent.monthlyTarget != null ? agent.monthlyTarget : '',
            status: agent.status || 'ACTIVE',
            notes: agent.notes || '',
        });
    };

    const handleSave = async () => {
        if (!editAgent) return;
        setSaving(true);
        try {
            await api.put(`/sales-agents/${encodeURIComponent(editAgent.salesUserId)}`, editForm);
            await fetchAgents();
            setEditAgent(null);
            setNotification({ open: true, message: 'Agent updated', severity: 'success' });
        } catch (e) {
            setNotification({ open: true, message: 'Update failed', severity: 'error' });
        } finally { setSaving(false); }
    };

    const filteredAgents = useMemo(() => {
        return agents.filter(a => {
            const q = searchQuery.toLowerCase();
            const matchesSearch = !q || a.salesUserId?.toLowerCase().includes(q)
                || a.salesEmail?.toLowerCase().includes(q) || a.displayName?.toLowerCase().includes(q);
            const matchesStatus = statusFilter === 'ALL' || a.status === statusFilter;
            return matchesSearch && matchesStatus;
        });
    }, [agents, searchQuery, statusFilter]);

    const totalAgents = agents.length;
    const activeCount = agents.filter(a => a.status === 'ACTIVE').length;
    const withTarget = agents.filter(a => a.monthlyTarget != null).length;
    const mappedCount = agents.filter(a => a.assignmentStatus === 'MAPPED').length;

    return (
        <Box sx={{ p: { xs: 2, md: 4 }, minHeight: '100vh', bgcolor: '#f8fafc' }}>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" mb={3} flexWrap="wrap" gap={2}>
                <Box>
                    <Typography variant="h5" fontWeight="800" color="#0f172a">Sales Agent Directory</Typography>
                    <Typography variant="body2" color="#64748b">Agent profiles &amp; portfolios. Emails are auto-populated from merchant data.</Typography>
                </Box>
                <Button size="small" variant="contained" startIcon={syncing ? <CircularProgress size={14} color="inherit" /> : <RefreshCw size={16} />} onClick={handleSync} disabled={syncing}
                    sx={{ bgcolor: '#10b981', '&:hover': { bgcolor: '#059669' }, textTransform: 'none', fontWeight: 600 }}>
                    {syncing ? 'Syncing...' : 'Sync from Merchants'}
                </Button>
            </Stack>

            <Grid container spacing={2} mb={3}>
                {[
                    { label: 'Total Agents', value: totalAgents, icon: UserCircle, color: '#3b82f6', bg: '#eff6ff' },
                    { label: 'Active', value: activeCount, icon: Users, color: '#10b981', bg: '#f0fdf4' },
                    { label: 'With Target', value: withTarget, icon: Target, color: '#8b5cf6', bg: '#f5f3ff' },
                    { label: 'Mapped to Team', value: mappedCount, icon: Filter, color: '#f59e0b', bg: '#fffbeb' },
                ].map((s, i) => (
                    <Grid item xs={6} md={3} key={i}>
                        <Card sx={{ border: '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 3 }}>
                            <CardContent sx={{ p: 2, '&:last-child': { pb: 2 }, display: 'flex', alignItems: 'center', gap: 2 }}>
                                <Box sx={{ width: 40, height: 40, borderRadius: 2, bgcolor: s.bg, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                                    <s.icon size={20} color={s.color} />
                                </Box>
                                <Box>
                                    <Typography variant="h6" fontWeight="800" color="#0f172a" lineHeight={1.2}>{s.value}</Typography>
                                    <Typography variant="caption" color="#64748b" fontWeight={500}>{s.label}</Typography>
                                </Box>
                            </CardContent>
                        </Card>
                    </Grid>
                ))}
            </Grid>

            {/* Portfolio drill-down */}
            {selectedAgent && (
                <SalesPortfolioPanel level="agent" id={selectedAgent} onClose={() => setSelectedAgent(null)} />
            )}

            <Card sx={{ border: '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 3 }}>
                <CardContent sx={{ p: 2.5 }}>
                    <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2} flexWrap="wrap" gap={1.5}>
                        <Stack direction="row" spacing={1} alignItems="center">
                            <UserCircle size={18} color="#3b82f6" />
                            <Typography variant="subtitle1" fontWeight="700" color="#0f172a">Agents</Typography>
                            <Chip label={`${filteredAgents.length} of ${totalAgents}`} size="small" sx={{ fontSize: 11, fontWeight: 600, height: 22, bgcolor: '#f1f5f9', color: '#475569' }} />
                        </Stack>
                        <Stack direction="row" spacing={1} alignItems="center">
                            <TextField size="small" placeholder="Search agents..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
                                InputProps={{ startAdornment: <InputAdornment position="start"><Search size={14} color="#94a3b8" /></InputAdornment> }}
                                sx={{ width: 220, '& .MuiOutlinedInput-root': { borderRadius: 2, fontSize: 13, bgcolor: '#fff' } }} />
                            <Select size="small" value={statusFilter} onChange={e => setStatusFilter(e.target.value)}
                                sx={{ minWidth: 120, fontSize: 13, borderRadius: 2, bgcolor: '#fff' }}>
                                <MenuItem value="ALL">All Status</MenuItem>
                                <MenuItem value="ACTIVE">Active</MenuItem>
                                <MenuItem value="INACTIVE">Inactive</MenuItem>
                            </Select>
                        </Stack>
                    </Stack>

                    {loading ? (
                        <Box sx={{ textAlign: 'center', py: 8 }}><CircularProgress size={28} /></Box>
                    ) : agents.length === 0 ? (
                        <Alert severity="info" variant="outlined" sx={{ borderRadius: 2 }}>
                            No agent profiles yet. Click <strong>Sync from Merchants</strong> to populate the directory from your merchant data.
                        </Alert>
                    ) : (
                        <Box sx={{ border: '1px solid #e2e8f0', borderRadius: 2, overflow: 'hidden' }}>
                            <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 110px 90px 110px 80px 80px', bgcolor: '#f8fafc', borderBottom: '2px solid #e2e8f0', px: 2, py: 1.25 }}>
                                {['Agent', 'Merchants', 'Status', 'Target', 'Team', ''].map((h, i) => (
                                    <Typography key={i} variant="caption" fontWeight={700} color="#64748b" textTransform="uppercase" letterSpacing="0.05em" sx={{ textAlign: i === 0 ? 'left' : 'center' }}>{h}</Typography>
                                ))}
                            </Box>
                            <Box sx={{ maxHeight: 560, overflowY: 'auto' }}>
                                {filteredAgents.map((a, idx) => (
                                    <Box key={a.salesUserId} sx={{ display: 'grid', gridTemplateColumns: '1fr 110px 90px 110px 80px 80px', px: 2, py: 1.25, borderBottom: '1px solid #f1f5f9', alignItems: 'center', bgcolor: idx % 2 === 0 ? '#fff' : '#fafbfc', '&:hover': { bgcolor: '#f0f7ff' } }}>
                                        <Stack direction="row" spacing={1} alignItems="center" sx={{ overflow: 'hidden' }}>
                                            <Avatar sx={{ width: 30, height: 30, fontSize: 12, fontWeight: 700, bgcolor: '#dbeafe', color: '#1e40af' }}>
                                                {(a.displayName || a.salesUserId)?.charAt(0)?.toUpperCase()}
                                            </Avatar>
                                            <Box sx={{ overflow: 'hidden' }}>
                                                <Typography variant="body2" fontWeight={600} color="#0f172a" noWrap>{a.displayName || a.salesUserId}</Typography>
                                                <Typography variant="caption" color="#94a3b8" noWrap sx={{ fontSize: 11, display: 'block' }}>{a.salesEmail || a.salesUserId}</Typography>
                                            </Box>
                                        </Stack>
                                        <Box sx={{ textAlign: 'center' }}>
                                            <Chip icon={<Building2 size={11} />} label={a.merchantCount ?? '—'} size="small" sx={{ fontSize: 11, fontWeight: 600, height: 22, bgcolor: '#f1f5f9', color: '#475569', '& .MuiChip-icon': { color: '#94a3b8' } }} />
                                        </Box>
                                        <Box sx={{ textAlign: 'center' }}>
                                            <Chip label={a.status || 'ACTIVE'} size="small" sx={{ fontSize: 10, fontWeight: 700, height: 20, bgcolor: a.status === 'INACTIVE' ? '#fee2e2' : '#dcfce7', color: a.status === 'INACTIVE' ? '#991b1b' : '#166534' }} />
                                        </Box>
                                        <Box sx={{ textAlign: 'center' }}>
                                            <Typography variant="caption" fontWeight={700} color={a.monthlyTarget != null ? '#0f172a' : '#cbd5e1'}>
                                                {a.monthlyTarget != null ? fmtM(a.monthlyTarget) : '—'}
                                            </Typography>
                                        </Box>
                                        <Box sx={{ textAlign: 'center' }}>
                                            <Chip label={a.assignmentStatus === 'MAPPED' ? 'Yes' : 'No'} size="small" sx={{ fontSize: 10, fontWeight: 700, height: 20, bgcolor: a.assignmentStatus === 'MAPPED' ? '#dbeafe' : '#fef9c3', color: a.assignmentStatus === 'MAPPED' ? '#1e40af' : '#854d0e' }} />
                                        </Box>
                                        <Stack direction="row" justifyContent="center" spacing={0}>
                                            <Tooltip title="View portfolio"><IconButton size="small" onClick={() => setSelectedAgent(a.salesUserId)} sx={{ color: '#94a3b8', '&:hover': { color: '#3b82f6' } }}><Eye size={15} /></IconButton></Tooltip>
                                            <Tooltip title="Edit profile"><IconButton size="small" onClick={() => openEdit(a)} sx={{ color: '#94a3b8', '&:hover': { color: '#6366f1' } }}><Edit2 size={15} /></IconButton></Tooltip>
                                        </Stack>
                                    </Box>
                                ))}
                            </Box>
                        </Box>
                    )}
                </CardContent>
            </Card>

            {/* Edit drawer */}
            <Drawer anchor="right" open={!!editAgent} onClose={() => setEditAgent(null)} PaperProps={{ sx: { width: { xs: '100%', sm: 420 }, p: 3 } }}>
                {editAgent && (
                    <Box>
                        <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
                            <Typography variant="h6" fontWeight={800}>Edit Agent</Typography>
                            <IconButton size="small" onClick={() => setEditAgent(null)}><X size={18} /></IconButton>
                        </Stack>
                        <Stack spacing={0.5} mb={2}>
                            <Typography variant="body2" fontWeight={700} color="#0f172a">{editAgent.salesUserId}</Typography>
                            <Typography variant="caption" color="#64748b" sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                <Mail size={11} /> {editAgent.salesEmail || '— (sync to populate)'}
                            </Typography>
                            <Typography variant="caption" color="#94a3b8">Email is auto-populated from merchant data and not editable here.</Typography>
                        </Stack>
                        <Divider sx={{ mb: 2 }} />
                        <Stack spacing={2}>
                            <TextField label="Display Name" size="small" fullWidth value={editForm.displayName} onChange={e => setEditForm({ ...editForm, displayName: e.target.value })} />
                            <TextField label="Phone" size="small" fullWidth value={editForm.phone} onChange={e => setEditForm({ ...editForm, phone: e.target.value })}
                                InputProps={{ startAdornment: <InputAdornment position="start"><Phone size={13} color="#94a3b8" /></InputAdornment> }} />
                            <TextField label="Country Code (e.g. BH)" size="small" fullWidth value={editForm.countryCode} onChange={e => setEditForm({ ...editForm, countryCode: e.target.value.toUpperCase().slice(0, 2) })}
                                InputProps={{ startAdornment: <InputAdornment position="start"><Globe size={13} color="#94a3b8" /></InputAdornment> }} />
                            <TextField label="Hire Date" type="date" size="small" fullWidth InputLabelProps={{ shrink: true }} value={editForm.hireDate} onChange={e => setEditForm({ ...editForm, hireDate: e.target.value })} />
                            <TextField label="Monthly Target" type="number" size="small" fullWidth value={editForm.monthlyTarget} onChange={e => setEditForm({ ...editForm, monthlyTarget: e.target.value })}
                                InputProps={{ startAdornment: <InputAdornment position="start"><Target size={13} color="#94a3b8" /></InputAdornment> }} />
                            <Select size="small" fullWidth value={editForm.status} onChange={e => setEditForm({ ...editForm, status: e.target.value })}>
                                <MenuItem value="ACTIVE">Active</MenuItem>
                                <MenuItem value="INACTIVE">Inactive</MenuItem>
                            </Select>
                            <TextField label="Notes" size="small" fullWidth multiline rows={3} value={editForm.notes} onChange={e => setEditForm({ ...editForm, notes: e.target.value })} />
                        </Stack>
                        <Stack direction="row" spacing={1.5} mt={3}>
                            <Button fullWidth variant="outlined" onClick={() => setEditAgent(null)} sx={{ textTransform: 'none' }}>Cancel</Button>
                            <Button fullWidth variant="contained" onClick={handleSave} disabled={saving} sx={{ bgcolor: '#3b82f6', textTransform: 'none', fontWeight: 600 }}>
                                {saving ? 'Saving...' : 'Save'}
                            </Button>
                        </Stack>
                    </Box>
                )}
            </Drawer>

            <Snackbar open={notification.open} autoHideDuration={4000} onClose={() => setNotification({ ...notification, open: false })} anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}>
                <Alert severity={notification.severity} variant="filled" onClose={() => setNotification({ ...notification, open: false })}>{notification.message}</Alert>
            </Snackbar>
        </Box>
    );
};

export default SalesAgentDirectory;
