#!/usr/bin/env python3
"""
build_fresh_db_kit.py - generate the scripts that create a FRESH Acquira/Nexus database.

Why this exists
---------------
The application applies its SQL two ways: the ordered list in
`spring.sql.init.schema-locations` (schema.sql + 79 migrations, only when
SQL_INIT_MODE=always) and, since September 2026, migrations applied by hand with
psql. Nothing in the repository could build a *current* database from nothing.

What it does
------------
1. Works out the full ordered chain:
     schema-locations (in listed order) -> data.sql -> every other V2026_* migration by name,
   leaving out bundles, optional/rebuild scripts, editor copies and the scripts in OBSOLETE.
2. Runs that chain into a scratch database with ON_ERROR_STOP - any SQL error aborts.
3. Records every chain file in schema_migration_log, so the ledger of a fresh database is complete.
4. Exports the result as
     out/01_ddl.sql              schema only  (tables, partitions, indexes, constraints, RLS, functions)
     out/02_dml_reference.sql    data only    (reference data, rate cards, menus/RBAC, seed tenant + admin)
     out/00_create_fresh_db.sql  the meta script: refuses a non-empty database, then runs 01 and 02
                                 in ONE transaction and prints a verification summary
     out/chain_order.txt         the source files, in the order they were applied
5. Drops the scratch database (keep it with --keep).

The source of truth is the repository, not a developer database, so the kit carries no
local drift, no test tenants and no leftover tables.

Usage
-----
    python scripts/freshdb/build_fresh_db_kit.py
    python scripts/freshdb/build_fresh_db_kit.py --port 5433 --pg-bin "C:\\Program Files\\PostgreSQL\\18\\bin" --keep

Then, on the target server (an EMPTY database you have already created):
    psql -h <host> -U <user> -d <new_db> -f scripts/freshdb/out/00_create_fresh_db.sql
"""
import argparse
import datetime
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
RES = os.path.join(REPO, 'acquira-core', 'src', 'main', 'resources')
MIG = os.path.join(RES, 'db', 'migration')
OUT = os.path.join(HERE, 'out')

# Never part of a fresh build.
SKIP_MARKERS = ('CONSOLIDATED', 'OPTIONAL__', 'REBUILD_', ' - Copy')
# Scripts that are wrong or pointless on a fresh database (each with the reason).
OBSOLETE = {
    'V2026_02_21__gap_fixes.sql':
        'makes users.email NOT NULL + UNIQUE; the application has since made email optional '
        '(UserController stores NULL), so a user without an email could not be created. '
        'Also fails on line 30 (references users.id; the column is user_id).',
    'V2026_06_27_01__revenue_leakage_flags_reconcile.sql':
        'repairs databases created before schema.sql carried uq_revenue_leakage_flag; on a fresh '
        'database it only raises "relation already exists".',
}


def chain_from_properties():
    """Ordered entries of spring.sql.init.schema-locations (paths relative to resources/)."""
    lines = open(os.path.join(RES, 'application.properties'), encoding='utf-8').read().splitlines()
    out, on = [], False
    for ln in lines:
        s = ln.strip()
        if s.startswith('spring.sql.init.schema-locations='):
            on, s = True, s.split('=', 1)[1]
        elif on and not s.startswith('classpath:'):
            break
        if on:
            for part in s.split(','):
                part = part.strip().rstrip('\\').strip()
                if part.startswith('classpath:'):
                    out.append(part[len('classpath:'):])
            if not ln.rstrip().endswith('\\'):
                break
    if not out or out[0] != 'schema.sql':
        raise SystemExit('could not read spring.sql.init.schema-locations (expected schema.sql first)')
    return out


def build_order():
    chain = chain_from_properties()
    listed = {os.path.basename(x) for x in chain}
    later = sorted(f for f in os.listdir(MIG)
                   if f.endswith('.sql') and f.startswith('V') and f not in listed
                   and not any(m in f for m in SKIP_MARKERS) and f not in OBSOLETE)
    order = chain + ['data.sql'] + ['db/migration/' + f for f in later]
    missing = [x for x in order if not os.path.exists(os.path.join(RES, x))]
    if missing:
        raise SystemExit('chain references missing files: %s' % missing)
    return order, len(chain), len(later)


class Pg:
    def __init__(self, a):
        self.a = a
        self.env = dict(os.environ, PGPASSWORD=a.password, PGCLIENTENCODING='UTF8')
        exe = '.exe' if os.name == 'nt' else ''
        self.psql = os.path.join(a.pg_bin, 'psql' + exe) if a.pg_bin else 'psql'
        self.pg_dump = os.path.join(a.pg_bin, 'pg_dump' + exe) if a.pg_bin else 'pg_dump'

    def conn(self, db):
        return ['-h', self.a.host, '-p', str(self.a.port), '-U', self.a.user, '-d', db]

    def run(self, db, *args, check=True):
        r = subprocess.run([self.psql] + self.conn(db) + ['-q', '-v', 'ON_ERROR_STOP=1'] + list(args),
                           capture_output=True, env=self.env)
        if check and r.returncode != 0:
            raise SystemExit('psql failed (%s):\n%s' % (' '.join(args)[:120], r.stderr.decode('utf-8', 'replace')[:2000]))
        return r

    def scalar(self, db, sql):
        r = self.run(db, '-tA', '-c', sql)
        return r.stdout.decode('utf-8', 'replace').strip()

    def dump(self, db, *args):
        r = subprocess.run([self.pg_dump] + self.conn(db) + ['--no-owner', '--no-privileges', '--encoding=UTF8'] + list(args),
                           capture_output=True, env=self.env)
        if r.returncode != 0:
            raise SystemExit('pg_dump failed:\n%s' % r.stderr.decode('utf-8', 'replace')[:2000])
        return r.stdout.decode('utf-8', 'replace').replace('\r\n', '\n')


def sanitize(sql):
    """Remove lines that only newer psql/servers understand, so the files load on PostgreSQL 15+."""
    keep = []
    for ln in sql.split('\n'):
        if ln.startswith('\\restrict') or ln.startswith('\\unrestrict'):
            continue                      # psql meta-commands emitted by recent pg_dump builds
        if re.match(r'^SET (transaction_timeout|idle_in_transaction_session_timeout|lock_timeout|statement_timeout) = ', ln):
            continue                      # session timeouts are the operator's business; transaction_timeout is PG17+
        keep.append(ln)
    return '\n'.join(keep)


MASTER = r'''-- =============================================================================
-- 00_create_fresh_db.sql  -  create a FRESH Acquira/Nexus database (DDL + DML)
--
-- Generated %(stamp)s by scripts/freshdb/build_fresh_db_kit.py from the repository at
-- commit %(commit)s. Do not edit by hand - regenerate.
--
-- WHAT IT DOES
--   1. Refuses to run unless the target database is EMPTY (no tables in schema public).
--   2. In ONE transaction: 01_ddl.sql (schema) then 02_dml_reference.sql (reference data,
--      rate cards, menus and RBAC, the seed institution and the admin account).
--      Any error rolls everything back and leaves the database empty.
--   3. Prints a verification summary.
--
-- HOW TO RUN  (psql 12 or later; server PostgreSQL 15 or later)
--   createdb -h <host> -U <user> <new_db>
--   psql -h <host> -U <user> -d <new_db> -f 00_create_fresh_db.sql
--
--   Optional: set the admin password yourself instead of the seeded default
--   (pass a bcrypt hash; the account is still forced to change it at first login):
--   psql ... -v ADMIN_HASH='$2a$10$...' -f 00_create_fresh_db.sql
--
-- WHAT YOU GET
--   %(tables)s tables, %(indexes)s indexes, %(policies)s row-level-security policies, %(seqs)s sequences.
--   Reference data: %(countries)s countries, %(schemes)s card schemes, %(mcc)s MCC categories,
--   %(ic)s interchange rows, %(sf)s scheme-fee rows, %(fx)s daily FX rates, %(menus)s menus.
--   One seed institution (%(tenant)s) and one account (admin, password change forced at first login).
--   The migration ledger (schema_migration_log) lists all %(nfiles)s source scripts as applied.
--
-- WHAT IT DOES NOT LOAD
--   BIN ranges (ref_bin_range) - load them through BIN Management after go-live.
--   Any institution's merchants, transactions or summaries.
-- =============================================================================
\set ON_ERROR_STOP on
\pset pager off

SELECT COUNT(*) > 0 AS not_empty FROM pg_tables WHERE schemaname = 'public' \gset
\if :not_empty
    \echo
    \echo '*** REFUSING TO RUN: this database already contains tables in schema public.'
    \echo '*** This script only creates a FRESH database. Create an empty one and run it there.'
    \echo
    \quit
\endif

\echo '== creating schema (01_ddl.sql) and loading reference data (02_dml_reference.sql) =='
BEGIN;
\ir 01_ddl.sql
\ir 02_dml_reference.sql

\if :{?ADMIN_HASH}
    UPDATE public.users
       SET password_hash = CASE WHEN :'ADMIN_HASH' LIKE '{%%' THEN :'ADMIN_HASH' ELSE '{bcrypt}' || :'ADMIN_HASH' END,
           must_change_password = TRUE,
           password_changed_at = NULL
     WHERE username = 'admin';
    \echo '== admin password hash set from ADMIN_HASH =='
\else
    \echo '== NOTE: admin keeps the seeded default password; it must be changed at first login. =='
\endif
COMMIT;

SET search_path = public;
\echo
\echo '== verification =='
SELECT 'tables'            AS item, COUNT(*)::text AS value FROM pg_tables WHERE schemaname = 'public'
UNION ALL SELECT 'indexes',              COUNT(*)::text FROM pg_indexes  WHERE schemaname = 'public'
UNION ALL SELECT 'RLS policies',         COUNT(*)::text FROM pg_policies WHERE schemaname = 'public'
UNION ALL SELECT 'institutions',         COUNT(*)::text FROM tenant
UNION ALL SELECT 'users',                COUNT(*)::text FROM users
UNION ALL SELECT 'menus',                COUNT(*)::text FROM sys_menu
UNION ALL SELECT 'countries',            COUNT(*)::text FROM ref_country
UNION ALL SELECT 'card schemes',         COUNT(*)::text FROM ref_card_scheme
UNION ALL SELECT 'interchange rates',    COUNT(*)::text FROM interchange_rate_local
UNION ALL SELECT 'scheme fee rates',     COUNT(*)::text FROM scheme_fee_rate
UNION ALL SELECT 'daily FX rates',       COUNT(*)::text FROM fx_rate_daily
UNION ALL SELECT 'migrations in ledger', COUNT(*)::text FROM schema_migration_log
UNION ALL SELECT 'transactions',         COUNT(*)::text FROM fact_transaction;
\echo
\echo '== done. Start the application with SQL_INIT_MODE=never against this database. =='
'''


def main():
    ap = argparse.ArgumentParser(description='Generate the fresh-database kit (DDL + DML + meta script).')
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=5433)
    ap.add_argument('--user', default='postgres')
    ap.add_argument('--password', default=os.environ.get('PGPASSWORD', 'postgres'))
    ap.add_argument('--admin-db', default='postgres', help='database to connect to for CREATE/DROP DATABASE')
    ap.add_argument('--scratch-db', default='acquira_freshkit_build')
    ap.add_argument('--pg-bin', default=r'C:\Program Files\PostgreSQL\18\bin' if os.name == 'nt' else '',
                    help='folder with psql and pg_dump (pg_dump must not be older than the server)')
    ap.add_argument('--keep', action='store_true', help='keep the scratch database')
    a = ap.parse_args()
    pg = Pg(a)

    order, n_chain, n_later = build_order()
    print('chain: %d files (%d from schema-locations, data.sql, %d later migrations); %d obsolete excluded'
          % (len(order), n_chain, n_later, len(OBSOLETE)))

    if not re.match(r'^[a-z_][a-z0-9_]*$', a.scratch_db):
        raise SystemExit('scratch db name must be a plain identifier')
    pg.run(a.admin_db, '-c', 'DROP DATABASE IF EXISTS %s' % a.scratch_db)
    pg.run(a.admin_db, '-c', 'CREATE DATABASE %s' % a.scratch_db)
    try:
        for i, rel in enumerate(order, 1):
            r = pg.run(a.scratch_db, '-f', os.path.join(RES, rel), check=False)
            err = r.stderr.decode('utf-8', 'replace')
            if r.returncode != 0 or 'ERROR:' in err:
                raise SystemExit('FAILED at %d/%d %s\n%s' % (i, len(order), rel, err[:1500]))
        print('applied %d files with no SQL errors' % len(order))

        # A fresh database starts with a COMPLETE ledger (many older scripts never registered themselves).
        names = [os.path.basename(x) for x in order if x.startswith('db/migration/')]
        values = ','.join("('%s')" % n.replace("'", "''") for n in names)
        pg.run(a.scratch_db, '-c', 'INSERT INTO schema_migration_log (filename) VALUES %s ON CONFLICT (filename) DO NOTHING' % values)

        os.makedirs(OUT, exist_ok=True)
        ddl = sanitize(pg.dump(a.scratch_db, '--schema-only'))
        dml = sanitize(pg.dump(a.scratch_db, '--data-only', '--column-inserts', '--rows-per-insert=500'))

        s = lambda sql: pg.scalar(a.scratch_db, sql)
        try:
            commit = subprocess.run(['git', '-C', REPO, 'rev-parse', '--short', 'HEAD'], capture_output=True, text=True).stdout.strip() or 'unknown'
            dirty = subprocess.run(['git', '-C', REPO, 'status', '--porcelain', '--', 'acquira-core/src/main/resources'],
                                   capture_output=True, text=True).stdout.strip()
            if dirty:
                commit += ' + uncommitted SQL changes'
        except OSError:
            commit = 'unknown'
        stamp = datetime.datetime.now().strftime('%Y-%m-%d %H:%M')
        facts = dict(
            stamp=stamp, commit=commit, nfiles=len(order),
            tables=s("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public'"),
            indexes=s("SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public'"),
            policies=s("SELECT COUNT(*) FROM pg_policies WHERE schemaname='public'"),
            seqs=s("SELECT COUNT(*) FROM pg_sequences WHERE schemaname='public'"),
            countries=s('SELECT COUNT(*) FROM ref_country'), schemes=s('SELECT COUNT(*) FROM ref_card_scheme'),
            mcc=s('SELECT COUNT(*) FROM ref_mcc_category'), ic=s('SELECT COUNT(*) FROM interchange_rate_local'),
            sf=s('SELECT COUNT(*) FROM scheme_fee_rate'), fx=s('SELECT COUNT(*) FROM fx_rate_daily'),
            menus=s('SELECT COUNT(*) FROM sys_menu'),
            tenant=s("SELECT string_agg(bank_short_code || ' / ' || home_country_code || ' / ' || base_currency, ', ') FROM tenant"),
        )
        head = ('-- Generated %s by scripts/freshdb/build_fresh_db_kit.py (repository commit %s).\n'
                '-- Part of the fresh-database kit: run 00_create_fresh_db.sql, not this file on its own.\n\n' % (stamp, commit))
        w = lambda name, text: open(os.path.join(OUT, name), 'w', encoding='utf-8', newline='\n').write(text)
        w('01_ddl.sql', '-- 01_ddl.sql - schema only.\n' + head + ddl)
        w('02_dml_reference.sql', '-- 02_dml_reference.sql - reference and seed data only.\n' + head + dml)
        w('00_create_fresh_db.sql', MASTER % facts)
        w('chain_order.txt',
          '# Source scripts applied, in order, to build this kit (%s, commit %s).\n' % (stamp, commit)
          + '\n'.join(order) + '\n\n# Excluded as obsolete on a fresh database:\n'
          + '\n'.join('#   %s - %s' % kv for kv in OBSOLETE.items()) + '\n')
        for name in ('00_create_fresh_db.sql', '01_ddl.sql', '02_dml_reference.sql', 'chain_order.txt'):
            p = os.path.join(OUT, name)
            print('  wrote %-26s %9d bytes' % (name, os.path.getsize(p)))
        print('kit facts:', {k: v for k, v in facts.items() if k not in ('stamp', 'commit')})
    finally:
        if not a.keep:
            pg.run(a.admin_db, '-c', 'DROP DATABASE IF EXISTS %s' % a.scratch_db, check=False)


if __name__ == '__main__':
    main()
