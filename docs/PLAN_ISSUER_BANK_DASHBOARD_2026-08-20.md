# Local Debit Bank Dashboard (BIN-wise Txn Count & Volume)

Date: 2026-08-20. **Status: BUILT and verified locally.** Live at
`/business/local-debit-bank-dashboard`. Not yet deployed — see §10 for the
deployment runbook and §11 for the migration DDL (the `db/migration/` folder is
gitignored, so the SQL below is the tracked copy of what must be applied).

## 1. The idea (updated)

Dashboard answering: **"per LOCAL DEBIT issuing bank, per day: transaction count and
volume — with merchant drill-down."**

User-driven changes vs v1:
1. **Tenant-scoped BIN injection table**: the user uploads rows of
   `tenant_id, bin, bank`. NOT the global `ref_bin` — each tenant maintains its own
   local-bank BIN list.
2. **merchant_id added** to the summary grain → per-bank merchant drill-down works.
3. **Scope = LOCAL (domestic) DEBIT cards ONLY.** The summary stores nothing else.
4. **Matched vs Others**: a local-debit transaction whose BIN is in the tenant's
   injected list shows under that bank; an unmatched local-debit BIN falls into an
   **"Others"** bucket — never dropped.
5. **Reconciliation guarantee**: Σ(all banks) + Others on this page must equal the
   DOMESTIC ∩ DEBIT figures on the Destination / Card Type dashboards for the same
   window and filters.

## 2. New injection table (replaces the ref_bin dependency)

```sql
CREATE TABLE ref_tenant_bin_bank (
    tenant_id   BIGINT       NOT NULL,
    bin         VARCHAR(6)   NOT NULL,     -- 6 digits only, see rule below
    bank_name   VARCHAR(128) NOT NULL,
    source_file VARCHAR(256),
    loaded_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bin)
);
```

- **Stored as 6 digits.** Feeds deliver only first-6-clear PANs, so matching is
  always on `LEFT(card_number,6)`; an 8-digit BIN must be reduced to its 6-digit
  prefix before seeding. Where two 8-digit BINs share a prefix under different
  banks, one is chosen deliberately — the data cannot tell them apart.
- **Seeded through the database, NOT through the app** (decision revised
  2026-08-20 after review). There is no upload/edit/delete endpoint and the UI is
  read-only. Rationale: names resolve at QUERY time, so a wrong or partial list
  would re-attribute every bank across all history the instant it landed, with no
  rebuild to spread it and none to undo it. Maintain via
  `docs/deploy/02_seed_uae_bin_bank.sql`. A guarded self-service flow
  (validation, preview, audit, rollback) may be built later.
- **This table is the ONLY bank source.** `ref_bin` / `ref_bin_range` are NOT
  consulted anywhere on this dashboard.
- API surface: `GET .../bins` only — the read-only list the page displays,
  tenant taken from the session. No write endpoint exists.
- Bank name is resolved **at query time** by joining this table — so re-seeding a
  corrected list instantly re-labels ALL history, zero rebuilds. This is the
  feature's biggest strength and precisely why writes are DBA-only.

## 3. Summary table (scoped, merchant-grain)

```sql
CREATE TABLE sum_daily_local_debit_bin (
    tenant_id     BIGINT      NOT NULL,
    business_date DATE        NOT NULL,
    merchant_id   BIGINT      NOT NULL,
    bin6          VARCHAR(6)  NOT NULL,   -- '??????' bucket for malformed PANs
    total_txns    BIGINT      NOT NULL,
    total_volume  NUMERIC(18,4) NOT NULL, -- store_base_currency_amount, SIGNED (refunds net out)
    total_msf     NUMERIC(18,4),
    PRIMARY KEY (tenant_id, business_date, merchant_id, bin6)
);
-- partitioning + (tenant_id, business_date) index matching the other daily summaries
```

**Population predicate (the reconciliation contract):**

```sql
FROM fact_transaction f
WHERE f.tenant_id = ?
  AND f.merchant_id IS NOT NULL                          -- same as sum_daily_full
  AND UPPER(COALESCE(NULLIF(TRIM(f.card_type),''),'')) = 'DEBIT'
  AND <destination predicate — see §4>
GROUP BY tenant_id, DATE(payment_date), merchant_id,
         CASE WHEN f.card_number ~ '^[0-9]{6}' THEN LEFT(f.card_number,6) ELSE '??????' END
```

- No channel/scheme/destination/card_type dims needed — the table IS the
  domestic-debit slice, which keeps it small despite the merchant grain.
- Volume/MSF signed, settlement basis — identical to `sum_daily_full`, so the
  parity check in §5 is exact, not approximate.
- Cardinality: (active local-debit merchants/day) × (local debit BINs they saw)
  ≈ low tens of thousands of rows/tenant-day worst case; a monthly ranged scan on
  `(tenant_id, business_date)` stays well under a second; dashboard endpoints
  aggregate over it with top-N folding → fast.

## 4. ⚠ Decision needed: which "DOMESTIC" definition

The two existing dashboards do NOT agree on NULL destinations:
- `sum_daily_merchant_destination` (Destination Dashboard fast path) maps
  **NULL destination → DOMESTIC** at build time.
- `sum_daily_full` (Card Type Dashboard) keeps destination as-is; a literal
  `destination='DOMESTIC'` filter there **excludes** NULL/UNMAPPED rows.

This page can only reconcile exactly with ONE of them for rows where
destination is NULL. **Default chosen: `f.destination = 'DOMESTIC'` (strict)** —
matches sum_daily_full/Card Type filtering, and NULL destination means the token
mapping failed, which shouldn't silently count as "local". Deviation vs the
Destination Dashboard is only the NULL-destination debit slice; surface it via the
data-quality chip (§6). Flag if the COALESCE convention is preferred instead.

## 5. Reconciliation guarantee ("all dashboards' local domestic debit should match")

- Same source (`fact_transaction`), same merchant filter, same signed settlement
  volume, same card_type normalization, same destination predicate as the chosen
  convention → Σ this table == `sum_daily_full` rows where
  `destination='DOMESTIC' AND UPPER(card_type)='DEBIT'`, per tenant-day.
- Add a **parity assertion to the verification runbook**: one SQL comparing the two
  sums per month; run after backfill and after any future writer change
  (summary-rebuild-drift rule).
- "Others" is computed as (summary rows with bin6 NOT IN tenant's injected list) —
  it is part of the total, so matched + Others always equals the domestic-debit
  total by construction.

## 6. Dashboard screen

`/business/local-debit-bank-dashboard` (BUSINESS, display_order 20, icon `Landmark`),
`LocalDebitBankDashboardController` + repository in acquira-common, replicating the
Card Type Dashboard conventions: own `/bounds` over the new table, two windowed KPI
scans, top-25 + "Other" folding, `@menuAccess` gate, CAST not `::`.

Panels:
- KPI row: local-debit count, volume, avg ticket (current vs prior window).
- **Bank ranking** bar (banks + "Others" always shown as its own bar).
- Trend: daily/monthly lines for top-5 banks + Others.
- **Top merchants per bank**: pick a bank → top-25 merchants by volume/count
  (this is what merchant_id in the grain buys).
- **Coverage chip**: "Matched to a bank: NN.N% of local debit volume" + count of
  distinct unmatched BINs (click → top unmatched BINs by volume, to hand to the
  administrator for the next seed).
- **BIN list panel**: read-only view of the configured BIN → bank rows, so users
  can see what the page resolves against. No edit controls (see §2).
- Filters: date presets (anchored on own bounds), merchant search. No
  destination/card-type/mcc/sid controls — scope is fixed by definition.
- Data-quality footnote: volume of DEBIT rows with NULL/UNMAPPED destination
  excluded by the strict predicate (per §4), so any gap vs the Destination
  Dashboard is explained on-screen.

Frontend: `frontend/src/pages/business/LocalDebitBankDashboard.jsx`, `ldb-` class
prefix, Meridian tokens, chartPalette cat-1..5 by bank rank, Others = grey,
mono numerals.

## 7. Work items

### A. Database (hand-applied; migrations are gitignored)
1. `V2026_08_20_0X__ref_tenant_bin_bank.sql` — injection table (§2).
2. `V2026_08_20_0X__sum_daily_local_debit_bin.sql` — summary (§3) + partitions.
3. Menu migration for `/business/local-debit-bank-dashboard`.
4. All registered in schema-locations.

### B. Batch (ALL writers mirrored + deleteDay — known drift trap)
Every path that produces summaries must maintain `sum_daily_local_debit_bin`
with the IDENTICAL INSERT (§3) — explicitly confirmed with the user 2026-08-20:
- `TransactionJobConfig.populateSummary` — covers BOTH transaction-file ingest
  paths (UI file upload AND server/S3 folder file processing; they share this
  job config).
- `BulkMigrationService.rebuildSummaries` — the summary REBUILD must rebuild
  this table too (delete-first for the window, then re-insert from fact).
- `BackfillIngestionService` — its summary path gets the same INSERT.
- `deleteDay`: add `sum_daily_local_debit_bin` to the delete list.

### C. Backend
- Controller (dashboard read endpoints + read-only `GET /bins`) + repository.

### D. Frontend
- Dashboard page + read-only BIN list panel; menu icon.

### E. Runbook
See `docs/deploy/README_LOCAL_DEBIT_BANK.md` — schema script, seed script,
rebuild, then the verification script (which includes the parity assertion).

## 8. Decisions taken

1. **§4 destination predicate**: strict `='DOMESTIC'` — matches the Card Type
   Dashboard; NULL/UNMAPPED debit is excluded and explained on-screen.
2. **BIN list is DBA-seeded, app read-only** (revised after review — see §2).
3. Metrics: count + volume (+MSF included cheaply); full fee stack deferred.
4. Rows with NULL merchant_id excluded (mirrors sum_daily_full) — required for the
   parity guarantee.

## 9. What was actually built (2026-08-20)

Files (all committed except the two migrations — `db/migration/` is gitignored,
so their DDL is reproduced in §11):

| Layer | File |
|---|---|
| Migration | `V2026_08_20_01__local_debit_bank_dashboard.sql` (gitignored — §11) |
| Migration | `V2026_08_20_02__local_debit_bank_menu.sql` (gitignored — §11) |
| Batch | `TransactionJobConfig` (clean-slate list + phase-1 INSERT) |
| Batch | `BulkMigrationService` (rebuild block 11b², analyze list, `deleteDay`) |
| Batch | `BackfillIngestionService` (clean-slate list + step 9c²) |
| Batch | `PartitionMaintenanceService` (yearly list → 12 tables) |
| Test | `PartitionMaintenanceServiceTest` (EXISTS counts 24→25, 47→49) |
| Backend | `LocalDebitBankDashboardController` (acquira-core) |
| Backend | `LocalDebitBankDashboardRepository` (acquira-common) |
| Frontend | `pages/business/LocalDebitBankDashboard.jsx`, route in `App.jsx` |
| Frontend | `BusinessFilters.jsx` — new `merchantOnly` prop |
| Config | `application.properties` + `application-prod.properties` (gitignored) schema-locations |

Plus the deployment kit in `docs/deploy/` (schema, UAE seed, verification
scripts + README) — the tracked, runnable copy of everything the gitignored
migrations contain.

Local verification passed: all endpoints 200; KPIs, share ribbon, bank ranking,
monthly trend, per-bank merchant drill-down, unmatched-BIN worklist and the
read-only BIN list panel ("158 BINs · 40 banks") all render; parity with
`sum_daily_full`'s DOMESTIC × DEBIT slice exact (6,375,740.31 / 6,810 txns).

Local caveats (dev-box only, not defects): `fact_transaction` is empty locally so
the summary was synthesized from `sum_daily_full`; `dim_merchant` was empty and
needed synthetic rows; the backend CORS allowlist only permits `localhost:5173`
(pass `CORS_ORIGINS` for another Vite port); `spring-boot:run` must be launched
from inside `acquira-core` (the parent POM has no mainClass).

## 10. Deployment runbook

Full version with copy-paste commands: **`docs/deploy/README_LOCAL_DEBIT_BANK.md`**.

1. Deploy the code; add both migration paths to `application-prod.properties`
   schema-locations (gitignored — edit on the target).
2. `psql -f docs/deploy/01_local_debit_bank_schema.sql` (idempotent).
3. Restart the backend (registers the controller + menu grant).
4. `psql -v tenant_id=<id> -f docs/deploy/02_seed_uae_bin_bank.sql` — the BIN list
   is DBA-seeded; there is no upload path (§2).
5. Run a bulk-migration **summary rebuild** for the historical months —
   `fact_transaction.card_number` carries the first 6 digits, so all history fills.
   Check the batch log for `sum_daily_local_debit_bin rebuild skipped`, which
   means step 2 was missed (the rebuild warns and continues rather than failing).
6. `psql -v tenant_id=<id> -f docs/deploy/03_verify_local_debit_bank.sql` —
   `diff_volume` and `diff_txns` must both be 0.

## 11. Migration DDL (tracked copy — `db/migration/` is gitignored)

### V2026_08_20_01__local_debit_bank_dashboard.sql

```sql
CREATE TABLE IF NOT EXISTS ref_tenant_bin_bank (
    tenant_id   INT          NOT NULL,
    bin         VARCHAR(6)   NOT NULL,
    bank_name   VARCHAR(128) NOT NULL,
    source_file VARCHAR(256),
    loaded_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bin)
);
ALTER TABLE ref_tenant_bin_bank ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON ref_tenant_bin_bank;
CREATE POLICY tenant_isolation_policy ON ref_tenant_bin_bank
    USING (tenant_id = get_current_tenant());

CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin (
    summary_id    BIGSERIAL,
    tenant_id     INT NOT NULL,
    business_date DATE NOT NULL,
    merchant_id   BIGINT NOT NULL,
    bin6          VARCHAR(6) NOT NULL,
    total_txns    BIGINT DEFAULT 0,
    total_volume  DECIMAL(19, 2) DEFAULT 0,
    total_msf     DECIMAL(19, 2) DEFAULT 0,
    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, bin6)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2024
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2025
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2026
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2027
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_default
    PARTITION OF sum_daily_local_debit_bin DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_daily_ldb_tenant_date
    ON sum_daily_local_debit_bin (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_daily_ldb_tenant_date_bin
    ON sum_daily_local_debit_bin (tenant_id, business_date, bin6);

ALTER TABLE sum_daily_local_debit_bin ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_local_debit_bin;
CREATE POLICY tenant_isolation_policy ON sum_daily_local_debit_bin
    USING (tenant_id = get_current_tenant());
```

### V2026_08_20_02__local_debit_bank_menu.sql

```sql
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Local Debit Banks', '/business/local-debit-bank-dashboard', 'Landmark', 'BUSINESS', 20
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/local-debit-bank-dashboard');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/local-debit-bank-dashboard'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/local-debit-bank-dashboard'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
```

### schema-locations additions (both properties files)

```
  classpath:db/migration/V2026_08_20_01__local_debit_bank_dashboard.sql,\
  classpath:db/migration/V2026_08_20_02__local_debit_bank_menu.sql
```
