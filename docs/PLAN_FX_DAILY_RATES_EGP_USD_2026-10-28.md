# Date-aware EGP → USD conversion (daily FX rates) — design

Date: 2026-10-28 — **IMPLEMENTED the same day (see §10)**
Scope: Egypt tenants (EGP). Pegged Gulf currencies are unaffected.

Rate source (final decision 2026-10-28): **the bank's own MPS rate table**
(`MERCH_BANK_AFS.MPS_ADMN_RDEF_MAST`, `MAR_BUY_RATE`, source currency 122 → target 119),
i.e. the USD rate the bank itself books. History was loaded from the business's export
(`EGY.xls`, 2018-12-11 → 2026-10-27); from here on an Integration Hub pull reads the same
table daily. xe.com and Mastercard public rates were considered and dropped: the bank's
booked rate is the one figure nobody can dispute.

Carry-forward rule (user instruction): "if that day is not there use previous day's".

---

## 1. Problem

Today USD figures on the executive screens and the daily digest are produced by multiplying
every amount by ONE hardcoded rate:

| Place | Rate | Stamp |
|---|---|---|
| `frontend/src/config/fxRates.js` | EGP 0.02070 USD/unit | 2026-09-10 |
| `acquira-common/.../service/FxRates.java` | EGP 0.02070 USD/unit | 2026-09-10 |

The conversion is applied client-side to already-aggregated period totals
(`utils/formatters.js` → `convertForDisplay`). For the pegged currencies this is
fine. For EGP, which floats, it is wrong in three ways:

1. January volume is converted at October's rate.
2. Month-on-month USD comparisons move with the rate snapshot, not with the business.
3. The rate goes stale silently; the "as of" stamp is the only hint.

## 2. Target behaviour

- Every EGP amount is converted at the USD rate **of the business date it belongs to**.
- A period total in USD is the **sum of the day-by-day converted amounts**, never
  "total × one rate".
- Each screen/export/email states which rates were used (method, date range, coverage).
- Rates live in the database with provenance (who/what loaded them, when), not in code.
- Days without a rate are visible, never silently filled.

## 3. Data model

New table (migration `V2026_10_28_01__fx_rate_daily.sql`, applied via `apply_migrations.bat` /
psql like the other digest-era scripts):

```sql
CREATE TABLE IF NOT EXISTS fx_rate_daily (
    fx_rate_id      BIGSERIAL PRIMARY KEY,
    currency_code   VARCHAR(3)     NOT NULL,            -- ISO 4217, e.g. EGP
    rate_date       DATE           NOT NULL,
    units_per_usd   NUMERIC(18,8)  NOT NULL CHECK (units_per_usd > 0),  -- e.g. 48.65000000 EGP per 1 USD (how Mastercard publishes it)
    usd_per_unit    NUMERIC(18,10) GENERATED ALWAYS AS (1 / units_per_usd) STORED,
    source          VARCHAR(16)    NOT NULL,            -- MC_CSV | MC_API | MANUAL | PEG
    source_ref      VARCHAR(200),                       -- file name, Mastercard fxDate, note
    loaded_by       VARCHAR(100),
    loaded_at       TIMESTAMP      NOT NULL DEFAULT NOW(),
    UNIQUE (currency_code, rate_date)
);
CREATE INDEX IF NOT EXISTS ix_fx_rate_daily_ccy_date ON fx_rate_daily (currency_code, rate_date DESC);
```

Notes
- Global, not per tenant. A rate is a market fact; all Egypt tenants share it. Row-level
  security is therefore not applied (same as `ref_country`).
- `units_per_usd` is the canonical column because that is the number people read on
  the Mastercard Currency Converter ("1 USD = 48.65 EGP"). `usd_per_unit` is derived so SQL joins stay simple.
- Pegged currencies (BHD, AED, OMR, SAR, QAR) are NOT loaded. `FxRateService` keeps the
  peg constants as a built-in fallback so Gulf tenants behave exactly as today.
- Weekends/holidays: no row. Lookup carries the last known rate forward
  (see §4), bounded by a staleness limit.

Audit: inserts/updates also write `audit_log` (action `FX_RATE_UPSERT`) so a changed historical
rate is traceable.

## 4. Rate lookup rules (`FxRateService`, replaces static `FxRates`)

```
usdPerUnit(ccy, date):
  if ccy == USD            -> null  (do not convert)
  if ccy is pegged         -> constant (as today)
  row = latest fx_rate_daily where currency_code=ccy and rate_date <= date
  if row is null           -> null  (no rate: caller must show "unavailable")
  if date - row.rate_date > fx.max-carry-forward-days (default 5) -> null
  return row.usd_per_unit
```

- Carry-forward of 5 days covers weekends plus a public holiday; anything longer is a
  data gap and is surfaced, not papered over.
- `coverage(ccy, from, to)` returns `{ daysInRange, daysWithRate, firstMissing, lastRateDate }`
  and is attached to every API response that converts.
- Cache: whole table for the floating currencies is small (≈ 250 rows/year); load into
  memory on first use, invalidate on any write (same pattern as `CurrencyResolver`).

## 5. Where conversion happens

The screens behind the topbar USD toggle (`Layout.jsx` → `CCY_TOGGLE_PATHS`) all read
from the daily summary tables keyed by `business_date`. Conversion moves from the
frontend into those SQL aggregates:

```sql
SELECT SUM(s.total_volume)                                   AS volume_local,
       SUM(s.total_volume * COALESCE(fx.usd_per_unit, 0))    AS volume_usd,
       COUNT(*) FILTER (WHERE fx.usd_per_unit IS NULL)       AS days_without_rate
FROM   merchant_daily_summary s
LEFT JOIN LATERAL (
       SELECT usd_per_unit FROM fx_rate_daily r
       WHERE  r.currency_code = :ccy
         AND  r.rate_date BETWEEN s.business_date - :carry AND s.business_date
       ORDER BY r.rate_date DESC LIMIT 1) fx ON TRUE
WHERE  s.tenant_id = :tenant AND s.business_date BETWEEN :from AND :to
```

Response contract (every executive endpoint on the toggle list):

```json
{
  "volume": 123456.78,           // local, unchanged
  "volumeUsd": 2538.12,          // new, null when coverage incomplete
  "fx": { "base": "EGP", "quote": "USD", "method": "DAILY",
          "from": "2026-10-01", "to": "2026-10-28",
          "daysWithRate": 28, "daysInRange": 28,
          "effectiveRate": 48.63 }   // volume-weighted EGP per USD, for the footer only
}
```

Frontend changes
- `formatters.js`: `setDisplayMode('USD')` stays, but `convertForDisplay` is no longer a
  multiply. Pages render the `*Usd` field when present; if a page has no `*Usd` field for
  a metric it falls back to local and labels it (never a wrong number).
- Pegged tenants: backend fills the `*Usd` fields with the peg constant, so the frontend
  path is identical for every tenant and `fxRates.js` can be deleted.
- Topbar toggle label becomes "USD · daily rates"; tooltip and CSV footer print the
  `fx` block ("Converted day-by-day at stored EGP/USD rates, 2026-10-01 → 2026-10-28,
  28/28 days, effective 48.63 EGP/USD").
- Coverage gap: an amber badge "N days without a rate — USD figures incomplete" and the
  USD numbers are shown with the gap excluded (never estimated).

Digest email (`DigestEmailService`): the "(≈ $…)" annotation uses the rate for the digest's
business date; the footer sentence quotes that date and rate instead of `FxRates.AS_OF`.

PDF reports: no USD today; out of scope. The service is available if a USD page is wanted later.

Rounding: multiply per row at full precision, sum, then round once to 2 dp (USD). Never
round the rate.

## 6. Loading the rates (as built)

**History** — migration `V2026_10_28_02__fx_rate_daily_egp_seed.sql`: 1,833 dated rows
generated from `EGY.xls`. Where the export held several buy rates for one date (23 dates;
the MPS keeps one row per rate table), the average of the distinct values was stored and the
fact is noted in the file header. Gaps in the export (longest: 2023-05-07 → 2025-04-21) are
left as gaps: the lookup carries the last rate forward and reports how stale it is.

**Daily feed** — Integration Hub report of the new type **FX Rates** (`FX_RATE`), on the
tenant's existing MPS (Oracle) connection, scheduled daily after the MPS end-of-day:

```sql
SELECT MAR_BUY_RATE, MAR_RATE_DATE
FROM   MERCH_BANK_AFS.MPS_ADMN_RDEF_MAST
WHERE  MAR_SRCY_SQID = '122'          -- USD
  AND  MAR_TRCY_SQID = '119'          -- EGP
  AND  MAR_RATE_DATE >= :dateFrom     -- rolling window, re-pulls the last few days
  AND  MAR_RATE_DATE <  :dateToExclusive
```

Column mapping: `{"MAR_BUY_RATE": "units_per_usd", "MAR_RATE_DATE": "rate_date"}`.
The currency column is optional and defaults to the tenant's base currency (EGP).
The apply step averages same-date rows, upserts into `fx_rate_daily` with `source = MPS_PULL`,
and never overwrites a `MANUAL` row. No Spring Batch job: one SQL upsert. Like every report,
the SQL needs SUPER_ADMIN approval before it runs.

### 6a. Original Phase-1 notes (superseded)

The business will provide EGP/USD rates from 1 January 2025 through today. Two ways in,
both landing in the same table with `source` set accordingly:

**A. One-off seed script** (`docs/deploy/FX_EGP_USD_2025_2026.sql`, applied by hand like the
other deploy scripts): generated from the supplied CSV, idempotent
(`ON CONFLICT (currency_code, rate_date) DO NOTHING`).

**B. Admin screen** Settings → Regional → **FX Rates** (new RBAC menu privilege `fx.rates`):
- grid by date with filters (currency, from, to, source), inline edit, add row
- CSV upload, template below; validation: ISO date, positive number, no duplicate dates in
  file, warn on a day-over-day move > 5 % (likely a typo, not a block)
- coverage strip showing missing business days in the selected range
- Phase 3 adds a "Fetch from Mastercard" button and the scheduler status here

CSV template the business should fill:

```
rate_date,currency_code,units_per_usd
2025-01-01,EGP,50.8500
2025-01-02,EGP,50.7900
...
2026-10-28,EGP,47.9100
```

**Rate source: Mastercard (decided 2026-10-28).** The bank uses the Mastercard daily
currency conversion rate rather than xe.com. Reasons:
- it is the rate the scheme itself applies when converting cross-border EGP transactions,
  so the USD figures are defensible to auditors and to the scheme;
- the bank already receives it (Mastercard Connect / clearing reports), no new vendor;
- the public Mastercard Currency Converter publishes one rate per calendar day, which
  matches the one-row-per-date model above.

How to export it: in the Mastercard Currency Converter (or Mastercard Connect), set
transaction currency EGP, cardholder billing currency USD, bank fee 0 %, and take the
"1 USD = x EGP" figure for each date into the `units_per_usd` column. Keep bank fee at 0 %
so the stored rate is the pure scheme rate.

Caveats to state on the screen footer: the Mastercard rate is a scheme rate and will differ
from a mid-market quote by a fraction of a percent; it is published once a day and weekend
dates repeat the last business-day rate, which the carry-forward rule already handles.

## 7. Phase 3: Mastercard rate feed

Mastercard Developers offers the **Currency Conversion Calculator API** (free developer
account, OAuth 1.0a request signing with a Mastercard-issued key, product
"Currency Conversion Calculator" under `developer.mastercard.com`):

| Need | Endpoint |
|---|---|
| Rate for a date | `GET /settlement/currencyrate/conversion-rate?fxDate=2026-10-27&transCurr=EGP&crdhldBillCurr=USD&bankFee=0&transAmt=1` |
| Is today's rate published yet | `GET /settlement/currencyrate/settlement-date` |
| Supported currencies | `GET /settlement/currencyrate/supported-currencies` |

The response carries `conversionRate` (USD per 1 EGP at `bankFee=0`); the feed stores
`units_per_usd = 1 / conversionRate` so the table reads the same as the CSV rows.
Confirm with Mastercard how far back `fxDate` is accepted; the CSV load covers history
regardless, the API only needs to keep the table current.

Design
- `MastercardRateFeed` in `acquira-core`, `java.net.http.HttpClient` (same as `WebhookDispatcher`),
  5 s connect / 15 s read timeouts, never follows redirects. OAuth 1.0a signing via the
  Mastercard `oauth1-signer` library (one small dependency) or a hand-rolled RSA-SHA256 signer.
- `@Scheduled` daily at 06:30 Africa/Cairo, after Mastercard publishes the day's rate; checks
  `settlement-date` first and retries hourly until the new date appears. Catch-up: fetches
  every missing date in the last 10 days, so a failed night heals itself.
- Writes `source = 'MC_API'`, `source_ref` = fxDate + response timestamp. **Never overwrites a
  `MANUAL` row**; an admin override wins. A later manual edit of an API row is recorded in `audit_log`.
- Config (Kubernetes secret, not `application.properties`):
  `acquira.fx.mc.enabled=false` (default off), `acquira.fx.mc.consumer-key`,
  `acquira.fx.mc.signing-key` (PKCS#12 path + password), `acquira.fx.mc.currencies=EGP`.
- Failure → `IngestAlertScheduler`-style alert email to admins after 2 consecutive misses;
  the admin screen shows "last successful fetch".
- Cluster egress: the core pod needs outbound HTTPS to `api.mastercard.com` (network policy
  change in `deploy/kubernetes-aws`).

Why not xe.com: paid subscription, a different rate from the one the scheme settles at, and
one more vendor to onboard. The design keeps `source` open-ended, so a second source could
be added later without schema change.

## 8. Delivery plan (original)

| Phase | Work | Outcome |
|---|---|---|
| 1 | Table + migration, `FxRateService`, seed script from the supplied CSV, admin FX Rates screen with CSV upload, RBAC privilege | Rates stored with provenance; business can maintain them |
| 2 | Date-aware SQL in the executive endpoints, `*Usd` fields + `fx` block, frontend toggle/footers/coverage badge, digest email, delete hardcoded EGP rate | USD figures correct per date on every executive screen |
| 3 | Mastercard API feed, scheduler, alerts, egress | Rates arrive without manual work |

Phase 1 and 2 can be built together; nothing in Phase 2 depends on the API feed.

## 9. Test plan (key cases)

- Rate lookup: exact date; weekend carries Friday's rate; 6-day gap returns null.
- Period total equals the sum of daily converted amounts (not total × any single rate).
- Range with one missing day: `daysWithRate = daysInRange − 1`, badge shown, USD excludes that day.
- Pegged tenant (BHD): USD figures identical to today's output to the cent.
- CSV upload: duplicate date rejected, 5 % jump warned, re-upload idempotent.
- Mastercard feed off by default; with a stubbed endpoint, catch-up fills a 3-day hole and does
  not overwrite a MANUAL row; `units_per_usd` equals 1 / `conversionRate` to 8 dp.
- Digest email for an Egypt tenant quotes that business date's rate.

## 10. What was built on 2026-10-28

| Layer | Change |
|---|---|
| DB | `V2026_10_28_01__fx_rate_daily.sql` (table `fx_rate_daily` + staging `stg_fx_rate_raw`), `V2026_10_28_02__fx_rate_daily_egp_seed.sql` (1,833 EGP rows from EGY.xls) |
| Common | `FxRateService`: `rateOn(ccy, date)` with carry-forward, `series(ccy, from, to)`, volume-weighted `effective(tenant, ccy, from, to)`; `FxRates` keeps pegs only — the EGP constant is gone on both server and client |
| Batch | `IntegrationReport.ReportType.FX_RATE`; pull → `stg_fx_rate_raw` → averaged upsert into `fx_rate_daily` (`MPS_PULL`), cache invalidated |
| Core | `GET /api/fx/series?from&to`: per-day rate (with the rate date actually used and stale-days), tenant daily volume, effective rate, latest rate; 204 when the tenant currency cannot be converted. Digest email converts at the digest's business-date rate and says so in the footer |
| Frontend | `formatters.js` takes a dated series (`setFxSeries`, `fxRateForRange`), `hooks/useFxSeries` loads a page's range, `Layout` loads a trailing-35-day default and shows "1 USD = 56.01 EGP · 2026-10-27" beside the toggle; Integration Hub offers the FX Rates report type |
| Executive Dashboard | Every week/month bucket is converted at the volume-weighted rate of **its own dates**, totals and prior-period baselines at their own ranges, run-rate at the MTD rate; CSV gets a "Rate (EGP per USD)" column per row and a footer naming the rate basis |
| Other toggle pages | Daily Merchant, Net Spread, Product Summary load the rates of the month / selected days they show. Volume & Revenue, Loss-Making, Attrition, Top Performers, Sales Hierarchy and Sales Pulse convert at the Layout default (trailing 35 days, volume-weighted) until wired to their own ranges |
| Tests | `FxRateServiceTest` (exact day, carry-forward + staleness, weighted vs simple average, EGP has no constant); `displayCurrency.test.js` extended for the dated series |

Not done / to verify on UAT: the migrations must be applied (`apply_migrations.bat`), the FX
Rates report must be created and approved in the Integration Hub, and the screens should be
eyeballed with an Egypt tenant — the local box has no Egypt data to preview against.
No admin screen for manual rate edits yet (a `MANUAL` row can be inserted by SQL and is
protected from the pull).
