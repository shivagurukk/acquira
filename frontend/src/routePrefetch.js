/**
 * routePrefetch — warm a route's lazy chunk before the user clicks.
 *
 * THE PROBLEM THIS SOLVES
 * -----------------------
 * Every page in App.jsx is React.lazy()'d, so the FIRST navigation to a route
 * pays a network round-trip to fetch that route's JS chunk before anything
 * renders — a visible stall on click, especially on the heavier chart pages.
 *
 * WHAT THIS DOES
 * --------------
 * prefetchRoute(path) fires the SAME dynamic import() App.jsx uses for that
 * route. Calling it on sidebar hover means the chunk is already in the browser
 * cache by the time the user clicks, so the lazy boundary resolves instantly
 * instead of waiting on the network.
 *
 * It is intentionally a thin, isolated module:
 *   - the import thunks below are the exact specifiers from App.jsx (keep in
 *     sync when routes are added — a missing entry just means "no prefetch",
 *     never a crash);
 *   - each thunk is fired at most once (results memoised by the module loader
 *     anyway, plus a Set guard to avoid re-invoking on repeated hovers);
 *   - errors are swallowed — a failed prefetch must never surface to the user;
 *     the real navigation will retry and show the normal loader/error path.
 *
 * This does not change routing, guards, or how pages mount — it only changes
 * WHEN their code is downloaded.
 */

// Route path -> dynamic import thunk. Mirror of the lazy() specifiers in App.jsx.
const ROUTE_IMPORTS = {
    '/dashboard': () => import('./pages/Dashboard'),
    '/executive/daily-merchant': () => import('./pages/executive/DailyMerchantDashboard'),

    '/merchants': () => import('./components/MerchantHierarchy'),
    '/transactions': () => import('./components/TransactionList'),
    '/merchant-summary': () => import('./components/MerchantSummary'),
    '/merchant/insight-hub': () => import('./pages/reports/MerchantInsightHub'),
    '/trends/hub': () => import('./pages/reports/TransactionTrendsHub'),

    '/business/dashboard': () => import('./pages/business/BusinessDashboard'),
    '/business/volume-revenue': () => import('./pages/business/VolumeRevenueSummary'),
    '/business/merchant-financial': () => import('./pages/business/MerchantFinancialSummary'),
    '/business/performance': () => import('./pages/business/TransactionPerformanceDashboard'),
    '/business/debit-prepaid': () => import('./pages/business/DebitPrepaidMetrics'),
    '/business/attrition': () => import('./pages/business/AttritionReport'),
    '/business/zero-transaction': () => import('./pages/business/ZeroTransactionReport'),
    '/business/heatmap': () => import('./pages/business/MerchantHeatmap'),
    '/business/daily-dashboard': () => import('./pages/business/DailyMerchantDashboard'),
    '/business/merchant-analytics': () => import('./pages/business/MerchantAnalyticsReport'),
    '/business/comparison': () => import('./pages/business/MerchantComparison'),
    '/business/opportunity': () => import('./pages/business/OpportunityIntelligence'),
    '/business/groups': () => import('./pages/GroupReports'),
    '/explorer': () => import('./pages/analytics/DataExplorer'),
    '/analytics/interactive': () => import('./pages/analytics/InteractiveExplorer'),
    '/ai-assistant': () => import('./pages/ai/AiAssistant'),

    '/sales/team-management': () => import('./pages/sales/SalesTeamManagement'),
    '/sales/country-management': () => import('./pages/sales/SalesCountryLeadManagement'),
    '/sales/agents': () => import('./pages/sales/SalesAgentDirectory'),
    '/sales/leaderboard': () => import('./pages/sales/SalesLeaderboard'),
    '/sales/hierarchy': () => import('./pages/sales/SalesHierarchyTree'),
    '/sales/targets': () => import('./pages/sales/SalesTargetManagement'),

    '/executive/sales': () => import('./pages/executive/ExecutiveSalesPulse'),

    '/finance/dashboard': () => import('./pages/finance/FinanceDashboard'),
    '/finance/summary': () => import('./pages/finance/FinanceSummary'),
    '/finance/lists': () => import('./pages/finance/FinanceLists'),

    '/business/report-manager': () => import('./pages/business/MerchantReportManager'),
    '/upload': () => import('./pages/UploadPage'),
    '/ops/server-file': () => import('./pages/ServerFileProcessor'),
    '/ops/batch-logs': () => import('./pages/BatchMonitoring'),
    '/ops/ingest-trust': () => import('./pages/ops/IngestTrust'),
    '/ops/scheme-billing-reference': () => import('./pages/ops/SchemeBillingReference'),
    '/business/emails': () => import('./pages/StatementEmails'),
    '/business/revenue-leakage': () => import('./pages/business/RevenueLeakage'),

    '/users': () => import('./pages/UserManagement'),
    '/tenants': () => import('./pages/TenantManagement'),
    '/admin/groups': () => import('./pages/RbacGroups'),
    '/admin/smtp-settings': () => import('./pages/SmtpSettings'),
    '/admin/s3-settings': () => import('./pages/admin/S3Settings'),
    '/admin/audit-logs': () => import('./pages/admin/AuditLogViewer'),
    '/admin/backups': () => import('./pages/BackupRestore'),
    '/admin/integration': () => import('./pages/admin/IntegrationHub'),
    '/admin/integration/connections': () => import('./pages/admin/IntegrationHub'),
    '/admin/integration/reports': () => import('./pages/admin/IntegrationHub'),
    '/admin/integration/schedules': () => import('./pages/admin/IntegrationHub'),
    '/admin/integration/runs': () => import('./pages/admin/IntegrationHub'),
    '/admin/sso-settings': () => import('./pages/admin/SsoSettings'),
    '/admin/email-campaigns': () => import('./pages/admin/EmailCampaignHub'),
    '/admin/data-migration': () => import('./pages/admin/DataMigration'),
    '/admin/security-settings': () => import('./pages/admin/SecuritySettings'),
    '/admin/maintenance': () => import('./pages/admin/DatabaseMaintenance'),
    '/admin/bin-management': () => import('./pages/admin/BinManagement'),
    '/admin/alerts': () => import('./pages/admin/AlertsNotifications'),
    '/admin/api-management': () => import('./pages/admin/ApiManagement'),
};

const PREFETCHED = new Set();

/**
 * Warm the chunk for a route path. Safe to call repeatedly and on unknown
 * paths — unknown paths and failures are silently ignored.
 */
export function prefetchRoute(path) {
    if (!path || PREFETCHED.has(path)) return;
    const thunk = ROUTE_IMPORTS[path];
    if (!thunk) return;
    PREFETCHED.add(path);
    try {
        const p = thunk();
        if (p && typeof p.catch === 'function') p.catch(() => { PREFETCHED.delete(path); });
    } catch {
        PREFETCHED.delete(path);
    }
}

/**
 * Prefetch a small set of likely-next routes during browser idle time.
 * Called once after login/first paint to warm the common landing pages
 * without competing with the initial render.
 */
export function prefetchCommonRoutes(paths = ['/dashboard', '/business/dashboard', '/finance/dashboard']) {
    const run = () => paths.forEach(prefetchRoute);
    if (typeof window !== 'undefined' && 'requestIdleCallback' in window) {
        window.requestIdleCallback(run, { timeout: 3000 });
    } else {
        setTimeout(run, 1500);
    }
}

export default prefetchRoute;
