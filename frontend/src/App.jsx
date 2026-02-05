import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
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
import MerchantInsights from './pages/business/MerchantInsights';
import MerchantReportManager from './pages/business/MerchantReportManager';
import MerchantHeatmap from './pages/business/MerchantHeatmap';
import DailyMerchantDashboard from './pages/business/DailyMerchantDashboard';
import MerchantAnalyticsReport from './pages/business/MerchantAnalyticsReport';

import UserManagement from './pages/UserManagement';
import TenantManagement from './pages/TenantManagement';
import RbacGroups from './pages/RbacGroups';
import BatchMonitoring from './pages/BatchMonitoring';
import GroupReports from './pages/GroupReports';
import FinanceSummary from './pages/finance/FinanceSummary';
import MerchantInsightHub from './pages/reports/MerchantInsightHub';
import TransactionTrendsHub from './pages/reports/TransactionTrendsHub';
import BackupRestore from './pages/BackupRestore';

import ProtectedRoute from './components/ProtectedRoute';

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<Navigate to="/dashboard" replace />} />

      {/* Protected Routes */}
      <Route element={
        <ProtectedRoute>
          <Layout />
        </ProtectedRoute>
      }>
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/merchants" element={<MerchantHierarchy />} />
        <Route path="/transactions" element={<TransactionList />} />
        <Route path="/merchant-summary" element={<MerchantSummary />} />
        <Route path="/merchant/insight-hub" element={<MerchantInsightHub />} />
        <Route path="/trends/hub" element={<TransactionTrendsHub />} />
        <Route path="/upload" element={<UploadPage />} />

        {/* Business Universe Routes */}
        <Route path="/business/dashboard" element={<BusinessDashboard />} />
        <Route path="/business/insights" element={<MerchantInsights />} />
        <Route path="/business/report-manager" element={<MerchantReportManager />} />
        <Route path="/business/opportunity" element={<OpportunityIntelligence />} />
        <Route path="/business/groups" element={<GroupReports />} />
        <Route path="/business/volume-revenue" element={<VolumeRevenueSummary />} />
        <Route path="/business/merchant-financial" element={<MerchantFinancialSummary />} />
        <Route path="/business/performance" element={<TransactionPerformanceDashboard />} />
        <Route path="/business/debit-prepaid" element={<DebitPrepaidMetrics />} />
        <Route path="/business/attrition" element={<AttritionReport />} />
        <Route path="/business/zero-transaction" element={<ZeroTransactionReport />} />
        <Route path="/business/executive-dashboard-v2" element={<ExecutiveDashboardReport />} />
        <Route path="/business/heatmap" element={<MerchantHeatmap />} />
        <Route path="/business/daily-dashboard" element={<DailyMerchantDashboard />} />
        <Route path="/business/merchant-analytics" element={<MerchantAnalyticsReport />} />


        {/* Finance Routes */}
        <Route path="/finance/dashboard" element={<FinanceDashboard />} />
        <Route path="/finance/lists" element={<FinanceLists />} />
        <Route path="/finance/summary" element={<FinanceSummary />} />

        {/* Admin & Operations Routes */}
        <Route path="/users" element={<UserManagement />} />
        <Route path="/tenants" element={<TenantManagement />} />
        <Route path="/admin/groups" element={<RbacGroups />} />
        <Route path="/admin/backups" element={<BackupRestore />} />
        <Route path="/ops/batch-logs" element={<BatchMonitoring />} />
      </Route>
    </Routes>
  );
}

export default App;
