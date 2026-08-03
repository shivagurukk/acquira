# Acquira — Multi-Tenancy Audit

**Date:** 2026-08-02
**Branch:** `deploy/kubernetes-aws`
**Scope:** every HTTP endpoint in `acquira-{core,batch,pdf,ai}`, every repository in `acquira-common`, every dynamic-SQL builder, and every frontend call site in `frontend/src`. The root `src/` tree and `migration/` were excluded (dead code, not in any Maven module).

---

## How tenancy works today

Three layers, only two of which are actually load-bearing:

1. **`JwtRequestFilter`** (`acquira-common/.../security/JwtRequestFilter.java`) resolves the tenant per request. It validates the `X-Tenant-Id` header against the caller's `UserTenantAccess` rows and returns **403 on mismatch** rather than silently falling back — correct. Result goes into a ThreadLocal `TenantContext`.
2. **Application-level `WHERE tenant_id = ?`** — the real isolation boundary.
3. **Postgres RLS** — policies exist on ~150 tables and `app.current_tenant` is stamped on every pooled connection (`TenantConnectionDataSourceConfig`). **But only 3 tables carry `FORCE ROW LEVEL SECURITY`.** Plain `ENABLE` does not apply to the table owner, which is how the app connects. *The RLS safety net is effectively inert.* Any missing `tenant_id` filter is a live leak, not a caught one.

`TenantContext.getVisibleTenants()` throws when unset (fail-loud) — good. `getCurrentTenant()` returns null, so callers must guard, and most do.

**Verdict: the codebase is in far better shape than the endpoint count suggests.** All four large dynamic-SQL builders implement a fail-closed `requireTenant()`. The explorer/report builders are column-whitelisted and pin `tenant_id` on the base table *and* every dimension join. Controllers E–R came back with zero unscoped endpoints. The defects are concentrated in a handful of places, listed below.

---

## Fixed in this pass

### 1. `AccessRequestController` — cross-tenant read + privilege grant (CRITICAL)

`acquira-core/.../controller/AccessRequestController.java`

All four endpoints ran with no tenant predicate at all, behind `hasAnyRole('ADMIN','SUPER_ADMIN')` — and `ADMIN` is a per-bank role, not a platform one.

- `listRequests` / `pendingCount` returned every tenant's pending requests: requester email, display name, SSO provider, and (via the enrichment loop) other banks' names.
- `approveRequest` took `tenantId` from the **request body** and passed it straight to `tenantRepository.findById` to build a `UserTenantAccess` row. A bank admin could approve any request and grant the new account access to **any tenant on the platform**. The equivalent guard already existed in `AdminController.createUser` but had never been copied here.
- `rejectRequest` had no ownership check on `findById`.

Added `canAccessRequest()` (super-admin unrestricted, otherwise must match the active tenant), applied it to all four handlers, and added the body-`tenantId` guard to `approveRequest`. Foreign ids now 404 rather than 403 so request ids can't be probed.

### 2. Role escalation — the tenant-isolation master key (CRITICAL)

`UserController.createUser` / `updateUser`, `AdminController.createUser`

`users.role` is not a UI hint: `JwtRequestFilter:137` reads it and, when it equals `ROLE_SUPER_ADMIN`, **skips the `UserTenantAccess` check on `X-Tenant-Id` entirely**. All three endpoints bound the role straight from the request body with no authorization check — `updateUser` even carried a comment claiming a super-admin check that was never written.

A bank admin could set `role = 'ROLE_SUPER_ADMIN'` on an account in their own tenant (or on themselves — `canActOnUser` passes), then read every other bank's data through endpoints that are individually correctly scoped. Note this needs no change to the Spring authorities, so `@PreAuthorize` gates offer no protection.

Added `mayAssignRole()`; only a super admin may assign the super-admin role.

### 3. `MigrationController.getProgress` — missing authorization (HIGH)

`acquira-batch/.../controller/MigrationController.java:99`

The only method in the controller without `@PreAuthorize("hasRole('SUPER_ADMIN')")`; `/start` and `/delete-day` both have it with a comment explaining why. `BulkMigrationService` keeps one **global** progress object, so any bank admin could poll the target tenant, month and row counts of a super-admin migration. Added the missing annotation.

### 4. `DataBoundsService` — fail-open on missing tenant (MEDIUM)

`acquira-common/.../service/DataBoundsService.java`

The only query in the codebase that **widened** on missing context: when `tenantId` was null it dropped the `WHERE` clause entirely, returned platform-wide `MIN/MAX(payment_date)`, and cached the result under the shared key `'all'`. Every peer (`VolumeRevenueRepository.requireTenant`, `DestinationDashboardRepository`) throws instead. Now fails closed, and the cache key no longer has an `'all'` bucket.

### 5. `TenantService.getAllowedTenants` — tenant roster disclosure (MEDIUM)

`acquira-core/.../service/TenantService.java:40`

Returned `tenantRepository.findAll()` for `ROLE_ADMIN` as well as `ROLE_SUPER_ADMIN`, leaking every tenant's bankName / institutionId / country / currency through `/api/auth/login`, `/api/auth/session` and `/api/banks` — and silently defeating `BankController`'s own super-admin guard, which delegates to this method. Restricted to `ROLE_SUPER_ADMIN`. Not escalatable to data access (the JWT filter still blocks the switch), but it was disclosure to every bank admin.

### 6. `SavedFilterController` — owner-checked but not tenant-checked (LOW)

`updateView` / `deleteView` / `setDefault` used `findByIdAndUserId`, proving ownership but not tenancy. A user with access to tenants A and B, acting in A, could edit, delete or re-share their own filter belonging to B — including flipping `isShared`, which republishes it to every user of B. Added `findOwnFilterInTenant()`.

### 7. Frontend — calls bypassing the tenant header

The shared axios client (`frontend/src/api/axios.js`) attaches `X-Tenant-Id` automatically; several call sites used bare `axios` or raw `fetch` instead and so silently hit the user's **default** tenant after a switch.

- **`hooks/useExcelExport.js`** — used the bare `axios` library. Highest impact: a file download, shared by `TransactionList`, `MerchantUniverse` and `FinanceLists`. In `FinanceLists` the on-screen table used the shared client while its own Export button did not, so the two could disagree about which tenant they were showing.
- **`pages/MerchantUniverse.jsx`** — the bulk **upload** (`/api/upload/*`), the job-status poller, and the merchant-360 fetch. The upload is the only *write* gap found anywhere in the frontend: merchant and transaction rows were ingested into the default tenant, silently, and awkward to undo once summaries are built.

All migrated to the shared client (which also gains them 401-refresh handling).

**Verified:** `mvn compile` on `acquira-common,acquira-core,acquira-batch` and `npm run build` both pass.

---

## Open — recommended next, not yet fixed

These need either a design decision or a wider refactor than a security patch should carry.

### A. `AiQueryService` subquery gap (HIGH)

`acquira-ai/.../service/AiQueryService.java:571`, `:660`

The NL-to-SQL guard is genuinely strong — statement whitelist, table whitelist, no comma joins, read-only transaction, row cap. But the tenant check is satisfied by **one** predicate anywhere in the statement: `ensureTenant` skips injection when `tenant_id = <caller>` matches anywhere, and `validateTenantPredicates` returns as soon as `callerPredicateSeen` is true. So a scalar subquery over a whitelisted summary table with no predicate of its own passes every check:

```sql
SELECT (SELECT SUM(total_volume) FROM sum_daily_bank) AS x
FROM dim_merchant WHERE tenant_id = <caller> LIMIT 1
```

Same for `IN (SELECT …)`. Forced RLS would close this; so would requiring a tenant predicate per FROM/JOIN target rather than per statement. **Prefer the latter** — don't make this the one thing depending on RLS.

### B. `PdfController` shared-folder fallback (HIGH)

`acquira-pdf/.../controller/PdfController.java:94`

`monthFolder(ym, null)` falls through to the legacy mixed-tenant `reports/{YYYY-MM}` root when the tenant is null — which the comment at `:1128` explicitly claims is no longer reachable. Affects `checkReportStatus`, `listReports`, `downloadReport` and `downloadAllReports`; the last would zip the folder wholesale. `ExternalReportApiController:82-89` got this right by returning null instead. Also `generateReport` (`:1096`) is missing the null-tenant fail-closed check its siblings at `:147` and `:1048` have, and a null tenant makes `CoreServiceClient.fetchInsights` skip the merchant-ownership check.

### C. Merchant-id-keyed finders with no tenant column (HIGH latent)

13 methods across `SumDailyMerchantRepository` (lines 135, 141, 147, 152, 159, 181, 243, 264), `SumDailyMerchantAttributeRepository` (16, 22, 30) and `SumMonthlyCardRepository` (16, 23) return a merchant's full financial and card detail keyed only on `merchant_id` — which is a **global sequence**, unique but not tenant-unique.

Nothing at the DB layer stops a wrong id returning another tenant's financials. Every current caller is correctly guarded (`MerchantInsightService.getInsights` throws `SecurityException` on a foreign merchant), **but** the 3-arg overload at `MerchantInsightService:256` passes `null` for `expectedTenantId`, and `getBulkInsights` takes no tenant argument at all. This is the largest latent risk in the codebase: one careless new caller from a full cross-tenant statement leak. Add `AND tenantId = :tenantId` to all 13 and thread the parameter through.

The same shape, lower blast radius: `StoreRepository.findByMerchantId`, `TerminalRepository.findByStoreId(In)`, `MerchantContact/Document/RiskProfileRepository.findByMerchantId`. All six callers are guarded by `findOwnMerchant`, but the guard lives in the controller, not the query, and `MerchantController` alone has seven call sites relying on it.

### D. Decide on forced RLS (ARCHITECTURAL)

RLS is currently decorative. Two things block turning it on:

- `TenantAspect` only sets `app.current_tenant` when `getCurrentTenant() != null`, and **every** batch/async/scheduled path runs with a null context. Forcing RLS would break ingestion.
- `set_config(..., false)` is session-scoped on pooled connections, so a batch thread can inherit the previous borrower's tenant. Harmless while RLS is off; incorrect once it's on.

Worth doing — it would independently close A and C — but it is a project, not a patch.

### E. Smaller items

| Item | Location | Note |
|---|---|---|
| `UserController` leaks user↔tenant assignments | `:358`, `:513`, `:555` | `accessRepository.findAllByUser` isn't filtered to the caller's scope; a bank admin learns which other banks a shared user works for |
| Cross-tenant `isDefaultTenant` write | `UserController:404-409`, `:453-458` | Setting a default in tenant A clears the flag on the user's rows in B, C… |
| Unauthenticated SSO config read | `SsoController:121` + `:198` | `/api/sso/**` is `permitAll`; `?tenantId=N` returns tenant N's Entra client_id / IdP tenant / redirect URI. Secret not emitted. Semi-public OAuth values, but it's an unauthenticated per-tenant enumerator |
| Tenant roster to any MS account | `SsoController:374-378` | `not_registered` branch returns every tenant id + bank name |
| `BulkMigrationService.dryRun` | `:722`, `:759-789` | Reads an arbitrary table with no tenant predicate; `validateColumnMapping` is only called from `startMigration`, so column names reach SQL unvalidated. SUPER_ADMIN-gated |
| `ExternalReportApiController.downloadByMid` | `:264-265` | `scopeTenantId == null ||` is a fail-open default; unreachable today, wrong default. Also loads all of `dim_merchant` per request |
| `IntegrationController.getOverview` | `:495` | `activeSchedules` is a global cross-tenant count |
| `MerchantReportJobConfig.merchantReader` | `:119` | `findAll()` across all tenants; safe only because nothing launches the job |
| `IntegrationScheduleRepository.findByIsEnabledTrue` | `DynamicSchedulerService:44` | The only one of three schedulers that doesn't set `TenantContext` around execution |
| `MerchantController./hierarchy` | `:350-376`, `:404-439` | Child subqueries unguarded; safe only because ids are global sequences |
| Dead frontend files | `BankManagement.jsx`, `CombinedViewSwitcher.jsx` | Unreferenced, no auth header at all. `CombinedViewSwitcher` also mutates `axios.defaults` and invents an `X-Tenant-Ids` header nothing else sends. Delete rather than fix |
| Legacy `tenantId` localStorage key | `MerchantUniverse.jsx` | Second source of truth beside `defaultTenantId`; goes stale on sidebar switches. Latent |
| JWT in SSE query string | `BatchMonitoring.jsx:44` | `EventSource` can't send headers so tenant goes as a query param (fine), but so does the raw JWT — lands in access logs |

---

## Clean

For the record, these were traced end-to-end and are consistently scoped — base-table predicate, tenant pushed onto every dimension join, ownership checks before update/delete, and `X-Tenant-Id` request params deliberately discarded in favour of `TenantContext`:

**Controllers:** Alert, Analytics, AnalyticsExplorer, ApiKey, AuditLog, BudgetTarget, Business, BusinessAnalytics, ChurnRisk, CrossFilter, DailyMerchantDashboard, DataExplorer, DestinationDashboard, EmailCampaign, Email, EmailSmtp, ExecutiveDashboard, ExternalDataApi, Finance, Forecast, GeoAnalytics, GroupAnalytics, Insight, Leaderboard, Menu, Merchant, MerchantSegment, ReportBuilder, ReportFilter, RevenueKpi, RevenueLeakage, S3Settings, SalesAgentProfile, SalesCountryLead, SalesPortfolio, SalesTeam, Store, TopPerformers, Transaction, Trends, ZeroTransaction.

**Batch:** all upload/job/progress endpoints, `IntegrationController` (19 endpoints, including cross-object ownership re-checks), both Spring Batch job configs — worker threads correctly take tenant from `@StepScope` job parameters rather than the ThreadLocal.

**API keys:** binding is sound. Keys carry `tenant_id`, tenant is resolved *from the key*, a client-supplied `tenantCode` may only match and never widen, and the all-tenant break-glass key is off by default.

**`@Modifying` queries:** only four exist; none is an unguarded cross-tenant write.

**Frontend:** ~268 of ~294 call sites use the shared client. `apiCache.js` is correctly keyed `${tenantId}|${url}|${params}` and invalidated on login, switch and logout.

Nine call sites attach `X-Tenant-Id` by hand rather than via the interceptor (`MerchantHierarchy`, `MerchantSummary`, `TransactionList`, three `business/` pages). All correct today, but fragile — and the three in `MerchantHierarchy` send the header unconditionally, so they transmit the literal string `"null"` when it's unset, which the shared interceptor explicitly guards against.
