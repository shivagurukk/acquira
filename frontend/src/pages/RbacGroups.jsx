import { useState, useEffect, useCallback } from 'react';
import { Plus, Edit2, Shield } from 'lucide-react';
import api from '../api/axios';
import { showToast } from '../contexts/ToastContext';
import {
    Page, Card, Button, Badge, DataTable, Modal,
    FormField, FormGrid, Input, Checkbox,
} from '../components/ui';

const emptyGroup = { id: null, groupName: '', description: '', menuIds: [] };

const RbacGroups = () => {
    const [groups, setGroups] = useState([]);
    const [menus, setMenus] = useState([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [currentGroup, setCurrentGroup] = useState(emptyGroup);

    const fetchGroups = useCallback(async () => {
        try {
            const res = await api.get('/admin/rbac/groups');
            setGroups(res.data);
        } catch (e) { console.error(e); }
    }, []);

    const fetchMenus = useCallback(async () => {
        try {
            const res = await api.get('/admin/rbac/menus');
            setMenus(res.data);
        } catch (e) { console.error(e); }
    }, []);

    useEffect(() => {
        setLoading(true);
        Promise.all([fetchGroups(), fetchMenus()]).finally(() => setLoading(false));
    }, [fetchGroups, fetchMenus]);

    const openModal = (group = null) => {
        if (group) {
            setCurrentGroup({
                id: group.groupId,
                groupName: group.groupName,
                description: group.description,
                menuIds: group.menus ? group.menus.map(m => m.menuId) : []
            });
        } else {
            setCurrentGroup(emptyGroup);
        }
        setIsModalOpen(true);
    };

    const handleSave = async (e) => {
        e.preventDefault();
        setSaving(true);
        try {
            const payload = {
                id: currentGroup.id,
                groupName: currentGroup.groupName,
                description: currentGroup.description,
                menuIds: currentGroup.menuIds
            };

            await api.post('/admin/rbac/groups', payload);
            showToast(currentGroup.id ? 'Group updated' : 'Group created', 'success');
            fetchGroups();
            setIsModalOpen(false);
        } catch (error) {
            console.error(error);
            showToast(error?.response?.data?.error || 'Failed to save group', 'error');
        } finally {
            setSaving(false);
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

    const columns = [
        {
            key: 'groupName',
            header: 'Group',
            sortable: true,
            render: g => (
                <span className="ui-row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                    <Shield size={15} style={{ color: 'var(--brand)', flexShrink: 0 }} />
                    <span style={{ fontWeight: 600 }}>{g.groupName}</span>
                </span>
            ),
        },
        {
            key: 'description',
            header: 'Description',
            sortable: true,
            muted: true,
        },
        {
            key: '_menuCount',
            header: 'Menus',
            align: 'right',
            numeric: true,
            width: 90,
            sortable: true,
            sortValue: g => (g.menus ? g.menus.length : 0),
            render: g => (g.menus ? g.menus.length : 0),
        },
        {
            key: 'menus',
            header: 'Permissions',
            render: g => {
                const assigned = g.menus || [];
                if (assigned.length === 0) return <span className="ui-td--muted">No menu access</span>;
                return (
                    <span className="ui-row" style={{ gap: 6, maxWidth: 460 }}>
                        {assigned.slice(0, 5).map(m => (
                            <Badge key={m.menuId} tone="info">{m.menuName}</Badge>
                        ))}
                        {assigned.length > 5 && (
                            <span className="ui-td--muted" style={{ fontSize: '0.75rem' }}>
                                +{assigned.length - 5} more
                            </span>
                        )}
                    </span>
                );
            },
        },
        {
            key: '_actions',
            header: '',
            align: 'right',
            width: 60,
            render: g => (
                <Button
                    variant="ghost"
                    size="sm"
                    iconOnly
                    icon={Edit2}
                    onClick={() => openModal(g)}
                    aria-label={`Edit ${g.groupName}`}
                />
            ),
        },
    ];

    return (
        <Page
            title="RBAC groups and permissions"
            subtitle="Groups bundle menu access. Every user inherits the menus of the groups assigned to them."
            icon={Shield}
            actions={
                <Button variant="primary" icon={Plus} onClick={() => openModal()}>
                    New group
                </Button>
            }
        >
            <Card>
                <DataTable
                    columns={columns}
                    rows={groups}
                    rowKey={g => g.id}
                    loading={loading}
                    defaultSort={{ key: 'groupName', dir: 'asc' }}
                    emptyVariant="data"
                />
            </Card>

            <Modal
                as="form"
                onSubmit={handleSave}
                open={isModalOpen}
                onClose={() => setIsModalOpen(false)}
                size="lg"
                title={currentGroup.id ? 'Edit group' : 'New group'}
                subtitle="Selected menus become visible to every user in this group."
                footer={
                    <>
                        <Button type="button" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                        <Button type="submit" variant="primary" loading={saving}>Save group</Button>
                    </>
                }
            >
                <div className="ui-stack ui-stack--sm">
                    <FormGrid cols={2}>
                        <FormField label="Group name" required>
                            <Input
                                value={currentGroup.groupName}
                                onChange={e => setCurrentGroup({ ...currentGroup, groupName: e.target.value })}
                                required
                            />
                        </FormField>
                        <FormField label="Description">
                            <Input
                                value={currentGroup.description}
                                onChange={e => setCurrentGroup({ ...currentGroup, description: e.target.value })}
                            />
                        </FormField>
                    </FormGrid>

                    <div className="ui-field">
                        <span className="ui-field__label">Menu access</span>
                        <div
                            style={{
                                maxHeight: 400,
                                overflowY: 'auto',
                                border: '1px solid var(--border)',
                                borderRadius: 'var(--radius-md)',
                                padding: 'var(--space-lg)',
                                background: 'var(--bg-muted)',
                            }}
                        >
                            {Object.keys(groupedMenus).map(category => (
                                <div key={category} style={{ marginBottom: 'var(--space-xl)' }}>
                                    <div
                                        style={{
                                            fontSize: '0.7rem',
                                            fontWeight: 700,
                                            letterSpacing: '0.04em',
                                            textTransform: 'uppercase',
                                            color: 'var(--text-secondary)',
                                            marginBottom: 'var(--space-sm)',
                                        }}
                                    >
                                        {category}
                                    </div>
                                    <FormGrid cols={2}>
                                        {groupedMenus[category].map(menu => (
                                            <Checkbox
                                                key={menu.menuId}
                                                checked={currentGroup.menuIds.includes(menu.menuId)}
                                                onChange={() => toggleMenu(menu.menuId)}
                                                label={menu.menuName}
                                            />
                                        ))}
                                    </FormGrid>
                                </div>
                            ))}
                            {menus.length === 0 && (
                                <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
                                    No menus are registered yet.
                                </div>
                            )}
                        </div>
                        <span className="ui-field__hint">
                            Only the menus ticked here are reachable by the group.
                        </span>
                    </div>
                </div>
            </Modal>
        </Page>
    );
};

export default RbacGroups;
