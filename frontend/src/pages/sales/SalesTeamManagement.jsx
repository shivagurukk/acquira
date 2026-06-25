import React, { useState, useEffect, useMemo } from 'react';
import {
    Box, Typography, Grid, Card, CardContent, Button, IconButton,
    Dialog, DialogTitle, DialogContent, DialogActions, TextField,
    Checkbox, FormControlLabel, Chip, Avatar, Tooltip, Stack, Divider,
    CircularProgress, Alert, Snackbar, InputAdornment, Select, MenuItem,
    Collapse, LinearProgress
} from '@mui/material';
import {
    Plus, Edit2, Trash2, Users, UserPlus, Star, Search, ShieldCheck, Mail,
    ChevronDown, ChevronUp, Hash, Download, Filter, Building2
} from 'lucide-react';
import api from '../../api/axios';

const SalesTeamManagement = () => {
    const [teamLeads, setTeamLeads] = useState([]);
    const [salesUsers, setSalesUsers] = useState([]);
    const [loading, setLoading] = useState(true);
    const [openDialog, setOpenDialog] = useState(false);
    const [dialogMode, setDialogMode] = useState('add');
    const [selectedLead, setSelectedLead] = useState(null);
    const [formData, setFormData] = useState({ name: '', email: '', isDefault: false });
    const [formErrors, setFormErrors] = useState({});
    const [notification, setNotification] = useState({ open: false, message: '', severity: 'success' });
    const [assigning, setAssigning] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [statusFilter, setStatusFilter] = useState('ALL');
    const [expandedLead, setExpandedLead] = useState(null);
    const [selectedUsers, setSelectedUsers] = useState(new Set());
    const [bulkLeadId, setBulkLeadId] = useState('');

    useEffect(() => { fetchData(); }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const [leadsRes, usersRes] = await Promise.all([
                api.get('/sales-team/team-leads'),
                api.get('/sales-team/sales-users'),
            ]);
            setTeamLeads(leadsRes.data);
            setSalesUsers(usersRes.data);
        } catch (error) { console.error('Failed to fetch data', error); }
        finally { setLoading(false); }
    };

    const validateForm = () => {
        const errors = {};
        if (!formData.name?.trim()) errors.name = 'Name is required';
        if (!formData.email?.trim()) errors.email = 'Email is required';
        else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email.trim())) errors.email = 'Invalid email format';
        setFormErrors(errors);
        return Object.keys(errors).length === 0;
    };

    const handleSaveLead = async () => {
        if (!validateForm()) return;
        try {
            if (dialogMode === 'add') {
                await api.post('/sales-team/team-leads', formData);
            } else {
                await api.put(`/sales-team/team-leads/${selectedLead.id}`, formData);
            }
            fetchData();
            setOpenDialog(false);
            setNotification({ open: true, message: `Team Lead ${dialogMode === 'add' ? 'created' : 'updated'} successfully`, severity: 'success' });
        } catch (error) { console.error('Error saving lead', error); setNotification({ open: true, message: 'Failed to save', severity: 'error' }); }
    };

    const handleDeleteLead = async (id) => {
        if (!window.confirm('Are you sure? This will unassign all their users.')) return;
        try {
            await api.delete(`/sales-team/team-leads/${id}`);
            fetchData();
            setNotification({ open: true, message: 'Team Lead deleted', severity: 'info' });
        } catch (error) { console.error('Error deleting lead', error); }
    };

    const handleAssign = async (salesUserId, teamLeadId) => {
        setAssigning(true);
        try {
            await api.post('/sales-team/assign', { salesUserId, teamLeadId });
            setSalesUsers(prev => prev.map(u => u.salesUserId === salesUserId ? { ...u, teamLeadId, status: 'MAPPED' } : u));
            setNotification({ open: true, message: 'User assigned', severity: 'success' });
        } catch (error) { setNotification({ open: true, message: 'Assignment failed', severity: 'error' }); }
        finally { setAssigning(false); }
    };

    const handleBulkAssign = async () => {
        if (!bulkLeadId || selectedUsers.size === 0) return;
        setAssigning(true);
        try {
            await Promise.all(
                [...selectedUsers].map(userId =>
                    api.post('/sales-team/assign', { salesUserId: userId, teamLeadId: bulkLeadId })
                )
            );
            setSelectedUsers(new Set());
            setBulkLeadId('');
            fetchData();
            setNotification({ open: true, message: `${selectedUsers.size} users assigned`, severity: 'success' });
        } catch (error) { setNotification({ open: true, message: 'Bulk assignment failed', severity: 'error' }); }
        finally { setAssigning(false); }
    };

    const handleAutoAssign = async () => {
        if (!window.confirm('Auto-assign all unmapped users to the default team lead?')) return;
        try {
            await api.post('/sales-team/auto-assign');
            fetchData();
            setNotification({ open: true, message: 'Auto-assignment complete', severity: 'success' });
        } catch (error) { console.error('Error auto-assigning', error); }
    };

    const handleExportCSV = () => {
        const rows = [['Sales User ID', 'Email', 'Status', 'Team Lead']];
        filteredUsers.forEach(u => {
            const lead = teamLeads.find(l => l.id === u.teamLeadId);
            rows.push([u.salesUserId, u.salesUserEmail || '', u.status, lead?.teamLeadName || 'Unassigned']);
        });
        const csv = rows.map(r => r.join(',')).join('\n');
        const blob = new Blob([csv], { type: 'text/csv' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a'); a.href = url; a.download = 'sales_team_mapping.csv'; a.click();
    };

    // Filtered & searched users
    const filteredUsers = useMemo(() => {
        return salesUsers.filter(u => {
            const matchesSearch = !searchQuery || u.salesUserId?.toLowerCase().includes(searchQuery.toLowerCase()) || u.salesUserEmail?.toLowerCase().includes(searchQuery.toLowerCase());
            const matchesStatus = statusFilter === 'ALL' || u.status === statusFilter;
            return matchesSearch && matchesStatus;
        });
    }, [salesUsers, searchQuery, statusFilter]);

    // Stats
    const totalUsers = salesUsers.length;
    const mappedCount = salesUsers.filter(u => u.status === 'MAPPED').length;
    const unmappedCount = totalUsers - mappedCount;
    const mappedPct = totalUsers > 0 ? Math.round(mappedCount / totalUsers * 100) : 0;

    const toggleSelectUser = (userId) => {
        setSelectedUsers(prev => { const next = new Set(prev); next.has(userId) ? next.delete(userId) : next.add(userId); return next; });
    };
    const toggleSelectAll = () => {
        if (selectedUsers.size === filteredUsers.length) setSelectedUsers(new Set());
        else setSelectedUsers(new Set(filteredUsers.map(u => u.salesUserId)));
    };

    const getLeadMembers = (leadId) => salesUsers.filter(u => u.teamLeadId === leadId);

    return (
        <Box sx={{ p: { xs: 2, md: 4 }, minHeight: '100vh', bgcolor: '#f8fafc' }}>
            {/* Header */}
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" mb={3} flexWrap="wrap" gap={2}>
                <Box>
                    <Typography variant="h5" fontWeight="800" color="#0f172a">Sales Team Management</Typography>
                    <Typography variant="body2" color="#64748b">Manage team structures, assign sales users, and track coverage</Typography>
                </Box>
                <Stack direction="row" spacing={1.5}>
                    <Button size="small" variant="outlined" startIcon={<Download size={16} />} onClick={handleExportCSV} sx={{ borderColor: '#e2e8f0', color: '#475569', textTransform: 'none', fontWeight: 600 }}>Export CSV</Button>
                    <Button size="small" variant="contained" startIcon={<ShieldCheck size={16} />} onClick={handleAutoAssign} sx={{ bgcolor: '#10b981', '&:hover': { bgcolor: '#059669' }, textTransform: 'none', fontWeight: 600 }}>Auto-Assign Unmapped</Button>
                </Stack>
            </Stack>

            {/* Summary Stats */}
            <Grid container spacing={2} mb={3}>
                {[
                    { label: 'Team Leads', value: teamLeads.length, icon: Users, color: '#3b82f6', bg: '#eff6ff' },
                    { label: 'Sales Users', value: totalUsers, icon: UserPlus, color: '#8b5cf6', bg: '#f5f3ff' },
                    { label: 'Mapped', value: mappedCount, icon: ShieldCheck, color: '#10b981', bg: '#f0fdf4' },
                    { label: 'Unmapped', value: unmappedCount, icon: Filter, color: unmappedCount > 0 ? '#f59e0b' : '#10b981', bg: unmappedCount > 0 ? '#fffbeb' : '#f0fdf4' },
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

            {/* Coverage Progress */}
            {totalUsers > 0 && (
                <Card sx={{ mb: 3, border: '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 3 }}>
                    <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
                        <Stack direction="row" justifyContent="space-between" alignItems="center" mb={1}>
                            <Typography variant="body2" fontWeight={600} color="#374151">Assignment Coverage</Typography>
                            <Typography variant="body2" fontWeight={700} color={mappedPct === 100 ? '#10b981' : '#f59e0b'}>{mappedPct}%</Typography>
                        </Stack>
                        <LinearProgress variant="determinate" value={mappedPct} sx={{ height: 8, borderRadius: 4, bgcolor: '#f1f5f9', '& .MuiLinearProgress-bar': { borderRadius: 4, bgcolor: mappedPct === 100 ? '#10b981' : '#3b82f6' } }} />
                    </CardContent>
                </Card>
            )}

            <Grid container spacing={3}>
                {/* Left Panel: Team Leads */}
                <Grid item xs={12} md={4}>
                    <Card sx={{ border: '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 3, height: '100%' }}>
                        <CardContent sx={{ p: 2.5 }}>
                            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2.5}>
                                <Stack direction="row" spacing={1} alignItems="center">
                                    <Users size={18} color="#3b82f6" />
                                    <Typography variant="subtitle1" fontWeight="700" color="#0f172a">Team Leads</Typography>
                                </Stack>
                                <Button size="small" variant="contained" startIcon={<Plus size={14} />}
                                    onClick={() => { setDialogMode('add'); setFormData({ name: '', email: '', isDefault: false }); setFormErrors({}); setOpenDialog(true); }}
                                    sx={{ bgcolor: '#3b82f6', textTransform: 'none', fontWeight: 600, fontSize: 12, px: 1.5, py: 0.5 }}>Add</Button>
                            </Stack>

                            <Stack spacing={1.5}>
                                {loading ? <CircularProgress size={24} sx={{ mx: 'auto', my: 4 }} /> : teamLeads.map(lead => {
                                    const members = getLeadMembers(lead.id);
                                    const isExpanded = expandedLead === lead.id;
                                    return (
                                        <Card key={lead.id} sx={{ border: lead.isDefault ? '1.5px solid #3b82f6' : '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 2.5, overflow: 'hidden', bgcolor: lead.isDefault ? '#f0f7ff' : '#fff' }}>
                                            <Box sx={{ p: 2 }}>
                                                <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                                                    <Box sx={{ flex: 1 }}>
                                                        <Stack direction="row" spacing={1} alignItems="center">
                                                            <Avatar sx={{ width: 32, height: 32, bgcolor: lead.isDefault ? '#3b82f6' : '#e2e8f0', fontSize: 13, fontWeight: 700, color: lead.isDefault ? '#fff' : '#475569' }}>
                                                                {lead.teamLeadName?.charAt(0)?.toUpperCase()}
                                                            </Avatar>
                                                            <Box>
                                                                <Typography variant="body2" fontWeight="700" color="#0f172a">{lead.teamLeadName}</Typography>
                                                                <Typography variant="caption" color="#64748b" sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                                                    <Mail size={10} /> {lead.teamLeadEmail}
                                                                </Typography>
                                                            </Box>
                                                        </Stack>
                                                    </Box>
                                                    <Stack direction="row" spacing={0}>
                                                        {lead.isDefault && <Tooltip title="Default for auto-assignment"><Box sx={{ mt: 0.5 }}><Star size={14} color="#3b82f6" fill="#3b82f6" /></Box></Tooltip>}
                                                        <IconButton size="small" onClick={() => { setSelectedLead(lead); setFormData({ name: lead.teamLeadName, email: lead.teamLeadEmail, isDefault: lead.isDefault }); setFormErrors({}); setDialogMode('edit'); setOpenDialog(true); }} sx={{ color: '#94a3b8', '&:hover': { color: '#3b82f6' } }}><Edit2 size={14} /></IconButton>
                                                        <IconButton size="small" onClick={() => handleDeleteLead(lead.id)} sx={{ color: '#94a3b8', '&:hover': { color: '#ef4444' } }}><Trash2 size={14} /></IconButton>
                                                    </Stack>
                                                </Stack>

                                                <Divider sx={{ my: 1.5 }} />

                                                <Stack direction="row" justifyContent="space-between" alignItems="center" onClick={() => setExpandedLead(isExpanded ? null : lead.id)} sx={{ cursor: 'pointer', '&:hover': { bgcolor: '#f8fafc' }, borderRadius: 1, mx: -0.5, px: 0.5, py: 0.25 }}>
                                                    <Stack direction="row" spacing={1} alignItems="center">
                                                        <Chip label={`${members.length} member${members.length !== 1 ? 's' : ''}`} size="small" sx={{ fontSize: 11, fontWeight: 600, height: 22, bgcolor: '#f1f5f9', color: '#475569' }} />
                                                        {members.length > 0 && <Typography variant="caption" color="#94a3b8">{isExpanded ? 'Hide' : 'Show'}</Typography>}
                                                    </Stack>
                                                    {members.length > 0 && (isExpanded ? <ChevronUp size={14} color="#94a3b8" /> : <ChevronDown size={14} color="#94a3b8" />)}
                                                </Stack>

                                                <Collapse in={isExpanded}>
                                                    <Stack spacing={0.5} mt={1.5}>
                                                        {members.map(m => (
                                                            <Stack key={m.salesUserId} direction="row" spacing={1} alignItems="center" sx={{ py: 0.5, px: 1, bgcolor: '#f8fafc', borderRadius: 1.5 }}>
                                                                <Avatar sx={{ width: 22, height: 22, fontSize: 10, bgcolor: '#e0e7ff', color: '#4f46e5' }}>{m.salesUserId?.charAt(0)?.toUpperCase()}</Avatar>
                                                                <Box sx={{ flex: 1, overflow: 'hidden' }}>
                                                                    <Typography variant="caption" fontWeight={600} color="#374151" noWrap>{m.salesUserId}</Typography>
                                                                    {m.salesUserEmail && <Typography variant="caption" color="#94a3b8" display="block" noWrap sx={{ fontSize: 10 }}>{m.salesUserEmail}</Typography>}
                                                                </Box>
                                                                {m.merchantCount != null && <Chip label={m.merchantCount} size="small" sx={{ height: 18, fontSize: 10, fontWeight: 700, bgcolor: '#dbeafe', color: '#1e40af' }} />}
                                                            </Stack>
                                                        ))}
                                                        {members.length === 0 && <Typography variant="caption" color="#94a3b8" sx={{ py: 1, textAlign: 'center' }}>No members assigned</Typography>}
                                                    </Stack>
                                                </Collapse>
                                            </Box>
                                        </Card>
                                    );
                                })}
                                {teamLeads.length === 0 && !loading && (
                                    <Alert severity="info" variant="outlined" sx={{ borderRadius: 2 }}>No team leads found. Add one to get started.</Alert>
                                )}
                            </Stack>
                        </CardContent>
                    </Card>
                </Grid>

                {/* Right Panel: Sales Users */}
                <Grid item xs={12} md={8}>
                    <Card sx={{ border: '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 3 }}>
                        <CardContent sx={{ p: 2.5 }}>
                            {/* Toolbar */}
                            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2} flexWrap="wrap" gap={1.5}>
                                <Stack direction="row" spacing={1} alignItems="center">
                                    <UserPlus size={18} color="#10b981" />
                                    <Typography variant="subtitle1" fontWeight="700" color="#0f172a">Sales Users</Typography>
                                    <Chip label={`${filteredUsers.length} of ${totalUsers}`} size="small" sx={{ fontSize: 11, fontWeight: 600, height: 22, bgcolor: '#f1f5f9', color: '#475569' }} />
                                </Stack>
                                <Stack direction="row" spacing={1} alignItems="center">
                                    <TextField size="small" placeholder="Search users..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
                                        InputProps={{ startAdornment: <InputAdornment position="start"><Search size={14} color="#94a3b8" /></InputAdornment> }}
                                        sx={{ width: 200, '& .MuiOutlinedInput-root': { borderRadius: 2, fontSize: 13, bgcolor: '#fff' } }} />
                                    <Select size="small" value={statusFilter} onChange={e => setStatusFilter(e.target.value)}
                                        sx={{ minWidth: 110, fontSize: 13, borderRadius: 2, bgcolor: '#fff' }}>
                                        <MenuItem value="ALL">All Status</MenuItem>
                                        <MenuItem value="MAPPED">Mapped</MenuItem>
                                        <MenuItem value="UNMAPPED">Unmapped</MenuItem>
                                    </Select>
                                </Stack>
                            </Stack>

                            {/* Bulk Assign Bar */}
                            <Collapse in={selectedUsers.size > 0}>
                                <Stack direction="row" spacing={1.5} alignItems="center" sx={{ bgcolor: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: 2, p: 1.5, mb: 2 }}>
                                    <Typography variant="body2" fontWeight={600} color="#1e40af">{selectedUsers.size} selected</Typography>
                                    <Select size="small" value={bulkLeadId} onChange={e => setBulkLeadId(e.target.value)} displayEmpty
                                        sx={{ minWidth: 160, fontSize: 12, borderRadius: 1.5, bgcolor: '#fff' }}>
                                        <MenuItem value="" disabled><em>Assign to...</em></MenuItem>
                                        {teamLeads.map(l => <MenuItem key={l.id} value={l.id}>{l.teamLeadName}</MenuItem>)}
                                    </Select>
                                    <Button size="small" variant="contained" disabled={!bulkLeadId || assigning} onClick={handleBulkAssign}
                                        sx={{ bgcolor: '#2563eb', textTransform: 'none', fontWeight: 600, fontSize: 12 }}>Assign Selected</Button>
                                    <Button size="small" onClick={() => setSelectedUsers(new Set())} sx={{ color: '#64748b', textTransform: 'none', fontSize: 12 }}>Clear</Button>
                                </Stack>
                            </Collapse>

                            {/* Table */}
                            <Box sx={{ border: '1px solid #e2e8f0', borderRadius: 2, overflow: 'hidden' }}>
                                {/* Table Header */}
                                <Box sx={{ display: 'grid', gridTemplateColumns: '40px 1fr 100px 90px 1fr', bgcolor: '#f8fafc', borderBottom: '2px solid #e2e8f0', px: 2, py: 1.25 }}>
                                    <Box>
                                        <Checkbox size="small" checked={selectedUsers.size === filteredUsers.length && filteredUsers.length > 0} indeterminate={selectedUsers.size > 0 && selectedUsers.size < filteredUsers.length}
                                            onChange={toggleSelectAll} sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: 18 } }} />
                                    </Box>
                                    <Typography variant="caption" fontWeight={700} color="#64748b" textTransform="uppercase" letterSpacing="0.05em">Sales User</Typography>
                                    <Typography variant="caption" fontWeight={700} color="#64748b" textTransform="uppercase" letterSpacing="0.05em">Merchants</Typography>
                                    <Typography variant="caption" fontWeight={700} color="#64748b" textTransform="uppercase" letterSpacing="0.05em">Status</Typography>
                                    <Typography variant="caption" fontWeight={700} color="#64748b" textTransform="uppercase" letterSpacing="0.05em">Team Lead</Typography>
                                </Box>

                                {/* Table Body */}
                                <Box sx={{ maxHeight: 520, overflowY: 'auto' }}>
                                    {loading ? (
                                        <Box sx={{ textAlign: 'center', py: 8 }}><CircularProgress size={28} /></Box>
                                    ) : filteredUsers.length === 0 ? (
                                        <Box sx={{ textAlign: 'center', py: 6, color: '#94a3b8' }}>
                                            <Typography variant="body2">No users match your search</Typography>
                                        </Box>
                                    ) : filteredUsers.map((user, idx) => {
                                        const isSelected = selectedUsers.has(user.salesUserId);
                                        return (
                                            <Box key={user.salesUserId} sx={{ display: 'grid', gridTemplateColumns: '40px 1fr 100px 90px 1fr', px: 2, py: 1.25, borderBottom: '1px solid #f1f5f9', alignItems: 'center', bgcolor: isSelected ? '#eff6ff' : idx % 2 === 0 ? '#fff' : '#fafbfc', '&:hover': { bgcolor: '#f0f7ff' }, transition: 'background 0.15s' }}>
                                                <Box>
                                                    <Checkbox size="small" checked={isSelected} onChange={() => toggleSelectUser(user.salesUserId)} sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: 18 } }} />
                                                </Box>
                                                <Stack direction="row" spacing={1} alignItems="center">
                                                    <Avatar sx={{ width: 28, height: 28, fontSize: 11, fontWeight: 700, bgcolor: user.status === 'MAPPED' ? '#dbeafe' : '#fef3c7', color: user.status === 'MAPPED' ? '#1e40af' : '#92400e' }}>
                                                        {user.salesUserId?.charAt(0)?.toUpperCase()}
                                                    </Avatar>
                                                    <Box sx={{ overflow: 'hidden' }}>
                                                        <Typography variant="body2" fontWeight={600} color="#0f172a" noWrap>{user.salesUserId}</Typography>
                                                        {user.salesUserEmail && user.salesUserEmail !== user.salesUserId && (
                                                            <Typography variant="caption" color="#94a3b8" noWrap sx={{ fontSize: 11 }}>{user.salesUserEmail}</Typography>
                                                        )}
                                                    </Box>
                                                </Stack>
                                                <Box>
                                                    <Chip icon={<Building2 size={11} />} label={user.merchantCount ?? '—'} size="small"
                                                        sx={{ fontSize: 11, fontWeight: 600, height: 22, bgcolor: '#f1f5f9', color: '#475569', '& .MuiChip-icon': { color: '#94a3b8' } }} />
                                                </Box>
                                                <Box>
                                                    <Chip label={user.status} size="small" sx={{ fontSize: 10, fontWeight: 700, height: 20, letterSpacing: '0.03em',
                                                        bgcolor: user.status === 'MAPPED' ? '#dcfce7' : '#fef9c3', color: user.status === 'MAPPED' ? '#166534' : '#854d0e' }} />
                                                </Box>
                                                <Select size="small" fullWidth value={user.teamLeadId || ''} onChange={e => handleAssign(user.salesUserId, e.target.value)} disabled={assigning} displayEmpty
                                                    sx={{ fontSize: 12, borderRadius: 1.5, bgcolor: '#fff', '& .MuiSelect-select': { py: 0.75 } }}>
                                                    <MenuItem value="" disabled><em style={{ color: '#94a3b8' }}>Select Lead</em></MenuItem>
                                                    {teamLeads.map(lead => <MenuItem key={lead.id} value={lead.id}>{lead.teamLeadName}</MenuItem>)}
                                                </Select>
                                            </Box>
                                        );
                                    })}
                                </Box>
                            </Box>
                        </CardContent>
                    </Card>
                </Grid>
            </Grid>

            {/* Add/Edit Dialog */}
            <Dialog open={openDialog} onClose={() => setOpenDialog(false)} PaperProps={{ sx: { borderRadius: 3, minWidth: 420 } }}>
                <DialogTitle sx={{ fontWeight: 700 }}>{dialogMode === 'add' ? 'Add Team Lead' : 'Edit Team Lead'}</DialogTitle>
                <DialogContent>
                    <Stack spacing={2.5} mt={1}>
                        <TextField label="Name" fullWidth variant="outlined" size="small" required value={formData.name} onChange={e => setFormData({ ...formData, name: e.target.value })}
                            error={!!formErrors.name} helperText={formErrors.name} />
                        <TextField label="Email" fullWidth variant="outlined" size="small" required type="email" value={formData.email} onChange={e => setFormData({ ...formData, email: e.target.value })}
                            error={!!formErrors.email} helperText={formErrors.email} />
                        <FormControlLabel control={<Checkbox checked={formData.isDefault} onChange={e => setFormData({ ...formData, isDefault: e.target.checked })} />}
                            label={<Typography variant="body2">Set as Default Team Lead (for auto-assignment)</Typography>} />
                    </Stack>
                </DialogContent>
                <DialogActions sx={{ px: 3, pb: 2 }}>
                    <Button onClick={() => setOpenDialog(false)} sx={{ color: '#64748b', textTransform: 'none' }}>Cancel</Button>
                    <Button onClick={handleSaveLead} variant="contained" sx={{ bgcolor: '#3b82f6', textTransform: 'none', fontWeight: 600 }}>Save</Button>
                </DialogActions>
            </Dialog>

            <Snackbar open={notification.open} autoHideDuration={4000} onClose={() => setNotification({ ...notification, open: false })} anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}>
                <Alert severity={notification.severity} variant="filled" onClose={() => setNotification({ ...notification, open: false })}>{notification.message}</Alert>
            </Snackbar>
        </Box>
    );
};

export default SalesTeamManagement;
