# Egypt (EGP) & Bahrain (BHD) Tenant — End-to-End Currency Audit & Live Test
**Date:** 2026-08-10 · **Scope:** ingestion → fee engine → DB → APIs → UI screens → exports → PDFs, for a new Egypt tenant (EG/EGP, 2dp) and a new Bahrain tenant (BH/BHD, 3dp).

**Method:** four parallel code audits (backend pipeline, APIs+PDF, frontend, Egypt readiness) **plus a live E2E test** on the local dev stack: created both tenants through the real API, ingested merchant + transaction files for each (EG as CMM/minor-units, BH as AMS/3-decimal), and verified DB values, dashboards, and generated PDFs.

**Live test artifacts (left in the local dev DB for reproduction):**
- Tenant 8 = `EGY` / Nexus Egypt Bank (EG, EGP, CMM) · Tenant 9 = `BHR` / AFS Bahrain Bank (BH, BHD, AMS)
- Merchants: MIDs 2000001/2000002 (EG), 3000001/3000002 (BH); transactions dated 2026-08-01
- Remove with: `DELETE FROM fact_transaction WHERE tenant_id IN (8,9); DELETE FROM sum_daily_bank WHERE tenant_id IN (8,9);` (and the other `sum_*`/`dim_*` tables, then the two `tenant` rows)
- Local-only schema patches applied during testing (see Issue 14): `fact_transaction.issuer_bank/issuer_country`, `dim_merchant.generate_report_flag INTEGER`

---

## Verdict at a glance

| Requirement | Egypt (EGP) | Bahrain (BHD) |
|---|---|---|
| Tenant creation sets country/currency | ✅ works (verified live) | ✅ works (verified live) |
| Reference data (ref_country, divisor) | ✅ EG/EGP/100 | ✅ BH/BHD/1000 |
| Amounts survive ingestion at full precision | ✅ (2dp aligns) | ❌ **fils destroyed** (verified: 100.505→100.51, 99.999→100.00) |
| MSF/VAT ingestion (CMM minor units) | ❌ **not divided** (verified: 195% "margin") | n/a (AMS) — but same code path |
| Destination "local ⇒ DOMESTIC" rule | ❌ not implemented (verified: 1.85% fallback) | ❌ not implemented (verified) |
| Fee engine rate card reachable | ⚠️ only for literal `DOMESTIC`/`INTERNATIONAL` | ⚠️ same; BENEFIT row also mispriced via LOCAL |
| Currency label on dashboards | ✅ EGP shown (verified) | ✅ BHD shown (verified) |
| Decimal precision on screens | ⚠️ mostly 0dp compaction | ❌ 2/3dp jagged + fils hidden |
| Screens with NO currency at all | ❌ ~10 screens | ❌ same |
| CSV/Excel exports | ❌ no currency, fixed 2dp | ❌ same + fils rounded away |
| PDFs | ⚠️ EGP label ✅, 0 decimals ❌ (verified) | ⚠️ BHD label ✅, 0 decimals ❌ (verified) |
| Tenant isolation (data) | ✅ verified — no leakage either direction | ✅ verified |
| Tenant isolation (config/currency) | ✅ per-tenant currency correct on all tested screens | ✅ |

**Isolation:** verified clean. Each tenant's dashboards, rollups, and PDFs showed only its own data with its own currency. Rate cards are shared **country-level** rows (by design); per-tenant overrides are the differentiation mechanism.

---

## CRITICAL — wrong numbers stored or computed

### 1. BHD third decimal (fils) destroyed at ingestion — every amount, every table
**Verified live:** uploaded `100.505` BHD (AMS, no division needed) → `fact_transaction` stores `100.51`; `99.999` → `100.00`. Sum rolled up as `496.51` instead of `496.504`.
Three independent layers each destroy the 3rd decimal; all must be fixed:
- `TransactionJobConfig.java:689,698` — `divide(divisor, 2, HALF_UP)`: divisor is currency-aware, **result scale hardcoded 2**.
- `IntegrationPullService.java:611-622` — same bug in SQL: `ROUND(amount/divisor, 2)`.
- `schema.sql:518,520,802,804` + every `sum_*` volume/fee column — `DECIMAL(19,2)` columns truncate even correctly-scaled values. (`V4__msf_4_decimals.sql` fixed this for MSF only and is not in the startup migration list.)
**Fix order:** widen columns → derive scale from `ref_country.decimal_notation_value` (1000→3, 100→2) as `InterchangeNormalizationService.java:759-771` already does → re-ingest any BH data loaded before the fix.
Also: `fact_transaction.interchange_fee` is DECIMAL(19,4) but `sum_daily_*.total_interchange` is (19,2) — interchange is re-truncated at rollup for **all** tenants (verified: BH 8.9352 → 8.94).

### 2. CMM (minor-unit) files: amounts are divided, MSF/VAT are NOT
`TransactionJobConfig.java:512-513` reads MSF and VAT raw — they are assumed to be final decimals in every input format, while `Txn/Store Currency Amount` are divided by 100/1000.
**Verified live (Egypt, CMM):** amount `15050` → `150.50` ✅ but MSF `225` stored as `225.00 EGP` (should be `2.25`) → Executive Dashboard shows **MSF EGP 7.2K on EGP 3.7K volume, "net margin 195.84%"**.
**Action:** confirm with the real Egypt feed whether MSF/VAT/settled columns arrive in minor units or decimals. If minor units, divide them in the same place amounts are divided. Either way, document the contract per column; today it's implicit and internally inconsistent. (`Total Amount Settled` is discarded entirely — `TransactionJobConfig.java:700`.)

### 3. Destination "local ⇒ DOMESTIC, else INTERNATIONAL" — still not implemented
Exact-string match only (`TransactionJobConfig.java:1181,1225`: `i.dest = UPPER(TRIM(destination))`).
**Verified live (both tenants):** rows with `LOCAL` matched no rate row → interchange silently replaced with hardcoded **1.85%** (`:1112`) and scheme fee **NULL→0**. The Bahrain BENEFIT test row (petrol MCC 5541, should be 0.6% capped 0.085 BHD) was charged **1.85%, 0.846 BHD instead of 0.085 BHD** because its destination said LOCAL. This alone made the BH tenant show **negative net margin (-0.52%)** on the dashboard.
**Fix:** the `destination_token_map` design from `docs/BAHRAIN_TENANT_ONBOARDING_AUDIT_AND_PLAN_2026-08-08.md` Phase 2 (normalize at staging→fact; remove the 1.85% fallback; surface unmapped tokens). Get the **actual destination tokens** from the real EG and BH feeds before go-live.

### 4. Hardcoded 1.85% fallback + NULL scheme fee = silent mispricing
`TransactionJobConfig.java:1110-1117`. Any unmatched transaction prices at the UAE cross-border rate with zero scheme fee, indistinguishable from a real zero. The code comments at `:1062-1064` and `:1090-1093` claim the feed value is kept — **false**, the fallback overwrites it (verified live: feed interchange 0.603 → stored 1.8594). No `fee_resolution_status`, no rule-id provenance, no effective-dating anywhere.

### 5. BulkMigrationService: hardcoded `'BHD'` currency + no minor-unit division
`BulkMigrationService.java:465-466` — unmapped currency columns default every migrated row to `'BHD'` regardless of tenant; `:486-504` inserts amounts with **no** minor-unit division at all. Migrating a CMM-format source this way lands amounts 100×/1000× too large, labelled BHD. Also `BackfillIngestionService.java:152-215` (4th ingest path) skips division AND currency-code resolution entirely.

---

## HIGH — fee data gaps (numbers will be wrong even when the pipeline works)

### 6. Egypt: Meeza scheme absent
No Meeza row in `ref_card_scheme` (`schema.sql:104-136`), no EG scheme fee, explicitly skipped (`V2026_07_31_05:9-10`). Egypt's national debit scheme → prices at generic 1.75%/1.90% wildcard, massively overstating cost on the largest domestic debit slice. Needs the equivalent of `V2026_08_08_05` (BENEFIT) for Meeza, with real Meeza economics.

### 7. ECOM channel detection is a UAE terminal-type whitelist
`TransactionJobConfig.java:1146-1147` — `IN ('ECOM PROFILE','MPGS','PAY BY LINK','PAY ON') → ECOM, else POS`. Egyptian/Bahraini processors emit different terminal types → **all** their e-commerce classifies as POS; half of each country's rate card (774 EG ECOM rows) is unreachable. Needs `terminal_channel_map(country_code, type, channel)` config + the real terminal-type strings from both feeds.

### 8. Placeholder UAE data in EG/BH rate cards
- Scheme-fee grids for EG and BH are verbatim UAE copies (`V2026_07_31_05:2383-2390`, `V2026_07_31_03`).
- INTERNATIONAL interchange = flat 1.85% "per UAE" for both countries.
- No `ecom_flat_fee` row for EG or BH (deliberate; means 0 until seeded — `V2026_07_31_06:50-51`).
- EG card has no card_type/tier differentiation (all wildcard); BENEFIT scheme fee not seeded (UAE wildcard 0.11% applies).
These are data-acquisition tasks from the EG/BH business cases.

---

## HIGH — display/formatting (systemic)

### 9. No per-currency decimal precision anywhere in the display stack
`ref_country.decimal_notation_value` is read **only** by ingestion. `Tenant`/DTOs/frontend have no decimals field; `RefCountry.java` doesn't even map the column. Consequences:
- **PDFs: every amount is 0 decimals** (`MerchantInsightService.java:1489,1502-1505` `String.format("%,.0f")`; all 13 live Thymeleaf templates use `formatDecimal(...,0,...)`). Verified live: BHD `450.755` → PDF shows `451` (labelled BHD correctly).
- **Frontend:** `formatters.js:70-71` `minimumFractionDigits: 2` (no max) → BHD renders jagged 2-or-3dp in the same column (verified: `BHD 496.51` next to `BHD 7.387` on the Executive Dashboard). `AuthContext.jsx:209-215` defaults to **0** decimals. Compact K/M/B tiers (`formatters.js:120-122` + ~10 page-local clones) hardcode `.toFixed(2)/(1)`.
- **Excel/CSV:** `ReportExportService.java:55` `"#,##0.00"`; `TransactionController.java:268` `%.2f`; Dashboard/CeoVolumeRevenue CSV exports `.toFixed(2)`.
- **Email:** `TemplateRendererService.java:192-197` `%,.2f`, no currency variable offered.
**Fix pattern:** surface `decimal_notation_value` → tenant/login payload → `setDefaultCurrency(code, decimals)` (`formatters.js:9`) and a `decimals` field on `MerchantInsightsDTO`; then replace the hardcoded scales.

### 10. Hardcoded/fallback currency literals ("still using a hard-coded or default currency")
Live paths that will mislabel a misconfigured tenant:
- Backend PDF/insights: `MerchantInsightService.java:205,253,268-269,366-367` — falls back to **AED** silently (bare `// ignore, fallback to AED`).
- PDF templates: `basic-report.html:469,605,699,729` JS fallback `|| 'AED'`; `p04b-heatmap.html:94,113` literal `'AED '` prefix (live template!).
- Frontend: `formatters.js:8` default `'AED'`; **`AuthContext.jsx:181` falls back to `'BHD'`** — backend and frontend disagree on the fallback for the same null; page-local `= 'AED'` defaults in ExecutiveDashboard/GroupReports/DebitPrepaidMetrics/MerchantFinancialSummary/FinanceDashboard/TransactionTrendsHub.
- `ExternalDataApiController.java:116` returns literal `"currency":"BASE"`.
- DB: `tenant.base_currency DEFAULT 'USD'` (schema.sql:303) + `home_country_code DEFAULT 'AE'` — a tenant created without jurisdiction silently becomes a USD-labelled UAE-priced tenant. `TenantManagement.jsx:228` Jurisdiction is **not required**, and `BankController.java:110-115` blank→`'AE'` with no alarm (`warnIfCountryCurrencyMismatch` only catches AE+non-AED, not the inverse).
- Dead but confusing: `PdfGenerationService.java` (both copies) ~40 AED literals — unused (nothing injects it); delete to prevent resurrection.
- Tests lock in wrong behavior: `AuthContext.test.jsx:37-65` asserts `'BHD 1,234'` (0dp).

### 11. Screens with NO currency indication at all (bare numbers)
Data Explorer (explicitly "symbol-agnostic", plus money measures pinned 2dp/4dp), AI Assistant, Revenue Leakage, Pricing Simulator, Interactive Explorer, all 5 Sales screens (Leaderboard, Executive, Hierarchy, Agent Directory, Portfolio panel), TransactionPerformanceDashboard + MerchantAnalyticsReport grids (`style:'decimal'` helpers misnamed `formatCurrency`), FinanceSummary MSF cells (`formatMsf` called without symbol), most chart axes/tooltips (`${v/1000}k`). And **no API response except merchant-insights carries a currency field** (17 money-bearing controllers, 0 currency keys) — the UI can only ever guess from the login payload.

### 12. TransactionList hardcoded per-row precision
`TransactionList.jsx:286` — amount `.toFixed(3)` for every currency (wrong for EGP/AED, right for BHD by accident); `:288` MSF `.toFixed(4)`; no currency label in the AMOUNT column header.

---

## MEDIUM — correctness/operational

### 13. Currency-blind thresholds and buckets
- Ticket-size buckets (`<50 / 50-100 / ... / 5K+`) compare raw settlement amounts — calibrated for AED; meaningless for BHD (50 BHD ≈ 487 AED) and EGP (50 EGP ≈ 4 AED). `TransactionJobConfig.java:1621-1630` + 2 copies.
- `min_ticket_aed`/`max_ticket_aed`/`cap_amount` are unitless columns interpreted in the tenant's settlement currency; the `_aed` name is a trap (acknowledged in `V2026_08_08_05:26-28`).
- `sum_daily_merchant_attribute` 'COUNTRY' uses `txn_currency` as issuer-country proxy → EG domestic shows a "country" called EGP.

### 14. Entity↔schema drift breaks screens on any freshly provisioned DB (verified live)
- `Transaction` entity maps `issuer_bank`/`issuer_country`; no startup migration creates them → **Transactions screen 500s** on a fresh DB (verified; patched locally).
- `DimMerchant` maps `generate_report_flag` (INTEGER) — same problem → **PDF generation 500s** (verified; patched locally).
- After patching, `/api/transactions/keyset` still 500s: `ERROR: could not determine data type of parameter $2` — the `(:param IS NULL OR col >= :param)` keyset query breaks on Postgres when date filters are null. Broken for **all** tenants in this environment.
These columns exist on RDS (added manually at some point) — add idempotent migrations so new environments (and the eventual EG/BH production DBs) don't hit this. Cross-ref: [[acquira-summary-rebuild-drift]] pattern.

### 15. Egypt operational gaps
- `Africa/Cairo` missing from both hardcoded timezone lists (`RegionalSettings.jsx:29-32`, `IntegrationHub.jsx:58`) — Egypt observes DST, Gulf zones don't; scheduled pulls will be an hour off half the year.
- EG `ref_country.currency_symbol` = bare `£` — renders ambiguously (reads as GBP). Recommend `E£` or `EGP`.
- VAT: no rate configured anywhere (feed-supplied) — Egypt's 14% flows through fine, but there's no reasonableness check if the feed computes it wrong.
- Super-admin upload resolves tenant from the file's **Entity Name** column (must equal bank short code or institution id), not the selected tenant (verified live: `Nexus Egypt` failed, `EGY` worked). Document this for the ops runbook or make the X-Tenant-Id context authoritative.

### 16. Caches & reseeds
- `TransactionJobConfig.java:524` `static REF_CACHE` (currency divisor map) loaded once per JVM, never invalidated — a `ref_country` fix requires a restart, else BHD could still divide by a stale value.
- Duplicate `ref_country` seed block (schema.sql:9970, no decimals column, `ON CONFLICT DO NOTHING`) is harmless today but lands `decimal_notation_value=100` for every country if it ever runs first on a fresh DB — BHD would ingest ÷100.
- Legacy `ACQ` seed tenant is Bahrain/BHD in `schema.sql` but priced on the UAE card (deliberately excluded from backfill) — keep excluded, but don't clone it as a template for the real BH tenant.

---

## What was verified working ✅
- Tenant creation via API/UI sets `home_country_code`, `base_currency`, `input_format` correctly (EG/EGP/CMM and BH/BHD/AMS persisted; tenant switcher shows "EGY · Egypt · EGP" / "BHR · Bahrain · BHD").
- `ref_country`: EG=EGP/100, BH=BHD/1000, both with correct ISO numerics; EG rate card (2,331 rows of real MCC data) and BH card + BENEFIT are seeded and **reachable** for literal `DOMESTIC` destination (verified: EG 1.75% MCC 5411, BH 1.75%, UAE-copy scheme fee 0.11% applied).
- CMM ÷100 division on amounts (EG), AMS no-division (BH) — the tenant-level `input_format` switch works with a plain filename.
- Tenant data isolation across dashboards, rollups, merchants, PDFs — no leakage in either direction.
- Currency label (EGP/BHD) correct on Executive Dashboard, tenant switcher, and PDF DTOs when `base_currency` is set.
- Egypt sidesteps the 3-decimal problem entirely (2dp aligns with the hardcoded scales).

## Suggested fix order (go-live floor)
1. Issue 3+4 — destination normalization + kill the 1.85% fallback (both tenants price wrong without it).
2. Issue 1 — BHD 3dp: widen columns, currency-driven scale, before any real BH data is loaded.
3. Issue 2 — confirm the real feeds' MSF/VAT units; fix CMM division accordingly.
4. Issue 7 — terminal-type → channel map (need real feed strings).
5. Issue 6+8 — Meeza + real EG/BH scheme-fee/intl figures (data acquisition, parallel).
6. Issue 9+10 — decimals plumbing + kill AED/BHD/USD fallbacks (PDF + UI + exports).
7. Issue 14 — drift migrations so the new tenants' environments boot clean.
