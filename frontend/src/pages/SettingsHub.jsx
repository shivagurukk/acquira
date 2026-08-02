import React, { Suspense, lazy, useMemo, useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import {
    Search, Lock, Building2, Palette, Shield, KeyRound, Mail,
    Bell, Server, Database, Plug, Users, HardDrive, Cpu, FileClock,
    ChevronRight, SlidersHorizontal, Settings as SettingsIcon
} from 'lucide-react';
import { Page, Button, Input, Alert } from '../components/ui';

// ============================================================================
// Settings Hub — one consolidated home for every configurable knob.
//
// This is a SHELL: it lazy-loads the existing settings pages into a right-hand
// panel and presents them under a searchable, grouped left nav. Nothing is
// rewritten — each panel is the page that already works, mounted here.
//
// Access model (revised 2026-07-11): both Bank Admin (ROLE_ADMIN) and Super
// Admin (ROLE_SUPER_ADMIN) reach the hub, but sections whose BACKEND is
// super-admin-only (`superAdminOnly: true`) are hidden from Bank Admins —
// showing them would just render dead buttons that 403 on every call
// (BackupController is class-level SA; migration start/dry-run/delete-day are
// SA; tenant create/update via /banks is SA). The route itself is guarded to
// the two roles in App.jsx; the underlying controllers keep enforcing their
// own role + tenant scoping regardless of what the UI shows.
// ============================================================================

// ── Existing pages, lazy-loaded as panels ──────────────────────────────────
const ChangePasswordPanel = lazy(() => import('./ChangePasswordPage'));
const SecuritySettings     = lazy(() => import('./admin/SecuritySettings'));
const SsoSettings          = lazy(() => import('./admin/SsoSettings'));
const SmtpSettings         = lazy(() => import('./SmtpSettings'));
const EmailCampaignHub     = lazy(() => import('./admin/EmailCampaignHub'));
const AlertsNotifications  = lazy(() => import('./admin/AlertsNotifications'));
const S3Settings           = lazy(() => import('./admin/S3Settings'));
const IntegrationHub       = lazy(() => import('./admin/IntegrationHub'));
const ApiManagement        = lazy(() => import('./admin/ApiManagement'));
const TenantManagement     = lazy(() => import('./TenantManagement'));
const RbacGroups           = lazy(() => import('./RbacGroups'));
const DatabaseMaintenance  = lazy(() => import('./admin/DatabaseMaintenance'));
const BackupRestore        = lazy(() => import('./BackupRestore'));
const DataMigration        = lazy(() => import('./admin/DataMigration'));
const AuditLogViewer       = lazy(() => import('./admin/AuditLogViewer'));
const UserManagement       = lazy(() => import('./UserManagement'));
const RegionalSettings     = lazy(() => import('./admin/RegionalSettings'));

// Placeholder panel for sections whose backing store exists but has no UI yet,
// or that are planned. Keeps the nav honest without pretending to be wired.
const ComingSoon = ({ title, note }) => (
    <Page title={title} width="narrow">
        <Alert tone="info">{note}</Alert>
    </Page>
);

// ── Section catalog. Order defines the nav. `el` renders in the right panel. ─
// `superAdminOnly: true` hides an item from Bank Admins (backend is SA-only).
const GROUPS = [
    {
        group: 'My Account',
        items: [
            { key: 'password',   label: 'Password',            icon: Lock,   keywords: 'change password credentials', el: <ChangePasswordPanel embedded /> },
            { key: 'preferences',label: 'Preferences',         icon: Palette,keywords: 'theme dark light landing density format',
              el: <ComingSoon title="Preferences" note="Theme, default landing page, table density and number/date format. These are mostly client-side preferences — wiring pending. Toggle dark mode from the top bar for now." /> },
        ],
    },
    {
        group: 'Organization',
        items: [
            { key: 'regional',     label: 'Regional & Data',         icon: Palette,   keywords: 'date format timezone locale region load mode replace append upload',
              el: <RegionalSettings /> },
            { key: 'bank-profile', label: 'Bank Profile & Branding', icon: Building2, keywords: 'bank name logo brand currency region tenant',
              el: <ComingSoon title="Bank Profile & Branding" note="Bank name, short code, logo, primary color, currency and region. Managed today under Platform → Tenants (Banks) — a lighter branding-only editor for the current bank is planned here." /> },
            { key: 'dashboard',    label: 'Dashboard Configuration', icon: SlidersHorizontal, keywords: 'kpi tiles visibility order dashboard',
              el: <ComingSoon title="Dashboard Configuration" note="Per-dashboard KPI-tile visibility and ordering. The backing store (dashboard_config) and endpoints exist; a dedicated editor is on the roadmap." /> },
        ],
    },
    {
        group: 'Security & Access',
        items: [
            { key: 'security',        label: 'Password & Login Policy', icon: Shield,  keywords: 'password policy lockout session token mfa rate limit',
              el: <SecuritySettings /> },
            { key: 'sso',             label: 'Single Sign-On (SSO)',    icon: KeyRound,keywords: 'sso microsoft entra azure oauth', el: <SsoSettings /> },
            { key: 'users',           label: 'Users & Access',          icon: Users,   keywords: 'users access requests approve roles assign', el: <UserManagement /> },
        ],
    },
    {
        group: 'Communications',
        items: [
            { key: 'smtp',      label: 'Email Server (SMTP)', icon: Server, keywords: 'smtp email server mail outbound', el: <SmtpSettings /> },
            { key: 'templates', label: 'Email Templates',     icon: Mail,   keywords: 'template statement welcome alert campaign', el: <EmailCampaignHub /> },
            { key: 'alerts',    label: 'Alerts & Notifications', icon: Bell, keywords: 'alert threshold notification rule', el: <AlertsNotifications /> },
        ],
    },
    {
        group: 'Data & Integrations',
        items: [
            { key: 's3',           label: 'Report Storage (S3)',   icon: HardDrive, keywords: 's3 bucket storage archive report', el: <S3Settings /> },
            { key: 'integrations', label: 'External Integrations', icon: Plug,      keywords: 'integration oracle sql server connection schedule', el: <IntegrationHub /> },
            { key: 'api-keys',     label: 'API Keys',              icon: KeyRound,  keywords: 'api key external x-api-key token', el: <ApiManagement /> },
            { key: 'leakage',      label: 'Revenue-Leakage Rules', icon: SlidersHorizontal, keywords: 'leakage threshold detection anomaly',
              el: <ComingSoon title="Revenue-Leakage Thresholds" note="Detection thresholds (the leakage.* keys) currently live in tenant_setting and are SQL-only. A form to manage them here is planned." /> },
        ],
    },
    {
        group: 'Platform',
        items: [
            { key: 'tenants',     label: 'Tenants (Banks)',    icon: Building2, keywords: 'tenant bank create configure', el: <TenantManagement embedded />, superAdminOnly: true },
            { key: 'rbac',        label: 'Roles & Menus (RBAC)', icon: Users,   keywords: 'rbac group role menu permission', el: <RbacGroups /> },
            { key: 'maintenance', label: 'Database Maintenance', icon: Database, keywords: 'maintenance vacuum analyze nightly window', el: <DatabaseMaintenance /> },
            { key: 'backups',     label: 'Backup & Restore',   icon: HardDrive, keywords: 'backup restore dump database', el: <BackupRestore />, superAdminOnly: true },
            { key: 'migration',   label: 'Data Migration',     icon: Server,    keywords: 'migration legacy import day correction', el: <DataMigration />, superAdminOnly: true },
            { key: 'ai',          label: 'AI Assistant',       icon: Cpu,       keywords: 'ai ollama anthropic openai gemini model provider',
              el: <ComingSoon title="AI Assistant Provider" note="Provider (Ollama / Anthropic / OpenAI / Gemini), model and query guardrails are set via application.properties today. A gated UI to switch provider without a redeploy is planned." /> },
            { key: 'audit',       label: 'Audit Log',          icon: FileClock, keywords: 'audit log trail history who changed', el: <AuditLogViewer /> },
        ],
    },
];

const SettingsHub = () => {
    const navigate = useNavigate();
    const { section } = useParams();
    const { userRole } = useAuth();
    const [query, setQuery] = useState('');

    const isSuperAdmin = userRole === 'ROLE_SUPER_ADMIN';

    // Role-filtered catalog: Bank Admins never see SA-only sections — not in
    // the nav, not via deep link (`active` resolves against this list too, so
    // /settings/backups falls back to the first visible section for them).
    const visibleGroups = useMemo(
        () => GROUPS
            .map(g => ({ ...g, items: g.items.filter(it => isSuperAdmin || !it.superAdminOnly) }))
            .filter(g => g.items.length > 0),
        [isSuperAdmin]
    );
    const flat = useMemo(
        () => visibleGroups.flatMap(g => g.items.map(it => ({ ...it, group: g.group }))),
        [visibleGroups]
    );

    const active = useMemo(
        () => flat.find(i => i.key === section) || flat[0],
        [section, flat]
    );

    // Keep the URL in sync so panels are deep-linkable / bookmarkable.
    useEffect(() => {
        if (!section) navigate(`/settings/${flat[0].key}`, { replace: true });
    }, [section, navigate, flat]);

    const q = query.trim().toLowerCase();
    const groupsToShow = visibleGroups
        .map(g => ({
            ...g,
            items: g.items.filter(it =>
                !q || it.label.toLowerCase().includes(q) || (it.keywords || '').includes(q)
            ),
        }))
        .filter(g => g.items.length > 0);

    return (
        <div style={{ display: 'flex', height: 'calc(100vh - 52px)', background: 'var(--bg)', color: 'var(--text)' }}>
            {/* ── Left settings nav ─────────────────────────────────────── */}
            <aside style={{
                width: 280, flexShrink: 0, borderRight: '1px solid var(--border)',
                background: 'var(--bg-card)', display: 'flex', flexDirection: 'column', overflow: 'hidden',
            }}>
                <div style={{ padding: 'var(--space-xl) var(--space-lg) var(--space-md)' }}>
                    <div className="ui-row" style={{ gap: 10, marginBottom: 'var(--space-lg)', flexWrap: 'nowrap' }}>
                        <div className="ui-page__icon">
                            <SettingsIcon size={18} strokeWidth={1.9} />
                        </div>
                        <div style={{ minWidth: 0 }}>
                            <div style={{ fontSize: '0.95rem', fontWeight: 700 }}>Settings</div>
                            <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)' }}>
                                {userRole === 'ROLE_SUPER_ADMIN' ? 'Super Admin' : 'Bank Admin'} · this bank
                            </div>
                        </div>
                    </div>
                    <div className="ui-table-search" style={{ width: '100%' }}>
                        <Search size={14} />
                        <Input
                            type="search"
                            value={query}
                            onChange={e => setQuery(e.target.value)}
                            placeholder="Find a setting…"
                            aria-label="Find a setting"
                            style={{ width: '100%' }}
                        />
                    </div>
                </div>

                <nav style={{ flex: 1, overflowY: 'auto', padding: '4px 10px var(--space-xl)' }}>
                    {groupsToShow.map(g => (
                        <div key={g.group} style={{ marginBottom: 10 }}>
                            <div style={{
                                fontSize: '0.7rem', fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase',
                                color: 'var(--text-secondary)', padding: '8px 10px 4px',
                            }}>{g.group}</div>
                            {g.items.map(it => {
                                const Icon = it.icon;
                                const isActive = active.key === it.key;
                                return (
                                    <Button
                                        key={it.key}
                                        block
                                        variant={isActive ? 'subtle' : 'ghost'}
                                        icon={Icon}
                                        iconRight={isActive ? ChevronRight : undefined}
                                        onClick={() => navigate(`/settings/${it.key}`)}
                                        aria-current={isActive ? 'page' : undefined}
                                        style={{ justifyContent: 'flex-start', marginBottom: 2 }}
                                    >
                                        <span style={{ flex: 1, textAlign: 'left' }}>{it.label}</span>
                                    </Button>
                                );
                            })}
                        </div>
                    ))}
                    {groupsToShow.length === 0 && (
                        <div style={{ padding: 'var(--space-xl) var(--space-md)', color: 'var(--text-secondary)', fontSize: '0.8rem' }}>
                            No settings match “{query}”.
                        </div>
                    )}
                </nav>
            </aside>

            {/* ── Right content panel ───────────────────────────────────── */}
            <main style={{ flex: 1, overflowY: 'auto', minWidth: 0 }}>
                <Suspense fallback={
                    <div style={{ padding: 'var(--space-4xl)', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                        Loading {active.label}…
                    </div>
                }>
                    {/* key forces a clean remount when switching sections so each
                        panel re-runs its own fetch effects. */}
                    <div key={active.key}>{active.el}</div>
                </Suspense>
            </main>
        </div>
    );
};

export default SettingsHub;
