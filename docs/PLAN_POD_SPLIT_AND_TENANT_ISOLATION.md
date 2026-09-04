# Acquira — Deferred Architecture Plans

> Recorded 2026-08-21. Both plans are **assessed against the actual code** (coupling audits
> below were verified by grep/inspection, not assumed) and **parked** until scheduled.
> Branch context at time of writing: `deploy/kubernetes-aws`.

---

## Plan 1 — Split the fat pod into multiple pods (web / worker / pdf / ai)

### Current state

`CoreApplication` component-scans `batch + pdf + ai` into a single JVM
(`@ComponentScan(basePackages = {"com.acquira.common", "com.acquira.core", "com.acquira.batch", "com.acquira.pdf", "com.acquira.ai"})`),
and `deploy/docker/Dockerfile.core` bundles all four modules into one image. The k8s
Deployment (`deploy/k8s/05-core.yaml`) is pinned to `replicas: 1` **on purpose**: that one
process runs every scheduler (EmailQueueProcessor, ScheduledDbPullJob, ChurnRetrainScheduler,
ExplorerAlertScheduler, DatabaseMaintenanceService, ApiRequestLogRetentionScheduler,
DynamicSchedulerService) plus Spring Batch. Two replicas would double-send emails,
double-run pulls, and collide on batch jobs. Consequence: the API tier cannot scale at all,
and the 4Gi memory limit exists mostly because Chromium (Playwright PDF) lives inside the
same JVM as the web tier.

### Coupling audit (verified)

| Path | Coupling | Verdict |
|---|---|---|
| core → batch | **zero Java imports**; batch has its own controllers (`/api/batch/**`, `/api/upload`, `/api/admin/{migration,integration,backups,interchange-normalization}`) | splits by ingress path routing alone |
| core → ai | **zero imports**; `/api/ai` self-contained | splits clean |
| core → pdf | **2 call-sites** inject `PlaywrightPdfService` in-process: `EmailController`, `CampaignExecutionService` | the only real code change |
| pdf → core | none; `MerchantInsightController` is already `@ConditionalOnMissingClass("com.acquira.pdf.controller.PdfController")` | split-ready by design |
| Auth | stateless JWT (`acquira-common/security`), no server session | replica-safe |
| Files | reports + uploads dirs shared between web/pdf/batch | needs RWX volume (EFS on EKS — mapping already in `deploy/README.md`) |

### Target topology — 5 pods

| Pod | Modules | Replicas | Resources | Why |
|---|---|---|---|---|
| `acquira-web` | core + common (no batch/pdf/ai scan) | 2+, HPA | ~1Gi | dashboards scale independently |
| `acquira-worker` | batch + common + all `@Scheduled` beans | **1 pinned**, `Recreate` | 1–2Gi | schedulers/Spring Batch stay singleton; upload spikes stop stalling dashboards |
| `acquira-pdf` | pdf + common + Chromium | 1–2 | 2Gi | isolates the memory hog; a Chromium crash no longer kills the API |
| `acquira-ai` | ai + common | 1 | 512Mi | optional; cheapest to defer (can stay in web initially) |
| `acquira-frontend` | nginx | 2 | as-is | already separate (`06-frontend.yaml`) |

Routing needs no service mesh — URL namespaces are already disjoint; the ingress fans out
by path prefix (batch/upload/admin-migration → worker, `/api/ai` → ai,
`/api/external/reports` + `/api/business/insights` → pdf, everything else → web).

### Phases

**Phase 1 — decouple in code (no infra change)**
1. Replace the two `PlaywrightPdfService` injections with an HTTP client
   (`PDF_SERVICE_URL` env) behind an interface, keeping an in-process fallback so the
   monolith build still works.
2. Gate every scheduler on `@ConditionalOnProperty("acquira.scheduling.enabled")` —
   web pods run `false`, worker runs `true`. **This alone is what unpins `replicas: 1`.**
3. Trim `CoreApplication`'s scan to `common + core` via profile; keep a `monolith`
   profile scanning everything so local dev stays one process.

**Phase 2 — images & manifests**
4. Four Dockerfiles from the same multi-stage build (`-pl acquira-<module> -am`);
   only the pdf image carries the Playwright/Chromium layer.
5. Split `05-core.yaml` into web (replicas 2 + HPA), worker (1, `Recreate`), pdf, ai;
   add path-routing rules to `07-ingress.yaml`.
6. Reports/uploads PVC → RWX (kind: RWX-capable provisioner; EKS: EFS).

**Phase 3 — verify on kind, then EKS**
7. Smoke: login → dashboard (web); file upload → summary rebuild (worker); statement
   PDF lands in the shared volume and downloads from web; AI assistant answers;
   **kill the pdf pod mid-render and confirm web stays up**.
8. Scale test: 2 web replicas, confirm zero duplicate emails/pulls.

**Deferred deliberately:** DB split, message queue between web→worker, per-tenant pods.

---

## Plan 2 — Per-tenant data isolation (schema-per-tenant vs RLS)

### What the code says (verified)

- **1,260 `tenant_id` references** across Java; **20 repositories build native SQL**.
  A migration that rewrites queries to `tenant_7.sum_daily_full` is not a real option.
- Lucky property: **every native query uses unqualified table names**
  (`FROM sum_daily_terminal`, never `public.sum_daily_terminal`). Postgres `search_path`
  switching therefore gives schema-per-tenant **without touching any of the 1,260
  references** — the existing `tenant_id = :tenantId` predicates stay as harmless
  defense-in-depth.
- `TenantContext` (ThreadLocal, populated by `JwtRequestFilter`) already exists —
  exactly the hook Hibernate schema-multitenancy needs. (`TenantFilter` itself is a
  disabled placeholder after the IDOR fix; validation lives in `JwtRequestFilter`.)
- **Real blocker:** `TenantContext.visibleTenants` — a multi-tenant *read* scope used by
  group/executive reports (`GroupAnalyticsController` etc.). `tenant_id IN (...)` over one
  table becomes `UNION ALL` across N schemas after a split. This is the feature the
  migration genuinely fights with.

### Schema-per-tenant plan

**Phase 0 — table classification (design doc)**
Global in `public`: tenants, users, roles, sys_menu, ref_country, ref_tenant_bin_bank,
SMTP/S3/SSO config, Spring Batch metadata. Per-tenant in `tenant_<id>`: dim_merchant /
dim_store / dim_terminal, fact tables, all `sum_*`, staging, report cache.
Rule of thumb: carries `tenant_id` → moves; keyed by it → stays global.

**Phase 1 — plumbing (the only Java work)**
1. `CurrentTenantIdentifierResolver` reading `TenantContext.getCurrentTenant()`.
2. `MultiTenantConnectionProvider`: `SET search_path TO tenant_<id>, public` on
   connection checkout, reset to `public` on release. Plain Hikari is fine;
   **pgBouncer in transaction mode would break this** — relevant on the AWS branch.
3. Fail closed: no resolved tenant → nonexistent schema → error (same philosophy as
   the repository-level `requireTenant()`).

**Phase 2 — provisioning**
`TenantProvisionController` already runs per-tenant scripts — extend it:
new tenant = `CREATE SCHEMA tenant_<id>` + apply a versioned DDL template
(tables, indexes, partitions, sequences). The template becomes the single source of
per-tenant schema truth.

**Phase 3 — data migration, one tenant at a time**
Create schema → `INSERT INTO tenant_X.t SELECT * FROM public.t WHERE tenant_id = X` →
row-count + checksum parity → flip that tenant's flag → delete old rows only after a
soak period. Tenants migrate independently; rollback = flip the flag back.

**Phase 4 — the two hard edges**
- **Group reports:** generate per-group `UNION ALL` views in `public`, rebuilt when
  group membership changes, so `visibleTenants` queries keep hitting one relation.
- **Migration multiplication:** every schema change now runs N× (once per tenant
  schema). Migrations are currently **gitignored and hand-applied** — this multiplies
  the riskiest existing process. A schema-iterating migration runner is required, and
  migration version-tracking should be fixed **before** this project starts, not during.

### Cheaper alternative — Postgres Row-Level Security (RLS)

If the driver is purely *"one tenant must never see another's data"*: a policy per table
on `tenant_id`, enforced by the database itself. Zero data movement, works with the
columns already in place, does not break group reports (`tenant_id IN` still works under
a policy on the visible set). Roughly a week of work vs. a multi-week migration.

### Decision rule (open — awaiting the driver)

| Driver | Pick |
|---|---|
| Isolation / compliance ("never see another tenant's rows") | **RLS first** |
| Per-tenant operability (backup / restore / export / residency) | **Schema-per-tenant plan** |

---

## Sequencing note

If both plans proceed, do the **pod split first** — it is smaller, independent, and the
worker pod becomes the natural home for the schema-migration runner. Fix migration
version-tracking (the `.gitignore` `migration/` pattern) before either plan touches
production schemas.
