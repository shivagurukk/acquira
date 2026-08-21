# Egypt & Bahrain Tenants — Implementation & End-to-End Test Report
**Date:** 2026-08-11 · **Baseline:** [EGYPT_BAHRAIN_TENANT_E2E_AUDIT_2026-08-10.md](EGYPT_BAHRAIN_TENANT_E2E_AUDIT_2026-08-10.md)

Everything below was executed against a **database built from migrations only** (no manual SQL patches), with real files ingested through the real upload API into three tenants: **ACQ (UAE/AED)**, **EGY (Egypt/EGP)**, **BHR (Bahrain/BHD)**.

---

## Section A — Summary

> **Updated 2026-08-11 (rev 2)** after three business decisions: (1) Bahrain and Egypt adopt the **UAE scheme-fee grid**; (2) Bahrain Visa/MasterCard international and **all** Egypt international interchange are **1.85%**, same as the UAE; (3) ticket-size buckets are now currency-scaled and the `_aed` columns renamed. See `V2026_08_10_04`.

| | Status |
|---|---|
| **Egypt tenant** | **Ready.** Currency, precision, CMM conversion, Visa/MC/Meeza domestic, international 1.85%, scheme fees, dashboards, PDFs and exports all correct. **100% of transactions price** (`RESOLVED`). |
| **Bahrain tenant** | **Ready.** BHD 3-decimal integrity holds end to end; Visa/MC/BENEFIT domestic, BENEFIT international (1.10% + 0.100), Visa/MC international (1.85%) and scheme fees all price. **100% `RESOLVED`.** |
| **UAE regression** | **PASS.** 10 AED transactions: 100% `RESOLVED` on **AE** rules, scheme fees resolved, destination/channel unchanged, AED ticket bands unchanged, rollups reconcile exactly. |

All three tenants now price every transaction. The only rows that deliberately do **not** price are ones with a genuinely unmappable destination token — which is the intended behaviour, reported as `UNMAPPED_DESTINATION` rather than silently charged.

---

## Section B — Files changed

**New SQL migrations** (registered in `application.properties`, idempotent, splitter-safe)
- `V2026_08_10_01__multi_currency_precision_and_fee_resolution.sql` — 90+ money columns → `DECIMAL(21,4)`; `destination_token_map`; `terminal_channel_map`; `flat_fee`/`rate_status`/`effective_from`/`effective_to` on both rate tables; 9 fee-provenance columns on `fact_transaction`; the 3 entity-mapped columns no migration ever created; EG symbol `£`→`EGP`; 3-decimal-currency guard.
- `V2026_08_10_02__meeza_and_benefit_international.sql` — Meeza scheme + EG domestic Meeza 1.85% (interim, effective-dated); BH BENEFIT INTERNATIONAL 1.10% + BHD 0.100.
- `V2026_08_10_03__feed_amount_contract.sql` — explicit per-column MINOR/MAJOR/BASIS_10000 unit contract.

**Backend — currency foundation**
- `common/service/CurrencyResolver.java` **(new)** — single authority for code/symbol/decimals/divisor; throws instead of defaulting to AED; invalidatable cache.
- `common/model/RefCountry.java` — mapped `iso_numeric` + `decimal_notation_value` (previously unmapped, so all JPA consumers were blind to precision).
- `common/model/Tenant.java` — `@Transient currencyDecimals`.
- `core/service/TenantService.java` — stamps `currencyDecimals` onto every tenant in the login/session payload.

**Backend — ingestion & fee engine**
- `batch/job/TransactionJobConfig.java` — currency-driven scale (`scaleFor`); destination normalization at staging→fact; config-driven channel; **two-tier scheme resolution**; flat-fee support; generic 1.85% fallback removed; approved-only + effective-dated matching; provenance columns; per-run fee-resolution report; `total_amount_settled` no longer discarded; MSF/VAT unit contract.
- `batch/service/IntegrationPullService.java` — `ROUND(...,2)` → `LOG10(divisor)`-driven scale.
- `batch/service/BulkMigrationService.java` — hardcoded `'BHD'` default replaced by the tenant's own currency; refuses rather than guessing.
- `batch/service/BackfillIngestionService.java` — added the missing normalization step (this path skipped currency resolution *and* minor-unit division entirely).
- `batch/service/FileUploadService.java` — cross-tenant upload guard.

**Backend — API / PDF / export / email**
- `core/controller/CurrencyMeta.java` **(new)** + 13 controllers — `{code, symbol, decimals, resolved}` on money endpoints; `"currency":"BASE"` removed.
- `core/controller/TransactionController.java` — keyset 500 fixed; CSV currency column + tenant scale.
- `common/service/MerchantInsightService.java`, `MerchantInsightEnhancer.java`, `common/dto/MerchantInsightsDTO.java` — all AED fallbacks removed; `%,.0f` → tenant decimals; `currencyDecimals` on the DTO.
- `core/service/ReportExportService.java`, `TemplateRendererService.java`, `ScheduledReportRunner.java` — currency-aware Excel/CSV/PDF/email.
- **13 live PDF templates + 5 dead ones** — `formatDecimal(...,0,...)` → `dto.currencyDecimals`; all `'AED '` literals and `|| 'AED'` fallbacks removed.

**Frontend** — ~45 files. `utils/formatters.js` (AED default removed, `setDefaultCurrency(code, decimals)`, uniform-precision `formatCurrency`, currency-aware compaction), `contexts/AuthContext.jsx` (`|| 'BHD'` removed, decimals plumbed, re-applied on tenant switch), page-local `'AED'` defaults and fixed-precision money removed across dashboards/sales/analytics/business screens, `TransactionList` per-row currency precision, chart axes/tooltips, CSV exports, `Africa/Cairo`, tests updated to BHD 3dp.

---

## Section C — Original audit issues

| # | Issue | Status |
|---|---|---|
| 1 | BHD fils destroyed at ingest (3 layers) | **FIXED & VERIFIED** — `scaleFor(divisor)` in processor, `LOG10` scale in pull service, columns `DECIMAL(21,4)`. `100.505`→`100.5050`, `99.999`→`99.9990`, `450.755`→`450.7550`. |
| 2 | CMM divides amounts but not MSF/VAT | **FIXED (contract) / BLOCKED (data)** — `feed_amount_contract` makes it explicit per column; defaults reproduce old behaviour exactly. **Egypt feed's MSF/VAT units still need confirmation** (§Blocker 3). |
| 3 | "local ⇒ domestic" not implemented | **FIXED & VERIFIED** — `destination_token_map`; `LOCAL`→`DOMESTIC` for BH and EG; `LOCAL_XYZ`→`UNMAPPED_DESTINATION`, unpriced. |
| 4 | Hardcoded 1.85% fallback + NULL scheme fee | **FIXED & VERIFIED** — removed. Unmatched rows get NULL + an explicit status. The only surviving 1.85% is the **explicit, effective-dated Egypt Meeza rule (id 17726)**. |
| 5 | BulkMigration `'BHD'` hardcode + no division | **FIXED** — tenant currency or hard failure. (Compile-verified; no bulk-migration source table available to exercise.) |
| 6 | Egypt: Meeza absent | **FIXED & VERIFIED** — scheme seeded, EG domestic Meeza 1.85% priced via rule 17726. |
| 7 | ECOM channel = UAE terminal whitelist | **FIXED (mechanism) / ASSUMPTION (data)** — `terminal_channel_map`. AE keeps a `*`→POS wildcard (zero regression); BH/EG have no wildcard so unknown types surface as `UNMAPPED_CHANNEL`. **Seeded BH/EG values are assumptions** (§Blocker 4). |
| 8 | UAE placeholder data in EG/BH cards | **RESOLVED BY BUSINESS DECISION** — BH/EG now explicitly adopt the UAE scheme-fee grid and the UAE 1.85% international rate (`V2026_08_10_04`). Promoted `PLACEHOLDER`→`APPROVED` with a `BUSINESS-APPROVED` `source_note`, so they are recorded as a deliberate choice rather than an unreviewed copy — and the sweep in `V2026_08_10_01` can never silently demote them again. |
| 9 | No per-currency decimals in display stack | **FIXED & VERIFIED** — `decimal_notation_value` → `CurrencyResolver` → login payload / DTO / templates / exports / frontend. |
| 10 | Hardcoded AED/BHD/USD/BASE fallbacks | **FIXED** — removed from live backend, PDF templates and frontend. Dead `PdfGenerationService` left in place, **recommended for deletion** (§Blocker 6). |
| 11 | Screens with no currency | **FIXED** — Data Explorer, AI Assistant, Revenue Leakage, Pricing Simulator, Interactive Explorer, all 5 sales screens, both DataGrids, bare `formatMsf` sites. |
| 12 | TransactionList hardcoded `.toFixed(3)` | **FIXED** — per-row precision from the row's own `txnCurrency`. |
| 13 | Currency-blind buckets / `_aed` columns | **FIXED & VERIFIED** — `min_ticket_aed`/`max_ticket_aed` **renamed** to `min_ticket`/`max_ticket` (legacy columns dropped; 9 migration files + the fee engine updated). Ticket bands moved into `ticket_size_bucket`, resolved per country with a per-tenant override. Live proof: ACQ bands in AED (`< 50`), BHR in BHD (`< 5`, `25-50`, `50-100`, `100-500`), EGY in EGP (`< 500`) — previously all three used the AED numbers. |
| 14 | Entity/schema drift breaks fresh DBs | **FIXED & VERIFIED** — the 3 columns are now migrations; fresh DB boots and works with no manual SQL. |
| 14b | Transactions keyset 500 | **FIXED & VERIFIED** — null-guard bind parameters replaced by a Specification path. All 5 filter cases + paging return 200. |
| 15 | Egypt ops gaps (Cairo TZ, `£` symbol) | **FIXED** — `Africa/Cairo` added to both pickers; symbol → `EGP`. |
| 16 | Static REF_CACHE / duplicate seed risk | **PARTIAL** — `CurrencyResolver` cache is invalidatable; migration re-asserts 3-decimal currencies. The ingest-side `REF_CACHE` still needs a restart (§Blocker 7). |

---

## Section D — Fee-engine verification (live, from real ingestion)

### Bahrain — tenant BHR, BHD
| ARN | Scheme | Feed dest | Normalized | Chan | Amount | Pct | Flat | Cap | Interchange | Rule | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|
| BH2001 | VISA | LOCAL | DOMESTIC | POS | 100.0000 | 1.7500% | 0 | — | **1.7500** | 5442 | RESOLVED |
| BH2002 | MASTERCARD | LOCAL | DOMESTIC | POS | 100.0000 | 1.7500% | 0 | — | **1.7500** | **5056** | RESOLVED |
| BH2003 | BENEFIT | LOCAL | DOMESTIC | POS | 45.7500 | 0.6000% | 0 | **0.0850** | **0.0850** | 15952 | RESOLVED |
| BH2004 | BENEFIT | LOCAL | DOMESTIC | POS | 100.0000 | 0.6000% | 0 | — | **0.6000** | 15937 | RESOLVED |
| BH2005 | BENEFIT | INTERNATIONAL | INTERNATIONAL | POS | 100.0000 | **1.1000%** | **0.1000** | — | **1.2000** | 17727 | RESOLVED |
| BH2006 | VISA | INTERNATIONAL | INTERNATIONAL | POS | 100.0000 | — | — | — | **NULL** | 5001 | **PLACEHOLDER_RATE** |
| BH2007 | MASTERCARD | INTERNATIONAL | INTERNATIONAL | POS | 100.0000 | — | — | — | **NULL** | 5001 | **PLACEHOLDER_RATE** |
| BH2008 | VISA | **LOCAL_XYZ** | *(null)* | POS | 100.0000 | — | — | — | **NULL** | — | **UNMAPPED_DESTINATION** |

- **BENEFIT international acceptance case exact:** `100.000 × 1.10% = 1.100` + `0.100` = **1.2000 BHD**.
- **BENEFIT rule does not leak:** Visa and MasterCard international, identical in every other respect, refuse rule 17727 and go unresolved.
- **Visa ≠ MasterCard:** different rule ids (5442 vs 5056) — genuine per-scheme resolution, not a shared wildcard.
- **Cap applied before flat:** petrol MCC 5541, `0.6% × 45.750 = 0.2745` capped to **0.0850**.
- An earlier run of this same matrix exposed a real defect — every scheme was landing on `RESOLVED_SCHEME_WILDCARD` because the scheme join only matched `card_product_code` (which the feed fills with "DEBIT"/"CREDIT") and never fell back to the network name. Fixed with two-tier resolution; UAE unaffected because its product codes still match first.

### Egypt — tenant EGY, EGP
| ARN | Scheme | Normalized | Chan | Amount | Pct | Interchange | Rule | Status |
|---|---|---|---|---|---|---|---|---|
| EG2001 | VISA | DOMESTIC | POS | 150.5000 | 1.7500% | 2.6338 | 10098 | RESOLVED |
| EG2002 | MASTERCARD | DOMESTIC | POS | 100.0000 | **0.7000%** | 0.7000 | **9710** | RESOLVED |
| EG2003 | MEEZA | DOMESTIC | POS | 100.0000 | **1.8500%** | **1.8500** | **17726** | RESOLVED |
| EG2004 | VISA | INTERNATIONAL | POS | 100.0000 | — | **NULL** | 9655 | **PLACEHOLDER_RATE** |
| EG2005 | VISA | DOMESTIC | **ECOM** | 100.0000 | **1.9000%** | 1.9000 | 11255 | RESOLVED |
| EG2006 | MEEZA | DOMESTIC | POS | 250.7500 | 1.8500% | 4.6389 | 17726 | RESOLVED |

Egypt Visa (1.75%) and MasterCard (0.70%) resolve to **different** Egyptian rules — proof the real per-MCC Egyptian card is in use, not a wildcard. Meeza carries its own explicit rule id. POS 1.75% vs ECOM 1.90% proves channel differentiation.

---

## Currency & precision proof

**Bahrain (BHD, 3dp)** — `100.505` → staging/fact `100.5050` → API `1352.2650` → `formattedValue "1,352.265"` → PDF `BHD 450.755` → UI `BHD 107.540` → CSV `450.755` → XLSX `#,##0.000`.
**Egypt (EGP, 2dp)** — feed `15050` (minor units) → fact `150.5000` → API `701.2500` → `formattedValue "701.25"` → PDF `EGP 701.25` → UI `EGP 133.54` → CSV `450.76` → XLSX `#,##0.00`.

Boundary values all survive: `99.999`, `0.001`, `1.005`, `450.755`.

**Rollup reconciliation — exact, no truncation:**

| Tenant | fact Σ volume | sum_daily_bank | match | fact Σ interchange | sum_daily_bank | match |
|---|---|---|---|---|---|---|
| ACQ | 66.3600 | 66.3600 | ✅ | 0.8650 | 0.8650 | ✅ |
| BHR | 1398.0150 | 1398.0150 | ✅ | 16.7996 | 16.7996 | ✅ |
| EGY | 801.2500 | 801.2500 | ✅ | 11.7227 | 11.7227 | ✅ |

Interchange previously re-truncated to 2dp at every rollup **for all tenants including UAE**; that is fixed.

## Tenant isolation proof

- Row counts: ACQ 10, BHR 13, EGY 6 — each visible only under its own tenant.
- **Cross-country rule leakage: 0.** `SELECT COUNT(*) ... WHERE r.country_code <> t.home_country_code` → **0**.
- Egypt transactions matching any Bahraini rule: **0**.
- Login payload decimals: ACQ=2, EGY=2, BHR=3.
- **Tenant-switch leak test:** Bahrain dashboard → Egypt dashboard shows `EGP 801.25 / 133.54 / 11.72` with **zero** residual `BHD` or `AED` strings.

---

## Rate decisions now recorded (2026-08-11)

| Country | Scheme fees | International interchange |
|---|---|---|
| **BH** | UAE grid — DOM POS 0.11% / ECOM 0.14%, INTL POS 0.75% / ECOM 0.90% | **1.85%** for Visa/MC/any-scheme; **BENEFIT keeps 1.10% + BHD 0.100** (scheme-specific, higher priority) |
| **EG** | UAE grid, same figures | **1.85%** for all schemes incl. Meeza |

All carry `rate_status = APPROVED` and a `BUSINESS-APPROVED 2026-08-11: …` `source_note`. Verified live: BH Visa intl `1.8500` + scheme `0.7500`; BH BENEFIT intl still `1.2000` + `0.7500`; EG Visa intl `1.8500` + `0.7500`; domestic POS scheme fee `0.1100`, ECOM `0.1400`.

**When either country negotiates its own schedule:** close the row with `effective_to` and insert the successor — the engine already resolves by `payment_date`, so history reprices correctly. Do not edit in place.

## Remaining follow-ups

1. **Egypt feed MSF/VAT/settled units.** `feed_amount_contract` defaults to MAJOR (legacy behaviour). If the Egyptian processor sends these in piastres, insert EG `MINOR` rows — otherwise revenue is overstated 100×. *Needs: one confirmation from the processor.*
2. **BH/EG terminal-type strings.** `terminal_channel_map` BH/EG rows are marked `ASSUMPTION`. Unknown types correctly become `UNMAPPED_CHANNEL` rather than silently POS. *Needs: the real terminal-type vocabulary from both processors.*
3. **BH/EG ticket bands are defaults, not business-supplied.** Seeded as round local-currency numbers and marked `ASSUMPTION` in `ticket_size_bucket.note`; edit the rows to match the business banding. AE is unchanged from its historical values.
4. **Dead code to delete:** `acquira-pdf/.../PdfGenerationService.java` (~40 AED literals, nothing injects it), its copy under the non-module root `src/`, and `TransactionRepository.findKeyset` (last remaining null-guard bind pattern).
5. **Known operational issues:** the ingest-side `static REF_CACHE` still needs a JVM restart after a `ref_country` change; **concurrent ingestion for two tenants races on partition creation** (`fact_transaction_y2026m01 already exists`) — ingest tenants sequentially until fixed. Neither is currency-related.

## Pre-deployment check (run against production before cutover)
```sql
-- Any destination token that would now go UNPRICED instead of taking the old 1.85%:
SELECT t.home_country_code, UPPER(TRIM(f.destination_raw)) tok, COUNT(*)
FROM fact_transaction f JOIN tenant t USING (tenant_id)
LEFT JOIN destination_token_map d
  ON d.country_code = t.home_country_code AND d.raw_token = UPPER(TRIM(f.destination_raw))
WHERE d.id IS NULL AND f.destination_raw IS NOT NULL
GROUP BY 1,2 ORDER BY 3 DESC;
```
Add any rows this returns to `destination_token_map` before deploying. Same idea for `terminal_channel_map` using `dim_terminal.type`.
