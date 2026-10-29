# Tenant 1 recovery by backfill (2026-10-13)

## Why this works

The 2026-10-08 cross-tenant cleanup deleted tenant 1's `dim_merchant`, `dim_store` and
`dim_terminal` rows. The 91.6M `fact_transaction` rows and every summary table survived
but point at IDs that no longer exist, so every merchant-level report joins to ~7% of the
data. The nightly merchant pull has since re-created the merchants, stores and terminals
under NEW ids.

`POST /api/batch/backfill` (BackfillIngestionService) re-pulls a date range from the
source, one day at a time, and for each day:

1. stages the day's rows, auto-creates any missing merchant / store / terminal by MID /
   SID / TID (so it resolves to the NEW ids the nightly pull created),
2. prices the rows with the fee engine,
3. **deletes that day's existing `fact_transaction` rows and inserts the new ones**,
4. after all days: rebuilds the daily and monthly summaries for the landed dates and the
   monthly merchant metrics for the touched months.

So backfilling a range is "clean the orphan facts + reload against the new ids" in one
step. No mapping between old and new ids is needed.

## Before you start

1. **Deploy the MonthlyMetricsRebuilder guard** (acquira-common, this change set). Without
   it any month that still has orphan daily summaries fails its monthly-metrics step with
   the `sum_monthly_merchant_metrics_merchant_id_fkey` error you saw. With it the nightly
   `dbPullTransactionJob` for tenant 1 also starts succeeding again.
2. **Do not run the duplicate-merchant cleanup again.** Tenants 1 and 8 share 14,195
   merchants because both pulls read the same merchant master. Fix the source query
   filter first (see "Root cause" below) or the same situation returns.
3. Confirm the source still holds tenant 1 history back to 2025-01-09 (earliest fact).

## Step 1 — register the source for the backfill

Backfill reads `data_source_config`, not `integration_connection`. Both use the same
`cryptoService` encryption, so copy tenant 1's working connection across. Check the real
column names first (`SELECT column_name FROM information_schema.columns WHERE table_name =
'data_source_config'`), then:

```sql
INSERT INTO data_source_config (name, db_type, host, port, db_name, username, encrypted_password, is_active, created_at)
SELECT 'Tenant1 backfill (' || name || ')', db_type, host, port::text, db_name, username, encrypted_password, true, now()
FROM integration_connection
WHERE tenant_id = 1 AND is_active = true
ORDER BY id LIMIT 1
RETURNING id;   -- this is dataSourceId
```

## Step 2 — build the per-day source query

Take tenant 1's active TRANSACTION query from `integration_report`:

```sql
SELECT id, report_type, column_mapping, sql_text
FROM integration_report
WHERE tenant_id = 1 AND is_active = true AND report_type ILIKE '%trans%';
```

Two changes are needed because the backfill has no column mapping and binds only one
parameter:

* Replace the nightly date filter (`:dateFrom` / `:dateTo` / `:dateToExclusive`) with a
  single-day filter on `:targetDate`, e.g.
  `AND TRUNC(t.payment_date) = :targetDate` (Oracle) or
  `AND DATE(t.payment_date) = :targetDate` (Postgres / MSSQL `CAST(... AS DATE)`).
* Alias every selected column to the staging name the backfill reads
  (`column_mapping` tells you which source column is which):

```
entity_name, aggregator_internal_id, aggregator_name, aggregator_code,
mid, merchant_internal_id, merchant_name,
sid, merchant_store_internal_id, cmm_merchant_store_internal_id, merchant_store_legal_name, store_name,
tid, arn, rrn_number, card_number, auth_code,
payment_date, transaction_date, batch_number, transaction_type, card_scheme, card_type, dcc,
txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount,
msf, vat, total_amount_settled, interchange_fee, destination
```

Columns the source does not have can be omitted (they load as NULL). `mid`, `sid`,
`tid`, `payment_date` and the amount columns are the ones that matter.

Send me the `sql_text` + `column_mapping` and I will do the rewrite.

## Step 3 — run it, one month at a time, oldest first

Endpoint is on the main application host (core bundles the batch module). Needs a
SUPER_ADMIN JWT.

```bash
curl -X POST "https://<uat-host>/api/batch/backfill" \
  -H "Authorization: Bearer <SUPER_ADMIN token>" -H "Content-Type: application/json" \
  -d '{
    "tenantId": 1,
    "dataSourceId": <id from step 1>,
    "startDate": "2026-10-01",
    "endDate":   "2026-10-13",
    "sourceQueries": ["<rewritten query from step 2>"]
  }'
```

```bash
curl -H "Authorization: Bearer <token>" "https://<uat-host>/api/batch/backfill/status"
```

Order and sizing:

* **Run October 2026 first** (1st to today). It clears the orphan rows in the current
  month, which is what the nightly job trips over, and shows the whole pipeline works.
* Then whole calendar months, newest first is fine for reports, but **always full
  months** (1st to last day) so each month's metrics are rebuilt from a consistent set.
* One request at a time per tenant. The service keeps a single progress object; a second
  request overwrites the status view and both compete for staging.
* Expect roughly 140k facts per day for tenant 1. Watch the status endpoint; each day's
  failure is logged and the run continues, so read `errorMessages` at the end.
* Report caches are cleared at the end of each run.

## Step 4 — verify after each month

```sql
-- facts that still point at deleted merchants, for the backfilled month
SELECT COUNT(*) FROM fact_transaction f
WHERE f.tenant_id = 1 AND f.payment_date >= DATE '2026-10-01' AND f.payment_date < DATE '2026-11-01'
  AND NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = f.merchant_id);   -- expect 0

-- bank-level vs merchant-level (joined) totals must now match
SELECT 'bank' AS src, SUM(total_volume), SUM(total_txns) FROM sum_daily_bank
WHERE tenant_id = 1 AND business_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-13'
UNION ALL
SELECT 'merchant join', SUM(s.total_volume), SUM(s.total_txns)
FROM sum_daily_merchant s JOIN dim_merchant m ON m.merchant_id = s.merchant_id
WHERE s.tenant_id = 1 AND s.business_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-13';
```

Also compare the backfilled month's bank total with what the Executive Summary showed
before the cleanup; they should agree to the fils.

## What backfill does NOT restore

* Store / terminal attributes that only lived in the deleted rows (addresses, MCC) come
  back from the merchant master pull, not from the backfill — they are already there.
* Days with no source data stay empty.
* Merchants with no transactions since 2025-01 are simply absent, which is correct.

## Root cause to close

```sql
SELECT id, tenant_id, report_type, LEFT(sql_text, 500)
FROM integration_report
WHERE tenant_id IN (1, 8) AND report_type ILIKE '%merchant%';
```

If neither query filters the merchant master by institution / tenant, both tenants
import the same merchants every night. Add the filter, then remove the copies that do
not belong — but this time check `fact_transaction` references per tenant before
deleting anything, and never delete rows that have facts.
