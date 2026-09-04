# PLAN — Terminal / Store / Merchant Rentals (2026-08-29, rev 3 — decisions locked)

> **BUILD STATUS (2026-08-29): Phase 1 BUILT.**
> Migrations `V2026_08_29_01__rentals.sql` + `_02__rentals_menu.sql` (registered in
> application.properties); `RentalJobConfig` (rentalLoadJob + dbPullRentalJob);
> `FileUploadService` RENTAL detection (checked BEFORE the transaction/merchant
> markers — the rental file also carries "Payment Date" and MID/SID) + routing in
> upload / multi-file / server-folder paths; `IntegrationPullService` RENTAL report
> type + `insertRentalStaging`; `RentalController` gated at
> `@menuAccess.canAccess('/business/rentals')`; `RentalOverview.jsx` at
> /business/rentals; Integration Hub + Server File Processor UIs know the type.
> Known parity limitation: rental uploads are not gated by assertNoRunningIngest
> (same as merchant uploads) — two concurrent rental loads for one tenant would
> race the tenant-scoped staging wipe.
> Phase 2 (summaries) NOT built. Real feed headers still pending — parser reads
> the dummy headers (Entity Name, MID, SID, TID, Rental Amount, Payment Date).
>
> **LIVE E2E PASSED (2026-08-29, local dev)**: AMS file (AFSB, tenant 8, real dims)
> + CMM file (test tenant 9 'TCM', created locally) through /api/upload →
> level derivation, FK resolution, REJECTED (TID w/o SID), UNMATCHED (unknown SID),
> re-upload → 5 DUPLICATE + 0 double-inserts, dim latest-rental refresh, overview/
> list/exceptions/export APIs, and the screen (3 tabs AMS / Store-only CMM,
> exceptions panel) all verified. One UI bug found+fixed: `createFmt` returns an
> object — use `fmt.currency()` / `fmt.money()`, never `fmt()`.
> E2E caveat: an early mis-routed run (stale ~/.m2 jar before `mvn install`)
> auto-created dim_store 'UNKNOWN-S999' on tenant 8 — removed. Local dev now has
> synthetic tenant 9 (TCM/CMM) with 1 merchant + 2 stores + 2 fact_rental rows.

## Goal
Ingest and display **rental** charges billed at merchant (MID), store (SID) and terminal
(TID) level. Rentals arrive in a **dedicated rental file** (not the transaction or
merchant-master feed), through the **same three channels as transactions**: file upload,
Server File Processor, and the scheduled DB pull.

## Decisions (user-confirmed 2026-08-29)

1. **No Level column — level is DERIVED from ID presence** (AMS):
   - MID + SID + TID present → **TERMINAL** level charge
   - MID + SID present (no TID) → **STORE** level charge
   - MID only → **MERCHANT** level charge
   - CMM files carry **SID only** → always STORE level.
   - We assign the level type code ourselves (`MERCHANT|STORE|TERMINAL`) and **validate**
     each row's ID combination before applying (pre-upload/pre-apply check): TID without
     SID, or SID without MID (AMS) → row REJECTED with reason, surfaced in the job result.
2. **No unit division** — amounts are in local (tenant base) currency, major units, both
   systems.
3. **Each row carries a payment date** (any date, like the transaction feed) → rentals are
   **dated charge records**, not a point-in-time snapshot. Re-uploads are new dated rows;
   duplicates guarded by row_hash.
4. Channels: file upload + Server File Processor + scheduled integration pull — all three.
5. Screen lives under the **Business** menu.
6. Phase 2 recognition: rental **spread across the month** of the payment date.

---

## Data model

### Migration `V2026_08_29_01__rentals.sql` (acquira-core, tracked)
```sql
CREATE TABLE IF NOT EXISTS stg_rental_raw (
    raw_id BIGSERIAL PRIMARY KEY,
    tenant_id INT,
    file_id BIGINT,
    load_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    row_hash VARCHAR(64),
    status VARCHAR(20) DEFAULT 'PENDING',   -- PENDING|PROCESSED|REJECTED|UNMATCHED
    error_message TEXT,
    entity_name VARCHAR(100),
    mid VARCHAR(50),
    sid VARCHAR(50),
    tid VARCHAR(50),
    rental_amount DECIMAL(19,4),
    payment_date DATE
);
ALTER TABLE stg_rental_raw ENABLE ROW LEVEL SECURITY;  -- + tenant_isolation_policy

CREATE TABLE IF NOT EXISTS fact_rental (
    rental_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    level VARCHAR(20) NOT NULL,              -- MERCHANT|STORE|TERMINAL (derived)
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    store_id    BIGINT REFERENCES dim_store(store_id),
    terminal_id BIGINT REFERENCES dim_terminal(terminal_id),
    mid VARCHAR(50), sid VARCHAR(50), tid VARCHAR(50),  -- as received (audit/unmatched)
    rental_amount DECIMAL(19,4) NOT NULL,
    payment_date DATE NOT NULL,
    file_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, level, COALESCE-key on ids, payment_date, rental_amount) -- via row_hash instead; see note
);
CREATE INDEX ix_fact_rental_tenant_date ON fact_rental(tenant_id, payment_date);
ALTER TABLE fact_rental ENABLE ROW LEVEL SECURITY;
```
Note: dedupe via `row_hash` uniqueness at staging (same convention as transactions) rather
than a fragile composite unique on nullable FKs.

Optionally ALSO keep the "current rental" on the dims for other screens (latest
payment per entity): `dim_merchant/store/terminal.rental_amount DECIMAL(19,4)` updated by
the apply step. Cheap, and preserves the original "column on the dims" ask.

Mirror everything into `schema.sql` (all consolidated blocks) + consolidated migrations.

---

## File formats (dummy values now, real later — samples in docs/samples/)

**AMS** (`AMS_RENTAL_SAMPLE.csv`) — level derived per row:
```
Entity Name,MID,SID,TID,Rental Amount,Payment Date
TBH,TBH-M001,,,25.000,2026-08-05          <- MERCHANT level
TBH,TBH-M001,TBH-S001,,10.500,2026-08-05  <- STORE level
TBH,TBH-M001,TBH-S001,TBH-T0001,5.250,2026-08-05  <- TERMINAL level
```

**CMM** (`CMM_RENTAL_SAMPLE.csv`) — always store level:
```
Entity Name,SID,Rental Amount,Payment Date
EGY,EG-S0001,150.50,2026-08-05
EGY,EG-S0002,225.00,2026-08-05
```

Entity Name = tenant short code per row (transaction-feed convention). Dates yyyy-MM-dd in
the dummies; parser will accept the same date formats the transaction parser accepts.

---

## Phase 1 — Build

### 1. Detection (`FileUploadService`)
- Row-0 scan (`FileUploadService.java:264-345`): classify **RENTAL** when headers contain
  "rental" (e.g. "Rental Amount") — checked **before** the generic `mid`→MERCHANT fallback
  at `:345` (rental files also contain MID/SID headers).
- Route RENTAL → `rentalJob` in the upload path (`:491-511`), the Server File Processor
  path (`:969-1051`), and the directory-scan allowlist (`:639`).

### 2. `RentalJobConfig` (acquira-batch)
- **ingestRentalStep**: header mapping (`getCellValue` style), `normalizeSid`-family
  normalization on MID/SID/TID so they join the dims, row_hash for dedupe, stage into
  `stg_rental_raw` tagged with resolved tenant (Entity-Name rule).
- **validateAndApplyRentalStep** (set-based SQL):
  1. Derive level per row from ID presence (decision 1); invalid combos → `REJECTED`.
  2. Resolve dim FKs: mid→dim_merchant, (mid,sid)→dim_store, (…,tid)→dim_terminal.
     No dim auto-create from rental files; unresolved → `UNMATCHED` (kept, re-appliable
     after the next merchant-master load).
  3. Insert resolved rows into `fact_rental` (skip row_hash duplicates).
  4. Refresh dim `rental_amount` = latest payment per entity (if we keep the dim columns).
- Register in the `ingest_run` ledger with real step progress.

### 3. Scheduled pull (`IntegrationPullService`)
Add a rental query slot per integration config (same pattern as the merchant pull) →
insert into `stg_rental_raw` → reuse `validateAndApplyRentalStep` via a `dbPullRentalJob`.

### 4. API — `RentalController`, `/api/business/rentals` (gated + menu code; Business)
- `GET /overview?from&to` — totals & counts per level for the date range, `levels` from
  `tenant.input_format` (CMM ⇒ `["STORE"]`, AMS ⇒ all three), last file/pull date.
- `GET /list?level&from&to&search&page` — charge records joined up the hierarchy
  (terminal→store→merchant names), amount, payment date.
- `GET /exceptions` — REJECTED + UNMATCHED staging rows with reasons.
- CSV export mirroring list.

### 5. Screen — `frontend/src/pages/business/RentalOverview.jsx` at `/business/rentals`
Register like Local Debit Bank Dashboard (App.jsx route, menu seed + grant under Business,
Layout entry). Meridian steel theme.
- Date-range filter (payment date, default current month).
- Tiles: total rental in range, per-level subtotals, billed-entity counts, exception count.
- Level tabs from API `levels` (CMM: Store only; AMS: Merchant/Store/Terminal).
- Table: MID/SID/TID, names, parent links, amount (mono numerals, tenant ccy),
  payment date, search + CSV; Exceptions panel when count > 0.

### 6. Verification
- Unit: detection precedence test; level-derivation matrix (all ID combos incl. invalid);
  dedupe on re-upload; unmatched→re-apply flow.
- Live: AMS dummy (3 levels) + CMM dummy into two local tenants → fact_rental rows with
  correct derived levels, screen variants, tenant isolation, ingest-trust row.

---

## Phase 2 — Summaries (later)

Recognition = **spread across the month of payment_date** (decision 6):
daily_rental(tenant, day, merchant/store/terminal) = rental_amount / days_in_month for
each day of that month.

1. `sum_daily_rental` (tenant_id, summary_date, level, merchant_id, store_id, terminal_id,
   rental_amount_day) — rebuilt per (tenant, month) from `fact_rental` whenever a rental
   ingest touches that month. Partitioned by year like the other sum_daily tables.
2. Finance Summary fee stack / `sum_daily_finance_rollup`: add **Rental income** from
   `sum_daily_rental` (tenant-day grain fits the rollup's fast path).
3. Executive revenue views: rental as a component; flows through `formatters` → USD toggle
   works; add `ReportCache` eviction on rental ingest.
4. **Rebuild rule**: fact-transaction-based rebuilds (`BulkMigrationService`, backfill)
   must not touch `fact_rental`/`sum_daily_rental`; they rebuild only from `fact_rental`.

## Remaining inputs
- Real header names + real sample rows (both systems) — dummies stand in until then.
- Integration-pull source table/query for rentals per tenant config.
