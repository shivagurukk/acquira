import React, { useState, useEffect } from 'react';
import { ChevronDown, Building2 } from 'lucide-react';

const TenantSwitcher = () => {
    const [tenants, setTenants] = useState([]);
    const [currentTenantId, setCurrentTenantId] = useState('');
    const [isOpen, setIsOpen] = useState(false);

    useEffect(() => {
        const storedTenants = localStorage.getItem('allowedTenants');
        const storedCurrent = localStorage.getItem('tenantId');

        if (storedTenants) {
            setTenants(JSON.parse(storedTenants));
        }
        if (storedCurrent) {
            setCurrentTenantId(storedCurrent);
        }
    }, []);

    const handleSwitch = (tenantId) => {
        localStorage.setItem('tenantId', tenantId);
        setCurrentTenantId(tenantId);
        setIsOpen(false);
        window.location.reload(); // Simple reload to refresh data with new Tenant ID
    };

    if (tenants.length <= 1) return null; // Don't show if only 1 tenant

    return (
        <div className="relative inline-block text-left">
            <div>
                <button
                    type="button"
                    className="inline-flex items-center justify-between w-full h-10 px-4 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-slate-500 transition-all duration-200"
                    onClick={() => setIsOpen(!isOpen)}
                >
                    <div className="flex items-center">
                        <div className="p-1 mr-2 bg-slate-100 rounded-md">
                            <Building2 className="w-4 h-4 text-slate-500" />
                        </div>
                        {(() => {
                            const current = tenants.find(t => String(t.tenantId || t) === String(currentTenantId));
                            return (
                                <span className="truncate max-w-[150px]">
                                    {current ? (current.bankName || `Tenant ${currentTenantId}`) : 'Select Tenant'}
                                </span>
                            );
                        })()}
                    </div>
                    <ChevronDown className={`w-4 h-4 ml-2 transition-transform duration-200 text-slate-400 ${isOpen ? 'transform rotate-180' : ''}`} />
                </button>
            </div>

            {isOpen && (
                <div className="absolute right-0 z-50 w-64 mt-2 origin-top-right bg-white rounded-lg shadow-xl ring-1 ring-black ring-opacity-5 focus:outline-none border border-slate-100 animate-in fade-in slide-in-from-top-2 duration-200">
                    <div className="p-1">
                        <div className="px-3 py-2 text-xs font-semibold text-slate-400 uppercase tracking-wider">
                            Switch Organization
                        </div>
                        {tenants.map((tenant) => (
                            <button
                                key={tenant.tenantId || tenant}
                                onClick={() => handleSwitch(tenant.tenantId || tenant)}
                                className={`flex items-center w-full px-3 py-2 text-sm rounded-md transition-colors duration-150 group
                                    ${String(tenant.tenantId || tenant) === String(currentTenantId)
                                        ? 'bg-slate-100 text-slate-900'
                                        : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
                                    }`}
                            >
                                <div className={`flex items-center justify-center w-8 h-8 mr-3 rounded-md ${String(tenant.tenantId || tenant) === String(currentTenantId) ? 'bg-white shadow-sm text-blue-600' : 'bg-slate-100 text-slate-500 group-hover:bg-white group-hover:shadow-sm'}`}>
                                    <Building2 className="w-4 h-4" />
                                </div>
                                <div className="flex flex-col items-start">
                                    <span className="font-medium text-left line-clamp-1">
                                        {tenant.bankName || `Tenant ${tenant}`}
                                    </span>
                                    {tenant.institutionId && (
                                        <span className="text-xs text-slate-400 font-mono">
                                            {tenant.institutionId}
                                        </span>
                                    )}
                                </div>
                                {String(tenant.tenantId || tenant) === String(currentTenantId) && (
                                    <div className="ml-auto w-2 h-2 bg-green-500 rounded-full"></div>
                                )}
                            </button>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
};

export default TenantSwitcher;
