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
import SalesTrends from './pages/business/SalesTrends';
import MerchantLifecycle from './pages/business/MerchantLifecycle';
import ZeroSalesDrop from './pages/business/ZeroSalesDrop';
import OpportunityIntelligence from './pages/business/OpportunityIntelligence';
import FinanceDashboard from './pages/finance/FinanceDashboard';
import ProfitabilityAnalysis from './pages/finance/ProfitabilityAnalysis';
import FinanceLists from './pages/finance/FinanceLists';
import VolumeRevenueSummary from './pages/business/VolumeRevenueSummary';
import MerchantFinancialSummary from './pages/business/MerchantFinancialSummary';
import TransactionPerformanceDashboard from './pages/business/TransactionPerformanceDashboard';
import DebitPrepaidMetrics from './pages/business/DebitPrepaidMetrics';
import AttritionReport from './pages/business/AttritionReport';
import ZeroTransactionReport from './pages/business/ZeroTransactionReport';
import ExecutiveDashboardReport from './pages/business/ExecutiveDashboardReport';

import UserManagement from './pages/UserManagement';
import TenantManagement from './pages/TenantManagement';
import RbacGroups from './pages/RbacGroups';
import BatchMonitoring from './pages/BatchMonitoring';
import GroupReports from './pages/GroupReports';
import FinanceSummary from './pages/finance/FinanceSummary';
import MerchantInsightHub from './pages/reports/MerchantInsightHub';
import TransactionTrendsHub from './pages/reports/TransactionTrendsHub';

import SalesAnalytics from './pages/sales/SalesAnalytics';
import ZeroSales from './pages/sales/ZeroSales';

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
        <Route path="/business/trends" element={<SalesTrends />} />
        <Route path="/business/lifecycle" element={<MerchantLifecycle />} />
        <Route path="/business/zero-sales" element={<ZeroSalesDrop />} />
        <Route path="/business/opportunity" element={<OpportunityIntelligence />} />
        <Route path="/business/groups" element={<GroupReports />} />
        <Route path="/business/volume-revenue" element={<VolumeRevenueSummary />} />
        <Route path="/business/merchant-financial" element={<MerchantFinancialSummary />} />
        <Route path="/business/performance" element={<TransactionPerformanceDashboard />} />
        <Route path="/business/debit-prepaid" element={<DebitPrepaidMetrics />} />
        <Route path="/business/debit-prepaid" element={<DebitPrepaidMetrics />} />
        <Route path="/business/attrition" element={<AttritionReport />} />
        <Route path="/business/zero-transaction" element={<ZeroTransactionReport />} />
        <Route path="/business/executive-dashboard-v2" element={<ExecutiveDashboardReport />} />

        {/* Sales Routes */}
        <Route path="/sales/analytics" element={<SalesAnalytics />} />
        <Route path="/sales/zero-sales" element={<ZeroSales />} />

        {/* Finance Routes */}
        <Route path="/finance/dashboard" element={<FinanceDashboard />} />
        <Route path="/finance/profitability" element={<ProfitabilityAnalysis />} />
        <Route path="/finance/lists" element={<FinanceLists />} />
        <Route path="/finance/summary" element={<FinanceSummary />} />

        {/* Admin & Operations Routes */}
        <Route path="/users" element={<UserManagement />} />
        <Route path="/tenants" element={<TenantManagement />} />
        <Route path="/admin/groups" element={<RbacGroups />} />
        <Route path="/ops/batch-logs" element={<BatchMonitoring />} />
      </Route>
    </Routes>
  );
}

export default App;
