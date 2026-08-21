# Multi-Country Transaction Ingestion & Fee Engine Audit — Bahrain First Case
**Date:** 2026-08-08 · **Branch:** `deploy/kubernetes-aws` · **Scope:** ingestion → classification → interchange → scheme fee → rollups

## Method and honest limits

Everything below is a **static trace** of the shipped code and seed data:

- `acquira-batch/.../job/TransactionJobConfig.java` — the whole pipeline (reader, processor, staging→fact, fee UPDATE, rollups)
- `acquira-core/src/main/resources/application.properties` — `spring.sql.init.schema-locations` (this project has **no Flyway/Liquibase**; that property is the migration runner)
- `db/migration/V2026_07_05_01`, `V2026_07_07_01/04/05`, `V2026_07_15_01`, `V2026_07_31_02`, `V2026_07_31_03` (Bahrain), `V2026_07_31_06`
- `schema.sql` (`ref_card_scheme`, `ref_country`, `fact_transaction`, `stg_trnx_raw`, `tenant`)

I did **not** execute the pipeline or query a database. The local Postgres is empty, and no Bahrain tenant or Bahrain transaction file exists in this repo. So "Actual" in the test tables is **derived by deterministic trace of the rate-resolution SQL against the seeded rate rows** — the predicates are all deterministic (`ORDER BY … LIMIT 1`), so the trace is reliable, but it is a code-derived result, not an observed one. Every claim cites the file and line it comes from.

**Verdict up front: the Bahrain implementation is NOT correct and must not go live as-is.** The country-isolation *architecture* is sound; the *activation* of it is missing, the fee *model* is structurally incomplete, and there is no rate versioning. Details follow.

---

## 1. Current transaction-ingestion audit

Pipeline (`TransactionJobConfig`):

1. **Read** — Excel/CSV, 31 columns, fixed header names (`TransactionJobConfig.java:476`). Fee-relevant columns: `Transaction Type`, `Card Scheme`, `Card Type`, `Txn Currency`, `Txn Currency Amount`, `Store Base Currency`, `Store Base Currency Amount`, `MSF`, `VAT`, `Interchange Fee`, `Destination`.
2. **Process** (`:654-707`) — sets tenant; back-fills `card_scheme` from the card-type token when scheme is blank/`'NULL'`; preserves the granular product code into `card_product_code`; coarsens `card_type` to DEBIT/CREDIT/PREPAID via `ref_card_scheme`; resolves currency codes from ISO-numeric; divides amounts by the currency's `decimal_notation_value` (skipped for `inputType=AMS`).
3. **Write staging** — `stg_trnx_raw` (`:712-718`).
4. **staging→fact** (`:913-948`) — delete-then-insert for the dates in scope; forces refund sign.
5. **Fee computation** (`:1099-1241`) — one `UPDATE fact_transaction` setting `interchange_fee`, `scheme_fee`, `ecom_fee`.
6. **Rollups** (`:1340+`) — ~10 summary tables, all deriving `net_revenue = msf − interchange − scheme_fee − ecom_fee`.

**What the pipeline knows, per your 14-point checklist:**

| # | Required to price | Available? | Source |
|---|---|---|---|
| 1 | Transaction country | ❌ **No** | Never captured. Inferred as = tenant country. |
| 2 | Merchant / acquirer country | ❌ **No** | `dim_merchant`/`dim_store` carry no country. Acquirer country assumed = `tenant.home_country_code`. |
| 3 | Issuer / card country | ❌ **No** | `fact_transaction.issuer_country` exists (`Transaction.java:99`) but is **not in the staging table and not in the staging→fact INSERT column list** (`:913-916`) — permanently NULL. |
| 4 | Domestic vs international | ⚠️ **Feed-supplied only** | Raw `Destination` string, passed through untouched (`:941`). Never derived, never validated. |
| 5 | Card scheme | ✅ Yes | `card_scheme` / `card_product_code` → `ref_card_scheme` (`:1136-1144`). |
| 6 | Card product / type | ⚠️ Partial | Coarse DEBIT/CREDIT/PREPAID + `card_subtype` tier. No commercial/corporate/business category at all. |
| 7 | Transaction type | ⚠️ Partial | Only `RFND`/`REFUND` are distinguished (`:1153`). |
| 8 | MCC | ⚠️ Merchant-static | From `dim_store.mcc` (`:1163`), not per-transaction. NULL when store is unresolved. |
| 9 | Amount + currency | ✅ Yes | but see §7 (BHD precision). |
| 10 | Settlement currency | ✅ Yes | `store_base_currency`; fees computed off `store_base_currency_amount`. Correct basis. |
| 11 | Interchange rule | ⚠️ Resolvable, unrecorded | Resolved, but the winning row's `id` is never persisted. |
| 12 | Scheme-fee rule | ⚠️ Same | |
| 13 | Cross-border / additional fees | ❌ **No** | No cross-border assessment line exists. |
| 14 | Final expected cost | ⚠️ Computed but unexplainable | Three amounts written, zero provenance. |

**No BIN table exists anywhere in the repo.** `card_number` *is* ingested, so BIN-based issuer-country/scheme/product derivation is buildable — it simply has not been built. Card identification is 100% dependent on the feed's `Card Scheme` / `Card Type` text tokens.

---

## 2. Bahrain-specific transaction-flow audit

### 2.1 The blocking defect: nothing sets `tenant.home_country_code`

Country resolution is the pivot of the whole engine. All four rate lookups key on it:

```sql
WHERE i.country_code = COALESCE(tn.home_country_code,'AE')   -- :1179, :1185
WHERE s.country_code = COALESCE(tn.home_country_code,'AE')   -- :1222  (scheme fee)
WHERE m.country_code = COALESCE(tn.home_country_code,'AE')   -- :1161  (mcc sector)
WHERE e.country_code = COALESCE(tn.home_country_code,'AE')   -- :1234  (ecom flat fee)
```

The column is created **`NOT NULL DEFAULT 'AE'`** (`V2026_07_15_01__region_readiness_uae.sql:41`).

And then:

- `Tenant.java` has **no `homeCountryCode` field** — only free-text `country` (`Tenant.java:28`, commented `// e.g. USA`). JPA physically cannot read or write it.
- A repo-wide grep for `home_country_code|homeCountryCode` across all `.java`/`.jsx` returns **only the comments and the four SQL fragments in `TransactionJobConfig`**. No controller, no service, no DTO, no admin screen.
- `BankController.java:88` updates `tenant.setCountry(...)` — the free-text field, which the fee engine never reads.

**Therefore: onboarding a Bahrain tenant through the application yields `home_country_code = 'AE'`, and every Bahrain transaction is priced off the UAE rate card — in BHD.** The Bahrain rate card (2,387 lines of it) is loaded into the database and never consulted. This is exactly the cross-country contamination scenario you asked about, and it is the default outcome, not an edge case. Only a manual `UPDATE tenant SET home_country_code='BH'` avoids it, and nothing in the codebase or the migration set performs or verifies that.

### 2.2 What the Bahrain rate card actually contains

`V2026_07_31_03__rate_card_bahrain.sql` — country-level rows (`tenant_id IS NULL`, `country_code='BH'`, `cap_currency_code='BHD'`):

| Priority | Rows | Content |
|---|---|---|
| 65 | 2 | DOMESTIC JCB 1.75%, UnionPay 1.75% — **copied from UAE** (`:19`) |
| 60 | ~1,548 | DOMESTIC × {Visa,MasterCard} × {POS,ECOM} × 387 MCCs — the only genuinely Bahraini figures |
| 40 | ~774 | DOMESTIC any-scheme × {POS,ECOM} × MCC = max(Visa,MC) |
| 15 | 4 | DOMESTIC scheme default, MCC-wildcard (Visa POS 1.75%, Visa ECOM 1.90%, MC POS 1.75%, MC ECOM 1.90%) |
| 10 | 2 | DOMESTIC any-scheme default (POS 1.75%, ECOM 1.90%) |
| 1 | 1 | INTERNATIONAL flat **1.85%, scheme-agnostic — copied from UAE** (`:8`, `:14`) |

`card_type` and `tier` are **NULL (wildcard) on every Bahrain row**. Bahrain therefore prices debit, credit, prepaid, premium and standard **identically**. There is no Bahrain debit interchange, no premium differential, no cap.

Bahrain's scheme-fee grid is a **verbatim copy of the UAE grid** (`:2380-2385`):

| dest | channel | scheme | fee_pct |
|---|---|---|---|
| DOMESTIC | POS | Visa/MC/Amex/wildcard | 0.11% |
| DOMESTIC | ECOM | Visa/MC/Amex/wildcard | 0.14% |
| INTERNATIONAL | POS | Visa/MC/Amex/wildcard | 0.75% |
| INTERNATIONAL | ECOM | Visa/MC/Amex/wildcard | 0.90% |
| any | any | JCB / UnionPay | 0.05% |

(Derived: `V2026_07_07_01:403-437` seeds the matrix, `V2026_07_07_05:22-30` corrects DOMESTIC POS 0.12%→0.11% for Visa/MC/Amex/wildcard only.)

`ecom_flat_fee` has **no Bahrain row** — deliberately (`V2026_07_31_06:50-51`), so BH ECOM per-transaction fee = 0.

**BENEFIT — Bahrain's domestic debit switch — is absent entirely.** No `ref_card_scheme` row, no rate rows (`V2026_07_31_03:9-10`). In Bahrain, BENEFIT carries the bulk of domestic debit POS volume. Those transactions resolve `group_name → NULL` and land on the any-scheme fallback at **1.75%**, versus a real BENEFIT domestic switch fee that is a small flat/low-percentage charge. This is a large, systematic overstatement of cost on the single biggest slice of Bahrain domestic volume.

---

## 3. Domestic vs. international classification audit

**Finding: there is no classification logic. There is a passthrough.**

`destination` is read from the file (`:516`), stored to staging (`:718`), copied to fact verbatim (`:941`), and matched by exact string:

```sql
AND i.dest = UPPER(TRIM(COALESCE(ft.destination,'')))
```

Consequences:

- Issuer country is never compared to acquirer/merchant country. `issuer_country` is never even populated (§1).
- Any token other than `DOMESTIC`/`INTERNATIONAL` — `LOCAL`, `ON-US`, `INTL`, blank, NULL — matches **no rate row**, and falls into the fallback path (§9).
- **On-us is not modelled.** A transaction where the tenant is both acquirer and issuer is priced as ordinary domestic, booking phantom interchange the bank pays itself.
- **Regional (GCC / MEA intra-region) is not modelled.** Visa and Mastercard price intra-regional differently from inter-regional; the engine has one `INTERNATIONAL` bucket.
- A feed that mislabels `destination` is unauditable and uncorrectable — there is no independent signal to cross-check it against.

---

## 4. Interchange-fee calculation audit

```sql
CASE WHEN rf.is_refund THEN 0
     WHEN lr.interchange_pct IS NULL
     THEN 0.018500 * ABS(COALESCE(ft.store_base_currency_amount,0))
     ELSE LEAST(lr.interchange_pct * ABS(COALESCE(ft.store_base_currency_amount,0)),
                COALESCE(lr.cap_amount, 999999999999)) END          -- :1110-1114
```

Correct: settlement-amount basis, `ABS()` so refund signing doesn't flip the fee, deterministic single-row pick, cap via `LEAST`.

Defects:

1. **The 1.85% fallback is hardcoded in Java** (`:1112`) and is the UAE international rate. Any unmatched transaction in any country is charged UAE's cross-border interchange.
2. **Two code comments assert the opposite of what the code does.** `:1062-1064` ("Rows without a matching rate row … keep the feed interchange value untouched, so this can never break ingestion") and `V2026_07_05_01:76-77` ("the feed value survives untouched") are both **false** — the fallback overwrites. This stale comment is load-bearing for the "safe for unseeded countries" claim in three migration headers.
3. **No fixed-fee component.** `interchange_rate_local` has `interchange_pct` and `cap_amount` and nothing else. Real Visa/MC interchange is routinely `pct + fixed`. The migrations themselves flag the cells they had to drop: `6051` "AED 2", `8398/8661` "25 Cents", `5511/5521` "1.5% & $150+0.30%" (`V2026_07_07_04:29-33`) — and Bahrain has the same gap (`V2026_07_31_03:29-31`). Those cells silently receive a percentage rate instead.
4. **No minimum-fee floor.** `cap_amount` is a ceiling only.
5. **Thresholds are currency-blind and mis-named.** `min_ticket_aed` / `max_ticket_aed` (`V2026_07_05_01:118-119`) are compared directly to `store_base_currency_amount` with no conversion (`:1207-1208`). `cap_currency_code` exists (`V2026_07_15_01:26`) and is **never read by the engine**. Bahrain seeds no thresholds so this is latent for BH — but it detonates the moment a BH tenant falls through to the AE card (§2.1), where AED 36,700 / AED 137.625 get applied as if BHD.

---

## 5. Scheme-fee calculation audit

```sql
CASE WHEN rf.is_refund THEN 0
     ELSE (sfr.fee_pct * ABS(COALESCE(ft.store_base_currency_amount,0))) END   -- :1116-1117
```

- Resolution: `dest × channel`, preferring a scheme-specific row over the `scheme_group IS NULL` wildcard (`:1221-1227`). Deterministic.
- **No fallback.** If `dest` doesn't match, `sfr.fee_pct` is NULL, the product is NULL, and NULL is written to `scheme_fee` — then silently `COALESCE(...,0)` in every rollup (`:1349`, `:1365`, `:1393`, …). A missing rule becomes a **zero fee**, indistinguishable from a genuinely zero fee.
- **Scheme fee and cross-border assessment are conflated.** The `INTERNATIONAL` percentages (0.75%/0.90%) are one blended number. Visa ISA / Mastercard cross-border are separate charges from the base assessment, with different bases and different treatment for single- vs multi-currency. There is no way to report them separately, and no way to price them correctly.
- **Missing fee categories entirely:** authorisation/switch fees, clearing fees, fixed acquirer network fees, misuse-of-authorisation and integrity fees, chargeback fees, FX/DCC margin. `dcc` is ingested and used only for volume splits (`:1371`) — never for pricing.
- The ECOM flat fee is the one per-transaction fee that exists, and it is **not zeroed for refunds** (`:1122` has no `is_refund` branch, unlike interchange and scheme fee). Deliberate per `:1108`, but it makes refund fee treatment internally inconsistent.

---

## 6. BIN / card-classification audit

| Attribute | How determined | Assessment |
|---|---|---|
| Scheme group | `ref_card_scheme` matched on `code` **or** `name`, space- and case-insensitive (`:1136-1144`) | Works; see risk below |
| Tier | `card_subtype = 1 → Standard`, **everything else → Premium** (`:1203`) | Unknown ⇒ most expensive tier. Wrong-direction default. |
| Card type for pricing | `ft.card_type`, except `rcs.card_type = 3` (MCCP) remapped to CREDIT (`:1197`) | Business-confirmed |
| Issuer country | **Never determined** | No BIN table; column never populated |
| Commercial / corporate | **Not modelled** | `ref_card_scheme` has 17 rows, none commercial |

Two concrete risks:

1. **Row-multiplication risk in the scheme join.** `LEFT JOIN ref_card_scheme rcs ON <code match> OR <name match>` is unbounded. With today's 17-row seed no two rows collide (I checked each: no row's normalised `name` equals another row's normalised `code`), so exactly ≤1 row matches. But if anyone adds a row whose name normalises to another's code, the sub-select emits **two rows per `transaction_id`**, and `UPDATE … FROM` with a duplicated join key picks one **arbitrarily and silently** — Postgres does not raise. One future reference-data row can non-deterministically change fees. Fix: wrap as `LEFT JOIN LATERAL (… ORDER BY <specificity> LIMIT 1)`.
2. **Generic scheme tokens map to DEBIT.** `ref_card_scheme` seeds `VISA` and `MCRD` with `card_type = 0`, and the processor's mapping treats 0 as DEBIT (`V2026_06_25_02:8-10`). A feed sending a bare `VISA` product code is priced as debit. Immaterial for Bahrain (all BH rows are card-type wildcard) but material for UAE.

---

## 7. Currency and settlement audit

Correct: fees are computed off `store_base_currency_amount` (settlement, single-currency) and never off `txn_currency_amount` — stated at `:1061-1062` and `:1096-1097` and true in the SQL.

Defects:

1. **BHD is a 3-decimal currency; the amount columns are `DECIMAL(19,2)`.** `schema.sql:518-520` (`stg_trnx_raw`) and `:802-804` (`fact_transaction`) both declare `txn_currency_amount` / `store_base_currency_amount` as `DECIMAL(19,2)`. The processor compounds it by rounding to a **hardcoded scale of 2** after dividing by the currency's decimal factor:

   ```java
   item.setStoreBaseCurrencyAmount(
       item.getStoreBaseCurrencyAmount().divide(decimalDivisor(stlDecVal), 2, HALF_UP));   // :696-699
   ```

   The divisor is currency-aware (1000 for BHD); the **scale is not**. A settlement amount of 100.505 BHD becomes 100.51, and every fee is then computed off the rounded figure. This affects Bahrain, Oman (OMR, 3dp) and Kuwait (KWD, 3dp). Volume, MSF and all fees inherit the loss.
2. `cap_currency_code` is stored and never used (§4.5).
3. No FX rate table, no `txn_currency` vs `store_base_currency` consistency validation, no FX-margin fee.
4. Rollup columns are `DECIMAL(19,2)` (`total_interchange`, `total_scheme_fee` — `schema.sql:852-853`, `:903-904`) while fact columns are `DECIMAL(19,4)`. Aggregation rounds to 2dp, again lossy for 3-decimal currencies.

---

## 8. Rule-selection and priority audit

```sql
ORDER BY (ilr.tenant_id IS NOT NULL) DESC, ilr.priority DESC, ilr.id ASC LIMIT 1   -- :1212
```

**Deterministic and correct in shape.** Tenant override beats country default; higher priority beats lower; ties broken by id. The two-branch `UNION ALL` (MCC-specific / MCC-wildcard) is a performance split that preserves the candidate set (`:1166-1189`). Bahrain's priority ladder is internally consistent: 65 JCB/UPI > 60 scheme+MCC > 40 any-scheme+MCC > 15 scheme default > 10 any-scheme default > 1 international.

**But there is a shadow-row defect that breaks operator control of rates.**

`V2026_07_07_01` performs an **unguarded** delete-then-insert of the UAE rate rows and the entire scheme-fee matrix, keyed on `tenant_id = <ACQ tenant>`, on **every application startup** (`schema-locations` re-runs all scripts every boot — `application.properties:61-66`):

```sql
DELETE FROM scheme_fee_rate sfr USING tenant t
WHERE sfr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ';    -- :399-401
INSERT INTO scheme_fee_rate (tenant_id, dest, channel, scheme_group, fee_pct)
SELECT t.tenant_id, …                                               -- :403-405
```

`V2026_07_31_02` converted the AE rows to country defaults (`tenant_id → NULL`) under a one-time `schema_migration_log` guard (`:134-149`). So from the next boot onward, `V2026_07_07_01`'s `DELETE` matches nothing and its `INSERT` **creates a second, per-tenant copy** of the UAE card alongside the country default. `V2026_07_07_04` §2/§3 do the same for interchange rows. Because `(tenant_id IS NOT NULL) DESC` is the first sort key, **the freshly-reseeded shadow rows win every lookup.**

Net effect today: the shadow rows carry the same values, so **UAE fees are numerically unchanged** — and the shadow rows are `country_code='AE'` (column default), so **they cannot leak into Bahrain**. Country isolation holds. But:

- Any rate change an operator makes to an AE country-default row is **silently overridden on the next restart**.
- The row count grows by one full matrix per boot for `scheme_fee_rate` until `V2026_07_07_05` re-normalises values (it does, at `:22-30` — which is the only reason DOMESTIC POS is still 0.11% and not 0.12%; the TODO at `V2026_07_07_05:14-16` to fix the `V2026_07_07_01` literals was **never done**).
- The correctness of the UAE card now depends on an accidental ordering interaction between three migrations that all mutate the same rows on every boot.

**There is no admin UI or API for any rate table.** `interchange_rate_local`, `scheme_fee_rate`, `mcc_sector_map` and `ecom_flat_fee` appear in exactly **one** file in the entire codebase — `TransactionJobConfig.java`. Multiple migration headers claim "in-UI rate edits are never clobbered" and "rates are retunable in-table" (`V2026_07_05_01:55`, `V2026_07_31_03:35`); there is no UI, and per the above, in-table edits *are* clobbered. `PricingSimulator.jsx` is an MSF what-if tool that explicitly notes "dimensional summaries carry no interchange" (`:409`) — it is not a rate-card editor.

---

## 9. Missing-data and error-handling audit

The engine has **no exception path**. Every failure to resolve produces a number and continues.

| Missing / bad input | Behaviour | Signal to operator |
|---|---|---|
| `destination` blank/unknown | interchange = **1.85%** (UAE intl), scheme fee = **NULL → 0** | **none** |
| `home_country_code` unset | prices off **UAE** card | **none** |
| Country has no rate rows | interchange = **1.85%**, scheme fee = **0** | **none** |
| Scheme unrecognised | `group_name → ''`; wildcard rows only | **none** |
| Product code unknown | tier → **Premium** (most expensive) | **none** |
| `store_id` unresolved → `dim_store.mcc` NULL | MCC-specific rows unreachable; falls to MCC-wildcard default | **none** |
| `store_base_currency_amount` NULL | `COALESCE(…,0)` → fee **0** | **none** |
| Transaction type not RFND/REFUND | treated as a **purchase** | **none** |

There is no quarantine table, no `fee_resolution_status` column, no counter of fallback hits, no reconciliation assertion on fee totals. The only integrity check in the whole ingest is the staged-vs-inserted row-count assertion at `:952-969` — which is good, and is the model the fee stage needs and lacks.

---

## 10. Duplicate and data-integrity checks

- `fact_transaction` has **no unique constraint on any business key** — PK is the surrogate `(transaction_id, payment_date)`. Stated plainly in the code's own comment: "this delete is the ONLY thing preventing duplicates — the database will not catch them" (`:867-869`).
- `StagingTransaction.rowHash` exists as a field (`StagingTransaction.java:19`) but is **never computed and is not in the writer's INSERT column list** (`:713-718`). Dead field. There is no row-level idempotency key anywhere.
- APPEND mode deletes by `(tenant_id, DATE(payment_date), UPPER(TRIM(card_scheme)))` plus an explicit blank-scheme sweep (`:864-896`). The blank-scheme hole is genuinely fixed and well-commented.
- **Residual exposure:** the delete key is *date × scheme*, not *file* or *batch*. Two files covering the same date for the same scheme — a split or partial-day upload — mean the second load **deletes the first load's rows wholesale** and replaces them with only its own. That is silent data loss, not duplication. Correctness depends on an undocumented operational rule: one whole day per scheme per file.
- Refund handling is consistent between the two places that need to agree — `IN ('RFND','REFUND')` at `:934`/`:937`/`:939` (signing) and `:1153` (fee zeroing) — and the comment at `:1149-1152` records the earlier bug where they diverged. Good.

---

## 11. Historical pricing / versioning audit

**There is no versioning. This is a hard blocker for a regulated acquiring MIS.**

None of `interchange_rate_local`, `scheme_fee_rate`, `mcc_sector_map`, `ecom_flat_fee` has an `effective_from` / `effective_to` / `version` column. Fees are computed at ingest from **whatever the rate tables hold at that moment**. Nothing anchors a fee to the rate that was in force on the transaction date.

The migrations state the consequence outright: *"BACKFILL: fees compute at ingest — re-upload affected months to recompute with the new rate"* (`V2026_07_07_04:36`, `V2026_07_07_05:18-19`, `V2026_07_31_03:36`).

So:

- Re-ingesting January 2026 today reprices it at August 2026 rates and **silently rewrites reported history**.
- A mid-month scheme rate change cannot be represented at all.
- No fee figure is reproducible after any rate edit. There is no audit trail of what rate was applied to what transaction, because the winning rule id is never stored.

---

## 12. Multi-country architecture assessment

**The good news:** the resolution *shape* is right, and country isolation is structurally enforced. All four lookups filter `country_code = COALESCE(tn.home_country_code,'AE')`, and the country-default (`tenant_id IS NULL`) / per-tenant-override model is clean and correctly implemented. A BH tenant genuinely cannot see AE rows — including the shadow rows (§8). `V2026_07_31_02` is well-designed work.

**What still blocks "new country = configuration only":**

| Should be config | Actually | Where |
|---|---|---|
| Country → rate card | ✅ config | `country_code` on all four tables |
| Interchange / scheme-fee tables | ✅ config | |
| Card-product mappings | ⚠️ `ref_card_scheme` is a **global** 17-row table with no country dimension | `schema.sql:108-140` |
| MCC → sector | ✅ config, country-keyed | |
| ECOM flat fee | ✅ config, country-keyed | `V2026_07_31_06` |
| Effective dates | ❌ **absent** | §11 |
| **Domestic/international rule** | ❌ **hardcoded passthrough** of the feed string | `:941`, `:1181` |
| **No-match fallback rate** | ❌ **hardcoded `0.018500`** in Java | `:1112` |
| **ECOM channel definition** | ❌ **hardcoded whitelist** `('ECOM PROFILE','MPGS','PAY BY LINK','PAY ON')` in Java | `:1146-1147` |
| **Refund type tokens** | ❌ **hardcoded** `('RFND','REFUND')` in Java | `:934`, `:1153` |
| **Tier rule** | ❌ **hardcoded** `card_subtype = 1 → Standard, else Premium` in Java | `:1203` |
| **Credit-prepaid remap** | ❌ **hardcoded** `card_type = 3 → 'CREDIT'` in Java | `:1197` |
| **Amount decimal scale** | ❌ **hardcoded 2** | `:689`, `:698`, and `DECIMAL(19,2)` columns |
| **Country onboarding itself** | ❌ **no way to set `home_country_code`** | §2.1 |
| BIN / issuer reference data | ❌ does not exist | §6 |

The ECOM whitelist deserves emphasis: those are **UAE processor terminal-type strings**. A Bahrain acquiring platform will emit different `dim_terminal.type` values, so **every Bahrain e-commerce transaction will classify as POS**, taking POS interchange (1.75% vs 1.90% at MCC 5411) and POS scheme fee (0.11% vs 0.14%). Bahrain's entire POS/ECOM rate split — half of the 2,387-line card — is unreachable unless BH terminal types happen to match four hardcoded UAE strings.

---

## 13. Detailed test cases — traced end to end

Common setup: tenant `T_BH`, settlement currency BHD, `dim_store.mcc` as stated, settlement amount 100.000 BHD, purchase unless noted. "Actual" = deterministic trace of `:1099-1241` against the seeded rows.

### TC-1 — BH Visa Premium credit · BH merchant · POS · MCC 5411 · DOMESTIC

| Step | Value |
|---|---|
| Amount (settlement) | 100.000 BHD |
| Country resolution | `home_country_code='BH'` (assumed set) → BH card |
| Classification | `destination='DOMESTIC'` (feed) |
| Channel | `dim_terminal.type='POS TERMINAL'` → not in whitelist → **POS** |
| Scheme | `card_product_code='VICP'` → `ref_card_scheme` id 12 → group **Visa**, `card_type=1`, `card_subtype=2` → tier **Premium** |
| Card type | CREDIT |
| Interchange rule | priority **60**, `BH Visa POS MCC 5411`, 1.750000% (`V2026_07_31_03:488`) — matched because country=BH, dest=DOMESTIC, channel=POS, scheme=Visa, mcc=5411; card_type/tier/sector wildcards |
| Interchange | 100.000 × 0.017500 = **1.7500** |
| Scheme-fee rule | DOMESTIC/POS/Visa 0.110000% |
| Scheme fee | **0.1100** |
| ECOM flat | n/a (POS) → NULL |
| **Total cost** | **1.8600 BHD** |

Expected per the Bahrain workbook rate (1.75%) + configured scheme fee: 1.8600. **PASS** — arithmetic and rule selection correct. *Caveat: the scheme fee is a UAE figure (§2.2), so the total is right for the configuration and wrong for Bahrain.*

### TC-2 — Same, but ECOM (`dim_terminal.type='MPGS'`)

| Step | Value |
|---|---|
| Channel | **ECOM** (whitelist hit) |
| Interchange rule | priority 60, `BH Visa ECOM MCC 5411`, 1.900000% (`:1650`) |
| Interchange | **1.9000** |
| Scheme fee | DOMESTIC/ECOM/Visa 0.140000% → **0.1400** |
| ECOM flat | no BH row → `COALESCE(NULL,0)` → **0.0000** |
| **Total** | **2.0400 BHD** |

**WARNING** — rule selection correct, total knowingly understated by Bahrain's real per-ECOM-transaction fee, which is unconfigured (`V2026_07_31_06:50-51`). Also note this case is only reachable if BH terminal types happen to be UAE strings (§12).

### TC-3 — Foreign-issued Visa credit · BH merchant · POS · MCC 5411 · INTERNATIONAL

| Step | Value |
|---|---|
| Issuer country | **unknown** — `issuer_country` never populated |
| Classification | trusts feed `destination='INTERNATIONAL'`; no independent check |
| Interchange rule | priority **1**, `Intl flat 1.85 (constant per UAE)` (`:47`) — scheme-agnostic, card-type-agnostic |
| Interchange | **1.8500** |
| Scheme fee | INTERNATIONAL/POS/Visa 0.750000% → **0.7500** |
| Cross-border assessment | **not modelled** |
| **Total** | **2.6000 BHD** |

**FAIL.** Three defects: (a) 1.85% is an explicitly UAE-borrowed placeholder, not a Bahrain inter-regional rate, and it ignores scheme, product and card type, all of which Visa/MC differentiate; (b) no separate cross-border assessment line exists, so the true cost is understated and the components are unreportable; (c) classification is unverifiable because issuer country is never captured.

### TC-4 — BH-issued card used internationally

**FAIL — not representable.** The engine has no merchant-country dimension; acquirer country is *assumed* equal to `tenant.home_country_code` and never validated. A row whose merchant sits outside Bahrain is still priced on the Bahrain card. There is no field that could distinguish "foreign card at BH merchant" from "BH card at foreign merchant" — both are just `destination='INTERNATIONAL'`.

### TC-5 — BENEFIT domestic debit · BH merchant · POS · MCC 5411 · DOMESTIC

| Step | Value |
|---|---|
| Scheme | `'BENEFIT'` → **no `ref_card_scheme` row** → `rcs` NULL → `COALESCE(group_name,'')=''` |
| Card type | `cardSchemeToType` lookup misses → `ft.card_type` keeps the raw feed token |
| Interchange rule | only scheme-wildcard rows survive → priority **40**, `BH any-scheme POS MCC 5411 (higher of Visa/MC)`, 1.750000% (`:875`) |
| Interchange | **1.7500** |
| Scheme fee | DOMESTIC/POS wildcard 0.110000% → **0.1100** |
| **Total** | **1.8600 BHD** |

**FAIL.** BENEFIT domestic debit is charged the *higher of Visa/MC credit* interchange. Real BENEFIT domestic switch economics are a small flat or low-percentage fee. Direction of error: cost massively overstated, net revenue understated, on what is plausibly the largest single slice of Bahrain domestic POS volume. Compounded by Bahrain having no debit rate at all (all BH rows are `card_type` wildcard).

### TC-6 — The realistic onboarding state: `home_country_code` left at `'AE'`

Same transaction as TC-1.

| Step | Value |
|---|---|
| Country resolution | `COALESCE('AE','AE')` → **AE card** (BH card never consulted) |
| Interchange rule | AE priority **50**, `MCC 5411 Visa flat 1.05` (`V2026_07_07_04:130`) — mcc-keyed, CREDIT, channel-wildcard. Beats priority 20 (MCC 5411 not in `mcc_sector_map`) and priority 10 (Visa CREDIT Premium 1.80%). Shadow ACQ rows excluded because `T_BH.tenant_id ≠ ACQ.tenant_id` |
| Interchange | 100.000 × 0.010500 = **1.0500** |
| Scheme fee | AE DOMESTIC/POS/Visa 0.110000% → **0.1100** |
| **Total** | **1.1600 BHD** |
| **Correct (TC-1)** | **1.8600 BHD** |

**FAIL — highest severity.** 37.6% understatement of cost, i.e. a 0.70 BHD per-100-BHD overstatement of profit, applied to *every Bahrain transaction*. And this is the **default** state, because no code path sets `home_country_code` (§2.1). AED-denominated caps and AED ticket thresholds are simultaneously applied to BHD amounts.

### TC-7 — Refund · BH Visa POS MCC 5411 · −100.000 BHD

| Step | Value |
|---|---|
| Signing (`:934-940`) | `txn_currency_amount` −100.000, `store_base_currency_amount` −100.000, `msf` −ABS(msf), `vat` +ABS(vat) |
| `is_refund` | `'RFND' IN ('RFND','REFUND')` → true |
| Interchange | **0** (`:1110`) |
| Scheme fee | **0** (`:1116`) |
| ECOM flat | POS → NULL |
| Net revenue | −msf − 0 − 0 − 0 = negative → nets out in rollups |

**WARNING — accepted policy divergence.** Correct for the stated goal (reconcile to the raw feed / finance pivot, verified against 10.18M July rows per `:927-931` — good work). But economically wrong: a refund *returns* interchange to the issuer, it does not vanish. True acquirer net is misstated whenever refund mix moves, and the error is invisible because zero is indistinguishable from "no rule matched" (§9). Note also the internal inconsistency: ECOM flat fee is *not* zeroed on refunds (`:1122`).

### TC-8 — Reversal / chargeback / auth-only

**FAIL.** Only `RFND`/`REFUND` are recognised. `REVERSAL`, `VOID`, `CHGBK`, `PREAUTH` or any other credit-side type is treated as a **purchase**: `+ABS` volume, `+ABS` MSF, full interchange and full scheme fee charged. A reversal is therefore double-counted as new volume *and* charged fees. No transaction-type taxonomy or whitelist exists.

### TC-9 — Preferential MCC with unresolved store (`dim_store.mcc` NULL) · Visa POS DOMESTIC, true MCC 8211 (education)

| Step | Value |
|---|---|
| MCC branch 1 | `i.mcc = ds.mcc` → `NULL = NULL` is never true → no MCC-specific candidates |
| MCC branch 2 | `i.mcc IS NULL` → priority **15**, `BH Visa POS domestic default`, 1.750000% (`:434`) |
| Interchange | **1.7500** |
| Correct (MCC 8211) | priority 60, `BH Visa POS MCC 8211`, 0.850000% (`:477`) → **0.8500** |

**FAIL** — 106% overstatement, silent. Any store the dimension join fails to resolve loses its entire preferential MCC treatment with no log line and no counter.

### TC-10 — `destination` blank or unrecognised (`'LOCAL'`, `'ON-US'`, NULL)

| Step | Value |
|---|---|
| `i.dest = UPPER(TRIM(COALESCE(ft.destination,'')))` | matches **no** row |
| Interchange | `lr.interchange_pct IS NULL` → **0.018500 × 100.000 = 1.8500** (hardcoded UAE intl rate, `:1112`) |
| Scheme fee | `sfr.fee_pct` NULL → `NULL × amount` = **NULL** → written NULL → `COALESCE(…,0)` in every rollup → **0** |
| **Total** | **1.8500 BHD** |

**FAIL.** The "pricing rule not found" path silently produces UAE's cross-border interchange and a zero scheme fee, with no error, no quarantine, no counter. This is also where the stale comments at `:1062-1064` and `V2026_07_05_01:76-77` — which claim the feed value is preserved — actively mislead anyone reading the code.

### TC-11 — On-us (tenant is both acquirer and issuer)

**FAIL — not representable.** No on-us concept in the data model or the `dest` vocabulary. Priced as ordinary domestic, booking interchange the bank notionally pays itself. Overstates cost, understates profit.

### TC-12 — Duplicate / re-upload

| Scenario | Result |
|---|---|
| Same file re-uploaded (APPEND) | Delete by (date × scheme) + blank-scheme sweep covers the insert set → **no duplication**. **PASS** |
| Two files, same date, same scheme (split day) | Second load deletes the first's rows wholesale → **silent data loss**. **FAIL** |
| Row-level dedupe | `rowHash` never computed; no unique business key on `fact_transaction`. **FAIL** |

### TC-13 — Historical transaction re-ingested after a rate change

**FAIL.** No `effective_from`/`effective_to` on any rate table; fees computed from current rates at ingest. Re-ingesting an old month reprices it at today's rates and silently rewrites reported history. The winning rule id is never stored, so the original figure cannot be reconstructed or explained.

### TC-14 — Multiple matching rules

**PASS on determinism** — `ORDER BY (tenant_id IS NOT NULL) DESC, priority DESC, id ASC LIMIT 1` is fully deterministic, and Bahrain's priority ladder is internally consistent.
**WARNING on the scheme join** — the unbounded `code OR name` join can multiply rows if reference data ever collides, and `UPDATE … FROM` would then pick arbitrarily and silently (§6.1).

### TC-15 — 3-decimal currency precision (BHD)

Settlement 100.505 BHD → processor divides by 1000 then rounds to **hardcoded scale 2** (`:698`) → **100.51**; column is `DECIMAL(19,2)` regardless. Interchange then computes off 100.51, not 100.505.
**FAIL** — systematic precision loss on BHD/OMR/KWD in volume, MSF and every fee.

### Summary

| # | Case | Result |
|---|---|---|
| 1 | BH Visa credit, POS, MCC 5411, domestic | **PASS** (config-correct) |
| 2 | Same, ECOM | **WARNING** (ECOM flat fee unconfigured) |
| 3 | Foreign card at BH merchant, international | **FAIL** |
| 4 | BH card used internationally | **FAIL** (not representable) |
| 5 | BENEFIT domestic debit | **FAIL** |
| 6 | `home_country_code` unset (default state) | **FAIL — blocker** |
| 7 | Refund | **WARNING** (accepted divergence) |
| 8 | Reversal / chargeback | **FAIL** |
| 9 | Unresolved store → MCC lost | **FAIL** |
| 10 | Missing / unknown `destination` | **FAIL** |
| 11 | On-us | **FAIL** |
| 12 | Duplicate / split-day upload | **PASS** / **FAIL** |
| 13 | Historical repricing | **FAIL** |
| 14 | Multiple matching rules | **PASS** / **WARNING** |
| 15 | BHD 3-decimal precision | **FAIL** |

**2 PASS · 3 WARNING · 11 FAIL.**

---

## 14. Bugs and calculation risks, ranked

| # | Severity | Finding | Evidence |
|---|---|---|---|
| B1 | **Blocker** | Nothing in the application sets `tenant.home_country_code`; it defaults `'AE'`. Bahrain tenants price off the UAE card in BHD. | `V2026_07_15_01:41`; `Tenant.java` (no field); repo-wide grep |
| B2 | **Blocker** | No rate versioning. Fees computed at ingest from current rates; history silently rewritten on re-ingest; no rule id stored. | all four rate tables; `V2026_07_07_04:36` |
| B3 | **Critical** | Domestic/international is an unvalidated feed passthrough. Issuer country never populated. No on-us, no regional. | `:941`, `:1181`; `:913-916` |
| B4 | **Critical** | Unmatched rule → hardcoded 1.85% UAE interchange + NULL→0 scheme fee, silently. Code comments claim the opposite. | `:1112`, `:1062-1064`, `V2026_07_05_01:76-77` |
| B5 | **Critical** | BENEFIT absent → Bahrain domestic debit charged 1.75% any-scheme credit rate. | `V2026_07_31_03:9-10`, `:875` |
| B6 | **Critical** | BHD/OMR/KWD 3-decimal precision lost: `DECIMAL(19,2)` columns + hardcoded rounding scale 2. | `schema.sql:518-520`, `:802-804`; `:689`, `:698` |
| B7 | **High** | ECOM channel = hardcoded UAE terminal-type whitelist → all BH e-commerce classifies as POS, making half the BH card unreachable. | `:1146-1147` |
| B8 | **High** | Shadow-row reseed: `V2026_07_07_01`/`_04` re-insert per-tenant AE copies every boot; they win the `ORDER BY`; operator rate edits silently reverted. | `V2026_07_07_01:399-405`, `V2026_07_07_04:55-73`, `:1212` |
| B9 | **High** | No fixed-fee, no minimum-fee, no separate cross-border assessment, no auth/clearing/chargeback/FX fees. Known-skipped cells fall through to percentages. | `V2026_07_07_04:29-33`, `V2026_07_31_03:29-31` |
| B10 | **High** | Bahrain scheme-fee grid is a verbatim UAE copy; intl interchange is a UAE constant. | `V2026_07_31_03:8-11`, `:2380-2385` |
| B11 | **High** | Reversals/chargebacks priced as purchases; only RFND/REFUND recognised. | `:934`, `:1153` |
| B12 | **High** | No fee-resolution observability at all: no status column, no fallback counter, no quarantine. | §9 |
| B13 | **Medium** | Split-day re-upload silently deletes the earlier load. `rowHash` dead; no unique business key. | `:864-896`, `:867-869` |
| B14 | **Medium** | Currency-blind thresholds (`min/max_ticket_aed`); `cap_currency_code` never read. | `:1207-1208`, `V2026_07_15_01:26` |
| B15 | **Medium** | Bahrain has no card-type or tier differentiation (all wildcard) — debit, credit, prepaid, premium priced identically. | `V2026_07_31_03:17` |
| B16 | **Medium** | Unbounded `ref_card_scheme` join (`code OR name`) can multiply rows; `UPDATE … FROM` then picks arbitrarily. Safe with today's seed only. | `:1136-1144` |
| B17 | **Medium** | Unknown product → **Premium** (most expensive tier). Wrong-direction default. | `:1203` |
| B18 | **Medium** | No admin UI/API for any rate table, contradicting three migration headers. | grep: tables appear only in `TransactionJobConfig` |
| B19 | **Low** | Rollups `DECIMAL(19,2)` vs fact `DECIMAL(19,4)` — aggregation rounding. | `schema.sql:852-853` |
| B20 | **Low** | Unresolved store silently forfeits preferential MCC rates. | `:1163`, `:1182` |
| B21 | **Low** | ECOM flat fee not zeroed on refunds while interchange/scheme are. | `:1122` |
| B22 | **Low** | `V2026_07_07_05`'s own TODO to fix `V2026_07_07_01`'s 0.0012 literals was never done; correctness relies on migration ordering. | `V2026_07_07_05:14-16` |

---

## 15. Required backend changes

1. **Extract the fee engine out of the ingest `UPDATE`** into a testable component (`FeeResolutionService`) with a typed `FeeQuote` result carrying the rule id, matched predicates, rate, cap, effective version and computed amount. The current 140-line string-concatenated SQL is untestable by construction — there is not one unit test for fee resolution.
2. **Fail loudly instead of falling back.** Delete the hardcoded `0.018500`. On no match: write NULL fees, set `fee_resolution_status = 'NO_RULE'`, increment a counter, and fail the step if the rate exceeds a threshold. Fix the two comments that assert the opposite (`:1062-1064`, `V2026_07_05_01:76-77`).
3. **Derive domestic/international** from issuer country vs. acquirer country, with the feed's `destination` retained as a cross-check and a mismatch counter. Requires B3's issuer-country source.
4. **Add BIN reference data** (`ref_bin`: BIN range → issuer country, scheme, product, card type, commercial flag) and populate `issuer_country`, `issuer_bank` at ingest from the already-ingested `card_number`.
5. **Make currency precision currency-driven** — widen amount columns to `DECIMAL(19,4)`+ and take the rounding scale from `ref_country.decimal_notation_value` instead of the literal `2`.
6. **Move hardcoded rules to config**: ECOM channel classification (`terminal_channel_map` keyed by country + terminal type), refund/reversal transaction-type taxonomy, tier rule, credit-prepaid remap.
7. **Persist fee provenance** — `interchange_rule_id`, `scheme_fee_rule_id`, `rate_version` on `fact_transaction`, so every fee is explainable and reproducible.
8. **Add a transaction-type taxonomy** (purchase / refund / reversal / chargeback / representment / auth-only) with explicit sign and fee treatment per type, and reject unknown types.
9. **Bound the `ref_card_scheme` join** — `LEFT JOIN LATERAL (… ORDER BY <specificity> LIMIT 1)`.
10. **Make the reseed migrations idempotent** against the country-default model (guard them the way `V2026_07_31_02` guards its conversion), or retire them into a single authoritative AE seed.
11. **Extend the existing row-count reconciliation pattern** (`:952-969`) to fees: assert every fact row in scope has a non-NULL, rule-attributed interchange and scheme fee.

## 16. Required configuration / data-model changes

- `effective_from` / `effective_to` / `version` on `interchange_rate_local`, `scheme_fee_rate`, `mcc_sector_map`, `ecom_flat_fee`, with resolution by transaction date and an exclusion constraint preventing overlapping windows for the same key.
- `fixed_fee`, `min_fee`, `fee_basis`, `fee_currency` on the rate tables; rename `min_ticket_aed`/`max_ticket_aed` → `min_ticket_amount`/`max_ticket_amount` + `ticket_currency_code`, and actually read `cap_currency_code`.
- A separate `cross_border_assessment` table so cross-border is not conflated with the base scheme fee.
- `country_code` on `ref_card_scheme` (or a `country_scheme_map`) so BENEFIT and other domestic schemes can exist per country without polluting the global table. **Seed BENEFIT for BH.**
- Country dimension on `dim_merchant` / `dim_store` so merchant country is a real field, and `acquirer_country` on `tenant` distinct from free-text `country`.
- `dest` vocabulary extended: `ON_US`, `DOMESTIC`, `REGIONAL`, `INTERNATIONAL`.
- Bahrain-specific data to obtain and seed: real BH scheme-fee grid, real BH cross-border interchange by scheme/product, BENEFIT rates, BH ECOM per-transaction fee, BH card-type/tier differentiation, BH terminal-type → channel mapping, and the flat-fee cells the migration skipped.
- `fee_resolution_status` + rule-id columns on `fact_transaction` (§15.7).
- A unique business key on `fact_transaction` (e.g. `tenant_id, arn, rrn_number, payment_date, transaction_type`) or a real `row_hash`, and populate it.

## 17. Required admin / dashboard changes

- **Tenant country setting** — expose `home_country_code` as a validated FK-backed dropdown in tenant admin, required at creation, blocked from silently defaulting. Nothing works until this exists.
- **Rate-card management UI** for all four tables: browse by country, effective-dated edit with maker-checker, version history, and a diff view. Today there is no UI at all despite migration headers claiming otherwise.
- **Fee explainability drill-down** — for any transaction, show: amount → classification → country → dest → scheme → product/tier → rule selected (id + label + why it matched) → rate/fixed/cap/min → effective version → computed amount, for each of interchange, scheme fee and ECOM fee. This is the "should ideally be able to explain" list from your brief; none of it is currently possible because provenance is not stored.
- **Ingestion exception dashboard** — counts and drill-through for: no rule matched, `destination` unrecognised, scheme unresolved, product unresolved, store/MCC unresolved, currency mismatch, unknown transaction type.
- **Rate-card coverage report** per country — which (dest × channel × scheme × card type × MCC) combinations have no explicit row and are relying on a wildcard, so gaps like BENEFIT and the BH ECOM flat fee are visible before go-live rather than after.

## 18. Monitoring and reconciliation requirements

- Per-run fee-resolution metrics: % rows matched by priority band, % on wildcard fallback, % `NO_RULE`. Alert on any `NO_RULE` and on fallback share exceeding a threshold.
- Daily reconciliation of computed interchange against the scheme settlement/clearing files (Visa VSS, Mastercard GCMS) per country × scheme × dest, with a variance tolerance and alerting. This is the only external check that can prove the rate cards are right; nothing like it exists today.
- Reconciliation of computed scheme fees against network invoices, monthly.
- Retain the existing staged-vs-inserted row-count assertion, and add: fact-vs-feed volume/MSF signed-sum checks per day (the July 10.18M-row verification at `:927-931` is the right pattern — make it a standing job, not a one-off).
- Alert on `tenant.home_country_code = 'AE'` for any tenant whose `base_currency ≠ 'AED'` — a cheap, high-value guard against B1 recurring.
- Track rate-card change events (who, what, when, effective from) as an audit log.

## 19. Recommended target architecture

```
File / API feed
   ↓
Staging (stg_trnx_raw)  + row_hash idempotency key
   ↓
Enrichment
   ├─ BIN lookup      → issuer country, scheme, product, card type, commercial flag
   ├─ Merchant/store  → merchant country, MCC, acquirer country
   └─ Terminal        → channel (country-configured map)
   ↓
Classification  (issuer country × acquirer country × merchant country)
   → ON_US | DOMESTIC | REGIONAL | INTERNATIONAL
   → cross-checked against feed `destination`, mismatches counted
   ↓
FeeResolutionService  — one call per fee category, per transaction
   ├─ Interchange       (country, effective-dated, pct + fixed + cap + floor)
   ├─ Scheme fee        (country, effective-dated)
   ├─ Cross-border assessment  (separate line)
   ├─ Per-transaction fees     (auth, clearing, ECOM flat)
   └─ FX / DCC margin
   → returns FeeQuote{ruleId, version, basis, rate, fixed, cap, amount, whyMatched}
   ↓
fact_transaction  + fee amounts + rule ids + versions + resolution status
   ↓
Rollups (unchanged shape)      Exception queue (NO_RULE / mismatch)
   ↓                                    ↓
Reporting + fee drill-down      Ops dashboard + alerts
   ↓
Scheme-invoice reconciliation (VSS / GCMS)
```

Key principles: fees resolved by transaction date against versioned rules; every fee carries its provenance; no silent fallback; all country-specific behaviour is data, including classification rules and channel mapping.

## 20. Go-live checklist

**Blockers — Bahrain cannot go live until all are done**

- [ ] `home_country_code` settable and validated in tenant admin; set to `'BH'` for every Bahrain tenant and verified in the database (B1)
- [ ] Alert in place for `home_country_code='AE'` with non-AED base currency (B1)
- [ ] Effective-dating on all four rate tables; fees resolved by transaction date (B2)
- [ ] Rule id + version persisted per transaction; drill-down proves each fee (B2, §17)
- [ ] Hardcoded 1.85% fallback removed; `NO_RULE` surfaced and alerted; misleading comments corrected (B4)
- [ ] BENEFIT seeded in `ref_card_scheme` + BH rate rows, with real BENEFIT economics (B5)
- [ ] BHD 3-decimal precision fixed end to end: columns widened, rounding scale currency-driven (B6)
- [ ] BH terminal-type → channel mapping obtained and made configuration; verified against a real BH file (B7)
- [ ] Real Bahrain scheme-fee grid and real BH cross-border interchange replace the UAE copies (B10)
- [ ] Cross-border assessment modelled as a separate fee line (B9)
- [ ] Reversal/chargeback transaction types handled; unknown types rejected (B11)
- [ ] Fee-resolution status/counters and exception dashboard live (B12)

**Required before scale-up**

- [ ] Shadow-row reseed defect fixed; operator rate edits provably survive restart (B8)
- [ ] Fixed-fee, minimum-fee and the migration-skipped flat-fee cells implemented (B9)
- [ ] BH card-type/tier differentiation seeded (B15)
- [ ] Unique business key or `row_hash` on `fact_transaction`; split-day upload safe (B13)
- [ ] `ref_card_scheme` join bounded (B16); unknown product no longer defaults to Premium (B17)
- [ ] Rate-card admin UI with maker-checker and version history (B18)
- [ ] Rate-card coverage report shows zero unintended wildcard reliance for BH
- [ ] Scheme-invoice reconciliation running for at least one full cycle with variance within tolerance
- [ ] Unit + integration tests for fee resolution covering all 15 cases in §13
- [ ] Parallel run: BH volumes priced by the engine vs. the business-case workbook, reconciled to the fils

**Final gate:** do not declare Bahrain correct until ingestion, domestic/international classification, interchange, scheme fee, currency handling and rule selection have each been independently validated against Bahrain source data and a real scheme settlement file. On the evidence in §13 — 11 of 15 traced cases failing — that gate is currently far from met.
