import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { Menu, Transition } from '@headlessui/react';
import { ChevronDown as ChevronDownIcon, Plus as PlusIcon } from 'lucide-react';

const CombinedViewSwitcher = ({ onContextChange }) => {
    const [tenants, setTenants] = useState([]);
    const [views, setViews] = useState([]);
    const [activeContext, setActiveContext] = useState(null); // { type: 'SINGLE'|'COMBINED', id: ..., name: ... }
    const [showCreateModal, setShowCreateModal] = useState(false);
    const [newViewName, setNewViewName] = useState('');
    const [selectedTenants, setSelectedTenants] = useState([]);

    useEffect(() => {
        fetchContextData();
    }, []);

    const fetchContextData = async () => {
        try {
            // 1. Fetch Allowed Tenants
            // Assuming existing endpoint /api/auth/allowed-tenants or similar exists
            // If not, we might need to expose it. 
            // For now let's assume valid endpoints:
            const tenantRes = await axios.get('/api/tenants/allowed');
            setTenants(tenantRes.data);

            // 2. Fetch Combined Views
            const viewRes = await axios.get('/api/tenants/views');
            setViews(viewRes.data);

            // 3. Determine Active Context (from localStorage or Default)
            // This logic likely needs to sync with a global AuthContext
        } catch (err) {
            console.error("Failed to fetch context data", err);
        }
    };

    const handleSwitch = (type, item) => {
        const context = {
            type,
            id: type === 'SINGLE' ? item.tenantId : item.viewId,
            name: type === 'SINGLE' ? item.bankName : item.viewName,
            tenantIds: type === 'SINGLE' ? [item.tenantId] : item.tenantIds.split(',').map(Number)
        };

        // Set Header for Axios
        if (type === 'SINGLE') {
            axios.defaults.headers.common['X-Tenant-Id'] = context.id;
            delete axios.defaults.headers.common['X-Tenant-Ids'];
        } else {
            delete axios.defaults.headers.common['X-Tenant-Id'];
            axios.defaults.headers.common['X-Tenant-Ids'] = context.tenantIds.join(',');
        }

        setActiveContext(context);
        if (onContextChange) onContextChange(context);

        // In a real app, you might reload the page or trigger a global re-fetch
        window.location.reload();
    };

    const handleCreateView = async () => {
        if (!newViewName || selectedTenants.length === 0) return;
        try {
            await axios.post('/api/tenants/views', {
                viewName: newViewName,
                tenantIds: selectedTenants
            });
            setShowCreateModal(false);
            setNewViewName('');
            setSelectedTenants([]);
            fetchContextData(); // Refresh list
        } catch (err) {
            alert(err.message);
        }
    };

    return (
        <div className="relative inline-block text-left z-50">
            <Menu as="div" className="relative">
                <Menu.Button className="inline-flex w-full justify-center gap-x-1.5 rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50">
                    {activeContext ? activeContext.name : 'Select Context'}
                    <ChevronDownIcon className="-mr-1 h-5 w-5 text-gray-400" aria-hidden="true" />
                </Menu.Button>

                <Transition
                    enter="transition ease-out duration-100"
                    enterFrom="transform opacity-0 scale-95"
                    enterTo="transform opacity-100 scale-100"
                    leave="transition ease-in duration-75"
                    leaveFrom="transform opacity-100 scale-100"
                    leaveTo="transform opacity-0 scale-95"
                >
                    <Menu.Items className="absolute right-0 z-10 mt-2 w-56 origin-top-right divide-y divide-gray-100 rounded-md bg-white shadow-lg ring-1 ring-black ring-opacity-5 focus:outline-none">

                        {/* Single Tenants Section */}
                        <div className="px-1 py-1">
                            <div className="text-xs font-bold text-gray-500 px-2 py-1 uppercase">Institutions</div>
                            {tenants.map((tenant) => (
                                <Menu.Item key={tenant.tenantId}>
                                    {({ active }) => (
                                        <button
                                            onClick={() => handleSwitch('SINGLE', tenant)}
                                            className={`${active ? 'bg-violet-500 text-white' : 'text-gray-900'
                                                } group flex w-full items-center rounded-md px-2 py-2 text-sm`}
                                        >
                                            {tenant.bankName}
                                        </button>
                                    )}
                                </Menu.Item>
                            ))}
                        </div>

                        {/* Combined Views Section */}
                        <div className="px-1 py-1">
                            <div className="flex justify-between items-center px-2 py-1">
                                <span className="text-xs font-bold text-gray-500 uppercase">Combined Views</span>
                                <button onClick={() => setShowCreateModal(true)} className="text-violet-600 hover:text-violet-800">
                                    <PlusIcon className="h-4 w-4" />
                                </button>
                            </div>
                            {views.map((view) => (
                                <Menu.Item key={view.viewId}>
                                    {({ active }) => (
                                        <button
                                            onClick={() => handleSwitch('COMBINED', view)}
                                            className={`${active ? 'bg-violet-500 text-white' : 'text-gray-900'
                                                } group flex w-full items-center rounded-md px-2 py-2 text-sm`}
                                        >
                                            {view.viewName}
                                        </button>
                                    )}
                                </Menu.Item>
                            ))}
                        </div>
                    </Menu.Items>
                </Transition>
            </Menu>

            {/* Simplified Create Modal (Inline for brevity, strictly should be separate) */}
            {showCreateModal && (
                <div className="fixed inset-0 bg-black bg-opacity-30 flex items-center justify-center z-[100]">
                    <div className="bg-white p-6 rounded-lg shadow-xl w-96">
                        <h3 className="text-lg font-bold mb-4">New Combined View</h3>
                        <input
                            type="text"
                            placeholder="View Name (e.g. MENA Region)"
                            className="w-full border p-2 mb-4 rounded"
                            value={newViewName}
                            onChange={e => setNewViewName(e.target.value)}
                        />
                        <div className="mb-4 max-h-40 overflow-y-auto">
                            {tenants.map(t => (
                                <label key={t.tenantId} className="flex items-center space-x-2 block mb-1">
                                    <input
                                        type="checkbox"
                                        checked={selectedTenants.includes(t.tenantId)}
                                        onChange={e => {
                                            if (e.target.checked) setSelectedTenants([...selectedTenants, t.tenantId]);
                                            else setSelectedTenants(selectedTenants.filter(id => id !== t.tenantId));
                                        }}
                                    />
                                    <span>{t.bankName}</span>
                                </label>
                            ))}
                        </div>
                        <div className="flex justify-end space-x-2">
                            <button onClick={() => setShowCreateModal(false)} className="px-4 py-2 text-gray-600">Cancel</button>
                            <button onClick={handleCreateView} className="px-4 py-2 bg-violet-600 text-white rounded">Create</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default CombinedViewSwitcher;
