# Plan — BIN-Based Card Typing (tenant opt-in, local-only) + Destination Dashboard screen

**Date:** 2026-08-15
**Scope:** two features. No code has been changed yet — this is the implementation plan.

---

# Feature 1 — BIN-based card typing

## Requirements (as agreed)

1. **Tenant-level opt-in.** A tenant chooses BIN typing; tenants that don't stay exactly as today.
2. **Local cards only.** BIN-derived typing is applied **only when the card's issuer country (from the BIN reference data) equals the tenant's home country**. International cards are **never touched** — they keep the feed's card type/product unchanged.
3. "Local" is defined by **`tenant.home_country_code`** (BH for Bahrain tenant, EG for Egypt, AE for UAE…).

## What already exists (verified in code)

| Piece | Where | State |
|---|---|---|
| Opt-in flag | `tenant.card_type_source` (`'FILE'`\|`'BIN'`, CHECK-constrained, default `'FILE'`) — `V2026_08_08_06`, `Tenant.java:58` | exists, exposed in Tenant Management UI, **read by nothing in ingestion** |
| Home country | `tenant.home_country_code` (`Tenant.java:39`, backfilled `V2026_08_08_04`) | exists, already used by fee engine SQL |
| Reference data | `ref_bin_range` (~800K rows: Visa BIN list + promoted Mastercard T068; 19-char zero-padded `range_low/high`, `issuer_country`, `card_type`, `product_code`, `funding_source`) and `ref_bin` (operator-uploaded 6/8-digit exact mapping) | loaded and maintained via BIN Management |
| Lookup shape | zero-pad prefix to 19 → `range_low <= p AND range_high >= p` (proven in `BinManagementController.java:105-139`) | pattern established |
| PAN in feed | first-**6** clear + masked + last-4 (`TransactionJobConfig.java:1025-1033`) → only a 6-digit BIN is extractable | constraint |
| Insertion point | `transactionTenantProcessor` (`TransactionJobConfig.java:691-799`) — per-tenant `@StepScope`, already does card-type coarsening via the `REF_CACHE` in-memory pattern (`:523-590`) | the natural home |
| `fact_transaction.issuer_country` | column exists (`V2026_08_10_01:371`), **never written** | free win |

## Design

### D1. Configuration — no new schema
Reuse `tenant.card_type_source='BIN'` as the switch. No new settings, no migration. Add helper text in Tenant Management: *"BIN typing applies only to domestically-issued cards (issuer country = tenant home country); international cards always keep the file's card type."*

### D2. Reference cache (batch module)
Extend the existing `RefTableCache` double-checked-locking pattern (`TransactionJobConfig.java:523-590`) with a BIN cache:

- **Per-scheme sorted arrays** of `(rangeLow, rangeHigh, cardType, productCode, issuerCountry)` from `ref_bin_range`, binary-searched by the zero-padded 19-char prefix (lexicographic == numeric for fixed-width). ~800K entries ≈ tens of MB — acceptable; intern the small string values (card types, country codes).
- A small **exact-match map from `ref_bin`** (6-digit keys only — 8-digit rows are unusable with a 6-clear-digit feed and are skipped with a startup log line). `ref_bin` acts as the **operator override**: checked first, then `ref_bin_range`.
- **Load-time invariants** (mirror `BinManagementController.java:100-101`): skip and count rows where `LENGTH(range_low) <> 19`; probe table existence via metadata first (same defensive style as the existing cache) so a missing ref table degrades to FILE behaviour instead of failing the job.
- **Staleness**: the current `REF_CACHE` is loaded once per JVM; BIN uploads happen in the core JVM, so re-key the BIN cache **per job execution** (store the loading job's start timestamp; reload when a new job starts). Cheap: one reload per ingestion run.

### D3. Per-row enrichment (processor)
In `transactionTenantProcessor`, loaded once per step (same spot as `loadAmountContract`, `:710`): fetch `card_type_source` + `home_country_code` for the tenant. Then per row, **only when source == BIN**:

1. Extract the clear prefix from `card_number`: take leading digits until the first non-digit; require **exactly ≥6 clear digits**, else skip (row keeps FILE values). Guard against the sample-fixture case (`4111********1111` = only 4 clear) — real AMS files carry 6; rows that don't are simply untouched and counted.
2. Look up: `ref_bin` 6-digit exact → else `ref_bin_range` containment (scheme-agnostic search across all schemes; the ranges are disjoint per scheme file).
3. **No match** → untouched (this correctly leaves Meeza/Benefit and any unlisted local schemes alone).
4. Match found → **local check**: `issuerCountry == tenant.home_country_code`?
   - **Yes (local)**: overwrite `cardType` with the BIN's normalized `CREDIT/DEBIT/PREPAID`. *(See D4 for why `card_product_code` is NOT overwritten in v1.)*
   - **No (international)**: leave card type and product exactly as the feed said. **Never modified.**
5. **In both cases** (match found, local or not): populate `issuer_country` on the row — the column exists and is null today; writing it is pure metadata gain (enables future per-country analytics) and does not alter typing, fees, or destination. Plumb it through the `stg_trnx_raw` INSERT (`:804-829`) and the staging→fact copy (`:1004-1074`) — `stg_trnx_raw` needs an `issuer_country` column (one small migration, see D6).
6. **Counters + provenance log**: per-file counts of `binTyped`, `binIntlSkipped`, `binNoMatch`, `binShortPrefix`, logged at step end — mirrors the fee-provenance philosophy without new fact columns.

### D4. What is deliberately NOT changed (v1 guardrails)

- **`card_product_code` stays from the feed.** The interchange tier resolution (`:1315-1325`) matches rate-card product codes that were calibrated against the feed's AMS codes (VIPM/MCPM…). `ref_bin_range.product_code` uses scheme-file codes (different vocabulary) — overwriting would silently re-price interchange against codes the rate cards were never verified for. Product-code-from-BIN is a **phase 2** with its own rate-card mapping verification against the golden grid.
- **`destination` is untouched.** Domestic/international for **fee pricing** continues to come from the feed token via `destination_token_map` (`:1061-1067`). The BIN's issuer country is used only as the gate for typing (and stored as metadata). This guarantees zero fee-engine and zero MSF-reconciliation impact from the destination axis.
- **MCCP carve-out preserved automatically**: the "prepaid priced as credit" rule (`:1405-1409`) keys on `ref_card_scheme` (scheme code), not on `ft.card_type`, so a BIN-corrected card_type doesn't disturb it.

### D5. Downstream impact (accepted, by design)
A corrected `card_type` on local rows flows into the 8 summary tables keyed or CASE'd on card_type (`sum_daily_merchant` debit/credit split, `sum_daily_scheme` grain fallback, `sum_daily_finance` dom_debit/credit blocks, `sum_daily_insight`/`_full`/`_explorer`/`sum_monthly_insight` grain, `sum_daily_merchant_attribute` CARD_TYPE rows). **No code change needed there** — `populateSummary` and the `BulkMigrationService` rebuild both read the fact rows after enrichment, and no columns are added to any summary, so the rebuild-drift trap does not apply.

### D6. Migrations
One tiny migration: `ALTER TABLE stg_trnx_raw ADD COLUMN IF NOT EXISTS issuer_country VARCHAR(10)` (fact already has it). Register in `application.properties` schema-locations (and the prod list — noting the known dev/prod list drift).

### D7. Behaviour matrix

| Tenant source | Card | Result |
|---|---|---|
| FILE (default) | any | exactly as today, zero change |
| BIN | local (BIN issuer = home country) | card_type from BIN; product from feed; issuer_country stored |
| BIN | international | **untouched** typing; issuer_country stored |
| BIN | no BIN match / <6 clear digits | untouched; counted |
| BIN, `ref_bin_range` empty | any | untouched (degrades to FILE); warning logged |

### D8. Testing
- **Unit**: prefix extraction (6 clear, 4 clear, non-digit, empty); binary search hits low/high boundary, between-ranges miss; ref_bin override precedence.
- **E2E (BH tenant, source=BIN)**: ingest a small file containing (a) a Visa BIN whose `ref_bin_range.issuer_country='BH'` — expect card_type flipped to the range's value; (b) a foreign-issued Visa BIN — expect feed values byte-identical; (c) a Benefit PAN with no range match — untouched. Verify `issuer_country` populated on all matched rows, fee columns unchanged for (b)/(c), `sum_daily_merchant` debit/credit split reflects (a).
- **Regression**: re-ingest the existing golden-grid file on a FILE tenant — facts and `sum_daily_bank` must be byte-identical to the current run.
- **Perf**: cache load timing log; ingest the 10M-row month — O(log n) per row on 8 workers is negligible; confirm no heap pressure.

### D9. Rollout
Flag stays `FILE` everywhere; flip one pilot tenant (BH) in Tenant Management after the E2E passes. Historic data is **not** retro-typed by flipping the flag (ingestion-time only); if retro-typing is ever wanted, it's a separate apply-tool in the interchange-normalization mould.

**Effort:** ~1–1.5 days implementation + 0.5 day test/verification.

---

# Feature 5 — Destination Dashboard screen (`/business/destination-dashboard`)

## What exists
The backend is fully built and tenant-scoped (fail-closed) but has **zero frontend and no menu row**; it was role-gated shut during the security pass. Four POST endpoints on `/api/business/destination-dashboard`, all taking the standard `VolumeRevenueFilterDTO` body:

- `/kpis` — domestic + international blocks (volume, txns, MSF, growth vs auto prior window, active merchants, avg ticket, effective bps) + intl DCC opt-in metrics + share %.
- `/trend` — monthly `domVolume/domTxns/domMsf/intlVolume/intlTxns/intlMsf`.
- `/breakdown/{dimension}` — dom/intl split by `scheme | cardType | channel | mcc`.
- `/top-merchants?limit=` — per-merchant dom/intl volume + MSF + intl share %.

All read `sum_daily_insight`, splitting on `destination='DOMESTIC'` vs everything else.

## Known data caveats (drive the plan)
1. `sum_daily_insight.total_volume` is **cardholder currency** — the international series is a mixed-currency sum; `total_msf` is not interchange-net.
2. The purpose-built table `sum_daily_merchant_destination` (settlement currency, real fee columns: interchange, scheme fee, ecom fee, net revenue; partitioned + RLS'd) **exists but is never populated** — its promised `populateSummaryStep` INSERT was never written.
3. There is **no per-country dimension** — the data supports a domestic-vs-international split, not a world map.

## Phase A — ship the screen (on existing endpoints)

**Backend touch-ups (small):**
1. Replace the `hasAnyRole('ADMIN','SUPER_ADMIN')` shut-gate with `@PreAuthorize("@menuAccess.canAccess('/business/destination-dashboard')")` — consistent with every other business screen.
2. `breakdown/{dimension}`: unknown dimension currently throws → 500; return 400. (FE also constrains the value.)
3. `/trend`, `/breakdown`, `/top-merchants`: default missing dates server-side (last 12 months / 30 days) instead of scanning all history.
4. `/kpis`: remove the silent `catch → zeros`; let errors surface as 500 so the screen can show a real error state (per the silent-swallow lesson from the audit).

**Menu + route:**
5. Migration `V2026_08_15_01__destination_dashboard_menu.sql` (template: `V2026_07_05_04__loss_making_menu.sql`): insert `('Destination Dashboard','/business/destination-dashboard','Globe','BUSINESS',16)` guarded, grant to `Super Admin`/`Bank Admin` (+ uppercase variants), `ON CONFLICT DO NOTHING`; add to `application.properties` schema-locations; mirror in `MenuController.ensureMenusExist` (the startup safety net) so every environment gets it on next boot.
6. `App.jsx`: lazy import + route in the Business block. Plain `ProtectedRoute` (no RoleGuard — the API is menu-gated).

**The page** (`frontend/src/pages/business/DestinationDashboard.jsx`) — copy the `VolumeRevenueSummary.jsx` skeleton (it already POSTs the same DTO):
- `PremiumReportHeader` + `BusinessFilters` with **`hideDestination`** (backend ignores `destinationList` — destination IS the split) + date range defaulting to last 30 days (KPIs) / 12 months (trend).
- **KPI row** (`KpiCards`): Domestic volume, International volume (with growth trends from the prior-window numbers), Intl share %, DCC opt-in rate (intl only). Mixed-currency caveat: a small info badge on the international cards — *"international volume is summed in cardholder currencies"* (removed in Phase B).
- **Trend**: recharts stacked bar (dom vs intl volume) with an MSF line overlay, monthly.
- **Breakdown**: tab strip `Scheme | Card type | Channel | MCC` → grouped horizontal bars dom vs intl; dimension values hardcoded to the four the backend accepts.
- **Top merchants**: MUI `DataGrid` + `GridToolbar` (mid, name, dom/intl volume, intl share % with an inline bar), CSV via `exportToCSV`.
- Standard conventions: `T` token object for theming, `createFmt(currencySymbol, currencyDecimals)` from `useAuth()`, `tenantVersion` in every fetch dep array, `SkeletonLoader` while loading, and a **visible error state** (not a silent zero dashboard).

**Verification:** build; login as Bank Admin on the BH tenant → menu appears, screen renders BHD-formatted domestic numbers matching a psql cross-check of `sum_daily_insight`; grant-toggle check (user without the menu → 403 API / no sidebar entry); tenant switch re-fetches (AE ≠ BH numbers).

## Phase B (recommended follow-up) — real settlement-currency numbers
Populate `sum_daily_merchant_destination` and repoint the repository at it:
1. Add the INSERT to `populateSummary` (mirroring the `sum_daily_merchant` block, plus `destination` in grain and the fee columns from the fact row) **and, in the same change, the identical SQL to `BulkMigrationService`** — the known drift trap: a rebuilt month must produce the same rows.
2. Rebuild historical months via the existing bulk-migration path.
3. Repoint `DestinationDashboardRepository` from `sum_daily_insight` to the new table → volume becomes settlement currency, and MSF/interchange/scheme-fee/net-revenue become real. Drop the mixed-currency caveat badge; optionally add a "net revenue dom vs intl" KPI.

**Effort:** Phase A ~1–1.5 days; Phase B ~1 day (dominated by rebuild verification).

## Order of execution
1. Feature 5 Phase A (independent, instantly visible).
2. Feature 1 (batch change + E2E on BH pilot).
3. Feature 5 Phase B (touches the same `populateSummary` area as Feature 1's verification — do after 2 to avoid conflating fee/summary diffs).
