# New Bahrain Tenant — Onboarding Audit & Implementation Plan
**Date:** 2026-08-08 · **Scenario:** a brand-new tenant is created for Bahrain and starts uploading transaction files.

## The intended rule (business requirement, confirmed 2026-08-08)

1. **Tenant drives country context.** If the tenant is Bahrain, all pricing/classification happens in the BH context.
2. **Destination from the file:** rows marked **local** ⇒ DOMESTIC (Bahrain local card at Bahrain merchant); **anything else** ⇒ INTERNATIONAL card.
3. **BHD is a 3-decimal currency** — must be correctly seeded and enforced end-to-end.

## What actually happens today — step-by-step trace of a new BH tenant

### Step 1 — Create the tenant (Admin → Banks)

`BankController.createBank` → `tenantRepository.save(tenant)` (`BankController.java:66`).
`Tenant.java` has **no `homeCountryCode` field**, so JPA writes only the columns it knows; the DB fills `home_country_code` with its default:

```sql
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS home_country_code VARCHAR(2) NOT NULL DEFAULT 'AE';
-- V2026_07_15_01__region_readiness_uae.sql:41
```

**Result: the new Bahrain tenant is born as `home_country_code = 'AE'`.** ❌
Every fee lookup keys on `COALESCE(tn.home_country_code,'AE')` (`TransactionJobConfig.java:1161,1179,1185,1222,1234`), so the tenant prices off the **UAE card** from day one. The 2,387-row BH rate card sits unused. The `PUT /banks/{id}` update path (`BankController.java:82-93`) also cannot fix it — same missing field. Only a manual SQL `UPDATE` can, and nothing prompts for or verifies that.

**Requirement 1: FAIL — the single most important switch has no on-switch.**

### Step 2 — Upload the first transaction file

The reader takes the `Destination` column verbatim (`:516`), staging→fact copies it untouched (`:941`), and the fee engine matches it by **exact string**:

```sql
AND i.dest = UPPER(TRIM(COALESCE(ft.destination,'')))   -- :1181
```

Rate rows only exist for the tokens `DOMESTIC` and `INTERNATIONAL`. There is **no mapping layer**: I grepped the whole pipeline — no occurrence of `LOCAL` normalization anywhere; the only `DOMESTIC` literals are rollup CASE expressions (`:1456-1463`).

So under your rule "file contains *local*":

| File says | Engine sees | Rate rows matched | Fee outcome |
|---|---|---|---|
| `DOMESTIC` | `DOMESTIC` | BH domestic ladder (once home country is BH) | correct |
| `LOCAL` / `Local` | `LOCAL` | **none** | interchange = hardcoded **1.85%** fallback (`:1112`), scheme fee = NULL→**0** |
| `INTERNATIONAL` | `INTERNATIONAL` | intl rows | 1.85% + intl scheme fee |
| `INTL`, blank, anything else | as-is / `''` | **none** | 1.85% + 0, silently |

**Requirement 2: FAIL.** "Local ⇒ domestic, else international" is not implemented anywhere. Today the rule is "exactly `DOMESTIC` ⇒ domestic, exactly `INTERNATIONAL` ⇒ international, everything else ⇒ silent fallback that *looks* international-priced but has zero scheme fee." If the Bahrain feed writes `LOCAL`, **100% of domestic volume mis-prices with no error**.

Note the "else ⇒ international" leg of your rule is also only half-true today: an unrecognized token gets international-*ish* interchange (1.85% by accident of the fallback constant) but **zero** scheme fee — so even the fallback doesn't equal "treat as international."

### Step 3 — BHD decimal handling

Seeds — these are **present and correct**:

- `ref_country` BH row: `('BH','BAHRAIN','BHD','Dinar','BHD','973','048',1000)` — `schema.sql:9548` ✅
- `V_add_ref_card_scheme.sql:47`: `iso_numeric='048', decimal_notation_value=1000 WHERE currency_code='BHD'` ✅ (also KWD/OMR = 1000)
- The processor loads the map at startup (`:573`) and picks divisor 1000 for `048`/`BHD` (`:684,693`) ✅

Enforcement — **broken in three places**:

1. **Hardcoded scale 2 in the division** (`:689`, `:698`):
   ```java
   .divide(decimalDivisor(stlDecVal), 2, RoundingMode.HALF_UP)   // 100.505 BHD → 100.51
   ```
   The divisor is currency-aware; the rounding scale is not. The third decimal (fils) is destroyed at ingest.
2. **Column types**: `stg_trnx_raw` / `fact_transaction` amount columns are `DECIMAL(19,2)` (`schema.sql:518-520`, `:802-804`) — even a fixed scale can't be stored.
3. **Rollups**: `total_volume`/`total_msf`/`total_interchange`/`total_scheme_fee` are `DECIMAL(19,2)` (`schema.sql:852-853` etc.) — aggregation re-rounds.

**Requirement 3: FAIL — seed data is right, but the pipeline rounds BHD to 2 decimals at the first touch and every table downstream enforces it.** On 10M rows/month, ±0.005 BHD/row is up to ~±50,000 BHD/month of untracked drift in volume alone, plus fee errors compounding off the rounded base.

### Step 4 — What the BH card would price once reachable (from the previous audit, unchanged)

Even after fixing steps 1–3, these carry over from the full audit ([MULTI_COUNTRY_FEE_ENGINE_AUDIT_2026-08-08.md](MULTI_COUNTRY_FEE_ENGINE_AUDIT_2026-08-08.md)):

| Gap | Effect on a BH tenant |
|---|---|
| **BENEFIT scheme absent** (`V2026_07_31_03:9-10`) | BH domestic debit — the largest domestic slice — falls to the any-scheme fallback at 1.75% credit-level interchange |
| **ECOM whitelist is UAE terminal strings** (`:1146-1147`) | BH e-commerce classifies as POS; the ECOM half of the BH card (1.90% rows, 0.14% scheme fee) is unreachable |
| **Scheme-fee grid is a UAE copy** (`V2026_07_31_03:2380-2385`) | 0.11/0.14/0.75/0.90 are UAE figures |
| **Intl interchange = flat 1.85% UAE constant** | not a Bahrain cross-border rate; ignores scheme/product |
| **No BH ECOM flat fee row** (`V2026_07_31_06:50-51`) | BH ECOM per-txn fee = 0 until seeded |
| **No effective-dating** on any rate table | re-ingest reprices history at today's rates |
| **No rule-id provenance** on `fact_transaction` | no fee is explainable |
| Reversals/chargebacks priced as purchases (`:934`, `:1153`) | credit-side types inflate volume and fees |

### Audit verdict for the new-tenant scenario

| # | Requirement | Status |
|---|---|---|
| 1 | Tenant = Bahrain ⇒ BH pricing context | **FAIL** — no way to set `home_country_code`; defaults to AE |
| 2 | File "local" ⇒ domestic, else international | **FAIL** — exact-string match on `DOMESTIC`/`INTERNATIONAL` only; unknown tokens silently mis-price |
| 3 | BHD 3-decimal seeded and enforced | **FAIL** — seeded ✅, enforced ❌ (hardcoded scale 2 + `DECIMAL(19,2)` columns) |

---

# The Plan

Ordered so each phase is independently shippable; Phase 1–3 are the minimum for the new tenant to price correctly at all.

## Phase 1 — Make tenant country real (blocker, ~1 day)

**1.1** Add the field to the entity — `acquira-common/.../model/Tenant.java`:
```java
@Column(name = "home_country_code", nullable = false)
private String homeCountryCode = "AE";   // FK → ref_country
```
**1.2** `BankController`: accept/require `homeCountryCode` on create and update; validate it exists in `ref_country`. On create, when absent, **reject** rather than default silently (a 2-country platform must not guess).
**1.3** Frontend bank-admin form: country dropdown sourced from `ref_country`; also auto-fill `base_currency`/symbol from the selected country row.
**1.4** Backfill + guard migration:
```sql
-- one-time: any existing tenant whose free-text country says Bahrain
UPDATE tenant SET home_country_code='BH'
WHERE country ILIKE '%bahrain%' OR base_currency='BHD';
```
**1.5** Startup sanity check (log ERROR): any tenant where `home_country_code='AE'` but `base_currency<>'AED'` — the cheap alarm that catches this whole class of bug forever.

**Acceptance:** create a BH tenant through the UI → `SELECT home_country_code FROM tenant` returns `BH`; fee LATERALs hit `country_code='BH'` rows (verify with one ingested test row).

## Phase 2 — Destination normalization: "local ⇒ DOMESTIC, else INTERNATIONAL" (~1–2 days)

Implement your rule as an explicit, configurable normalization at **staging→fact** (one place, so fact + fee engine + every rollup all see canonical values):

**2.1** New config table (idempotent migration, follows the existing country-default/tenant-override model):
```sql
CREATE TABLE IF NOT EXISTS destination_token_map (
    id BIGSERIAL PRIMARY KEY,
    tenant_id INT,                    -- NULL = country default
    country_code VARCHAR(2) NOT NULL REFERENCES ref_country(country_code),
    raw_token VARCHAR(30) NOT NULL,   -- UPPER(TRIM()) of feed value
    dest VARCHAR(20) NOT NULL         -- DOMESTIC / INTERNATIONAL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_dest_token_map
    ON destination_token_map (country_code, COALESCE(tenant_id,0), raw_token);

INSERT INTO destination_token_map (tenant_id, country_code, raw_token, dest) VALUES
 (NULL,'BH','LOCAL','DOMESTIC'), (NULL,'BH','DOMESTIC','DOMESTIC'),
 (NULL,'BH','INTERNATIONAL','INTERNATIONAL'), (NULL,'BH','INTL','INTERNATIONAL'),
 (NULL,'AE','LOCAL','DOMESTIC'), (NULL,'AE','DOMESTIC','DOMESTIC'),
 (NULL,'AE','INTERNATIONAL','INTERNATIONAL')
ON CONFLICT DO NOTHING;
```
**2.2** In the staging→fact INSERT (`TransactionJobConfig.java:913-948`), replace `stg.destination` with the mapped value, applying your default rule for unmapped-but-present tokens:
```sql
COALESCE(dtm.dest,
         CASE WHEN NULLIF(TRIM(stg.destination),'') IS NULL THEN NULL
              ELSE 'INTERNATIONAL' END)   -- "anything else = international card"
```
(join `destination_token_map` on the tenant's `home_country_code` + `UPPER(TRIM(stg.destination))`, tenant override preferred — same LATERAL pattern as the rate lookups).
**2.3** Keep the **raw** feed token in a new `destination_raw` column for audit; count and log unmapped tokens per run (`log.warn` + a per-run metric). NULL destination rows get `fee_resolution_status='NO_DEST'` (Phase 5) instead of silently taking the fallback.
**2.4** Remove the hardcoded `0.018500` fallback (`:1112`) — with normalization in place it no longer has a legitimate role; a non-match now means *configuration bug*, and must surface, not price.

**Acceptance:** upload a BH file using `Local` / `LOCAL` / `International` / an unknown token; verify fact rows carry `DOMESTIC`/`INTERNATIONAL`/`INTERNATIONAL` respectively, the unknown token is logged, and zero rows take a fallback rate.

## Phase 3 — BHD 3-decimal end-to-end (~2–3 days incl. re-ingest)

**3.1** Widen columns (migration): all amount/fee columns on `stg_trnx_raw`, `fact_transaction`, and every `sum_*` table from `DECIMAL(19,2)` → `DECIMAL(19,4)`. (Postgres widening is metadata-only; the partitioned tables inherit.)
**3.2** Currency-driven scale in the processor (`:689`, `:698`):
```java
int scale = scaleFor(decVal);          // 1000→3, 100→2, 1→0
item.setStoreBaseCurrencyAmount(amt.divide(decimalDivisor(decVal), scale, HALF_UP));
```
**3.3** The refund-signing CASEs and fee multiplications need no change (they inherit column scale), but verify the rollup INSERT…SELECTs don't `ROUND(x,2)` anywhere (they don't — they rely on column type, which 3.1 fixes).
**3.4** Frontend formatters (`frontend/src/utils/formatters.js`) and PDF renderers: format by tenant currency exponent (BHD→3dp), not fixed 2.
**3.5** Re-ingest all BH data uploaded before the fix (none exists yet if this ships before go-live — the reason to do it now).

**Acceptance:** ingest a row of 100.505 BHD → fact stores 100.505; a 1.75% interchange computes 1.7588 (4dp column); daily rollup sums preserve 3dp.

## Phase 4 — Complete the Bahrain rate card (data work, parallel to 1–3)

- **4.1 BENEFIT**: add `ref_card_scheme` row(s) for BENEFIT (+ the feed's actual token variants), and BH `interchange_rate_local` rows with real BENEFIT domestic-switch economics (flat/low fee — get the figure from the BH business case). Until then every BH debit prices at 1.75%.
- **4.2 BH terminal-type → channel**: get the BH processor's terminal-type strings; make the ECOM whitelist (`:1146-1147`) a config table (`terminal_channel_map(country_code, type, channel)`) seeded for AE (existing 4 strings) and BH.
- **4.3 Real BH scheme-fee grid** to replace the UAE copy; **real BH cross-border interchange** to replace flat 1.85%; **BH `ecom_flat_fee` row** in BHD.
- **4.4** Card-type/tier differentiation for BH if the business case has it (current BH rows are all wildcard).

## Phase 5 — Safety net (before first production month closes)

- **5.1** `fee_resolution_status` + `interchange_rule_id`/`scheme_fee_rule_id` columns on `fact_transaction`; populate from the LATERALs (they already select the winning row — just also select its `id`). Fail the batch step if `NO_RULE > 0`.
- **5.2** Effective-dating (`effective_from`/`effective_to`) on the four rate tables, resolved by `payment_date` — before the first BH rate change, not after.
- **5.3** Transaction-type taxonomy: recognize reversal/chargeback tokens used by the BH feed; unknown types → quarantine, not purchase-pricing.
- **5.4** Fee reconciliation job vs. scheme settlement files (VSS/GCMS) per country, monthly.

## Go-live gate for the Bahrain tenant

- [ ] Tenant created with `home_country_code='BH'` (verified in DB, not assumed)
- [ ] Startup guard active (AE-code + non-AED alarm)
- [ ] Destination map seeded for BH; test file with `LOCAL`+`INTERNATIONAL`+unknown tokens behaves per rule; zero fallback hits
- [ ] Hardcoded 1.85% fallback removed
- [ ] BHD amounts verified 3dp end-to-end (staging → fact → fee → rollup → UI/PDF)
- [ ] BENEFIT seeded; BH ECOM channel mapping live; BH scheme-fee/intl rates replaced with real figures
- [ ] One full BH sample month ingested and reconciled against the business-case workbook to the fils

**Effort estimate:** Phases 1–3 ≈ 4–6 dev-days plus test; Phase 4 is mostly data acquisition from the Bahrain business side; Phase 5 ≈ 1 week. Phases 1–3 + 4.1/4.2 are the hard floor for onboarding the tenant.
