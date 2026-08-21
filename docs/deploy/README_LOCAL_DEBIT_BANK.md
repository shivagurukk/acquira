# Deploying the Local Debit Bank Dashboard (UAT / PROD)

Everything needed to bring `/business/local-debit-bank-dashboard` up on a new
environment.

The three `V2026_08_20_*` files are **copies of the real migrations** in
`acquira-core/src/main/resources/db/migration/`. That folder is gitignored, so
these tracked copies are how the SQL actually reaches a deployment — keep them
byte-identical if you ever edit one.

| File | What it does | When it runs |
|---|---|---|
| `V2026_08_20_01__local_debit_bank_dashboard.sql` | `ref_tenant_bin_bank`, `sum_daily_local_debit_bin` (+partitions, indexes, RLS) | startup / once via psql |
| `V2026_08_20_02__local_debit_bank_menu.sql` | Menu entry + group grants (this grant *is* the API's access gate) | startup / once via psql |
| `V2026_08_20_03__seed_uae_bin_bank.sql` | The 158 UAE BINs / 40 banks, attached to every `home_country_code='AE'` tenant | startup / once via psql |
| `add_or_correct_bins.sql` | Day-to-day: add or fix a handful of BINs | manually, as needed |
| `verify_local_debit_bank.sql` | Post-rebuild checks incl. the parity assertion | after the rebuild |

All three migrations are idempotent and safe to re-run.

> **Why bank names matter so much here:** they are resolved at *query* time by
> joining `ref_tenant_bin_bank`. That is the feature's strength — correcting the
> list re-labels all history with no rebuild — and precisely why the table is
> **DBA-seeded with no upload path**: one wrong list would re-attribute every
> bank across every historical month the instant it landed. A local-debit BIN
> that is absent simply shows under **"Other Banks"**, so totals stay correct.

---

## Step 1 — Deploy the code

Deploy the branch as usual. The build contains the controller, repository, batch
wiring and the React page.

If the environment runs migrations at startup (`spring.sql.init.mode=always`),
confirm `application-prod.properties` lists all three under
`spring.sql.init.schema-locations` — that file is gitignored, so edit it on the
target. `_03` must come **after** `_01`, which creates the table:

```
  classpath:db/migration/V2026_08_20_01__local_debit_bank_dashboard.sql,\
  classpath:db/migration/V2026_08_20_02__local_debit_bank_menu.sql,\
  classpath:db/migration/V2026_08_20_03__seed_uae_bin_bank.sql
```

Then restart — steps 2 and 3 happen automatically and you can skip to step 4.

## Step 2 — Apply the migrations (when `sql.init.mode=never`, the usual prod setting)

Run them in order:

```bash
psql -h <host> -p <port> -U <user> -d <db> -v ON_ERROR_STOP=1 -f V2026_08_20_01__local_debit_bank_dashboard.sql
```

```bash
psql -h <host> -p <port> -U <user> -d <db> -v ON_ERROR_STOP=1 -f V2026_08_20_02__local_debit_bank_menu.sql
```

```bash
psql -h <host> -p <port> -U <user> -d <db> -v ON_ERROR_STOP=1 -f V2026_08_20_03__seed_uae_bin_bank.sql
```

`_03` prints `INSERT 0 158` on a fresh database (and `INSERT 0 0` on a re-run —
it is `ON CONFLICT DO NOTHING`, so it never reverts a bank name someone has
since corrected).

**It self-selects the tenant** — no id to pass. Rows attach to every tenant with
`home_country_code = 'AE'`, which is right for UAE issuer BINs; a non-UAE tenant
gets nothing, and a UAE tenant added later picks the list up automatically.

Check what landed:

```bash
psql -h <host> -p <port> -U <user> -d <db> -c "SELECT tenant_id, COUNT(*) bins, COUNT(DISTINCT bank_name) banks FROM ref_tenant_bin_bank GROUP BY tenant_id;"
```

## Step 3 — Restart the backend

Registers the new API and picks up the menu grant.

## Step 4 — Rebuild history

The dashboard reads a summary table, so existing months must be recalculated
once. `fact_transaction` keeps the first 6 digits of the card number, so all
history is recoverable.

In the UI: **Administration → Data Migration → Summary Rebuild**, with the tenant
switcher on the target tenant. Or via the API (super-admin; rebuilds whichever
tenant you are switched into — omit the months to auto-detect the full range):

```bash
curl -X POST 'https://<host>/api/admin/migration/rebuild-summaries' -H 'Authorization: Bearer <token>' -H 'X-Tenant-Id: 1' -H 'Content-Type: application/json' -d '{"startMonth":"2025-01","endMonth":"2026-06","confirm":true}'
```

Progress: `GET /api/admin/migration/progress`.

> ⚠️ **Check the batch log afterwards.** If the schema migration was missed, the
> rebuild does *not* fail — it logs
> `[REBUILD] sum_daily_local_debit_bin rebuild skipped (non-fatal): ...`
> and carries on, leaving the dashboard empty. Grep for that line to be sure.

## Step 5 — Verify

```bash
psql -h <host> -p <port> -U <user> -d <db> -v tenant_id=1 -f verify_local_debit_bank.sql
```

Pass criteria:

1. BIN list shows **158 / 40**.
2. Summary has rows covering the expected date range.
3. **`diff_volume` and `diff_txns` are both 0** — the page reconciles exactly
   with the Card Type Dashboard's Domestic × Debit figures.
4. Per-month parity returns **no rows**.
5. Bank split looks sane; note the "Other Banks" share.
6. Unmatched BINs — the worklist for step 6 below.
7. Menu entry exists with at least one group grant (zero grants = the page is
   invisible and the API returns 403).

Then log in and open **Business → Local Debit Banks**.

---

## Ongoing maintenance

- **Improving coverage.** The page's "Unmatched BIN worklist" chip lists the
  highest-volume BINs with no bank. Put them into `add_or_correct_bins.sql` —
  edit the marked VALUES block — and run it:

  ```bash
  psql -h <host> -p <port> -U <user> -d <db> -v ON_ERROR_STOP=1 -v tenant_id=1 -f add_or_correct_bins.sql
  ```

  It upserts, refuses to commit if any BIN is not 6 digits, and prints the bank
  list so you can spot a misspelling. Effect is immediate and retrospective —
  **no rebuild** — because names resolve at query time.

- **Keep spelling consistent.** There is no bank master, so `RAKBANK` and
  `Rak Bank` render as two banks. Check first:
  `SELECT DISTINCT bank_name FROM ref_tenant_bin_bank WHERE tenant_id = <id>;`

- **BIN format.** Store 6 digits. Feeds expose only the first 6 clear PAN
  digits, so an 8-digit BIN can never match — use its 6-digit prefix. Where two
  8-digit BINs share a prefix under different banks, pick one deliberately; the
  data cannot tell them apart.

- **New months** need nothing: every ingest path (file upload, server file
  processing, backfill) and the summary rebuild all maintain the table.

- **Never** add a self-service upload for this table until a guarded flow exists
  (validation, diff preview, audit, rollback) — see the warning above.
