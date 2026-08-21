# E2E Test Cases — Bahrain & Egypt Tenants (Login → PDF)

**Date:** 2026-08-15
**Environment:** Local — backend Spring Boot :8081, frontend Vite :5173, Postgres 127.0.0.1:5433/postgres
**Scope:** Login → tenant access → ingestion (card/BIN/product type) → screens → persistence → PDF generation, for a Bahrain tenant and an Egypt tenant, with tenant-isolation checks at every stage.

---

## ⚠️ Known implementation status (pre-test finding, from code inspection 2026-08-15)

**BIN-based card/product-type identification is NOT yet wired into ingestion.** This is stated explicitly in the code:

- `BinManagementController.java:16` — *"CONFIGURATION ONLY this phase: nothing in ingestion or the fee engine reads ref_bin yet"*
- `Tenant.java:51` — `card_type_source` (FILE|BIN) is *"CONFIG ONLY for now — no ingestion/fee logic reads it yet"*
- No ingestion path extracts the first 6 digits of `CardNumber`; card type comes from the feed file's `Card Type` column, normalized via `ref_card_scheme` (TransactionJobConfig.java:504, :732).
- Local/non-local (DOMESTIC/INTERNATIONAL) comes from the feed file's `Destination` token via `destination_token_map` (TransactionJobConfig.java:1055-1067) — per the user-confirmed 2026-08-09 decision, BIN data must NOT drive destination; it is scoped to product type only.

Consequently, test group **C (BIN-based card type)** and parts of group **D (product type from BIN)** are **expected to FAIL** against the stated requirement. They are included deliberately so the run documents the gap with evidence. Everything else (tenant isolation, file-driven card type, destination mapping, currency precision, PDF correctness) is genuinely testable today.

**BIN reference data present locally:** `ref_bin_range` has 806,469 rows (VISA 593,505 / MASTERCARD 212,964; BH 1,094, EG 3,736). `ref_bin` (manual 6/8-digit upload table) is **empty** — if `ref_bin` rows are needed as source of truth for any test, they must be uploaded (BIN data to be supplied by tester).

---

## Test tenants and test data

### Tenants (to be created via Super-Admin → Tenant Provisioning / Tenant Management)

| | Bahrain | Egypt |
|---|---|---|
| Institution ID | TESTBH01 | TESTEG01 |
| Bank name | Test Bank Bahrain | Test Bank Egypt |
| Short code (= feed Entity Name) | TBH | TEG |
| Jurisdiction / home_country_code | BH | EG |
| Base currency (decimals) | BHD (3 dp) | EGP (2 dp) |
| input_format | AMS (amounts are final decimals) | AMS |
| card_type_source | BIN | BIN |

> Ops note: backend startup re-runs schema.sql and **wipes tenants/facts** — tenants must be recreated after any backend restart. Ingest the two tenants **sequentially** (concurrent ingestion races on partition creation).

### Card test data (BINs verified against local `ref_bin_range`, 2026-08-15)

| Ref | BIN | Scheme | Issuer country | card_type in BIN table | product_code | Role |
|---|---|---|---|---|---|---|
| BH-L1 | 510146 | MASTERCARD | BH | DEBIT | CIR | BH local debit |
| BH-L2 | 401575 | VISA | BH | CREDIT | N | BH local credit |
| BH-L3 | 510543 | MASTERCARD | BH | PREPAID | MRH | BH local prepaid |
| EG-L1 | 222698 | MASTERCARD | EG | DEBIT | CIR | EG local debit |
| EG-L2 | 400112 | VISA | EG | CREDIT | N1 | EG local credit |
| EG-L3 | 400725 | VISA | EG | PREPAID | F | EG local prepaid |
| NL-1 | 429625 | VISA | US | CREDIT | B | Non-local for both tenants |
| XX-1 | 999999 | — | — | not in any range | — | Unknown/unmapped BIN (negative) |

Card numbers are given in the feed mask format `first6 + ****** + last4`, e.g. `510146******1001`.

### Feed files (AMS format, Entity Name = tenant short code)

- `TBH_E2E_TXN_JUL2026.csv` — 8 rows: BH-L1/L2/L3 with `Destination=LOCAL`, NL-1 ×2 with `Destination=INTERNATIONAL`, one row with unknown destination token `ONSHORE` (negative), one row XX-1, one refund row. Amounts with 3 decimals (e.g. 12.345 BHD).
- `TEG_E2E_TXN_JUL2026.csv` — 8 rows: EG-L1/L2/L3 `LOCAL`, NL-1 ×2 `INTERNATIONAL`, one `ONSHORE` (negative), one XX-1, one refund. Amounts with 2 decimals, incl. the EG-only Meeza scheme row if the canonical layout accepts it.

Distinct merchants per tenant (e.g. MID `TBH-M001` / `TEG-M001`) so PDF generation is per-tenant unambiguous.

---

## Group A — Login & tenant access

### A1 — Login with valid credentials
- **Tenant:** n/a (super-admin)
- **Scenario:** Standard login succeeds and returns tenant context.
- **Preconditions:** Backend + frontend running; seeded super-admin account available.
- **Test data:** Seeded `admin` account.
- **Steps:** Open `/login`, submit credentials.
- **Expected:** 200 from `POST /api/auth/login`; payload contains `jwt`, `allowedTenants`, `defaultTenantId`; redirect to `/dashboard` (or tenant picker if >1 tenant).
- **DB validation:** n/a.
- **PDF validation:** n/a.
- **Status:** ☐

### A2 — Login with invalid credentials
- **Scenario:** Wrong password rejected, no session artifacts.
- **Steps:** Submit bad password.
- **Expected:** 401/403; error shown; no JWT in localStorage.
- **Status:** ☐

### A3 — Tenant list after tenant creation
- **Scenario:** After creating TBH and TEG, super-admin login exposes all tenants; tenant switcher lists ACQ, TBH, TEG with correct name/short-code/country/currency.
- **Preconditions:** B1, B2 done.
- **Expected:** `allowedTenants` contains all three; switcher rows show `Bahrain/BHD` and `Egypt/EGP` correctly.
- **DB validation:** `SELECT bank_short_code, home_country_code, base_currency, input_format, card_type_source FROM tenant` matches the tenant table above.
- **Status:** ☐

### A4 — Forged X-Tenant-Id rejected (isolation, non-super-admin)
- **Scenario:** A request carrying an `X-Tenant-Id` the user has no access to is rejected, not silently remapped.
- **Preconditions:** A tenant-scoped (non-super-admin) user bound to TBH only, if one can be created; otherwise verify via API with a hand-crafted header.
- **Steps:** Call `GET /api/business/dashboard/kpis` with JWT of TBH-only user and `X-Tenant-Id: <TEG id>`.
- **Expected:** 403 "No access to tenant" (JwtRequestFilter.java:194-201) — no data returned.
- **Status:** ☐

---

## Group B — Tenant provisioning & configuration

### B1 — Create Bahrain tenant
- **Tenant:** TBH
- **Scenario:** Super-admin creates the BH tenant with jurisdiction BH, BHD, AMS, card_type_source=BIN.
- **Steps:** `/tenants` (or `/admin/tenant-provisioning`) → create with values from the tenant table; set Jurisdiction dropdown = Bahrain; input format AMS; card type source BIN.
- **Expected:** Tenant saved; visible in switcher.
- **DB validation:** `tenant` row: `home_country_code='BH'`, `base_currency='BHD'`, `input_format='AMS'`, `card_type_source='BIN'`.
- **Status:** ☐

### B2 — Create Egypt tenant
- Same as B1 with EG/EGP values.
- **DB validation:** `home_country_code='EG'`, `base_currency='EGP'`.
- **Status:** ☐

### B3 — Currency decimals propagate to session
- **Scenario:** BHD tenant renders 3-decimal amounts, EGP renders 2, sourced from `ref_country.decimal_notation_value` → `Tenant.currencyDecimals` → login payload.
- **Steps:** Switch to TBH, inspect any amount on dashboard; repeat for TEG.
- **Expected:** BHD amounts 3 dp; EGP 2 dp; no AED symbol/fallback anywhere.
- **DB validation:** `ref_country.decimal_notation_value` = 1000 for BH, 100 for EG.
- **Status:** ☐

---

## Group C — Ingestion & BIN-based card/product type

### C1 — BH file upload resolves to correct tenant by Entity Name
- **Tenant:** TBH
- **Scenario:** Super-admin upload of `TBH_E2E_TXN_JUL2026.csv` lands under the TBH tenant only.
- **Preconditions:** B1; file Entity Name = `TBH`.
- **Steps:** `/upload` → upload BH file; run/await batch.
- **Expected:** Batch completes; rows visible only under TBH.
- **DB validation:** `SELECT COUNT(*) FROM fact_transaction WHERE tenant_id=<TBH>` = row count; **`WHERE tenant_id=<TEG>` = 0**.
- **Status:** ☐

### C2 — EG file upload (sequential, after C1)
- Same for `TEG_E2E_TXN_JUL2026.csv` / TEG.
- **DB validation:** TEG count correct; TBH count unchanged; no rows with each other's tenant_id.
- **Status:** ☐

### C3 — Card type determined from BIN table via first 6 digits (BH) — **requirement test**
- **Tenant:** TBH
- **Scenario:** With `card_type_source='BIN'`, card type of each transaction must come from a lookup of `LEFT(card_number,6)` against BIN config (`ref_bin`/`ref_bin_range`), not from the file column.
- **Test data:** Row BH-L1 (`510146******1001`) deliberately carries a **contradictory** file `Card Type` (e.g. CREDIT) while the BIN table says DEBIT.
- **Expected (requirement):** fact row card_type = DEBIT (BIN wins for BIN-sourced tenant).
- **Expected (current code):** file value wins — `ref_bin*` is never read. **Anticipated FAIL — documents the wiring gap.**
- **DB validation:** `SELECT card_number, card_type, card_product_code FROM fact_transaction WHERE tenant_id=<TBH>` vs `SELECT card_type, product_code FROM ref_bin_range WHERE '<pan19>' BETWEEN range_low AND range_high`.
- **Status:** ☐

### C4 — Card type from BIN (EG) — **requirement test**
- Same as C3 with EG-L1 (`222698`, BIN says DEBIT). **Anticipated FAIL** (same gap).
- **Status:** ☐

### C5 — Result not hardcoded per tenant/country
- **Scenario:** The same non-local card (NL-1 `429625`, US VISA CREDIT) ingested under both tenants must resolve identically (CREDIT) in both — proving no tenant-based hardcoding.
- **DB validation:** card_type for NL-1 rows equal across tenant_ids.
- **Status:** ☐

### C6 — Local vs non-local classification (BH)
- **Scenario:** Rows with `Destination=LOCAL` map to DOMESTIC; `INTERNATIONAL` maps to INTERNATIONAL, via `destination_token_map` for country BH.
- **Note:** By confirmed design (2026-08-09), local/non-local comes from the feed's destination token, **not** the BIN. This case validates the configured mapping table (it is the "BIN/card configuration"-adjacent source of truth for locality).
- **DB validation:** `SELECT destination, destination_raw, COUNT(*) FROM fact_transaction WHERE tenant_id=<TBH> GROUP BY 1,2` → LOCAL→DOMESTIC, INTERNATIONAL→INTERNATIONAL.
- **Status:** ☐

### C7 — Local vs non-local classification (EG)
- Same for TEG.
- **Status:** ☐

### C8 — Unknown destination token (negative)
- **Scenario:** The `ONSHORE` row (no mapping for BH/EG) must surface as unmapped — NOT silently default.
- **Expected:** `destination` NULL, `destination_raw='ONSHORE'`, fee_resolution_status = UNMAPPED_DESTINATION; row visible/flagged, not priced.
- **Status:** ☐

### C9 — Unknown/unmapped BIN (negative)
- **Scenario:** XX-1 (`999999******0001`) is in no BIN range.
- **Expected (requirement):** flagged unknown product type, no crash, row still ingested with card_type from file or NULL; error handling visible.
- **DB validation:** row present; no BIN-derived fields populated.
- **Status:** ☐

### C10 — Invalid card number format (negative)
- **Scenario:** A row with malformed PAN (`51`, or blank) ingests without aborting the batch.
- **Expected:** Batch completes; row rejected or ingested with null card fields per design; error logged, not a 500.
- **Status:** ☐

---

## Group D — Product type from configuration

### D1 — Product code preserved and priced from config (BH)
- **Scenario:** `card_product_code` on fact rows must match config-derived product identification; fee engine resolves rate rules by product code tier first, then network.
- **DB validation:** `SELECT card_product_code, fee_resolution_status, applied_rule_id FROM fact_transaction WHERE tenant_id=<TBH>` — every priced row carries rule-id provenance; no generic 1.85% fallback rows.
- **Status:** ☐

### D2 — Product type from BIN table (BH/EG) — **requirement test**
- **Scenario:** Where product type is required, it must be retrievable from `ref_bin_range.product_code` via first-6 lookup (BH-L3 → MRH, EG-L3 → F).
- **Expected (current code):** not populated from BIN. **Anticipated FAIL** (same wiring gap as C3).
- **Status:** ☐

### D3 — Positive/negative product mapping
- **Scenario:** Known BIN → product resolved (BH-L1→CIR); unknown BIN (XX-1) → no mapping, handled gracefully.
- **Status:** ☐

---

## Group E — UI screens vs database

### E1 — Transactions screen (BH) matches DB
- **Steps:** Switch to TBH → `/transactions`; compare rows/amounts/card types against `fact_transaction WHERE tenant_id=<TBH>`.
- **Expected:** Counts, amounts (3 dp), card types identical; **no TEG rows visible**.
- **Status:** ☐

### E2 — Transactions screen (EG) matches DB
- Same for TEG (2 dp). **No TBH rows visible.**
- **Status:** ☐

### E3 — Dashboard KPIs per tenant
- **Steps:** `/dashboard` under each tenant; compare volume/count to `SUM(amount)/COUNT(*)` per tenant_id.
- **Expected:** Each tenant sees only its own totals; switching tenant refreshes data (no stale cross-tenant cache).
- **Status:** ☐

### E4 — Debit/Prepaid screen split matches DB
- **Steps:** `/business/debit-prepaid` under each tenant vs `GROUP BY card_type`.
- **Status:** ☐

### E5 — Tenant switch cache isolation
- **Scenario:** Switch TBH → TEG rapidly; verify no BH numbers linger (tenantVersion bump + cache invalidation).
- **Expected:** All widgets re-fetch; values match TEG DB.
- **Status:** ☐

---

## Group F — PDF generation & validation

### F1 — Generate BH merchant PDF
- **Steps:** Under TBH → `/business/report-manager` → scope ONE (TBH-M001) → confirm tenant dialog (must display Test Bank Bahrain / TBH / Bahrain / BHD) → generate → download.
- **Expected:** PDF produced under `reports/TBH/<YYYY-MM>/`; batch status SUCCESS.
- **Status:** ☐

### F2 — BH PDF content correctness
- **PDF validation:** Cover: merchant name TBH-M001-name, period, **BHD** currency, sales/txn counts = DB sums (3 dp). Card analytics page: credit/debit/prepaid split = `GROUP BY card_type`; **local vs international split** = DOMESTIC/INTERNATIONAL row counts; avg ticket by card type matches DB.
- **DB cross-check:** totals vs `fact_transaction`/summary tables for tenant TBH only.
- **Status:** ☐

### F3 — Generate EG merchant PDF
- Same as F1 for TEG-M001 → `reports/TEG/<YYYY-MM>/`, EGP.
- **Status:** ☐

### F4 — EG PDF content correctness
- Same as F2 with EGP 2 dp, Meeza/scheme rows if present.
- **Status:** ☐

### F5 — PDF cross-tenant isolation
- **Scenario:** BH PDF contains zero EG data and vice versa.
- **PDF validation:** No TEG merchant names, EGP symbols, or EG totals in the BH PDF (and inverse). Totals in each PDF reconcile exactly to that tenant's DB rows — any excess implies leakage.
- **Status:** ☐

### F6 — Report folder isolation on download
- **Scenario:** Under TEG context, `list-reports`/`download-all-reports` must not serve TBH's files.
- **Note:** Code review flagged `resolveReportFolder`'s fallback to a shared non-tenant `reports/<YYYY-MM>` root (PdfController.java:1150-1158) — watch this path specifically.
- **Status:** ☐

### F7 — PDF regeneration overwrite prompt
- **Scenario:** Re-running generation for the same month prompts overwrite (check-status flow) and does not duplicate/mix files.
- **Status:** ☐

---

## Group G — Cross-tenant isolation sweep (DB-level)

### G1 — Fact/summary tables carry no cross-tenant rows
- **DB validation:**
  - `SELECT tenant_id, COUNT(*) FROM fact_transaction GROUP BY 1` — only expected tenant_ids.
  - Same for `dim_merchant`, `sum_daily_merchant`, `sum_monthly_bank`, `kpi_snapshot_*`.
  - No TBH merchant MIDs under TEG tenant_id and vice versa.
- **Status:** ☐

### G2 — RLS / context enforcement
- **Scenario:** API calls with TBH context return zero TEG rows even when filters are removed (server-side tenant_id binding + `app.current_tenant` GUC).
- **Status:** ☐

---

## Execution order

A1 → A2 → B1 → B2 → A3 → B3 → C1 → C6 → C8..C10 → C2 → C7 → C3..C5 → D1..D3 → E1..E5 → F1..F7 → G1..G2 → A4.

## Requirements-to-status summary (executed 2026-08-15)

| Requirement | Cases | Result |
|---|---|---|
| Tenant isolation end-to-end | C1b, C1, C2, E1–E5, F5, F6, G1, G2 | **PASS** (A4 not run — needs a tenant-scoped user) |
| Complete E2E flow login→PDF | A1–A3, B1–B3, C1, C2, E1–E4, F1–F4 | **PASS** (1 UI defect: multi-tenant login stalls on "Signing in…") |
| BIN-based card type (first 6 digits, config table) | C3, C4, C5 | **FAIL — not implemented** (config-only; file value wins) |
| Product type from config table | D1, D2, D3 | **PARTIAL** — file+ref_card_scheme works; BIN product not wired (D2 FAIL) |
| Local vs non-local validation | C6, C7, C8 | **PASS** (destination_token_map; unmapped surfaces, never defaults) |
| Error handling (invalid PAN, unsupported BIN, unmapped, wrong tenant) | A2, C1b, C8, C9, C10 | **PASS** |
| PDF vs stored data | F2, F4, F5 | **PASS** — both PDFs reconcile to the fils/piastre |

## Execution results (2026-08-15, local env, tenants TBH=8 / TEG=9)

| Case | Result | Evidence |
|---|---|---|
| A1 | PASS | UI login → dashboard; POST /api/auth/login 200 with jwt/allowedTenants/defaultTenantId |
| A2 | PASS | Wrong password → 401 |
| A3 | PASS | allowedTenants = ACQ, TBH (BHD, dp3), TEG (EGP, dp2); switcher lists all 3 with correct meta |
| A4 | NOT RUN | Needs a non-super-admin tenant-scoped user (none seeded). Guard verified in code (JwtRequestFilter 403). SA + bogus X-Tenant-Id 999 returns empty set, no leak |
| B1 | PASS | tenant 8: TBH / BAHRAIN / BH / BHD / AMS / BIN (DB verified) |
| B2 | PASS | tenant 9: TEG / EGYPT / EG / EGP / AMS / BIN (DB verified) |
| B3 | PASS | currencyDecimals 3 (BHD) / 2 (EGP) in login payload; UI renders 12.345 BHD, 33.33 EGP |
| C1 | PASS | 9 rows tenant_id=8 only; Entity Name TBH resolved to tenant 8 |
| C1b (bonus) | PASS | TEG file uploaded under TBH context → HTTP 400 "Tenant mismatch — upload refused", names both tenants |
| C2 | PASS | 9 rows tenant_id=9; TBH counts unchanged; sequential ingest |
| C3 | **FAIL** | 510146 (BIN: DEBIT/CIR) stored as CREDIT/MCCR from file despite card_type_source=BIN — ref_bin/ref_bin_range never read at ingest |
| C4 | **FAIL** | 222698 (BIN: DEBIT/CIR) stored as CREDIT/MCCR — same gap |
| C5 | PASS | 429625 → CREDIT under both tenants; no tenant hardcoding |
| C6 | PASS | TBH: LOCAL→DOMESTIC, INTERNATIONAL→INTERNATIONAL via destination_token_map(BH) |
| C7 | PASS | TEG: same via destination_token_map(EG) |
| C8 | PASS | ONSHORE → destination NULL, destination_raw kept, fee_resolution_status=UNMAPPED_DESTINATION; UI shows "-" |
| C9 | PASS* | Unknown BIN 999999 ingested without error (*no BIN-unknown flagging exists — same wiring gap as C3) |
| C10 | PASS | Malformed PAN "51" ingested; batch completed; UI masks as **** |
| D1 | PASS | Explicit fee_resolution_status on every row; country-specific rule ids where matched (BH 5001, EG 9655); no generic fallback. Note: RESOLVED refunds carry NULL rule id; most rows UNMAPPED_CHANNEL by design (BH/EG have no terminal-channel wildcard) |
| D2 | **FAIL** | card_product_code comes from file Card Type column, never from ref_bin_range.product_code |
| D3 | PARTIAL | Known mappings via ref_card_scheme OK; unknown BIN handled gracefully but not flagged |
| E1 | PASS | TBH /transactions = 9 DB rows, BHD 3dp, no TEG rows |
| E2 | PASS | TEG /transactions = 9 DB rows, EGP 2dp, no TBH rows |
| E3 | PASS | Dashboard/report KPIs match per-tenant DB sums (evidenced via E4/F2/F4 reconciliation) |
| E4 | PASS | TEG Debit/Prepaid: 3 txns EGP 59.08 = 45.75−20.00+33.33; book 4,264.83 ✓; top scheme MEEZA ✓ |
| E5 | PASS | Tenant switch re-fetches immediately; no stale cross-tenant values |
| F1 | PASS | TBH PDF → reports/TBH/2026-07/Insight_Bahrain_Test_Merchant_2026-07.pdf; confirm dialog showed TBH · BAHRAIN · BHD |
| F2 | PASS | Cover: BHD 245.662 net (248.872 − 3.210 refund), 9 txns. Card page: Credit 216.095/6, Debit 4.567/2, Prepaid 25.000/1; Local 147.912 / Intl 87.750 (unmapped ONSHORE excluded) — all equal DB sums |
| F3 | PASS | TEG PDF → reports/TEG/2026-07/, batch 1/1 success |
| F4 | PASS | EGP 4,264.83; Credit 4,205.75 / Debit 59.08 / Prepaid 0.00; Intl 3,600.00 — all equal DB sums, 2dp |
| F5 | PASS | Zero cross-tenant strings/values in either PDF (grep for Egypt/EGP/TEG in BH PDF and Bahrain/BHD/TBH in EG PDF: none) |
| F6 | PASS | list-reports resolves only the active tenant's folder; cross-tenant file download blocked (401; error text "Session expired" is misleading — cosmetic defect) |
| F7 | NOT RUN | Re-generation overwrite prompt not exercised (single cycle per tenant) |
| G1 | PASS | fact/dim/sum tables: rows only under tenants 8 and 9; fact↔merchant cross-tenant join count = 0 |
| G2 | PASS | API/UI return only active-tenant rows; RLS + TenantContext enforced server-side |

### Defects & gaps found

1. **[Requirement gap — HIGH] BIN-based card/product type not wired** (C3, C4, D2): `tenant.card_type_source='BIN'` is stored but ignored; ingestion never extracts the 6-digit BIN or consults ref_bin/ref_bin_range. Card and product type come solely from the feed's Card Type column. The BIN tables, upload screens, and the tenant flag all exist — only the ingestion lookup is missing.
2. **[UI defect — MEDIUM] Multi-tenant login stalls**: with >1 allowed tenants, the login form sticks on "Signing in…" and the organisation picker never renders (auth state is saved; a manual reload lands on /dashboard under the default tenant). Single-tenant login was unaffected earlier in the session.
3. **[Cosmetic] Cross-tenant PDF download returns 401 "Session expired"** instead of a truthful 403/404.
4. **[Robustness] GET /api/transactions 500s** when called without the parameters the UI sends (works with the UI's query shape).
5. *(Pre-existing, by design)* BH/EG rows price as UNMAPPED_CHANNEL until real terminal-type strings are mapped in terminal_channel_map — expected per the 2026-08-10 design decision, listed here so nobody mistakes it for a regression.
