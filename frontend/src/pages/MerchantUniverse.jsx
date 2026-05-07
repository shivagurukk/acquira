import React, { useState, useEffect, useRef } from 'react';
import {
    LayoutDashboard, Store, CreditCard,
    FileText, ShieldCheck, Settings, Users,
    MapPin, Activity, DollarSign, Search,
    Building2, Check, Upload as UploadIcon, X, FileUp, RefreshCw, TrendingUp
} from 'lucide-react';
import Loader from '../components/Loader';
import TenantSwitcher from '../components/TenantSwitcher';

import MerchantHierarchy from '../components/MerchantHierarchy';
import TransactionList from '../components/TransactionList';
import MerchantHeatmap from './business/MerchantHeatmap';
import useExcelExport from '../hooks/useExcelExport';

const MerchantUniverse = () => {
    const [loading, setLoading] = useState(true);
    // Removed redundant merchants state and pagination state

    // Refresh Key to trigger re-fetches in children
    const [refreshKey, setRefreshKey] = useState(0);

    const [activeView, setActiveView] = useState('LIST');
    const [selectedMerchant, setSelectedMerchant] = useState(null);
    const [showTenantModal, setShowTenantModal] = useState(false);
    const [availableTenants, setAvailableTenants] = useState([]);
    const { exportExcel, isExporting } = useExcelExport();

    // Upload & Batch State
    const [showUploadModal, setShowUploadModal] = useState(false);
    const [uploadFile, setUploadFile] = useState(null);
    const [uploadType, setUploadType] = useState('unified'); // unified, merchant, transaction
    const [isUploading, setIsUploading] = useState(false);
    const [uploadJobId, setUploadJobId] = useState(null);
    const [jobStatus, setJobStatus] = useState(null);
    const pollingIntervalRef = useRef(null);

    useEffect(() => {
        const fetchSession = async () => {
            const token = localStorage.getItem('token');
            try {
                // Fetch fresh session data (tenants etc)
                const response = await fetch('/api/auth/session', {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (response.ok) {
                    const data = await response.json();
                    setAvailableTenants(data.allowedTenants);
                    localStorage.setItem('allowedTenants', JSON.stringify(data.allowedTenants));

                    // CONSOLIDATION (2026-05): the rest of the app (axios interceptor +
                    // all dashboard pages) reads `defaultTenantId`. This component used
                    // to read/write `tenantId` causing tenant switches to silently fail
                    // for the rest of the app. We now read both for backward compat
                    // and ALWAYS write `defaultTenantId` so other pages stay in sync.
                    const currentTenant = localStorage.getItem('defaultTenantId') || localStorage.getItem('tenantId');
                    // If no valid tenant selected, verify if current is in allowed list or default
                    const isValid = data.allowedTenants.some(t => t.tenantId == currentTenant);

                    if (!currentTenant || currentTenant === 'null' || !isValid) {
                        // Auto-select Default if available, else show modal
                        if (data.defaultTenantId) {
                            localStorage.setItem('defaultTenantId', data.defaultTenantId);
                            localStorage.setItem('tenantId', data.defaultTenantId); // legacy mirror
                            setRefreshKey(prev => prev + 1);
                        } else {
                            setShowTenantModal(true);
                        }
                    } else if (!localStorage.getItem('defaultTenantId')) {
                        // Migrate stale `tenantId`-only setups to also have `defaultTenantId`.
                        localStorage.setItem('defaultTenantId', currentTenant);
                    }
                    // No need to fetchMerchants here, children will fetch on mount
                } else {
                    // Fallback to local storage if API fails
                    fallbackInit();
                }
            } catch (e) {
                console.error("Session fetch failed", e);
                fallbackInit();
            } finally {
                setLoading(false);
            }
        };

        const fallbackInit = () => {
            const storedTenants = localStorage.getItem('allowedTenants');
            const currentTenant = localStorage.getItem('defaultTenantId') || localStorage.getItem('tenantId');
            if (storedTenants) setAvailableTenants(JSON.parse(storedTenants));

            if (!currentTenant || currentTenant === 'null' || currentTenant === 'undefined') {
                setShowTenantModal(true);
            } else if (!localStorage.getItem('defaultTenantId')) {
                // Forward-fill so axios interceptor finds the tenant
                localStorage.setItem('defaultTenantId', currentTenant);
            }
            setLoading(false);
        };

        fetchSession();

        return () => stopPolling();
    }, []);

    const handleTenantSelect = (tenantId) => {
        // Write BOTH keys so the entire app sees the new tenant.
        // The axios interceptor reads `defaultTenantId`; legacy code paths read `tenantId`.
        localStorage.setItem('defaultTenantId', tenantId);
        localStorage.setItem('tenantId', tenantId);
        setShowTenantModal(false);
        setRefreshKey(prev => prev + 1); // Trigger remount of children
    };

    // Removed fetchMerchants and changePage

    // --- UPLOAD HANDLERS ---
    const handleFileChange = (e) => {
        setUploadFile(e.target.files[0]);
    };

    const handleUpload = async () => {
        if (!uploadFile) return;
        setIsUploading(true);
        setJobStatus(null);
        setUploadJobId(null);

        const formData = new FormData();
        formData.append('file', uploadFile);

        const token = localStorage.getItem('token');
        const endpoint = uploadType === 'transaction' ? '/api/upload/transaction' :
            uploadType === 'merchant' ? '/api/upload/merchant' : '/api/upload'; // Unified default

        try {
            const response = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${token}` },
                body: formData
            });

            if (response.ok) {
                const data = await response.json();
                setUploadJobId(data.jobId);
                startPolling(data.jobId);
            } else {
                alert('Upload failed');
                setIsUploading(false);
            }
        } catch (error) {
            console.error(error);
            setIsUploading(false);
        }
    };

    const startPolling = (jobId) => {
        if (pollingIntervalRef.current) clearInterval(pollingIntervalRef.current);

        pollingIntervalRef.current = setInterval(async () => {
            const token = localStorage.getItem('token');
            try {
                const res = await fetch(`/api/batch/jobs/${jobId}`, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (res.ok) {
                    const statusData = await res.json();
                    setJobStatus(statusData);
                    if (statusData.status === 'COMPLETED' || statusData.status === 'FAILED') {
                        stopPolling();
                        setIsUploading(false);
                        if (statusData.status === 'COMPLETED') {
                            setRefreshKey(prev => prev + 1); // Refresh list
                        }
                    }
                }
            } catch (e) {
                console.error("Polling error", e);
            }
        }, 2000); // Poll every 2s
    };

    const stopPolling = () => {
        if (pollingIntervalRef.current) clearInterval(pollingIntervalRef.current);
        pollingIntervalRef.current = null;
    };


    const SidebarItem = ({ id, label, icon: Icon }) => (
        <div
            onClick={() => setActiveView(id)}
            style={{
                display: 'flex', alignItems: 'center', gap: '10px',
                padding: '10px 15px',
                cursor: 'pointer',
                background: activeView === id ? '#e2e8f0' : 'transparent',
                borderRadius: '8px',
                color: activeView === id ? '#0f172a' : '#64748b',
                fontWeight: activeView === id ? 'bold' : 'normal',
                marginBottom: '4px'
            }}
        >
            <Icon size={18} />
            <span>{label}</span>
        </div>
    );

    const renderContent = () => {
        if (activeView === 'TRANSACTIONS') {
            return <TransactionList key={refreshKey} />;
        }
        if (['LIST', 'STORES', 'TERMINALS'].includes(activeView)) {
            return <MerchantHierarchy key={refreshKey} viewMode={activeView} />;
        }

        if (activeView === 'PROFILE' && selectedMerchant) {
            return (
                <MerchantProfileView merchant={selectedMerchant} onBack={() => setActiveView('LIST')} />
            );
        }

        if (activeView === 'HEATMAP') {
            return <MerchantHeatmap />;
        }

        return <div style={{ padding: '20px', color: '#64748b' }}>Section under construction</div>;
    };

    // Removed blocking loader check if merchants is empty
    if (loading) return <Loader />;

    return (
        <div style={{ display: 'flex', height: '100%', position: 'relative' }}>

            {/* Upload Modal */}
            {showUploadModal && (
                <div style={{
                    position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
                    backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 60,
                    display: 'flex', justifyContent: 'center', alignItems: 'center',
                    backdropFilter: 'blur(2px)'
                }}>
                    <div style={{
                        background: 'white', borderRadius: '12px', padding: '24px',
                        width: '450px', boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1)'
                    }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                            <h3 style={{ fontSize: '1.2rem', fontWeight: 'bold' }}>Import Data</h3>
                            <button onClick={() => setShowUploadModal(false)} style={{ background: 'none', border: 'none', cursor: 'pointer' }}><X size={20} /></button>
                        </div>

                        {!jobStatus ? (
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '15px' }}>
                                <div>
                                    <label style={{ display: 'block', fontSize: '0.9rem', marginBottom: '5px', fontWeight: '500' }}>File Type</label>
                                    <select
                                        value={uploadType}
                                        onChange={(e) => setUploadType(e.target.value)}
                                        style={{ width: '100%', padding: '10px', borderRadius: '6px', border: '1px solid #cbd5e1' }}
                                    >
                                        <option value="unified">Unified (Auto-Detect)</option>
                                        <option value="merchant">Merchant Master</option>
                                        <option value="transaction">Transactions</option>
                                    </select>
                                </div>
                                <div style={{ border: '2px dashed #cbd5e1', borderRadius: '8px', padding: '30px', textAlign: 'center', cursor: 'pointer', position: 'relative' }}>
                                    <input
                                        type="file"
                                        onChange={handleFileChange}
                                        style={{ opacity: 0, position: 'absolute', top: 0, left: 0, width: '100%', height: '100%', cursor: 'pointer' }}
                                    />
                                    <FileUp size={32} color="#64748b" style={{ marginBottom: '10px' }} />
                                    <p style={{ color: '#64748b', fontSize: '0.9rem' }}>
                                        {uploadFile ? uploadFile.name : "Click to select or drag file here"}
                                    </p>
                                </div>
                                <button
                                    onClick={handleUpload}
                                    disabled={!uploadFile || isUploading}
                                    style={{
                                        width: '100%', padding: '12px', borderRadius: '6px',
                                        background: isUploading ? '#94a3b8' : '#3b82f6', color: 'white',
                                        border: 'none', cursor: isUploading ? 'not-allowed' : 'pointer',
                                        fontWeight: 'bold', marginTop: '10px'
                                    }}
                                >
                                    {isUploading ? 'Uploading...' : 'Start Import'}
                                </button>
                            </div>
                        ) : (
                            <div style={{ textAlign: 'center', padding: '20px 0' }}>
                                <RefreshCw size={40} className={jobStatus.status === 'STARTED' || jobStatus.status === 'STARTING' ? 'spin' : ''} color={jobStatus.status === 'COMPLETED' ? '#16a34a' : '#3b82f6'} style={{ marginBottom: '15px' }} />
                                <h4 style={{ fontSize: '1.1rem', fontWeight: 'bold' }}>{jobStatus.status}</h4>
                                <p style={{ color: '#64748b', fontSize: '0.9rem', marginBottom: '20px' }}>
                                    {jobStatus.status === 'COMPLETED' ? 'Import completed successfully.' :
                                        jobStatus.status === 'FAILED' ? 'Import failed.' : 'Processing your file...'}
                                </p>

                                <div style={{ background: '#f8fafc', padding: '15px', borderRadius: '8px', fontSize: '0.9rem', textAlign: 'left' }}>
                                    <div><strong>Read:</strong> {jobStatus.readCount}</div>
                                    <div><strong>Written:</strong> {jobStatus.writeCount}</div>
                                    <div><strong>Skipped:</strong> {jobStatus.skipCount}</div>
                                </div>

                                {(jobStatus.status === 'COMPLETED' || jobStatus.status === 'FAILED') && (
                                    <button
                                        onClick={() => { setJobStatus(null); setUploadFile(null); setShowUploadModal(false); }}
                                        style={{
                                            marginTop: '20px', padding: '10px 20px', borderRadius: '6px',
                                            background: '#cbd5e1', color: '#334155', border: 'none', cursor: 'pointer'
                                        }}
                                    >
                                        Close
                                    </button>
                                )}
                            </div>
                        )}
                    </div>
                </div>
            )}

            {/* Tenant Selection Modal */}
            {showTenantModal && (
                <div style={{
                    position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
                    backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 50,
                    display: 'flex', justifyContent: 'center', alignItems: 'center',
                    backdropFilter: 'blur(4px)'
                }}>
                    <div style={{
                        background: 'white', borderRadius: '16px', padding: '32px',
                        width: '400px', boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)'
                    }}>
                        <h2 style={{ fontSize: '1.5rem', fontWeight: 'bold', marginBottom: '8px', textAlign: 'center' }}>Select Tenant</h2>
                        <p style={{ color: '#64748b', textAlign: 'center', marginBottom: '24px' }}>
                            Choose an organization to view its merchants.
                        </p>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                            {availableTenants.map(tenant => (
                                <button
                                    key={tenant.tenantId || tenant}
                                    onClick={() => handleTenantSelect(tenant.tenantId || tenant)}
                                    style={{
                                        padding: '16px', borderRadius: '12px',
                                        border: '1px solid #e2e8f0',
                                        background: localStorage.getItem('tenantId') == (tenant.tenantId || tenant) ? '#eff6ff' : 'white',
                                        display: 'flex', alignItems: 'center', gap: '12px',
                                        cursor: 'pointer', transition: 'all 0.2s',
                                        textAlign: 'left'
                                    }}
                                    onMouseOver={(e) => e.currentTarget.style.borderColor = '#3b82f6'}
                                    onMouseOut={(e) => e.currentTarget.style.borderColor = '#e2e8f0'}
                                >
                                    <div style={{ padding: '8px', background: '#f1f5f9', borderRadius: '8px' }}>
                                        <Building2 size={20} color="#64748b" />
                                    </div>
                                    <div style={{ flex: 1 }}>
                                        <div style={{ fontWeight: '600', color: '#0f172a' }}>{tenant.bankName || `Tenant ${tenant}`}</div>
                                        {tenant.institutionId && <div style={{ fontSize: '0.8rem', color: '#94a3b8' }}>ID: {tenant.institutionId}</div>}
                                    </div>
                                    {localStorage.getItem('tenantId') == (tenant.tenantId || tenant) &&
                                        <Check size={20} color="#3b82f6" />
                                    }
                                </button>
                            ))}
                        </div>
                    </div>
                </div>
            )}

            {/* Inner Sidebar */}
            <div style={{ width: '240px', background: 'white', borderRight: '1px solid #e2e8f0', padding: '20px', display: 'flex', flexDirection: 'column' }}>
                <h3 style={{ fontSize: '0.9rem', color: '#94a3b8', textTransform: 'uppercase', marginBottom: '15px', fontWeight: 'bold' }}>Merchant Management</h3>

                <SidebarItem id="LIST" label="Merchant List" icon={Users} />
                <SidebarItem id="DASHBOARD" label="Overview (KPIs)" icon={LayoutDashboard} />
                <SidebarItem id="HEATMAP" label="Growth Heatmap" icon={TrendingUp} />

                <h3 style={{ fontSize: '0.9rem', color: '#94a3b8', textTransform: 'uppercase', marginTop: '20px', marginBottom: '15px', fontWeight: 'bold' }}>Operations</h3>
                <SidebarItem id="TRANSACTIONS" label="Transactions" icon={FileText} />
                <SidebarItem id="STORES" label="Store Management" icon={Store} />
                <SidebarItem id="TERMINALS" label="Terminal Management" icon={CreditCard} />
                <SidebarItem id="CONTACTS" label="Contacts" icon={Users} />
                <SidebarItem id="DOCUMENTS" label="Documents" icon={FileText} />

                <h3 style={{ fontSize: '0.9rem', color: '#94a3b8', textTransform: 'uppercase', marginTop: '20px', marginBottom: '15px', fontWeight: 'bold' }}>Risk & Finance</h3>
                <SidebarItem id="RISK" label="Risk Profile" icon={ShieldCheck} />
                <SidebarItem id="SETTLEMENT" label="Settlement" icon={DollarSign} />
                <SidebarItem id="ACTIVITY" label="Activity" icon={Activity} />

                <div style={{ marginTop: 'auto', paddingTop: '20px', borderTop: '1px solid #e2e8f0' }}>
                    <button
                        onClick={() => exportExcel('MERCHANT_MASTER')}
                        disabled={isExporting}
                        style={{
                            width: '100%', padding: '10px', borderRadius: '8px',
                            background: isExporting ? '#cbd5e1' : 'white',
                            color: isExporting ? '#64748b' : '#3b82f6',
                            border: '1px solid #e2e8f0', fontWeight: '600',
                            cursor: isExporting ? 'not-allowed' : 'pointer',
                            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px'
                        }}
                    >
                        {isExporting ? <Loader size={16} /> : <FileUp size={18} />}
                        <span>{isExporting ? 'Exporting...' : 'Export Merchants'}</span>
                    </button>
                </div>
            </div>

            {/* Main Content Area */}
            <div style={{ flex: 1, padding: '24px', overflowY: 'auto', background: '#f1f5f9' }}>
                {renderContent()}
            </div>

            <style>{`
                .spin { animation: spin 1s linear infinite; }
                @keyframes spin { 100% { transform: rotate(360deg); } }
            `}</style>
        </div>
    );
};

// -- SUB COMPONENTS --

const MerchantProfileView = ({ merchant, onBack }) => {
    // Determine active tab state, or just render all for 360 view
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchData = async () => {
            const token = localStorage.getItem('token');
            try {
                // Fetch 360 View from new endpoint
                const res = await fetch(`/api/merchants/${merchant.merchantId}/360`, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (res.ok) {
                    const json = await res.json();
                    setData(json);
                }
            } catch (e) { console.error(e); }
            finally { setLoading(false); }
        };
        fetchData();
    }, [merchant]);

    if (loading) return <div style={{ padding: '20px' }}>Loading profile...</div>;
    if (!data) return <div style={{ padding: '20px' }}>Error loading profile.</div>;

    const { merchant: m, stores, contacts, documents, riskProfile } = data;

    return (
        <div>
            <button onClick={onBack} style={{ marginBottom: '15px', background: 'none', border: 'none', color: '#3b82f6', cursor: 'pointer' }}>&larr; Back to List</button>

            {/* Header Card */}
            <div style={{ background: 'white', padding: '24px', borderRadius: '12px', marginBottom: '20px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <div>
                        <h2 style={{ fontSize: '1.8rem', fontWeight: 'bold', marginBottom: '5px' }}>{m.name}</h2>
                        <div style={{ color: '#64748b' }}>MID: {m.mid} | Internal ID: {m.internalId}</div>
                    </div>
                    <span style={{
                        padding: '6px 12px', borderRadius: '20px',
                        background: m.status === 'ACTIVE' ? '#dcfce7' : '#fee2e2',
                        color: m.status === 'ACTIVE' ? '#166534' : '#991b1b',
                        fontWeight: 'bold'
                    }}>{m.status}</span>
                </div>
            </div>

            {/* Grid Layout */}
            <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '20px' }}>

                {/* Left Column */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>

                    {/* Stores & Terminals */}
                    <div style={{ background: 'white', padding: '20px', borderRadius: '12px' }}>
                        <h3 style={{ fontSize: '1.1rem', fontWeight: 'bold', marginBottom: '15px', borderBottom: '1px solid #f1f5f9', paddingBottom: '10px' }}>Stores ({stores ? stores.length : 0})</h3>
                        {stores && stores.map(store => (
                            <div key={store.storeId} style={{ marginBottom: '15px', padding: '15px', border: '1px solid #e2e8f0', borderRadius: '8px' }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', fontWeight: '600' }}>
                                    <span>{store.name}</span>
                                    <span style={{ fontSize: '0.8rem', color: '#64748b' }}>{store.sid}</span>
                                </div>
                                <div style={{ fontSize: '0.9rem', color: '#64748b', marginTop: '5px' }}>{store.city}, {store.state}</div>
                            </div>
                        ))}
                    </div>

                    <div style={{ background: 'white', padding: '20px', borderRadius: '12px' }}>
                        <h3 style={{ fontSize: '1.1rem', fontWeight: 'bold', marginBottom: '15px', borderBottom: '1px solid #f1f5f9', paddingBottom: '10px' }}>Terminals ({data.terminals ? data.terminals.length : 0})</h3>
                        {data.terminals && data.terminals.map(terminal => {
                            const store = stores.find(s => s.storeId === terminal.storeId);
                            return (
                                <div key={terminal.terminalId} style={{ marginBottom: '10px', padding: '10px', background: '#f8fafc', borderRadius: '6px', fontSize: '0.9rem' }}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', fontWeight: '600', marginBottom: '4px' }}>
                                        <span>TID: {terminal.tid}</span>
                                        <span style={{
                                            padding: '2px 6px', borderRadius: '4px', fontSize: '0.75rem',
                                            background: terminal.status === 'ACTIVE' ? '#dcfce7' : '#fee2e2',
                                            color: terminal.status === 'ACTIVE' ? '#166534' : '#991b1b'
                                        }}>{terminal.status}</span>
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', color: '#64748b', fontSize: '0.85rem' }}>
                                        <span>Dev: {terminal.deviceNumber}</span>
                                        <span>{terminal.type}</span>
                                    </div>
                                    <div style={{ fontSize: '0.8rem', color: '#94a3b8', marginTop: '4px' }}>
                                        Store: {store ? store.name : 'Unknown'}
                                    </div>
                                </div>
                            );
                        })}
                    </div>

                    {/* Contacts */}
                    <div style={{ background: 'white', padding: '20px', borderRadius: '12px' }}>
                        <h3 style={{ fontSize: '1.1rem', fontWeight: 'bold', marginBottom: '15px' }}>Contacts ({contacts ? contacts.length : 0})</h3>
                        <table style={{ width: '100%', fontSize: '0.9rem' }}>
                            <thead>
                                <tr style={{ color: '#64748b', textAlign: 'left' }}>
                                    <th>Name</th><th>Role</th><th>Email</th><th>Phone</th>
                                </tr>
                            </thead>
                            <tbody>
                                {contacts && contacts.map(c => (
                                    <tr key={c.contactId} style={{ borderTop: '1px solid #f1f5f9' }}>
                                        <td style={{ padding: '8px 0' }}>{c.contactName}</td>
                                        <td>{c.role}</td>
                                        <td>{c.email}</td>
                                        <td>{c.phone}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>

                {/* Right Column */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                    {/* Risk Profile */}
                    <div style={{ background: 'white', padding: '20px', borderRadius: '12px' }}>
                        <h3 style={{ fontSize: '1.1rem', fontWeight: 'bold', marginBottom: '15px' }}>Risk Profile</h3>
                        {riskProfile ? (
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                                    <span style={{ color: '#64748b' }}>Risk Score</span>
                                    <strong>{riskProfile.riskScore || '-'}</strong>
                                </div>
                                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                                    <span style={{ color: '#64748b' }}>KYC Status</span>
                                    <strong>{riskProfile.kycStatus || '-'}</strong>
                                </div>
                                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                                    <span style={{ color: '#64748b' }}>Last Review</span>
                                    <strong>{riskProfile.lastReviewDate || '-'}</strong>
                                </div>
                            </div>
                        ) : (
                            <p style={{ color: '#94a3b8' }}>No risk profile data found.</p>
                        )}
                    </div>

                    {/* Documents */}
                    <div style={{ background: 'white', padding: '20px', borderRadius: '12px' }}>
                        <h3 style={{ fontSize: '1.1rem', fontWeight: 'bold', marginBottom: '15px' }}>Documents</h3>
                        {documents && documents.map(d => (
                            <div key={d.documentId} style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '10px' }}>
                                <FileText size={16} color="#64748b" />
                                <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                    <div style={{ fontSize: '0.9rem' }}>{d.documentName}</div>
                                    <div style={{ fontSize: '0.75rem', color: '#94a3b8' }}>{d.documentType}</div>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
};

export default MerchantUniverse;
