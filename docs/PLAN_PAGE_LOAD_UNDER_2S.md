# Plan: every page loads under 2 seconds

Status: **NOT APPLIED** — audit done 2026-09-03, all changes deferred by decision.
The gzip edit to `deploy/docker/nginx.conf` was made and then rolled back on request;
the exact block to re-apply is below.

---

## Where the time goes today (audited findings)

| Layer | Problem | Evidence |
|---|---|---|
| Bundle | ~500 KB gz shell before any page code; `vendor-icons` alone is 876 KB raw / 163 KB gz | `frontend/src/components/Layout.jsx:4` `import * as LucideIcons` + `LucideIcons[menu.iconKey]` defeats tree-shaking; chunk is modulepreloaded in `dist/index.html` |
| Serving | Container nginx ships everything **uncompressed** (no gzip block); no HTTP/2/TLS anywhere | `deploy/docker/nginx.conf` (the RHEL config `scripts/nginx-acquira.conf:107` *does* have gzip) |
| Serving | SPA deep links (`/dashboard`) get no Cache-Control — internal `try_files` fallback skips the `= /index.html` block | `deploy/docker/nginx.conf` `location /`, same in `scripts/nginx-acquira.conf:100` |
| Shell | Whole tree blocks on `/auth/session` before Layout or any page mounts | `frontend/src/components/ProtectedRoute.jsx:22-36`; endpoint does 3 sequential lookups (`AuthController.java:780-800`) |
| Backend | ~4 DB queries per authenticated request, 2 redundant; open-in-view defaults true, pins pool conn per request | `acquira-common/.../security/JwtRequestFilter.java:119,124,175`; `spring.jpa.open-in-view` unset |
| Backend | Hot dashboards uncached + sequential internals | `/business/revenue-kpis` = 4 sequential native queries (`RevenueKpiController.java:77-117`); `/finance/dashboard/kpis` = 4 sequential scans (`FinanceController.java:184-189`); also uncached: kpis-filtered, volume-revenue-summary, finance trends/profitability |
| Backend | Card-type / destination / local-debit dashboards query `fact_transaction` directly | `CardTypeDashboardRepository.java:126`, `DestinationDashboardRepository`, `LocalDebitBankDashboardRepository.java:24` |
| Pages | Attrition serialises data-bounds → report (2 RTT before render); FinanceDashboard fires 4 requests from 3 effects; almost no AbortController → abandoned queries hold the 30-conn pool | `AttritionReport.jsx:271-284`, `FinanceDashboard.jsx:106-138` |
| API caching | No ETag / Cache-Control on any JSON; `apiCache.js` used only by useDataBounds + BusinessFilters, and every hit fires a silent revalidate refetch | no `ShallowEtagHeaderFilter` anywhere |

Full audit details: memory note `acquira-page-load-audit-2026-09-03`.

---

## Fix order (impact per effort)

### 1. gzip in the container nginx  (config-only, ~70% payload cut)

Compress at the **container level**, not ingress/host — `gzip_proxied any` lets it pass
through any proxy; do NOT also enable gzip upstream (double compression, wasted CPU).
If the box is the bare-metal layout using `scripts/nginx-acquira.conf`, gzip is already on.

Insert into `deploy/docker/nginx.conf` right before the `client_max_body_size 2g;` line:

```nginx
    # Compression: bundle is ~2.6 MB JS/CSS raw, ~700 KB gzipped.
    gzip on;
    gzip_static on;         # serves a pre-built .gz next to the asset when present
    gzip_vary on;
    gzip_proxied any;
    gzip_comp_level 6;
    gzip_min_length 1024;
    gzip_types
        text/plain text/css text/javascript application/javascript
        application/json application/xml image/svg+xml
        font/ttf font/otf application/vnd.ms-fontobject;
```

Ubuntu EC2 commands (repo root, after pulling the change):

```bash
# validate config inside the real image
docker run --rm -v "$PWD/deploy/docker/nginx.conf:/etc/nginx/conf.d/default.conf:ro" nginx:1.27-alpine nginx -t

# docker-compose deployment
docker compose -f deploy/docker/docker-compose.yml up -d --build frontend

# OR kind/k8s deployment
docker build -t acquira-frontend:local -f deploy/docker/Dockerfile.frontend . \
  && kind load docker-image acquira-frontend:local --name acquira \
  && kubectl -n acquira rollout restart deploy/acquira-frontend \
  && kubectl -n acquira rollout status deploy/acquira-frontend

# verify: expect "Content-Encoding: gzip" and low-hundreds-KB length
curl -sI -H 'Accept-Encoding: gzip' http://<host>/assets/<any-vendor-chunk>.js \
  | grep -iE 'content-encoding|content-length'
```

### 2. Kill the icon mega-chunk (~160 KB gz off every cold load)
Replace `import * as LucideIcons` in `Layout.jsx` with a static map of only the
iconKeys actually present in `sys_menu` (named imports), fallback `Circle`.

### 3. Non-blocking session check
`ProtectedRoute`: render immediately when a token exists in storage, validate
`/auth/session` in background, redirect on failure. Page data fetch then runs in
parallel with the session check.

### 4. Per-request auth overhead
In `JwtRequestFilter`, reuse the user + tenant-access already loaded by
`CustomUserDetailsService` (drop the duplicate `findByUsername` at :124 and
`findByUser` at :175); optionally cache the resolved principal per token ~60s.
Set `spring.jpa.open-in-view=false`.

### 5. Cache + parallelise hot endpoints
Put revenue-kpis, dashboard/kpis-filtered, finance kpis/trends/profitability,
volume-revenue-summary behind the existing `ReportCache` (same ingest eviction,
add to `ReportCacheWarmup`). Inside, merge/parallelise the sequential queries
(finance kpis: 4 range scans are all subsets of one range → one query).

### 6. Move fact_transaction dashboards to summary tables
Card-type + destination → `sum_daily_full`; local-debit → `sum_daily_local_debit_bin`.
Until then, at minimum wrap them in ReportCache.

### 7. Page fetch shape
Fire attrition report without waiting for bounds; collapse FinanceDashboard into
one parallel batch; add AbortController on route change everywhere.

### 8. HTTP caching on API
`ShallowEtagHeaderFilter` on GET report responses (304s on revisit); short
Cache-Control on `/business/filter-options` + `/business/data-bounds`; drop the
unconditional `revalidate()` refetch in `apiCache.js`.

### Also worth doing at the serving layer
- Add a `Cache-Control: no-store` header to the SPA fallback (use a named
  `@spa_fallback` location or `add_header` inside `location /`).
- HTTP/2 + TLS at the edge (ingress/ALB or host nginx) — HTTP/1.1's 6-connection
  limit hurts the many-chunk bundle.
- Consider deleting the duplicate index `idx_sum_daily_merchant_tenant_date`
  (identical to `idx_sum_merch_tenant_date`) — write-path cost, not read.

---

## Verification once applied
Chrome performance trace on deployed UAT for the three worst pages:
Business Dashboard, Finance Dashboard, Attrition. Budget: shell < 200 KB gz over
HTTP/2, 0 blocking round-trips before first render, 1–2 cached API calls per page.
