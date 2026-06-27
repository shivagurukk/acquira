# Acquira — Feature & Page Developer Guide

**Audience:** Developers working on the live multi-module app (`acquira-*`).
**Scope:** End-to-end reference for every page in the React app — its purpose, features, the REST endpoints it calls, and the database tables behind it. This is the companion to `ACQUIRA_DEVELOPER_GUIDE.md` (which covers infra, batch ingestion, schema, deploy, auth, and the 2026-05-08 audit). This guide picks up where that one stops: the dashboards, analytics, sales, finance, ops, and admin features built on top of the warehouse.
**Stack:** Java 21, Spring Boot 3.2 (multi-module: `acquira-common`, `acquira-batch`, `acquira-pdf`, `acquira-ai`, `acquira-core`), PostgreSQL, React 19 + Vite + MUI 7 + Tailwind 4 + Recharts. Backend listens on **8081**; frontend dev server proxies `/api/*` to it.

> **How to read this guide.** Sections 1–4 cover the cross-cutting frontend systems shared by every page. Section 5 is the page atlas, grouped by sidebar category — this is the bulk of the document. Section 6 is the flagship Analytics Explorer engine. Section 7 is the complete REST endpoint reference. Section 8 documents the tables introduced/used by these features. Section 9 captures the data-sourcing rules (which dashboard reads which summary table) and the tenant-isolation patterns enforced throughout. **Section 10 is the full backend reference: every remaining controller's exact endpoints, plus a complete column-level data dictionary for every table in `schema.sql`.**

---

## Table of Contents

1. [Module & Runtime Recap](#1-module--runtime-recap)
2. [Routing & Navigation Map](#2-routing--navigation-map)
3. [Cross-Cutting Frontend Systems](#3-cross-cutting-frontend-systems)
4. [The BusinessFilters Drawer & Saved Views](#4-the-businessfilters-drawer--saved-views)
5. [Page Atlas (end-to-end, by category)](#5-page-atlas-end-to-end-by-category)
   - [5.1 Executive](#51-executive)
   - [5.2 Merchant Management](#52-merchant-management)
   - [5.3 Business Analytics](#53-business-analytics)
   - [5.4 Sales](#54-sales)
   - [5.5 Finance](#55-finance)
   - [5.6 Operations](#56-operations)
   - [5.7 Administration](#57-administration)
   - [5.8 Data Integration](#58-data-integration)
6. [The Analytics Explorer Engine](#6-the-analytics-explorer-engine)
7. [Complete REST Endpoint Reference](#7-complete-rest-endpoint-reference)
8. [Database Tables Behind These Features](#8-database-tables-behind-these-features)
9. [Data-Sourcing Rules & Tenant-Isolation Patterns](#9-data-sourcing-rules--tenant-isolation-patterns)
10. [Full Backend Reference — Controllers & Complete Schema](#10-full-backend-reference--controllers--complete-schema)
    - [10.1 Remaining Controllers (exact endpoints)](#101-remaining-controllers-exact-endpoints)
    - [10.2 Complete Schema — Data Dictionary (every table, every column)](#102-complete-schema--data-dictionary-every-table-every-column)
    - [10.3 Table → Controller/Page Cross-Reference](#103-table--controllerpage-cross-reference)
    - [10.4 Row-Level Security, Partitioning & Seed Data](#104-row-level-security-partitioning--seed-data)

---

## 1. Module & Runtime Recap

Only `acquira-core` has a runnable `main()` (`CoreApplication`); it component-scans the other four packages, so the whole platform runs as one process. Controllers live across modules but share one Spring context:

| Module | Controllers it contributes | Base paths |
|---|---|---|
| `acquira-core` | ~42 REST controllers (dashboards, analytics, sales, finance, admin, auth, RBAC) | `/api/...` |
| `acquira-batch` | `FileUploadController`, `BatchJobController`, `BatchProgressController`, `MigrationController`, `BackfillController`, `BackupController`, `IntegrationController` | `/api/upload`, `/api/batch`, `/api/admin/migration`, `/api/admin/integration` |
| `acquira-pdf` | `PdfController` / `ExternalReportApiController` (overrides the `MerchantInsightController` stub when on classpath) | `/api/business/insights` |
| `acquira-ai` | `AiAssistantController` | `/api/ai` |
| `acquira-common` | No controllers — shared entities, repositories, DTOs, security, `TenantContext`, `TenantAspect` | — |

`TenantContext` (ThreadLocal) carries `currentTenant` (writes) and `visibleTenants` (cross-tenant reads for super-admins). Every controller below resolves the tenant via `TenantContext.getCurrentTenant()` or `tenantService.getCurrentTenantId()` and 403s when it is null. The frontend sends the active tenant in the `X-Tenant-Id` header on every request (see §3).

---

## 2. Routing & Navigation Map

Routes are defined in `frontend/src/App.jsx`. All pages are lazy-loaded and wrapped in `<ProtectedRoute><Layout/></ProtectedRoute>` (sidebar shell), except `/login`, `/auth/sso/callback`, and `/change-password`. Role-restricted routes use `<RoleGuard requiredRoles={[...]}>`.

The sidebar itself is **not** hardcoded — it is built from `GET /api/users/me/menus`, which reads `sys_group_menu × sys_menu` for the user's group. `MenuController` runs a startup safety net (`@PostConstruct ensureMenusExist`) that upserts every known menu row into `sys_menu` and grants them to Super Admin (and most to Bank Admin), so a fresh DB always has a working menu. Adding a screen = add the route here + add a `sys_menu` row + grant it via `sys_group_menu`.

| Category | Route | Component | Role guard |
|---|---|---|---|
| Executive | `/dashboard` | `Dashboard` | any |
| Executive | `/business/executive-dashboard-v2` | `ExecutiveDashboardReport` | any |
| Merchant | `/merchants` | `MerchantHierarchy` | any |
| Merchant | `/transactions` | `TransactionList` | any |
| Merchant | `/merchant-summary` | `MerchantSummary` | any |
| Merchant | `/merchant/insight-hub` | `MerchantInsightHub` | any |
| Merchant | `/trends/hub` | `TransactionTrendsHub` | any |
| Business | `/business/dashboard` | `BusinessDashboard` | any |
| Business | `/business/volume-revenue` | `VolumeRevenueSummary` | any |
| Business | `/business/merchant-financial` | `MerchantFinancialSummary` | any |
| Business | `/business/performance` | `TransactionPerformanceDashboard` | any |
| Business | `/business/debit-prepaid` | `DebitPrepaidMetrics` | any |
| Business | `/business/attrition` | `AttritionReport` | any |
| Business | `/business/zero-transaction` | `ZeroTransactionReport` | any |
| Business | `/business/heatmap` | `MerchantHeatmap` | any |
| Business | `/business/daily-dashboard` | `DailyMerchantDashboard` | any |
| Business | `/business/merchant-analytics` | `MerchantAnalyticsReport` | any |
| Business | `/business/comparison` | `MerchantComparison` | any |
| Business | `/business/opportunity` | `OpportunityIntelligence` | any |
| Business | `/business/groups` | `GroupReports` | any |
| Business | `/explorer` | `DataExplorer` | any |
| Business | `/analytics/interactive` | `InteractiveExplorer` | any |
| Business | `/ai-assistant` | `AiAssistant` | any |
| Sales | `/sales/team-management` | `SalesTeamManagement` | any |
| Sales | `/sales/country-management` | `SalesCountryLeadManagement` | any |
| Sales | `/sales/agents` | `SalesAgentDirectory` | any |
| Sales | `/sales/leaderboard` | `SalesLeaderboard` | any |
| Sales | `/sales/hierarchy` | `SalesHierarchyTree` | any |
| Finance | `/finance/dashboard` | `FinanceDashboard` | any |
| Finance | `/finance/summary` | `FinanceSummary` | any |
| Finance | `/finance/lists` | `FinanceLists` | any |
| Operations | `/business/report-manager` | `MerchantReportManager` | ADMIN, SUPER_ADMIN |
| Operations | `/upload` | `UploadPage` | ADMIN, SUPER_ADMIN |
| Operations | `/ops/server-file` | `ServerFileProcessor` | ADMIN, SUPER_ADMIN |
| Operations | `/ops/batch-logs` | `BatchMonitoring` | ADMIN, SUPER_ADMIN |
| Operations | `/business/emails` | `StatementEmails` | ADMIN, SUPER_ADMIN |
| Operations | `/business/revenue-leakage` | `RevenueLeakage` | ADMIN, SUPER_ADMIN |
| Administration | `/users` | `UserManagement` | ADMIN, SUPER_ADMIN |
| Administration | `/tenants` | `TenantManagement` | SUPER_ADMIN |
| Administration | `/admin/groups` | `RbacGroups` | SUPER_ADMIN |
| Administration | `/admin/smtp-settings` | `SmtpSettings` | ADMIN, SUPER_ADMIN |
| Administration | `/admin/s3-settings` | `S3Settings` | ADMIN, SUPER_ADMIN |
| Administration | `/admin/audit-logs` | `AuditLogViewer` | ADMIN, SUPER_ADMIN |
| Administration | `/admin/backups` | `BackupRestore` | SUPER_ADMIN |
| Administration | `/admin/sso-settings` | `SsoSettings` | SUPER_ADMIN |
| Administration | `/admin/email-campaigns` | `EmailCampaignHub` | ADMIN, SUPER_ADMIN |
| Administration | `/admin/data-migration` | `DataMigration` | SUPER_ADMIN |
| Administration | `/admin/security-settings` | `SecuritySettings` | ADMIN, SUPER_ADMIN |
| Administration | `/admin/maintenance` | `DatabaseMaintenance` | ADMIN, SUPER_ADMIN |
| Administration | `/admin/alerts` | `AlertsNotifications` | ADMIN, SUPER_ADMIN |
| Administration | `/admin/api-management` | `ApiManagement` | ADMIN, SUPER_ADMIN |
| Data Integration | `/admin/integration[/connections\|/reports\|/schedules\|/runs]` | `IntegrationHub` (tabbed) | ADMIN, SUPER_ADMIN |

---

## 3. Cross-Cutting Frontend Systems

These live in `frontend/src/contexts/` and `frontend/src/api/` and apply to **every** page.

**`AuthContext`** — holds the logged-in user, role, and tenant list; exposes the tenant switcher. On login it stores `token`, `refreshToken`, and `defaultTenantId` in `localStorage`. `ProtectedRoute` gates routes; `RoleGuard` enforces per-route roles.

**`ThemeContext`** — dark/light mode via CSS variables. All dashboards read colors from `ThemeContext` variables rather than hardcoding, so charts re-theme instantly.

**`ToastContext`** — global toast notifications. Exposes a module-level `showToast(message, severity, durationMs)` that even non-React code (the axios interceptor) calls.

**`LoadingContext`** — a global top progress bar. It exports module-level `startLoading()` / `stopLoading()`. The axios request interceptor calls `startLoading()` on every outgoing request and `stopLoading()` when it settles, so any in-flight API call shows the bar with zero per-page wiring.

**`api/axios.js`** — the single axios instance (`baseURL: '/api'`, `withCredentials: true`). The **request** interceptor attaches `Authorization: Bearer <token>` and `X-Tenant-Id: <defaultTenantId>`, and ticks the loader. The **response** interceptor:
- On `401` (and not already retrying), calls `POST /api/auth/refresh` once, queues concurrent requests behind the refresh, swaps in the new JWT, and replays them. If refresh fails or there's no refresh token, it clears auth storage, toasts "session expired", and redirects to `/login`.
- On `403`, it logs a warning but does **not** log out (the UI handles forbidden inline).
- The refresh token is primarily an HttpOnly cookie; the `localStorage` copy is a plain-HTTP dev fallback only.

**`components/Loaders.jsx`** — `PageLoader` (route-level Suspense fallback), `Spinner`, and `ContentLoader` (skeletons). Skeleton loaders are wired into the heavier dashboards (`ExecutiveDashboardReport`, `DailyMerchantDashboard`, `TransactionPerformanceDashboard`, `VolumeRevenueSummary`, `FinanceLists`).

**API client modules** (`frontend/src/api/`):
- `explorer.js` — `explorerApi` (fields/distinct/query/queryMerchants/associative + master-items + alerts), `reportApi` (excel/csv export, templates, schedules), `savedViewsApi` (filter views CRUD + set-default).
- `merchants.js` — `merchantApi.search` (hierarchy lookup) and `merchantApi.compare`.
- `ai.js` — `aiApi.health/models/ask/explain`.

---

## 4. The BusinessFilters Drawer & Saved Views

Most analytics pages share one **BusinessFilters** drawer. It posts a single `VolumeRevenueFilterDTO` to the page's `*-filtered` endpoint. The DTO fields (all optional, all tenant-scoped server-side):

`startDate`, `endDate`, `partnerList` (referral partner), `rmList` (`dim_merchant.sales_email`), `teamLeaderList` (resolved to `sales_user_id`s by `BusinessAnalyticsController.resolveFilters` via `SalesTeamService`), `merchantName` (ILIKE), `midList`, `sidList`, `mccList`, `schemeList`, `cardTypeList`, `destinationList`, `channelList`.

The drawer's dropdown values come from `ReportFilterController` (`/api/reports/filters/*`):

| Field | Endpoint | Source |
|---|---|---|
| MCC | `GET /api/reports/filters/mcc` | `dim_store.mcc` |
| RM | `GET /api/reports/filters/rm` | `dim_merchant.sales_email` |
| Merchants | `GET /api/reports/filters/merchants` | `dim_merchant` (limit 2000) |
| Stores | `GET /api/reports/filters/stores?mid=` | `dim_store` joined to `dim_merchant` |
| Terminals | `GET /api/reports/filters/terminals?sid=` | `dim_terminal` joined to `dim_store` |
| Partners | `GET /api/reports/filters/partners` | `dim_merchant.referral_partner` |
| Channels | `GET /api/reports/filters/channels` | `sum_daily_insight.channel` |

**Filter applicability differs per table.** Card-level filters (`scheme`/`cardType`/`destination`/`channel`) only work where the base table has those columns (`sum_daily_insight`). On `sum_daily_merchant`-backed reports (Daily Merchant, Group Reports) those filters are silently ignored — the drawer should disable them there.

**Saved Views** (`SavedFilterController`, base `/api/filters/views`) persist a filter set per `(user, tenant, dashboardType)` to `saved_filter`. Rules enforced server-side: name required, `filterJson` ≤ 10 KB, max 50 views per user/tenant, one default per dashboard type (setting a new default clears the old). Shared views are visible to the tenant; private views only to the owner (`findAccessibleViews`).

---

## 5. Page Atlas (end-to-end, by category)

Each entry lists: **Route**, **What it does**, **Key features**, **Backend** (controller → endpoints), **Tables**.

### 5.1 Executive

#### Landing Dashboard
- **Route:** `/dashboard` (`Dashboard.jsx`; an enhanced variant `Dashboard.enhanced.jsx` exists with sticky header + live badge, sparkline KPI cards, "At a glance" insight strip, donut center label, and a ranked merchant list).
- **What it does:** Top-line tenant snapshot — KPI tiles, scheme donut, ranked merchants.
- **Backend:** `BusinessController` → `GET /api/business/dashboard/kpis` (header `X-Tenant-Id`, optional `endDate`) and `POST /api/business/dashboard/kpis-filtered` (BusinessFilters body). KPIs: daily/MTD/YTD volume+count, active/dormant/new merchant counts. `AnalyticsController` → `POST /api/analytics/scheme-breakdown` for the donut.
- **Tables:** `sum_daily_bank` (unfiltered KPIs), `sum_daily_insight` (filtered KPIs + active-merchant count), `merchant_activity_summary` (active/dormant/onboarded counts), `sum_daily_scheme` (donut).

#### Executive Dashboard v2
- **Route:** `/business/executive-dashboard-v2` (`ExecutiveDashboardReport.jsx`). Enhanced with toggleable chart series (Volume / MSF / Transactions), a hover scrubber, Recharts `Brush` zoom, and click-to-pin donut slices; switched from `AreaChart` to `ComposedChart`.
- **Backend:** `ExecutiveDashboardController` → `GET /api/dashboard/v2/data?dataset=&asOfDate=` and `GET /api/dashboard/v2/datasets`. The repository runs 9 tenant-scoped queries to assemble the DTO. Also `BusinessAnalyticsController` → `POST /api/business/executive-metrics`.
- **Tables:** `sum_daily_bank`, `sum_monthly_bank` (and the dimension/summary tables the repository rolls up).

### 5.2 Merchant Management

#### Merchant Hierarchy
- **Route:** `/merchants` (`MerchantHierarchy.jsx`). Tree of Merchant → Store → Terminal.
- **Backend:** `MerchantController` → `GET /api/merchants/hierarchy?page=&size=&search=` (returns `{content:[{merchantId,name,mid,status,stores[...]}]}`), plus merchant detail/CRUD on `/api/merchants/*`.
- **Tables:** `dim_merchant`, `dim_store`, `dim_terminal`.

#### Transactions
- **Route:** `/transactions` (`TransactionList.jsx`). Paginated, filterable raw transaction browser.
- **Backend:** `TransactionController` → `GET /api/transactions?page=&size=&mid=&sid=&tid=&paymentDateFrom=&paymentDateTo=&transactionDateFrom=&transactionDateTo=` (JPA `Specification`, tenant-scoped, sorted by `paymentDate` desc). CSV export at `GET /api/transactions/export/csv` (ADMIN/SUPER_ADMIN; card numbers masked to last 4, max 100k rows).
- **Tables:** `fact_transaction`, with MID/SID/TID resolved through `dim_merchant`/`dim_store`/`dim_terminal`.

#### Merchant Summary
- **Route:** `/merchant-summary` (`MerchantSummary.jsx`). Per-merchant Daily/MTD/YTD volumes + credit vs debit/prepaid split.
- **Backend:** `AnalyticsController` → `GET /api/analytics/merchant-summaries?year=&month=&day=&page=&size=` and CSV `GET /api/analytics/merchant-summaries/export`. MTD/YTD subqueries are tenant-scoped inside the subquery (P2-1).
- **Tables:** `dim_merchant` LEFT JOIN `sum_daily_merchant` (daily + MTD + YTD aggregates).

#### Merchant Insight Hub
- **Route:** `/merchant/insight-hub` (`MerchantInsightHub.jsx`). Cross-tab merchant insight report with rich filters and pagination.
- **Backend:** `InsightController` → `POST /api/reports/insight/generate` (body = `InsightFilterRequest`: datePreset/dateFrom/dateTo, mcc, optStatus, rm, partner, mid/sid/tid, intlLocal, cardType, scheme, posEcom, page/size). Returns `{content,totalElements,totalPages}`.
- **Tables:** `sum_daily_insight` joined to `dim_merchant`/`dim_store`/`dim_terminal` (grouped by mid/name/sid/tid/mcc/destination/card_type/channel/sales_email/is_opt_in/business_date).
- **Per-merchant PDF insight** (Merchant Insights page / report): `GET /api/business/insights/overview?merchantId=&year=&month=` — served by `MerchantInsightController` (stub) when `acquira-pdf` is absent, or by the real `PdfController` when present (Playwright/Chromium HTML→PDF). The stub enforces tenant ownership of `merchantId` (IDOR fix).

#### Transaction Trends Hub
- **Route:** `/trends/hub` (`TransactionTrendsHub.jsx`). Trend exploration across schemes/channels over time.
- **Backend:** `AnalyticsController` / `TransactionController` (scheme breakdown + trend series). Uses `POST /api/analytics/scheme-breakdown` and the finance trends endpoints.
- **Tables:** `sum_daily_scheme`, `sum_daily_channel`, `sum_daily_bank`.

### 5.3 Business Analytics

All pages in this group post a `VolumeRevenueFilterDTO` and are backed by `BusinessAnalyticsController` (`/api/business/*`) which delegates to `VolumeRevenueRepository`. `resolveFilters()` translates team-leader names → `sales_user_id`s before querying.

#### Business Dashboard
- **Route:** `/business/dashboard` (`BusinessDashboard.jsx`).
- **Backend:** same KPI endpoints as the landing dashboard (`/api/business/dashboard/kpis`, `/kpis-filtered`) plus `GET /api/business/data-bounds`.
- **Tables:** `sum_daily_bank`, `sum_daily_insight`, `merchant_activity_summary`.

#### Volume / Revenue Summary
- **Route:** `/business/volume-revenue` (`VolumeRevenueSummary.jsx`).
- **Backend:** `POST /api/business/volume-revenue-summary`.
- **Tables:** summary tables via `VolumeRevenueRepository.getSummary`.

#### Merchant Financial Summary
- **Route:** `/business/merchant-financial` (`MerchantFinancialSummary.jsx`).
- **Backend:** `POST /api/business/merchant-financial-summary`.

#### Transaction Performance Dashboard
- **Route:** `/business/performance` (`TransactionPerformanceDashboard.jsx`). Drill-down Month → Day → Merchant.
- **Backend:** `POST /api/business/performance-dashboard?groupBy=&parentValue=&grandParentValue=` (`VolumeRevenueRepository.getPerformanceDashboardData`). Skeleton loader wired.

#### Debit / Prepaid Metrics
- **Route:** `/business/debit-prepaid` (`DebitPrepaidMetrics.jsx`).
- **Backend:** `POST /api/business/debit-prepaid-metrics`.

#### Attrition Report
- **Route:** `/business/attrition` (`AttritionReport.jsx`).
- **Backend:** `POST /api/business/attrition-report`.

#### Zero Transaction Report
- **Route:** `/business/zero-transaction` (`ZeroTransactionReport.jsx`). Merchants/terminals with no recent activity.
- **Backend:** `ZeroTransactionController` (`/api/reports/zero-txn`): `POST /list`, `POST /kpi`, `POST /summary`, `POST /page?rangeType=&status=&page=&size=` (status = ALL | IN30 | NEVER | IN7). Each takes the filter body + `rangeType` (default `LAST_30`).
- **Tables:** `sum_daily_terminal` joined `dim_terminal → dim_store → dim_merchant` (all tenant-scoped).

#### Merchant Heatmap
- **Route:** `/business/heatmap` (`MerchantHeatmap.jsx`). Month-by-merchant volume heat grid.
- **Backend:** `AnalyticsController` → `GET /api/analytics/heatmap?year=` and `POST /api/analytics/heatmap-filtered?year=` (filtered). Year list from `GET /api/analytics/available-years`. Fast path uses `sum_daily_merchant`; card-level filters switch it to `sum_daily_insight`.
- **Tables:** `sum_daily_merchant` (fast), `sum_daily_insight` (card-filtered), `dim_merchant`/`dim_store`.

#### Daily Merchant Dashboard
- **Route:** `/business/daily-dashboard` (`DailyMerchantDashboard.jsx`). Per-merchant per-day grid for a month, with Today / 7-day avg / trend% / status. Month selector has This Month / Last Month quick buttons, a month/year picker, and a "jump to latest available" banner (uses `data-bounds`).
- **Backend:** `DailyMerchantDashboardController` → `GET /api/business/daily-merchant-dashboard?year=&month=&midList=&sidList=&merchantName=` and `POST /api/business/daily-merchant-dashboard-filtered?year=&month=` (drawer body). Bounds from `BusinessAnalyticsController` → `GET /api/business/data-bounds` (queries `fact_transaction`, falls back to `sum_daily_insight`).
- **Tables:** `sum_daily_merchant` (source of truth — synchronous, batch-written, indexed; replaced the drop-prone async `merchant_daily_metrics`), with `dim_merchant`/`dim_store` for the filter whitelist. The DTO computes per-day map, MTD total, today, 7-day average, trend %, and a lightweight status heuristic on the fly.

#### Merchant Analytics Report
- **Route:** `/business/merchant-analytics` (`MerchantAnalyticsReport.jsx`). Paginated analytics grid.
- **Backend:** `POST /api/business/merchant-analytics?page=&size=`.

#### Merchant Comparison
- **Route:** `/business/comparison` (`MerchantComparison.jsx`). Side-by-side multi-merchant comparison.
- **Backend:** `merchantApi.compare` → `POST /api/merchants/compare` (body `{merchantIds,startDate,endDate}`); merchant search via `GET /api/merchants/hierarchy`.
- **Tables:** `dim_merchant` + `sum_daily_insight` (the compare query joins `dim_merchant` LEFT/INNER to `sum_daily_insight` for KPIs, monthly trend, scheme & card-type breakdowns; 2–10 merchants).

#### Opportunity Intelligence
- **Route:** `/business/opportunity` (`OpportunityIntelligence.jsx`). Per-merchant opportunity scores (0–100) with reason tags.
- **Backend:** `BusinessController` → `GET /api/business/opportunity` (uses `findLatestByTenant` — one latest row per merchant, not every historical snapshot). Falls back from header `X-Tenant-Id` to `TenantContext`.
- **Tables:** `merchant_opportunity_score` (recalculated by `calculateBusinessMetricsStep`).

#### Group Reports
- **Route:** `/business/groups` (`GroupReports.jsx`). Grouped rollups by MCC / Merchant / Sales / Referral.
- **Backend:** `GroupAnalyticsController` → `GET /api/group-analytics/{type}?period=&fromDate=&toDate=` and `POST /api/group-analytics/{type}/filtered` (drawer body). `type` ∈ {MCC, MERCHANT, SALES, REFERRAL}. The filtered variant adds joins on demand; card-level filters are no-ops here (base is `sum_daily_merchant`).
- **Tables:** `sum_daily_merchant` (+ `dim_merchant`/`dim_store`); MCC report joins `dim_store` on `merchant_id` (since `sum_daily_merchant.store_id` is always NULL); `sum_daily_mcc` for the legacy MCC path.

#### Data Explorer (drag-and-drop, staging-grained)
- **Route:** `/explorer` (`DataExplorer.jsx`). The simpler Qlik-style pivot over raw staging tables.
- **Backend:** `DataExplorerController` (`/api/explorer`): `GET /options` (distinct filter values per whitelisted column), `POST /query` (`source` = "merchant" | "transaction", dimensions/measures/filters; whitelisted columns + aggregations only; date dims grouped by month; 5000-row cap).
- **Tables:** `stg_merchant_master_raw`, `stg_trnx_raw` (staging — reflects the **last upload** only). For warehouse-grained analytics use the Interactive Explorer (§6).

#### Interactive Explorer (associative / cross-filter)
- **Route:** `/analytics/interactive` (`InteractiveExplorer.jsx`). Click-to-cross-filter dashboard + the full associative explorer.
- **Backend:** `CrossFilterController` → `GET /api/cross-filter?dateFrom=&dateTo=&schemes=&channels=&cardTypes=&destinations=&mccs=` (per-dimension self-excluding breakdowns + timeline + totals), and the `AnalyticsExplorerController` engine (§6).
- **Tables:** `sum_daily_insight` (+ `dim_store` for MCC). Note: amount here is cardholder-currency (`txn_currency_amount`); the UI defaults to Transactions for exact comparability.

#### AI Assistant
- **Route:** `/ai-assistant` (`AiAssistant.jsx`). Natural-language Q&A over a local LLM.
- **Backend:** `AiAssistantController` (`/api/ai`): `GET /health`, `GET /models`, `POST /ask {question,model}`, `POST /explain {question,model}`. Ollama (`llama3.2` at `localhost:11434`).

### 5.4 Sales

The sales hierarchy is **Country Lead → Team Lead → Sales Agent**, where an "agent" is a distinct `dim_merchant.sales_user_id` (rep code, not email). Identity throughout the rollups is `sales_user_id`.

#### Sales Team Management
- **Route:** `/sales/team-management` (`SalesTeamManagement.jsx`). CRUD team leads, assign agents.
- **Backend:** `SalesTeamController` (`/api/sales-team`): `GET/POST/PUT/DELETE /team-leads[/{id}]`, `GET /sales-users`, `POST /assign {salesUserId,teamLeadId}`, `POST /auto-assign`.
- **Tables:** `sales_team_mapping`, `sales_user_assignment`.

#### Sales Country Lead Management
- **Route:** `/sales/country-management` (`SalesCountryLeadManagement.jsx`).
- **Backend:** `SalesCountryLeadController` (`/api/sales-country-lead`): `GET/POST/PUT/DELETE /country-leads[/{id}]`, `GET /team-leads` (with MAPPED/UNMAPPED status), `POST /assign {teamLeadId,countryLeadId}`, `POST /auto-assign`.
- **Tables:** `sales_country_lead`, `sales_team_mapping` (`country_lead_id`).

#### Sales Agent Directory
- **Route:** `/sales/agents` (`SalesAgentDirectory.jsx`). Agent profiles (display name, email, phone, country, hire date, status, monthly target).
- **Backend:** `SalesAgentProfileController` (`/api/sales-agents`): `GET /`, `GET /{salesUserId}`, `PUT /{salesUserId}`, `POST /sync` (creates/refreshes profile stubs from merchant data, auto-populating email).
- **Tables:** `sales_agent_profile`.

#### Sales Leaderboard
- **Route:** `/sales/leaderboard` (`SalesLeaderboard.jsx`). Ranked agents / teams / countries with badges and period-over-period change.
- **Backend:** `LeaderboardController` (`/api/leaderboard`): `GET /agents`, `GET /teams`, `GET /countries`, `GET /overview`, `GET /agents/{agentEmail}` — all take `period` (MTD/QTD/YTD/LAST_MONTH/LAST_QUARTER) or `dateFrom`/`dateTo`. Adds rank, gamification badges, MSF rate, and volume change %.
- **Tables:** `dim_merchant` (`sales_email`/`sales_user_id`/`created_date`), `sum_daily_merchant` (`total_base_volume`, `total_txns`, `total_msf`), `sales_team_mapping`, `sales_user_assignment`, `sales_country_lead`. **Note:** leaderboards use `total_base_volume` (settlement, single-currency), not `total_volume`.

#### Sales Hierarchy Tree / Portfolios
- **Route:** `/sales/hierarchy` (`SalesHierarchyTree.jsx`). Drill the hierarchy and view each tier's portfolio (all-time, optional date bounds): country → teams → agents → merchants, with target attainment.
- **Backend:** `SalesPortfolioController` (`/api/sales-portfolio`): `GET /agent/{salesUserId}`, `GET /team/{teamLeadId}`, `GET /country/{countryLeadId}` — each with `dateFrom`/`dateTo`, returning children one level down, totals, MSF rate, monthly trend (12 mo), and attainment vs `monthly_target`.
- **Tables:** `sum_daily_merchant` (volume), `dim_merchant`, `sales_agent_profile`, `sales_team_mapping`, `sales_user_assignment`, `sales_country_lead`.

### 5.5 Finance

Backed by `FinanceController` (`/api/finance`).

#### Finance Dashboard
- **Route:** `/finance/dashboard` (`FinanceDashboard.jsx`). Redesigned interactive frontend: period selector, hero KPI tiles, Revenue Bridge, Profitability Explorer (dimension/metric switch), ranked bars, paginated table with negative-margin flagging, CSV export, Risk Watchlist tabs.
- **Backend:**
  - `GET /api/finance/dashboard/kpis?from=&to=` and `POST /api/finance/dashboard/kpis-filtered` — daily/MTD/YTD net revenue + volume (anchored on *now*), and filter-range cost analysis (MSF, interchange, scheme fees, VAT, margin %).
  - `GET /api/finance/dashboard/trends/{mode}?from=&to=` and `POST /api/finance/dashboard/trends-filtered?mode=` — daily ≤45 days, else monthly.
  - `GET /api/finance/profitability?groupBy=&from=&to=` (merchant|mcc|scheme|channel, paginated) + CSV `GET /api/finance/export/profitability?groupBy=`.
  - Risk lists: `GET /api/finance/loss-making-merchants`, `GET /api/finance/high-volume-low-margin?minVolume=&maxMarginPct=`.
- **Tables:** `sum_daily_bank`/`sum_monthly_bank` (unfiltered KPIs & trends), `sum_daily_insight` (filtered KPIs/trends — has dimensional columns; interchange/scheme are 0 there, netRev approximated as MSF−interchange−scheme), `sum_daily_merchant`/`sum_daily_mcc`/`sum_daily_scheme`/`sum_daily_channel` (profitability + risk lists).

#### Finance Summary
- **Route:** `/finance/summary` (`FinanceSummary.jsx`). Drill-down Month → Day → Merchant.
- **Backend:** `GET /api/finance/summary?period=&groupBy=&startDate=&endDate=` (period TODAY/MONTH/LAST_MONTH/YEAR/PY; groupBy MONTH/DAY/MERCHANT). Delegates to `VolumeRevenueRepository.getPerformanceDashboardData` and remaps `row_label → month_label`.

#### Finance Lists
- **Route:** `/finance/lists` (`FinanceLists.jsx`). Loss-making + high-volume/low-margin tables. Skeleton loader wired.
- **Backend:** `GET /api/finance/loss-making-merchants`, `GET /api/finance/high-volume-low-margin`.

### 5.6 Operations

#### Upload Files
- **Route:** `/upload` (`UploadPage.jsx`, ADMIN+). Browser upload, auto-detects MERCHANT vs TRANSACTION.
- **Backend:** `FileUploadController` (`acquira-batch`): `POST /api/upload`, `POST /api/upload/multi`. (See `ACQUIRA_DEVELOPER_GUIDE.md` §8 for the pipeline.)

#### Server File Processor
- **Route:** `/ops/server-file` (`ServerFileProcessor.jsx`, ADMIN+). Bulk-process files already on the EC2 disk.
- **Backend:** `POST /api/upload/process-server-file?path=` — path validation lives in `FileUploadService` (`toRealPath()` + symlink rejection, P0-4/P2-6); `SecurityException` → 403.

#### Batch Logs / Monitoring
- **Route:** `/ops/batch-logs` (`BatchMonitoring.jsx`, ADMIN+). Recent batch runs + live progress.
- **Backend:** `BatchJobController` (`/api/batch/jobs`): `GET /?page=&size=` (all job names, tenant-filtered via JobParameters; super-admins see all), `GET /{id}` (status + read/write/skip counts + progress %; returns 404 cross-tenant so it won't confirm existence). Live progress via `BatchProgressController`.
- **Tables:** Spring Batch metadata (`batch_job_execution`, `batch_step_execution`, …) + `batch_run_log`.

#### Email Manager (Statement Emails)
- **Route:** `/business/emails` (`StatementEmails.jsx`, ADMIN+). Send statement emails to merchants.
- **Backend:** `EmailController` (`/api/email`) + `EmailSmtpController` (`/api/email/smtp-configs`). Per-tenant SMTP from `email_smtp_config`. Queue/log tables drive delivery; `EmailQueueProcessor` polls `email_queue` every 60s.
- **Tables:** `email_queue`, `email_smtp_config`, `dim_merchant.contact_email`.

#### Merchant Report Manager
- **Route:** `/business/report-manager` (`MerchantReportManager.jsx`, ADMIN+). Generate/track per-merchant PDF statements in bulk.
- **Backend:** `/api/business/insights/*` (real impl in `acquira-pdf`'s `PdfController`: generate-all, check-status, list-reports, download). PDFs land in `app.reports.dir`, logged in `pdf_batch_log`, optionally pushed to S3.

#### Revenue Leakage
- **Route:** `/business/revenue-leakage` (`RevenueLeakage.jsx`, ADMIN+). Anomaly/leakage flags with KPI cards and a triage workflow.
- **Backend:** `RevenueLeakageController` (`/api/leakage`): `GET /flags?status=&checkType=&severity=&limit=`, `GET /summary` (open count, merchants affected, est. monthly impact, by-severity/by-type), `POST /run` (run detection for the tenant via `RevenueLeakageDetectionService`), and `POST /flags/{id}/resolve|ignore|reopen`.
- **Tables:** `revenue_leakage_flags` (enrichment columns + unique constraint added by `V2026_06_27_01__revenue_leakage_flags_reconcile.sql` to fix schema drift). Detection is service-side; every query is tenant-scoped.

### 5.7 Administration

#### User Management
- **Route:** `/users` (`UserManagement.jsx`, ADMIN+).
- **Backend:** `UserController` (`/api/users`) — user CRUD, approval, tenant/group assignment. Approvals also flow through `AccessRequestController`.
- **Tables:** `users`, `user_tenant_access`, `user_region_access`, `sys_user_group`.

#### Security Settings (Access Requests)
- **Route:** `/admin/security-settings` (`SecuritySettings.jsx`, ADMIN+). Review SSO/self-service access requests.
- **Backend:** `AccessRequestController` (`/api/admin/access-requests`): `GET /?status=`, `GET /count` (pending badge), `POST /{id}/approve` (creates user + `user_tenant_access`, marks APPROVED), `POST /{id}/reject`. Also `AdminController` → `GET /api/admin/security/policy`, `PUT /api/admin/security/policy`, `GET /api/admin/security/locked-users`, `POST /api/admin/security/unlock-user/{id}`, `POST /api/admin/security/revoke-all-sessions` `[SA]`.
- **Tables:** `access_request`, `users`, `user_tenant_access`, `tenant`, `sys_user_group`, `tenant_setting` (`security.*` keys), `refresh_token`.

#### Tenant (Bank) Management
- **Route:** `/tenants` (`TenantManagement.jsx`, SUPER_ADMIN). CRUD tenants/banks.
- **Backend:** `BankController` (`/api/banks`) + `AdminController` (`/api/admin/tenants`) + tenant endpoints in `UserController`/`TenantService`.
- **Tables:** `tenant`, `region`, `tenant_setting`, `dashboard_config`.

#### Group Management (RBAC)
- **Route:** `/admin/groups` (`RbacGroups.jsx`, SUPER_ADMIN). Define groups and which menus each can see.
- **Backend:** `RbacController` (`/api/admin/rbac`): `GET /groups`, `POST /groups` (SUPER_ADMIN — groups+menu grants are system-wide), `GET /menus`.
- **Tables:** `sys_user_group`, `sys_menu`, `sys_group_menu`.

#### Audit Logs
- **Route:** `/admin/audit-logs` (`AuditLogViewer.jsx`, ADMIN+). Searchable/exportable audit trail.
- **Backend:** `AuditLogController` (`/api/admin/audit-logs`): `GET /?page=&size=&search=&category=&action=&username=&startDate=&endDate=`, `GET /export` (CSV), `GET /stats`. Bank admins see only their tenant's trail; super-admins see all.
- **Tables:** `audit_log`.

#### SMTP Settings
- **Route:** `/admin/smtp-settings` (`SmtpSettings.jsx`, ADMIN+). Per-tenant SMTP config.
- **Backend:** `EmailSmtpController` (`/api/email/smtp-configs`): `GET /`, `POST /`, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/activate`, `POST /{id}/test`. Password AES-256-GCM encrypted; never returned (`__UNCHANGED__` sentinel echoed back to keep stored value).
- **Tables:** `email_smtp_config`.

#### S3 Report Storage
- **Route:** `/admin/s3-settings` (`S3Settings.jsx`, ADMIN+). Per-tenant S3 for report archival.
- **Backend:** `S3SettingsController` (`/api/admin/s3-settings`): `GET /` (secret masked), `POST /` (secret AES-256 encrypted via `S3EncryptionService`, only updated if a new non-mask value is sent), `POST /test`.
- **Tables:** `tenant_setting` (keys `s3.enabled/region/bucket/prefix/accessKeyId/secretAccessKey`).

#### Backup & Restore
- **Route:** `/admin/backups` (`BackupRestore.jsx`, SUPER_ADMIN). Trigger/track DB backups.
- **Backend:** `BackupController` (`acquira-batch`, `/api/admin/backups`). Backed by `BackupService` (has a JUnit test, `BackupServiceTest`).

#### SSO Settings
- **Route:** `/admin/sso-settings` (`SsoSettings.jsx`, SUPER_ADMIN). Microsoft Entra SSO config.
- **Backend:** `SsoController` (`/api/sso`): `GET /microsoft/config`, `POST /microsoft/callback`, `POST /request-access`. Public callback at `/auth/sso/callback` (frontend) → token exchange. DB settings (`tenant_setting` `sso_*` keys) override `application.properties` so admins can toggle without restart.
- **Tables:** `tenant_setting` (`sso_*` keys), `access_request` (unprovisioned SSO logins), `users` (`sso_provider`/`sso_id`/`approval_status`), `sso_state_token`, `refresh_token`.

#### Email Campaigns
- **Route:** `/admin/email-campaigns` (`EmailCampaignHub.jsx`, ADMIN+). Template builder + bulk campaigns with delivery logs.
- **Backend:** `EmailCampaignController` (`/api/email-campaigns`): templates `GET/POST/PUT/DELETE /templates[/{id}]`, `POST /templates/{id}/preview`, `GET /templates/variables`; campaigns `GET/POST/PUT /campaigns[/{id}]`, `POST /campaigns/{id}/launch`, `POST /campaigns/{id}/retry-failed`, `GET /campaigns/{id}/logs`, `GET /campaigns/{id}/stats`, `POST /campaigns/preview-recipients`, `GET /campaign-logs`. Execution via `CampaignExecutionService`; rendering via `TemplateRendererService`.
- **Tables:** `email_template_config`, `email_campaign`, `email_campaign_log`, `email_queue`.

#### Data Migration
- **Route:** `/admin/data-migration` (`DataMigration.jsx`, SUPER_ADMIN). Bulk migrate from an external/legacy table; super-admin full-day delete correction tool.
- **Backend:** `MigrationController` (`acquira-batch`, `/api/admin/migration`): `POST /start` (validated `sourceTable`/`startMonth`/`endMonth`/`columnMapping`; async), `GET /progress`, `POST /dry-run`, `POST /delete-day` (SUPER_ADMIN; requires `"confirm": true`; wipes one tenant+date across fact + summary tables and rebuilds monthly rollups).
- **Tables:** writes `fact_transaction` + all summary tables; reads the named source table.

#### Database Maintenance
- **Route:** `/admin/maintenance` (`DatabaseMaintenance.jsx`, ADMIN+). Control the nightly maintenance job (ANALYZE/VACUUM window).
- **Backend:** `MaintenanceController` (`/api/admin/maintenance`): `GET /status`, `PUT /config` (SUPER_ADMIN — enabled/window/tables), `POST /run?force=&overrideBatch=` (SUPER_ADMIN). Backed by `DatabaseMaintenanceService` (has a JUnit test, `DatabaseMaintenanceServiceTest`).

#### Alerts & Notifications
- **Route:** `/admin/alerts` (`AlertsNotifications.jsx`, ADMIN+). Threshold alert rules + triggered history.
- **Backend:** `AlertController` (`/api/admin/alerts`, ADMIN/SUPER_ADMIN): `GET/POST/PUT/DELETE /rules[/{id}]`, `GET /history?limit=`, `POST /history/{id}/acknowledge`.
- **Tables:** `alert_rule`, `alert_history` (from `V2__feature_security_alerts_api.sql`). (Distinct from the Explorer's own threshold alerts in `explorer_alert`, §6.)

#### API Management
- **Route:** `/admin/api-management` (`ApiManagement.jsx`, ADMIN+). Issue/revoke external API keys (`X-API-Key`).
- **Backend:** `ApiKeyController` (`/api/admin/api-keys`, ADMIN/SUPER_ADMIN): `GET /` (prefixes only), `POST /` (returns the raw key **once**; only a BCrypt hash is stored), `DELETE /{id}` (revoke).
- **Tables:** `api_key` (from `V2__feature_security_alerts_api.sql`).

### 5.8 Data Integration

#### Integration Hub
- **Route:** `/admin/integration[/connections|/reports|/schedules|/runs]` (`IntegrationHub.jsx`, tabbed, ADMIN+). Read-only external DB pulls (Oracle/SQL Server) into the warehouse on a schedule.
- **Backend:** `IntegrationController` (`acquira-batch`, `/api/admin/integration`):
  - Connections: `GET/POST/PUT/DELETE /connections[/{id}]`, `POST /connections/{id}/test`. Passwords are AES-encrypted via `CryptoService`; ciphertext is never returned (masked as `__UNCHANGED__`).
  - Reports: `GET/POST/PUT/DELETE /reports[/{id}]` (`?type=`), `POST /reports/{id}/validate` (preview rows/columns).
  - Schedules: `GET/POST/PUT/DELETE /schedules[/{id}]`, `POST /schedules/{id}/toggle`, `POST /schedules/{id}/run-now` (`{dateFrom,dateTo}`). Registered with `DynamicSchedulerService`.
  - Run history: `GET /runs?page=&size=&status=&reportId=`, `POST /runs/{id}/retry`.
  - `GET /overview` — connection/report/schedule counts + 24h run stats + recent runs.
- **Tables:** `integration_connection`, `integration_report`, `integration_schedule`, `integration_run_log`. All CRUD re-verifies tenant ownership of referenced ids.

---

## 6. The Analytics Explorer Engine

The flagship analytics feature is `AnalyticsExplorerController` (`/api/analytics/explorer`), powering the drag-and-drop / associative Interactive Explorer. It is a governed, injection-safe query engine over the warehouse. Phases 0–4 are implemented.

### 6.1 Semantic model & two-grain query planning
Every dimension/measure carries **two** SQL expressions: one for the fact grain (`fact_transaction t`) and one for the pre-aggregated daily grain (`sum_daily_insight s`). At query time the engine inspects the requested dimensions, measures, and filters and chooses the **summary grain** when every one of them is serviceable there (far smaller scan), falling back to the **fact grain** only when something fact-only is needed:

- **Summary-capable dimensions:** mid, merchant name, mcc, industry, city, status, referral partner, sales user, risk level (dim fields); sid/store fields, tid/terminal fields; card_scheme, card_type, destination, dcc (opt-in), payment_month, payment_date.
- **Fact-only dimensions:** transaction_type, txn_currency, store_base_currency, transaction_date.
- **Summary-capable measures:** txn_count, total_volume, total_msf, avg_txn_value, distinct_merchants.
- **Fact-only measures:** total_txn_currency_amount, total_vat, total_settled, total_interchange, distinct_cards.
- Any **amount-range filter** forces the fact grain.

Because `sum_daily_insight` is populated from the same fact rows (`populateSummaryStep`), `SUM` over the summary equals `SUM` over the fact for the same grain — results reconcile. The chosen grain is returned in the response as `grain: "summary" | "fact"` for observability.

### 6.2 Endpoints
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/analytics/explorer/fields` | Field catalog: merchant fields, transaction fields, base measures, measure columns + `aggsByKind` |
| GET | `/api/analytics/explorer/distinct/{fieldKey}?limit=` | Distinct values for a filter dropdown (from the cheapest correct source) |
| POST | `/api/analytics/explorer/query` | Core aggregation (dimensions ≤5, measures, filters, amountFilters, calc/agg/time measures, date range, limit ≤5000) |
| POST | `/api/analytics/explorer/query/merchants` | Entity analysis straight off `dim_merchant` (incl. zero-txn merchants); delegates to the fact/summary query if txn measures are requested |
| POST | `/api/analytics/explorer/associative` | Qlik-style green/white/gray state for a set of fields given current selections |

### 6.3 Measure Studio (user-defined measures)
Three governed, injection-safe measure types can be sent on a query (and stored in master items / saved views / alerts):
- **Calculated measures** (`calcMeasures`): arithmetic over base measure keys with a tokenizing recursive-descent compiler. Whitelisted functions only: `ABS, ROUND, COALESCE, LEAST, GREATEST`; division auto-wraps in `NULLIF(...,0)`. User text never becomes a SQL identifier.
- **Aggregation measures** (`aggMeasures`): `agg(column)` over a whitelisted column set (volume, txn amount, msf, vat, interchange, settled, merchant/store/terminal/card ids, rows) with a whitelisted function set (`SUM, AVG, MIN, MAX, MEDIAN, STDDEV, P90, P95, COUNT, COUNT_DISTINCT`). Optional "set-analysis" condition (`filterField IN (values)`) is parameterized. Fact-grain only.
- **Time-intelligence measures** (`timeMeasures`): compare a base measure across windows — `comparison` ∈ {PREV, MOM, YOY}, `mode` ∈ {growth, delta, prior}. Implemented by running the per-window aggregation for the current and shifted windows and merging per dimension tuple. Requires a date range.

### 6.4 Associative state
`POST /associative` returns, per requested field, every value tagged `selected` (green), `possible` (white, still reachable given selections in *other* fields), or `excluded` (gray). Associativity rule: a field's own selection never restricts its own possible set (within-field = OR, across-field = AND), so when scoring field F the engine applies every selection **except** F's own.

### 6.5 Governance: Master Items & Threshold Alerts (Phase 4.x)
- **Master items** (`/master-items`, `explorer_master_item`): governed, shareable definitions (`itemType` = CALC | AGG | TIME) validated on create (formula compiles, agg column/func valid, time base present). Create/delete are ADMIN/SUPER_ADMIN; unique per `(tenant, itemKey)`.
- **Threshold alerts** (`/alerts`, `explorer_alert`): a measure + operator + threshold + window + severity + recipients, evaluated over a trailing window. `POST /alerts/{id}/run` evaluates immediately (returns value + breach flag). A background `ExplorerAlertScheduler` evaluates enabled alerts on schedule using the same engine.

### 6.6 Security invariants (apply to every Explorer query)
Only whitelisted columns may appear; user input is never interpolated as an identifier. Tenant is scoped on the base table **and** pushed into every dim join (`dX.tenant_id = base.tenant_id`, per P2-1). Dim joins are added only when referenced. Date predicates let the partition pruner trim `fact_transaction`. Filter/condition **values** are always parameterized.

---

## 7. Complete REST Endpoint Reference

Grouped by controller. All are tenant-scoped unless noted; `[A]` = ADMIN/SUPER_ADMIN, `[SA]` = SUPER_ADMIN only.

**AnalyticsExplorerController** `/api/analytics/explorer` — `GET /fields`, `GET /distinct/{field}`, `POST /query`, `POST /query/merchants`, `POST /associative`, `GET/POST/DELETE /master-items[/{id}]` `[A]` (create/delete), `GET/POST/PUT/DELETE /alerts[/{id}]`, `POST /alerts/{id}/run`.

**DataExplorerController** `/api/explorer` — `GET /options`, `POST /query`.

**CrossFilterController** `/api/cross-filter` — `GET /`.

**AnalyticsController** `/api/analytics` — `GET /executive`, `GET /merchant-summaries`, `GET /merchant-summaries/export`, `GET /heatmap`, `POST /heatmap-filtered`, `GET /available-years`, `POST /scheme-breakdown`.

**BusinessController** `/api/business` — `GET /dashboard/kpis`, `POST /dashboard/kpis-filtered`, `GET /opportunity`.

**BusinessAnalyticsController** `/api/business` — `POST /volume-revenue-summary`, `POST /merchant-financial-summary`, `POST /performance-dashboard`, `POST /debit-prepaid-metrics`, `POST /attrition-report`, `POST /executive-metrics`, `POST /merchant-analytics`, `GET /filter-options`, `GET /data-bounds`.

**DailyMerchantDashboardController** `/api/business` — `GET /daily-merchant-dashboard`, `POST /daily-merchant-dashboard-filtered`.

**ExecutiveDashboardController** `/api/dashboard/v2` — `GET /data`, `GET /datasets`.

**FinanceController** `/api/finance` — `GET /summary`, `GET /dashboard/kpis`, `POST /dashboard/kpis-filtered`, `GET /dashboard/trends/{mode}`, `POST /dashboard/trends-filtered`, `GET /profitability`, `GET /export/profitability`, `GET /loss-making-merchants`, `GET /high-volume-low-margin`.

**GroupAnalyticsController** `/api/group-analytics` — `GET /{type}`, `POST /{type}/filtered`.

**InsightController** `/api/reports/insight` — `POST /generate`.

**MerchantInsightController** (or PDF) `/api/business/insights` — `GET /overview`, `GET /check-status`, `POST /generate-all`, `POST /generate/{merchantId}`, `GET /pdf`, `GET /list-reports`, `GET /download-report`, `GET /download-all-reports`, batch-status endpoints.

**ZeroTransactionController** `/api/reports/zero-txn` — `POST /list`, `POST /kpi`, `POST /summary`, `POST /page`.

**GeoAnalyticsController** `/api/analytics/geo` — `GET /heatmap`.

**LeaderboardController** `/api/leaderboard` — `GET /agents`, `GET /teams`, `GET /countries`, `GET /overview`, `GET /agents/{agentEmail}`.

**SalesPortfolioController** `/api/sales-portfolio` — `GET /agent/{salesUserId}`, `GET /team/{teamLeadId}`, `GET /country/{countryLeadId}`.

**SalesTeamController** `/api/sales-team` — `GET/POST/PUT/DELETE /team-leads[/{id}]`, `GET /sales-users`, `POST /assign`, `POST /auto-assign`.

**SalesCountryLeadController** `/api/sales-country-lead` — `GET/POST/PUT/DELETE /country-leads[/{id}]`, `GET /team-leads`, `POST /assign`, `POST /auto-assign`.

**SalesAgentProfileController** `/api/sales-agents` — `GET /`, `GET /{salesUserId}`, `PUT /{salesUserId}`, `POST /sync`.

**TransactionController** `/api/transactions` — `GET /`, `GET /export/csv` `[A]`.

**MerchantController** `/api/merchants` — `GET /`, `GET /{id}`, `GET /{id}/360`, `GET /{id}/stores`, `GET /{id}/terminals`, `GET /{id}/contacts`, `POST /compare`, `GET /hierarchy`.

**StoreController** `/api/stores` — `GET /{id}/terminals`.

**ReportBuilderController** `/api/reports` — `GET/POST/PUT/DELETE /templates[/{id}]`, `POST /export/excel`, `POST /export/csv`, `GET /schedules`, `POST /templates/{id}/schedule`, `DELETE /schedules/{id}`.

**ReportFilterController** `/api/reports/filters` — `GET /mcc|/rm|/merchants|/stores|/terminals|/partners|/channels`.

**SavedFilterController** `/api/filters/views` — `GET /{dashboardType}`, `POST /`, `PUT /{id}`, `DELETE /{id}`, `PUT /{id}/default`.

**RevenueLeakageController** `/api/leakage` — `GET /flags`, `GET /summary`, `POST /run`, `POST /flags/{id}/resolve|ignore|reopen`.

**MenuController** `/api/users/me` — `GET /menus`.

**UserController** `/api/users` `[A]` — `POST /`, `GET /`, `GET /enriched`, `PUT /{id}`, `POST /{userId}/reset-password`, `POST /{userId}/unlock`, `POST /change-password`, `GET /check-email`, `GET /check-username`, `POST /{userId}/assign`, `GET /{username}/banks`, `GET/POST /{userId}/tenant-access`, `PUT/DELETE /{userId}/tenant-access/{accessId}`.

**BankController** `/api/banks` — `GET /` (super-admin: all; else allowed only), `POST /` `[SA]`, `PUT /{id}` `[SA]`.

**AdminController** `/api/admin` `[A]` — `GET /countries`, `POST /tenants`, `POST /users`, `GET/PUT /settings`, `GET/POST /tenants/{tenantId}/settings`, `GET/POST /tenants/{tenantId}/dashboard-config`, `GET /security/locked-users`, `POST /security/unlock-user/{userId}`, `POST /security/revoke-all-sessions` `[SA]`, `GET/PUT /security/policy`.

**SsoController** `/api/sso` — `GET /microsoft/config`, `POST /microsoft/callback`, `POST /request-access`.

**EmailController** `/api/email` `[A]` — `GET /stats`, `GET /logs`, `POST /send/{merchantId}`, `POST /send-bulk`, `GET /batch-status/{jobId}`.

**EmailSmtpController** `/api/email/smtp-configs` `[A]` — `GET /`, `POST /`, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/activate`, `POST /{id}/test`.

**RbacController** `/api/admin/rbac` `[A]` — `GET /groups`, `POST /groups` `[SA]`, `GET /menus`.

**AccessRequestController** `/api/admin/access-requests` `[A]` — `GET /`, `GET /count`, `POST /{id}/approve`, `POST /{id}/reject`.

**AuditLogController** `/api/admin/audit-logs` — `GET /`, `GET /export`, `GET /stats`.

**AlertController** `/api/admin/alerts` `[A]` — `GET/POST/PUT/DELETE /rules[/{id}]`, `GET /history`, `POST /history/{id}/acknowledge`.

**ApiKeyController** `/api/admin/api-keys` `[A]` — `GET /`, `POST /`, `DELETE /{id}`.

**MaintenanceController** `/api/admin/maintenance` `[A]` — `GET /status`, `PUT /config` `[SA]`, `POST /run` `[SA]`.

**S3SettingsController** `/api/admin/s3-settings` — `GET /`, `POST /`, `POST /test`.

**EmailCampaignController** `/api/email-campaigns` — templates + campaigns CRUD + launch/retry/logs/stats/preview (see §5.7).

**IntegrationController** `/api/admin/integration` — connections/reports/schedules/runs CRUD + test/validate/toggle/run-now/retry/overview (see §5.8).

**MigrationController** `/api/admin/migration` — `POST /start`, `GET /progress`, `POST /dry-run`, `POST /delete-day` `[SA]`.

**BatchJobController** `/api/batch/jobs` — `GET /`, `GET /{id}`.

**FileUploadController** `/api/upload` `[A]` — `POST /`, `POST /multi`, `POST /process-server-file`.

**BackupController** `/api/admin/backups` `[SA]` — backup trigger/list/status (acquira-batch).

**AiAssistantController** `/api/ai` — `GET /health`, `GET /models`, `POST /ask`, `POST /explain`.

---

## 8. Database Tables Behind These Features

These are the tables most relevant to the pages above (warehouse/summary tables are documented in `ACQUIRA_DEVELOPER_GUIDE.md` §4). Every business table has `tenant_id`. **For the exhaustive column-level data dictionary of every table, see §10.2.**

**Analytics & governance**
- `sum_daily_insight` — the cross-tab that powers the Explorer, Interactive Explorer, filtered Finance/Business KPIs, and Insight Hub. Per `(tenant, date, merchant, store, terminal, scheme, type, destination, channel, opt_in)`; carries `total_txns`, `total_volume` (cardholder currency), `total_msf`. No interchange/scheme/VAT columns.
- `explorer_master_item` — governed CALC/AGG/TIME measure definitions (`item_type`, `item_key`, `label`, `definition`, `created_by`); unique `(tenant_id, item_key)`.
- `explorer_alert` — Explorer threshold alerts (`measure_key`, `calc_json`, `filter_json`, `window_days`, `operator`, `threshold`, `severity`, `recipients`, `is_enabled`, `last_value`, `last_checked_at`).
- `saved_filter` — saved filter "views" per `(user, tenant, dashboard_type)` (`filter_json`, `is_default`, `is_shared`).

**Sales hierarchy**
- `sales_team_mapping` — team leads (`id`, `team_lead_name`, `team_lead_email`, `country_lead_id`).
- `sales_user_assignment` — agent (`sales_user_id`) → team lead mapping.
- `sales_country_lead` — country leads (`id`, `country_lead_name`, `country_lead_email`, `country_code`).
- `sales_agent_profile` — agent profile + `monthly_target` (drives attainment).

**Alerts, API, audit**
- `alert_rule`, `alert_history` — admin metric alerts (rule CRUD + triggered/acknowledged history).
- `api_key` — external API keys (`key_hash` BCrypt, `key_prefix`, `permissions` JSON, `is_active`, usage counters).
- `audit_log` — every sensitive action; bank-admins see only their tenant.
- `access_request` — self-service / SSO access requests pending admin approval.

**Email & campaigns**
- `email_template_config`, `email_campaign`, `email_campaign_log`, `email_queue`, `email_smtp_config`.

**Reports & integration**
- `report_template`, `report_schedule` (+ `report_run_log`, `report_query_config`, `data_source_config`).
- `integration_connection`, `integration_report`, `integration_schedule`, `integration_run_log`.

**Merchant lifecycle / risk**
- `merchant_activity_summary` (active/dormant/onboarded snapshots), `merchant_opportunity_score`, `revenue_leakage_flags`, `merchant_contact`/`merchant_note`/etc.

**Settings**
- `tenant_setting` — per-tenant key/value (SMTP, S3 `s3.*` keys with encrypted secret, SSO `sso_*`, security `security.*`, leakage thresholds, locale).
- `dashboard_config` — per-tenant KPI tile visibility/order.

---

## 9. Data-Sourcing Rules & Tenant-Isolation Patterns

### 9.1 Which table backs which read
| Need | Use | Avoid |
|---|---|---|
| Bank-level KPIs/trends (unfiltered) | `sum_daily_bank` / `sum_monthly_bank` | scanning `fact_transaction` |
| Dimension-filtered KPIs/trends (partner/RM/MCC/scheme/…) | `sum_daily_insight` (+ dim joins) | `sum_daily_bank` (no dimensional columns) |
| Per-merchant per-day grid, leaderboards, portfolios | `sum_daily_merchant` (`total_base_volume`) | `merchant_daily_metrics` (async, drop-prone); `total_volume` for settlement |
| Cross-tab / associative / Explorer | `sum_daily_insight` | staging tables |
| Raw drag-and-drop "Data Explorer" | `stg_merchant_master_raw` / `stg_trnx_raw` (last upload only) | treating staging as historical |
| Earliest/latest data bounds | `fact_transaction` (authoritative), fall back to `sum_daily_insight` | `sum_daily_insight` alone (may be sparse if `populateSummaryStep` failed) |
| Scheme/channel/MCC breakdowns | `sum_daily_scheme` / `sum_daily_channel` / `sum_daily_mcc` | — |
| Geo heatmap | `sum_daily_terminal` | — |

**Settlement vs cardholder amount:** `sum_daily_merchant.total_base_volume` (and fact `store_base_currency_amount`) is the single-currency settlement figure used by leaderboards, portfolios, and dashboards. `total_volume` is the cardholder-currency figure and is intentionally *not* used for ranking.

### 9.2 Tenant isolation — the non-negotiable pattern
Every read/write scopes on `tenant_id`, and inline aggregation subqueries push the tenant filter **inside** the subquery (and onto every dim join: `dX.tenant_id = base.tenant_id`), not just the outer WHERE (P2-1). This is correctness, not just performance: it stays correct even if `merchant_id` ever stops being globally unique. Concretely enforced across `AnalyticsController` (MTD/YTD subqueries), `GroupAnalyticsController`, `FinanceController`, `BusinessController`, the Explorer engine, and the geo/zero-txn/insight queries.

Other isolation guarantees baked into these controllers:
- **IDOR-safe detail reads:** merchant insight (`merchantId` must belong to the tenant), email campaign logs/stats (campaign ownership re-checked), integration connections/reports/schedules (referenced ids re-verified per tenant), alert acknowledge (scoped to tenant), batch job status (returns 404 cross-tenant so existence isn't confirmed), user-by-id operations (`canActOnUser` — a bank admin may only act on users in their active tenant; a super-admin may act on any), tenant-access mutations (`canAssignTenant` — the access row must belong to the path user *and* target a tenant the caller administers).
- **Secrets never leave the server in clear:** S3 secret masked + AES-256 at rest; integration passwords AES via `CryptoService`, ciphertext replaced by `__UNCHANGED__` in responses; SMTP password AES-256-GCM, `__UNCHANGED__` sentinel; API keys returned once then only a BCrypt hash is stored; transaction CSV masks card numbers to last 4.
- **Super-admin scope:** cross-tenant rollups require a concrete tenant selected in the switcher (an "Unknown Tenant" state 403s on report generation). Super-admins get `visibleTenants` populated (P2-4) and the cross-tenant batch/audit/user views; bank admins are locked to their active tenant. `BankController.GET /` returns all tenants only to super-admins (else only allowed tenants) so the dropdown matches what the server will accept.

### 9.3 Filtered-endpoint convention
Almost every analytics page has a GET (legacy/simple) and a `*-filtered` POST that accepts the full `VolumeRevenueFilterDTO`. The POST variant only emits a WHERE fragment **and** binds its parameter when the corresponding list is non-empty, switching base tables (`sum_daily_merchant` → `sum_daily_insight`) when card-level filters demand columns the merchant grain doesn't have. When adding a new filterable dashboard, follow this pattern: keep the GET working, add a `*-filtered` POST, push tenant into every join, and disable card-level filters in the UI where the base table can't honor them.

---

## 10. Full Backend Reference — Controllers & Complete Schema

This section is the exhaustive backend companion to the page-oriented atlas above: (10.1) the controllers not yet detailed endpoint-by-endpoint, (10.2) the full column-level data dictionary for **every** table in `acquira-core/src/main/resources/schema.sql` (plus the `V2`/`V3` feature-migration tables), (10.3) a table → controller/page cross-reference, and (10.4) RLS/partitioning/seed facts.

### 10.1 Remaining Controllers (exact endpoints)

#### MerchantController — `/api/merchants`
Merchant-universe reads + comparison. Reads tenant from `TenantContext`; 403 when null.

| Method | Path | Body / Params | Returns | Notes |
|---|---|---|---|---|
| GET | `/` | `page=0`, `size=20` | `Page<Merchant>` | `findAllByTenantId` |
| GET | `/{id}` | — | `Merchant` | 404 if absent |
| GET | `/{id}/360` | — | `Merchant360DTO` | merchant + stores + terminals (via store ids) + contacts + documents + risk profile |
| GET | `/{id}/stores` | — | `List<Store>` | `findByMerchantId` |
| GET | `/{id}/terminals` | — | `List<Terminal>` | stores → `findByStoreIdIn` |
| GET | `/{id}/contacts` | — | `List<MerchantContact>` | — |
| POST | `/compare` | `{merchantIds[], startDate, endDate}` | `{merchants[], comparison{leaders,deltas}}` | 2–10 ids; positive-id guard; per-merchant KPIs + monthly trend + scheme & card-type breakdown from `sum_daily_insight`; leaders/deltas computed in-Java |
| GET | `/hierarchy` | `search,sid,tid,storeName,mFrom,mTo,sFrom,sTo,tFrom,tTo,page,size` | `Page<MerchantHierarchyDTO>` | JPA `Specification` with **subqueries** for store/terminal filters; children bulk-fetched only when child filters active (else lazy-load on expand) |

Tables: `dim_merchant`, `dim_store`, `dim_terminal`, `merchant_contact`, `merchant_document`, `merchant_risk_profile`, `sum_daily_insight` (compare).

#### UserController — `/api/users` `[A]`
User CRUD + tenant/group assignment. Isolation helpers: `isSuperAdmin()`, `userIdsInCurrentTenant()`, `canActOnUser(id)`, `canAssignTenant(tenantId)`.

| Method | Path | Body / Params | Notes |
|---|---|---|---|
| POST | `/` | `User` | username+email duplicate check; password strength; encodes pw; `mustChangePassword=true`; records pw history |
| GET | `/` | — | super-admin → all; bank admin → users in active tenant |
| GET | `/enriched` | — | as above + per-user tenant assignments, ssoProvider, approvalStatus, lock state |
| PUT | `/{id}` | `User` | `canActOnUser`; email dup check; displayName/role/active; optional admin pw reset |
| POST | `/{userId}/reset-password` | `{newPassword}` | `canActOnUser`; sets must-change |
| POST | `/{userId}/unlock` | — | clears `failed_login_attempts`/`locked_until`/`last_failed_login` |
| POST | `/change-password` | `{currentPassword,newPassword}` | self-service (auth user); not `[A]` |
| GET | `/check-email` | `email` | `{available}` |
| GET | `/check-username` | `username` | `{available}` |
| POST | `/{userId}/assign` | `{bankId,groupId}` | `canActOnUser` + `canAssignTenant` |
| GET | `/{username}/banks` | — | allowed tenants for the user (not `[A]`) |
| GET | `/{userId}/tenant-access` | — | list access rows (accessId, tenant, group, roleInTenant, isDefault) |
| POST | `/{userId}/tenant-access` | `{tenantId,groupId,roleInTenant,isDefault}` | duplicate-tenant guard; default-flip |
| PUT | `/{userId}/tenant-access/{accessId}` | `{groupId,roleInTenant,isDefault}` | row must belong to path user + tenant the caller administers |
| DELETE | `/{userId}/tenant-access/{accessId}` | — | same guards as PUT |

Tables: `users`, `user_tenant_access`, `sys_user_group`, `tenant`, `password_history`.

#### EmailController — `/api/email` `[A]`
Email Manager (statement emails). Tenant from `TenantContext` (header-aware). Does **not** send directly — enqueues into `email_queue`; `EmailQueueProcessor` delivers async.

| Method | Path | Body / Params | Notes |
|---|---|---|---|
| GET | `/stats` | `month` (YYYY-MM) | `{sent,failed,pending,total}` from `email_queue` |
| GET | `/logs` | `month,page=0,size=50` | paginated queue rows (id, merchant, recipient, subject, status, retryCount, error, sentAt) |
| POST | `/send/{merchantId}` | `month` | generate statement PDF + enqueue one row (per-row Retry) |
| POST | `/send-bulk` | `month` | spawns a **daemon thread** (`enqueueBulk`) over `findAllByTenantId(tid)`; returns immediately; sets `TenantContext` inside the worker |
| GET | `/batch-status/{jobId}` | `month` | RUNNING while PENDING rows remain, else COMPLETED |

PDF via `MerchantInsightService` + `PlaywrightPdfService`; files written under `reports/statement-emails/<month>/`. Tables: `email_queue`, `dim_merchant` (`contact_email`).

#### EmailSmtpController — `/api/email/smtp-configs` `[A]`
Per-tenant SMTP CRUD; password AES-256-GCM, never returned (`__UNCHANGED__` sentinel echoed on update to keep stored value). Tenant from `TenantContext`.

`GET /` list · `POST /` create · `PUT /{id}` update · `DELETE /{id}` · `POST /{id}/activate` (enforces at-most-one active per tenant) · `POST /{id}/test` (returns `{status,message}`). Table: `email_smtp_config`.

#### SsoController — `/api/sso`
Microsoft Entra (Azure AD) OAuth2. DB `tenant_setting` `sso_*` keys override `application.properties` (toggle without restart). In-memory CSRF state tokens (10-min TTL).

| Method | Path | Body | Flow |
|---|---|---|---|
| GET | `/microsoft/config` | — | `{enabled,provider,authUrl,clientId}` or `{enabled:false}`; mints state token |
| POST | `/microsoft/callback` | `{code,state}` | validate state → exchange code for token → Graph `/me` → look up user by email → issue JWT (approved) / pending / rejected / not_registered (+ available tenants) |
| POST | `/request-access` | `{email,displayName,ssoId,message,tenantId}` | creates `access_request` (PENDING) |

JWT response mirrors `AuthController` (jwt, refreshToken, allowedTenants, defaultTenantId, roles, menus, displayName). Tables: `users`, `access_request`, `tenant_setting`, `refresh_token`, `tenant`.

#### BankController — `/api/banks`
| Method | Path | Guard | Notes |
|---|---|---|---|
| GET | `/` | any auth | super-admin → all tenants; else `tenantService.getAllowedTenants(name)`; anonymous → `[]` |
| POST | `/` | `[SA]` | auto-fills `bankShortCode`/`institutionId` if missing |
| PUT | `/{id}` | `[SA]` | updates name/shortCode/country/currencyName/currencySymbol/baseCurrency |

Table: `tenant`.

#### StoreController — `/api/stores`
`GET /{id}/terminals` → `List<Terminal>` (`findByStoreId`); 403 when no tenant context. Table: `dim_terminal`.

#### AdminController — `/api/admin` `[A]` (class-level)
| Method | Path | Guard | Notes |
|---|---|---|---|
| GET | `/countries` | `[A]` | `ref_country` ordered by name |
| POST | `/tenants` | `[A]` | create tenant + audit `CREATE_TENANT` |
| POST | `/users` | `[A]` | create user (+ optional `?tenantId=` assign); bank admin may only target active tenant; pw strength + history + audit |
| GET | `/settings` | `[A]` | `tenant_setting` for tenant (from `X-Tenant-Id`, fallback first access) |
| PUT | `/settings` | `[A]` | upsert one `{settingKey,settingValue}` |
| GET | `/tenants/{tenantId}/settings` | `[A]` | list settings |
| POST | `/tenants/{tenantId}/settings` | `[A]` | save a `TenantSetting` |
| GET | `/tenants/{tenantId}/dashboard-config` | `[A]` | `dashboard_config` ordered |
| POST | `/tenants/{tenantId}/dashboard-config` | `[A]` | save a `DashboardConfig` |
| GET | `/security/locked-users` | `[A]` | `findByLockedUntilAfter(now)` |
| POST | `/security/unlock-user/{userId}` | `[A]` | clear lock + audit |
| POST | `/security/revoke-all-sessions` | `[SA]` | `refreshTokenService.revokeAll()` + audit |
| GET | `/security/policy` | `[A]` | merges defaults with `security.*` settings |
| PUT | `/security/policy` | `[A]` | upserts each policy key as `security.<k>` |

Tables: `ref_country`, `tenant`, `users`, `user_tenant_access`, `tenant_setting`, `dashboard_config`, `refresh_token`, `password_history`.

> **Note on the `TenantSetting` entity:** the JPA entity exposes `getKey()/getValue()/getType()` mapped to columns `setting_key`/`setting_value`/`setting_type`. Code reading "key"/"value" is hitting those columns.

### 10.2 Complete Schema — Data Dictionary (every table, every column)

Source: `acquira-core/src/main/resources/schema.sql` (canonical, de-duplicated) plus feature migrations `V2__feature_security_alerts_api.sql` (alert_rule/alert_history/api_key) and `V3__feature_revenue_leakage.sql`. Types as declared. **RLS** = Row-Level Security enabled with `tenant_isolation_policy USING (tenant_id = get_current_tenant())`. Partitioned fact/summary tables list their partition key.

#### A. Tenancy, Regions & Reference Data
**region** — `region_id SERIAL PK`, `region_name VARCHAR(100) UNIQUE NOT NULL`.

**ref_country** — `country_code VARCHAR(2) PK` (ISO alpha-2), `country_name`, `currency_code VARCHAR(3)`, `currency_name`, `currency_symbol`, `phone_code`, `iso_numeric`, `decimal_notation_value INT DEFAULT 100` (raw-amount divisor — 1000 for BHD/KWD/OMR/etc., 10 for JPY, 100 default). ~200 rows seeded.

**ref_card_scheme** — `id INT PK`, `is_active`, `code VARCHAR(10) UNIQUE`, `name`, `group_code`, `group_name`, `status`, `created_date`, `card_type INT` (0 Generic / 1 Credit / 2 Debit / 3 Credit Prepaid / 4 Debit Prepaid), `card_subtype INT` (0/1 Standard / 2 Premium). 17 schemes seeded (VISA, MCRD, AMEX, VIDB, MCDB, MCCR, VICR, UPI, JCB, MCPM, MCSD, VICP, VIPM, VISD, MCCP, MCDP, ZPET).

**tenant** — `tenant_id SERIAL PK`, `institution_id VARCHAR(50) UNIQUE`, `bank_name`, `bank_short_code VARCHAR(10) UNIQUE`, `base_currency DEFAULT 'USD'`, `country`, `currency_name`, `currency_symbol`, `region_id → region`, `status DEFAULT 'ACTIVE'`, `created_at`.

#### B. RBAC, Users & Auth
**sys_user_group** — `group_id BIGSERIAL PK`, `group_name VARCHAR(50) UNIQUE`, `description`. Seeded: Super Admin, Bank Admin, Business User, Finance User, Ops User.

**sys_menu** — `menu_id BIGSERIAL PK`, `menu_name`, `path VARCHAR(100) UNIQUE` (`uq_menu_path`), `icon_key`, `category`, `display_order`. One row per App.jsx route.

**sys_group_menu** — `group_id → sys_user_group`, `menu_id → sys_menu`, PK `(group_id, menu_id)`. The sidebar source of truth.

**role** — `role_id SERIAL PK`, `role_name VARCHAR(50) UNIQUE`.

**users** — `user_id BIGSERIAL PK`, `username VARCHAR(50) UNIQUE`, `password_hash VARCHAR(255)`, `email`, `role VARCHAR(50)`, `is_active DEFAULT TRUE`, `created_at`, `last_login`, `must_change_password`, `password_changed_at`, `failed_login_attempts DEFAULT 0`, `locked_until`, `last_failed_login`. **ALTER-added:** `sso_provider VARCHAR(20)`, `sso_id VARCHAR(255)`, `approval_status VARCHAR(20) DEFAULT 'APPROVED'` (APPROVED/PENDING/REJECTED), `display_name VARCHAR(150)`. Seed: `admin / {noop}password` role `ROLE_SUPER_ADMIN`.

**user_role** — `map_id SERIAL PK`, `user_id → users`, `role_id → role`, UNIQUE `(user_id, role_id)`.

**user_tenant_access** — `access_id SERIAL PK`, `user_id → users`, `tenant_id → tenant`, `group_id → sys_user_group`, UNIQUE `(user_id, tenant_id)`. **ALTER-added:** `role_in_tenant VARCHAR(50)`, `is_default_tenant BOOLEAN DEFAULT FALSE`. The critical multi-tenancy join.

**user_region_access** — `access_id SERIAL PK`, `user_id → users`, `region_id → region`, UNIQUE `(user_id, region_id)`.

**refresh_token** — `id BIGSERIAL PK`, `username`, `token_hash VARCHAR(128) UNIQUE`, `issued_at`, `expires_at`, `revoked DEFAULT FALSE`, `replaced_by`, `user_agent`, `ip_address`. Rotation + revocation (#14).

**sso_state_token** — `state_token VARCHAR(100) PK`, `created_at`, `expires_at`, `used DEFAULT FALSE`. CSRF persistence across restart (#7).

**password_history** — `history_id BIGSERIAL PK`, `user_id → users`, `password_hash`, `created_at`. Reuse prevention.

**password_reset_token** — `token_id BIGSERIAL PK`, `user_id → users`, `token VARCHAR(255) UNIQUE`, `expires_at`, `used DEFAULT FALSE`, `created_at`.

**access_request** — `request_id BIGSERIAL PK`, `email`, `display_name`, `sso_provider`, `sso_id`, `requested_tenant_id → tenant`, `message`, `status DEFAULT 'PENDING'` (PENDING/APPROVED/REJECTED), `reviewed_by → users`, `reviewed_at`, `review_notes`, `created_at`.

**audit_log** *(RLS)* — `log_id BIGSERIAL PK`, `tenant_id → tenant` (nullable for system actions), `user_id → users`, `username`, `action_type`, `details TEXT`, `ip_address`, `event_time`, `http_method`, `endpoint`, `status_code`, `user_agent`, `category`, `entity_type`, `entity_id`, `duration_ms`.

**tenant_setting** *(RLS)* — `setting_id SERIAL PK`, `tenant_id → tenant`, `setting_key VARCHAR(100)`, `setting_value TEXT`, `setting_type DEFAULT 'STRING'` (STRING/JSON/BOOLEAN/NUMBER), UNIQUE `(tenant_id, setting_key)`. Holds `sso_*`, `s3.*`, `security.*`, `password_*`, `max_failed_logins`, `lockout_duration_minutes`, `leakage.*` keys.

**dashboard_config** *(RLS)* — `config_id SERIAL PK`, `tenant_id → tenant`, `kpi_key VARCHAR(50)`, `display_label`, `is_visible DEFAULT TRUE`, `display_order`, UNIQUE `(tenant_id, kpi_key)`.

**ai_chat_history** — `chat_id BIGSERIAL PK`, `tenant_id`, `user_id → users`, `question TEXT`, `generated_sql TEXT`, `summary TEXT`, `row_count`, `duration_ms`, `is_error DEFAULT FALSE`, `error_msg`, `created_at`.

#### C. Staging (transient — truncated each upload) *(RLS)*
**stg_merchant_master_raw** — `raw_id BIGSERIAL PK`, `tenant_id`, `file_id`, `load_time`, `row_hash`, `status DEFAULT 'PENDING'`, `error_message`, then the full merchant-master Excel column set: `institution_code/name`, `entity_internal_id/name/code`, `aggregator_internal_id/name/code`, `merchant_internal_id`, `mid`, `merchant_name`, `merchant_status`, `merchant_store_internal_id`, `sid`, `store_legal_name`, `store_name`, `store_status`, `business_type`, `business_mcc`, `vat_number`, primary/secondary contact person/number/email/designation, `address`, `city`, `state`, `postal_code`, `store_desc`, `industry_type`, `customer_type`, `source_of_fund`, `expected_volume`, `regulated_activity(+_desc)`, `auditor_name`, `is_pep`, `pep_reason`, `high_risk_adverse_media`, `high_risk_source_of_wealth`, `risk_level(+_high/_prohibited/_restricted)`, `product`, `date_of_onboarding`, `reviewed_date`, `next_reviewed_date`, `sales_user_email`, `sales_user_id`, `referral_partner`, `created_date`, terminal_internal_id, `tid`, `terminal_name/status/device_number/type/description`, `bank_name`, `bank_account_name/number`, `swift_code`, `iban_number`, `merchant_created_date`, `merchant_store_created_date`, `terminal_created_date`.

**stg_trnx_raw** — `raw_id BIGSERIAL PK`, `tenant_id`, `file_id`, `load_time`, `row_hash`, `status`, `error_message`, then: `entity_name`, `aggregator_internal_id/name/code`, `mid`, `merchant_internal_id`, `merchant_name`, `sid`, `merchant_store_internal_id`, `cmm_merchant_store_internal_id`, `merchant_store_legal_name`, `store_name`, `tid`, `arn`, `rrn_number`, `card_number`, `auth_code`, `payment_date`, `transaction_date`, `batch_number`, `transaction_type`, `card_scheme`, `card_type`, `dcc BOOLEAN`, `txn_currency`, `txn_currency_amount`, `store_base_currency`, `store_base_currency_amount`, `msf`, `vat`, `total_amount_settled`, `interchange_fee`, `destination`.

#### D. Dimensions *(RLS)*
**dim_merchant** — `merchant_id BIGSERIAL PK`, `tenant_id → tenant NOT NULL`, `internal_id`, `mid`, `name`, `status`, `created_date`, `sales_user_id`, `sales_email` (RM mapping), `referral_partner`, `risk_level`, `industry`, `mcc`, `location`, `city`, UNIQUE `(tenant_id, internal_id)`. **ALTER-added:** `contact_email VARCHAR(255)`.

**dim_store** — `store_id BIGSERIAL PK`, `tenant_id NOT NULL`, `internal_id`, `merchant_id → dim_merchant`, `sid`, `name`, `legal_name`, `address`, `city`, `state`, `postal_code`, `mcc`, `status`, `created_date`, `latitude DOUBLE`, `longitude DOUBLE`, `timezone`, `operating_hours JSONB`, UNIQUE `(tenant_id, internal_id)`.

**dim_terminal** — `terminal_id BIGSERIAL PK`, `tenant_id NOT NULL`, `internal_id`, `store_id → dim_store`, `tid`, `device_number`, `type`, `status`, `created_date`, UNIQUE `(tenant_id, internal_id)`.

**dim_bank_account** *(RLS)* — `account_id BIGSERIAL PK`, `tenant_id NOT NULL`, `store_id → dim_store`, `bank_name`, `account_number`, `swift_code`, `iban`.

**dim_aggregator** — `aggregator_id SERIAL PK`, `tenant_id → tenant` (optional), `internal_id`, `name`, `code`.

#### E. Merchant Management & Lifecycle *(RLS)*
**bank_budget_target** — `budget_id SERIAL PK`, `tenant_id NOT NULL`, `month_key INT`, `metric_type`, `target_value`, `created_at`.

**merchant_lifecycle_status** — `merchant_id → dim_merchant`, `tenant_id NOT NULL`, `current_status`, `reason_code`, `last_status_change`.

**merchant_activity_summary** — `summary_id BIGSERIAL PK`, `tenant_id NOT NULL`, `merchant_id → dim_merchant`, `calc_date DATE`, `first_txn_date`, `last_txn_date`, `last_7d_cnt`, `last_7d_value`, `last_30d_cnt`, `last_30d_value`, `status`, `status_change_date`, UNIQUE `(tenant_id, merchant_id, calc_date)`. Drives dashboard active/dormant/new counts.

**merchant_opportunity_score** — `score_id BIGSERIAL PK`, `tenant_id NOT NULL`, `merchant_id → dim_merchant`, `score DECIMAL(5,2)`, `reason_tags VARCHAR(255)`, `calc_date DATE`, UNIQUE `(tenant_id, merchant_id, calc_date)`.

**revenue_leakage_flags** — `flag_id BIGSERIAL PK`, `merchant_id → dim_merchant`, `tenant_id NOT NULL`, `check_type`, `severity`, `details TEXT`, `detected_at`, `is_resolved DEFAULT FALSE`; **enrichment:** `merchant_name`, `business_date DATE`, `metric_value NUMERIC(19,2)`, `baseline_value`, `delta_pct NUMERIC(9,2)`, `est_monthly_impact`, `status DEFAULT 'OPEN'` (OPEN/RESOLVED/IGNORED), `resolved_at`, `resolved_by`; UNIQUE `(tenant_id, merchant_id, check_type, business_date)` (`uq_revenue_leakage_flag`).

**merchant_contact** — `contact_id BIGSERIAL PK`, `merchant_id`, `store_id` (optional), `tenant_id NOT NULL`, `contact_name`, `role` (Primary/Technical/Finance/Emergency), `email`, `phone`, `is_primary`.

**merchant_document** — `document_id BIGSERIAL PK`, `merchant_id`, `tenant_id NOT NULL`, `document_type` (Agreement/KYC/License), `document_name`, `file_path`, `upload_date`, `expiry_date`.

**merchant_contract** — `contract_id BIGSERIAL PK`, `merchant_id`, `tenant_id NOT NULL`, `contract_number`, `start_date`, `end_date`, `auto_renew`, `status` (Active/Expired/Pending).

**merchant_risk_profile** — `profile_id BIGSERIAL PK`, `merchant_id`, `tenant_id NOT NULL`, `risk_score INT`, `compliance_status`, `kyc_status`, `aml_checks_passed`, `last_review_date`, `notes`.

**merchant_settlement_config** — `config_id BIGSERIAL PK`, `merchant_id`, `tenant_id NOT NULL`, `settlement_frequency` (Daily/Weekly), `hold_days`, `min_settlement_amount`, `currency`.

**merchant_note** — `note_id BIGSERIAL PK`, `merchant_id`, `tenant_id NOT NULL`, `note_text`, `created_by`, `created_at`.

**merchant_activity** *(RLS)* — `activity_id BIGSERIAL PK`, `tenant_id NOT NULL`, `merchant_id → dim_merchant`, `last_txn_date DATE`, `days_since_last_txn`, `status` (ACTIVE/DORMANT/CHURNED), UNIQUE `(tenant_id, merchant_id)`.

#### F. Fact & Summary Tables *(RLS; partitioned by date unless noted)*
**fact_transaction** — PK `(transaction_id, payment_date)`, **PARTITION BY RANGE (payment_date)** (monthly partitions `fact_transaction_y{YYYY}m{MM}` created at startup by `PartitionMaintenanceService`; `fact_transaction_default`). Columns: `transaction_id BIGSERIAL`, `tenant_id NOT NULL` (no FK — partition compatibility), `merchant_id`, `store_id`, `terminal_id`, `arn`, `rrn_number`, `card_number`, `auth_code`, `payment_date NOT NULL`, `transaction_date`, `batch_number`, `transaction_type`, `card_scheme`, `card_type`, `dcc BOOLEAN`, `txn_currency`, `txn_currency_amount`, `store_base_currency`, `store_base_currency_amount`, `msf DECIMAL(19,4)`, `vat DECIMAL(19,4)`, `total_amount_settled`, `interchange_fee DECIMAL(19,4)`, `destination`, `created_at`. The grain everything rolls up from.

**sum_daily_merchant** — PK `(summary_id, business_date)`, UNIQUE `(tenant_id, business_date, merchant_id)`, **PARTITION BY RANGE (business_date)** (yearly `_y2024/_y2025/_y2026/_default`). Columns: `tenant_id NOT NULL`, `business_date NOT NULL`, `institution_id`, `merchant_id`, `store_id` (always NULL — see Group Reports MCC note), `total_txns`, `total_volume` (cardholder ccy), `total_msf`, `total_interchange`, `total_scheme_fee`, `total_margin`, `total_debit_prepaid_volume`, `total_credit_volume`, `sales_user_id`, `unique_customer_count`, `top_spending_customer_id`, `top_spending_amount`, **`total_base_volume`** (settlement, single-currency — used by leaderboards/portfolios/dashboards), `dcc_eligible_volume`, `dcc_optin_volume`, `dcc_optout_volume`, `dcc_eligible_count`, `dcc_optin_count`.

**sum_daily_scheme** — PK `(summary_id, business_date)`, UNIQUE `(tenant_id, business_date, card_scheme)`, partitioned. `total_txns`, `total_volume`, `total_msf`, `total_interchange`, `total_scheme_fee`, `total_net_revenue`.

**sum_daily_channel** — same shape keyed on `channel` (POS/ECOM…). UNIQUE `(tenant_id, business_date, channel)`.

**sum_daily_terminal** — PK `(summary_id, business_date)`, UNIQUE `(tenant_id, business_date, merchant_id, store_id, terminal_id)`, partitioned. `total_txns`, `total_volume`, `total_msf`, `total_revenue`. Backs Zero-Txn + Geo heatmap.

**sum_daily_merchant_attribute** — PK `(id, business_date)`, UNIQUE `(tenant_id, merchant_id, business_date, attribute_type, attribute_value)`, partitioned. `attribute_type`, `attribute_value`, `metric_count`, `metric_volume`, `version`, `tenant_id`.

**sum_monthly_bank** — `summary_id BIGSERIAL PK`, `tenant_id → tenant`, `month_key INT` (YYYYMM), `total_txns`, `total_volume`, `total_msf`, `total_interchange`, `total_scheme_fee`, `total_vat`, `total_net_revenue`, UNIQUE `(tenant_id, month_key)`.

**sum_daily_bank** — PK `(summary_id, business_date)`, UNIQUE `(tenant_id, business_date)`, partitioned. `total_txns`, `total_volume`, `total_msf`, `total_interchange`, `total_scheme_fee`, `total_vat`, `total_net_revenue`. Backs unfiltered bank KPIs/trends.

**sum_daily_finance** — PK `(summary_id, business_date)`, UNIQUE `(tenant_id, business_date)`, partitioned. Domestic-debit/-credit & international splits: `dom_debit_cnt/vol/msf/optin`, `dom_credit_cnt/vol/msf/optin`, `int_cnt/vol/msf/optin`, `total_vol`, `total_msf`.

**sum_daily_insight** — PK `(summary_id, business_date)`, **UNIQUE `(tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in)`**, partitioned. Dimensional cross-tab: `merchant_id`, `store_id`, `terminal_id`, `card_scheme`, `card_type`, `destination`, `channel`, `is_opt_in BOOLEAN`, `total_txns`, `total_volume` (cardholder ccy), `total_msf`. **No** interchange/scheme/VAT columns. Powers Explorer / CrossFilter / filtered KPIs / Insight Hub / compare.

**sum_daily_mcc** — `summary_id BIGSERIAL PK`, `tenant_id → tenant`, `business_date`, `mcc`, `card_scheme`, `total_txns`, `total_volume`, `total_msf`, `total_scheme_fee`, `total_net_revenue`, UNIQUE `(tenant_id, business_date, mcc, card_scheme)`. *Not partitioned.*

**sum_monthly_card** — `id BIGSERIAL PK`, `tenant_id`, `merchant_id`, `month_key` (YYYYMM), `card_number`, `visit_count`, `total_spend`, UNIQUE `(tenant_id, merchant_id, month_key, card_number)`. Loyalty/frequency.

**sum_monthly_merchant_metrics** — `metric_id BIGSERIAL PK`, `tenant_id → tenant`, `merchant_id → dim_merchant`, `month_year VARCHAR(7)` (YYYY-MM), `volatility_index`, `stability_label`, `behavior_tag`, `smart_comment`, `week_1..5_health`, `total_volume`, `avg/max/min_daily_volume`, `created_at`, `updated_at`, UNIQUE `(tenant_id, merchant_id, month_year)`.

**merchant_daily_metrics** *(RLS)* — `id BIGSERIAL PK`, `tenant_id → tenant`, `report_date DATE`, `merchant_id VARCHAR(255)`, `merchant_name`, `mid`, `today_volume`, `yesterday_volume`, `avg7day`, `total_mtd`, `trend_pct`, `volatility`, `risk_score`, `ui_status`, `daily_volumes_json`, `sparkline_data_json`, `source_type`, `updated_at`. **Legacy/old** async hybrid table — replaced by `sum_daily_merchant` for the Daily Merchant Dashboard (was drop-prone).

**kpi_snapshot_daily** — `snapshot_id BIGSERIAL PK`, `tenant_id → tenant`, `snapshot_date DATE`, `metric_key` (TOTAL_VOL/TOTAL_REV/ACTIVE_MERCHANTS/NEW_MERCHANTS), `metric_value`, UNIQUE `(tenant_id, snapshot_date, metric_key)`.

**kpi_snapshot_monthly** — `snapshot_id BIGSERIAL PK`, `tenant_id → tenant`, `month_key INT` (YYYYMM), `metric_key`, `metric_value`, UNIQUE `(tenant_id, month_key, metric_key)`.

**batch_run_log** *(RLS)* — `run_id BIGSERIAL PK`, `tenant_id`, `job_name`, `start_time`, `end_time`, `status` (COMPLETED/FAILED/RUNNING), `records_processed`, `records_failed`, `error_message`.

#### G. Config, Reports, Integration & Saved Filters
**data_source_config** — `id BIGSERIAL PK`, `name`, `db_type` (ORACLE/POSTGRES/MSSQL), `host`, `port`, `db_name`, `username`, `encrypted_password`, `is_active`, `created_at`.

**report_query_config** — `id BIGSERIAL PK`, `report_name`, `sql_text TEXT`, `source_id → data_source_config`, `description`, `is_active`, `approved_by`, `created_at`.

**report_run_log** — `id BIGSERIAL PK`, `query_id → report_query_config`, `start_time`, `end_time`, `status` (SUCCESS/FAILED/RUNNING), `row_count`, `error_message`.

**saved_filter** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `user_id NOT NULL`, `name VARCHAR(100)`, `dashboard_type VARCHAR(50)`, `filter_json TEXT` (≤10KB enforced in service), `is_default`, `is_shared`, `created_at`, `updated_at`, UNIQUE `(tenant_id, user_id, dashboard_type, name)`. Max 50/user/tenant.

**integration_connection** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `name`, `db_type`, `host`, `port`, `db_name`, `username`, `encrypted_password TEXT` (AES via CryptoService), `timeout_seconds DEFAULT 30`, `max_retries DEFAULT 3`, `is_active`, `last_test_at`, `last_test_status`, `created_at`, `updated_at`.

**integration_report** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `connection_id → integration_connection`, `name`, `report_type` (MERCHANT/TRANSACTION), `sql_text TEXT`, `column_mapping TEXT`, `description`, `param_schema TEXT`, `is_active`, `approved_by`, `created_at`, `updated_at`.

**integration_schedule** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `report_id → integration_report`, `cron_expression`, `frequency_label`, `timezone DEFAULT 'UTC'`, `is_enabled`, `last_run_at`, `next_run_at`, `created_at`, `updated_at`.

**integration_run_log** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `report_id`, `schedule_id`, `trigger_type`, `status`, `attempt_number`, `max_retries`, `rows_fetched`, `rows_processed`, `rows_failed`, `start_time`, `end_time`, `error_message`, `date_range_from/to`, `duration_ms`, `created_at`.

**report_template** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `user_id NOT NULL`, `name`, `description`, `config_json TEXT`, `is_shared`, `last_run_at`, `created_at`, `updated_at`.

**report_schedule** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `template_id → report_template`, `cron_expression`, `frequency_label`, `timezone`, `delivery_method DEFAULT 'EMAIL'`, `recipient_emails TEXT`, `export_format DEFAULT 'EXCEL'`, `is_enabled`, `last_run_at`, `next_run_at`, `created_at`, `updated_at`.

#### H. Email & Campaigns
**email_template_config** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `name`, `template_type` (STATEMENT/WELCOME/ALERT), `subject_template`, `body_html TEXT`, `body_text`, `is_active`, `is_default_for_type`, `created_by`, `created_at`, `updated_at`, UNIQUE `(tenant_id, name)` (`uq_email_tpl_tenant_name`). Seeded per tenant (Monthly Statement / Welcome / Dormancy Alert). Variables: `{{tenant_name}}`, `{{merchant_name}}`, `{{contact_name}}`, `{{mid}}`, `{{month}}`, `{{total_count/volume/msf}}`, `{{days_since_last_txn}}`, `{{city}}`, `{{store_count}}`, `{{terminal_count}}`.

**email_campaign** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `name`, `template_id → email_template_config`, `campaign_type`, `recipient_filter_json TEXT`, `attachment_type DEFAULT 'NONE'`, `attachment_report_template_id`, `statement_month`, `schedule_cron`, `schedule_timezone`, `status DEFAULT 'DRAFT'`, `total_recipients`, `sent_count`, `failed_count`, `sent_at`, `created_by`, `created_at`, `updated_at`.

**email_campaign_log** — `id BIGSERIAL PK`, `campaign_id → email_campaign`, `tenant_id NOT NULL`, `merchant_id`, `merchant_name`, `recipient_email`, `subject_rendered`, `status`, `sent_at`, `error_message`, `retry_count`, `created_at`.

**email_smtp_config** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `config_name`, `host`, `port DEFAULT 587`, `username`, `password VARCHAR(1024)` (AES-256-GCM, `enc:v1:` prefixed), `auth_enabled`, `starttls_enabled`, `ssl_enabled`, `from_address`, `from_name`, `reply_to`, `connection_timeout`, `read_timeout`, `write_timeout`, `rate_limit_ms`, `max_retries`, `is_active`, `auto_send_after_batch`, `created_at`, `updated_at`. Partial-unique index `uq_email_smtp_config_active (tenant_id) WHERE is_active` → at most one active per tenant.

**email_queue** — `id BIGSERIAL PK`, `tenant_id`, `merchant_id`, `merchant_name`, `recipient NOT NULL`, `subject`, `body TEXT`, `is_html DEFAULT TRUE`, `attachment_path VARCHAR(1024)`, `statement_month VARCHAR(10)`, `status DEFAULT 'PENDING'` (PENDING/SENT/FAILED), `retry_count`, `error_message`, `created_at`, `sent_at`. Polled by `EmailQueueProcessor` (60s) `WHERE status='PENDING'`.

#### I. Data Explorer Governance
**explorer_master_item** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `item_type VARCHAR(20)` (CALC/AGG/TIME), `item_key VARCHAR(120)`, `label`, `definition TEXT`, `description`, `created_by`, `created_at`, UNIQUE `(tenant_id, item_type, item_key)` (`uq_master_item`).

**explorer_alert** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `name`, `measure_key`, `calc_json TEXT`, `filter_json TEXT`, `window_days DEFAULT 1`, `operator VARCHAR(4)`, `threshold DOUBLE`, `severity DEFAULT 'WARNING'`, `recipients TEXT`, `is_enabled DEFAULT TRUE`, `last_value`, `last_checked_at`, `last_triggered_at`, `created_by`, `created_at`.

#### J. Sales Hierarchy
**sales_team_mapping** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `team_lead_name`, `team_lead_email`, `country_lead_id` (nullable → rolls up to default country lead), `is_default`, `created_at`, UNIQUE `(tenant_id, team_lead_email)`.

**sales_user_assignment** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `sales_user_id VARCHAR(100)`, `team_lead_id → sales_team_mapping NOT NULL`, `assigned_at`, UNIQUE `(tenant_id, sales_user_id)`.

**sales_country_lead** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `country_lead_name`, `country_lead_email`, `country_code VARCHAR(2)`, `is_default`, `created_at`, UNIQUE `(tenant_id, country_lead_email)`.

**sales_agent_profile** — `id BIGSERIAL PK`, `tenant_id NOT NULL`, `sales_user_id VARCHAR(100)`, `sales_email` (auto-populated from `dim_merchant`), `display_name`, `phone`, `country_code`, `hire_date`, `monthly_target DECIMAL(19,2)`, `status DEFAULT 'ACTIVE'`, `notes`, `created_at`, `updated_at`, UNIQUE `(tenant_id, sales_user_id)`. Reconciles rep CODE (`sales_user_id`) ↔ EMAIL (`sales_email`).

#### K. Security/Alerts/API feature migration (`V2__feature_security_alerts_api.sql`)
**alert_rule** — admin metric alert definitions: `rule_id`, `name`, `description`, `metric`, `operator`, `threshold`, `severity`, `recipients`, `is_active`, `check_frequency`, `scope`, `created_at`, `updated_at`. *(Columns inferred from `AlertController`; canonical DDL in V2 migration.)*

**alert_history** — triggered alerts: `alert_id`, `rule_name`, `severity`, `merchant_name`, `message`, `metric_value`, `acknowledged`, `acknowledged_by`, `acknowledged_at`, `triggered_at`.

**api_key** — external API keys: `key_id`, `name`, `key_hash` (BCrypt), `key_prefix`, `permissions` (JSON), `is_active`, `created_at`, `last_used`, `request_count`, `created_by`, `revoked_at`, `revoked_by`. Raw key shown once on create.

### 10.3 Table → Controller/Page Cross-Reference

| Table | Primary controller(s) | Page(s) |
|---|---|---|
| `dim_merchant` / `dim_store` / `dim_terminal` | MerchantController, StoreController, ReportFilterController | Merchant Hierarchy, filters everywhere |
| `dim_bank_account` | (merchant 360 / settlement) | Merchant detail |
| `fact_transaction` | TransactionController, MigrationController, data-bounds | Transactions, Data Migration |
| `sum_daily_bank` / `sum_monthly_bank` | BusinessController, ExecutiveDashboardController, FinanceController | Dashboards, Finance |
| `sum_daily_insight` | AnalyticsExplorerController, CrossFilterController, InsightController, MerchantController(compare), filtered KPIs | Interactive Explorer, Insight Hub, Comparison |
| `sum_daily_merchant` | DailyMerchantDashboardController, LeaderboardController, SalesPortfolioController, GroupAnalyticsController, AnalyticsController(heatmap) | Daily Dashboard, Leaderboard, Hierarchy, Group Reports, Heatmap |
| `sum_daily_scheme` / `sum_daily_channel` / `sum_daily_mcc` | AnalyticsController, FinanceController, TransactionTrendsHub | Trends, Finance profitability |
| `sum_daily_terminal` | ZeroTransactionController, GeoAnalyticsController | Zero-Txn, Geo heatmap |
| `sum_daily_finance` | FinanceController, BusinessAnalyticsController(debit-prepaid) | Finance, Debit/Prepaid |
| `merchant_activity_summary` | BusinessController, AnalyticsController | Dashboard KPIs |
| `merchant_opportunity_score` | BusinessController | Opportunity Intelligence |
| `revenue_leakage_flags` | RevenueLeakageController | Revenue Leakage |
| `stg_merchant_master_raw` / `stg_trnx_raw` | DataExplorerController, FileUploadController | Data Explorer, Upload |
| `users` / `user_tenant_access` / `user_region_access` | UserController, AdminController, AccessRequestController | User Mgmt, Security Settings |
| `sys_user_group` / `sys_menu` / `sys_group_menu` | RbacController, MenuController | Group Mgmt, sidebar |
| `tenant` / `region` | BankController, AdminController, TenantService | Bank Setup |
| `tenant_setting` | AdminController, S3SettingsController, SsoController | S3/SSO/Security/SMTP settings |
| `dashboard_config` | AdminController | Dashboard config |
| `audit_log` | AuditLogController, AuditService | Audit Logs |
| `access_request` | AccessRequestController, SsoController | Security Settings, SSO |
| `refresh_token` / `sso_state_token` / `password_history` / `password_reset_token` | AuthController, SsoController, AdminController, PasswordService | login/SSO/security |
| `email_queue` / `email_smtp_config` | EmailController, EmailSmtpController | Email Manager, SMTP Settings |
| `email_template_config` / `email_campaign` / `email_campaign_log` | EmailCampaignController | Email Campaigns |
| `integration_*` | IntegrationController | Integration Hub |
| `report_template` / `report_schedule` | ReportBuilderController | Report builder |
| `saved_filter` | SavedFilterController | Saved Views (all dashboards) |
| `explorer_master_item` / `explorer_alert` | AnalyticsExplorerController | Interactive Explorer governance |
| `alert_rule` / `alert_history` | AlertController | Alerts & Notifications |
| `api_key` | ApiKeyController | API Management |
| `sales_team_mapping` / `sales_user_assignment` / `sales_country_lead` / `sales_agent_profile` | SalesTeam/CountryLead/AgentProfile/Portfolio/Leaderboard controllers | Sales suite |
| `ai_chat_history` | AiAssistantController | AI Assistant |
| `batch_run_log` | BatchJobController | Batch Logs |

### 10.4 Row-Level Security, Partitioning & Seed Data

**RLS.** `get_current_tenant()` reads the `app.current_tenant` session var (set per request by `TenantAspect`/filter). Every business table has `ENABLE ROW LEVEL SECURITY` + `CREATE POLICY tenant_isolation_policy USING (tenant_id = get_current_tenant())`. This is a **defence-in-depth backstop** under the application-level tenant scoping described in §9.2 — both layers must agree. Tables with RLS marked *(RLS)* above include all `dim_*`, `fact_transaction`, every `sum_*`, the merchant-management tables, staging tables, `audit_log`, `tenant_setting`, `dashboard_config`, `merchant_daily_metrics`.

**Partitioning.** `fact_transaction` is **monthly** range-partitioned on `payment_date` (`PartitionMaintenanceService.ensurePartitionsForYear()` at startup; a `_default` partition catches strays). The partitioned summary tables (`sum_daily_merchant`, `sum_daily_scheme`, `sum_daily_channel`, `sum_daily_terminal`, `sum_daily_merchant_attribute`, `sum_daily_bank`, `sum_daily_finance`, `sum_daily_insight`) are **yearly** range-partitioned on `business_date` (`_y2024/_y2025/_y2026/_default`). Date predicates in queries enable partition pruning. `sum_daily_mcc`, `sum_monthly_bank`, `sum_monthly_card`, `sum_monthly_merchant_metrics`, `kpi_snapshot_*` are plain tables.

**Performance indexes.** Hot paths are indexed on `(tenant_id, business_date)` / `(tenant_id, payment_date)`; `fact_transaction` also on `(tenant_id, merchant_id, payment_date)`, `merchant_id`, `card_number`; dims on `(mid|sid|tid, tenant_id)`; staging on tenant + filter columns; `dim_merchant(tenant_id, sales_user_id) WHERE sales_user_id IS NOT NULL` for sales rollups; partial indexes on `email_queue(status,created_at) WHERE status='PENDING'`. The bulk batch-ingest index set lives in `db/migration/V2026_05_07_01__performance_indexes.sql` (idempotent, wired via `spring.sql.init.schema-locations`).

**Seed data (on fresh/dev DB).** 5 groups; full `sys_menu` registry (1:1 with routes) + group→menu grants; default tenant `BANK001` "Acquira Bank" (`ACQ`, Bahrain/BHD in the later seed block); `admin/{noop}password` ROLE_SUPER_ADMIN granted Super Admin on BANK001; `ref_country` (~200 rows w/ currency + `decimal_notation_value`); 17 `ref_card_scheme`; default Team Lead + Country Lead per tenant; per-tenant `tenant_setting` defaults (`sso_*`, `password_*`, `max_failed_logins=5`, `lockout_duration_minutes=15`, `leakage.*` thresholds); per-tenant default email templates (STATEMENT/WELCOME/ALERT). Note schema.sql contains two default-tenant seed blocks (UAE/AED then Bahrain/BHD), both `ON CONFLICT (institution_id) DO NOTHING`, so whichever runs first wins on a clean DB.

> **Schema-drift caution (recurring).** `CREATE TABLE IF NOT EXISTS` silently skips updated column definitions when a table already exists, and production runs `spring.sql.init.mode=never`. Any new column/constraint on a live DB needs an explicit `ALTER`-based migration (the `revenue_leakage_flags` enrichment columns + `uq_revenue_leakage_flag` were reconciled this way in `V2026_06_27_01__revenue_leakage_flags_reconcile.sql`). Staging tables are truncated each upload — never an analytics source.

---

*Companion document: `ACQUIRA_DEVELOPER_GUIDE.md` (infra, batch pipeline, schema DDL, indexes, deploy, ops runbook, 2026-05-08 audit changelog). For questions or corrections, edit this file and submit a PR.*
