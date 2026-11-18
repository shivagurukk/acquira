# Database kit: fresh install and production upgrade

Two single-file scripts, both generated from the repository's SQL:

- **`out/acquira_fresh_install.sql`** creates a NEW database: full schema (DDL) plus reference and
  seed data (DML), in one guarded transaction.
- **`out/acquira_prod_upgrade.sql`** upgrades an EXISTING database (UAT, production) and keeps all
  its data. How to run it on production: [PROD_UPGRADE.md](PROD_UPGRADE.md).

## Files

| File | What it is |
|---|---|
| `build_fresh_db_kit.py` | Generator for the fresh-install files below. |
| `build_prod_upgrade.py` | Generator for the upgrade script; also verifies it (no-op, worst case, superseded, realistic, dry run, refusals) and fails if any check fails. Run it after `build_fresh_db_kit.py`. |
| `out/acquira_fresh_install.sql` | **New database, one file.** The meta script below with `01` and `02` inlined. |
| `out/00_create_fresh_db.sql` | The same as a meta script: refuses a non-empty database, then runs the two files below in a single transaction and prints a verification summary. |
| `out/01_ddl.sql` | Schema only: tables, partitions, indexes, constraints, row-level security, functions, sequences. |
| `out/02_dml_reference.sql` | Data only: reference tables, rate cards, menus and RBAC, the seed institution and the admin account, the migration ledger. |
| `out/acquira_prod_upgrade.sql` | **Existing database, one file.** Applies each migration the database's `schema_migration_log` does not record, one transaction each. |
| `out/chain_order.txt` | The source scripts the kit was built from, in the order they were applied. |

## Upgrade an existing database

```
psql -h <host> -U <user> -d <db> -v DRY_RUN=1 -f scripts/freshdb/out/acquira_prod_upgrade.sql   # plan only
psql -h <host> -U <user> -d <db> -f scripts/freshdb/out/acquira_prod_upgrade.sql                # apply
```

It covers the 84 migrations applied by hand after the init chain (`schema.sql`, `data.sql` and the
81 scripts in `spring.sql.init.schema-locations`), which it does not re-run and whose tables it
requires. Many older migrations never recorded themselves in `schema_migration_log`, so a live
database can have a migration without its ledger row; the upgrade then runs it again. The
generator therefore re-runs every migration alone on a current database during development, and
its verification proves that re-running all 37 that may lack a ledger row changes nothing. The one
migration whose re-run would undo a later one, the draft Egypt Mastercard tiers
(`V2026_08_30_01`), is recorded without running when its replacement (`V2026_10_26_02`) is
already applied. Comparisons ignore timestamps, generated ids, partition index names and the
`rbac_version` counter.

## Create a database

```
createdb -h <host> -U <user> <new_db>
psql -h <host> -U <user> -d <new_db> -f scripts/freshdb/out/acquira_fresh_install.sql
```
(`00_create_fresh_db.sql` does the same with `01` and `02` as separate files beside it.)

Needs psql 12 or later and PostgreSQL 15 or later (the schema uses `NULLS NOT DISTINCT`).
It was generated and tested on PostgreSQL 18.1; it has not been run on other versions.

The run takes a few seconds. If anything fails the transaction rolls back and the database
stays empty. Running it against a database that already has tables does nothing but print a refusal.

Then start the application against the new database with `SQL_INIT_MODE=never`.

### Admin account

The kit seeds one account, `admin`, exactly as `data.sql` does: a known default password and
`must_change_password = true`, so the first login is forced to set a new one. To avoid having a
known password even briefly, pass your own bcrypt hash:

```
psql ... -v ADMIN_HASH='$2a$10$....' -f scripts/freshdb/out/00_create_fresh_db.sql
```

## What the database contains

- The seed institution `ACQ` (UAE, AED). Create the real institutions through Bank Setup.
- Reference data: countries and currencies, card schemes, MCC categories, destination and
  terminal-channel maps, ticket buckets, BIN product tiers.
- Rate cards: interchange and scheme fees for the countries the repository carries, including the
  Mastercard UAE changes effective 1 October 2026 and the Jaywan scheme.
- Daily EGP/USD rates from the seed migration.
- Menus, permissions and RBAC grants.
- `schema_migration_log` listing every source script as applied, so later hand-applied migrations
  start from a complete ledger.

## What it does not contain

- **BIN ranges** (`ref_bin_range`, about 800k rows). They come from scheme files; load them through
  BIN Management. Until then, BIN-based card typing finds no matches and falls back to its defaults.
- Merchants, transactions, summaries, users other than `admin`, audit or batch history.
- The function `ensure_tenant_partitions()`. It is only defined in `REBUILD_TENANT_LIST_PARTITIONING.sql`,
  which is not part of the standard build, so the `tenant-partitions` provisioning step logs a
  failure when an institution is created. Ingest creates the partitions it needs and is not affected.

## How the kit is built

The application has no migration tool. It applies SQL two ways: the ordered list in
`spring.sql.init.schema-locations` (only when `SQL_INIT_MODE=always`), and migrations applied by
hand with psql. That list stops at early September 2026, so on its own it no longer produces a
current schema. The generator therefore applies, in this order:

1. `schema-locations`, in its listed order (`schema.sql`, `schema_extras.sql`, 79 migrations)
2. `data.sql`
3. every other `V2026_*` migration, by file name

into a scratch database with stop-on-error, completes the ledger, and exports the result with
`pg_dump` as separate schema and data files.

Left out on purpose:

| Script | Why |
|---|---|
| `*CONSOLIDATED*`, `OPTIONAL__*`, `REBUILD_*`, `* - Copy.sql` | Bundles of other scripts, optional or destructive one-offs, editor copies. |
| `V2026_02_21__gap_fixes.sql` | Makes `users.email` NOT NULL and UNIQUE. The application has since made email optional, so users without an email could not be created. It also fails on a column that no longer exists. |
| `V2026_06_27_01__revenue_leakage_flags_reconcile.sql` | Repairs databases older than the current `schema.sql`; on a fresh one it only raises "already exists". |

## Regenerate after adding a migration

```
python scripts/freshdb/build_fresh_db_kit.py
python scripts/freshdb/build_prod_upgrade.py
```
If the new migration should not be re-run on a database that already has a later one replacing
it, add it to `SUPERSEDED_BY` in `build_prod_upgrade.py`. The second command's "worst case" check
fails when a re-run changes data.

Options: `--host`, `--port`, `--user`, `--password`, `--pg-bin`, `--scratch-db`, `--keep`.
It needs a PostgreSQL server it can create and drop a scratch database on, and a `pg_dump` at
least as new as that server. Any SQL error in the chain stops the build and names the script.

## How it was verified

Regenerated 2026-11-14 from 166 scripts (84 later migrations, including five MIS/RBAC migrations
not yet committed): no SQL errors, 232 tables, 724 indexes, 65 RLS policies. The upgrade script
passed all its generator checks the same day. The application run below was done on the
2026-11-12 build and has not been repeated on this one.

### 2026-11-12

- All 162 source scripts applied with no SQL errors.
- The meta script was run on a new empty database: 213 tables, 677 indexes, 57 RLS policies.
  A second run was refused.
- The result matches the scratch build object for object, and matches the reference data of the
  development database row for row on every rate and reference table.
- The application (Spring Boot 4.1.1, JDK 25) was started on that database and passed 35 checks:
  forced admin password change, three institutions created, merchant and transaction files
  ingested, fees priced correctly, dashboards matching the data, PDF generation, daily FX rates.
