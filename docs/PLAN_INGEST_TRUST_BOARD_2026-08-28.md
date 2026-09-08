# Plan — Ingestion Trust & Data-Quality Board

**Date:** 2026-08-28
**Route:** `/ops/ingest-trust`
**Goal:** make every ingestion defect visible *before* someone opens a dashboard and trusts a wrong number.

---

## 1. Why

Five defects were found in UAT that should have been found by the platform itself:

| Defect | Where | Why it was invisible |
|---|---|---|
| Progress says "Complete!" at step 4 of 12 | `BatchProgressController.buildProgressPayload` sums `readCount` over **all** steps; the partitioned `masterIngestStep` alone reaches `totalReqRows` | No stage-weighted progress model |
| REPLACE wipes a whole day | `stagingToFactTasklet` deletes `fact_transaction` by **date**, so a 200-row resend destroys a 400k-row day | Nothing records what was deleted |
| Staging wiped by concurrent upload | `cleanTargetDayTasklet` runs `DELETE FROM stg_trnx_raw WHERE tenant_id = ?` — no day scope despite the name | Staging has no per-run scope |
| BH fee pass ran 1.7h | `populateSummaryStep` | No per-stage duration history, no SLA |
| BH rate card dead → fees all zero | fee pass silently priced nothing | No fee-coverage assertion |

Common root cause: **there is no durable, tenant-scoped record of what was ingested.** Spring Batch metadata is the only trace, and it:

- has no queryable `tenant_id` (it lives inside `JobParameters` as a string key),
- has no file identity, hash, or size,
- aggregates row counts in a way that is wrong for partitioned steps,
- does not cover `BackfillIngestionService` or `BulkMigrationService`, which are not Spring Batch jobs at all,
- is a purge target for `DatabaseMaintenanceService`.

The board is therefore **a ledger first and a screen second**. The screen is the cheap part.

---

## 2. The four questions the board answers

1. **Did the data arrive?** — freshness, expected-vs-actual day coverage, per tenant.
2. **Did all of it arrive?** — 4-tier reconciliation: file rows → staged → facted → summarised.
3. **Did it arrive on time?** — per-stage duration trend with an SLA line.
4. **Is it still the data we think it is?** — destructive-REPLACE detection, reload counts per day, fee-pricing coverage.

---

## Phase 0 — Fix the lying counters first

The board reports numbers. If the numbers are wrong the board is worse than nothing, because it manufactures confidence. Do these before anything else.

### P0-1 — Stage-weighted progress (0.5 day)

`buildProgressPayload` must stop deriving percentage from summed `readCount`.

- Attribute `readCount` to the ingest steps only (`masterIngestStep` + its partition children), not every step.
- Replace row-based percentage with a **stage-weight table**, e.g.
  `ensurePartitions 1, splitExcel 5, cleanTargetDay 1, masterIngest 30, analyzeStaging 3, autoCreateDimensions 3, stagingToFact 25, populateSummary 20, calculateBusinessMetrics 5, scoreMl 3, computeSegments 2, calculateDailyDashboard 2`
  Progress = completed weight + (current step weight × its own row fraction). Weights are re-tunable from the observed p50 durations the ledger will start collecting.
- Keep `TOTAL_STEPS` in sync — or better, derive it from the job definition instead of a hardcoded `Map.of`, which the existing comment already warns will go stale.

### P0-2 — Scope staging by run (1 day)

- `ALTER TABLE stg_trnx_raw ADD COLUMN ingest_run_id BIGINT;` plus index `(tenant_id, ingest_run_id)`.
- Pass `ingestRunId` as a job parameter; the CSV worker's `INSERT INTO stg_trnx_raw (...)` (plain JDBC, so this is a simple column add) sets it.
- `cleanTargetDayTasklet` becomes `DELETE FROM stg_trnx_raw WHERE tenant_id = ? AND ingest_run_id = ?` — and gets renamed to match what it does.
- Add a stale-staging sweeper for rows whose run is terminal and older than N hours (the old blanket delete was doing that job by accident).

This is a prerequisite for a truthful `rows_staged`, and it removes the concurrent-upload wipe as a side effect.

### P0-3 — Make destructive REPLACE visible, then guarded (1 day)

- **Visible (do now):** before the fact delete, count existing rows per target date; record `fact_rows_deleted` and per-date before/after on the run. Nothing changes behaviourally.
- **Guarded (do next):** refuse the delete when incoming rows for a date are below `replace.guard.min_ratio` (default 0.5) of existing rows, unless the operator explicitly confirms. Make it a tenant setting, since some tenants legitimately resend small days. Fail the run with a clear message rather than silently destroying.

---

## Phase 1 — The ledger (2 days)

Migration `acquira-core/src/main/resources/db/migration/V2026_08_28_01__ingest_trust.sql`. Additive only — do **not** touch `schema.sql`'s DROP path.

```sql
CREATE TABLE IF NOT EXISTS ingest_run (
  id                   BIGSERIAL PRIMARY KEY,
  tenant_id            BIGINT      NOT NULL,
  source               VARCHAR(24) NOT NULL,  -- UPLOAD|SERVER_FILE|DB_PULL|BACKFILL|BULK_MIGRATION
  job_execution_id     BIGINT,                -- Spring Batch id; NULL for backfill/migration
  job_name             VARCHAR(64),
  file_name            VARCHAR(512),          -- sanitised, never raw getOriginalFilename()
  file_bytes           BIGINT,
  file_sha256          CHAR(64),              -- duplicate-resend detection
  load_mode            VARCHAR(16),           -- REPLACE|APPEND
  status               VARCHAR(16) NOT NULL,  -- RUNNING|COMPLETED|FAILED|STOPPED
  started_at           TIMESTAMP   NOT NULL,
  ended_at             TIMESTAMP,
  duration_ms          BIGINT,
  rows_file            BIGINT,
  rows_staged          BIGINT,
  rows_facted          BIGINT,
  rows_summarised      BIGINT,
  rows_rejected        BIGINT,
  fact_rows_deleted    BIGINT,
  min_txn_date         DATE,
  max_txn_date         DATE,
  distinct_days        INT,
  unresolved_merchants INT,
  fee_priced_pct       NUMERIC(5,2),
  recon_status         VARCHAR(16),           -- OK|GAP|UNKNOWN
  error_class          VARCHAR(255),
  error_message        TEXT,
  triggered_by         VARCHAR(128),
  correlation_id       VARCHAR(64),
  acknowledged_by      VARCHAR(128),
  acknowledged_at      TIMESTAMP,
  ack_note             TEXT
);
CREATE INDEX IF NOT EXISTS ix_ingest_run_tenant_started ON ingest_run (tenant_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_ingest_run_tenant_status  ON ingest_run (tenant_id, status);
CREATE INDEX IF NOT EXISTS ix_ingest_run_sha            ON ingest_run (tenant_id, file_sha256);

CREATE TABLE IF NOT EXISTS ingest_run_stage (
  id           BIGSERIAL PRIMARY KEY,
  run_id       BIGINT      NOT NULL REFERENCES ingest_run(id) ON DELETE CASCADE,
  stage_name   VARCHAR(64) NOT NULL,
  seq          INT         NOT NULL,
  status       VARCHAR(16),
  started_at   TIMESTAMP,
  ended_at     TIMESTAMP,
  duration_ms  BIGINT,
  rows_in      BIGINT,
  rows_out     BIGINT,
  rows_skipped BIGINT,
  note         TEXT
);
CREATE INDEX IF NOT EXISTS ix_ingest_stage_run  ON ingest_run_stage (run_id, seq);
CREATE INDEX IF NOT EXISTS ix_ingest_stage_name ON ingest_run_stage (stage_name, started_at DESC);

CREATE TABLE IF NOT EXISTS ingest_day_coverage (
  tenant_id       BIGINT NOT NULL,
  txn_date        DATE   NOT NULL,
  rows_fact       BIGINT,
  rows_summary    BIGINT,
  gross_amount    NUMERIC(21,4),
  fee_priced_rows BIGINT,
  last_run_id     BIGINT,
  last_loaded_at  TIMESTAMP,
  load_count      INT DEFAULT 1,
  PRIMARY KEY (tenant_id, txn_date)
);

CREATE TABLE IF NOT EXISTS ingest_expectation (
  tenant_id         BIGINT PRIMARY KEY,
  expected_daily    BOOLEAN     DEFAULT TRUE,
  cutoff_local_time TIME        DEFAULT '09:00',
  timezone          VARCHAR(64) DEFAULT 'Asia/Bahrain',
  sla_minutes       INT         DEFAULT 45,
  min_rows_warn     BIGINT,
  variance_pct      INT         DEFAULT 40,
  enabled           BOOLEAN     DEFAULT FALSE
);
```

**Deliberately NOT doing:** adding `ingest_run_id` to `fact_transaction`. It is the largest, partitioned table in the system; the column add plus backfill is disproportionate. Fact-tier counts come from step write counts cross-checked against bounded date-range aggregates in `ingest_day_coverage`. Trade-off: we can attribute a *day* to a run, not an individual fact row.

---

## Phase 2 — Write path (1.5 days)

`IngestRunRecorder` in **acquira-common** (both `acquira-batch` and `acquira-core` need it).

```
openRun(tenantId, source, fileName, bytes, sha256, loadMode, triggeredBy) -> runId
recordStage(runId, stageName, seq, status, rowsIn, rowsOut, rowsSkipped, durationMs)
closeRun(runId, status, counts..., error)
upsertDayCoverage(tenantId, dates)
```

Rules:

- Every write in `REQUIRES_NEW` — a failed job must still record its own failure.
- **The recorder must never fail a job.** Catch and log everything; a broken observability layer must not break ingestion.
- Stage capture comes from an `IngestRunStepListener implements StepExecutionListener`, registered alongside the existing `mdcStepListener` on every step in both `transactionLoadJob` and `dbPullTransactionJob`. Per-stage timing for all 12 + 9 steps then arrives for free.
- `closeRun` fires from a `JobExecutionListener`.
- Call sites for `openRun`: `FileUploadService` (both upload paths), `ScheduledDbPullJob` / `IntegrationPullService`, `BackfillIngestionService`, `BulkMigrationService`. The last two are the ones Spring Batch never saw.
- `file_name` must be sanitised — the raw `getOriginalFilename()` finding from the ingest audit applies here too.

---

## Phase 3 — Reconciliation (1.5 days)

`IngestReconciliationService` computes the funnel and classifies each drop:

| Hop | Source of truth | A gap means |
|---|---|---|
| file → staged | `totalReqRows` (job context) vs `count(*) where ingest_run_id = ?` | Parser drops, encoding, bad rows |
| staged → facted | staged count vs `stagingToFactStep` write count | Unresolved merchants, rejects, filtered types |
| facted → summarised | date-range fact count vs `sum(txn_count) from sum_daily_full` | **Summary drift** — the known `BulkMigrationService` vs `TransactionJobConfig.populateSummary` mirror problem |

Plus two assertions that would each have caught a real defect:

- **Fee coverage:** `% of fact rows in the loaded days with a non-null, non-zero MSF`. Below threshold (default 95%) → `FEE_COVERAGE_DROP`. This is exactly the BH dead-rate-card failure, caught at load time instead of in UAT.
- **Destructive replace:** `fact_rows_deleted > rows_facted * 1.5` → `DESTRUCTIVE_REPLACE`.

Set `recon_status` on the run: `OK`, `GAP`, or `UNKNOWN` (older runs, missing counters).

---

## Phase 4 — Read API (1 day)

`IngestTrustController` in acquira-core. **Every endpoint `@PreAuthorize`-gated** — the improvement audit already counted ~25 ungated controllers; do not add another. Tenant admins see their own tenant; super-admins see all (mirror the tenant-filter pattern already in `BatchProgressController`).

```
GET  /api/ops/ingest/overview                     -> per-tenant freshness tiles
GET  /api/ops/ingest/runs?from&to&status&source   -> paged run list
GET  /api/ops/ingest/runs/{id}                    -> stage waterfall + funnel + discrepancies
GET  /api/ops/ingest/coverage?tenantId&from&to    -> calendar cells
GET  /api/ops/ingest/duration-trend?job&days      -> p50/p95 per stage + SLA line
POST /api/ops/ingest/runs/{id}/acknowledge        -> operator sign-off on a known-bad run
```

Caching: 60s on `/overview` only. This screen's whole value is being current — do not put it behind the 6h `ReportCache`.

**Expected-day computation needs a server-side working week.** `weekRules.js` is frontend-only today; add `WorkingWeekResolver` in acquira-common driven by `tenant.home_country_code` (UAE Sat+Sun, BH/OM/EG Fri+Sat) and have the frontend rules and this one agree. Without it, every Bahraini Friday shows as a missing-data alert.

---

## Phase 5 — The board (2.5 days)

Route `/ops/ingest-trust`, menu row seeded under `OPERATIONS` via the migration (`ON CONFLICT DO NOTHING`) **and** granted to the admin groups — menu-grant enforcement gates access, so an ungranted row is an invisible screen.

Four panels:

1. **Freshness strip** — one card per tenant: last good load, age, next expected, colour by SLA. Single card for tenant admins.
2. **Coverage calendar** — 90-day grid per tenant. Cell states: loaded / missing / stale / partial / reloaded-N×. Working-week aware. Clicking a cell opens the runs that touched that day — this is how "who wiped Tuesday" gets answered in ten seconds.
3. **Run detail: funnel + waterfall** — file/staged/facted/summarised bars showing the drop at each hop, beside the per-stage duration waterfall. A 1.7h fee pass renders as one bar 40× the others; no one has to read a log.
4. **Duration trend** — per stage, p50/p95 over 30 days with the SLA line.

Styling: Meridian steel tokens, `chartPalette` cat-1..5, mono numerals — the established Ledger conventions. Keep entrance animation minimal; verify with vitest rather than the browser pane (the pane gets no animation frames when hidden, so motion-heavy pages look broken there regardless of correctness).

---

## Phase 6 — Alerting (1.5 days)

Emit into the existing `alert_history` plumbing rather than building new delivery:

| Alert | Trigger |
|---|---|
| `NO_DATA` | past cutoff on an expected working day, nothing loaded |
| `RUN_FAILED` | terminal status FAILED |
| `SLA_BREACH` | duration > `sla_minutes` |
| `RECON_GAP` | any tier discrepancy over threshold |
| `DESTRUCTIVE_REPLACE` | `fact_rows_deleted` >> rows loaded |
| `FEE_COVERAGE_DROP` | fee-priced % below threshold |
| `VOLUME_ANOMALY` | day vs 8-week same-weekday baseline, beyond `variance_pct` |
| `DUPLICATE_FILE` | same `file_sha256` already loaded for this tenant |

Plus a digest at cutoff+30min: every tenant's status in one email to ops.

---

## Phase 7 — Verification (1.5 days)

- **Unit:** recorder writes; recon classification; working-week resolver against all four country codes.
- **Integration:** load a known 10k file, assert funnel = 10000/10000/10000/10000 and `recon_status = OK`.
- **Negative — these are the regression tests for the original defects:**
  - file with 50 unresolvable merchants → `STAGE_VS_FACT` gap = 50
  - two concurrent uploads, same tenant → two runs, both complete, neither wipes the other's staging
  - 200-row resend over a 400k-row day → `DESTRUCTIVE_REPLACE` raised (and blocked, once P0-3's guard is on)
  - tenant with no rate card → `FEE_COVERAGE_DROP` raised
  - progress never reports >99% before the final step
- **Path coverage:** assert a run row exists after each of upload, server-file, DB-pull, and backfill.

---

## Sequencing and effort

| Phase | Effort | Depends on |
|---|---|---|
| P0-1 progress fix | 0.5d | — |
| 1. Ledger schema | 2.0d | — |
| P0-2 staging run scope | 1.0d | 1 (needs `run_id`) |
| P0-3 replace visibility + guard | 1.0d | 1 |
| 2. Write path | 1.5d | 1 |
| 3. Reconciliation | 1.5d | 2, P0-2 |
| 4. Read API | 1.0d | 3 |
| 5. Board UI | 2.5d | 4 |
| 6. Alerting | 1.5d | 3 |
| 7. Verification | 1.5d | all |

**Total ~2.5 weeks, one developer.**

**MVP slice (~4 days, ~60% of the value):** P0-1 + Phase 1 + Phase 2 + the freshness strip alone. That gives "is every tenant's data current, and did anything fail" — which is the question that actually gets asked every morning. Reconciliation and the calendar can follow.

---

## Deployment notes

- Migration goes in `acquira-core/src/main/resources/db/migration/` (tracked in git since the 2026-08-22 gitignore negation). `acquira-batch`'s migration dir is still gitignored — do not put it there.
- Additive migration only. Do not edit `schema.sql`'s DROP section; with `SQL_INIT` always-on in the configmap, that path is a DB-wipe footgun.
- `ALTER TABLE stg_trnx_raw ADD COLUMN` is cheap (transient table) but must run with no job in flight.
- One-time backfill of `ingest_day_coverage` from `sum_daily_full` so the calendar has history on day one. Runs predating the ledger get `recon_status = UNKNOWN`.
- Seed `ingest_expectation` per tenant at provisioning time; default `enabled = false` so a new tenant doesn't alert before its first load.

---

## Open questions

1. Should the REPLACE guard block or warn by default? Blocking is safer; it will also stop a legitimate small resend at 2am until someone approves it.
2. Retention on `ingest_run_stage` — ~12 rows per run; at 6 tenants × a few runs/day this is trivial, but set a 12-month prune anyway.
3. Does the AMS/CMM file format carry a trailer row count? If so, `rows_file` should come from the trailer rather than the parsed count — that turns file→staged into a real external check rather than a self-consistency check.

---

# BUILD STATUS — 2026-08-28

All phases implemented. 242 backend tests and 69 frontend tests pass; both
modules compile and the frontend bundles.

| Phase | Status | Where |
|---|---|---|
| P0-1 stage-weighted progress | Built + tested | `BatchProgressController` |
| P0-2 staging run scope | Built | `TransactionJobConfig`, migration |
| P0-3 REPLACE visibility + guard | Built | `TransactionJobConfig` |
| 1. Ledger schema | Built | `V2026_08_28_01__ingest_trust.sql` |
| 2. Write path | Built | `IngestRunRecorder`, `IngestRunJobListener`, `IngestRunStepListener` |
| 3. Reconciliation | Built + tested | `IngestReconciliationService` |
| 4. Read API | Built | `IngestTrustController` |
| 5. Board UI | Built + tested | `pages/ops/IngestTrust.jsx` |
| 6. Alerting | Built | `IngestAlertScheduler` |
| 7. Verification | Built | 4 JUnit classes, 1 vitest file |

## Design decisions taken during the build

**The ledger is written by listeners, not call sites.** `IngestRunJobListener`
opens the run in `beforeJob` and closes it in `afterJob`, so every launch of
`transactionLoadJob` and `dbPullTransactionJob` is recorded no matter who
launched it, and the failure path cannot be forgotten. Call sites only declare
provenance via an `ingestSource` job parameter. `IngestRunStepListener` rides
alongside the existing `mdcStepListener` on all 12 + 9 steps.

**Concurrent same-tenant ingestion is PREVENTED, not supported.** Staging rows
now carry `ingest_run_id`, but every downstream read in `stagingToFactTasklet`
is still tenant-scoped. Run-scoping those ~20 queries is a much larger change,
and a half-scoped pipeline silently merges two uploads into one fact load — so
`FileUploadService.assertNoRunningIngest` refuses a second transaction upload
while one is RUNNING for that tenant (stale RUNNING rows are ignored after 6h so
a killed pod cannot lock a tenant out).

**`fact_transaction` was left alone.** No `ingest_run_id` column: it is the
largest partitioned table in the system. Days are attributed to runs via
`ingest_day_coverage` instead.

**`WorkingWeekResolver` was added to acquira-common** because the working week
existed only in the frontend, and every expected-day calculation on the server
needs it. Its tests pin it to `weekRules.js`; if the two diverge, every Gulf
tenant's weekend reads as missing data.

## What is NOT verified

- **The migration has never been executed.** There is no local Postgres and no
  `psql` on this machine, so `V2026_08_28_01__ingest_trust.sql` is unrun. Apply
  it to a scratch database before UAT.
- **No end-to-end ingestion was run.** The pipeline changes are compile- and
  unit-verified only. The first real upload after deployment is the actual test
  of the ledger write path.
- **The board has not been rendered in a browser.** Its logic is vitest-covered;
  its appearance is not.
- `IntegrationPullService` still clears staging with its own tenant-wide delete
  before launching `dbPullTransactionJob`. That path is single-threaded per
  tenant today, and the upload guard now also blocks an overlapping manual
  upload, but the delete itself was left untouched.

## Deploy order

1. Apply `V2026_08_28_01__ingest_trust.sql` **with no ingest job running** — it
   adds a column to `stg_trnx_raw`.
2. Deploy `acquira-common`, `acquira-batch`, `acquira-core`, frontend.
3. Confirm the `Ingest Trust` menu row exists and is granted (the migration
   copies the grants from `/ops/batch-logs`).
4. Enable monitoring per tenant: `UPDATE ingest_expectation SET enabled = TRUE`
   — deliberately off by default so a tenant does not alert before its first
   load. Set `cutoff_local_time`, `timezone` and `sla_minutes` per tenant first.
5. Watch the first upload end-to-end: a run row appears, 12 stage rows follow,
   `recon_status` lands on OK.

Rollback: `acquira.ingest.alerts.enabled=false` silences alerting;
`acquira.replace.guard.enabled=false` restores the old destructive REPLACE. The
ledger tables are additive and can be left in place.
