# Acquira — Loss-Making Merchants Dashboard Audit

**Date:** 2026-08-05
**Branch audited:** `deploy/kubernetes-aws` @ `1f19d04` (+ uncommitted working tree)
**Screen:** `/business/loss-making` — "Loss-Making Merchants" (Executive)

**Surface under audit**

| Layer | File |
|---|---|
| Route | `frontend/src/App.jsx:104` |
| Page | `frontend/src/pages/LossMakingMerchants.jsx` (thin wrapper) |
| Component | `frontend/src/pages/CeoVolumeRevenue.jsx` (shared, `lossOnly` prop) |
| API | `GET /api/business/ceo-volume-revenue` — `BusinessController.java:314-496` |
| Data | `sum_daily_terminal` ⋈ `dim_merchant` ⋈ `dim_store` |
| Rollup | `TransactionJobConfig.java:1428-1440` (`sum_daily_terminal` populate) |
| Menu/ACL | `V2026_07_05_04__loss_making_menu.sql` |

---

## 1. Method — and what I could not test

**What I did test.** Static review of the full read path; plus **dynamic SQL validation** against the live local PostgreSQL 18.1 instance (`127.0.0.1:5433`). I reconstructed the exact SQL the controller emits and executed **1,280 query executions** covering the complete filter matrix:

> 4 periods (MTD, YTD, This Month, pick-a-month) × lossOnly on/off × search on/off × 10 sort keys (including an unknown key, to exercise the default-fallback branch) × ASC/DESC × page 0 and page 3 × both the row query and the count/totals query.

**Result: 0 SQL failures.** Every filter, individually and in every combination, produces valid SQL — no syntax errors, no `GROUP BY`/`HAVING` violations, no invalid `ORDER BY` against the merchant-level rollup, and the sort whitelist correctly prevents user text from reaching a SQL identifier. Scripts: `scratchpad/matrix.ps1`, `scratchpad/audit_queries.sql`, `scratchpad/proofs.sql`.

**What I could not test, and why.** No backend or frontend process is running in this environment, and the local database has **no transactional data** — `sum_daily_terminal`, `sum_daily_bank`, `sum_daily_merchant` and `fact_transaction` are all empty (only reference data: `interchange_rate_local`, `ref_country`, `sys_menu`, etc. are populated). So I could **not**:

- click through the UI and observe rendered numbers,
- reconcile a KPI tile against a hand-computed figure from real rows,
- verify a downloaded CSV byte-for-byte,
- observe live pagination behaviour under real data volumes.

Findings below are therefore derived from code, schema, and executed SQL — several are proven with reproducible SQL (§3), the rest are reasoned from source with exact line references. **§5 lists what still needs hands-on validation against a populated environment.**

| Severity | Count |
|---|---|
| High | 3 |
| Medium | 7 |
| Low | 8 |

---

## 1a. Remediation status (2026-08-05)

All findings except M-7 are fixed. Backend (`mvn compile`) and frontend (`vite build`) both build clean; the full 4-period × lossOnly × search × sort × direction × page filter matrix was re-executed against the modified query shapes — **3,520 query executions, 0 SQL failures**, now including the new `margin` sort key, the ORDER BY tiebreakers, and searches containing `%`, `_`, `\` and `'`.

| ID | Status | Where |
|---|---|---|
| H-1 | Fixed — **needs manual apply** | `V2026_08_05_01__sdt_grain_nulls_not_distinct.sql` (new) |
| H-2 | Fixed | `CeoVolumeRevenue.jsx` — `totalCosts` + tile caption |
| H-3 | Fixed | `MenuAccessEvaluator.java` (new) + `BusinessController.java` |
| M-1 | Fixed | `BusinessController.java` — `tieBreak` |
| M-2 | Fixed | `BusinessController.java` (null marginPct) + `CeoVolumeRevenue.jsx` (`pct`/`pctTone`) |
| M-3 | Fixed | `BusinessController.java` — `eff` from `sum_daily_terminal`, + `dataThrough` |
| M-4 | Fixed | `CeoVolumeRevenue.jsx` — `exportCsv` catch |
| M-5 | Fixed | `CeoVolumeRevenue.jsx` — `AbortController` |
| M-6 | Fixed | `CeoVolumeRevenue.jsx` — page reset + out-of-range empty state |
| M-7 | **Open — product decision** | see below |
| L-1, L-2, L-7 | Fixed | `BusinessController.java` — sort whitelist, `ESCAPE '\'` |
| L-3, L-4, L-5 | Fixed | `CeoVolumeRevenue.jsx` |
| L-6 | Fixed | `BusinessController.java` — 403 now carries a `message` |
| L-8 | Partly | M-4 makes the failure visible; a streaming export endpoint is still the real fix |

**Two things need your attention:**

1. **H-1's migration is not wired into `spring.sql.init.schema-locations`, by design.** Prod boots with `spring.sql.init.mode=always` and the dedupe DELETE is a full-table scan — it has no business running on every restart. Apply once via psql, following the V2026_08_01_01 / V2026_08_03_01 convention. Back up first, and run the duplicate-count query in the file header before and after.

2. **M-7 was deliberately not fixed.** Collapsing `/api/finance/loss-making-merchants` into this implementation changes what the Finance screens display, which is a product call, not a cleanup. It needs a decision on which definition is canonical before any code moves.

**Two implementation notes that differ from the recommendations above:**

- **H-3 is enforced in the method body, not via `@PreAuthorize`.** The `hasAnyRole('SUPER_ADMIN','BANK_ADMIN')` suggested in §H-3 would have been **wrong** — the `role` table seeds only ROLE_ADMIN / ROLE_USER / ROLE_SUPER_ADMIN, and 'Bank Admin' is a *group*, not a role, so that annotation would have locked out every legitimate Bank Admin user. The shipped fix checks the actual `sys_group_menu` grant, keeping the SQL migration as the single source of truth. A SpEL form referencing `#lossOnly` was also rejected: this build does not compile with `-parameters` (confirmed — no `MethodParameters` attribute on the compiled class), so the expression would have thrown at runtime.
- Live grants were verified unchanged by the fix: `/business/loss-making` and `/business/ceo-volume-revenue` are each granted to exactly Super Admin and Bank Admin, so no current user loses access.

The verification in §5 still stands — none of it is closed by these changes, because none of it could be run here.

---

## 2. What is correct

Worth stating plainly, because most of this screen is sound:

- **The `lossOnly` semantics are right.** `HAVING SUM(t.total_revenue) < 0` is applied to the grouped aggregate and is textually shared (`base`) by the row query, the count and the totals query, so the three can never disagree about *which* merchants qualify (`BusinessController.java:385, 396-406`).
- **Merchant-level rollup is the correct choice.** `lossOnly` groups by `m.mid, m.name` rather than MID × SID, so a merchant's combined position decides inclusion. The matching decision to drop the `s.sid` search predicate in that mode (`:401-405`) is correct — filtering on SID in `WHERE` would evaluate the loss over one store's rows only, contradicting the rollup.
- **No join fan-out.** `dim_store.store_id` is the primary key and `(tenant_id, sid, merchant_id)` has no duplicates in this schema, so the `LEFT JOIN` cannot multiply rows.
- **The totals/count single-pass refactor is correctly indexed.** The `c1..c7` select order is `txns, volume, msf, interchange, schemeFee, **net**, **ecomFee**` — deliberately *not* the display order — and the Java shift at `:469-479` reads `tot[5]`→net, `tot[6]`→ecomFee. That off-pattern mapping is easy to break in review; it is currently right.
- **Table colspans are correct in both modes.** `lossOnly` → `colSpan=2` + 8 cells = 10 columns; full mode → `colSpan=3` + 8 = 11.
- **The CSV pagination-cap fix is real.** The old `Math.min(totalRows, 5000)` truncation is gone (`CeoVolumeRevenue.jsx:184-194`), and the appended server-computed `TOTAL` row makes the file self-checking.
- **Sort injection is not possible** — `sortCols` is a whitelist with a safe default (`:368-378`); `search` is bound as a parameter.

---

## 3. Findings

### HIGH

---

#### H-1 — The summary rollup double-counts any merchant whose transactions have no store or terminal, and the inflation compounds on every batch re-run

**Severity:** High · **Category:** Data correctness (upstream of the dashboard)
**Evidence:** `TransactionJobConfig.java:1428-1440`; schema constraint on `sum_daily_terminal`

The populate step upserts on a five-column key:

```sql
ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id) DO UPDATE SET ...
```

Verified against the live schema:

```
sum_daily_terminal_tenant_id_business_date_merchant_id_stor_key
    | UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id)

store_id    | attnotnull = f
terminal_id | attnotnull = f
```

`store_id` and `terminal_id` are **nullable**, and the constraint is a plain `UNIQUE` — i.e. `NULLS DISTINCT`. In PostgreSQL two rows that are `NULL` in a key column never collide, so the `ON CONFLICT` arbiter **never matches** for those rows: each rollup run *inserts a new row* instead of updating the existing one. `fact_transaction.store_id` and `.terminal_id` are likewise nullable, so any ingested transaction that did not resolve a store/terminal lands in exactly this case.

Every one of those merchants' `total_base_volume`, `total_msf`, `total_interchange`, `total_scheme_fee`, `total_ecom_fee` and `total_revenue` is then summed *N* times, where *N* is the number of times the rollup has covered that date. On this screen that means an **overstated loss** — and, because `HAVING SUM(total_revenue) < 0` is evaluated on the inflated figure, it can also drag a marginally-profitable merchant onto the list, or magnify a small loss into a headline one.

**Proof (executed, `scratchpad/proofs.sql`):**

```
=== PROOF A: UNIQUE(...) does NOT dedupe when store/terminal are NULL ===
 rows_after_3_identical_upserts | net_the_dashboard_would_show | net_it_should_show
--------------------------------+------------------------------+--------------------
                              3 |                        -1500 |               -500

 rows_for_merchant_11_after_2_upserts | net       <- control: non-NULL keys
--------------------------------------+------
                                    1 | -300
```

Three identical upserts of a −500 loss produce **−1,500**. The control row with non-NULL `store_id`/`terminal_id` collapses correctly.

**Steps to reproduce (populated environment)**

1. Ingest a transaction file containing at least one row whose store/terminal cannot be resolved (blank SID / TID column), for a merchant that is net-negative.
2. Run the summary rollup for that business date.
3. Note the merchant's Net Margin on `/business/loss-making`.
4. Re-run the rollup for the same date (re-upload, or a backfill covering it).
5. Reload the dashboard — Net Margin, Volume, MSF and all fee columns for that merchant have **doubled**. Confirm with:
   `SELECT merchant_id, store_id, terminal_id, COUNT(*) FROM sum_daily_terminal WHERE tenant_id=? AND business_date=? GROUP BY 1,2,3 HAVING COUNT(*)>1;`

**Recommended fix**

PostgreSQL 15+ (this deployment is 18.1) supports a `NULLS NOT DISTINCT` unique index, which makes the arbiter behave the way the code already assumes:

```sql
-- new migration, e.g. V2026_08_05_01__sdt_nulls_not_distinct.sql
-- 1. collapse the duplicates that already exist
WITH d AS (
  SELECT tenant_id, business_date, merchant_id, store_id, terminal_id,
         MIN(summary_id) AS keep_id
  FROM sum_daily_terminal
  GROUP BY 1,2,3,4,5 HAVING COUNT(*) > 1
)
DELETE FROM sum_daily_terminal s USING d
WHERE s.tenant_id = d.tenant_id AND s.business_date = d.business_date
  AND s.merchant_id IS NOT DISTINCT FROM d.merchant_id
  AND s.store_id   IS NOT DISTINCT FROM d.store_id
  AND s.terminal_id IS NOT DISTINCT FROM d.terminal_id
  AND s.summary_id <> d.keep_id;
-- (then re-run the rollup for affected dates so the surviving row holds the true sum)

-- 2. replace the constraint
ALTER TABLE sum_daily_terminal
  DROP CONSTRAINT sum_daily_terminal_tenant_id_business_date_merchant_id_stor_key;
CREATE UNIQUE INDEX sum_daily_terminal_grain_key
  ON sum_daily_terminal (tenant_id, business_date, merchant_id, store_id, terminal_id)
  NULLS NOT DISTINCT;
```

`sum_daily_terminal` is partitioned — apply per-partition or on the partitioned parent as your migration convention requires, and validate on a copy first. If you would rather not change the constraint, the alternative is to make the grain columns non-nullable by writing a sentinel (`COALESCE(store_id, -1)`) at ingest, but that changes every reader.

**Scope note — this is not confined to `sum_daily_terminal`.** I checked the other summary tables' conflict targets against the live schema and the same pattern recurs:

| Table | Nullable column(s) in the conflict target | Insert coalesces it? |
|---|---|---|
| `sum_daily_terminal` | `merchant_id`, `store_id`, `terminal_id` | no — **affected** |
| `sum_daily_mcc` | `mcc`, `card_scheme` | no (`s.mcc`, `f.card_scheme` raw, `:1381-1390`) — **affected** |
| `sum_daily_merchant` | `merchant_id` | inner-joined to `dim_merchant`, so non-null in practice — low risk |
| `sum_daily_channel` | `channel` | yes — `COALESCE(t.type,'POS')` (`:1417`) |
| `sum_daily_scheme` | `card_scheme` | yes — `COALESCE(NULLIF(TRIM(...),''),'Unclassified')` |
| `sum_daily_bank` | none (`tenant_id`, `business_date` both NOT NULL) | n/a |

Only `sum_daily_terminal` affects this dashboard, so only it is in scope for this audit — but `sum_daily_mcc` carries the identical defect and is worth a separate ticket. The pattern to enforce going forward: **every `ON CONFLICT` target column must be either `NOT NULL`, coalesced in the `SELECT`, or covered by a `NULLS NOT DISTINCT` index.**

---

#### H-2 — The KPI band does not reconcile: "Costs" omits the ECOM fee that Net Margin subtracts

**Severity:** High · **Category:** Metric correctness
**Evidence:** `CeoVolumeRevenue.jsx:247, 390-402`; `TransactionJobConfig.java:1433`

The rollup defines net revenue as **all four** deductions:

```sql
SUM(COALESCE(msf,0) - COALESCE(interchange_fee,0) - COALESCE(scheme_fee,0) - COALESCE(ecom_fee,0))
```

The dashboard's Costs tile defines it as two:

```js
// CeoVolumeRevenue.jsx:247
const totalCosts = totals ? num(totals.interchange) + num(totals.schemeFee) : 0;
```

and the Net Margin tile's caption asserts the relationship explicitly:

```js
caption={lossOnly ? 'across loss rows' : 'MSF − costs'}     // :399
```

So for any tenant with e-commerce volume, `MSF − Costs ≠ Net Margin` on screen, and the caption is literally false. The Costs tile's own sub-label — "interchange + scheme fee" — is accurate about what it computes, which makes this a silent understatement rather than an obvious one: an executive reading the band sees costs that are too low and a net margin that doesn't follow from them, on the one screen whose entire purpose is explaining *why* a merchant is losing money.

Corroboration that this is an oversight rather than a definition: the main Dashboard gets it right — `frontend/src/pages/Dashboard.jsx:384` computes `fees = interchange + schemeFee + ecomFee`. The ECOM Fee column is already present in this table and in the CSV; only the KPI aggregate misses it.

**Steps to reproduce**

1. Open `/business/loss-making` for a period where the ECOM Fee column is non-zero.
2. Read the band: `MSF − Costs` and compare to the `Total Net Loss` tile.
3. The difference equals the ECOM Fee total shown in the table's TOTAL row.

**Recommended fix** — `CeoVolumeRevenue.jsx:247`

```js
/* Total costs = interchange + scheme fee + ECOM fee — every deduction
   between MSF and net, matching sum_daily_terminal.total_revenue. */
const totalCosts = totals
    ? num(totals.interchange) + num(totals.schemeFee) + num(totals.ecomFee)
    : 0;
```

and update the tile caption at `:393` to `"interchange + scheme + ECOM"`. With that change `MSF − Costs` equals `Net Margin` exactly, and the caption at `:399` becomes true.

---

#### H-3 — The endpoint enforces no authorization; the Loss-Making grant is UI-only

**Severity:** High · **Category:** Access control
**Evidence:** `SecurityConfig.java:61-73`; `BusinessController.java:314-325`; `V2026_07_05_04__loss_making_menu.sql:17-23`

The migration deliberately restricts the screen:

```sql
WHERE m.path = '/business/loss-making'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
```

but that grant only controls whether the sidebar renders the link. The security chain has no matcher for `/api/business/**`:

```java
.requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
.requestMatchers("/api/batch/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
.anyRequest().authenticated()
```

and the handler checks only that a tenant resolves (`if (tenantId == null) return 403`) — no role, no menu-grant check. Any authenticated user of the tenant can retrieve the full loss-making merchant list, with fee breakdown, by calling the API directly.

This is **not** cross-tenant leakage — `TenantContext` scoping is applied correctly and `resolveTenant()` properly refuses the attacker-controlled `X-Tenant-Id` header. The exposure is intra-tenant: a read-only or analyst-level user sees an executive-restricted commercial dataset.

**Steps to reproduce**

1. Log in as a user in a group *without* the Loss-Making menu grant. Confirm the sidebar link is absent.
2. From the browser console on any Acquira page:
   ```js
   await (await fetch('/api/business/ceo-volume-revenue?lossOnly=true&mode=YTD&size=500',
     { headers: { Authorization: 'Bearer ' + localStorage.getItem('token'),
                  'X-Tenant-Id': localStorage.getItem('defaultTenantId') } })).json()
   ```
3. The full loss-making list returns 200.

**Recommended fix.** Enforce the menu grant server-side rather than duplicating role lists. Short term, annotate the endpoint to match the migration's intent:

```java
@GetMapping("/ceo-volume-revenue")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BANK_ADMIN')")
```

(`@EnableMethodSecurity` is already on at `SecurityConfig.java:29`.) Longer term this screen is one instance of a general gap — a `sys_group_menu`-backed authorization voter that checks the caller's granted menu paths would close it for every DB-driven screen at once, and keep the SQL grant as the single source of truth. Worth confirming how many other `/api/business/**` endpoints share this exposure.

---

### MEDIUM

---

#### M-1 — Pagination is non-deterministic: `ORDER BY` has no unique tiebreaker, so rows can repeat or vanish between pages

**Severity:** Medium · **Category:** Correctness
**Evidence:** `BusinessController.java:414`

```java
"ORDER BY " + orderExpr + " " + orderDir + " NULLS LAST " + "LIMIT :lim OFFSET :off"
```

`orderExpr` is a single aggregate (`SUM(t.total_revenue)`, `SUM(t.total_txns)`, …) with no unique column appended. Ties are ordered arbitrarily and *the order is not stable between statements* — different plans, parallel workers, or the offset itself can permute them. With `LIMIT/OFFSET` paging over an unstable sort, a tied row can appear on two pages or on none.

Ties are not hypothetical here: `sort=txns` on a portfolio with many small merchants produces large tie groups, and the loss list's own default (`net`) ties whenever merchants share a rounded loss.

The CSV export inherits this. It walks pages 0..N with the same unstable sort (`CeoVolumeRevenue.jsx:189-194`), so the exported file can contain duplicate merchants and omit others — while the appended `TOTAL` row is computed by a single unpaginated aggregate and stays correct. The self-verifying TOTAL row would then *disagree* with the column sum, which is exactly the symptom the comment at `:184-188` was written to prevent.

**Steps to reproduce (populated environment)**

1. Pick a period and a sort column with many equal values (`Count` is easiest).
2. Page through all pages and collect the MIDs.
3. Compare the collected count and distinct count against `totalRows`. Under concurrency or a plan flip they diverge.
4. Or: export the CSV and compare `SUM(Volume)` over the data rows against the trailing `TOTAL` row.

**Recommended fix** — `BusinessController.java:414`, append a unique, always-present tiebreaker. `m.mid` is unique at merchant grain (`lossOnly`); MID + SID is unique in full mode:

```java
String tieBreak = lossOnly ? ", m.mid ASC" : ", m.mid ASC, s.sid ASC";
... "ORDER BY " + orderExpr + " " + orderDir + " NULLS LAST" + tieBreak + " LIMIT :lim OFFSET :off"
```

For very large exports, keyset pagination on that same key would also remove the `OFFSET` cost, but the tiebreaker alone fixes the correctness problem.

---

#### M-2 — Net Margin % reports `0.00%` in green for refund-only merchants — the exact case this screen exists to surface

**Severity:** Medium · **Category:** Metric correctness / misleading UI
**Evidence:** `BusinessController.java:445-447` and `:480-482`; rendering at `CeoVolumeRevenue.jsx:465-477`

```java
m.put("marginPct", vol.compareTo(BigDecimal.ZERO) > 0
        ? net.multiply(BigDecimal.valueOf(100)).divide(vol, 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO);
```

The guard is `> 0`, and the fallback is `ZERO` rather than null. Two bad outcomes:

- **Volume exactly 0, net negative** (a merchant whose period is refunds/chargebacks only — a textbook loss-maker): margin renders as `0.00%`, and because the frontend colours on `num(r.marginPct) >= 0` it renders **green on a success background**. A row with a red six-figure Net Margin sits beside a green `0.00%`.
- **Volume negative** (net refunds exceed sales): also forced to `0.00%`, discarding a meaningful signal.

**Proof (executed):**

```
  vol  |  net  | margin_pct_as_coded | margin_pct_correct
-------+-------+---------------------+--------------------
     0 | -1200 |                   0 |               (n/a)
 -5000 |  -900 |                   0 |              18.00
 10000 |  -400 |               -4.00 |              -4.00
```

**Steps to reproduce**

1. Find a merchant with negative `total_revenue` and zero/negative `total_base_volume` in the period.
2. Open `/business/loss-making` and locate the row.
3. Net Margin is red and negative; Net Margin % is a green `0.00%`.

**Recommended fix.** Return `null` when the ratio is undefined and render it as a neutral em-dash, rather than manufacturing a number:

```java
// BusinessController.java:445  (and the same guard at :480 for totals)
m.put("marginPct", vol.signum() != 0
        ? net.multiply(BigDecimal.valueOf(100)).divide(vol, 2, RoundingMode.HALF_UP)
        : null);
```

```jsx
// CeoVolumeRevenue.jsx:465 — treat null as "not meaningful", not as zero
{r.marginPct == null ? '—' : `${num(r.marginPct).toFixed(2)}%`}
```

Note `signum() != 0` also makes negative-volume rows report a real (if unusual) ratio; if you would rather suppress those too, keep `> 0` for the divisor but return `null` instead of `ZERO`. Either way, the fix that matters is **never render an undefined ratio as a healthy green zero**. The CSV writer at `:207` and `:221` needs the same null handling.

---

#### M-3 — The effective date comes from a different table than the data

**Severity:** Medium · **Category:** Data consistency
**Evidence:** `BusinessController.java:327-332` vs `:396-397`

```java
"SELECT MAX(business_date) FROM sum_daily_bank WHERE tenant_id = :tid"
```

drives `eff`, which defines every period window (MTD, YTD, This Month, and the month-picker anchor) — but every figure on the screen is read from **`sum_daily_terminal`**. These are two independent steps of the rollup (`TransactionJobConfig.java:1350-1440`, run concurrently via `runAsync`). If the bank rollup succeeds and the terminal rollup fails, lags, or is still running, the dashboard advertises a window (`from → to` is printed in the subtitle, `CeoVolumeRevenue.jsx:275`) that includes days with no terminal rows, and quietly reports a partial period as a complete one. On this screen a truncated period systematically *understates* losses.

**Recommended fix.** Anchor the window to the table being read:

```java
Object maxD = entityManager
    .createNativeQuery("SELECT MAX(business_date) FROM sum_daily_terminal WHERE tenant_id = :tid")
```

If `eff` must stay aligned with the rest of the CEO suite for cross-screen consistency, then read both and surface the gap — return `terminalMaxDate` in the response and render a "data through <date>" warning when it trails `effectiveDate`. Silently reporting a partial period is the outcome to avoid.

---

#### M-4 — CSV export failures are completely silent

**Severity:** Medium · **Category:** Error handling
**Evidence:** `CeoVolumeRevenue.jsx:231`; `frontend/src/api/axios.js:50-121`

```js
} catch { /* toast handled globally */ }
finally { setExporting(false); }
```

The comment is wrong. The axios response interceptor toasts on **401 only** (session expiry); 403 logs to console, and every other status is re-thrown untouched. There is no global error toast. So if any request in the export loop fails — a 500, a 429 from `RateLimitFilter`, a dropped connection — the button flips back from "Exporting…" to "CSV" and **nothing else happens**. No file, no error, no indication that the download was attempted. The user reasonably concludes the click didn't register and retries, which under a 429 makes it worse.

The partial-failure case is worse than the total one: a failure on page 7 of 12 discards the six pages already fetched and produces no file at all.

**Steps to reproduce**

1. Open `/business/loss-making`, DevTools → Network → set request blocking for `*/business/ceo-volume-revenue*` (or throttle to offline).
2. Click **CSV**.
3. Button shows "Exporting…" then returns to "CSV". No download, no error message, console clean.

**Recommended fix**

```js
} catch (e) {
    showToast(e?.response?.status === 429
        ? 'Export throttled — too many requests. Try a shorter period.'
        : 'Export failed. Please try again.', 'error', 5000);
} finally { setExporting(false); }
```

with `import { showToast } from '../contexts/ToastContext';`. Separately, consider whether the empty-catch-with-"handled globally" pattern appears on other export buttons — the comment suggests a shared assumption that was never true.

---

#### M-5 — Stale responses can overwrite fresh ones (no request cancellation)

**Severity:** Medium · **Category:** Correctness / race condition
**Evidence:** `CeoVolumeRevenue.jsx:149-166`

`load()` fires on every change to period, month, page, sort, dir or debounced query, and unconditionally does `setData(res.data)` when it resolves. There is no `AbortController`, no sequence guard, and no cleanup in the `useEffect`. Requests are not guaranteed to resolve in the order they were issued — a slow YTD query issued first can land *after* a fast MTD query issued second, leaving MTD selected in the toolbar while YTD numbers are on screen.

The window is widest exactly where users click fastest: the MTD / YTD / This Month toggle group, where YTD is by far the heaviest query. The 350 ms search debounce (`:143-147`) mitigates the search path but does nothing for the period buttons, sort headers or pager.

This is also a *silent* wrong-number bug — the subtitle date range comes from `data.from`/`data.to`, so a stale response relabels itself consistently and looks correct.

**Steps to reproduce**

1. DevTools → Network → throttle to "Slow 3G".
2. Click **YTD**, then immediately click **MTD**.
3. The toolbar highlights MTD while the KPI band, table and subtitle show YTD — until the next reload.

**Recommended fix** — abort the in-flight request when the inputs change:

```js
const load = useCallback(async (signal) => {
    setLoading(true); setError(null);
    try {
        const res = await api.get('/business/ceo-volume-revenue', {
            signal,
            params: { ...periodParams, lossOnly: lossOnly || undefined,
                      page, size: PAGE_SIZE, sort, dir, search: query || undefined },
        });
        setData(res.data);
    } catch (e) {
        if (e.name === 'CanceledError' || e.code === 'ERR_CANCELED') return;  // superseded
        setError(e?.response?.data?.message || 'Failed to load report');
    } finally { setLoading(false); }
}, [periodParams, lossOnly, page, sort, dir, query]);

useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
}, [load, tenantVersion]);
```

Keep the manual Refresh button working by calling `load()` with no signal.

---

#### M-6 — Switching tenant while on page 2+ shows "No loss-making merchants… That's good news."

**Severity:** Medium · **Category:** Correctness / misleading UI
**Evidence:** `CeoVolumeRevenue.jsx:166` and `:354-358`

Every other input resets pagination — period (`:303`), month (`:317`), search (`:145`), sort (`:173`). `tenantVersion` does not:

```js
useEffect(() => { load(); }, [load, tenantVersion]);
```

Switch to a tenant with fewer loss rows while sitting on page 3 and the request carries `page=3`, returns an empty `rows` array with a correct non-zero `totalRows`, and the component's `!rows.length` branch renders the reassuring empty state:

> **No loss-making merchants** — No merchants are running at a loss for MTD. That's good news.

That is a false all-clear on a risk screen. The same shape occurs after a data refresh that shrinks the result set beneath the current page.

**Steps to reproduce**

1. On a tenant with >100 loss-making merchants, page to page 3.
2. Switch tenant (tenant selector) to one with fewer than 100.
3. The "That's good news" empty state renders even though the new tenant has loss-making merchants.

**Recommended fix.** Reset the page on tenant change, and distinguish the two empty cases:

```js
useEffect(() => { setPage(0); }, [tenantVersion]);
```

```jsx
) : !rows.length ? (
    totalRows > 0 ? (
        <EmptyState title="Page out of range"
            message={`This page is past the end of ${totalRows.toLocaleString()} results.`}
            action={{ label: 'Back to first page', onClick: () => setPage(0) }} />
    ) : (
        <EmptyState title={lossOnly ? 'No loss-making merchants' : 'No rows'} ... />
    )
) : (
```

The second half is worth doing regardless of the first — an empty page with `totalRows > 0` should never claim there is nothing to find.

---

#### M-7 — Two independent, unrelated definitions of "loss-making merchants" ship in the same product

**Severity:** Medium · **Category:** Consistency / maintainability
**Evidence:** this screen vs `FinanceController.java:655-665` → `SumDailyMerchantRepository.java:60-73`

The Finance screens (`FinanceLists.jsx:23-24`, `FinanceDashboard.jsx:130`) call `/api/finance/loss-making-merchants`, which is a completely separate implementation:

| | Executive · Loss-Making Merchants | Finance · Loss Making Merchants |
|---|---|---|
| Source table | `sum_daily_terminal` | `sum_daily_merchant` |
| Net measure | `total_revenue` | `total_margin` |
| Volume measure | `total_base_volume` | `total_volume` |
| Predicate | `HAVING SUM(total_revenue) < 0` | `HAVING SUM(m.totalMargin) < 0` |
| Filters | period / search / sort / lossOnly | date range only |

Both net measures currently derive from the same ingest formula, so the *lists* should broadly agree today — but nothing enforces that, they are maintained independently, and they are already exposed to different failure modes (H-1 affects the terminal-grain table only, so the two screens will diverge the moment a NULL-store rollup runs twice). Two answers to "which merchants are losing us money" in one product is a reporting-integrity problem regardless of whether they happen to match this week.

The Finance-side repository also carries evidence of unfinished work — `FinanceController.java:642-650` is a block of commented-out deliberation about where to compute margin %, ending with "let the frontend calculate it".

**Recommended fix.** Pick the terminal-grain implementation (it has the richer fee stack and the filters) as the single source of truth and have `/api/finance/loss-making-merchants` delegate to the same query, or extract a shared `LossMakingQueryService`. If the two must stay separate for performance reasons, add a reconciliation test that asserts the two endpoints return the same merchant set for the same window — a failing test is much better than two screens quietly disagreeing in front of a CFO.

---

### LOW

---

**L-1 — `ecomFee` sort ignores NULLs inconsistently with display.**
`BusinessController.java:374` sorts on `SUM(t.total_ecom_fee)` while `:412` *selects* `SUM(COALESCE(t.total_ecom_fee,0))`. Combined with the unconditional `NULLS LAST` at `:414`, merchants with no ECOM fee display `0.00` but sort to the bottom in **both** directions — ascending should put them first.
*Fix:* `sortCols.put("ecomFee", "SUM(COALESCE(t.total_ecom_fee,0))");` — matching what the column actually shows.

**L-2 — Search wildcards are not escaped.**
`:419` binds `"%" + search.trim() + "%"` into `ILIKE`. A user typing `50%` or `a_b` gets wildcard semantics instead of a literal match — confusing on a screen where MIDs contain underscores.
*Fix:* escape first — `search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")` — and append `ESCAPE '\'` to the `ILIKE` clauses.

**L-3 — CSV formula injection via merchant name.**
`CeoVolumeRevenue.jsx:195` quotes and doubles quotes correctly, which is right for CSV parsing but does not stop Excel from evaluating a field that begins `=`, `+`, `-` or `@` once unquoted. Merchant names come from ingested master data.
*Fix:* prefix a single quote when the first character is one of those — `const esc = (v) => { const s = String(v ?? ''); return `"${(/^[=+\-@]/.test(s) ? "'" + s : s).replace(/"/g, '""')}"`; };`

**L-4 — "This Month" is indistinguishable from MTD, and advertises a future end date.**
`BusinessController.java:352-355` sets `to` to the last day of the month. Since no data exists past `eff`, the numbers are always identical to MTD — but the subtitle prints a `from → to` range ending in the future (`CeoVolumeRevenue.jsx:275`), implying coverage that doesn't exist. Either clamp to `min(monthEnd, eff)` for display, or label the tile so the intent (full calendar month, partially elapsed) is explicit.

**L-5 — Month dropdown can be off by one in negative-UTC-offset browsers.**
`CeoVolumeRevenue.jsx:63` — `new Date('2026-08-04')` parses as UTC midnight, then `getFullYear()`/`getMonth()` read it in **local** time. West of UTC that resolves to 2026-08-03 → the list starts at Jul 2026 and the current month is missing. Not an issue for a Bahrain/UAE (UTC+3/+4) user base, but it is a latent bug for any remote user.
*Fix:* parse the components directly — `const [y, m] = anchorISO.split('-').map(Number); const base = new Date(y, m - 1, 1);`

**L-6 — 403 renders as "Failed to load report".**
`BusinessController.java:325` returns `ResponseEntity.status(403).build()` with no body, so the frontend's `e?.response?.data?.message` fallback (`CeoVolumeRevenue.jsx:160`) produces a generic message for what is actually "no tenant selected / no access". Return a small body with a `message`, or special-case 403 in the catch.

**L-7 — Net Margin % is not sortable.**
`ALL_COLUMNS` marks `margin` as `sortable: false` (`:52`). On a screen dedicated to loss-making merchants, "worst margin percentage" is arguably the most important ordering — a large merchant losing 0.1% and a small one losing 40% are very different problems, and only the absolute Net Margin sort is available today. Adding it is a one-line whitelist entry: `sortCols.put("margin", "CASE WHEN SUM(t.total_base_volume) <> 0 THEN SUM(t.total_revenue)/SUM(t.total_base_volume) END");` plus flipping the flag.

**L-8 — Large exports can trip the rate limiter.**
The export loop (`:189-194`) issues `ceil(totalRows/500)` sequential requests with no pacing, against `RateLimitFilter`'s `REGULAR_LIMIT = 200` per minute per IP. The loss-making list is normally small enough, but the same component backs the full Volume & Revenue screen where MID × SID rows run much higher — >100k rows exceeds the budget and, per M-4, fails silently. Pair the M-4 fix with a server-side streaming CSV endpoint if large exports are a real workflow.

---

## 4. Suggested fix order

1. **H-1** — corrupts the underlying numbers; everything else is cosmetic while this is live. Ship the dedupe + `NULLS NOT DISTINCT` index together, and re-run the rollup for affected dates.
2. **H-2, M-2** — small, contained frontend/controller edits that make the displayed figures internally consistent.
3. **H-3** — one annotation now; the menu-grant voter as a follow-up.
4. **M-1, M-3** — one-line query changes, both correctness.
5. **M-4, M-5, M-6** — UI reliability.
6. **M-7** — design decision; needs a product call on which definition wins.
7. **L-1 … L-8** — batch into one cleanup PR.

Note that H-2, M-1, M-2, M-5, M-6 and L-1…L-8 all live in `CeoVolumeRevenue.jsx` / the shared endpoint, so they affect **both** the Loss-Making Merchants screen and the CEO Volume & Revenue screen. Fixing them once fixes both.

---

## 5. Still requires hands-on validation

These could not be closed without a running instance and populated data. Each is a specific check, not a general "test it":

1. **Reconcile the KPI band against SQL** for one period: `Rows`, `Volume`, `MSF`, `Costs`, `Total Net Loss`, `Net Margin %` versus a direct aggregate over `sum_daily_terminal`. Confirms H-2's magnitude and rules out any remaining index-shift error in the `c1..c7` totals mapping (§2).
2. **Confirm H-1 in the real data:** `SELECT COUNT(*) FROM (SELECT tenant_id, business_date, merchant_id, store_id, terminal_id FROM sum_daily_terminal GROUP BY 1,2,3,4,5 HAVING COUNT(*)>1) d;` — a non-zero result quantifies the live inflation.
3. **Page-stability test for M-1:** walk every page twice under concurrent load and diff the MID sets against `totalRows`.
4. **CSV end-to-end:** export, then verify the data-row column sums equal the trailing `TOTAL` row and that row count equals `totalRows` — the single check that catches M-1, M-4 and the old truncation regression at once.
5. **Cross-screen reconciliation (M-7):** same date window on `/business/loss-making` and Finance → Loss Making Merchants; diff the merchant sets.
6. **Authorization (H-3):** run the §H-3 repro with a genuinely low-privilege account.
7. **Filter behaviour in the browser:** although all 1,280 SQL combinations execute cleanly, the *UI* transitions between them (loading states, page resets, the month picker interacting with the MTD/YTD/This Month group, KPI band re-render) were not observed live.
8. **Empty-state and boundary cases:** a tenant with zero loss-making merchants, a period before any data, and a single-row result — confirm the empty state, pager ("Showing 1–0 of 0") and TOTAL row all behave.
