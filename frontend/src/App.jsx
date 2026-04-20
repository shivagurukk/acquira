import React, { Suspense, lazy } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import { ThemeProvider } from './contexts/ThemeContext';
import ErrorBoundary from './components/ErrorBoundary';
import Layout from './components/Layout';
import LoginPage from './pages/LoginPage';
import ProtectedRoute from './components/ProtectedRoute';
import { RoleGuard } from './components/ProtectedRoute';

// #11: Shared loading spinner for lazy-loaded routes
const PageLoader = () => (
  <div style={{ display:'flex', alignItems:'center', justifyContent:'center', minHeight:'60vh' }}>
    <div style={{ textAlign:'center' }}>
      <div style={{ width:40, height:40, border:'3px solid #E5E7EB', borderTopColor:'#1E3A8A', borderRadius:'50%', animation:'spin 0.8s linear infinite', margin:'0 auto 12px' }} />
      <div style={{ fontSize:13, color:'#6B7280', fontFamily:'Inter, sans-serif' }}>Loading...</div>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  </div>
);

// #24: Lazy-loaded route components — only loaded when user navigates to them
const Dashboard = lazy(() => import('./pages/Dashboard'));
const UploadPage = lazy(() => import('./pages/UploadPage'));
const MerchantHierarchy = lazy(() => import('./components/MerchantHierarchy'));
const TransactionList = lazy(() => import('./components/TransactionList'));
const MerchantSummary = lazy(() => import('./components/MerchantSummary'));
const BusinessDashboard = lazy(() => import('./pages/business/BusinessDashboard'));
const OpportunityIntelligence = lazy(() => import('./pages/business/OpportunityIntelligence'));
const FinanceDashboard = lazy(() => import('./pages/finance/FinanceDashboard'));
const FinanceLists = lazy(() => import('./pages/finance/FinanceLists'));
const VolumeRevenueSummary = lazy(() => import('./pages/business/VolumeRevenueSummary'));
const MerchantFinancialSummary = lazy(() => import('./pages/business/MerchantFinancialSummary'));
const TransactionPerformanceDashboard = lazy(() => import('./pages/business/TransactionPerformanceDashboard'));
const DebitPrepaidMetrics = lazy(() => import('./pages/business/DebitPrepaidMetrics'));
const AttritionReport = lazy(() => import('./pages/business/AttritionReport'));
const ZeroTransactionReport = lazy(() => import('./pages/business/ZeroTransactionReport'));
const ExecutiveDashboardReport = lazy(() => import('./pages/business/ExecutiveDashboardReport'));
const MerchantReportManager = lazy(() => import('./pages/business/MerchantReportManager'));
const MerchantHeatmap = lazy(() => import('./pages/business/MerchantHeatmap'));
const DailyMerchantDashboard = lazy(() => import('./pages/business/DailyMerchantDashboard'));
const MerchantAnalyticsReport = lazy(() => import('./pages/business/MerchantAnalyticsReport'));
const MerchantComparison = lazy(() => import('./pages/business/MerchantComparison'));
const SalesTeamManagement = lazy(() => import('./pages/sales/SalesTeamManagement'));
const SalesLeaderboard = lazy(() => import('./pages/sales/SalesLeaderboard'));
const UserManagement = lazy(() => import('./pages/UserManagement'));
const TenantManagement = lazy(() => import('./pages/TenantManagement'));
const RbacGroups = lazy(() => import('./pages/RbacGroups'));
const BatchMonitoring = lazy(() => import('./pages/BatchMonitoring'));
const GroupReports = lazy(() => import('./pages/GroupReports'));
const FinanceSummary = lazy(() => import('./pages/finance/FinanceSummary'));
const MerchantInsightHub = lazy(() => import('./pages/reports/MerchantInsightHub'));
const TransactionTrendsHub = lazy(() => import('./pages/reports/TransactionTrendsHub'));
const BackupRestore = lazy(() => import('./pages/BackupRestore'));
const DataExplorer = lazy(() => import('./pages/analytics/DataExplorer'));
const AiAssistant = lazy(() => import('./pages/ai/AiAssistant'));
const StatementEmails = lazy(() => import('./pages/StatementEmails'));
const ServerFileProcessor = lazy(() => import('./pages/ServerFileProcessor'));
const SmtpSettings = lazy(() => import('./pages/SmtpSettings'));
const AuditLogViewer = lazy(() => import('./pages/admin/AuditLogViewer'));
const IntegrationHub = lazy(() => import('./pages/admin/IntegrationHub'));
const EmailCampaignHub = lazy(() => import('./pages/admin/EmailCampaignHub'));
const SsoSettings = lazy(() => import('./pages/admin/SsoSettings'));
const DataMigration = lazy(() => import('./pages/admin/DataMigration'));
const SecuritySettings = lazy(() => import('./pages/admin/SecuritySettings'));
const AlertsNotifications = lazy(() => import('./pages/admin/AlertsNotifications'));
const ApiManagement = lazy(() => import('./pages/admin/ApiManagement'));
const ChangePasswordPage = lazy(() => import('./pages/ChangePasswordPage'));
// S3 report storage settings
const S3Settings = lazy(() => import('./pages/admin/S3Settings'));

function App() {
  return (
    // #10: ErrorBoundary wraps entire app — catches unhandled errors
    <ErrorBoundary>
      <ThemeProvider>
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
              <Route path="/business/executive-dashboard-v2" element={<ExecutiveDashboardReport />} />

              {/* Merchant MGT */}
              <Route path="/merchants" element={<MerchantHierarchy />} />
              <Route path="/transactions" element={<TransactionList />} />
              <Route path="/merchant-summary" element={<MerchantSummary />} />
              <Route path="/merchant/insight-hub" element={<MerchantInsightHub />} />
              <Route path="/trends/hub" element={<TransactionTrendsHub />} />

              {/* Business */}
              <Route path="/business/dashboard" element={<BusinessDashboard />} />
              <Route path="/business/volume-revenue" element={<VolumeRevenueSummary />} />
              <Route path="/business/merchant-financial" element={<MerchantFinancialSummary />} />
              <Route path="/business/performance" element={<TransactionPerformanceDashboard />} />
              <Route path="/business/debit-prepaid" element={<DebitPrepaidMetrics />} />
              <Route path="/business/attrition" element={<AttritionReport />} />
              <Route path="/business/zero-transaction" element={<ZeroTransactionReport />} />
              <Route path="/business/heatmap" element={<MerchantHeatmap />} />
              <Route path="/business/daily-dashboard" element={<DailyMerchantDashboard />} />
              <Route path="/business/merchant-analytics" element={<MerchantAnalyticsReport />} />
              <Route path="/business/comparison" element={<MerchantComparison />} />
              <Route path="/business/opportunity" element={<OpportunityIntelligence />} />
              <Route path="/business/groups" element={<GroupReports />} />
              <Route path="/explorer" element={<DataExplorer />} />
              <Route path="/ai-assistant" element={<AiAssistant />} />

              {/* Sales */}
              <Route path="/sales/team-management" element={<SalesTeamManagement />} />
              <Route path="/sales/leaderboard" element={<SalesLeaderboard />} />

              {/* Finance */}
              <Route path="/finance/dashboard" element={<FinanceDashboard />} />
              <Route path="/finance/summary" element={<FinanceSummary />} />
              <Route path="/finance/lists" element={<FinanceLists />} />

              {/* Operations */}
              <Route path="/business/report-manager" element={<MerchantReportManager />} />
              <Route path="/upload" element={<UploadPage />} />
              <Route path="/ops/server-file" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><ServerFileProcessor /></RoleGuard>
              } />
              <Route path="/ops/batch-logs" element={<BatchMonitoring />} />
              <Route path="/business/emails" element={<StatementEmails />} />

              {/* Administration */}
              <Route path="/users" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><UserManagement /></RoleGuard>
              } />
              <Route path="/tenants" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><TenantManagement /></RoleGuard>
              } />
              <Route path="/admin/groups" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><RbacGroups /></RoleGuard>
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
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><SsoSettings /></RoleGuard>
              } />
              <Route path="/admin/email-campaigns" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><EmailCampaignHub /></RoleGuard>
              } />
              <Route path="/admin/data-migration" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}><DataMigration /></RoleGuard>
              } />
              <Route path="/admin/security-settings" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><SecuritySettings /></RoleGuard>
              } />
              <Route path="/admin/alerts" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><AlertsNotifications /></RoleGuard>
              } />
              <Route path="/admin/api-management" element={
                <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}><ApiManagement /></RoleGuard>
              } />
            </Route>
          </Routes>
        </Suspense>
      </AuthProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
}

export default App;
