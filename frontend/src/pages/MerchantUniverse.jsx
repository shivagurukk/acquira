import React, { useState, useEffect, useRef } from 'react';
import {
    LayoutDashboard, Store, CreditCard,
    FileText, ShieldCheck, Users,
    Activity, DollarSign,
    Building2, Check, X, FileUp, RefreshCw, TrendingUp,
    Upload as UploadIcon, ChevronLeft, Download
} from 'lucide-react';
import Loader from '../components/Loader';
import { useAuth } from '../contexts/AuthContext';
import api, { UPLOAD_TIMEOUT, isTimeoutError } from '../api/axios';

import MerchantHierarchy from '../components/MerchantHierarchy';
import TransactionList from '../components/TransactionList';
import MerchantHeatmap from './business/MerchantHeatmap';
import useExcelExport from '../hooks/useExcelExport';

/* View → human title shown in the workspace header */
const VIEW_META = {
    LIST: { title: 'Merchant List', desc: 'Browse and drill into your merchant hierarchy' },
    DASHBOARD: { title: 'Overview', desc: 'Portfolio KPIs at a glance' },
    HEATMAP: { title: 'Growth Heatmap', desc: 'Merchant growth and momentum' },
    TRANSACTIONS: { title: 'Transactions', desc: 'Recent transaction activity' },
    STORES: { title: 'Store Management', desc: 'Stores across all merchants' },
    TERMINALS: { title: 'Terminal Management', desc: 'Terminals across all stores' },
    CONTACTS: { title: 'Contacts', desc: 'Merchant contact directory' },
    DOCUMENTS: { title: 'Documents', desc: 'Uploaded merchant documents' },
    RISK: { title: 'Risk Profile', desc: 'Risk scoring and KYC status' },
    SETTLEMENT: { title: 'Settlement', desc: 'Settlement schedule and configuration' },
    ACTIVITY: { title: 'Activity', desc: 'Engagement and activity log' },
    PROFILE: { title: 'Merchant 360', desc: 'Full merchant profile' },
};

const NAV_SECTIONS = [
    {
        label: 'Management',
        items: [
            { id: 'LIST', label: 'Merchant List', icon: Users },
            { id: 'DASHBOARD', label: 'Overview (KPIs)', icon: LayoutDashboard },
            { id: 'HEATMAP', label: 'Growth Heatmap', icon: TrendingUp },
        ],
    },
    {
        label: 'Operations',
        items: [
            { id: 'TRANSACTIONS', label: 'Transactions', icon: FileText },
            { id: 'STORES', label: 'Store Management', icon: Store },
            { id: 'TERMINALS', label: 'Terminal Management', icon: CreditCard },
            { id: 'CONTACTS', label: 'Contacts', icon: Users },
            { id: 'DOCUMENTS', label: 'Documents', icon: FileText },
        ],
    },
    {
        label: 'Risk & Finance',
        items: [
            { id: 'RISK', label: 'Risk Profile', icon: ShieldCheck },
            { id: 'SETTLEMENT', label: 'Settlement', icon: DollarSign },
            { id: 'ACTIVITY', label: 'Activity', icon: Activity },
        ],
    },
];

const MerchantUniverse = () => {
    const { tenantVersion } = useAuth();
    const [loading, setLoading] = useState(true);

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
    }, [tenantVersion]);

    // When a super-admin switches tenant elsewhere in the app, AuthContext bumps
    // tenantVersion. Remount the data children so they re-fetch under the new
    // tenant (skip the very first mount, which the session effect already covers).
    const didMountRef = useRef(false);
    useEffect(() => {
        if (!didMountRef.current) { didMountRef.current = true; return; }
        setRefreshKey(prev => prev + 1);
    }, [tenantVersion]);

    const handleTenantSelect = (tenantId) => {
        // Write BOTH keys so the entire app sees the new tenant.
        // The axios interceptor reads `defaultTenantId`; legacy code paths read `tenantId`.
        localStorage.setItem('defaultTenantId', tenantId);
        localStorage.setItem('tenantId', tenantId);
        setShowTenantModal(false);
        setRefreshKey(prev => prev + 1); // Trigger remount of children
    };

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

        const endpoint = uploadType === 'transaction' ? '/upload/transaction' :
            uploadType === 'merchant' ? '/upload/merchant' : '/upload'; // Unified default

        try {
            // Shared client so the X-Tenant-Id header is attached. A raw fetch here
            // ingested merchant/transaction rows into the user's DEFAULT tenant rather
            // than the active one — silent, and awkward to undo once summaries are built.
            const { data } = await api.post(endpoint, formData, { timeout: UPLOAD_TIMEOUT });
            setUploadJobId(data.jobId);
            startPolling(data.jobId);
        } catch (error) {
            console.error(error);
            // A timeout means we stopped listening, not that the ingest failed —
            // re-uploading on this message is how a file lands twice.
            alert(isTimeoutError(error)
                ? 'The server did not respond in time. The file may still be processing — '
                  + 'check Batch Monitoring before uploading it again.'
                : 'Upload failed');
            setIsUploading(false);
        }
    };

    const startPolling = (jobId) => {
        if (pollingIntervalRef.current) clearInterval(pollingIntervalRef.current);

        pollingIntervalRef.current = setInterval(async () => {
            try {
                const { data: statusData } = await api.get(`/batch/jobs/${jobId}`);
                setJobStatus(statusData);
                if (statusData.status === 'COMPLETED' || statusData.status === 'FAILED') {
                    stopPolling();
                    setIsUploading(false);
                    if (statusData.status === 'COMPLETED') {
                        setRefreshKey(prev => prev + 1); // Refresh list
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

    const renderContent = () => {
        if (activeView === 'TRANSACTIONS') {
            return <TransactionList key={refreshKey} />;
        }
        if (['LIST', 'STORES', 'TERMINALS'].includes(activeView)) {
            return <MerchantHierarchy key={refreshKey} viewMode={activeView} />;
        }
        if (activeView === 'PROFILE' && selectedMerchant) {
            return <MerchantProfileView merchant={selectedMerchant} onBack={() => setActiveView('LIST')} />;
        }
        if (activeView === 'HEATMAP') {
            return <MerchantHeatmap />;
        }
        return (
            <UnderConstruction title={VIEW_META[activeView]?.title || 'Section'} />
        );
    };

    if (loading) return <Loader />;

    const currentTenantId = localStorage.getItem('defaultTenantId') || localStorage.getItem('tenantId');
    const currentTenant = availableTenants.find(t => String(t.tenantId ?? t) === String(currentTenantId));
    const currentTenantName = currentTenant?.bankName || (currentTenantId ? `Tenant ${currentTenantId}` : 'No tenant selected');
    const meta = VIEW_META[activeView] || { title: 'Merchant Universe', desc: '' };

    return (
        <div className="mu-root">

            {/* ── Upload Modal ── */}
            {showUploadModal && (
                <div className="mu-overlay" style={{ zIndex: 60 }}>
                    <div className="mu-modal" style={{ width: 460 }}>
                        <div className="mu-modal-head">
                            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                                <span className="mu-icon-tile"><UploadIcon size={16} /></span>
                                <h3 className="mu-modal-title">Import Data</h3>
                            </div>
                            <button className="mu-icon-btn" onClick={() => setShowUploadModal(false)}><X size={18} /></button>
                        </div>

                        {!jobStatus ? (
                            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                                <div>
                                    <label className="mu-label">File type</label>
                                    <select
                                        value={uploadType}
                                        onChange={(e) => setUploadType(e.target.value)}
                                        className="mu-select"
                                    >
                                        <option value="unified">Unified (Auto-Detect)</option>
                                        <option value="merchant">Merchant Master</option>
                                        <option value="transaction">Transactions</option>
                                    </select>
                                </div>
                                <div className="mu-dropzone">
                                    <input
                                        type="file"
                                        onChange={handleFileChange}
                                        style={{ opacity: 0, position: 'absolute', inset: 0, width: '100%', height: '100%', cursor: 'pointer' }}
                                    />
                                    <FileUp size={30} style={{ color: 'var(--brand)', marginBottom: 10 }} />
                                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                                        {uploadFile ? uploadFile.name : 'Click to select or drag a file here'}
                                    </p>
                                    <p style={{ color: 'var(--text-muted)', fontSize: '0.72rem', marginTop: 4 }}>
                                        .xlsx, .csv, .tsv supported
                                    </p>
                                </div>
                                <button
                                    onClick={handleUpload}
                                    disabled={!uploadFile || isUploading}
                                    className="mu-btn-primary"
                                    style={{ width: '100%', justifyContent: 'center', padding: '11px', opacity: (!uploadFile || isUploading) ? 0.6 : 1, cursor: (!uploadFile || isUploading) ? 'not-allowed' : 'pointer' }}
                                >
                                    {isUploading ? <RefreshCw size={15} className="spin" /> : <UploadIcon size={15} />}
                                    {isUploading ? 'Uploading…' : 'Start Import'}
                                </button>
                            </div>
                        ) : (
                            <div style={{ textAlign: 'center', padding: '12px 0' }}>
                                <RefreshCw
                                    size={38}
                                    className={(jobStatus.status === 'STARTED' || jobStatus.status === 'STARTING') ? 'spin' : ''}
                                    style={{ color: jobStatus.status === 'COMPLETED' ? 'var(--success)' : jobStatus.status === 'FAILED' ? 'var(--danger)' : 'var(--brand)', marginBottom: 14 }}
                                />
                                <h4 style={{ fontSize: '1.05rem', fontWeight: 700, color: 'var(--text)' }}>{jobStatus.status}</h4>
                                <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 18 }}>
                                    {jobStatus.status === 'COMPLETED' ? 'Import completed successfully.' :
                                        jobStatus.status === 'FAILED' ? 'Import failed.' : 'Processing your file…'}
                                </p>

                                <div className="mu-stat-grid">
                                    <div className="mu-stat"><span>Read</span><strong>{jobStatus.readCount ?? '—'}</strong></div>
                                    <div className="mu-stat"><span>Written</span><strong>{jobStatus.writeCount ?? '—'}</strong></div>
                                    <div className="mu-stat"><span>Skipped</span><strong>{jobStatus.skipCount ?? '—'}</strong></div>
                                </div>

                                {(jobStatus.status === 'COMPLETED' || jobStatus.status === 'FAILED') && (
                                    <button
                                        onClick={() => { setJobStatus(null); setUploadFile(null); setShowUploadModal(false); }}
                                        className="mu-btn-ghost"
                                        style={{ marginTop: 18 }}
                                    >
                                        Close
                                    </button>
                                )}
                            </div>
                        )}
                    </div>
                </div>
            )}

            {/* ── Tenant Selection Modal ── */}
            {showTenantModal && (
                <div className="mu-overlay" style={{ zIndex: 50 }}>
                    <div className="mu-modal" style={{ width: 420 }}>
                        <div style={{ textAlign: 'center', marginBottom: 22 }}>
                            <span className="mu-icon-tile" style={{ margin: '0 auto 12px', width: 44, height: 44 }}>
                                <Building2 size={22} />
                            </span>
                            <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--text)', marginBottom: 4 }}>Select Tenant</h2>
                            <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                                Choose an organization to view its merchants.
                            </p>
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxHeight: 360, overflowY: 'auto' }}>
                            {availableTenants.map(tenant => {
                                const tid = tenant.tenantId ?? tenant;
                                const isCurrent = String(currentTenantId) === String(tid);
                                return (
                                    <button
                                        key={tid}
                                        onClick={() => handleTenantSelect(tid)}
                                        className={`mu-tenant-card${isCurrent ? ' active' : ''}`}
                                    >
                                        <span className="mu-tenant-ico"><Building2 size={18} /></span>
                                        <span style={{ flex: 1, textAlign: 'left' }}>
                                            <span style={{ display: 'block', fontWeight: 600, color: 'var(--text)' }}>{tenant.bankName || `Tenant ${tid}`}</span>
                                            {tenant.institutionId && <span style={{ display: 'block', fontSize: '0.75rem', color: 'var(--text-muted)' }}>ID: {tenant.institutionId}</span>}
                                        </span>
                                        {isCurrent && <Check size={18} style={{ color: 'var(--brand)' }} />}
                                    </button>
                                );
                            })}
                        </div>
                    </div>
                </div>
            )}

            {/* ── Inner workspace rail ── */}
            <aside className="mu-rail">
                <div className="mu-rail-brand">
                    <span className="mu-icon-tile"><Store size={17} /></span>
                    <div style={{ minWidth: 0 }}>
                        <div className="mu-rail-title">Merchant Universe</div>
                        <div className="mu-rail-sub" title={currentTenantName}>{currentTenantName}</div>
                    </div>
                </div>

                <nav className="mu-nav-list">
                    {NAV_SECTIONS.map(section => (
                        <div key={section.label} style={{ marginBottom: 14 }}>
                            <div className="mu-nav-section">{section.label}</div>
                            {section.items.map(item => {
                                const Icon = item.icon;
                                const active = activeView === item.id;
                                return (
                                    <button
                                        key={item.id}
                                        onClick={() => setActiveView(item.id)}
                                        className={`mu-nav${active ? ' active' : ''}`}
                                    >
                                        <Icon size={16} />
                                        <span>{item.label}</span>
                                    </button>
                                );
                            })}
                        </div>
                    ))}
                </nav>

                <div className="mu-rail-foot">
                    <button
                        onClick={() => exportExcel('MERCHANT_MASTER')}
                        disabled={isExporting}
                        className="mu-btn-ghost"
                        style={{ width: '100%', justifyContent: 'center', opacity: isExporting ? 0.6 : 1, cursor: isExporting ? 'not-allowed' : 'pointer' }}
                    >
                        {isExporting ? <RefreshCw size={15} className="spin" /> : <Download size={15} />}
                        <span>{isExporting ? 'Exporting…' : 'Export Merchants'}</span>
                    </button>
                </div>
            </aside>

            {/* ── Main column ── */}
            <main className="mu-main">
                <header className="mu-header">
                    <div style={{ minWidth: 0 }}>
                        <h1 className="mu-header-title">{meta.title}</h1>
                        {meta.desc && <p className="mu-header-desc">{meta.desc}</p>}
                    </div>
                    <div className="mu-header-actions">
                        <button className="mu-tenant-chip" onClick={() => setShowTenantModal(true)} title="Switch tenant">
                            <Building2 size={14} />
                            <span className="mu-tenant-chip-name">{currentTenantName}</span>
                        </button>
                        <button className="mu-btn-primary" onClick={() => { setJobStatus(null); setUploadFile(null); setShowUploadModal(true); }}>
                            <UploadIcon size={15} />
                            <span>Import Data</span>
                        </button>
                    </div>
                </header>

                <div className="mu-content">
                    {renderContent()}
                </div>
            </main>

            <style>{`
                .mu-root {
                    display: flex; height: 100%; position: relative;
                    background: var(--bg); color: var(--text);
                }
                /* Rail */
                .mu-rail {
                    width: 256px; flex-shrink: 0; display: flex; flex-direction: column;
                    background: var(--bg-card); border-right: 1px solid var(--border);
                    padding: 18px 14px;
                }
                .mu-rail-brand {
                    display: flex; align-items: center; gap: 11px; padding: 4px 6px 16px;
                    border-bottom: 1px solid var(--border-light); margin-bottom: 14px;
                }
                .mu-rail-title { font-size: 0.95rem; font-weight: 700; color: var(--text); letter-spacing: -0.01em; line-height: 1.2; }
                .mu-rail-sub {
                    font-size: 0.74rem; color: var(--text-muted); margin-top: 2px;
                    overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 170px;
                }
                .mu-nav-list { flex: 1; overflow-y: auto; }
                .mu-nav-section {
                    font-size: 0.68rem; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase;
                    color: var(--text-muted); padding: 0 8px; margin-bottom: 6px;
                }
                .mu-nav {
                    width: 100%; display: flex; align-items: center; gap: 11px;
                    padding: 8px 10px; margin-bottom: 2px; border: none; background: transparent;
                    border-radius: var(--radius-md); cursor: pointer; text-align: left;
                    font-size: 0.84rem; font-weight: 500; color: var(--text-secondary);
                    transition: background 0.14s, color 0.14s;
                }
                .mu-nav:hover { background: var(--bg-hover); color: var(--text); }
                .mu-nav.active {
                    background: var(--sidebar-active-bg); color: var(--sidebar-active-text); font-weight: 600;
                }
                .mu-rail-foot { padding-top: 14px; border-top: 1px solid var(--border-light); }

                /* Main */
                .mu-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
                .mu-header {
                    display: flex; align-items: center; justify-content: space-between; gap: 16px;
                    flex-wrap: wrap; padding: 16px 24px; background: var(--bg-card);
                    border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 20;
                }
                .mu-header-title { font-size: 1.1rem; font-weight: 700; color: var(--text); letter-spacing: -0.02em; line-height: 1.2; }
                .mu-header-desc { font-size: 0.8rem; color: var(--text-secondary); margin-top: 2px; }
                .mu-header-actions { display: flex; align-items: center; gap: 9px; }
                .mu-content { flex: 1; overflow-y: auto; padding: 22px 24px; background: var(--bg); }

                /* Buttons */
                .mu-btn-primary {
                    display: inline-flex; align-items: center; gap: 7px; padding: 8px 15px;
                    border: none; border-radius: var(--radius-md); cursor: pointer;
                    background: var(--brand); color: #fff; font-size: 0.82rem; font-weight: 600;
                    transition: background 0.15s; white-space: nowrap;
                }
                .mu-btn-primary:hover:not(:disabled) { background: var(--brand-dark); }
                .mu-btn-ghost {
                    display: inline-flex; align-items: center; gap: 7px; padding: 8px 15px;
                    border: 1px solid var(--border); border-radius: var(--radius-md); cursor: pointer;
                    background: var(--bg-card); color: var(--text-secondary); font-size: 0.82rem; font-weight: 600;
                    transition: all 0.15s; white-space: nowrap;
                }
                .mu-btn-ghost:hover:not(:disabled) { border-color: var(--brand); color: var(--text); }
                .mu-icon-btn {
                    display: inline-flex; align-items: center; justify-content: center;
                    width: 30px; height: 30px; border: none; background: transparent;
                    border-radius: var(--radius-sm); cursor: pointer; color: var(--text-secondary);
                    transition: background 0.15s;
                }
                .mu-icon-btn:hover { background: var(--bg-hover); color: var(--text); }
                .mu-tenant-chip {
                    display: inline-flex; align-items: center; gap: 7px; padding: 7px 12px;
                    border: 1px solid var(--border); border-radius: var(--radius-md); cursor: pointer;
                    background: var(--bg-card); color: var(--text-secondary); font-size: 0.8rem; font-weight: 600;
                    transition: all 0.15s; max-width: 220px;
                }
                .mu-tenant-chip:hover { border-color: var(--brand); color: var(--text); }
                .mu-tenant-chip-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

                .mu-icon-tile {
                    width: 36px; height: 36px; flex-shrink: 0; border-radius: var(--radius-md);
                    display: inline-flex; align-items: center; justify-content: center;
                    background: var(--brand-light); color: var(--brand);
                }

                /* Modals */
                .mu-overlay {
                    position: absolute; inset: 0; background: rgba(15,23,42,0.45);
                    backdrop-filter: blur(3px); display: flex; align-items: center; justify-content: center;
                    padding: 20px; animation: fadeIn 0.18s ease-out;
                }
                .mu-modal {
                    background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); padding: 24px; box-shadow: var(--shadow-lg);
                    max-width: 92vw;
                }
                .mu-modal-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
                .mu-modal-title { font-size: 1.1rem; font-weight: 700; color: var(--text); }
                .mu-label { display: block; font-size: 0.78rem; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; }
                .mu-select {
                    width: 100%; padding: 10px 12px; border-radius: var(--radius-md);
                    border: 1px solid var(--border); background: var(--bg-card); color: var(--text);
                    font-size: 0.85rem; font-family: inherit;
                }
                .mu-dropzone {
                    position: relative; border: 1.5px dashed var(--border); border-radius: var(--radius-lg);
                    padding: 28px; text-align: center; cursor: pointer; transition: border-color 0.15s;
                    background: var(--bg-subtle);
                }
                .mu-dropzone:hover { border-color: var(--brand); }
                .mu-stat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
                .mu-stat {
                    background: var(--bg-subtle); border: 1px solid var(--border-light);
                    border-radius: var(--radius-md); padding: 12px 8px; text-align: center;
                }
                .mu-stat span { display: block; font-size: 0.7rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 4px; }
                .mu-stat strong { font-size: 1.1rem; font-weight: 700; color: var(--text); }

                .mu-tenant-card {
                    display: flex; align-items: center; gap: 12px; padding: 14px;
                    border: 1px solid var(--border); border-radius: var(--radius-lg);
                    background: var(--bg-card); cursor: pointer; transition: all 0.15s; width: 100%;
                }
                .mu-tenant-card:hover { border-color: var(--brand); background: var(--bg-hover); }
                .mu-tenant-card.active { border-color: var(--brand); background: var(--brand-light); }
                .mu-tenant-ico {
                    width: 36px; height: 36px; flex-shrink: 0; border-radius: var(--radius-md);
                    display: inline-flex; align-items: center; justify-content: center;
                    background: var(--bg-subtle); color: var(--text-secondary);
                }
            `}</style>
        </div>
    );
};

/* ───────────────────────── Sub-components ───────────────────────── */

const UnderConstruction = ({ title }) => (
    <div style={{
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
        textAlign: 'center', padding: '60px 24px', minHeight: 320,
        background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)',
    }}>
        <div style={{
            width: 56, height: 56, borderRadius: 'var(--radius-lg)', display: 'flex',
            alignItems: 'center', justifyContent: 'center', background: 'var(--bg-subtle)', marginBottom: 16,
        }}>
            <LayoutDashboard size={26} style={{ color: 'var(--text-muted)' }} />
        </div>
        <h3 style={{ fontSize: '1.05rem', fontWeight: 700, color: 'var(--text)', marginBottom: 6 }}>{title}</h3>
        <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', maxWidth: 360 }}>
            This section is coming soon. The data model is ready — the workspace view is being built.
        </p>
    </div>
);

const StatusPill = ({ status }) => {
    const active = status === 'ACTIVE';
    return (
        <span style={{
            padding: '4px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 700,
            background: active ? 'var(--success-bg)' : 'var(--danger-bg)',
            color: active ? 'var(--success-text)' : 'var(--danger-text)',
            border: `1px solid ${active ? 'var(--success)' : 'var(--danger)'}22`,
        }}>{status}</span>
    );
};

const Panel = ({ title, count, children }) => (
    <div style={{
        background: 'var(--bg-card)', border: '1px solid var(--border)',
        borderRadius: 'var(--radius-lg)', padding: 20, boxShadow: 'var(--shadow-card)',
    }}>
        <h3 style={{
            fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)', marginBottom: 14,
            paddingBottom: 10, borderBottom: '1px solid var(--border-light)',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        }}>
            <span>{title}</span>
            {count != null && (
                <span style={{
                    fontSize: '0.72rem', fontWeight: 700, color: 'var(--text-secondary)',
                    background: 'var(--bg-subtle)', borderRadius: 999, padding: '2px 9px',
                }}>{count}</span>
            )}
        </h3>
        {children}
    </div>
);

const MerchantProfileView = ({ merchant, onBack }) => {
    const [data, setData] = useState(null);
    const [salesHistory, setSalesHistory] = useState([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchData = async () => {
            try {
                // The assignment history is supplementary — a failure there (an older
                // backend, say) must not blank out the whole profile.
                const [profileRes, historyRes] = await Promise.allSettled([
                    api.get(`/merchants/${merchant.merchantId}/360`),
                    api.get(`/merchants/${merchant.merchantId}/assignment-history`),
                ]);
                if (profileRes.status === 'fulfilled') setData(profileRes.value.data);
                else console.error(profileRes.reason);
                if (historyRes.status === 'fulfilled') setSalesHistory(historyRes.value.data || []);
            } finally { setLoading(false); }
        };
        fetchData();
    }, [merchant]);

    if (loading) return <div style={{ padding: 24, color: 'var(--text-secondary)' }}>Loading profile…</div>;
    if (!data) return <div style={{ padding: 24, color: 'var(--text-secondary)' }}>Error loading profile.</div>;

    const { merchant: m, stores, contacts, documents, riskProfile } = data;

    return (
        <div>
            <button
                onClick={onBack}
                className="mu-btn-ghost"
                style={{ marginBottom: 16 }}
            >
                <ChevronLeft size={15} /> Back to list
            </button>

            {/* Header card */}
            <div style={{
                background: 'var(--bg-card)', border: '1px solid var(--border)',
                borderRadius: 'var(--radius-lg)', padding: 24, marginBottom: 20, boxShadow: 'var(--shadow-card)',
            }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, flexWrap: 'wrap' }}>
                    <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
                        <span style={{
                            width: 48, height: 48, borderRadius: 'var(--radius-lg)', flexShrink: 0,
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            background: 'var(--brand-light)', color: 'var(--brand)',
                        }}>
                            <Store size={22} />
                        </span>
                        <div>
                            <h2 style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--text)', marginBottom: 4, letterSpacing: '-0.02em' }}>{m.name}</h2>
                            <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                                MID: <span style={{ fontFamily: 'ui-monospace, monospace' }}>{m.mid}</span>
                                <span style={{ margin: '0 8px', color: 'var(--border)' }}>|</span>
                                Internal ID: <span style={{ fontFamily: 'ui-monospace, monospace' }}>{m.internalId}</span>
                            </div>
                        </div>
                    </div>
                    <StatusPill status={m.status} />
                </div>
            </div>

            {/* Grid */}
            <div className="mu-profile-grid">
                <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
                    <Panel title="Stores" count={stores ? stores.length : 0}>
                        {(!stores || stores.length === 0) && <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No stores on record.</p>}
                        {stores && stores.map(store => (
                            <div key={store.storeId} style={{
                                marginBottom: 12, padding: 14, border: '1px solid var(--border)',
                                borderRadius: 'var(--radius-md)', background: 'var(--bg-subtle)',
                            }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', fontWeight: 600, color: 'var(--text)' }}>
                                    <span>{store.name}</span>
                                    <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)', fontFamily: 'ui-monospace, monospace' }}>{store.sid}</span>
                                </div>
                                <div style={{ fontSize: '0.82rem', color: 'var(--text-secondary)', marginTop: 5 }}>{store.city}, {store.state}</div>
                            </div>
                        ))}
                    </Panel>

                    <Panel title="Terminals" count={data.terminals ? data.terminals.length : 0}>
                        {(!data.terminals || data.terminals.length === 0) && <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No terminals on record.</p>}
                        {data.terminals && data.terminals.map(terminal => {
                            const store = stores.find(s => s.storeId === terminal.storeId);
                            return (
                                <div key={terminal.terminalId} style={{
                                    marginBottom: 8, padding: 12, background: 'var(--bg-subtle)',
                                    borderRadius: 'var(--radius-md)', fontSize: '0.85rem', border: '1px solid var(--border-light)',
                                }}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', fontWeight: 600, marginBottom: 4, color: 'var(--text)' }}>
                                        <span style={{ fontFamily: 'ui-monospace, monospace' }}>TID: {terminal.tid}</span>
                                        <StatusPill status={terminal.status} />
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
                                        <span>Dev: {terminal.deviceNumber}</span>
                                        <span>{terminal.type}</span>
                                    </div>
                                    <div style={{ fontSize: '0.76rem', color: 'var(--text-muted)', marginTop: 4 }}>
                                        Store: {store ? store.name : 'Unknown'}
                                    </div>
                                </div>
                            );
                        })}
                    </Panel>

                    <Panel title="Contacts" count={contacts ? contacts.length : 0}>
                        {(!contacts || contacts.length === 0) ? (
                            <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No contacts on record.</p>
                        ) : (
                            <table style={{ width: '100%', fontSize: '0.85rem', borderCollapse: 'collapse' }}>
                                <thead>
                                    <tr style={{ color: 'var(--text-muted)', textAlign: 'left', fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                                        <th style={{ paddingBottom: 8 }}>Name</th><th>Role</th><th>Email</th><th>Phone</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {contacts.map(c => (
                                        <tr key={c.contactId} style={{ borderTop: '1px solid var(--border-light)', color: 'var(--text)' }}>
                                            <td style={{ padding: '9px 0' }}>{c.contactName}</td>
                                            <td style={{ color: 'var(--text-secondary)' }}>{c.role}</td>
                                            <td style={{ color: 'var(--text-secondary)' }}>{c.email}</td>
                                            <td style={{ color: 'var(--text-secondary)' }}>{c.phone}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        )}
                    </Panel>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
                    <Panel title="Sales Agent">
                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.85rem', marginBottom: 4 }}>
                            <span style={{ color: 'var(--text-secondary)' }}>Current agent</span>
                            <strong style={{ color: 'var(--text)' }}>{m.salesUserId || '—'}</strong>
                        </div>
                        {m.salesEmail && (
                            <div style={{ fontSize: '0.76rem', color: 'var(--text-muted)', textAlign: 'right', marginBottom: 10 }}>
                                {m.salesEmail}
                            </div>
                        )}

                        <div style={{
                            fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.04em',
                            color: 'var(--text-muted)', marginTop: 14, marginBottom: 8,
                            paddingTop: 12, borderTop: '1px solid var(--border-light)',
                        }}>
                            Assignment history
                        </div>

                        {salesHistory.length === 0 ? (
                            <p style={{ color: 'var(--text-muted)', fontSize: '0.82rem', margin: 0 }}>
                                This merchant has never changed sales agent.
                            </p>
                        ) : salesHistory.map(h => (
                            <div key={h.historyId} style={{
                                padding: '10px 12px', marginBottom: 8, borderRadius: 'var(--radius-md)',
                                background: 'var(--bg-subtle)', border: '1px solid var(--border-light)',
                            }}>
                                <div style={{ fontSize: '0.84rem', color: 'var(--text)', fontWeight: 600 }}>
                                    {h.oldSalesUserId || 'Unassigned'} → {h.newSalesUserId}
                                </div>
                                <div style={{ fontSize: '0.74rem', color: 'var(--text-muted)', marginTop: 4 }}>
                                    {h.changedAt ? String(h.changedAt).replace('T', ' ').slice(0, 19) : '—'}
                                    {' · '}{h.source}
                                    {h.uploadFileName ? ` · ${h.uploadFileName}` : ''}
                                </div>
                                <div style={{ fontSize: '0.74rem', color: 'var(--text-muted)' }}>
                                    by {h.changedBy || 'unknown'}
                                </div>
                            </div>
                        ))}
                    </Panel>

                    <Panel title="Risk Profile">
                        {riskProfile ? (
                            <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
                                {[
                                    ['Risk Score', riskProfile.riskScore],
                                    ['KYC Status', riskProfile.kycStatus],
                                    ['Last Review', riskProfile.lastReviewDate],
                                ].map(([label, val]) => (
                                    <div key={label} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.85rem' }}>
                                        <span style={{ color: 'var(--text-secondary)' }}>{label}</span>
                                        <strong style={{ color: 'var(--text)' }}>{val || '—'}</strong>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No risk profile data found.</p>
                        )}
                    </Panel>

                    <Panel title="Documents" count={documents ? documents.length : 0}>
                        {(!documents || documents.length === 0) && <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No documents uploaded.</p>}
                        {documents && documents.map(d => (
                            <div key={d.documentId} style={{ display: 'flex', alignItems: 'center', gap: 11, marginBottom: 11 }}>
                                <span style={{
                                    width: 32, height: 32, flexShrink: 0, borderRadius: 'var(--radius-sm)',
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                    background: 'var(--bg-subtle)', color: 'var(--text-secondary)',
                                }}>
                                    <FileText size={15} />
                                </span>
                                <div style={{ overflow: 'hidden' }}>
                                    <div style={{ fontSize: '0.85rem', color: 'var(--text)', textOverflow: 'ellipsis', whiteSpace: 'nowrap', overflow: 'hidden' }}>{d.documentName}</div>
                                    <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>{d.documentType}</div>
                                </div>
                            </div>
                        ))}
                    </Panel>
                </div>
            </div>

            <style>{`
                .mu-profile-grid { display: grid; grid-template-columns: 2fr 1fr; gap: 20px; }
                @media (max-width: 900px) { .mu-profile-grid { grid-template-columns: 1fr; } }
            `}</style>
        </div>
    );
};

export default MerchantUniverse;
