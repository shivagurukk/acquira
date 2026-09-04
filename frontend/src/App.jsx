import React, { Suspense } from 'react';
import { lazyWithReload as lazy } from './lazyWithReload';
import { Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import { ThemeProvider } from './contexts/ThemeContext';
import { ToastProvider } from './contexts/ToastContext';
import { LoadingProvider } from './contexts/LoadingContext';
import { ConfirmProvider } from './components/ui';
import Layout from './components/Layout';
import LoginPage from './pages/LoginPage';
import ProtectedRoute from './components/ProtectedRoute';
import { RoleGuard } from './components/ProtectedRoute';
import { PageLoader } from './components/Loaders';


// Lazy-loaded route components — only loaded when user navigates to them
const Dashboard = lazy(() => import('./pages/Dashboard'));
const CeoVolumeRevenue = lazy(() => import('./pages/CeoVolumeRevenue'));
const LossMakingMerchants = lazy(() => import('./pages/LossMakingMerchants'));
const UploadPage = lazy(() => import('./pages/UploadPage'));
const MerchantHierarchy = lazy(() => import('./components/MerchantHierarchy'));
const TransactionList = lazy(() => import('./components/TransactionList'));
const MerchantSummary = lazy(() => import('./components/MerchantSummary'));
const MerchantUniverse = lazy(() => import('./pages/MerchantUniverse'));
const BusinessDashboard = lazy(() => import('./pages/business/BusinessDashboard'));
const OpportunityIntelligence = lazy(() => import('./pages/business/OpportunityIntelligence'));
const RevenueLeakage = lazy(() => import('./pages/business/RevenueLeakage'));
const FinanceDashboard = lazy(() => import('./pages/finance/FinanceDashboard'));
const FinanceLists = lazy(() => import('./pages/finance/FinanceLists'));
const VolumeRevenueSummary = lazy(() => import('./pages/business/VolumeRevenueSummary'));
const MerchantFinancialSummary = lazy(() => import('./pages/business/MerchantFinancialSummary'));
const DebitPrepaidMetrics = lazy(() => import('./pages/business/DebitPrepaidMetrics'));
const AttritionReport = lazy(() => import('./pages/business/AttritionReport'));
const RetentionReport = lazy(() => import('./pages/business/RetentionReport'));
const ForecastingBenchmarking = lazy(() => import('./pages/business/ForecastingBenchmarking'));
const TopPerformers = lazy(() => import('./pages/business/TopPerformers'));
const ZeroTransactionReport = lazy(() => import('./pages/business/ZeroTransactionReport'));
const MerchantReportManager = lazy(() => import('./pages/business/MerchantReportManager'));
const MerchantHeatmap = lazy(() => import('./pages/business/MerchantHeatmap'));
const DailyMerchantDashboard = lazy(() => import('./pages/business/DailyMerchantDashboard'));
const DestinationDashboard = lazy(() => import('./pages/business/DestinationDashboard'));
const CardTypeDashboard = lazy(() => import('./pages/business/CardTypeDashboard'));
const LocalDebitBankDashboard = lazy(() => import('./pages/business/LocalDebitBankDashboard'));
const RentalOverview = lazy(() => import('./pages/business/RentalOverview'));
const MerchantAnalyticsReport = lazy(() => import('./pages/business/MerchantAnalyticsReport'));
const MerchantComparison = lazy(() => import('./pages/business/MerchantComparison'));
const PricingSimulator = lazy(() => import('./pages/business/PricingSimulator'));
const SalesTeamManagement = lazy(() => import('./pages/sales/SalesTeamManagement'));
const SalesCountryLeadManagement = lazy(() => import('./pages/sales/SalesCountryLeadManagement'));
const SalesAgentDirectory = lazy(() => import('./pages/sales/SalesAgentDirectory'));
const SalesLeaderboard = lazy(() => import('./pages/sales/SalesLeaderboard'));
const SalesHierarchyTree = lazy(() => import('./pages/sales/SalesHierarchyTree'));
const SalesExecutiveDashboard = lazy(() => import('./pages/sales/SalesExecutiveDashboard'));
const ExecutiveSalesPulse = lazy(() => import('./pages/executive/ExecutiveSalesPulse'));
// Executive daily view (distinct from pages/business/DailyMerchantDashboard, the month heat-grid)
const ExecutiveDailyMerchant = lazy(() => import('./pages/executive/DailyMerchantDashboard'));
// Net Spread — replica of the executive daily page at merchant grain, plus DCC + rental legs
const NetSpreadDashboard = lazy(() => import('./pages/executive/NetSpreadDashboard'));
const SalesTargetManagement = lazy(() => import('./pages/sales/SalesTargetManagement'));
const UserManagement = lazy(() => import('./pages/UserManagement'));
const TenantManagement = lazy(() => import('./pages/TenantManagement'));
const RbacGroups = lazy(() => import('./pages/RbacGroups'));
const BatchMonitoring = lazy(() => import('./pages/BatchMonitoring'));
const IngestTrust = lazy(() => import('./pages/ops/IngestTrust'));
const SchemeBillingReference = lazy(() => import('./pages/ops/SchemeBillingReference'));
const GroupReports = lazy(() => import('./pages/GroupReports'));
const FinanceSummary = lazy(() => import('./pages/finance/FinanceSummary'));
const MerchantInsightHub = lazy(() => import('./pages/reports/MerchantInsightHub'));
const TransactionTrendsHub = lazy(() => import('./pages/reports/TransactionTrendsHub'));
const BackupRestore = lazy(() => import('./pages/BackupRestore'));
const DataExplorer = lazy(() => import('./pages/analytics/DataExplorer'));
const InteractiveExplorer = lazy(() => import('./pages/analytics/InteractiveExplorer'));
const AiAssistant = lazy(() => import('./pages/ai/AiAssistant'));
const StatementEmails = lazy(() => import('./pages/StatementEmails'));
const ServerFileProcessor = lazy(() => import('./pages/ServerFileProcessor'));
const SmtpSettings = lazy(() => import('./pages/SmtpSettings'));
const AuditLogViewer = lazy(() => import('./pages/admin/AuditLogViewer'));
const IntegrationHub = lazy(() => import('./pages/admin/IntegrationHub'));
const EmailCampaignHub = lazy(() => import('./pages/admin/EmailCampaignHub'));
const SsoSettings = lazy(() => import('./pages/admin/SsoSettings'));
const DataMigration = lazy(() => import('./pages/admin/DataMigration'));
const TenantProvisioning = lazy(() => import('./pages/admin/TenantProvisioning'));
const SecuritySettings = lazy(() => import('./pages/admin/SecuritySettings'));
const DatabaseMaintenance = lazy(() => import('./pages/admin/DatabaseMaintenance'));
const BinManagement = lazy(() => import('./pages/admin/BinManagement'));
const AlertsNotifications = lazy(() => import('./pages/admin/AlertsNotifications'));
const ApiManagement = lazy(() => import('./pages/admin/ApiManagement'));
const ChangePasswordPage = lazy(() => import('./pages/ChangePasswordPage'));
const SettingsHub = lazy(() => import('./pages/SettingsHub'));
// S3 report storage settings
const S3Settings = lazy(() => import('./pages/admin/S3Settings'));
const BudgetTargets = lazy(() => import('./pages/admin/BudgetTargets'));
const InterchangeNormalization = lazy(() => import('./pages/admin/InterchangeNormalization'));

function App() {
  return (
    // ErrorBoundary is provided as the outermost wrapper in main.jsx.
    <ThemeProvider>
      <LoadingProvider>
      <ToastProvider>
      <ConfirmProvider>
      <AuthProvider>
        <Suspense fallback={<PageLoader />}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/auth/sso/callback" element={<LoginPage />} />
            <Route path="/" element={<Navigate to="/dashboard" replace />} />

            {/* Change Password — protected but NO Layout sidebar */}
            <Route path="/change-password" element={
              <ProtectedRoute><ChangePasswordPage /></ProtectedRoute>
            } />

            {/* All protected routes — wrapped in Layout with sidebar */}
            <Route element={<ProtectedRoute><Layout /></ProtectedRoute>}>
              {/* Executive */}
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/business/ceo-volume-revenue" element={<CeoVolumeRevenue />} />
              <Route path="/business/loss-making" element={<LossMakingMerchants />} />
              <Route path="/executive/sales" element={<ExecutiveSalesPulse />} />
              <Route path="/executive/daily-merchant" element={<ExecutiveDailyMerchant />} />
              <Route path="/executive/net-spread" element={<NetSpreadDashboard />} />

              {/* Merchant MGT */}
              <Route path="/merchants" element={<MerchantHierarchy />} />
              <Route path="/transactions" element={<TransactionList />} />
              <Route path="/merchant-summary" element={<MerchantSummary />} />
              <Route path="/merchant/universe" element={<MerchantUniverse />} />
              <Route path="/merchant/insight-hub" element={<MerchantInsightHub />} />
              <Route path="/trends/hub" element={<TransactionTrendsHub />} />

              {/* Business */}
              <Route path="/business/dashboard" element={<BusinessDashboard />} />
              <Route path="/business/volume-revenue" element={<VolumeRevenueSummary />} />
              <Route path="/business/merchant-financial" element={<MerchantFinancialSummary />} />
              <Route path="/business/debit-prepaid" element={<DebitPrepaidMetrics />} />
              <Route path="/business/attrition" element={<AttritionReport />} />
              <Route path="/business/retention" element={<RetentionReport />} />
              <Route path="/business/forecasting" element={<ForecastingBenchmarking />} />
              <Route path="/business/top-performers" element={<TopPerformers />} />
              <Route path="/business/zero-transaction" element={<ZeroTransactionReport />} />
              <Route path="/business/heatmap" element={<MerchantHeatmap />} />
              <Route path="/business/daily-dashboard" element={<DailyMerchantDashboard />} />
              <Route path="/business/destination-dashboard" element={<DestinationDashboard />} />
              <Route path="/business/card-type-dashboard" element={<CardTypeDashboard />} />
              <Route path="/business/local-debit-bank-dashboard" element={<LocalDebitBankDashboard />} />
              <Route path="/business/rentals" element={<RentalOverview />} />
              <Route path="/business/merchant-analytics" element={<MerchantAnalyticsReport />} />
              <Route path="/business/comparison" element={<MerchantComparison />} />
              <Route path="/business/pricing-simulator" element={<PricingSimulator />} />
              <Route path="/business/opportunity" element={<OpportunityIntelligence />} />
              <Route path="/business/groups" element={<GroupReports />} />
              <Route path="/explorer" element={<DataExplorer />} />
              <Route path="/analytics/interactive" element={<InteractiveExplorer />} />
              <Route path="/ai-assistant" element={<AiAssistant />} />

              {/* Sales */}
              <Route path="/sales/executive" element={<SalesExecutiveDashboard />} />
              <Route path="/sales/team-management" element={<SalesTeamManagement />} />
              <Route path="/sales/country-management" element={<SalesCountryLeadManagement />} />
              <Route path="/sales/agents" element={<SalesAgentDirectory />} />
              <Route path="/sales/leaderboard" element={<SalesLeaderboard />} />
              <Route path="/sales/hierarchy" element={<SalesHierarchyTree />} />
              <Route path="/sales/targets" element={<SalesTargetManagement />} />

              {/* Finance */}
              <Route path="/finance/dashboard" element={<FinanceDashboard />} />
              <Route path="/finance/summary" element={<FinanceSummary />} />
              <Route path="/finance/lists" element={<FinanceLists />} />

              {/* Operations */}
              <Route path="/business/report-manager" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><MerchantReportManager /></RoleGuard>
              } />
              <Route path="/upload" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><UploadPage /></RoleGuard>
              } />
              <Route path="/ops/server-file" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><ServerFileProcessor /></RoleGuard>
              } />
              <Route path="/ops/batch-logs" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><BatchMonitoring /></RoleGuard>
              } />
              <Route path="/ops/ingest-trust" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><IngestTrust /></RoleGuard>
              } />
              <Route path="/ops/scheme-billing-reference" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><SchemeBillingReference /></RoleGuard>
              } />
              <Route path="/business/emails" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><StatementEmails /></RoleGuard>
              } />
              <Route path="/business/revenue-leakage" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><RevenueLeakage /></RoleGuard>
              } />
              {/* Unified Settings hub — Bank Admin gets everything; Super
                  Admin can also view. No super-admin-only gating. */}
              <Route path="/settings" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><SettingsHub /></RoleGuard>
              } />
              <Route path="/settings/:section" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><SettingsHub /></RoleGuard>
              } />

              {/* Administration */}
              <Route path="/users" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><UserManagement /></RoleGuard>
              } />
              {/* SA-only: tenant create/update (/api/banks POST/PUT) is guarded
                  hasRole('SUPER_ADMIN') server-side — a Bank Admin would only get 403s. */}
              <Route path="/tenants" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><TenantManagement /></RoleGuard>
              } />
              <Route path="/admin/groups" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><RbacGroups /></RoleGuard>
              } />
              <Route path="/admin/smtp-settings" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><SmtpSettings /></RoleGuard>
              } />
              <Route path="/admin/s3-settings" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><S3Settings /></RoleGuard>
              } />
              <Route path="/admin/audit-logs" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><AuditLogViewer /></RoleGuard>
              } />
              {/* SA-only: BackupController is class-level hasRole('SUPER_ADMIN'). */}
              <Route path="/admin/backups" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><BackupRestore /></RoleGuard>
              } />
              <Route path="/admin/integration" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><IntegrationHub /></RoleGuard>
              } />
              <Route path="/admin/integration/connections" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><IntegrationHub defaultTab="connections" /></RoleGuard>
              } />
              <Route path="/admin/integration/reports" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><IntegrationHub defaultTab="reports" /></RoleGuard>
              } />
              <Route path="/admin/integration/schedules" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><IntegrationHub defaultTab="schedules" /></RoleGuard>
              } />
              <Route path="/admin/integration/runs" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><IntegrationHub defaultTab="runs" /></RoleGuard>
              } />
              <Route path="/admin/sso-settings" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><SsoSettings /></RoleGuard>
              } />
              <Route path="/admin/email-campaigns" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><EmailCampaignHub /></RoleGuard>
              } />
              {/* SA-only: migration start/dry-run/delete-day are hasRole('SUPER_ADMIN'). */}
              <Route path="/admin/data-migration" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><DataMigration /></RoleGuard>
              } />
              {/* SA-only: provisioning scripts execute arbitrary SQL — platform-level. */}
              <Route path="/admin/tenant-provisioning" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><TenantProvisioning /></RoleGuard>
              } />
              <Route path="/admin/security-settings" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><SecuritySettings /></RoleGuard>
              } />
              <Route path="/admin/maintenance" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><DatabaseMaintenance /></RoleGuard>
              } />
              {/* SA-only: BIN reference data is platform-wide, shared by every tenant. */}
              <Route path="/admin/bin-management" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><BinManagement /></RoleGuard>
              } />
              <Route path="/admin/alerts" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><AlertsNotifications /></RoleGuard>
              } />
              <Route path="/admin/api-management" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><ApiManagement /></RoleGuard>
              } />
              <Route path="/business/budget-targets" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><BudgetTargets /></RoleGuard>
              } />
              {/* SA-only: apply overwrites fact-level interchange fees for a month. */}
              <Route path="/admin/interchange-normalization" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><InterchangeNormalization /></RoleGuard>
              } />
            </Route>
          </Routes>
        </Suspense>
      </AuthProvider>
      </ConfirmProvider>
      </ToastProvider>
      </LoadingProvider>
    </ThemeProvider>
  );
}

export default App;
