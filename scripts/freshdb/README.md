# Fresh database kit

Creates a new, empty-of-business-data database for the application: full schema (DDL) plus
reference and seed data (DML), in one guarded script.

## Files

| File | What it is |
|---|---|
| `build_fresh_db_kit.py` | Generator. Rebuilds everything in `out/` from the repository's SQL. |
| `out/00_create_fresh_db.sql` | **The meta script.** Run this one. Refuses a non-empty database, then runs the two files below in a single transaction and prints a verification summary. |
| `out/01_ddl.sql` | Schema only: tables, partitions, indexes, constraints, row-level security, functions, sequences. |
| `out/02_dml_reference.sql` | Data only: reference tables, rate cards, menus and RBAC, the seed institution and the admin account, the migration ledger. |
| `out/chain_order.txt` | The source scripts the kit was built from, in the order they were applied. |

## Create a database

```
createdb -h <host> -U <user> <new_db>
psql -h <host> -U <user> -d <new_db> -f scripts/freshdb/out/00_create_fresh_db.sql
```

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
```

Options: `--host`, `--port`, `--user`, `--password`, `--pg-bin`, `--scratch-db`, `--keep`.
It needs a PostgreSQL server it can create and drop a scratch database on, and a `pg_dump` at
least as new as that server. Any SQL error in the chain stops the build and names the script.

## How it was verified (2026-11-12)

- All 162 source scripts applied with no SQL errors.
- The meta script was run on a new empty database: 213 tables, 677 indexes, 57 RLS policies.
  A second run was refused.
- The result matches the scratch build object for object, and matches the reference data of the
  development database row for row on every rate and reference table.
- The application (Spring Boot 4.1.1, JDK 25) was started on that database and passed 35 checks:
  forced admin password change, three institutions created, merchant and transaction files
  ingested, fees priced correctly, dashboards matching the data, PDF generation, daily FX rates.
