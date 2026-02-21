import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import Layout from './components/Layout';
import LoginPage from './pages/LoginPage';
import Dashboard from './pages/Dashboard';
import UploadPage from './pages/UploadPage';
import MerchantHierarchy from './components/MerchantHierarchy';
import TransactionList from './components/TransactionList';
import MerchantSummary from './components/MerchantSummary';
import BusinessDashboard from './pages/business/BusinessDashboard';
import OpportunityIntelligence from './pages/business/OpportunityIntelligence';
import FinanceDashboard from './pages/finance/FinanceDashboard';
import FinanceLists from './pages/finance/FinanceLists';
import VolumeRevenueSummary from './pages/business/VolumeRevenueSummary';
import MerchantFinancialSummary from './pages/business/MerchantFinancialSummary';
import TransactionPerformanceDashboard from './pages/business/TransactionPerformanceDashboard';
import DebitPrepaidMetrics from './pages/business/DebitPrepaidMetrics';
import AttritionReport from './pages/business/AttritionReport';
import ZeroTransactionReport from './pages/business/ZeroTransactionReport';
import ExecutiveDashboardReport from './pages/business/ExecutiveDashboardReport';
// MerchantInsights removed — consolidated into MerchantInsightHub
import MerchantReportManager from './pages/business/MerchantReportManager';
import MerchantHeatmap from './pages/business/MerchantHeatmap';
import DailyMerchantDashboard from './pages/business/DailyMerchantDashboard';
import MerchantAnalyticsReport from './pages/business/MerchantAnalyticsReport';
import MerchantComparison from './pages/business/MerchantComparison';
import SalesTeamManagement from './pages/sales/SalesTeamManagement';
import SalesLeaderboard from './pages/sales/SalesLeaderboard';

import UserManagement from './pages/UserManagement';
import TenantManagement from './pages/TenantManagement';
import RbacGroups from './pages/RbacGroups';
import BatchMonitoring from './pages/BatchMonitoring';
import GroupReports from './pages/GroupReports';
import FinanceSummary from './pages/finance/FinanceSummary';
import MerchantInsightHub from './pages/reports/MerchantInsightHub';
import TransactionTrendsHub from './pages/reports/TransactionTrendsHub';
import BackupRestore from './pages/BackupRestore';
import DataExplorer from './pages/analytics/DataExplorer';
import AiAssistant from './pages/ai/AiAssistant';
import StatementEmails from './pages/StatementEmails';
import ServerFileProcessor from './pages/ServerFileProcessor';
import SmtpSettings from './pages/SmtpSettings';
import AuditLogViewer from './pages/admin/AuditLogViewer';
import IntegrationHub from './pages/admin/IntegrationHub';
import EmailCampaignHub from './pages/admin/EmailCampaignHub';
import SsoSettings from './pages/admin/SsoSettings';
import DataMigration from './pages/admin/DataMigration';

import ChangePasswordPage from './pages/ChangePasswordPage';
import ProtectedRoute from './components/ProtectedRoute';
import { RoleGuard } from './components/ProtectedRoute';

function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/auth/sso/callback" element={<LoginPage />} />
        <Route path="/" element={<Navigate to="/dashboard" replace />} />

        {/* Change Password — protected but NO Layout sidebar (standalone page) */}
        <Route path="/change-password" element={
          <ProtectedRoute>
            <ChangePasswordPage />
          </ProtectedRoute>
        } />

        {/* All protected routes — wrapped in Layout with sidebar */}
        {/* ONE ProtectedRoute here validates session, all child routes are instant */}
        <Route element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }>
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
          {/* /business/insights removed — use /merchant/insight-hub instead */}
          <Route path="/business/comparison" element={<MerchantComparison />} />
          <Route path="/business/report-manager" element={<MerchantReportManager />} />
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
          <Route path="/upload" element={<UploadPage />} />
          <Route path="/ops/server-file" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <ServerFileProcessor />
            </RoleGuard>
          } />
          <Route path="/ops/batch-logs" element={<BatchMonitoring />} />
          <Route path="/business/emails" element={<StatementEmails />} />

          {/* Administration — RoleGuard only checks role (no session re-validation) */}
          <Route path="/users" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <UserManagement />
            </RoleGuard>
          } />
          <Route path="/tenants" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <TenantManagement />
            </RoleGuard>
          } />
          <Route path="/admin/groups" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}>
              <RbacGroups />
            </RoleGuard>
          } />
          <Route path="/admin/smtp-settings" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <SmtpSettings />
            </RoleGuard>
          } />
          <Route path="/admin/audit-logs" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <AuditLogViewer />
            </RoleGuard>
          } />
          <Route path="/admin/backups" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}>
              <BackupRestore />
            </RoleGuard>
          } />

          {/* Data Integration — single page with tabs */}
          <Route path="/admin/integration" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <IntegrationHub />
            </RoleGuard>
          } />
          <Route path="/admin/integration/connections" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <IntegrationHub defaultTab="connections" />
            </RoleGuard>
          } />
          <Route path="/admin/integration/reports" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <IntegrationHub defaultTab="reports" />
            </RoleGuard>
          } />
          <Route path="/admin/integration/schedules" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <IntegrationHub defaultTab="schedules" />
            </RoleGuard>
          } />
          <Route path="/admin/integration/runs" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <IntegrationHub defaultTab="runs" />
            </RoleGuard>
          } />

          {/* SSO Settings */}
          <Route path="/admin/sso-settings" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}>
              <SsoSettings />
            </RoleGuard>
          } />

          {/* Email Campaign Hub */}
          <Route path="/admin/email-campaigns" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
              <EmailCampaignHub />
            </RoleGuard>
          } />

          {/* Data Migration */}
          <Route path="/admin/data-migration" element={
            <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN']}>
              <DataMigration />
            </RoleGuard>
          } />
        </Route>
      </Routes>
    </AuthProvider>
  );
}

export default App;
