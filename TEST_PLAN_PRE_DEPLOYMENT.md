# Acquira — Pre-Deployment Test Plan

**Scope:** All changes from the current work stream — Database Maintenance job + UI, autovacuum partition tuning, Zero Transaction & Attrition report enhancements, security-policy enforcement, `sum_daily_terminal` index, BackupService rewrite, Executive Dashboard interactivity, Report Manager multi-tenant behavior, and connection-handling regressions.

**How to use:** Run top to bottom. Section A (build/migrations) gates everything else. Mark each case Pass / Fail / Blocked and record the actual result. Anything in **bold-CRITICAL** must pass to deploy.

**Environments**
- DEV: local PostgreSQL `:5433`, `spring.sql.init.mode=always` (migrations auto-run).
- PROD/UAT: RHEL + RDS, `spring.sql.init.mode=never` (migrations run **manually**).

**Legend:** TC = test case. Pre = precondition. ✔ = expected result.

---

## A. Build, Migrations & Startup  *(gate — do first)*

### TC-A1 — Clean build succeeds **(CRITICAL)**
- Steps: `mvn clean install -DskipTests -T 2C` from repo root.
- ✔ All five modules compile; no "cannot find symbol" (verifies the `SecurityPolicyService` import fix in `AuthController`). BUILD SUCCESS.

### TC-A2 — Dev startup runs every migration idempotently
- Pre: fresh dev DB (or existing — must be safe to re-run).
- Steps: start `acquira-core`; restart it a second time.
- ✔ App starts both times with no SQL errors. Re-running does not duplicate rows or throw (all migrations are `IF NOT EXISTS` / `ON CONFLICT DO NOTHING`).

### TC-A3 — Prod migrations applied in order **(CRITICAL for prod)**
- Pre: PROD/UAT where `sql.init.mode=never`.
- Steps: run by hand, in order:
  1. `V2026_05_07_01__performance_indexes.sql` (now includes `idx_sum_daily_terminal_tenant_terminal_date`)
  2. `V2026_06_26_01__db_maintenance.sql`
  3. `V2026_06_26_02__db_maintenance_menu.sql`
  4. The autovacuum backfill blocks (fact + sum_daily partitions)
  5. (Prod, large table) the `CREATE INDEX CONCURRENTLY` variant + `ANALYZE sum_daily_terminal`
- ✔ Each runs without error; re-running is a no-op.

### TC-A4 — Schema objects exist after startup/migration
- Steps (psql):
  ```sql
  \d db_maintenance_config
  \d db_maintenance_run
  SELECT * FROM db_maintenance_config;            -- exactly one row, id=1
  SELECT menu_name,path FROM sys_menu WHERE path='/admin/maintenance';
  SELECT indexname FROM pg_indexes WHERE indexname='idx_sum_daily_terminal_tenant_terminal_date';
  ```
- ✔ Both tables exist; config has one seeded row; menu row present; index present.

### TC-A5 — Frontend build & deploy
- Steps: `cd frontend && npm run build`; on RHEL `cp -r dist/* /opt/acquira/frontend/` + `restorecon -Rv /opt/acquira/frontend/` + hard browser cache clear.
- ✔ Build succeeds with no errors; nginx serves the new bundle (verify a known new string, e.g. the "tap a slice to focus" donut subtitle).

---

## B. Database Maintenance Job (backend + UI)

### TC-B1 — Menu visibility & RBAC
- Steps: log in as Super Admin → sidebar.
- ✔ **Administration → Database Maintenance** appears with the Database icon and routes to `/admin/maintenance`.
- Repeat as Admin: visible. As Business/Finance/Ops user: **not** visible, and direct navigation to `/admin/maintenance` is blocked by RoleGuard.

### TC-B2 — Status loads
- Steps: open the page.
- ✔ Shows enabled state, window hours, table chips (default list), "Batch running / No batch running", "In/Outside window", last run = "never" initially, and an (empty) Recent Runs panel.

### TC-B3 — Save config (Super Admin) **(CRITICAL)**
- Steps: toggle enabled, set window 02→05, keep default tables, Save.
- ✔ `PUT /api/admin/maintenance/config` returns 200; values persist on reload. Row in `db_maintenance_config` updated.

### TC-B4 — Config RBAC (Admin cannot write)
- Steps: as Admin, attempt Save / Run now.
- ✔ Backend returns 403; UI snackbar: "Only a Super Admin can change/run maintenance settings." Status GET still works for Admin.

### TC-B5 — Window logic incl. midnight wrap
- Cases: start=2,end=5 (normal); start=23,end=4 (wrap); start=end (empty).
- ✔ `inWindow` true only inside the range; wrap case true at 23:30 and 03:30, false at 12:00; empty window → "Window is empty" warning and job never auto-runs.

### TC-B6 — Manual "Run now" with no batch running **(CRITICAL)**
- Pre: no Spring Batch job active.
- Steps: click **Run now**.
- ✔ Runs immediately (force ignores window/last-run), snackbar shows `SUCCESS: N/N tables in X.Xs`; a `MANUAL` row appears in Recent Runs with per-table timings; `last_run_date` set to today.

### TC-B7 — Idle guard blocks during ingestion **(CRITICAL)**
- Pre: start a large file upload so a batch job is `STARTED`.
- Steps: click **Run now** (overrideBatch=false default).
- ✔ Returns `SKIPPED — A batch job is currently running`; **no VACUUM runs**; chip shows "Batch job running".
- Then: `POST /run?force=true&overrideBatch=true` → runs despite the job (documented override).

### TC-B8 — Scheduled run fires once/day in window
- Steps: set window to the current hour, enabled, no batch running; wait one poll cycle (≤10 min) or temporarily lower `maintenance.poll-interval-ms`.
- ✔ A `SCHEDULED` run executes once; subsequent polls the same day do **not** re-run (last-run-today guard). Log line `[maintenance] SCHEDULED pass SUCCESS ...`.

### TC-B9 — VACUUM actually executed
- Steps: before/after a run, compare `last_autovacuum`/`last_vacuum` and `n_dead_tup`:
  ```sql
  SELECT relname,last_vacuum,last_analyze,n_dead_tup FROM pg_stat_user_tables
  WHERE relname IN ('sum_daily_bank','fact_transaction') ORDER BY 1;
  ```
- ✔ `last_vacuum`/`last_analyze` timestamps advance; dead tuples drop.

### TC-B10 — Custom table list + identifier guard
- Steps: turn off "use recommended", enter `sum_daily_bank, sum_daily_scheme`, Save, Run.
- ✔ Only those two are vacuumed (Recent Runs detail). Enter a bad token like `sum_daily_bank; DROP TABLE x` → that entry is rejected ("invalid identifier") and skipped; no SQL injection occurs.

### TC-B11 — Failure handling
- Steps: add a non-existent table name to the custom list, Run.
- ✔ Other tables still vacuum; run status reflects the error in `detail`; the job does not crash the scheduler.

### TC-B12 — Persistence across restart
- Steps: set config + run once, restart `acquira-core`.
- ✔ Config and Recent Runs survive (DB-backed); `last_run_date` respected so it doesn't immediately re-run that night.

### TC-B13 — Timezone sanity
- ✔ Window uses **server local time**. Confirm RHEL box TZ (`timedatectl`) matches intent (e.g. Asia/Bahrain), or note the offset.

---

## C. Autovacuum Partition Tuning

### TC-C1 — New partition inherits settings **(CRITICAL)**
- Steps: trigger partition creation (run any upload, or call `ensurePartitionsForCurrentAndNextYear`), then:
  ```sql
  SELECT relname,reloptions FROM pg_class
  WHERE relname LIKE 'fact_transaction_y%' ORDER BY relname DESC LIMIT 3;
  ```
- ✔ Newest fact partitions carry `autovacuum_vacuum_scale_factor=0.01`, `..._threshold=50000`, and (PG13+) the `..._insert_*` knobs.

### TC-C2 — Existing partitions backfilled
- Steps: run the two backfill `DO` blocks; re-query reloptions for fact + `sum_daily_*` partitions.
- ✔ All existing partitions carry the intended options; `sum_daily_*` partitions also show `fillfactor=90`.

### TC-C3 — PG12 graceful degradation
- Pre: only if any target server is PostgreSQL 12.
- ✔ Partition creation still succeeds; a WARN is logged that the insert-vacuum knobs were skipped; base options applied.

---

## D. Zero Transaction Report

### TC-D1 — Summary accuracy **(CRITICAL)**
- Steps: `POST /reports/zero-txn/summary`; cross-check counts against raw SQL for the same tenant/date range.
- ✔ total / never-transacted / inactive-30 / inactive-7 and the days-inactive buckets match SQL `COUNT(... FILTER)`. Top-6 aggregators reflect the **full** set (not a 500-row sample).

### TC-D2 — Server-side pagination
- Steps: grid first page, change page size, jump pages via `POST /reports/zero-txn/page`.
- ✔ Each request returns only that page + correct `total`; rows risk-ordered; no client-side 500-row cap.

### TC-D3 — Status quick-filters
- Steps: click ALL / IN30 / NEVER / IN7 chips.
- ✔ Both summary band and grid update; counts consistent between chip and grid total.

### TC-D4 — Export
- Steps: export current view.
- ✔ Pulls up to 1000 rows honoring the active status filter; file opens; values match grid.

### TC-D5 — Tenant isolation
- Steps: run as two different tenants (or super admin switching tenants).
- ✔ No merchant/aggregator from another tenant appears; counts differ appropriately.

### TC-D6 — Empty/edge data
- ✔ Tenant with no dormancy shows zeros and an empty grid without errors.

---

## E. Attrition Report

### TC-E1 — Value at Risk KPI **(CRITICAL)**
- Steps: note CHURNED + AT_RISK merchants; compute sum of their current-period value.
- ✔ KPI equals that sum; shows currency for Volume/Revenue metric and a count for Transactions.

### TC-E2 — Metric toggle re-derivation
- Steps: switch Volume → Txns → Revenue.
- ✔ KPI, portfolio-health bar, %-change histogram, and steepest-decline list all recompute from the active metric.

### TC-E3 — Portfolio health bar
- ✔ Stacked segments + legend counts sum to total merchants; percentages add to ~100%.

### TC-E4 — YTD %-change histogram
- ✔ Buckets (≤-50, -50..-20, -20..0, 0..+20, >+20) sum to the number of merchants with a valid YTD %; colors graded red→green.

### TC-E5 — Steepest-decline call list
- ✔ Shows the 6 most-negative YTD movers; clicking a row jumps the grid/filters to that status cohort.

### TC-E6 — Existing grid intact
- ✔ MoM/MTD/YTD comparison grid, column grouping, and existing chips behave as before (no regression).

---

## F. Security-Policy Enforcement

### TC-F1 — Password composition rules **(CRITICAL)**
- Steps: set policy (min length, upper/lower/digit/special), then attempt change/create.
- ✔ Weak passwords rejected with the specific rule; compliant ones accepted.

### TC-F2 — Breached / common password gate
- ✔ With `blockBreached=true`, a known-common password is rejected; with false, allowed.

### TC-F3 — Password history & min age
- ✔ Reusing the last N passwords is blocked; self-service change before `minPasswordAgeHours` is blocked; admin reset path behaves per design.

### TC-F4 — Token TTLs from policy
- Steps: set access/refresh TTLs; log in; inspect token expiry + cookie Max-Age.
- ✔ Access/refresh expirations and the refresh cookie expiry match policy values.

### TC-F5 — Concurrent session limit
- Steps: set max sessions = N; log in N+1 times.
- ✔ Oldest session(s) revoked so only newest N remain active (`enforceSessionLimit`).

### TC-F6 — Lockout & rate limit **(CRITICAL)**
- Steps: exceed `maxFailedAttempts` for one user from one IP.
- ✔ Account locks for `lockoutDurationMinutes`, returns generic `423` (no `lockedUntil` leaked); a different user from the same NAT IP is **not** locked (per-(IP,username) key); counter resets after the window.

### TC-F7 — Generic auth failure
- ✔ Bad password / inactive / pending all return the same `401 {"error":"Invalid username or password"}`; the real reason is only in `audit_log`.

### TC-F8 — Revoke all sessions
- Steps: Super Admin → "Revoke all sessions".
- ✔ `POST /api/admin/security/revoke-all-sessions` 200; all refresh tokens invalidated; users must re-login.

### TC-F9 — "Enforcement pending" items
- ✔ MFA / IP allowlist / API-key expiry etc. are clearly labeled pending and do **not** falsely claim enforcement.

---

## G. `sum_daily_terminal` Index

### TC-G1 — Planner uses it
- Steps:
  ```sql
  EXPLAIN ANALYZE SELECT MAX(business_date) FROM sum_daily_terminal
  WHERE tenant_id=1 AND terminal_id=<id>;
  ```
- ✔ Uses `idx_sum_daily_terminal_tenant_terminal_date` (index scan), not a seq scan. (Run `ANALYZE sum_daily_terminal` first.)

### TC-G2 — Report speed
- ✔ Zero Transaction summary/page response time materially improved on a large table vs pre-index baseline.

---

## H. BackupService

### TC-H1 — Successful backup **(CRITICAL)**
- Pre: `pg_dump` on PATH or `app.backup.pg-dump` set to full path; matching client major version.
- Steps: Super Admin → create backup.
- ✔ 200 with `fileName`; file present under `app.backup.dir` (`./backups`) with non-zero size; appears in list.

### TC-H2 — Failure surfaces real reason **(CRITICAL)**
- Steps: temporarily break PATH or point `app.backup.pg-dump` at a bad path; create backup.
- ✔ Response includes a `detail` field with the actual cause ("could not be started … set app.backup.pg-dump …"); partial/empty file is cleaned up (not listed as usable).

### TC-H3 — Timeout destroys process
- Steps: set `app.backup.timeout-seconds=1` against a non-trivial DB; create backup.
- ✔ Returns a timeout error advising to raise the property; no orphaned `pg_dump` process remains (`ps aux | grep pg_dump`).

### TC-H4 — Restore
- Pre: a valid backup file + a safe target DB.
- Steps: restore it.
- ✔ Completes; exit 0 → success, exit 1 → "completed with warnings"; higher → failure with `detail`.

### TC-H5 — List / download / delete
- ✔ List shows files newest-first with sizes; download streams the file; delete removes it.

### TC-H6 — Path-traversal guard **(CRITICAL)**
- Steps: call restore/delete/download with `../../etc/passwd`, `..\\x`, `/abs/path`, `a.exe`.
- ✔ All rejected (controller 400 + service `IllegalArgumentException`); only `^[A-Za-z0-9_.-]+\.(sql|dump)$` accepted.

### TC-H7 — URL parsing
- ✔ Host/port(default 5432)/db parsed from `jdbc:postgresql://host:port/db?params`; param string stripped.

---

## I. Executive Dashboard Interactivity

### TC-I1 — Series toggles
- Steps: click Volume / MSF / Txns legend buttons.
- ✔ Each line shows/hides; Txns draws on the right axis; hidden ones strike through; tooltip/scrubber/avg respect visibility.

### TC-I2 — Hover scrubber
- ✔ Moving across the chart updates the readout strip (date + visible-series values); leaving shows "Latest · <date>".

### TC-I3 — Brush zoom
- ✔ Dragging the brush narrows the date window; avg line + scrubber stay consistent; works for 90D/YTD.

### TC-I4 — Donut click-to-pin (touch-safe) **(CRITICAL for tablet)**
- Steps: click a slice or legend row; click again.
- ✔ Pins focus (others dim, center label sticks, "● PINNED" shown); second click unpins; works via tap on touch devices where hover does nothing.

### TC-I5 — Period switch + drilldowns
- ✔ 7D/30D/90D/YTD refetch; KPI cards navigate to their drilldown routes; merchant rows navigate.

### TC-I6 — Reduced motion
- ✔ With OS "reduce motion", CountUp/animations are disabled; values still render.

### TC-I7 — Empty/loading states
- ✔ Skeletons while loading; EmptyState + "Upload Data" when no data; no console errors.

---

## J. Report Manager — Multi-Tenant (Super Admin)

### TC-J1 — Active-tenant scoping **(CRITICAL)**
- Steps: as Super Admin pick Tenant A in the switcher → Report Manager.
- ✔ Banner reads "Generating for: <Tenant A>"; merchant count = Tenant A only.

### TC-J2 — Unknown-tenant guard
- Steps: ensure no tenant selected (if reachable).
- ✔ Banner shows "Unknown Tenant"; generation returns 403 / fails clearly — does **not** silently run cross-tenant.

### TC-J3 — Scope ALL / ONE / FILE
- ✔ ALL = every merchant in active tenant; ONE = the entered MID only; FILE = MIDs from CSV/TXT (unmatched MIDs reported as skipped).

### TC-J4 — Delivery modes
- ✔ Local / S3 / Email / Email+S3 each behaves and the mode badge + logs match.

### TC-J5 — Per-tenant isolation of output **(CRITICAL)**
- Steps: generate for Tenant A, switch to Tenant B, generate.
- ✔ PDFs land under `reports/<bankShortCode>/<YYYY-MM>/` per tenant; no mixing; "Download All" zips only the active tenant's files.

### TC-J6 — Confirm dialog
- ✔ Dialog shows the active tenant and warns reports include only that tenant's merchants; multi-tenant users get the amber "you have access to N orgs" note.

---

## K. Connections & Resources (Regression)

### TC-K1 — No idle-in-transaction after big upload **(CRITICAL)**
- Steps: upload a large multi-month file; during/after, run:
  ```sql
  SELECT count(*) FROM pg_stat_activity WHERE state='idle in transaction';
  ```
- ✔ Returns 0; no "Connection is closed" errors; log shows `Manual Ingestion Completed for all dates.`

### TC-K2 — No connection leak
- Steps: exercise dashboards, uploads, an external integration pull, a maintenance run; watch Hikari.
- ✔ No `leak-detection-threshold` stack traces; active connections return to idle; pool never exhausts (stays < max 30).

### TC-K3 — Maintenance VACUUM doesn't exhaust pool
- ✔ During a maintenance pass, app remains responsive; VACUUM borrows/returns one connection per table (not one held for the whole pass).

### TC-K4 — External pull closes everything
- Steps: run an Integration / DB-pull report.
- ✔ External `Connection`/`ResultSet` closed; no orphaned `setNetworkTimeout` executor threads accumulate over repeated pulls.

---

## L. Smoke / Sanity (run last, full stack up)

- TC-L1 Login (Super Admin + Bank Admin) succeeds; JWT issued.
- TC-L2 Each dashboard (Executive, Finance, Transactions, Merchant Insight, Daily Merchant, Leaderboard, Geo, Zero Transaction, Attrition) loads without console/network errors.
- TC-L3 Single file upload completes end-to-end; fact + summary rows present; dashboard reflects it.
- TC-L4 Generate one merchant PDF; opens and renders.
- TC-L5 Menu/RBAC: each role sees only its permitted menu items.
- TC-L6 Tenant switch (Super Admin) re-scopes dashboards and report manager.

---

## M. Deployment & Rollback Checklist

- [ ] DB backup taken **before** deploy (and verified restorable — TC-H1/H4).
- [ ] Prod migrations A3 applied + verified (A4).
- [ ] `acquira-core` jar deployed; `systemctl restart acquira`; log shows `Started CoreApplication`.
- [ ] Frontend dist copied + `restorecon` + cache-busted (A5).
- [ ] Post-deploy smoke (Section L) green.
- [ ] Maintenance window/timezone confirmed (B13); job enabled.
- **Rollback:** redeploy previous jar + previous frontend dist. New tables/indexes/menus are additive and safe to leave; if required, `DELETE FROM sys_menu WHERE path='/admin/maintenance'` and drop `db_maintenance_*` / the new index. Restore the pre-deploy DB backup only if data was affected.

---

## Sign-off

| Area | Owner | Result (Pass/Fail) | Notes |
|---|---|---|---|
| A. Build & Migrations | | | |
| B. Maintenance Job | | | |
| C. Autovacuum | | | |
| D. Zero Transaction | | | |
| E. Attrition | | | |
| F. Security | | | |
| G. Index | | | |
| H. Backup | | | |
| I. Dashboard | | | |
| J. Report Manager | | | |
| K. Connections | | | |
| L. Smoke | | | |

**Go / No-Go:** ____________   **Date:** ____________   **Approver:** ____________
</content>