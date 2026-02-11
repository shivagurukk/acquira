
import React, { useState, useEffect } from 'react';
import {
    Box, Typography, Grid, Card, CardContent, Button, IconButton,
    Dialog, DialogTitle, DialogContent, DialogActions, TextField,
    Checkbox, FormControlLabel, Chip, Avatar, Tooltip, Stack, Divider,
    CircularProgress, Alert, Snackbar
} from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import {
    Plus, Edit2, Trash2, Users, UserPlus, Star, Search, ShieldCheck, Mail
} from 'lucide-react';

const SalesTeamManagement = () => {
    const [teamLeads, setTeamLeads] = useState([]);
    const [salesUsers, setSalesUsers] = useState([]);
    const [loading, setLoading] = useState(true);
    const [openDialog, setOpenDialog] = useState(false);
    const [dialogMode, setDialogMode] = useState('add'); // add, edit
    const [selectedLead, setSelectedLead] = useState(null);
    const [formData, setFormData] = useState({ name: '', email: '', isDefault: false });
    const [notification, setNotification] = useState({ open: false, message: '', severity: 'success' });
    const [assigning, setAssigning] = useState(false);

    useEffect(() => {
        fetchData();
    }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const headers = { 'Authorization': `Bearer ${token}` };

            const [leadsRes, usersRes] = await Promise.all([
                fetch('/api/sales-team/team-leads', { headers }),
                fetch('/api/sales-team/sales-users', { headers })
            ]);

            if (leadsRes.ok && usersRes.ok) {
                const leads = await leadsRes.json();
                const users = await usersRes.json();
                setTeamLeads(leads);
                setSalesUsers(users);
            }
        } catch (error) {
            console.error("Failed to fetch data", error);
        } finally {
            setLoading(false);
        }
    };

    const handleSaveLead = async () => {
        try {
            const token = localStorage.getItem('token');
            const url = dialogMode === 'add' ? '/api/sales-team/team-leads' : `/api/sales-team/team-leads/${selectedLead.id}`;
            const method = dialogMode === 'add' ? 'POST' : 'PUT';

            const res = await fetch(url, {
                method,
                headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
                body: JSON.stringify(formData)
            });

            if (res.ok) {
                fetchData();
                setOpenDialog(false);
                setNotification({ open: true, message: 'Team Lead saved successfully', severity: 'success' });
            }
        } catch (error) {
            console.error("Error saving lead", error);
        }
    };

    const handleDeleteLead = async (id) => {
        if (!window.confirm("Are you sure? This will unassign all their users.")) return;
        try {
            const token = localStorage.getItem('token');
            await fetch(`/api/sales-team/team-leads/${id}`, {
                method: 'DELETE',
                headers: { 'Authorization': `Bearer ${token}` }
            });
            fetchData();
        } catch (error) {
            console.error("Error deleting lead", error);
        }
    };

    const handleAssign = async (salesUserId, teamLeadId) => {
        setAssigning(true);
        try {
            const token = localStorage.getItem('token');
            await fetch('/api/sales-team/assign', {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
                body: JSON.stringify({ salesUserId, teamLeadId })
            });
            // Update local state to avoid full reload flicker
            setSalesUsers(prev => prev.map(u => u.salesUserId === salesUserId ? { ...u, teamLeadId, status: 'MAPPED' } : u));
            setNotification({ open: true, message: 'User assigned', severity: 'success' });
        } catch (error) {
            console.error("Error assigning", error);
            setNotification({ open: true, message: 'Assignment failed', severity: 'error' });
        } finally {
            setAssigning(false);
        }
    };

    const handleAutoAssign = async () => {
        if (!window.confirm("Auto-assign all unmapped users to the default team lead?")) return;
        try {
            const token = localStorage.getItem('token');
            await fetch('/api/sales-team/auto-assign', {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${token}` }
            });
            fetchData();
            setNotification({ open: true, message: 'Auto-assignment complete', severity: 'success' });
        } catch (error) {
            console.error("Error auto-assigning", error);
        }
    };

    const columns = [
        {
            field: 'salesUserId', headerName: 'Sales User ID / Email', flex: 1.5, renderCell: (p) => (
                <Stack spacing={0.5}>
                    <Stack direction="row" spacing={1} alignItems="center">
                        <Avatar sx={{ width: 20, height: 20, fontSize: 10, bgcolor: '#3B82F6' }}>
                            {p.value?.charAt(0).toUpperCase()}
                        </Avatar>
                        <Typography variant="body2" fontWeight="600">{p.value}</Typography>
                    </Stack>
                    {p.row.salesUserEmail && (
                        <Typography variant="caption" color="text.secondary" sx={{ ml: 3.5 }}>
                            {p.row.salesUserEmail}
                        </Typography>
                    )}
                </Stack>
            )
        },
        {
            field: 'status', headerName: 'Status', width: 120, renderCell: (p) => (
                <Chip
                    label={p.value}
                    size="small"
                    color={p.value === 'MAPPED' ? 'success' : 'warning'}
                    variant="outlined"
                    sx={{ fontWeight: 600 }}
                />
            )
        },
        {
            field: 'teamLeadId', headerName: 'Assigned Team Lead', flex: 1.5, renderCell: (params) => (
                <TextField
                    select
                    fullWidth
                    size="small"
                    value={params.value || ''}
                    onChange={(e) => handleAssign(params.row.salesUserId, e.target.value)}
                    SelectProps={{ native: true }}
                    disabled={assigning}
                    sx={{
                        '& .MuiOutlinedInput-root': {
                            bgcolor: 'rgba(255,255,255,0.05)',
                            color: 'white',
                            '& fieldset': { border: 'none' }
                        }
                    }}
                >
                    <option value="" disabled>Select Lead</option>
                    {teamLeads.map(lead => (
                        <option key={lead.id} value={lead.id}>{lead.teamLeadName}</option>
                    ))}
                </TextField>
            )
        }
    ];

    return (
        <Box sx={{ p: 4, minHeight: '100vh', bgcolor: '#0B1121', color: 'white' }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={4}>
                <Box>
                    <Typography variant="h4" fontWeight="800" sx={{ background: 'linear-gradient(45deg, #3B82F6, #8B5CF6)', backgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>
                        Sales Team Management
                    </Typography>
                    <Typography color="text.secondary">Manage team structures and user assignments</Typography>
                </Box>
                <Button
                    variant="contained"
                    startIcon={<ShieldCheck />}
                    onClick={handleAutoAssign}
                    sx={{ bgcolor: '#10B981', '&:hover': { bgcolor: '#059669' }, fontWeight: 600 }}
                >
                    Auto-Assign Unmapped
                </Button>
            </Stack>

            <Grid container spacing={4}>
                {/* Left Panel: Team Leads */}
                <Grid item xs={12} md={4}>
                    <Card sx={{ bgcolor: '#1F2937', color: 'white', height: '100%', border: '1px solid rgba(255,255,255,0.1)' }}>
                        <CardContent>
                            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={3}>
                                <Stack direction="row" spacing={1} alignItems="center">
                                    <Users size={20} color="#3B82F6" />
                                    <Typography variant="h6" fontWeight="bold">Team Leads</Typography>
                                </Stack>
                                <Button
                                    size="small"
                                    variant="outlined"
                                    startIcon={<Plus size={16} />}
                                    onClick={() => {
                                        setDialogMode('add');
                                        setFormData({ name: '', email: '', isDefault: false });
                                        setOpenDialog(true);
                                    }}
                                    sx={{ borderColor: 'rgba(255,255,255,0.2)', color: 'white' }}
                                >
                                    Add New
                                </Button>
                            </Stack>

                            <Stack spacing={2}>
                                {loading ? <CircularProgress size={24} sx={{ mx: 'auto' }} /> : teamLeads.map(lead => (
                                    <Card key={lead.id} sx={{ bgcolor: '#111827', border: lead.isDefault ? '1px solid #3B82F6' : '1px solid rgba(255,255,255,0.05)', p: 2, position: 'relative' }}>
                                        {lead.isDefault && (
                                            <Tooltip title="Default for auto-assignment">
                                                <Box sx={{ position: 'absolute', top: 8, right: 8 }}>
                                                    <Star size={16} color="#3B82F6" fill="#3B82F6" />
                                                </Box>
                                            </Tooltip>
                                        )}
                                        <Typography variant="subtitle1" fontWeight="bold">{lead.teamLeadName}</Typography>
                                        <Stack direction="row" spacing={1} alignItems="center" mt={0.5} color="text.secondary">
                                            <Mail size={14} />
                                            <Typography variant="caption">{lead.teamLeadEmail}</Typography>
                                        </Stack>

                                        <Divider sx={{ my: 1.5, borderColor: 'rgba(255,255,255,0.1)' }} />

                                        <Stack direction="row" justifyContent="space-between" alignItems="center">
                                            <Typography variant="caption" color="text.secondary">
                                                {salesUsers.filter(u => u.teamLeadId === lead.id).length} Members
                                            </Typography>
                                            <Box>
                                                <IconButton size="small" onClick={() => {
                                                    setSelectedLead(lead);
                                                    setFormData({ name: lead.teamLeadName, email: lead.teamLeadEmail, isDefault: lead.isDefault });
                                                    setDialogMode('edit');
                                                    setOpenDialog(true);
                                                }} sx={{ color: '#94A3B8', '&:hover': { color: '#3B82F6' } }}>
                                                    <Edit2 size={16} />
                                                </IconButton>
                                                <IconButton size="small" onClick={() => handleDeleteLead(lead.id)} sx={{ color: '#94A3B8', '&:hover': { color: '#EF4444' } }}>
                                                    <Trash2 size={16} />
                                                </IconButton>
                                            </Box>
                                        </Stack>
                                    </Card>
                                ))}
                                {teamLeads.length === 0 && !loading && (
                                    <Alert severity="info" sx={{ bgcolor: 'rgba(59, 130, 246, 0.1)', color: '#93C5FD' }}>
                                        No team leads found. Add one to get started.
                                    </Alert>
                                )}
                            </Stack>
                        </CardContent>
                    </Card>
                </Grid>

                {/* Right Panel: Sales Users */}
                <Grid item xs={12} md={8}>
                    <Card sx={{ bgcolor: '#1F2937', color: 'white', height: '100%', border: '1px solid rgba(255,255,255,0.1)' }}>
                        <CardContent sx={{ height: 600, display: 'flex', flexDirection: 'column' }}>
                            <Stack direction="row" justifyContent="space-between" alignItems="center" mb={3}>
                                <Stack direction="row" spacing={1} alignItems="center">
                                    <UserPlus size={20} color="#10B981" />
                                    <Typography variant="h6" fontWeight="bold">Sales User Mapping</Typography>
                                </Stack>
                                <Chip label={`${salesUsers.filter(u => u.status === 'UNMAPPED').length} Unmapped`} color="warning" size="small" variant="outlined" />
                            </Stack>

                            <DataGrid
                                rows={salesUsers}
                                columns={columns}
                                getRowId={(r) => r.salesUserId}
                                loading={loading}
                                sx={{
                                    border: 'none',
                                    color: 'white',
                                    '& .MuiDataGrid-cell': { borderBottom: '1px solid rgba(255,255,255,0.05)' },
                                    '& .MuiDataGrid-columnHeaders': { bgcolor: '#111827', borderBottom: '1px solid rgba(255,255,255,0.1)' },
                                    '& .MuiDataGrid-row:hover': { bgcolor: 'rgba(255,255,255,0.02)' },
                                    '& .MuiTablePagination-root': { color: 'white' }
                                }}
                            />
                        </CardContent>
                    </Card>
                </Grid>
            </Grid>

            {/* Add/Edit Dialog */}
            <Dialog open={openDialog} onClose={() => setOpenDialog(false)} PaperProps={{ sx: { bgcolor: '#1F2937', color: 'white', minWidth: 400 } }}>
                <DialogTitle>{dialogMode === 'add' ? 'Add Team Lead' : 'Edit Team Lead'}</DialogTitle>
                <DialogContent>
                    <Stack spacing={3} mt={1}>
                        <TextField
                            label="Name"
                            fullWidth
                            variant="outlined"
                            value={formData.name}
                            onChange={e => setFormData({ ...formData, name: e.target.value })}
                            sx={{ '& .MuiOutlinedInput-root': { color: 'white', '& fieldset': { borderColor: 'rgba(255,255,255,0.2)' } }, '& .MuiInputLabel-root': { color: '#94A3B8' } }}
                        />
                        <TextField
                            label="Email"
                            fullWidth
                            variant="outlined"
                            value={formData.email}
                            onChange={e => setFormData({ ...formData, email: e.target.value })}
                            sx={{ '& .MuiOutlinedInput-root': { color: 'white', '& fieldset': { borderColor: 'rgba(255,255,255,0.2)' } }, '& .MuiInputLabel-root': { color: '#94A3B8' } }}
                        />
                        <FormControlLabel
                            control={<Checkbox checked={formData.isDefault} onChange={e => setFormData({ ...formData, isDefault: e.target.checked })} sx={{ color: '#94A3B8', '&.Mui-checked': { color: '#3B82F6' } }} />}
                            label="Set as Default Team Lead"
                        />
                    </Stack>
                </DialogContent>
                <DialogActions>
                    <Button onClick={() => setOpenDialog(false)} sx={{ color: '#94A3B8' }}>Cancel</Button>
                    <Button onClick={handleSaveLead} variant="contained" sx={{ bgcolor: '#3B82F6' }}>Save</Button>
                </DialogActions>
            </Dialog>

            <Snackbar
                open={notification.open}
                autoHideDuration={4000}
                onClose={() => setNotification({ ...notification, open: false })}
            >
                <Alert severity={notification.severity} variant="filled">{notification.message}</Alert>
            </Snackbar>
        </Box>
    );
};

export default SalesTeamManagement;
