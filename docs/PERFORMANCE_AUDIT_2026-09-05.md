# Acquira / Nexus — Full Performance Audit

**Date:** 2026-09-05 · **Branch:** `deploy/kubernetes-aws`
**Scope:** Backend (acquira-core web/API), database (Postgres warehouse), ingestion (acquira-batch), frontend (React/Vite), and deployment (Docker + k8s).
**Method:** White-box source audit verified against current code and the actual `frontend/dist` build output (not just prior plans). Stack: Spring Boot 3.2.0 / Java 21 / HikariCP / Caffeine cache; React + Vite + nginx.
**Prior context:** `docs/PLAN_PAGE_LOAD_UNDER_2S.md` (2026-09-03) — marked NOT APPLIED. Re-verified: its two headline frontend items are still open; most backend items still open; the "dashboards hit `fact_transaction` directly" concern is now largely fixed.

---

## 1. Executive summary

The platform's heavy lifting is in good shape: ingestion hot spots flagged in prior audits are genuinely fixed (two-phase fee pass, staging run-scoping, rental quadratic dedupe), summary tables now back the dashboards instead of the raw fact table, transaction listing uses keyset paging, and route-level code-splitting is excellent. Index coverage on the hot query paths is adequate.

The remaining cost is concentrated in three places:

1. **Per-request auth overhead** — every authenticated call pays 4 DB round-trips before its own work, 2 of them exact duplicates.
2. **Cold page load** — a ~1.9 MB **uncompressed** JS shell (no nginx gzip) with a 897 KB icon mega-chunk on the critical path, plus one blocking session round-trip before first paint.
3. **A handful of uncached, multi-query dashboard endpoints** and **connection-holding config** (`open-in-view=true` + `statement_timeout=0` on the interactive pool) that together let a few slow requests exhaust the 30-connection pool.

| Area | Critical | High | Medium | Low |
|---|---|---|---|---|
| Backend / DB | 1 | 4 | 3 | 5 |
| Frontend / Deploy | 2 | 1 | 3 | — |

**Highest impact-per-effort:** (a) add nginx gzip and (b) fix the icon import — two config/one-file changes that cut the cold-load wire payload from ~1.9 MB to ~350 KB; (c) de-duplicate the auth filter's queries — halves per-request DB load across the whole app.

---

## 2. Backend & database

### B-1 — Duplicate user + tenant-access loads on every request · **Critical** · Low effort
`acquira-common/.../security/JwtRequestFilter.java:119,124,175` + `CustomUserDetailsService.java:27,30`
`doFilterInternal` calls `loadUserByUsername()` (which already runs `findByUsername` + `findByUser`), then **repeats both** — `findByUsername` again at :124 and `findByUser` again at :175. That is **4 DB round-trips per request, 2 exact duplicates**, before any endpoint logic runs; lazy `getTenant()`/`getSysUserGroup()` access can add more per-row selects.
**Fix:** Return a custom `UserDetails` carrying the `User` + access list and reuse it in the filter (drop the :124/:175 re-queries), or cache the resolved principal per token ~30–60s (the file already does this for `getAllTenantIdsCached`). Ensure `UserTenantAccess.tenant`/`sysUserGroup` are join-fetched.

### B-2 — `spring.jpa.open-in-view` left at default (true) · **High** · Trivial
Not set in any active properties file (only documented as intended in the dev guide). OSIV holds the Hibernate session + its DB connection for the **entire request including JSON serialization**. Combined with B-3 and no frontend AbortController, an abandoned/slow report pins a Hikari connection until it fully completes.
**Fix:** `spring.jpa.open-in-view=false`. All hot reads use native-query DTOs, so nothing relies on lazy loading during serialization.

### B-3 — `statement_timeout=0` on the interactive pool · **High** · Low effort
`application.properties:363` (`connection-init-sql=... SET statement_timeout=0 ...`). Correct for batch, but the **same pool serves dashboards**, so a runaway analytic query (bad filter, huge range) runs unbounded and holds its slot. Only the AI path re-imposes a limit (`SET LOCAL`, 15s).
**Fix:** Separate read-only pool for interactive endpoints with `statement_timeout` ~20–30s, or wrap report queries in `SET LOCAL statement_timeout` (the AI path proves the pattern). Keep `0` only on the batch pool.

### B-4 — Volume/Revenue drill-down: `TO_CHAR(business_date)` on the index/partition key · **High** · Low effort
`acquira-common/.../repository/VolumeRevenueRepository.java:754,757,761` — filters `TO_CHAR(s.business_date,'YYYY-MM')=:parentMonth` / `...'YYYY-MM-DD'=:parentDay`, defeating the `(tenant_id,business_date)` index and partition pruning on every explorer expand. The **same file documents the correct range-predicate fix at line 2480** — internal inconsistency.
**Fix:** Range predicates: month → `business_date >= :monthStart AND business_date < :nextMonthStart`; day → `business_date = :day` (parse the label to `LocalDate` in Java, as the `preciseDateList` path already does).

### B-5 — Backfill business-metrics: unbounded full-tenant fact scan, once per backfilled day · **High** · Medium effort
`acquira-batch/.../BackfillIngestionService.java:543-577` (`calculateBusinessMetrics`), called per-day from `processSingleDate:259`. The `merchant_activity_summary` INSERT joins all merchants against `fact_transaction` with **no `payment_date` bound**, once per day → O(days × full-tenant-scan × merchants). Same bug already fixed on the upload path.
**Fix:** Port the upload job's guards (`TransactionJobConfig.calculateBusinessMetricsTasklet:1995-2027`): constant `payment_date` envelope + `merchant_id IN (staged scope)`; better, lift it out of the per-day loop and run once over all landed dates.

### B-6 — `/finance/dashboard/kpis` — 4 sequential range queries, entity hydration + Java sum, uncached · **High** · Medium effort
`acquira-core/.../controller/FinanceController.java:184-189`. Four nested-range `findByTenantIdAndBusinessDateBetween` calls each return `List<SumDailyBank>` entities into memory and sum in Java; `POST /kpis-filtered` (:251-254) is the same shape over the larger `sum_daily_insight`. No `ReportCache`.
**Fix:** Collapse to one query with conditional aggregation (`SUM(...) FILTER (WHERE business_date BETWEEN ...)` per bucket) returning scalars, and wrap in `ReportCache` with ingest eviction like the other executive endpoints.

### B-7 — Login recomputes tenant-settings ~8× per attempt · **Medium** · Low effort
`acquira-core/.../service/SecurityPolicyService.java:40-52`, called from `AuthController` at :157,168,169,288,289,302,363,404,433. Each typed accessor independently re-runs `findByTenant_TenantId` — ~8 identical round-trips on the most brute-force-exposed endpoint.
**Fix:** Load the settings map once per request and pass to accessors, or short-TTL cache keyed by tenant.

### B-8 — `/api/business/revenue-kpis` uncached (2–3 sequential native queries) · **Medium** · Low effort
`RevenueKpiController.java:76-138`. Correctly on summary tables, but recomputed every render. Minor non-sargable bits (`ILIKE '%'+name+'%'`, `CAST(... AS DATE)`) are on the small `dim_merchant` — low impact.
**Fix:** Wrap in `ReportCache`; merge/cache the `MAX(business_date)` probe.

### B-9 — Card-type / Destination / Local-debit dashboards uncached · **Medium** · Low effort
These now correctly query summary tables (the 09-03 "hits fact_transaction directly" concern is resolved) but remain uncached. Wrapping in `ReportCache` is a cheap win.

### B-10 — `TO_CHAR(payment_date,'YYYY-MM')` on `fact_transaction` in statement rendering · **Medium** · Low effort
`TemplateRendererService.java:132` — scans all of a merchant's rows and filters the month in memory; runs per statement/PDF render.
**Fix:** Sargable range `payment_date >= :monthStart AND payment_date < :monthStart + INTERVAL '1 month'`.

### Low
- **B-11** Migration merchant match uses bidirectional `LIKE col||'%'` (`BulkMigrationService.java:786-789,844-845`) — SA-tool only; prefer exact `mid` match.
- **B-12** Rental trend `TO_CHAR` group-by (`RentalController.java:207-210`) — small table; cosmetic.
- **B-13** DataExplorer `CAST/LIKE` on filter cols (`DataExplorerController.java:313,326`) — summary table, `LIMIT 5000`; acceptable.
- **B-14** Single-agent detail scans whole-tenant grouped COUNT (`SalesAgentProfileService.java:112`) — add a filtered COUNT.
- **B-15** Per-key query+save loop on password-policy update (`AdminController.java:411-426`) — batch `saveAll`.

### Verified already-optimized (do not re-flag)
Two-phase fee pass (`FeeComputationService.java:416-530`); staging run-scoping via `ingest_run_id` (`clearRunStagingStep:644-663`); rental single-pass dedupe (`RentalJobConfig.java:442-448`); keyset paging (`TransactionController.java:148-174`); `MerchantController` `Pageable` + batched lookups; `EmailController` `findAll→findAllByTenantId`. Index coverage on hot fact/summary paths is adequate (minor: drop the duplicate `idx_sum_daily_merchant_tenant_date`).

---

## 3. Frontend & deployment

### Measured cold-load shell (verified against `frontend/dist/assets`)
| Chunk | Raw | Gzip | Critical path? |
|---|---|---|---|
| vendor-icons | 897 KB | **167 KB** | YES (Layout eager) |
| vendor-mui | 394 KB | 118 KB | YES (Drawer/Tooltip) |
| vendor-misc | 265 KB | 93 KB | YES |
| vendor-react | 234 KB | 75 KB | YES |
| index (shell) | 130 KB | 37 KB | YES |
| vendor-charts | 471 KB | 123 KB | No (lazy) |
| vendor-mui-x | 371 KB | 110 KB | No (lazy) |

**Critical-path shell ≈ 490 KB gzip / ~1.9 MB raw** — and because nginx sends it **uncompressed**, the browser pulls ~1.9 MB of JS on a cold load.

### F-1 — Icon mega-chunk: `import * as LucideIcons` · **Critical** · Low effort
`frontend/src/components/Layout.jsx:4`. The namespace import + dynamic `LucideIcons[menu.iconKey]` lookup pulls the entire 44 MB icon set into `vendor-icons` (897 KB raw / 167 KB gz) and defeats tree-shaking. Layout is a **static** import (`App.jsx:9`), so this loads on every authenticated page — ~1/3 of the shell.
**Fix:** Named imports of only referenced icons + a static `ICONS` map for the `iconKey` lookup. Saves ~150 KB gz / ~850 KB raw.

### F-2 — nginx serves everything uncompressed (no gzip/brotli) · **Critical** · Trivial
`deploy/docker/nginx.conf` has cache headers but no `gzip on` block; `07-ingress.yaml` has no compression annotation — no compression at any layer. Text assets compress ~70–80%; the shell alone drops ~1.9 MB → ~490 KB.
**Fix:** Add the gzip block (recipe at `docs/PLAN_PAGE_LOAD_UNDER_2S.md:37-49`) + `gzip_static on` with precompressed `.gz`/`.br` at build time. Highest impact-per-effort fix in the whole audit.

### F-3 — Session validation blocks the whole shell on `/auth/session` · **High** · Low effort
`frontend/src/components/ProtectedRoute.jsx:25-73` renders a "Validating Session…" placeholder and mounts nothing until `GET /auth/session` (≈3 server-side lookups) resolves — one serial RTT before any paint, on every cold load/refresh.
**Fix:** Optimistic render when a token exists; validate in the background and redirect only on actual rejection, so page data fetches run in parallel with the check.

### F-4 — Layout drags MUI core onto the critical path · **Medium** · Medium effort
`Layout.jsx:3` imports `Drawer`/`Tooltip`/`useMediaQuery`, forcing `vendor-mui` (118 KB gz) + emotion into the shell — for a sidebar that is otherwise hand-rolled CSS (the mobile branch already uses a plain `<nav>` at :523).
**Fix:** Plain `<nav>` + CSS/title-attribute tooltips + a small `matchMedia` hook; MUI then loads only on grid pages.

### F-5 — Core deployment: TCP-only liveness/readiness probes · **Medium**
`deploy/k8s/05-core.yaml:45-54` — actuator isn't on the classpath, so all probes are `tcpSocket:8081`. A deadlocked/GC-thrashing/pool-wedged JVM that still holds the socket passes liveness forever and never restarts. With `replicas:1` + `strategy:Recreate`, any restart is full downtime.
**Fix:** Add spring-boot-actuator; point readiness/liveness at `/actuator/health/{readiness,liveness}` with tuned `failureThreshold`.

### F-6 — JVM heap shares a 4Gi limit with Chromium + batch in one process · **Medium**
`Dockerfile.core:43` (`MaxRAMPercentage=65` → ~2.6 GB heap) + `05-core.yaml:37-39` (4Gi limit). Same container runs Playwright/Chromium (~200–400 MB/page) and Spring Batch. A PDF batch concurrent with ingestion can exceed 4Gi → OOMKill of the sole replica = outage.
**Fix:** Lower `MaxRAMPercentage` to ~50 or raise the limit to 6–8Gi; cap concurrent Playwright contexts. Longer term: the documented web/worker split.

### F-7 — No AbortController on route-change fetches · **Medium** (per prior plan, not re-verified line-by-line)
Abandoned queries keep holding pool connections; serial bounds→report fetches add RTTs before first paint. Confirm current state before implementing; add AbortController keyed to route/params and collapse multi-effect fetch fans into one parallel batch.

### Checked and OK
Route code-splitting + hover/idle prefetch (`App.jsx:17-94`, `vite.config.js:20-54`); keyset pagination in `TransactionList.jsx`; correct `no-store` index.html + immutable 1y `/assets/` caching; frontend pod resources fine. Note: `public/fonts/*.ttf` are dead assets (no `@font-face`; app uses system fonts) — delete, not a load cost.

---

## 4. Recommended order of work
1. **nginx gzip (F-2)** + **named icon imports (F-1)** — ~1.9 MB → ~350 KB cold load. Config + one file.
2. **De-dup auth filter queries (B-1)** — halves per-request DB load app-wide.
3. **`open-in-view=false` (B-2)** + **bounded interactive `statement_timeout` (B-3)** — protects the connection pool.
4. **Optimistic session render (F-3)** — removes the one blocking RTT before paint.
5. **Cache `/finance/dashboard/kpis` + the uncached dashboards (B-6/B-8/B-9)**; fix the drill-down sargability (B-4).
6. **Backfill fact-scan bound (B-5)**; reliability-under-load fixes (F-5/F-6).
