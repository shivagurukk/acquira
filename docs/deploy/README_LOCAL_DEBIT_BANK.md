# Deploying the Local Debit Bank Dashboard (UAT / PROD)

Everything needed to bring `/business/local-debit-bank-dashboard` up on a new
environment. All scripts in this folder are idempotent — re-running is safe.

| File | What it does |
|---|---|
| `01_local_debit_bank_schema.sql` | Creates `ref_tenant_bin_bank`, `sum_daily_local_debit_bin` (+partitions, indexes, RLS) and the menu entry |
| `02_seed_uae_bin_bank.sql` | Loads the 158 UAE BINs / 40 banks (UAESWITCH, file date 2026-07-30) |
| `03_verify_local_debit_bank.sql` | Post-rebuild checks, including the parity assertion |
| `04_add_bins_template.sql` | Day-to-day: add or correct a handful of BINs (edit the VALUES block, run) |

> **Why the BIN list matters:** bank names come *only* from `ref_tenant_bin_bank`.
> The scheme BIN files (`ref_bin`, `ref_bin_range`) carry no issuer name and are
> never consulted by this page. Any local-debit BIN that is not in the list shows
> under **"Other Banks"** — never dropped, so totals always stay correct.

> **The BIN list is seeded through the database only.** There is deliberately no
> upload or edit endpoint, and the UI shows the list read-only. Bank names are
> applied at *query* time, so a wrong or partial list would instantly
> re-attribute every bank across every historical month — with nothing to
> rebuild and no way to undo it from the app. Treat it as controlled reference
> data: change it with a reviewed SQL script. A guarded self-service flow
> (validation, preview, audit, rollback) can replace this later.

---

## Step 1 — Deploy the application code

Deploy the branch as usual. The build already contains the controller, the
repository, the batch wiring and the React page.

**Also add these two lines** to `application-prod.properties` under
`spring.sql.init.schema-locations` (this file is gitignored, so edit it on the
target):

```
  classpath:db/migration/V2026_08_20_01__local_debit_bank_dashboard.sql,\
  classpath:db/migration/V2026_08_20_02__local_debit_bank_menu.sql
```

If prod runs with `spring.sql.init.mode=never` (the usual setting), this is
cosmetic — step 2 applies the schema by hand.

## Step 2 — Create the schema and the menu

```bash
psql -h <host> -p <port> -U <user> -d <db> -v ON_ERROR_STOP=1 -f 01_local_debit_bank_schema.sql
```

The script prints a three-row confirmation; all three must say `t`.

## Step 3 — Restart the backend

Required: it registers the new API and picks up the menu grant.

## Step 4 — Load the BIN → bank list

First find the tenant id:

```bash
psql -h <host> -p <port> -U <user> -d <db> -c "SELECT tenant_id, bank_name, home_country_code FROM tenant ORDER BY tenant_id;"
```

Then run the seed with that id:

```bash
psql -h <host> -p <port> -U <user> -d <db> -v ON_ERROR_STOP=1 -v tenant_id=1 -f 02_seed_uae_bin_bank.sql
```

It prints a confirmation — expect **158 BINs / 40 banks** — followed by the
per-bank breakdown.

To add or correct BINs later, edit `02_seed_uae_bin_bank.sql` (or write a small
INSERT in the same shape) and re-run it: `ON CONFLICT` updates existing rows in
place. Because names resolve at query time, the dashboard reflects the change on
the next page load — **no summary rebuild needed**. That is also exactly why this
is not exposed in the UI: the same property makes a bad list instantly global.

## Step 5 — Rebuild history

The dashboard reads a summary table, so existing months must be recalculated
once. `fact_transaction` keeps the first 6 digits of the card number, so all
history is recoverable.

In the UI: **Administration → Data Migration → Summary Rebuild**, with the tenant
switcher set to the target tenant.

Or via the API (super-admin; rebuilds the tenant you are switched into — omit the
months to auto-detect the full range):

```bash
curl -X POST 'https://<host>/api/admin/migration/rebuild-summaries' \
  -H 'Authorization: Bearer <token>' \
  -H 'X-Tenant-Id: 1' \
  -H 'Content-Type: application/json' \
  -d '{"startMonth":"2025-01","endMonth":"2026-06","confirm":true}'
```

Progress: `GET /api/admin/migration/progress`.

> ⚠️ **Check the batch log afterwards.** If step 2 was skipped, the rebuild does
> *not* fail — it logs
> `[REBUILD] sum_daily_local_debit_bin rebuild skipped (non-fatal): ...`
> and moves on, leaving the dashboard empty. Grep for that line to be sure.

## Step 6 — Verify

```bash
psql -h <host> -p <port> -U <user> -d <db> -v tenant_id=1 -f 03_verify_local_debit_bank.sql
```

Pass criteria:

1. BIN list shows **158 / 40** (for the UAE seed).
2. Summary has rows covering the expected date range.
3. **`diff_volume` and `diff_txns` are both 0** — the dashboard reconciles
   exactly with the Card Type Dashboard's Domestic × Debit figures.
4. Per-month parity returns **no rows**.
5. Bank split looks sane; note the "Other Banks" share.
6. Unmatched BINs — feed these back into the next upload to raise coverage.
7. Menu entry exists with at least one group grant (zero grants = the page is
   invisible and the API returns 403).

Finally, log in and open **Business → Local Debit Banks**.

---

## Ongoing maintenance

- **Improving coverage:** the page's "Unmatched BIN worklist" chip lists the
  highest-volume BINs with no bank. Put them in `04_add_bins_template.sql` (edit
  the VALUES block) and run it — all history re-labels instantly, no rebuild:

  ```bash
  psql -h <host> -p <port> -U <user> -d <db> -v ON_ERROR_STOP=1 -v tenant_id=1 -f 04_add_bins_template.sql
  ```

  Keep bank-name spelling identical to existing rows, or the same bank appears
  twice on the dashboard. Check with
  `SELECT DISTINCT bank_name FROM ref_tenant_bin_bank WHERE tenant_id = <id>;`
- **New months:** handled automatically. Every ingest path (file upload, server
  file processing, backfill) and the summary rebuild all maintain the table.
- **BIN format:** store 6 digits. Feeds only expose the first 6 clear PAN digits,
  so an 8-digit BIN can never match — take its 6-digit prefix. If two 8-digit
  BINs share a prefix under different banks, pick one deliberately; the data
  cannot distinguish them.
- **Never** point users at a self-service upload for this table until the guarded
  flow exists — see the warning at the top.
