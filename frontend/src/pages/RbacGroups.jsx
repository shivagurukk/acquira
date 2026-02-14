import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus, Edit2, Shield, Check, X, Menu } from 'lucide-react';
import api from '../api/axios';

const RbacGroups = () => {
    const [groups, setGroups] = useState([]);
    const [menus, setMenus] = useState([]);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [currentGroup, setCurrentGroup] = useState({ id: null, groupName: '', description: '', menuIds: [] });

    useEffect(() => {
        fetchGroups();
        fetchMenus();
    }, []);

    const fetchGroups = async () => {
        try {
            const res = await api.get('/admin/rbac/groups');
            setGroups(res.data);
        } catch (e) { console.error(e); }
    };

    const fetchMenus = async () => {
        try {
            const res = await api.get('/admin/rbac/menus');
            setMenus(res.data);
        } catch (e) { console.error(e); }
    };

    const openModal = (group = null) => {
        if (group) {
            setCurrentGroup({
                id: group.id,
                groupName: group.groupName,
                description: group.description,
                menuIds: group.menus ? group.menus.map(m => m.menuId) : []
            });
        } else {
            setCurrentGroup({ id: null, groupName: '', description: '', menuIds: [] });
        }
        setIsModalOpen(true);
    };

    const handleSave = async (e) => {
        e.preventDefault();
        try {
            const payload = {
                id: currentGroup.id,
                groupName: currentGroup.groupName,
                description: currentGroup.description,
                menuIds: currentGroup.menuIds
            };

            await api.post('/admin/rbac/groups', payload);
            fetchGroups();
            setIsModalOpen(false);
        } catch (error) {
            console.error(error);
            alert('Failed to save group');
        }
    };

    const toggleMenu = (menuId) => {
        const ids = [...currentGroup.menuIds];
        if (ids.includes(menuId)) {
            setCurrentGroup({ ...currentGroup, menuIds: ids.filter(id => id !== menuId) });
        } else {
            setCurrentGroup({ ...currentGroup, menuIds: [...ids, menuId] });
        }
    };

    // Group menus by category for the modal
    const groupedMenus = menus.reduce((acc, menu) => {
        const cat = menu.category || 'GENERAL';
        if (!acc[cat]) acc[cat] = [];
        acc[cat].push(menu);
        return acc;
    }, {});

    return (
        <div className="page-container" style={{ padding: '40px', color: '#1e293b' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '30px' }}>
                <h1 style={{ fontWeight: 'bold', fontSize: '24px' }}>RBAC Groups & Permissions</h1>
                <button className="primary-btn" onClick={() => openModal()} style={{ background: '#0f172a', color: 'white', padding: '10px 20px', borderRadius: '8px', display: 'flex', gap: '8px', alignItems: 'center' }}>
                    <Plus size={18} /> New Group
                </button>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '20px' }}>
                {groups.map(group => (
                    <motion.div
                        key={group.id}
                        initial={{ opacity: 0, y: 10 }}
                        animate={{ opacity: 1, y: 0 }}
                        style={{ background: 'white', padding: '24px', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', border: '1px solid #e2e8f0' }}
                    >
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '12px' }}>
                            <div>
                                <h3 style={{ fontWeight: 'bold', fontSize: '18px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                                    <Shield size={18} color="#3b82f6" /> {group.groupName}
                                </h3>
                                <p style={{ color: '#64748b', fontSize: '14px', marginTop: '4px' }}>{group.description}</p>
                            </div>
                            <button onClick={() => openModal(group)} style={{ color: '#3b82f6', background: 'transparent', border: 'none', cursor: 'pointer' }}>
                                <Edit2 size={18} />
                            </button>
                        </div>

                        <div style={{ marginTop: '16px', borderTop: '1px solid #f1f5f9', paddingTop: '12px' }}>
                            <div style={{ fontSize: '12px', fontWeight: 'bold', color: '#94a3b8', marginBottom: '8px' }}>PERMISSIONS ({group.menus ? group.menus.length : 0})</div>
                            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                                {group.menus && group.menus.slice(0, 5).map(m => (
                                    <span key={m.menuId} style={{ background: '#eff6ff', color: '#1d4ed8', padding: '4px 8px', borderRadius: '4px', fontSize: '11px' }}>
                                        {m.menuName}
                                    </span>
                                ))}
                                {group.menus && group.menus.length > 5 && (
                                    <span style={{ color: '#64748b', fontSize: '11px', padding: '4px' }}>+{group.menus.length - 5} more</span>
                                )}
                            </div>
                        </div>
                    </motion.div>
                ))}
            </div>

            <AnimatePresence>
                {isModalOpen && (
                    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 50 }}>
                        <motion.div
                            initial={{ scale: 0.95, opacity: 0 }}
                            animate={{ scale: 1, opacity: 1 }}
                            exit={{ scale: 0.95, opacity: 0 }}
                            style={{ background: 'white', padding: '30px', borderRadius: '16px', width: '100%', maxWidth: '700px', maxHeight: '90vh', overflowY: 'auto' }}
                        >
                            <h2 style={{ fontSize: '20px', fontWeight: 'bold', marginBottom: '20px' }}>{currentGroup.id ? 'Edit Group' : 'New Group'}</h2>
                            <form onSubmit={handleSave}>
                                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginBottom: '20px' }}>
                                    <div>
                                        <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Group Name</label>
                                        <input value={currentGroup.groupName} onChange={e => setCurrentGroup({ ...currentGroup, groupName: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} required />
                                    </div>
                                    <div>
                                        <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px', fontWeight: '500' }}>Description</label>
                                        <input value={currentGroup.description} onChange={e => setCurrentGroup({ ...currentGroup, description: e.target.value })} style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }} />
                                    </div>
                                </div>

                                <h3 style={{ fontSize: '16px', fontWeight: 'bold', marginBottom: '12px' }}>Menu Access</h3>
                                <div style={{ maxHeight: '400px', overflowY: 'auto', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '16px' }}>
                                    {Object.keys(groupedMenus).map(category => (
                                        <div key={category} style={{ marginBottom: '20px' }}>
                                            <div style={{ fontSize: '12px', fontWeight: 'bold', color: '#64748b', marginBottom: '8px', textTransform: 'uppercase' }}>{category}</div>
                                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
                                                {groupedMenus[category].map(menu => (
                                                    <label key={menu.menuId} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px', borderRadius: '6px', background: currentGroup.menuIds.includes(menu.menuId) ? '#eff6ff' : 'transparent', border: currentGroup.menuIds.includes(menu.menuId) ? '1px solid #bfdbfe' : '1px solid transparent', cursor: 'pointer' }}>
                                                        <input
                                                            type="checkbox"
                                                            checked={currentGroup.menuIds.includes(menu.menuId)}
                                                            onChange={() => toggleMenu(menu.menuId)}
                                                            style={{ width: '16px', height: '16px' }}
                                                        />
                                                        <span style={{ fontSize: '14px', fontWeight: currentGroup.menuIds.includes(menu.menuId) ? '600' : '400' }}>{menu.menuName}</span>
                                                    </label>
                                                ))}
                                            </div>
                                        </div>
                                    ))}
                                </div>

                                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '20px' }}>
                                    <button type="button" onClick={() => setIsModalOpen(false)} style={{ padding: '10px 20px', borderRadius: '8px', background: '#f1f5f9', border: 'none', cursor: 'pointer' }}>Cancel</button>
                                    <button type="submit" style={{ padding: '10px 20px', borderRadius: '8px', background: '#0f172a', color: 'white', border: 'none', cursor: 'pointer' }}>Save Group</button>
                                </div>
                            </form>
                        </motion.div>
                    </div>
                )}
            </AnimatePresence>
        </div>
    );
};

export default RbacGroups;
