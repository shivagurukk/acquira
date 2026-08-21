# Acquira — End-to-End Developer Guide

**Audience:** Developers joining the project / handover documentation
**Last updated:** 2026-08-07 (post-audit refresh — see Appendix E for everything added since the 2026-05-08 audit pass)
**Stack:** Java 21, Spring Boot 3.2, Spring Batch 5.1, PostgreSQL (RDS), React 19 + Vite + MUI, Playwright (PDF rendering), deployed on AWS EC2 + RDS (same AZ)

> **Recent audit pass (2026-05-08):** A code audit was applied that fixed
> several P0/P1/P2 issues across security, tenancy, and batch reliability.
> Highlights: tenant-scoped lookup in reporting (was a silent cross-tenant
> overwrite), symlink-aware path validation on server-folder uploads,
> propagation NEVER on the dashboard-metrics step (was the last unhardened
> step), `set_config()` instead of `SET LOCAL` in TenantAspect, generic
> auth-failure response, super-admin visibleTenants populated, MTD/YTD
> subqueries tenant-scoped, per-(IP, username) lockout. Full breakdown
> in Appendix D. Throughout this document, recently-changed behavior
> is tagged with the audit ID like `[P0-3]`, `[P1-2]`, etc.

---

## Table of Contents

1. [System Architecture](#1-system-architecture)
2. [Module Layout](#2-module-layout)
3. [Local Dev Setup](#3-local-dev-setup)
4. [Database Schema — All Tables](#4-database-schema--all-tables)
5. [Indexes (production)](#5-indexes-production)
6. [Authentication & RBAC](#6-authentication--rbac)
7. [Tenant Isolation](#7-tenant-isolation)
8. [File Upload Pipeline (end-to-end)](#8-file-upload-pipeline-end-to-end)
9. [Batch Job: Merchant Master](#9-batch-job-merchant-master)
10. [Batch Job: Transaction Load](#10-batch-job-transaction-load)
11. [Reporting / Manual Ingestion](#11-reporting--manual-ingestion)
12. [Dashboards & Reads](#12-dashboards--reads)
13. [PDF Reports](#13-pdf-reports)
14. [Email & Campaigns](#14-email--campaigns)
15. [Integration Hub](#15-integration-hub)
16. [Configuration Files](#16-configuration-files)
17. [Build & Deploy](#17-build--deploy)
18. [Operations Runbook](#18-operations-runbook)
19. [Troubleshooting](#19-troubleshooting)
20. [Performance Notes](#20-performance-notes)
21. [Business Analytics Reports & the Monthly Pre-Aggregate Layer](#21-business-analytics-reports--the-monthly-pre-aggregate-layer)
22. [Appendix D — Audit Pass Changelog (2026-05-08)](#appendix-d-audit-pass-changelog-2026-05-08)
23. [Appendix E — Changes Since the Audit (2026-05-08 → 2026-08-07)](#appendix-e-changes-since-the-audit-2026-05-08--2026-08-07)

---

## 1. System Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                      User's Browser                                  │
│  React + Vite + MUI (acquira-frontend, served by nginx or vite dev)  │
└──────────────────────────────┬───────────────────────────────────────┘
                               │  HTTPS (REST + JWT)
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│           EC2 Instance — Spring Boot Application (port 8081)         │
│                                                                      │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────┐  │
│  │  acquira-core   │  │ acquira-batch   │  │   acquira-pdf        │  │
│  │  (REST APIs,    │  │ (Spring Batch   │  │   (PDF generation,   │  │
│  │   dashboards,   │  │  jobs, file     │  │   chart rendering)   │  │
│  │   security)     │  │  upload)        │  │                      │  │
│  └─────────────────┘  └─────────────────┘  └──────────────────────┘  │
│                                                                      │
│  ┌─────────────────┐  ┌─────────────────────────────────────────┐    │
│  │  acquira-ai     │  │   acquira-common                        │    │
│  │ (chat, RAG,     │  │   (entities, repositories, services,    │    │
│  │  optional)      │  │    DTOs shared by all modules)          │    │
│  └─────────────────┘  └─────────────────────────────────────────┘    │
└────────────────────────┬─────────────────────────────────────────────┘
                         │  JDBC (HikariCP, sub-ms latency, same AZ)
                         ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    AWS RDS — PostgreSQL (same AZ as EC2)             │
│                                                                      │
│  • Multi-tenant (tenant_id column on every table)                    │
│  • Time-partitioned: fact_transaction by month                       │
│  • Reference data: ref_country, ref_card_scheme                      │
│  • Spring Batch metadata tables (batch_job_*, batch_step_*)          │
└──────────────────────────────────────────────────────────────────────┘
```

**Deployment topology:**
- One EC2 instance runs the single Spring Boot fat JAR (`acquira-core`).
- All modules build into that one JAR.
- RDS PostgreSQL in the same Availability Zone (sub-ms latency).
- Frontend is built by Vite (`npm run build`) and served as static files (nginx, S3+CloudFront, or Spring Boot static).

---

## 2. Module Layout

```
Acquira/
├── pom.xml                          # parent POM
├── acquira-common/                  # shared entities, repos, services
├── acquira-core/                    # REST controllers, security, dashboards
│   └── src/main/resources/
│       ├── schema.sql               # full DB schema (dev mode auto-runs)
│       ├── application.properties   # dev config
│       └── db/migration/            # production migration scripts
├── acquira-batch/                   # Spring Batch jobs, file upload
│   └── src/main/java/com/acquira/batch/
│       ├── job/                     # Job & Step definitions
│       │   ├── TransactionJobConfig.java
│       │   ├── MerchantMasterJobConfig.java
│       │   └── ...
│       ├── service/
│       │   ├── FileUploadService.java
│       │   ├── ManualIngestionService.java
│       │   └── PartitionMaintenanceService.java
│       └── controller/
│           ├── FileUploadController.java
│           └── BatchJobController.java
├── acquira-pdf/                     # PDF reports (multi-page)
├── acquira-ai/                      # optional, AI chat integration
└── frontend/                        # React + Vite + MUI
    ├── src/
    │   ├── pages/                   # Dashboards, screens
    │   ├── components/              # Shared UI
    │   ├── api/                     # axios clients
    │   └── App.jsx                  # routes + RBAC menu
    └── vite.config.js
```

**Why multi-module?** Each module can be tested in isolation; `acquira-common` prevents circular dependencies between core and batch.

---

## 3. Local Dev Setup

### Prerequisites

- JDK 21
- Maven 3.9+
- Node 20+ (for frontend)
- PostgreSQL 14+ (local), or Docker:
  ```bash
  docker run -d --name acquira-pg -p 5432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=acquira postgres:14
  ```

### Backend

```cmd
cd C:\Users\sivag\Desktop\cms\Acquira
mvn clean install -DskipTests -T 2C
cd acquira-core
mvn spring-boot:run
```

The first run auto-creates the schema from `acquira-core/src/main/resources/schema.sql` (because `spring.sql.init.mode=always` in dev).

Default dev port: **8081**

### Frontend

```cmd
cd C:\Users\sivag\Desktop\cms\Acquira\frontend
npm install
npm run dev
```

Opens at `http://localhost:5173` (Vite default), proxies `/api/*` to `http://localhost:8081`.

### Default users seeded by schema.sql

| Username | Password | Group |
|---|---|---|
| `superadmin` | (see schema.sql / DatabaseFixer) | Super Admin |
| `sivag` | `ITAcquiring` | Bank Admin (tenant_id=1) |

---

## 4. Database Schema — All Tables

Tables are grouped by purpose. Full DDL is in `acquira-core/src/main/resources/schema.sql`.

### 4.1 Reference Data (single-row global)

| Table | Purpose | Key columns |
|---|---|---|
| `ref_country` | ISO country + currency + decimal scaling | `country_code` (PK), `currency_code`, `iso_numeric`, `decimal_notation_value` |
| `ref_card_scheme` | Card scheme (VISA, MCRD, …) → card_type mapping | `id` (PK), `code` (UQ), `card_type`, `card_subtype` |

`decimal_notation_value` is critical: BHD = 1000, USD = 100. Raw amounts in transaction CSVs are integers and are divided by this.

### 4.2 Tenancy & RBAC

| Table | Purpose | Key columns |
|---|---|---|
| `tenant` | Bank / institution | `tenant_id` (PK), `bank_short_code`, `institution_id`, `name` |
| `region` | Optional grouping for tenants | `region_id` (PK), `region_name` |
| `users` | Application users | `user_id` (PK), `username` (UQ), `password` (BCrypt), `email`, `display_name`, `approval_status` |
| `sys_user_group` | Groups: Super Admin / Bank Admin / Business User / Finance User / Ops User | `group_id` (PK), `group_name` (UQ) |
| `sys_menu` | Menu registry — single source of truth | `menu_id` (PK), `path` (UQ), `category`, `display_order`, `icon_key` |
| `sys_group_menu` | Which menus a group can see | `(group_id, menu_id)` composite PK |
| `user_tenant_access` | User × Tenant × Group mapping (a user can belong to many tenants) | `user_id`, `tenant_id`, `group_id` |
| `user_region_access` | User → Region mapping (reporting scope) | `user_id`, `region_id` |
| `audit_log` | All sensitive operations | `id` (PK), `tenant_id`, `user_id`, `action`, `details`, `created_at` |

### 4.3 Dimension tables (master data)

| Table | Purpose | Key columns |
|---|---|---|
| `dim_aggregator` | Top-of-tree aggregator | `aggregator_id` (PK), `tenant_id`, `internal_id` |
| `dim_merchant` | Merchant master | `merchant_id` (PK), `tenant_id`, `internal_id` (UQ with tenant), `mid`, `name`, `mcc`, `sales_user_id`, `status` |
| `dim_store` | Store under merchant | `store_id` (PK), `tenant_id`, `merchant_id` (FK), `internal_id`, `sid`, `name`, `mcc`, `country_code` |
| `dim_terminal` | Terminal under store | `terminal_id` (PK), `tenant_id`, `store_id` (FK), `internal_id`, `tid`, `type` (POS/ECOM/MOTO) |
| `dim_bank_account` | Settlement account | `bank_account_id` (PK), `merchant_id`, `iban`, `currency_code` |

**Resolution chain in transaction load:** `stg.sid → dim_store.sid → dim_store.merchant_id → dim_merchant`. If SID is corrupted (Excel scientific notation), fallback to `stg.tid → dim_terminal.tid → dim_terminal.store_id → dim_store`.

### 4.4 Merchant lifecycle / business

| Table | Purpose |
|---|---|
| `merchant_lifecycle_status` | ONBOARDED → ACTIVE → DORMANT → CHURNED transitions |
| `merchant_activity_summary` | Per-merchant per-date snapshot: last_7d_cnt/value, last_30d_cnt/value, status |
| `merchant_opportunity_score` | Score (0-100) + reason tags, recalculated daily |
| `revenue_leakage_flags` | Detected anomalies (drop in volume, etc.) |
| `merchant_contact` | Contacts for a merchant |
| `merchant_document` | Uploaded merchant docs (PDFs etc.) |
| `merchant_note` | Free-text notes |
| `merchant_risk_profile` | Risk scoring |
| `merchant_settlement_config` | Settlement schedule, MSF rates |
| `merchant_contract` | Active contracts |
| `merchant_activity` | Activity log (calls, meetings) |

### 4.5 Staging tables (raw upload data)

| Table | Purpose |
|---|---|
| `stg_merchant_master_raw` | Raw rows from merchant master file uploads |
| `stg_trnx_raw` | Raw rows from transaction file uploads — emptied per upload |

`stg_trnx_raw` is wide (33 columns) and matches the CSV format provided by the bank. It's truncated at the start of each upload via `cleanTargetDayStep`.

### 4.6 Fact table — partitioned

| Table | Purpose |
|---|---|
| `fact_transaction` | One row per transaction — partitioned by `payment_date` (monthly). Has `tenant_id`, `merchant_id`, `store_id`, `terminal_id`, amounts, MSF, VAT, interchange. |

Partitions: `fact_transaction_y2025m01`, `fact_transaction_y2026m02`, etc. Created by `PartitionMaintenanceService.ensurePartitionsForCurrentAndNextYear()` on every job start.

### 4.7 Summary tables (aggregations)

These are pre-aggregated for dashboard speed. All have `(tenant_id, business_date, …)` as the conflict key. ON CONFLICT UPDATE keeps re-uploads idempotent.

| Table | Grain | Purpose |
|---|---|---|
| `sum_daily_bank` | per (tenant, date) | Bank-level totals: txns, volume, MSF, interchange, VAT, net revenue |
| `sum_daily_merchant` | per (tenant, date, merchant) | Merchant-level dashboard data, top spending customer |
| `sum_daily_merchant_attribute` | per (tenant, merchant, date, attribute_type, attribute_value) | Generic key-value: card_scheme, card_type, destination, transaction_type, hour, txn_size_bucket |
| `sum_daily_terminal` | per (tenant, date, merchant, store, terminal) | Terminal-level totals |
| `sum_daily_finance` | per (tenant, date) | Finance dashboard: domestic/international × debit/credit splits |
| `sum_daily_insight` | per (tenant, date, merchant, store, terminal, scheme, type, destination, channel, opt_in) | Cross-tab for analytics explorer. **Partitioned by year** (`sum_daily_insight_y2024`, `_y2025`, `_default`) since the 2026-06 pre-aggregate work. |
| `sum_monthly_insight` | per (tenant, `month_key` YYYYMM, merchant, store, terminal, scheme, type, destination, channel, opt_in) | **Month-grain pre-aggregate of `sum_daily_insight`** (~30× fewer rows). Added by `V2026_06_29_02`. Month-grained reads route here — see §21. All measures are additive SUMs so monthly = SUM(daily) reconciles exactly for whole months. |
| `sum_daily_scheme` | per (tenant, date, scheme) | VISA/MCRD/AMEX volumes |
| `sum_daily_channel` | per (tenant, date, channel) | POS / ECOM / MOTO volumes |
| `sum_daily_mcc` | per (tenant, date, mcc, scheme) | Industry breakdown |
| `sum_monthly_bank` | per (tenant, month_key) | Bank monthly rollup (rolled from sum_daily_bank) |
| `sum_monthly_card` | per (tenant, merchant, month, card_number) | Loyalty / repeat-customer detection |
| `sum_monthly_merchant_metrics` | per (tenant, merchant, month) | Calculated by Java — uniques, growth %, etc. |
| `merchant_daily_metrics` | per (tenant, merchant, date) | Reporting metrics (filled by ManualIngestionService) |
| `kpi_snapshot_daily` | per (tenant, date) | Top-line KPIs |
| `kpi_snapshot_monthly` | per (tenant, month_key) | Monthly KPIs |

### 4.8 Reports & Templates

| Table | Purpose |
|---|---|
| `report_template` | User-defined report templates (Report Builder) |
| `report_schedule` | Scheduled report runs |
| `report_run_log` | Execution history of report runs |
| `report_query_config` | Saved analytic queries |
| `data_source_config` | External datasource definitions |
| `pdf_batch_log` | PDF generation log |
| `saved_filter` | User-saved dashboard filter configs |
| `dashboard_config` | Per-tenant dashboard layout config |

### 4.9 Email & Campaigns

| Table | Purpose |
|---|---|
| `email_template_config` | Reusable email templates. `template_type` values: `STATEMENT`, `WELCOME`, `ALERT`, `PROMOTION`, `CUSTOM`, and **`REPORT_PDF`** (the covering email for the monthly PDF report batch — resolved per tenant via `isDefaultForType`, see §14.4). Unique on `(tenant_id, name)`; one default per `(tenant_id, template_type)` enforced by migration. |
| `email_campaign` | Campaign definition (target merchants + template) |
| `email_campaign_log` | Per-recipient send log |
| `email_queue` | Pending emails waiting on SMTP. Columns `merchant_id`, `merchant_name`, `is_html`, `statement_month`, `attachment_path` added by `V2026_07_10_04` — the Email Manager stats page filters on `statement_month`. Drained by `EmailQueueProcessor` (§14.3). |
| `email_smtp_config` | **Per-tenant SMTP server config.** Password AES-256-GCM encrypted at rest via `CryptoService`, decrypted only in-memory when building a `JavaMailSender`, and never returned to API clients (masked onto a detached copy — mutating the managed entity caused the 2026-07 "test works before save, 535 after" bug). Single active config per tenant. |

### 4.10 Integration Hub

| Table | Purpose |
|---|---|
| `integration_connection` | External DB connections (read-only data sources) |
| `integration_report` | A query template against a connection |
| `integration_schedule` | Cron schedule for an integration_report |
| `integration_run_log` | Execution history |

### 4.11 Sales Team

| Table | Purpose |
|---|---|
| `sales_team_mapping` | Sales team hierarchy |
| `sales_user_assignment` | Maps merchants to sales users |

### 4.12 Spring Batch metadata (auto-created)

`batch_job_instance`, `batch_job_execution`, `batch_job_execution_params`, `batch_job_execution_context`, `batch_step_execution`, `batch_step_execution_context`. Created by `spring.batch.jdbc.initialize-schema=always` on first run, then set to `never`.

### 4.13 Tenant settings

| Table | Purpose |
|---|---|
| `tenant_setting` | Per-tenant key-value config (logo path, currency display, locale) |

---

## 5. Indexes (production)

Critical indexes that must exist on prod RDS — created via `acquira-core/src/main/resources/db/migration/V2026_05_07_01__performance_indexes.sql`.

### 5.1 Dimension lookups (used in batch joins)

| Index | Table | Columns |
|---|---|---|
| `idx_dim_store_tenant_sid` | dim_store | `(tenant_id, sid) WHERE sid IS NOT NULL` |
| `idx_dim_merchant_tenant_mid` | dim_merchant | `(tenant_id, mid) WHERE mid IS NOT NULL` |
| `idx_dim_terminal_tenant_tid` | dim_terminal | `(tenant_id, tid) WHERE tid IS NOT NULL` |
| `idx_dim_terminal_tenant_store` | dim_terminal | `(tenant_id, store_id)` |

### 5.2 Staging table lookups

| Index | Columns |
|---|---|
| `idx_stg_trnx_tenant_paydate` | `(tenant_id, payment_date)` |
| `idx_stg_trnx_tenant_sid` | `(tenant_id, sid) WHERE sid IS NOT NULL` |
| `idx_stg_trnx_tenant_mid` | `(tenant_id, mid) WHERE mid IS NOT NULL` |
| `idx_stg_trnx_tenant_tid` | `(tenant_id, tid) WHERE tid IS NOT NULL` |
| `idx_stg_trnx_tenant_trim_sid` | `(tenant_id, TRIM(sid))` — functional index |
| `idx_stg_trnx_tenant_trim_mid` | `(tenant_id, TRIM(mid))` |
| `idx_stg_trnx_tenant_trim_tid` | `(tenant_id, TRIM(tid))` |
| `idx_stg_merchant_tenant_loadtime` | `(tenant_id, load_time)` on `stg_merchant_master_raw` |

### 5.3 Fact + summary

| Index | Table | Columns |
|---|---|---|
| `idx_fact_transaction_tenant_date_merchant` | fact_transaction | `(tenant_id, payment_date, merchant_id)` |
| `idx_sum_daily_merchant_tenant_date` | sum_daily_merchant | `(tenant_id, business_date)` |
| `idx_sum_*_tenant_date` | each summary table | `(tenant_id, business_date)` |

### 5.4 Maintenance after creating

```sql
ANALYZE stg_trnx_raw;
ANALYZE dim_store; ANALYZE dim_merchant; ANALYZE dim_terminal;
ANALYZE fact_transaction;
ANALYZE sum_daily_merchant; ANALYZE sum_daily_bank;
ANALYZE sum_daily_insight; ANALYZE sum_daily_terminal;
ANALYZE sum_daily_finance; ANALYZE sum_daily_scheme;
ANALYZE sum_daily_channel; ANALYZE sum_daily_mcc;
ANALYZE sum_daily_merchant_attribute;
ANALYZE sum_monthly_card; ANALYZE sum_monthly_bank;
```

ANALYZE is **mandatory** after creating new indexes; without it the planner may still pick seq scans.

---

## 6. Authentication & RBAC

### 6.1 Login flow

```
POST /api/auth/login  { username, password }
  └─> AuthController.login()
      └─> AuthenticationManager (Spring Security)
          └─> UserDetailsServiceImpl loads user + their groups
      └─> JwtTokenProvider.generateToken(user, tenants[])
      └─> returns JWT with claims: {sub: username, tenants: [...], groups: [...]}
```

**[P1-5] Generic auth-failure response.** The endpoint returns a single
`401 {"error":"Invalid username or password"}` for every kind of failure
(bad credentials, account inactive, account pending admin approval). The
specific reason is captured in `audit_log` only. This prevents username
enumeration via differential responses. The exception is **account
lockout** — that still returns `423` with a generic "try again later"
message (no `lockedUntil` timestamp leaked) because the user needs to
know why a correct password isn't working.

**[P2-7] Lockout key is `(client_ip, username)`, not `client_ip` alone.**
Previously a single typo from a corporate NAT could throttle the whole
office. Now one user's failures only affect that user's logins from that
IP. The in-memory bucket key is built as `ip + "|" + username.toLowerCase()`.

**[P1-4] Lockout counter resets when lockout expires.** Previously, after
the 15-min lockout window passed, `failed_login_attempts` was still 5.
One more typo would re-lock immediately. Now the expiry is checked on
the next attempt and the counter zeroed before authentication runs.

### 6.2 Tenant selection

If the user has access to multiple tenants, frontend shows a tenant picker. The selected `tenantId` is sent in every subsequent request as the `X-Tenant-ID` header. Server-side, `TenantContext` is a ThreadLocal that holds the current tenant for the request lifecycle.

### 6.3 Menu loading

```
GET /api/users/me/menu
  └─> MenuController.getMenusForCurrentUser()
      └─> reads sys_group_menu × sys_menu for user's group
      └─> returns flat array of menu items grouped by category
      └─> frontend renders sidebar
```

The frontend `App.jsx` does NOT hardcode menus — it reads them from this endpoint. Adding a new screen = adding a row to `sys_menu` + granting it via `sys_group_menu`.

### 6.4 Authorization

Method-level: `@PreAuthorize("hasAuthority('SUPER_ADMIN')")` on sensitive endpoints.
Tenant-level: every query is filtered by `tenant_id = TenantContext.getCurrentTenant()` automatically by the repos.

---

## 7. Tenant Isolation

**Every business table has a `tenant_id` column.** Repositories take `tenantId` as the first param on every method:

```java
List<Merchant> findByTenantIdAndStatus(Long tenantId, String status);
```

There's a Postgres function `get_current_tenant()` that reads from a session variable, but in practice we don't use Postgres RLS — we filter at the JPA/JDBC layer. Reason: we control all DB access, and explicit filtering is easier to debug.

A super admin can switch tenants via the tenant picker in the UI; a bank admin is locked to their tenant.

### 7.1 TenantAspect — sets `app.current_tenant` per request

`TenantAspect` is an AOP `@Around` advice that runs before every service or
repository call and pushes the current tenant ID into the DB session via
the GUC `app.current_tenant`. This GUC is what `get_current_tenant()` reads
when RLS is consulted.

**[P1-2] Implementation uses `set_config('app.current_tenant', ?, false)`,
NOT `SET LOCAL`.** `SET LOCAL` only takes effect inside an open transaction;
PostgreSQL silently no-ops it (with a WARNING) when run in autocommit mode.
That mattered because the long-running batch tasklets explicitly opt out of
transactions via `propagation NEVER`. The previous `SET LOCAL` was a no-op
for the entire batch pipeline. With `set_config(..., false)` the value is
session-scoped and works whether or not a transaction is open.

The aspect also no longer caches `lastSetTenant` per ThreadLocal. The
setting is per-CONNECTION, and Hikari recycles connections back to the
pool — so a thread might pick up a different connection on the next call
that has stale or no tenant context. We now set on every aspect invocation;
the cost is a sub-millisecond local round-trip, dwarfed by the actual query.

### 7.2 TenantContext — single tenant + visible tenants

Two ThreadLocals:
- `currentTenant` — single tenant for WRITES (used by every repo)
- `visibleTenants` — list of tenants for READS (cross-tenant rollups)

**[P2-4] Super admin visibleTenants is now populated.** When `JwtRequestFilter`
sees `ROLE_SUPER_ADMIN`, it calls `tenantRepository.findAll()` and pushes
the resulting tenant IDs into `setVisibleTenants(...)`. Previously this was
a TODO — every cross-tenant rollup endpoint silently scoped to a single
tenant for SA users.

**[P2-5] `getVisibleTenants()` throws when nothing is set.** If neither
`currentTenant` nor `visibleTenants` is populated (e.g. an `@Async` method
that didn't propagate context, or a `@Scheduled` job), the method now
throws `IllegalStateException` instead of silently returning an empty list.
An empty `WHERE tenant_id IN (?)` would have returned zero rows — masking
a real bug as "no data".

### 7.3 TenantAwareDataSource — per-checkout GUCs + web statement timeout

`TenantConnectionDataSourceConfig.TenantAwareDataSource` wraps the Hikari
datasource. On **every connection checkout** it runs one round-trip:

```sql
SELECT set_config('app.current_tenant', ?, false),
       set_config('statement_timeout', ?, false)
```

- `app.current_tenant` — from `TenantContext` (blank when none), overwritten
  on every checkout so it can never leak between borrowers.
- `statement_timeout` — **finite (default 30 000 ms) on servlet-request
  threads, `0` (unlimited) on batch/scheduler/startup threads.** The
  `hikari.connection-init-sql` sets `statement_timeout=0` for ingest, but web
  requests share the same 30-connection pool; one runaway report query per
  connection would deadlock the app with nothing to reap it. Override with
  `-Dacquira.web.statement.timeout.ms=NNNN` (0 disables).

Practical consequence: any dashboard query that cannot finish in 30 s
surfaces as `QueryTimeoutException: canceling statement due to statement
timeout`. The fix is to make the query cheap (pre-aggregates, §21), not to
raise the ceiling.

---

## 8. File Upload Pipeline (end-to-end)

### 8.1 Three entry points

All three end at the same `jobLauncher.run(transactionLoadJob | merchantMasterJob, params)`:

| Entry point | Endpoint | Use case |
|---|---|---|
| Single file via UI | `POST /api/upload` | One file at a time, browser upload |
| Multi-file via UI | `POST /api/upload/multi` | Multiple files in one request |
| Server folder | `POST /api/upload/process-server-file?path=...` | Files already on EC2 disk (preferred for bulk) |

### 8.2 What `processUnifiedFile` does (FileUploadService.java)

```
1. Save uploaded MultipartFile to /opt/acquira/data/uploads/tmp/<timestamp>_<name>
2. scanFileOnce(filePath) — opens workbook ONCE, returns:
     - detectedType: "MERCHANT" | "TRANSACTION" | "LEGACY_EXCEL" | "UNKNOWN"
     - entityId:     row 2, col 1 from the file (the bank short code)
3. resolveTargetTenantFromEntityId(entityId) — resolves tenantId
     - Super Admin: looks up by bank_short_code or institution_id
     - Bank Admin: must match their session tenant
4. Build JobParameters: tenantId, fullPath, startedAt
5. jobLauncher.run(matchingJob, params) — async, returns JobExecution immediately
6. For TRANSACTION files: kick off async ManualIngestionService.processManualUpload(tenantId)
7. Return JobExecution to caller (UI shows jobId)
```

### 8.3 Server folder path (used for bulk uploads)

```
processServerPath(path):
  1. validateAllowedPath(path):  [P0-4 + P2-6]
     - reject if path contains ".."
     - resolve via toRealPath() — follows symlinks AND canonicalizes
     - reject if any prefix segment is itself a symlink
     - confirm resolved path is under one of app.upload.allowed-paths
     - if path doesn't pass, throw SecurityException → controller maps to 403
  2. List all .xlsx, .csv, .tsv, .txt files in folder, sorted alphabetically
     - skip any individual file that's a symlink (defensive — toRealPath
       handles the parent dir, but per-file is still useful for audit)
  3. Hand off to runMultiFileBatch(files):
     a. Classify each file (MERCHANT vs TRANSACTION)
     b. Run ALL MERCHANT files first (sequential)
     c. Run ALL TRANSACTION files next (sequential)
     d. Trigger ONE ManualIngestionService.processManualUpload per tenant
     e. Return per-file results map
```

The order matters: dim tables must exist before transaction joins can resolve.

**[P0-4 + P2-6] Path validation lives in the SERVICE, not the controller.**
The controller (`FileUploadController.processServerFile`) is now a thin
dispatcher that translates `SecurityException` → HTTP 403. Any future
caller of `FileUploadService.processServerPath` (a scheduled job, a
different controller, an MCP integration) gets the same protection
automatically. Previously the only path check lived in the controller.

The validation uses `toRealPath()` (which resolves symlinks) plus an
explicit `Files.isSymbolicLink(...)` check, blocking the case where a
malicious actor with shell access plants `imports/leak -> /etc/passwd`
inside an allowed dir.

### 8.4 Allowed paths

In `application.properties`:

```
app.upload.allowed-paths=/opt/acquira/data,data/uploads,data/imports
```

Override per environment as needed.

---

## 9. Batch Job: Merchant Master

**File:** `MerchantMasterJobConfig.java`
**Trigger:** uploading a file detected as MERCHANT
**Output:** rows in `dim_merchant`, `dim_store`, `dim_terminal`

### Steps in order

| # | Step | What it does |
|---|---|---|
| 1 | `ensureMerchantPartitionsStep` | (no-op for merchant — kept for symmetry) |
| 2 | `merchantSplitStep` | Read Excel, split into CSV partitions in temp dir |
| 3 | `cleanMerchantStagingStep` | `DELETE FROM stg_merchant_master_raw WHERE tenant_id = ?` |
| 4 | `merchantIngestStep` | Partitioned read of CSV → bulk insert into `stg_merchant_master_raw` |
| 5 | `upsertAndSummarizeStep` | UPSERT into `dim_merchant`, `dim_store`, `dim_terminal` from staging — ON CONFLICT DO UPDATE |

### Key SQL pattern (upsertAndSummarizeStep)

```sql
INSERT INTO dim_merchant (tenant_id, internal_id, mid, name, mcc, sales_user_id, status, created_date)
SELECT tenant_id, internal_id, mid, name, mcc, sales_user_id, 'ACTIVE', NOW()
FROM stg_merchant_master_raw
WHERE tenant_id = ?
ON CONFLICT (tenant_id, internal_id)
DO UPDATE SET name = EXCLUDED.name, mcc = EXCLUDED.mcc, ...;
```

Same pattern for `dim_store` (linked via merchant's internal_id) and `dim_terminal`.

### SID normalization

`MerchantMasterJobConfig.normalizeSid()` handles the case where Excel exported SIDs as scientific notation (`4.00E+14`). It detects the pattern and converts back to the original integer string. **Both** the merchant master path and the transaction path use the same `normalizeSid()` so SIDs match across uploads.

### Propagation = NEVER

`upsertAndSummarizeStep` runs with `propagation NEVER` to avoid `idle_in_transaction_session_timeout`. Each `jdbcTemplate.update()` commits on its own boundary.

---

## 10. Batch Job: Transaction Load

**File:** `TransactionJobConfig.java`
**Trigger:** uploading a file detected as TRANSACTION
**Output:** rows in `fact_transaction` + populated summary tables + dashboard metrics

### Steps in order (9 steps)

| # | Step | Time (53 MB) | What it does |
|---|---|---|---|
| 1 | `ensurePartitionsStep` | 50-650 ms | Calls `PartitionMaintenanceService.ensurePartitionsForCurrentAndNextYear()` to create `fact_transaction_yYYYYmMM` for current + next year. Cached so subsequent calls are fast. |
| 2 | `splitExcelStep` | 1.8 s | If Excel: read workbook, split into CSV partitions in temp dir. If CSV: copy as-is and split into 8 partitions for parallel ingest. |
| 3 | `cleanTargetDayStep` | 50 ms | `DELETE FROM stg_trnx_raw WHERE tenant_id = ?`. Empties staging. |
| 4 | `masterIngestStep` | 1m 30s | Partitioned: 8 worker threads each read a CSV partition and bulk insert into `stg_trnx_raw` via `highPerfTransactionWriter` (chunk size 10,000). Includes ref-table lookups (card_scheme, currency division). |
| 5 | `autoCreateDimensionsStep` | 100 ms — 18 s | Pre-check `EXISTS` for unmapped SIDs/MIDs/TIDs. If found, INSERT placeholders into `dim_merchant`, `dim_store`, `dim_terminal` so transaction joins won't return NULL merchant_ids. Self-heals "transaction file uploaded before merchant file" case. |
| 6 | `stagingToFactStep` | 7 s — 1m 23s | (a) Auto-populate `dim_merchant.name` from staging where missing. (b) DELETE existing fact rows for the upload's dates. (c) INSERT INTO `fact_transaction` from `stg_trnx_raw` joined to dim tables (SID-primary). (d) Fix-up pass for unresolved store/terminal IDs. |
| 7 | `populateSummaryStep` | 50-62 s | 13 parallel SQL aggregations into the summary tables. Pool size 4. Phase 1: independent inserts. Phase 2: top-spending-customer UPDATE on sum_daily_merchant + sum_monthly_bank rollup. |
| 8 | `calculateBusinessMetricsStep` | 180 ms — 2 s | INSERT INTO `merchant_activity_summary` and `merchant_opportunity_score` from fact_transaction. Bounded to 60-day window for performance. |
| 9 | `calculateDailyDashboardMetricsStep` | 20-26 s | Java loop: for each month in upload, fetch `sum_daily_merchant`, group by merchant, compute monthly metrics, save to `sum_monthly_merchant_metrics`. Uses bulk fetch + saveAll for performance. **[P0-5]** Now runs with `propagation NEVER` — was the last unhardened long-running step. Without it, `monthlyMetricsRepo.saveAll` per-month inside a Java loop joined the step's outer transaction → idle-in-transaction risk on bulk historical uploads. |

After step 9, the job returns `COMPLETED`. **Then** the async `ManualIngestionService.processManualUpload(tenantId)` kicks off — this is reporting metrics.

### Critical config (TransactionJobConfig.java)

- **All long-running steps use `transactionAttribute(noTxn())`** = `PROPAGATION_NEVER`. Without this, all jdbcTemplate.update() calls accumulate in a single huge transaction, which Postgres kills via `idle_in_transaction_session_timeout` after 30 min. **[P0-5] Step 9 (`calculateDailyDashboardMetricsStep`) now also has this — was previously missing.**
- **Ref-table cache is JVM-wide**, not per-StepScope. Avoids 8× redundant loads when partitioned.
- **Distinct dates computed ONCE** at the start of stagingToFactStep and populateSummaryStep, then inlined as literal IN-lists in every aggregation query — avoids 13× full scans of stg_trnx_raw. **[P2-10] All inline-IN-list builds now go through `buildSafeDateInList()`** which validates each entry matches strict `YYYY-MM-DD` before interpolation. The inline literal approach is intentional (vs `= ANY(?)`) because PostgreSQL's partition pruner on `fact_transaction(payment_date)` prunes more aggressively with literal DATE values; switching to parameterized arrays would silently regress upload time by 30-60 seconds.
- **Aggregations parallelized** via `CompletableFuture` on a 4-thread executor. Pool size 4 (down from 8) for same-AZ EC2+RDS — prevents buffer-cache saturation.
- **[P3-6] All step timings + diagnostics go through SLF4J**, not `System.out.printf`. The previous `printf` calls were swallowed by the prod profile's logback config. Per-query timings in `populateSummaryTasklet` log at WARN level via the `runAsync()` helper so they bypass `org.springframework.batch=WARN` silencing.

### Currency division logic

Raw `txn_currency_amount` and `store_base_currency_amount` are integers (e.g. 12500 for 12.50 USD). Divided by `ref_country.decimal_notation_value` based on currency code. MSF/VAT/interchange always divided by 10000. Done in `transactionTenantProcessor` during ingest.

### DCC flag parsing

Both `Y/Yes` (older feeds) and `TRUE/FALSE` (newer feeds) are accepted via `parseDccFlag()`. Was a real bug: silently classified TRUE as opt-out, breaking page 9/10 of the merchant report.

---

## 11. Reporting / Manual Ingestion

**File:** `ManualIngestionService.java`
**Trigger:** Async after every TRANSACTION file upload, or manually via API.

### What it does

```
processManualUpload(tenantId):
  1. SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?
  2. For each distinct date:
       a. Compute reporting metrics (rows in merchant_daily_metrics)
       b. KPI snapshots (kpi_snapshot_daily, kpi_snapshot_monthly)
  3. Log "Manual Ingestion Completed for all dates."
```

This is what feeds the leaderboards and merchant insight dashboards. **The upload is fully done when this log line appears**, regardless of what the UI shows.

### **[P0-3] Tenant-scoped lookup — silent cross-tenant overwrite fixed**

The previous `processSingleDate` used `findByMerchantIdAndReportDate(merchId, date)`
to check if a metrics row already existed. That method omitted `tenantId`,
and `merchId` here is a bank-assigned internal_id which is **not unique
across tenants**. Two banks both assigning internal_id `12345` to a merchant
would silently overwrite each other's metrics rows.

The repo method has been replaced with
`findByTenantIdAndMerchantIdAndReportDate(tenantId, merchId, date)`. The
unique constraint `(tenant_id, reportDate, merchantId)` on the entity
backstops this at the schema level.

If you grep for `findByMerchantIdAndReportDate` and find any new callers,
they need the tenant-scoped variant.

### **[P2-9] N+1 → bulk fetch + saveAll**

`processSingleDate` previously did `findByMerchantIdAndReportDate(...).ifPresent(...)`
plus `save(...)` per merchant per date. With 5000 merchants × 80 dates =
800k round-trips on a multi-month upload, dominating end-to-end time.

It now does ONE `findByTenantIdAndReportDate(tenantId, monthKey)` per
month, builds an in-memory map, and ONE `saveAll(toSave)` per month.
Mirrors the pattern already used by `calculateDailyDashboardMetricsStep`.

---

## 12. Dashboards & Reads

### 12.1 Dashboard list (left sidebar)

| Dashboard | Path | Backend |
|---|---|---|
| Finance Dashboard | `/dashboard/finance` | `FinanceController` reads `sum_daily_finance`, `sum_daily_insight` |
| Transaction Performance | `/dashboard/transactions` | `TransactionController` + `AnalyticsController` |
| Merchant Insight | `/merchants/:id/insight` | `MerchantInsightController` reads sum_daily_merchant_attribute |
| Daily Merchant Dashboard | `/dashboard/daily-merchant` | `DailyMerchantDashboardController` |
| Group Reports | `/reports/groups` | `GroupAnalyticsController` |
| Geo Analytics | `/dashboard/geo` | `GeoAnalyticsController` |
| Sales Team | `/dashboard/sales-team` | `SalesTeamController` |
| Leaderboard | `/dashboard/leaderboard` | `LeaderboardController` |
| Zero Transaction | `/reports/zero-transaction` | `ZeroTransactionController` |
| Data Explorer | `/explorer` | `DataExplorerController`, `AnalyticsExplorerController` |
| Report Builder | `/reports/builder` | `ReportBuilderController` |

### 12.2 Filter standardization

All dashboards accept the same set of query params (BusinessFilters):
- `dateRange`: `{from, to}`
- `merchantIds[]`, `storeIds[]`, `terminalIds[]`
- `cardSchemes[]`, `cardTypes[]`, `destinations[]` (DOMESTIC/INTERNATIONAL)
- `mccs[]`, `regions[]`

The frontend has a `BusinessFilters` drawer that wires all dashboards. Saved filter sets live in `saved_filter`.

### 12.3 Why summary tables exist

Direct queries on `fact_transaction` for a year of data = millions of rows = slow. Summaries are pre-aggregated by `populateSummaryStep` and let dashboards return in <500ms.

### 12.4 **[P2-1] Always tenant-scope inside subqueries, not just at the outer WHERE**

`AnalyticsController.merchantSummaries` previously did:

```sql
LEFT JOIN (
    SELECT merchant_id, SUM(total_volume) ...
    FROM sum_daily_merchant
    WHERE business_date >= :startOfMonth ...
    GROUP BY merchant_id
) mtd ON mtd.merchant_id = m.merchant_id
WHERE m.tenant_id = :tenantId
```

The subquery scanned `sum_daily_merchant` for ALL tenants. The outer
`WHERE m.tenant_id = :tenantId` filtered the final result, and `merchant_id`
BIGSERIAL is unique across tenants today — so it WORKED, just slowly.
But it was a correctness time bomb: the moment `merchant_id` becomes
non-unique (multi-tenant ID strategy change), it would return cross-tenant
aggregations.

Both subqueries now include `AND tenant_id = :tenantId` and `mtd.tenant_id = m.tenant_id`
on the join. **Apply this pattern to every new controller that does
inline aggregation subqueries** — push the tenant filter as deep as possible.

---

## 13. PDF Reports

**Module:** `acquira-pdf`
**Rendering:** Microsoft **Playwright** (headless Chromium) printing **Thymeleaf** HTML templates to PDF. OpenPDF + JFreeChart are on the classpath for legacy/simple outputs, but the merchant report is HTML-first: each page is a Thymeleaf template under `acquira-pdf/src/main/resources/templates/pages/` (`p01-cover.html` … `p13-glossary.html`, `p99-closing.html`) with shared `partials/header.html` / `partials/footer.html`. Editing report layout = editing HTML/CSS, not Java drawing code.

### Report types

| Report | Source templates | Notes |
|---|---|---|
| Merchant Business Insight Report | `pages/p01…p99` | Cover, TOC, exec summary, scorecard, MoM compare, sales trends, heatmap, revenue, store leaderboard, card analytics, customer intel/loyalty, monthly trends, transactions, DCC, glossary |
| Basic report | `basic-report.html` | Single-page fallback |

### Generation pipeline (batch)

```
POST /api/business/insights/generate-all?year&month&sendEmail&sendS3[&merchantIds]
  1. Resolve tenant + month; list merchants (optionally selective)
  2. Chunked pre-fetch (pdf.batch.prefetch.chunk.size=100 merchants/chunk) —
     one getBulkInsights per chunk, freed after render, or the heap OOMs on 10k+ merchants
  3. PlaywrightPdfService renders each merchant's report → PDF file in
     <reports.dir>/<bank_short_code>/<YYYY-MM>/Insight_<name>_<YYYY-MM>.pdf
  4. Post-batch thread (only if sendEmail or sendS3):
       sendEmail  → queue one row per merchant into email_queue (§14.3)
       sendS3     → archive PDF to S3, INDEPENDENT of email outcome
```

`sendEmail`/`sendS3` combinations behave as a 2×2 matrix; S3 archival is not gated on email success (the PDF exists either way).

### Pool & threading

```
pdf.pool.size=2              # max concurrent PDF generations (memory-bound)
pdf.chart.wait.ms=800        # chart settle wait between draws
pdf.batch.data.threads=4     # SQL fetch threads per PDF
pdf.batch.prefetch.chunk.size=100
```

### Storage

Generated PDFs go to `pdf.reports.dir` (default `/opt/acquira/reports`), tenant-discriminated by `bank_short_code` (or `tenant-<id>` when no short code — two tenants' PDFs can never land in one shared folder). Logged in `pdf_batch_log`. Optionally archived to S3 (configured via `S3SettingsController`).

---

## 14. Email & Campaigns

### 14.1 Tables

`email_template_config`, `email_campaign`, `email_campaign_log`, `email_queue`, `email_smtp_config` (see §4.9).

### 14.2 Campaign flow

```
1. Bank Admin creates email_template_config (subject, body with {{variable}} merge tags)
   — Admin > Email campaign hub > Templates tab
2. Creates email_campaign (target merchants + template)
3. CampaignExecutionService renders per merchant (TemplateRendererService
   supplies merchant/txn variables) → enqueues into email_queue
4. EmailQueueProcessor drains the queue (14.3); results log to email_campaign_log
```

### 14.3 email_queue — the single outbound mail path

**ALL outbound merchant mail goes through `email_queue`**, never inline SMTP.
`EmailQueueProcessor` polls every 60 s (`@Scheduled(fixedDelay=60000)`), picks
`PENDING` rows under the retry cap, builds a `JavaMailSender` from the row's
tenant's **active `email_smtp_config`** (AES-decrypted password), sends (with
`attachment_path` if present, HTML body when `is_html`), then marks `SENT` or
bumps `retry_count` with the error message. This is why a slow SMTP server
cannot stall a PDF batch: the batch only inserts rows.

### 14.4 PDF report covering email — tenant templates (added 2026-08-07)

The email a merchant receives with their monthly PDF report used to be
hardcoded in `PdfController`. It is now resolved per tenant by
**`ReportEmailTemplateService`** (`acquira-common`):

```
resolve(tenantId, vars):
  1. tenant's ACTIVE email_template_config with template_type = REPORT_PDF
     and is_default_for_type = TRUE          → use its subject + bodyHtml
  2. otherwise → built-in template (the previous hardcoded email, verbatim,
     with {{placeholders}}) — behaviour unchanged for tenants without one
```

Merge variables (the batch supplies exactly these — nothing else renders):
`{{merchant_name}}`, `{{mid}}`, `{{month_year}}`, `{{statement_month}}`, `{{pdf_filename}}`.

Admin UI: Email campaign hub → Templates → type **"PDF report email"**. A
*Load the built-in email* button pre-fills the form with the real current email
(`GET /api/email-campaigns/templates/builtin/REPORT_PDF`) so customisation
starts from the working Outlook-safe layout. Mark the template *default for
type* or the batch ignores it. A malformed tenant template falls back to the
built-in rather than failing the batch loop.

The service lives in `acquira-common` because `acquira-pdf` depends only on
common — core's `TemplateRendererService` is unreachable from the PDF module.

### 14.5 SMTP config

Per-tenant rows in `email_smtp_config` (NOT `tenant_setting`), managed by
`SmtpConfigService` via the Settings screens. One active config per tenant;
password encrypted at rest; reads return a masked detached copy.

---

## 15. Integration Hub

External read-only data sources for cross-system reporting (e.g. core banking system, settlement platform).

### Tables

`integration_connection` → `integration_report` → `integration_schedule` → `integration_run_log`.

### Flow

```
1. Bank Admin defines integration_connection (JDBC URL, driver, creds in encrypted form)
2. Creates integration_report (parameterized SQL query)
3. Creates integration_schedule (cron expression)
4. Scheduler runs query, stores result rows, logs to integration_run_log
5. Result available in Data Explorer or merged into dashboards
```

---

## 16. Configuration Files

### 16.1 acquira-core/src/main/resources/application.properties (DEV)

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/acquira
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.sql.init.mode=always
spring.batch.jdbc.initialize-schema=always
server.port=8081
logging.level.root=INFO
logging.level.com.acquira=DEBUG
```

### 16.2 /opt/acquira/application.properties (PROD)

```properties
# Database
spring.datasource.url=jdbc:postgresql://<rds-endpoint>:5432/misuat?reWriteBatchedInserts=true&tcpKeepAlive=true&socketTimeout=0
spring.datasource.username=misuat-admin
spring.datasource.password=<rotate-after-deploy>

# HikariCP
spring.datasource.hikari.maximum-pool-size=30
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
spring.datasource.hikari.keepalive-time=300000
spring.datasource.hikari.validation-timeout=5000
spring.datasource.hikari.leak-detection-threshold=120000
spring.datasource.hikari.connection-init-sql=SET idle_in_transaction_session_timeout = 0; SET statement_timeout = 0; SET lock_timeout = 0

# Schema init — NEVER on prod after first deploy
spring.sql.init.mode=never
spring.batch.jdbc.initialize-schema=never

# Paths
app.logs.dir=/opt/acquira/logs
app.reports.dir=/opt/acquira/reports
app.data.dir=/opt/acquira/data
app.upload.allowed-paths=/opt/acquira/data,data/uploads,data/imports

# Logging
logging.file.name=/opt/acquira/logs/core.log
logging.level.root=WARN
logging.level.com.acquira=INFO

# Server
server.port=8081
server.tomcat.connection-timeout=60000
server.servlet.session.timeout=10m

# Multipart
spring.servlet.multipart.max-file-size=2048MB
spring.servlet.multipart.max-request-size=2048MB
spring.servlet.multipart.location=/opt/acquira/data/uploads/tmp
server.tomcat.max-http-form-post-size=2147483647

# JPA
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.batch_size=200

# Spring Batch — manual launch only
spring.batch.job.enabled=false

# CORS
app.cors.origins=https://yourdomain.com

# PDF
pdf.pool.size=2
pdf.chart.wait.ms=800
pdf.batch.data.threads=4
pdf.reports.dir=/opt/acquira/reports
```

### 16.3 logback-spring.xml

Has profiles: `dev` (console + DEBUG) and `prod` (file + WARN + async appender). Per-query batch logs use SLF4J at WARN level so they bypass `org.springframework.batch=WARN` silencing.

---

## 17. Build & Deploy

### 17.1 Build

```cmd
cd C:\Users\sivag\Desktop\cms\Acquira
mvn clean install -DskipTests -T 2C
```

Output: `acquira-core/target/acquira-core-1.0.0-SNAPSHOT.jar` (fat JAR, contains all modules).

### 17.2 Deploy to EC2

```bash
scp acquira-core/target/acquira-core-1.0.0-SNAPSHOT.jar ec2:/opt/acquira/app/
ssh ec2 'sudo systemctl restart acquira'
```

### 17.3 systemd unit (`/etc/systemd/system/acquira.service`)

```ini
[Unit]
Description=Acquira Core Service
After=network.target

[Service]
Type=simple
User=acquira
WorkingDirectory=/opt/acquira
ExecStart=/usr/lib/jvm/java-21/bin/java -Xmx6g -Xms2g \
  -jar /opt/acquira/app/acquira-core-1.0.0-SNAPSHOT.jar \
  --spring.config.location=file:/opt/acquira/application.properties
Restart=always
RestartSec=10
StandardOutput=append:/opt/acquira/logs/stdout.log
StandardError=append:/opt/acquira/logs/stderr.log

[Install]
WantedBy=multi-user.target
```

### 17.4 Frontend deploy

```cmd
cd frontend
npm run build
scp -r dist/* ec2:/var/www/acquira/
```

Served by nginx with proxy to `/api/*` → `http://localhost:8081`.

---

## 18. Operations Runbook

### 18.1 Daily checks

```sql
-- Yesterday's job runs
SELECT job_execution_id, status, start_time, end_time
FROM batch_job_execution
WHERE start_time > NOW() - INTERVAL '1 day'
ORDER BY job_execution_id DESC;

-- Idle-in-transaction sessions (should be 0)
SELECT COUNT(*) FROM pg_stat_activity WHERE state = 'idle in transaction';

-- Database size growth
SELECT pg_size_pretty(pg_database_size(current_database()));
```

### 18.2 Bulk upload (15 files example)

```bash
# 1. SCP files to server
scp *.csv ec2:/opt/acquira/data/imports/

# 2. From UI: OPERATIONS menu → Server File Processor → /opt/acquira/data/imports/ → Process
#    OR via curl:
curl -X POST "http://ec2:8081/api/upload/process-server-file?path=/opt/acquira/data/imports/" \
  -H "Authorization: Bearer $JWT"

# 3. Watch logs
ssh ec2 'tail -f /opt/acquira/logs/core.log | grep -E "(Step:|Manual Ingestion)"'
```

### 18.3 Verify data after upload

```sql
-- Did fact rows get inserted?
SELECT DATE(payment_date), COUNT(*)
FROM fact_transaction
WHERE tenant_id = 1
  AND payment_date >= NOW() - INTERVAL '7 days'
GROUP BY DATE(payment_date)
ORDER BY 1 DESC;

-- Summary rows aligned?
SELECT business_date, total_txns, total_volume
FROM sum_daily_bank
WHERE tenant_id = 1
ORDER BY business_date DESC LIMIT 7;

-- Any unresolved (NULL merchant_id) rows?
SELECT COUNT(*) FROM fact_transaction
WHERE tenant_id = 1 AND merchant_id IS NULL;
```

### 18.4 Killing stuck sessions

```sql
-- Find blocking
SELECT blocked.pid, blocking.pid, blocking.query
FROM pg_stat_activity blocked
JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS b(blocking_pid) ON true
JOIN pg_stat_activity blocking ON blocking.pid = b.blocking_pid;

-- Cancel (graceful)
SELECT pg_cancel_backend(<pid>);
-- Terminate (forceful)
SELECT pg_terminate_backend(<pid>);
```

### 18.5 Restart after redeploy

```bash
ssh ec2 'sudo systemctl restart acquira && sleep 10 && tail -50 /opt/acquira/logs/core.log'
```

Look for `Started CoreApplication in X seconds` to confirm.

---

## 19. Troubleshooting

### 19.1 "Connection is closed" error mid-upload

**Cause:** Postgres `idle_in_transaction_session_timeout` killed the connection.
**Fix:** Verify `connection-init-sql=SET idle_in_transaction_session_timeout = 0` is in `application.properties`. Verify all long steps use `propagation NEVER`.

### 19.2 Dashboard shows no data after upload

**Cause:** Most likely `merchant_id IS NULL` in fact_transaction (SID didn't resolve to dim_store).
**Check:**
```sql
SELECT COUNT(*) FROM fact_transaction WHERE tenant_id = ? AND merchant_id IS NULL;
```
**Fix:** Either upload merchant master file first, or check SID format mismatch (Excel scientific notation). `autoCreateDimensionsStep` should heal this automatically — verify it ran.

### 19.3 Upload hangs in UI but log shows COMPLETED

**Cause:** Frontend doesn't poll `/api/batch/jobs/{jobId}/status`.
**Workaround:** Refresh the page. The batch is done.
**Fix:** Frontend bug — needs polling logic added.

### 19.4 populateSummaryStep slow (>1 min)

**Diagnosis:** Look for per-query lines:
```
[populateSummary] sum_daily_merchant         12345 rows in 8.42s
```
Find the slow query, EXPLAIN ANALYZE it on prod.

**Common causes:**
- Missing index → run the migration script in 5.1
- Stale stats → run ANALYZE on the table
- Tiny RDS instance class → upgrade
- 8 parallel queries saturating buffer cache → reduce pool to 4 (already done)

### 19.5 Build fails with circular dependency

**Cause:** Code in `acquira-core` referencing `acquira-batch` or vice versa.
**Fix:** Move shared code to `acquira-common`.

### 19.6 "Permission Denied: You belong to X but trying to upload for Y"

**Cause:** File's row 2 col 1 (entityId) doesn't match user's tenant `bank_short_code` or `institution_id`.
**Fix:** Either fix the file's entityId column, or reassign the user to the right tenant.

---

## 20. Performance Notes

### 20.1 Same-AZ EC2+RDS — expected timings

For a 53 MB transaction CSV with all indexes + ANALYZE done:

| Step | Expected | Worst seen |
|---|---|---|
| ensurePartitions | <100 ms | 650 ms |
| splitExcel | 1-2 s | 2 s |
| cleanTargetDay | <100 ms | 200 ms |
| masterIngest | 30-60 s | 1m 34s (no index) |
| autoCreateDimensions | 100 ms | 18 s (no index) |
| stagingToFact | 15-30 s | 1m 23s (no index) |
| populateSummary | 15-30 s | 1m 2s (no index/analyze) |
| calculateBusinessMetrics | <500 ms | 2 s |
| calculateDailyDashboardMetrics | 5-10 s | 26 s |
| **Total** | **~1-1.5 min** | **3m+ without indexes** |

### 20.2 Why parallel uploads are sequential, not concurrent

`runMultiFileBatch()` processes files one at a time intentionally:
- Avoids contention on `dim_*` tables (auto-create races)
- Avoids contention on `fact_transaction` partitions
- Predictable resource usage (one upload's worth of memory at a time)

For 15 files, expect ~15-25 minutes total.

### 20.3 RDS instance recommendations

| Workload | Recommendation |
|---|---|
| Single-tenant UAT | db.t3.medium (2 vCPU, 4 GB) — sufficient |
| Multi-tenant prod | db.m5.large (2 vCPU, 8 GB) minimum |
| >50 GB fact_transaction | db.m5.xlarge + io1 storage |

### 20.4 JVM heap

Set explicitly in systemd unit:
```
-Xmx6g -Xms2g
```

Without `-Xmx`, JVM defaults to 25% of RAM which on a t3.medium = ~1 GB → OutOfMemoryError during big PDF generation or large batch jobs.

### 20.5 What NOT to do on production

- Don't run schema.sql (`spring.sql.init.mode=never` after first deploy)
- Don't `DELETE FROM fact_transaction` without a date filter
- Don't drop indexes during business hours (use `CONCURRENTLY`)
- Don't `VACUUM FULL` during business hours (locks the table)
- Don't increase `spring.datasource.hikari.maximum-pool-size` past `max_connections - 5` on RDS

---

## 21. Business Analytics Reports & the Monthly Pre-Aggregate Layer

The `/business/*` report family (added/expanded after the audit) reads the
insight summaries, never `fact_transaction`.

### 21.1 Endpoints (BusinessAnalyticsController + friends)

| Endpoint | Page | Base table(s) |
|---|---|---|
| `POST /business/volume-revenue-summary` | Volume & Revenue Summary | `sum_monthly_insight` / `sum_daily_insight` (routed, 21.2) |
| `POST /business/merchant-financial-summary` | Merchant Financial Summary | `sum_daily_insight` + dims |
| `POST /business/performance-dashboard?groupBy=` | drill-down MONTH/DAY/MERCHANT/STORE | routed for `groupBy=MONTH` |
| `POST /business/debit-prepaid-metrics`, `/debit-prepaid-summary` | Debit & Prepaid | `sum_daily_insight` (permissive card_type matcher + `ref_card_scheme` fallback; user-picked card types override the matcher) |
| `POST /business/top-performers-filtered` | Top Performers | dual-grain: `sum_daily_merchant` (settlement volume, real net margin = MSF − interchange − scheme fee) unless a card-level filter forces `sum_daily_insight` (cardholder volume, net margin ≈ MSF) |
| `GET /business/data-bounds` | all report pages | earliest/latest business_date for the tenant |

**Filter conventions shared by all of them:**
- Industry picks resolve to MCC lists via `ref_mcc_category` server-side
  (`resolveFilters`), because `dim_merchant.industry` carries raw feed text
  that never matches the category sheet.
- Team-leader picks resolve to `sales_user_id` lists via `SalesTeamService`;
  an unmatched name becomes the `__NO_MATCH__` sentinel (zero rows, never
  silently unfiltered).
- Frontend pages default their date range from `/business/data-bounds`
  (`useDataBounds` hook) — the full data window, NOT the current calendar
  month, so a feed whose latest date is in an earlier month still shows data
  on first load.

### 21.2 Monthly routing (the 30s-timeout fix)

Month-grained queries are served from `sum_monthly_insight` wherever the range
allows, because a year of `sum_daily_insight` at scale exceeds the 30 s web
statement timeout (§7.3):

```
[start, end]
  mStart = first whole-month boundary ≥ start
  mEnd   = last  whole-month boundary ≤ end
  if mStart ≤ mEnd:
      leg 1: sum_monthly_insight  month_key BETWEEN yyyymm(mStart)..yyyymm(mEnd)
      leg 2: sum_daily_insight    [start, mStart-1]   (partial head month, if any)
      leg 3: sum_daily_insight    [mEnd+1, end]       (partial tail month, if any)
      concatenate + re-sort — legs produce DISJOINT month buckets by
      construction, so no cross-leg merging and COUNT(DISTINCT merchant_id)
      is never split across legs
  else: single-month / open-bound range → daily table as-is
```

Used by `VolumeRevenueRepository.getSummary` and
`getPerformanceDashboardData(groupBy=MONTH)`. EXACTNESS RULE: the monthly
table may only serve WHOLE months — a partial month read from `month_key`
would include days outside the range and over-count vs daily.

**When adding a new month-grained report, route it the same way.** The daily
table is for day-grain or partial-month legs only.

---

## Appendix A: Useful SQL Queries

### Tenant data overview

```sql
SELECT
  (SELECT COUNT(*) FROM dim_merchant WHERE tenant_id = 1) AS merchants,
  (SELECT COUNT(*) FROM dim_store WHERE tenant_id = 1) AS stores,
  (SELECT COUNT(*) FROM dim_terminal WHERE tenant_id = 1) AS terminals,
  (SELECT COUNT(*) FROM fact_transaction WHERE tenant_id = 1) AS transactions,
  (SELECT MIN(payment_date) FROM fact_transaction WHERE tenant_id = 1) AS earliest,
  (SELECT MAX(payment_date) FROM fact_transaction WHERE tenant_id = 1) AS latest;
```

### Jobs run today

```sql
SELECT je.job_execution_id, ji.job_name, je.status, je.start_time, je.end_time,
       EXTRACT(EPOCH FROM (je.end_time - je.start_time)) AS duration_seconds
FROM batch_job_execution je
JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
WHERE je.start_time >= CURRENT_DATE
ORDER BY je.job_execution_id DESC;
```

### Failed jobs in last 7 days

```sql
SELECT je.job_execution_id, ji.job_name, je.status, je.exit_message
FROM batch_job_execution je
JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
WHERE je.start_time >= NOW() - INTERVAL '7 days'
  AND je.status IN ('FAILED', 'STOPPED')
ORDER BY je.job_execution_id DESC;
```

### Step timings for last job

```sql
SELECT step_name, status, start_time, end_time,
       EXTRACT(EPOCH FROM (end_time - start_time)) AS seconds
FROM batch_step_execution
WHERE job_execution_id = (SELECT MAX(job_execution_id) FROM batch_job_execution)
ORDER BY step_execution_id;
```

### Index usage stats (cleanup candidates)

```sql
SELECT schemaname, relname AS table_name, indexrelname AS index_name,
       idx_scan, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes
WHERE idx_scan = 0
  AND schemaname = 'public'
ORDER BY pg_relation_size(indexrelid) DESC;
```

---

## Appendix B: REST Endpoint Cheat Sheet

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/login` | Login → JWT |
| GET | `/api/users/me` | Current user info |
| GET | `/api/users/me/menu` | Menu items for user's group |
| POST | `/api/upload` | Single file upload (auto-detect type) |
| POST | `/api/upload/multi` | Multiple files in one request |
| POST | `/api/upload/process-server-file?path=` | Process files from server folder |
| GET | `/api/batch/jobs/{id}/status` | Job execution status |
| GET | `/api/batch/jobs?limit=20` | Recent batch runs |
| GET | `/api/dashboards/executive` | Executive dashboard data |
| GET | `/api/dashboards/finance` | Finance dashboard |
| GET | `/api/merchants` | List merchants |
| GET | `/api/merchants/{id}` | Merchant detail |
| GET | `/api/merchants/{id}/insight` | Merchant insight (paginated detail) |
| POST | `/api/reports/generate` | Generate PDF report |
| GET | `/api/reports/{id}/download` | Download generated PDF |
| GET | `/api/admin/audit-logs` | Audit log viewer |
| POST | `/api/admin/backups/run` | Trigger DB backup |

---

## Appendix C: Glossary

| Term | Meaning |
|---|---|
| **MID** | Merchant ID — bank-assigned unique merchant code |
| **SID** | Store ID — bank-assigned unique store code under a merchant |
| **TID** | Terminal ID — POS terminal serial |
| **MSF** | Merchant Service Fee — what the merchant pays for processing |
| **VAT** | Value Added Tax (region-dependent) |
| **Interchange Fee** | Fee paid to the card-issuing bank |
| **DCC** | Dynamic Currency Conversion — opt-in cardholder feature |
| **MCC** | Merchant Category Code (4-digit ISO) |
| **ARN** | Acquirer Reference Number — unique per transaction |
| **RRN** | Retrieval Reference Number — also unique per transaction |
| **Aggregator** | Top-level umbrella entity above merchants (rarely used) |
| **PartitionMaintenanceService** | Creates monthly partitions on `fact_transaction` |
| **TenantContext** | ThreadLocal holding the current request's tenant |

---

## Appendix D: Audit Pass Changelog (2026-05-08)

This appendix lists every change applied during the 2026-05-08 audit pass.
Each entry includes the audit ID, the affected file(s), and a one-paragraph
summary. Inline references like `[P0-3]` elsewhere in this guide point here.

### Already in place when audit started (no code change needed)

- **[P2-5] `TenantContext.getVisibleTenants()` throws on empty.** Fail-loud
  if neither `currentTenant` nor `visibleTenants` is set on the thread.
  Common cause: an `@Async` method that didn't propagate context.

### P0 — Critical fixes applied

- **[P0-3] Cross-tenant overwrite by merchant internal_id.**
  *Files:* `MerchantDailyMetricsRepository.java`, `ManualIngestionService.java`.
  Replaced `findByMerchantIdAndReportDate(merchId, date)` with
  `findByTenantIdAndMerchantIdAndReportDate(tenantId, merchId, date)`.
  Two banks with overlapping internal_ids would have silently overwritten
  each other's `merchant_daily_metrics` rows. Unique constraint
  `(tenant_id, reportDate, merchantId)` on the entity backstops the schema.

- **[P0-4] Server-folder upload symlink traversal.**
  *Files:* `FileUploadService.java`, `FileUploadController.java`.
  Replaced `Paths.get(...).normalize().toAbsolutePath()` (which doesn't
  resolve symlinks) with `toRealPath()` plus an explicit `Files.isSymbolicLink()`
  check. A malicious symlink inside an allowed dir (e.g.
  `/opt/acquira/data/imports/leak -> /etc/passwd`) would have passed the
  old `startsWith()` check.

- **[P0-5] `calculateDailyDashboardMetricsStep` missing `propagation NEVER`.**
  *File:* `TransactionJobConfig.java`. Added `transactionAttribute(noTxn())`
  to the step. Was the only long-running step in the file without it.
  Bulk historical uploads with multiple months would have re-introduced
  the `idle_in_transaction_session_timeout` symptom on this step.

### P1 — High fixes applied

- **[P1-2] TenantAspect `SET LOCAL` was a no-op outside transactions.**
  *File:* `TenantAspect.java`. Replaced `SET LOCAL app.current_tenant = '<id>'`
  with `SELECT set_config('app.current_tenant', ?, false)`. `SET LOCAL`
  is silently ignored (PG emits a WARNING) when run outside a transaction —
  exactly the case for tasklets running with `propagation NEVER`. The
  `lastSetTenant` ThreadLocal cache was also removed because the GUC is
  per-CONNECTION and Hikari recycles connections.

- **[P1-4] Lockout counter not reset on expiry.**
  *File:* `AuthController.java`. After the 15-min lockout window passed,
  `failed_login_attempts` was still 5 — one more typo would re-lock
  immediately. Now the expiry is checked on the next attempt and the
  counter is zeroed before authentication runs.

- **[P1-5] Auth response leaked account-state signal.**
  *File:* `AuthController.java`. Collapsed the previous
  `403 deactivated` / `403 pending approval` / `401 bad credentials`
  responses into a single `401 {"error":"Invalid username or password"}`.
  Audit log captures the actual reason. Account-lockout still returns
  `423` because the user needs to know why a correct password isn't
  working — but the `lockedUntil` timestamp is no longer in the response.

### P2 — Medium fixes applied

- **[P2-1] MTD/YTD subqueries unscoped on tenant.**
  *File:* `AnalyticsController.java` (both `/merchant-summaries` and
  `/merchant-summaries/export`). Inline aggregation subqueries against
  `sum_daily_merchant` now include `AND tenant_id = :tenantId` and
  `... ON mtd.tenant_id = m.tenant_id` on the join. Was relying on the
  outer `WHERE m.tenant_id = ...` plus `merchant_id` happening to be
  globally unique — a correctness time bomb if the ID strategy ever changes.

- **[P2-4] Super-admin `visibleTenants` was a TODO.**
  *File:* `JwtRequestFilter.java`. When role is `ROLE_SUPER_ADMIN`,
  `visibleTenants` is now populated from `tenantRepository.findAll()`.
  Cross-tenant rollups (executive dashboard, group reports) for SA users
  now actually see all tenants instead of silently scoping to one.
  *Note:* the constructor signature changed — `JwtRequestFilter` now takes
  `TenantRepository` as a fifth arg. Spring autowires it; only manual
  test constructors break.

- **[P2-6] Path validation moved into `FileUploadService`.**
  *Files:* `FileUploadService.java`, `FileUploadController.java`.
  The path-traversal guard previously lived only in the controller.
  Any future caller of `processServerPath` (scheduler, MCP integration,
  another controller) would have bypassed the check. Now the validation
  runs inside the service; the controller is a thin dispatcher that
  translates `SecurityException` → HTTP 403.

- **[P2-7] Lockout key was per-IP, not per-(IP, username).**
  *File:* `AuthController.java`. Bucket key is now
  `clientIp + "|" + username.toLowerCase()`. Previously a single
  typo from a corporate NAT could throttle the whole office.

- **[P2-9] `ManualIngestionService` did N+1 round-trips per merchant per month.**
  *File:* `ManualIngestionService.java`. Mirrored the bulk-fetch + batch-save
  pattern already used by `calculateDailyDashboardMetricsStep`. ONE
  `findByTenantIdAndReportDate` per month + ONE `saveAll(...)` per month.
  Goes from O(merchants × dates) round-trips to O(months).

- **[P2-10] Inline IN-list of dates not validated before SQL interpolation.**
  *File:* `TransactionJobConfig.java`. Added `buildSafeDateInList()` helper
  that validates every entry matches strict `YYYY-MM-DD` regex before
  interpolating. The inline literal IN-list (vs parameterized `= ANY(?)`)
  is intentional and stays — PostgreSQL's partition pruner on
  `fact_transaction(payment_date)` prunes more aggressively with literal
  DATE values, and the validation closes the defense-in-depth gap.

### P3 — Hygiene fixes applied

- **[P3-3] `.bak` files in source tree.**
  *Files:* `TransactionJobConfig.java.bak`, `MerchantReportJobConfig.java.bak`.
  Marked as DEPRECATED with delete instructions. **Action required:** run
  `Remove-Item` in PowerShell to actually delete (the Filesystem MCP
  used during the audit can't delete files):
  ```powershell
  Remove-Item .\acquira-batch\src\main\java\com\acquira\batch\job\TransactionJobConfig.java.bak
  Remove-Item .\acquira-batch\src\main\java\com\acquira\batch\job\MerchantReportJobConfig.java.bak
  ```

- **[P3-5] `e.printStackTrace()` → `log.error(..., e)` in controllers.**
  *File:* `FileUploadController.java`. Stacktraces now go through the
  configured logger (file rotation, levels, JSON encoder) instead of stdout.

- **[P3-6] `System.out.printf` → SLF4J in batch tasklets.**
  *Files:* `TransactionJobConfig.java` (23 sites), `MerchantMasterJobConfig.java`
  (14 sites). Per-step timings and diagnostics now go through SLF4J. The
  prod profile's logback config was swallowing the previous `printf`
  output, making it impossible to debug step performance from prod logs.
  Both files balance on parens and braces post-edit (verified).

### Not addressed in this pass — queued for next round

These are documented in the audit plan but were out of scope for this
sprint. They need either coordinated maintenance windows, decisions from
ops, or larger refactors:

- **[P0-1]** Production `spring.sql.init.mode=always` + DROP TABLE in
  `schema.sql` — one missed env var = data loss. Needs migration to
  Flyway and `spring.sql.init.mode=never` in prod props.
- **[P0-2]** JWT default secret hardcoded in source (and in `application.properties`
  default). Needs secret rotation + Secrets Manager integration.
- **[P1-1]** RLS policies enabled in schema but no `FORCE ROW LEVEL SECURITY` —
  half-on RLS is worst of both worlds. Decide: either commit fully (force
  + non-owner role) or remove the dead policies from schema.sql.
- **[P1-3]** JWT carries no tenant claim — every authenticated request
  hits `user_tenant_access` to determine scope. Move the tenant list
  into JWT claims.
- **[P3-1, P3-2]** `TransactionJobConfig.java` (1500 lines) and
  `MerchantMasterJobConfig.java` (812 lines) split by step into separate
  `@Configuration` classes.
- **[P3-7..P3-10]** Repo hygiene (loose `.ps1` files at root) and config
  reconciliation between dev/prod (`hikari.minimum-idle`, `pdf.chart.wait.ms`,
  dev port `5433` vs `5432`).

### Verification checklist after pulling these changes

1. **Build:** `mvn clean install -DskipTests -T 2C` — should succeed.
2. **Find any test class that manually constructs `JwtRequestFilter`** —
   the constructor gained a `TenantRepository` arg. Update those tests.
3. **Search for any caller of the removed `findByMerchantIdAndReportDate`** —
   should be zero hits outside `ManualIngestionService`. Audit verified
   this at the time but a downstream merge could re-introduce a caller.
4. **Smoke test the auth endpoint:** bad password should return generic
   401, not the previous 403/403/401 split. Locked accounts still return
   423 but without a `lockedUntil` field.
5. **Smoke test super-admin login:** any cross-tenant rollup endpoint
   should now show data from all tenants for SA users (was scoping to
   one tenant before P2-4).
6. **Smoke test path traversal:**
   `curl -X POST '...?path=/etc/passwd'` → 403.
   `curl -X POST '...?path=/opt/acquira/data/imports/../../etc/passwd'` → 403.
   A symlink inside an allowed dir pointing outside → 403.
7. **Bulk transaction upload (>100 MB, multi-month):** must complete with
   `Manual Ingestion Completed for all dates.` in the log. No
   `Connection is closed` errors in the dashboard step (P0-5).

---

## Appendix E: Changes Since the Audit (2026-05-08 → 2026-08-07)

The ordered ledger of every schema change is
`acquira-core/src/main/resources/application.properties` →
`spring.sql.init.schema-locations` (schema.sql, schema_extras.sql, then every
`db/migration/V*` in execution order). Highlights by area:

### Schema / migrations
- **`sum_monthly_insight`** (`V2026_06_29_02`) — month-grain pre-aggregate of
  `sum_daily_insight`; `sum_daily_insight` itself became year-partitioned.
  Monthly routing described in §21.2.
- **Interchange & scheme-fee engine** (`V2026_07_05_01`,
  `V2026_07_31_02..06`) — `sum_daily_terminal` fee columns, multi-country
  interchange engine, rate cards for UAE, Bahrain, Oman, Egypt, e-com flat-fee
  config, intl debit interchange, domestic POS scheme fee.
- **`ref_mcc_category`** (`V2026_07_10_01`) — the bank's MCC sector sheet;
  feeds the Industry filter resolution (§21.1).
- **`email_queue` completion** (`V2026_07_10_04`) — merchant_id /
  merchant_name / is_html / statement_month columns the queue processor and
  Email Manager stats page require.
- **Tenant provisioning + partition provisioning** (`V2026_07_11_03`,
  `V2026_07_12_01`) — self-service tenant creation incl. per-tenant partition
  setup.
- **Sales team build-out** (`V2026_07_10_05`, `V2026_07_11_04..05`,
  `V2026_08_06_01`) — /sales/* screens, country-lead hierarchy,
  `merchant_sales_assignment_history` audit trail (written set-based by the
  merchant-master upload when reassignments happen).
- **Password reset via OTP** (`V2026_07_11_01`), **account expiry**
  (`V2026_07_04_02`), **API management foundation** (`V2026_07_04_01`),
  **budget targets** (`V2026_07_02_01`), **DB maintenance screens**
  (`V2026_06_26_01..02`), **integration pull flags / schedule precondition**
  (`V2026_07_14_01`, `V2026_08_07_01`).

### Backend behavior
- **Web statement timeout** — `TenantAwareDataSource` stamps
  `statement_timeout=30s` on servlet threads, `0` on batch threads, at every
  connection checkout (§7.3).
- **Monthly pre-aggregate routing** in `VolumeRevenueRepository` (§21.2);
  `getSummary` also drops its `dim_merchant` join unless a merchant-attribute
  filter needs it (the join selected nothing and could not fan rows out —
  merchant_id is the PK).
- **All outbound mail through `email_queue`** with per-tenant
  `email_smtp_config` (AES-256-GCM at rest) drained by `EmailQueueProcessor`
  every 60 s (§14.3).
- **Tenant-templated PDF covering email** — `REPORT_PDF` template type +
  `ReportEmailTemplateService` with built-in fallback (§14.4).
- **Top Performers** — single-endpoint report (3 queries: current window,
  prior window, first-txn dates) with month-aligned prior-window comparison,
  25th-percentile noise floor on movers, first-revenue-date definition of
  "new merchant" (NOT `dim_merchant.created_date`, which is an ETL load
  timestamp).

### Frontend conventions
- **`useDataBounds`** — shared default-date-range hook; report pages seed
  filters from the tenant's actual data window and pass the seeded object
  explicitly to the first fetch (two-effect stale-closure bug fixed 2026-08-07
  on DebitPrepaidMetrics / MerchantFinancialSummary / GroupReports).
- **`PremiumReportHeader`** — shared report chrome: date-preset chips that
  pass the fully-resolved next-filters object to `onApplyAfterDatePreset`
  (never a stale closure), filters drawer, Run Report.
- Request-sequence guards (`reqSeq` ref) on pages where two in-flight report
  runs could land out of order.

---

**End of Guide.**

For questions or corrections, edit this file and submit a PR.
