import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus, Edit2, Trash, X, Check, Shield, Power } from 'lucide-react';
import '../UserManagement.css';

const MOCK_ENTITIES = [];

const UserManagement = () => {
    const [users, setUsers] = useState([]);
    const [banks, setBanks] = useState([]);
    const [groups, setGroups] = useState([]); // New state for Groups
    const [isModalOpen, setIsModalOpen] = useState(false);
    // Updated default state to include sysUserGroup
    const [currentUser, setCurrentUser] = useState({ username: '', sysUserGroup: null, active: true, password: '', id: null });
    const [selectedBankId, setSelectedBankId] = useState(null);
    const [selectedGroupId, setSelectedGroupId] = useState(''); // Local state for Select

    useEffect(() => {
        fetchUsers();
        fetchBanks();
        fetchGroups(); // Fetch groups
    }, []);

    const fetchUsers = async () => {
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('http://localhost:8081/api/users', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (res.ok) setUsers(await res.json());
        } catch (e) { console.error(e); }
    };

    const fetchBanks = async () => {
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('http://localhost:8081/api/banks', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (res.ok) setBanks(await res.json());
        } catch (e) { console.error(e); }
    };

    const fetchGroups = async () => {
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('http://localhost:8081/api/admin/rbac/groups', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (res.ok) setGroups(await res.json());
        } catch (e) { console.error(e); }
    };

    const openModal = (user = null) => {
        if (user) {
            setCurrentUser({ ...user, password: '' });
            setSelectedGroupId(''); // Reset group selection as it's for new assignment
            setSelectedBankId('');
        } else {
            setCurrentUser({ username: '', active: true, password: '', id: null });
            setSelectedGroupId('');
            setSelectedBankId('');
        }
        setIsModalOpen(true);
    };

    const handleSave = async (e) => {
        e.preventDefault();
        const method = currentUser.id ? 'PUT' : 'POST';
        const url = currentUser.id ? `http://localhost:8081/api/users/${currentUser.id}` : 'http://localhost:8081/api/users';

        // Prepare payload (User only)
        const payload = {
            ...currentUser,
            // Group is not part of User anymore
        };
        delete payload.sysUserGroup;
        delete payload.role;

        try {
            const token = localStorage.getItem('token');
            const res = await fetch(url, {
                method: method,
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify(payload)
            });

            if (res.ok) {
                const savedUser = await res.json();

                // Assign Tenant and Group if both selected
                if (selectedBankId && selectedGroupId) {
                    await fetch(`http://localhost:8081/api/users/${savedUser.id}/assign`, {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                            'Authorization': `Bearer ${token}`
                        },
                        body: JSON.stringify({
                            bankId: selectedBankId,
                            groupId: selectedGroupId
                        })
                    });
                } else if (selectedBankId && !selectedGroupId) {
                    alert("Please select a Group when assigning a Bank.");
                }

                fetchUsers();
                setIsModalOpen(false);
            }
        } catch (error) { console.error(error); }
    };

    const handleToggleActive = async (user) => {
        const updated = { ...user, active: !user.active };
        try {
            const token = localStorage.getItem('token');
            await fetch(`http://localhost:8081/api/users/${user.id}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify(updated)
            });
            fetchUsers();
        } catch (error) { console.error(error); }
    };

    return (
        <div className="page-container" style={{ padding: '40px', color: '#1e293b' }}>
            <div className="header-row" style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '30px' }}>
                <h1 style={{ fontWeight: 'bold', fontSize: '24px' }}>User Management</h1>
                <button className="primary-btn" onClick={() => openModal()} style={{ background: '#0f172a', color: 'white', padding: '10px 20px', borderRadius: '8px', display: 'flex', gap: '8px', alignItems: 'center' }}>
                    <Plus size={18} /> Add User
                </button>
            </div>

            <div style={{ background: 'white', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0' }}>
                        <tr>
                            <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Username</th>
                            <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Group / Role</th>
                            <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Status</th>
                            <th style={{ padding: '16px', textAlign: 'left', color: '#64748b' }}>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {users.map(user => (
                            <tr key={user.id} style={{ borderBottom: '1px solid #e2e8f0' }}>
                                <td style={{ padding: '16px' }}>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                                        <div style={{ width: '32px', height: '32px', background: '#e2e8f0', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '14px' }}>
                                            {user.username?.[0]?.toUpperCase()}
                                        </div>
                                        <span style={{ fontWeight: '500' }}>{user.username}</span>
                                    </div>
                                </td>
                                <td style={{ padding: '16px' }}>
                                    <span style={{ padding: '4px 12px', borderRadius: '999px', fontSize: '12px', fontWeight: '600', background: '#f1f5f9', color: '#475569' }}>
                                        {/* Groups are tenant specific now */}
                                        Tenant Access
                                    </span>
                                </td>
                                <td style={{ padding: '16px' }}>
                                    <div
                                        onClick={() => handleToggleActive(user)}
                                        style={{
                                            cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '6px',
                                            color: user.active ? '#10b981' : '#ef4444', fontWeight: '500', fontSize: '14px'
                                        }}
                                    >
                                        <Power size={16} /> {user.active ? 'Active' : 'Inactive'}
                                    </div>
                                </td>
                                <td style={{ padding: '16px' }}>
                                    <button onClick={() => openModal(user)} style={{ marginRight: '10px', color: '#3b82f6', background: 'transparent', border: 'none', cursor: 'pointer' }}><Edit2 size={18} /></button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <AnimatePresence>
                {isModalOpen && (
                    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 50 }}>
                        <motion.div
                            initial={{ scale: 0.95, opacity: 0 }}
                            animate={{ scale: 1, opacity: 1 }}
                            exit={{ scale: 0.95, opacity: 0 }}
                            style={{ background: 'white', padding: '30px', borderRadius: '16px', width: '100%', maxWidth: '450px' }}
                        >
                            <h2 style={{ fontSize: '20px', fontWeight: 'bold', marginBottom: '20px' }}>{currentUser.id ? 'Edit User' : 'New User'}</h2>
                            <form onSubmit={handleSave} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Username</label>
                                    <input value={currentUser.username} onChange={e => setCurrentUser({ ...currentUser, username: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} required disabled={!!currentUser.id} />
                                </div>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Password {currentUser.id && '(Leave empty to keep)'}</label>
                                    <input type="password" value={currentUser.password} onChange={e => setCurrentUser({ ...currentUser, password: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} />
                                </div>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Group</label>
                                    <select value={selectedGroupId} onChange={e => setSelectedGroupId(e.target.value)} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }}>
                                        <option value="">Select Group...</option>
                                        {groups.map(g => (
                                            <option key={g.id} value={g.id}>{g.groupName}</option>
                                        ))}
                                    </select>
                                </div>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Assign Bank (Tenant)</label>
                                    <select onChange={e => setSelectedBankId(e.target.value)} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }}>
                                        <option value="">Select Bank to Assign...</option>
                                        {banks.map(b => (
                                            <option key={b.tenantId} value={b.tenantId}>{b.bankName}</option>
                                        ))}
                                    </select>
                                    {currentUser.id && <p style={{ fontSize: '0.8rem', color: 'gray', marginTop: '4px' }}>* Selecting a bank will add user to that tenant.</p>}
                                </div>
                                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '10px' }}>
                                    <button type="button" onClick={() => setIsModalOpen(false)} style={{ padding: '10px 20px', borderRadius: '8px', background: '#f1f5f9', border: 'none', cursor: 'pointer' }}>Cancel</button>
                                    <button type="submit" style={{ padding: '10px 20px', borderRadius: '8px', background: '#0f172a', color: 'white', border: 'none', cursor: 'pointer' }}>Save</button>
                                </div>
                            </form>
                        </motion.div>
                    </div>
                )}
            </AnimatePresence>
        </div>
    );
};

export default UserManagement;
