import React, { useState, useRef, useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { showToast } from '../contexts/ToastContext';
import { Building2, ChevronDown, Check, Loader2 } from 'lucide-react';

/**
 * TenantSwitcher — compact, theme-aware organization switcher.
 *
 * Rendered inside the sidebar (.sb), so all colour comes from the shared
 * --sb-* tokens in sidebar.css and flips with light/dark automatically.
 * Single tenant renders as a static row; multiple tenants get a dropdown.
 */
const TenantSwitcher = () => {
    const { tenants, activeTenantId, activeTenant, switchTenant } = useAuth();
    const [isOpen, setIsOpen] = useState(false);
    const [switching, setSwitching] = useState(null); // tenantId being switched to
    const dropdownRef = useRef(null);

    // Close on click outside
    useEffect(() => {
        if (!isOpen) return;
        const handleClickOutside = (event) => {
            if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
                setIsOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, [isOpen]);

    // Close on Escape
    useEffect(() => {
        const handleEsc = (e) => { if (e.key === 'Escape') setIsOpen(false); };
        document.addEventListener('keydown', handleEsc);
        return () => document.removeEventListener('keydown', handleEsc);
    }, []);

    // Single tenant — static row, no switcher needed
    if (!tenants || tenants.length <= 1) {
        return (
            <div className="sb__tenant-trigger" style={{ cursor: 'default' }}>
                <Building2 size={14} style={{ flexShrink: 0, opacity: 0.7 }} />
                <span className="sb__tenant-name">{activeTenant?.bankName || 'Default Bank'}</span>
            </div>
        );
    }

    const handleSwitch = async (tenantId) => {
        if (String(tenantId) === String(activeTenantId)) {
            setIsOpen(false);
            return;
        }
        setSwitching(tenantId);
        try {
            // switchTenant never throws — it reports {success, error}. Keep the
            // dropdown open and tell the user when the switch didn't happen.
            const result = await switchTenant(tenantId);
            if (result?.success) {
                setIsOpen(false);
            } else {
                showToast(result?.error || 'Could not switch organization. Please try again.', 'error', 5000);
            }
        } finally {
            setSwitching(null);
        }
    };

    return (
        <div ref={dropdownRef} className="sb__tenant">
            <button
                className={`sb__tenant-trigger${isOpen ? ' sb__tenant-trigger--open' : ''}`}
                onClick={() => setIsOpen(v => !v)}
                aria-haspopup="listbox"
                aria-expanded={isOpen}
                title="Switch organization"
            >
                <Building2 size={14} style={{ flexShrink: 0, opacity: 0.7 }} />
                <span className="sb__tenant-name">{activeTenant?.bankName || 'Select organization'}</span>
                <ChevronDown size={13} className={`sb__tenant-chev${isOpen ? ' sb__tenant-chev--open' : ''}`} />
            </button>

            {isOpen && (
                <div className="sb__tenant-menu" role="listbox" aria-label="Organizations">
                    <div className="sb__tenant-menu-label">Switch organization</div>
                    {tenants.map((tenant) => {
                        const isActive = String(tenant.tenantId) === String(activeTenantId);
                        const isLoading = switching === tenant.tenantId;
                        return (
                            <button
                                key={tenant.tenantId}
                                className={`sb__tenant-item${isActive ? ' sb__tenant-item--active' : ''}`}
                                onClick={() => handleSwitch(tenant.tenantId)}
                                disabled={isLoading}
                                role="option"
                                aria-selected={isActive}
                                style={isLoading ? { opacity: 0.6, cursor: 'wait' } : undefined}
                            >
                                {isLoading
                                    ? <Loader2 size={14} style={{ flexShrink: 0, animation: 'sb-spin 1s linear infinite' }} />
                                    : <Building2 size={14} style={{ flexShrink: 0, opacity: isActive ? 1 : 0.6 }} />}
                                <div style={{ flex: 1, minWidth: 0 }}>
                                    <div className="sb__tenant-item-name">{tenant.bankName}</div>
                                    <div className="sb__tenant-item-meta">
                                        {tenant.bankShortCode}
                                        {tenant.country ? ` · ${tenant.country}` : ''}
                                        {tenant.baseCurrency ? ` · ${tenant.baseCurrency}` : ''}
                                    </div>
                                </div>
                                {isActive && !isLoading && <Check size={13} style={{ flexShrink: 0 }} />}
                            </button>
                        );
                    })}
                </div>
            )}

            <style>{`@keyframes sb-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`}</style>
        </div>
    );
};

export default TenantSwitcher;
