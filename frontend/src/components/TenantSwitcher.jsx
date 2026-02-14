import React, { useState, useRef, useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { Building2, ChevronDown, Check, Loader2, Globe, Shield } from 'lucide-react';

// ==========================================
// TenantSwitcher — Beautiful dropdown for switching between tenants
// • Shows active tenant with visual indicator
// • Animated dropdown with tenant cards
// • Shows user's role per tenant
// • Loading state during switch
// • Keyboard accessible (Escape to close)
// ==========================================
const TenantSwitcher = () => {
    const { tenants, activeTenantId, activeTenant, switchTenant, isSuperAdmin, userRole } = useAuth();
    const [isOpen, setIsOpen] = useState(false);
    const [switching, setSwitching] = useState(null); // tenantId being switched to
    const dropdownRef = useRef(null);

    // Close on click outside
    useEffect(() => {
        const handleClickOutside = (event) => {
            if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
                setIsOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    // Close on Escape
    useEffect(() => {
        const handleEsc = (e) => { if (e.key === 'Escape') setIsOpen(false); };
        document.addEventListener('keydown', handleEsc);
        return () => document.removeEventListener('keydown', handleEsc);
    }, []);

    // Single tenant — no switcher needed
    if (!tenants || tenants.length <= 1) {
        return (
            <div style={styles.singleTenant}>
                <Building2 size={16} style={{ opacity: 0.5 }} />
                <span style={styles.singleTenantName}>{activeTenant?.bankName || 'Default Bank'}</span>
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
            await switchTenant(tenantId);
            setIsOpen(false);
        } catch (err) {
            console.error('Switch failed:', err);
        } finally {
            setSwitching(null);
        }
    };

    return (
        <div ref={dropdownRef} style={styles.container}>
            {/* Trigger Button */}
            <button
                onClick={() => setIsOpen(!isOpen)}
                style={{
                    ...styles.trigger,
                    ...(isOpen ? styles.triggerOpen : {}),
                }}
            >
                <div style={styles.triggerLeft}>
                    <div style={styles.tenantIcon}>
                        <Building2 size={14} color="#3b82f6" />
                    </div>
                    <div style={styles.triggerText}>
                        <span style={styles.triggerLabel}>Organization</span>
                        <span style={styles.triggerValue}>
                            {activeTenant?.bankName || 'Select...'}
                        </span>
                    </div>
                </div>
                <ChevronDown
                    size={16}
                    style={{
                        ...styles.chevron,
                        transform: isOpen ? 'rotate(180deg)' : 'rotate(0deg)',
                    }}
                />
            </button>

            {/* Dropdown */}
            {isOpen && (
                <div style={styles.dropdown}>
                    {/* Header */}
                    <div style={styles.dropdownHeader}>
                        <Globe size={14} style={{ opacity: 0.5 }} />
                        <span style={styles.dropdownTitle}>Switch Organization</span>
                        {isSuperAdmin && (
                            <span style={styles.adminBadge}>
                                <Shield size={10} /> Admin
                            </span>
                        )}
                    </div>

                    {/* Tenant List */}
                    <div style={styles.tenantList}>
                        {tenants.map((tenant) => {
                            const isActive = String(tenant.tenantId) === String(activeTenantId);
                            const isLoading = switching === tenant.tenantId;

                            return (
                                <button
                                    key={tenant.tenantId}
                                    onClick={() => handleSwitch(tenant.tenantId)}
                                    disabled={isLoading}
                                    style={{
                                        ...styles.tenantItem,
                                        ...(isActive ? styles.tenantItemActive : {}),
                                        ...(isLoading ? styles.tenantItemLoading : {}),
                                    }}
                                    onMouseEnter={(e) => {
                                        if (!isActive) e.currentTarget.style.background = 'rgba(255,255,255,0.08)';
                                    }}
                                    onMouseLeave={(e) => {
                                        if (!isActive) e.currentTarget.style.background = 'transparent';
                                    }}
                                >
                                    <div style={styles.tenantItemLeft}>
                                        <div style={{
                                            ...styles.tenantItemIcon,
                                            background: isActive ? 'rgba(59,130,246,0.2)' : 'rgba(255,255,255,0.1)',
                                        }}>
                                            {isLoading ? (
                                                <Loader2 size={16} color="#60a5fa" style={{ animation: 'spin 1s linear infinite' }} />
                                            ) : (
                                                <Building2 size={16} color={isActive ? '#60a5fa' : 'rgba(255,255,255,0.5)'} />
                                            )}
                                        </div>
                                        <div style={styles.tenantItemInfo}>
                                            <span style={{
                                                ...styles.tenantItemName,
                                                color: isActive ? '#60a5fa' : 'rgba(255,255,255,0.9)',
                                                fontWeight: isActive ? 600 : 400,
                                            }}>
                                                {tenant.bankName}
                                            </span>
                                            <span style={styles.tenantItemMeta}>
                                                {tenant.bankShortCode}
                                                {tenant.country ? ` · ${tenant.country}` : ''}
                                                {tenant.baseCurrency ? ` · ${tenant.baseCurrency}` : ''}
                                            </span>
                                        </div>
                                    </div>

                                    {isActive && !isLoading && (
                                        <div style={styles.checkIcon}>
                                            <Check size={14} color="#60a5fa" />
                                        </div>
                                    )}
                                </button>
                            );
                        })}
                    </div>

                    {/* Footer */}
                    <div style={styles.dropdownFooter}>
                        <span style={styles.footerText}>
                            {tenants.length} organization{tenants.length !== 1 ? 's' : ''} available
                        </span>
                    </div>
                </div>
            )}

            {/* CSS for spinner animation */}
            <style>{`
                @keyframes spin {
                    from { transform: rotate(0deg); }
                    to { transform: rotate(360deg); }
                }
            `}</style>
        </div>
    );
};

// ==========================================
// Styles
// ==========================================
const styles = {
    container: {
        position: 'relative',
        width: '100%',
    },
    singleTenant: {
        display: 'flex',
        alignItems: 'center',
        gap: '8px',
        padding: '8px 12px',
        borderRadius: '8px',
        background: 'rgba(255,255,255,0.05)',
        color: 'rgba(255,255,255,0.7)',
        fontSize: '0.8rem',
    },
    singleTenantName: {
        fontSize: '0.8rem',
        fontWeight: 500,
        color: 'rgba(255,255,255,0.8)',
    },
    trigger: {
        width: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '10px 12px',
        borderRadius: '10px',
        border: '1px solid rgba(255,255,255,0.1)',
        background: 'rgba(255,255,255,0.05)',
        color: 'white',
        cursor: 'pointer',
        transition: 'all 0.2s ease',
        outline: 'none',
    },
    triggerOpen: {
        border: '1px solid rgba(96,165,250,0.4)',
        background: 'rgba(255,255,255,0.08)',
    },
    triggerLeft: {
        display: 'flex',
        alignItems: 'center',
        gap: '10px',
        overflow: 'hidden',
    },
    tenantIcon: {
        width: '28px',
        height: '28px',
        borderRadius: '6px',
        background: 'rgba(59,130,246,0.15)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
    },
    triggerText: {
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'flex-start',
        overflow: 'hidden',
    },
    triggerLabel: {
        fontSize: '0.65rem',
        color: 'rgba(255,255,255,0.4)',
        textTransform: 'uppercase',
        letterSpacing: '0.05em',
        fontWeight: 600,
    },
    triggerValue: {
        fontSize: '0.8rem',
        fontWeight: 500,
        color: 'rgba(255,255,255,0.9)',
        whiteSpace: 'nowrap',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        maxWidth: '140px',
    },
    chevron: {
        color: 'rgba(255,255,255,0.4)',
        transition: 'transform 0.2s ease',
        flexShrink: 0,
    },
    dropdown: {
        position: 'absolute',
        top: 'calc(100% + 6px)',
        left: 0,
        right: 0,
        zIndex: 100,
        borderRadius: '12px',
        border: '1px solid rgba(255,255,255,0.1)',
        background: '#1e293b',
        boxShadow: '0 20px 60px rgba(0,0,0,0.5)',
        overflow: 'hidden',
        animation: 'fadeIn 0.15s ease',
    },
    dropdownHeader: {
        display: 'flex',
        alignItems: 'center',
        gap: '8px',
        padding: '12px 14px 8px',
        color: 'rgba(255,255,255,0.4)',
        fontSize: '0.7rem',
        textTransform: 'uppercase',
        letterSpacing: '0.05em',
        fontWeight: 600,
    },
    dropdownTitle: {
        flex: 1,
    },
    adminBadge: {
        display: 'flex',
        alignItems: 'center',
        gap: '4px',
        padding: '2px 8px',
        borderRadius: '999px',
        background: 'rgba(234,179,8,0.15)',
        color: '#fbbf24',
        fontSize: '0.65rem',
        fontWeight: 600,
    },
    tenantList: {
        maxHeight: '280px',
        overflowY: 'auto',
        padding: '4px 6px',
    },
    tenantItem: {
        width: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '10px 10px',
        borderRadius: '8px',
        border: 'none',
        background: 'transparent',
        color: 'white',
        cursor: 'pointer',
        transition: 'all 0.15s ease',
        outline: 'none',
        marginBottom: '2px',
    },
    tenantItemActive: {
        background: 'rgba(59,130,246,0.1)',
    },
    tenantItemLoading: {
        opacity: 0.7,
        cursor: 'wait',
    },
    tenantItemLeft: {
        display: 'flex',
        alignItems: 'center',
        gap: '10px',
        overflow: 'hidden',
    },
    tenantItemIcon: {
        width: '32px',
        height: '32px',
        borderRadius: '8px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
    },
    tenantItemInfo: {
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'flex-start',
        overflow: 'hidden',
    },
    tenantItemName: {
        fontSize: '0.82rem',
        whiteSpace: 'nowrap',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        maxWidth: '140px',
    },
    tenantItemMeta: {
        fontSize: '0.68rem',
        color: 'rgba(255,255,255,0.35)',
        whiteSpace: 'nowrap',
    },
    checkIcon: {
        flexShrink: 0,
    },
    dropdownFooter: {
        padding: '8px 14px 10px',
        borderTop: '1px solid rgba(255,255,255,0.06)',
    },
    footerText: {
        fontSize: '0.68rem',
        color: 'rgba(255,255,255,0.25)',
    },
};

export default TenantSwitcher;
