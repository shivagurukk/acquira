import React, { useState, useEffect, useMemo } from 'react';
import {
    Box, Typography, Grid, Card, CardContent, Button, IconButton,
    Dialog, DialogTitle, DialogContent, DialogActions, TextField,
    Checkbox, FormControlLabel, Chip, Avatar, Tooltip, Stack, Divider,
    CircularProgress, Alert, Snackbar, InputAdornment, Select, MenuItem,
    Collapse, LinearProgress
} from '@mui/material';
import {
    Plus, Edit2, Trash2, Globe, UserPlus, Star, Search, ShieldCheck, Mail,
    ChevronDown, ChevronUp, Download, Filter, Users
} from 'lucide-react';
import api from '../../api/axios';

/**
 * Country Lead management. Hierarchy: Country Lead -> Team Lead -> Sales Agent.
 * Mirrors SalesTeamManagement: left = country leads (CRUD), right = team leads
 * mapped under a country lead.
 */
const SalesCountryLeadManagement = () => {
    const [countryLeads, setCountryLeads] = useState([]);
    const [teamLeads, setTeamLeads] = useState([]);
    const [loading, setLoading] = useState(true);
    const [openDialog, setOpenDialog] = useState(false);
    const [dialogMode, setDialogMode] = useState('add');
    const [selectedLead, setSelectedLead] = useState(null);
    const [formData, setFormData] = useState({ name: '', email: '', countryCode: '', isDefault: false });
    const [formErrors, setFormErrors] = useState({});
    const [notification, setNotification] = useState({ open: false, message: '', severity: 'success' });
    const [assigning, setAssigning] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [statusFilter, setStatusFilter] = useState('ALL');
    const [expandedLead, setExpandedLead] = useState(null);
    const [selectedTeams, setSelectedTeams] = useState(new Set());
    const [bulkLeadId, setBulkLeadId] = useState('');

    useEffect(() => { fetchData(); }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const [leadsRes, teamsRes] = await Promise.all([
                api.get('/sales-country-lead/country-leads'),
                api.get('/sales-country-lead/team-leads'),
            ]);
            setCountryLeads(leadsRes.data);
            setTeamLeads(teamsRes.data);
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
                await api.post('/sales-country-lead/country-leads', formData);
            } else {
                await api.put(`/sales-country-lead/country-leads/${selectedLead.id}`, formData);
            }
            fetchData();
            setOpenDialog(false);
            setNotification({ open: true, message: `Country Lead ${dialogMode === 'add' ? 'created' : 'updated'} successfully`, severity: 'success' });
        } catch (error) { console.error('Error saving lead', error); setNotification({ open: true, message: 'Failed to save', severity: 'error' }); }
    };

    const handleDeleteLead = async (id) => {
        if (!window.confirm('Are you sure? Team leads under this country lead will be unmapped.')) return;
        try {
            await api.delete(`/sales-country-lead/country-leads/${id}`);
            fetchData();
            setNotification({ open: true, message: 'Country Lead deleted', severity: 'info' });
        } catch (error) { console.error('Error deleting lead', error); }
    };

    const handleAssign = async (teamLeadId, countryLeadId) => {
        setAssigning(true);
        try {
            await api.post('/sales-country-lead/assign', { teamLeadId, countryLeadId });
            setTeamLeads(prev => prev.map(t => t.teamLeadId === teamLeadId ? { ...t, countryLeadId, status: 'MAPPED' } : t));
            setNotification({ open: true, message: 'Team lead mapped', severity: 'success' });
        } catch (error) { setNotification({ open: true, message: 'Mapping failed', severity: 'error' }); }
        finally { setAssigning(false); }
    };

    const handleBulkAssign = async () => {
        if (!bulkLeadId || selectedTeams.size === 0) return;
        setAssigning(true);
        try {
            await Promise.all(
                [...selectedTeams].map(teamLeadId =>
                    api.post('/sales-country-lead/assign', { teamLeadId, countryLeadId: bulkLeadId })
                )
            );
            setSelectedTeams(new Set());
            setBulkLeadId('');
            fetchData();
            setNotification({ open: true, message: `${selectedTeams.size} team leads mapped`, severity: 'success' });
        } catch (error) { setNotification({ open: true, message: 'Bulk mapping failed', severity: 'error' }); }
        finally { setAssigning(false); }
    };

    const handleAutoAssign = async () => {
        if (!window.confirm('Auto-assign all unmapped team leads to the default country lead?')) return;
        try {
            await api.post('/sales-country-lead/auto-assign');
            fetchData();
            setNotification({ open: true, message: 'Auto-assignment complete', severity: 'success' });
        } catch (error) { console.error('Error auto-assigning', error); }
    };

    const handleExportCSV = () => {
        const rows = [['Team Lead', 'Email', 'Status', 'Country Lead']];
        filteredTeams.forEach(t => {
            const lead = countryLeads.find(l => l.id === t.countryLeadId);
            rows.push([t.teamLeadName, t.teamLeadEmail || '', t.status, lead?.countryLeadName || 'Unassigned']);
        });
        const csv = rows.map(r => r.join(',')).join('\n');
        const blob = new Blob([csv], { type: 'text/csv' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a'); a.href = url; a.download = 'country_lead_mapping.csv'; a.click();
    };

    const filteredTeams = useMemo(() => {
        return teamLeads.filter(t => {
            const matchesSearch = !searchQuery || t.teamLeadName?.toLowerCase().includes(searchQuery.toLowerCase()) || t.teamLeadEmail?.toLowerCase().includes(searchQuery.toLowerCase());
            const matchesStatus = statusFilter === 'ALL' || t.status === statusFilter;
            return matchesSearch && matchesStatus;
        });
    }, [teamLeads, searchQuery, statusFilter]);

    const totalTeams = teamLeads.length;
    const mappedCount = teamLeads.filter(t => t.status === 'MAPPED').length;
    const unmappedCount = totalTeams - mappedCount;
    const mappedPct = totalTeams > 0 ? Math.round(mappedCount / totalTeams * 100) : 0;

    const toggleSelectTeam = (id) => {
        setSelectedTeams(prev => { const next = new Set(prev); next.has(id) ? next.delete(id) : next.add(id); return next; });
    };
    const toggleSelectAll = () => {
        if (selectedTeams.size === filteredTeams.length) setSelectedTeams(new Set());
        else setSelectedTeams(new Set(filteredTeams.map(t => t.teamLeadId)));
    };
    const getLeadMembers = (leadId) => teamLeads.filter(t => t.countryLeadId === leadId);

    return (
        <Box sx={{ p: { xs: 2, md: 4 }, minHeight: '100vh', bgcolor: '#f8fafc' }}>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" mb={3} flexWrap="wrap" gap={2}>
                <Box>
                    <Typography variant="h5" fontWeight="800" color="#0f172a">Country Lead Management</Typography>
                    <Typography variant="body2" color="#64748b">Country Lead → Team Lead → Sales Agent. Map team leads to their country lead.</Typography>
                </Box>
                <Stack direction="row" spacing={1.5}>
                    <Button size="small" variant="outlined" startIcon={<Download size={16} />} onClick={handleExportCSV} sx={{ borderColor: '#e2e8f0', color: '#475569', textTransform: 'none', fontWeight: 600 }}>Export CSV</Button>
                    <Button size="small" variant="contained" startIcon={<ShieldCheck size={16} />} onClick={handleAutoAssign} sx={{ bgcolor: '#10b981', '&:hover': { bgcolor: '#059669' }, textTransform: 'none', fontWeight: 600 }}>Auto-Assign Unmapped</Button>
                </Stack>
            </Stack>

            <Grid container spacing={2} mb={3}>
                {[
                    { label: 'Country Leads', value: countryLeads.length, icon: Globe, color: '#3b82f6', bg: '#eff6ff' },
                    { label: 'Team Leads', value: totalTeams, icon: Users, color: '#8b5cf6', bg: '#f5f3ff' },
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

            {totalTeams > 0 && (
                <Card sx={{ mb: 3, border: '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 3 }}>
                    <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
                        <Stack direction="row" justifyContent="space-between" alignItems="center" mb={1}>
                            <Typography variant="body2" fontWeight={600} color="#374151">Mapping Coverage</Typography>
                            <Typography variant="body2" fontWeight={700} color={mappedPct === 100 ? '#10b981' : '#f59e0b'}>{mappedPct}%</Typography>
                        </Stack>
                        <LinearProgress variant="determinate" value={mappedPct} sx={{ height: 8, borderRadius: 4, bgcolor: '#f1f5f9', '& .MuiLinearProgress-bar': { borderRadius: 4, bgcolor: mappedPct === 100 ? '#10b981' : '#3b82f6' } }} />
                    </CardContent>
                </Card>
            )}

            <Grid container spacing={3}>
                {/* Left: Country Leads */}
                <Grid item xs={12} md={4}>
                    <Card sx={{ border: '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 3, height: '100%' }}>
                        <CardContent sx={{ p: 2.5 }}>
                            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2.5}>
                                <Stack direction="row" spacing={1} alignItems="center">
                                    <Globe size={18} color="#3b82f6" />
                                    <Typography variant="subtitle1" fontWeight="700" color="#0f172a">Country Leads</Typography>
                                </Stack>
                                <Button size="small" variant="contained" startIcon={<Plus size={14} />}
                                    onClick={() => { setDialogMode('add'); setFormData({ name: '', email: '', countryCode: '', isDefault: false }); setFormErrors({}); setOpenDialog(true); }}
                                    sx={{ bgcolor: '#3b82f6', textTransform: 'none', fontWeight: 600, fontSize: 12, px: 1.5, py: 0.5 }}>Add</Button>
                            </Stack>

                            <Stack spacing={1.5}>
                                {loading ? <CircularProgress size={24} sx={{ mx: 'auto', my: 4 }} /> : countryLeads.map(lead => {
                                    const members = getLeadMembers(lead.id);
                                    const isExpanded = expandedLead === lead.id;
                                    return (
                                        <Card key={lead.id} sx={{ border: lead.isDefault ? '1.5px solid #3b82f6' : '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 2.5, overflow: 'hidden', bgcolor: lead.isDefault ? '#f0f7ff' : '#fff' }}>
                                            <Box sx={{ p: 2 }}>
                                                <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                                                    <Box sx={{ flex: 1 }}>
                                                        <Stack direction="row" spacing={1} alignItems="center">
                                                            <Avatar sx={{ width: 32, height: 32, bgcolor: lead.isDefault ? '#3b82f6' : '#e2e8f0', fontSize: 13, fontWeight: 700, color: lead.isDefault ? '#fff' : '#475569' }}>
                                                                {lead.countryLeadName?.charAt(0)?.toUpperCase()}
                                                            </Avatar>
                                                            <Box>
                                                                <Typography variant="body2" fontWeight="700" color="#0f172a">
                                                                    {lead.countryLeadName}
                                                                    {lead.countryCode && <Chip label={lead.countryCode} size="small" sx={{ ml: 0.5, height: 16, fontSize: 9, fontWeight: 700 }} />}
                                                                </Typography>
                                                                <Typography variant="caption" color="#64748b" sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                                                    <Mail size={10} /> {lead.countryLeadEmail}
                                                                </Typography>
                                                            </Box>
                                                        </Stack>
                                                    </Box>
                                                    <Stack direction="row" spacing={0}>
                                                        {lead.isDefault && <Tooltip title="Default for auto-assignment"><Box sx={{ mt: 0.5 }}><Star size={14} color="#3b82f6" fill="#3b82f6" /></Box></Tooltip>}
                                                        <IconButton size="small" onClick={() => { setSelectedLead(lead); setFormData({ name: lead.countryLeadName, email: lead.countryLeadEmail, countryCode: lead.countryCode || '', isDefault: lead.isDefault }); setFormErrors({}); setDialogMode('edit'); setOpenDialog(true); }} sx={{ color: '#94a3b8', '&:hover': { color: '#3b82f6' } }}><Edit2 size={14} /></IconButton>
                                                        <IconButton size="small" onClick={() => handleDeleteLead(lead.id)} sx={{ color: '#94a3b8', '&:hover': { color: '#ef4444' } }}><Trash2 size={14} /></IconButton>
                                                    </Stack>
                                                </Stack>
                                                <Divider sx={{ my: 1.5 }} />
                                                <Stack direction="row" justifyContent="space-between" alignItems="center" onClick={() => setExpandedLead(isExpanded ? null : lead.id)} sx={{ cursor: 'pointer', '&:hover': { bgcolor: '#f8fafc' }, borderRadius: 1, mx: -0.5, px: 0.5, py: 0.25 }}>
                                                    <Chip label={`${members.length} team${members.length !== 1 ? 's' : ''}`} size="small" sx={{ fontSize: 11, fontWeight: 600, height: 22, bgcolor: '#f1f5f9', color: '#475569' }} />
                                                    {members.length > 0 && (isExpanded ? <ChevronUp size={14} color="#94a3b8" /> : <ChevronDown size={14} color="#94a3b8" />)}
                                                </Stack>
                                                <Collapse in={isExpanded}>
                                                    <Stack spacing={0.5} mt={1.5}>
                                                        {members.map(m => (
                                                            <Stack key={m.teamLeadId} direction="row" spacing={1} alignItems="center" sx={{ py: 0.5, px: 1, bgcolor: '#f8fafc', borderRadius: 1.5 }}>
                                                                <Avatar sx={{ width: 22, height: 22, fontSize: 10, bgcolor: '#e0e7ff', color: '#4f46e5' }}>{m.teamLeadName?.charAt(0)?.toUpperCase()}</Avatar>
                                                                <Box sx={{ flex: 1, overflow: 'hidden' }}>
                                                                    <Typography variant="caption" fontWeight={600} color="#374151" noWrap>{m.teamLeadName}</Typography>
                                                                </Box>
                                                            </Stack>
                                                        ))}
                                                        {members.length === 0 && <Typography variant="caption" color="#94a3b8" sx={{ py: 1, textAlign: 'center' }}>No team leads mapped</Typography>}
                                                    </Stack>
                                                </Collapse>
                                            </Box>
                                        </Card>
                                    );
                                })}
                                {countryLeads.length === 0 && !loading && (
                                    <Alert severity="info" variant="outlined" sx={{ borderRadius: 2 }}>No country leads found. Add one to get started.</Alert>
                                )}
                            </Stack>
                        </CardContent>
                    </Card>
                </Grid>

                {/* Right: Team Leads */}
                <Grid item xs={12} md={8}>
                    <Card sx={{ border: '1px solid #e2e8f0', boxShadow: 'none', borderRadius: 3 }}>
                        <CardContent sx={{ p: 2.5 }}>
                            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2} flexWrap="wrap" gap={1.5}>
                                <Stack direction="row" spacing={1} alignItems="center">
                                    <Users size={18} color="#10b981" />
                                    <Typography variant="subtitle1" fontWeight="700" color="#0f172a">Team Leads</Typography>
                                    <Chip label={`${filteredTeams.length} of ${totalTeams}`} size="small" sx={{ fontSize: 11, fontWeight: 600, height: 22, bgcolor: '#f1f5f9', color: '#475569' }} />
                                </Stack>
                                <Stack direction="row" spacing={1} alignItems="center">
                                    <TextField size="small" placeholder="Search team leads..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
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

                            <Collapse in={selectedTeams.size > 0}>
                                <Stack direction="row" spacing={1.5} alignItems="center" sx={{ bgcolor: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: 2, p: 1.5, mb: 2 }}>
                                    <Typography variant="body2" fontWeight={600} color="#1e40af">{selectedTeams.size} selected</Typography>
                                    <Select size="small" value={bulkLeadId} onChange={e => setBulkLeadId(e.target.value)} displayEmpty
                                        sx={{ minWidth: 180, fontSize: 12, borderRadius: 1.5, bgcolor: '#fff' }}>
                                        <MenuItem value="" disabled><em>Assign to country lead...</em></MenuItem>
                                        {countryLeads.map(l => <MenuItem key={l.id} value={l.id}>{l.countryLeadName}</MenuItem>)}
                                    </Select>
                                    <Button size="small" variant="contained" disabled={!bulkLeadId || assigning} onClick={handleBulkAssign}
                                        sx={{ bgcolor: '#2563eb', textTransform: 'none', fontWeight: 600, fontSize: 12 }}>Assign Selected</Button>
                                    <Button size="small" onClick={() => setSelectedTeams(new Set())} sx={{ color: '#64748b', textTransform: 'none', fontSize: 12 }}>Clear</Button>
                                </Stack>
                            </Collapse>

                            <Box sx={{ border: '1px solid #e2e8f0', borderRadius: 2, overflow: 'hidden' }}>
                                <Box sx={{ display: 'grid', gridTemplateColumns: '40px 1fr 90px 1fr', bgcolor: '#f8fafc', borderBottom: '2px solid #e2e8f0', px: 2, py: 1.25 }}>
                                    <Box>
                                        <Checkbox size="small" checked={selectedTeams.size === filteredTeams.length && filteredTeams.length > 0} indeterminate={selectedTeams.size > 0 && selectedTeams.size < filteredTeams.length}
                                            onChange={toggleSelectAll} sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: 18 } }} />
                                    </Box>
                                    <Typography variant="caption" fontWeight={700} color="#64748b" textTransform="uppercase" letterSpacing="0.05em">Team Lead</Typography>
                                    <Typography variant="caption" fontWeight={700} color="#64748b" textTransform="uppercase" letterSpacing="0.05em">Status</Typography>
                                    <Typography variant="caption" fontWeight={700} color="#64748b" textTransform="uppercase" letterSpacing="0.05em">Country Lead</Typography>
                                </Box>
                                <Box sx={{ maxHeight: 520, overflowY: 'auto' }}>
                                    {loading ? (
                                        <Box sx={{ textAlign: 'center', py: 8 }}><CircularProgress size={28} /></Box>
                                    ) : filteredTeams.length === 0 ? (
                                        <Box sx={{ textAlign: 'center', py: 6, color: '#94a3b8' }}><Typography variant="body2">No team leads match your search</Typography></Box>
                                    ) : filteredTeams.map((team, idx) => {
                                        const isSelected = selectedTeams.has(team.teamLeadId);
                                        return (
                                            <Box key={team.teamLeadId} sx={{ display: 'grid', gridTemplateColumns: '40px 1fr 90px 1fr', px: 2, py: 1.25, borderBottom: '1px solid #f1f5f9', alignItems: 'center', bgcolor: isSelected ? '#eff6ff' : idx % 2 === 0 ? '#fff' : '#fafbfc', '&:hover': { bgcolor: '#f0f7ff' } }}>
                                                <Box>
                                                    <Checkbox size="small" checked={isSelected} onChange={() => toggleSelectTeam(team.teamLeadId)} sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: 18 } }} />
                                                </Box>
                                                <Stack direction="row" spacing={1} alignItems="center">
                                                    <Avatar sx={{ width: 28, height: 28, fontSize: 11, fontWeight: 700, bgcolor: team.status === 'MAPPED' ? '#dbeafe' : '#fef3c7', color: team.status === 'MAPPED' ? '#1e40af' : '#92400e' }}>
                                                        {team.teamLeadName?.charAt(0)?.toUpperCase()}
                                                    </Avatar>
                                                    <Box sx={{ overflow: 'hidden' }}>
                                                        <Typography variant="body2" fontWeight={600} color="#0f172a" noWrap>{team.teamLeadName}</Typography>
                                                        {team.teamLeadEmail && <Typography variant="caption" color="#94a3b8" noWrap sx={{ fontSize: 11 }}>{team.teamLeadEmail}</Typography>}
                                                    </Box>
                                                </Stack>
                                                <Box>
                                                    <Chip label={team.status} size="small" sx={{ fontSize: 10, fontWeight: 700, height: 20, bgcolor: team.status === 'MAPPED' ? '#dcfce7' : '#fef9c3', color: team.status === 'MAPPED' ? '#166534' : '#854d0e' }} />
                                                </Box>
                                                <Select size="small" fullWidth value={team.countryLeadId || ''} onChange={e => handleAssign(team.teamLeadId, e.target.value)} disabled={assigning} displayEmpty
                                                    sx={{ fontSize: 12, borderRadius: 1.5, bgcolor: '#fff', '& .MuiSelect-select': { py: 0.75 } }}>
                                                    <MenuItem value="" disabled><em style={{ color: '#94a3b8' }}>Select Country Lead</em></MenuItem>
                                                    {countryLeads.map(lead => <MenuItem key={lead.id} value={lead.id}>{lead.countryLeadName}</MenuItem>)}
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

            <Dialog open={openDialog} onClose={() => setOpenDialog(false)} PaperProps={{ sx: { borderRadius: 3, minWidth: 420 } }}>
                <DialogTitle sx={{ fontWeight: 700 }}>{dialogMode === 'add' ? 'Add Country Lead' : 'Edit Country Lead'}</DialogTitle>
                <DialogContent>
                    <Stack spacing={2.5} mt={1}>
                        <TextField label="Name" fullWidth variant="outlined" size="small" required value={formData.name} onChange={e => setFormData({ ...formData, name: e.target.value })}
                            error={!!formErrors.name} helperText={formErrors.name} />
                        <TextField label="Email" fullWidth variant="outlined" size="small" required type="email" value={formData.email} onChange={e => setFormData({ ...formData, email: e.target.value })}
                            error={!!formErrors.email} helperText={formErrors.email} />
                        <TextField label="Country Code (optional, e.g. BH)" fullWidth variant="outlined" size="small" value={formData.countryCode} onChange={e => setFormData({ ...formData, countryCode: e.target.value.toUpperCase().slice(0, 2) })} />
                        <FormControlLabel control={<Checkbox checked={formData.isDefault} onChange={e => setFormData({ ...formData, isDefault: e.target.checked })} />}
                            label={<Typography variant="body2">Set as Default Country Lead (for auto-assignment)</Typography>} />
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

export default SalesCountryLeadManagement;
