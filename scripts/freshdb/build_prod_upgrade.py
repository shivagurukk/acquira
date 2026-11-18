#!/usr/bin/env python3
"""
build_prod_upgrade.py - generate ONE script that upgrades an EXISTING Acquira/Nexus database
(UAT or production) to the schema and reference data of this repository, and prove it works.

Why this exists
---------------
A live database cannot be rebuilt from the fresh-install kit: it holds institutions, merchants,
transactions and summaries. It has to be moved forward by the migrations it has not had yet.
The application has no migration tool, and many older migrations never recorded themselves, so
which of them a given database has is not knowable from git. This script therefore decides per
migration, on the target database itself, from its ledger table (schema_migration_log).

What the generated script does (out/acquira_prod_upgrade.sql)
-------------------------------------------------------------
1. Refuses an empty database (use acquira_fresh_install.sql there).
2. Refuses a database that lacks any table the 81-script init chain (spring.sql.init
   schema-locations + data.sql) creates and the current schema still has. Those scripts are not
   re-run by the upgrade: some of them seed rate cards that later migrations re-seed, so re-running
   them on a live database could revert rates.
3. Creates schema_migration_log if missing, then prints the migrations this database has not
   recorded. With -v DRY_RUN=1 it stops there and changes nothing.
4. Applies every later migration (db/migration/V*.sql outside the init chain, file-name order)
   that is not in the ledger, EACH IN ITS OWN TRANSACTION together with its ledger row. A failure
   stops the run; everything before it stays applied and recorded, so a re-run resumes there.
5. Prints a verification summary.

What this generator verifies (unless --no-verify)
-------------------------------------------------
  no-op       full current database, complete ledger: every migration skipped, nothing changes
  worst case  same database with every later migration removed from the ledger: all of them
              re-run, no SQL error, and the schema and every table's data stay identical
  realistic   a database at the Kubernetes branch (migrations up to --baseline-cutoff applied the
              way they were, by hand): after the upgrade its schema and data equal a fresh build
  dry run     -v DRY_RUN=1 lists the pending migrations and changes nothing
  refusals    an empty database and a database missing init-chain tables are refused

Usage
-----
    python scripts/freshdb/build_prod_upgrade.py
    python scripts/freshdb/build_prod_upgrade.py --port 5433 --no-verify
"""
import argparse
import datetime
import difflib
import hashlib
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_fresh_db_kit as kit  # noqa: E402  (shares the chain order and the psql wrapper)

OUT = kit.OUT
OUT_FILE = 'acquira_prod_upgrade.sql'

# A migration whose effect a LATER migration replaces. If the later one is already recorded on the
# target database, running the earlier one now (out of order) would undo it - so it is recorded
# as superseded instead of run. Found by re-running every migration alone on a current database
# (see the verification): these are the only ones whose re-run changes data other than
# rbac_version.
SUPERSEDED_BY = {
    # Draft Egypt Mastercard tiers with placeholder Premium/Elite rates ("DO NOT APPLY YET");
    # V2026_10_26_02 re-aligns Egypt Mastercard to the manual and replaces these rows.
    'V2026_08_30_01__mc_manual_rate_alignment_egypt.sql': ['V2026_10_26_02__mc_eg_manual_align.sql'],
}
# Tables whose content legitimately changes when a migration re-runs.
VOLATILE = {
    'rbac_version': 'cache-invalidation counter; every RBAC migration bumps it so pods reload menus',
}
LEDGER_DDL = ('CREATE TABLE IF NOT EXISTS public.schema_migration_log ('
              'filename VARCHAR(200) PRIMARY KEY, applied_at TIMESTAMP NOT NULL DEFAULT NOW());')


def read_sql(rel):
    t = open(os.path.join(kit.RES, rel), encoding='utf-8-sig').read()
    return t.replace('\r\n', '\n').replace('\r', '\n')


def strip_comments(sql):
    sql = re.sub(r'/\*.*?\*/', ' ', sql, flags=re.S)
    return '\n'.join(ln.split('--', 1)[0] for ln in sql.split('\n'))


def runs_outside_transaction(sql):
    """CREATE INDEX CONCURRENTLY cannot run in a transaction block; a script with its own
    BEGIN/COMMIT manages its transaction itself. Neither is wrapped."""
    code = strip_comments(sql)
    return bool(re.search(r'\bCONCURRENTLY\b', code, re.I) or
                re.search(r'^\s*(BEGIN|COMMIT|START\s+TRANSACTION)\s*;', code, re.I | re.M))


def plan():
    order, n_chain, _ = kit.build_order()
    init = order[:n_chain + 1]           # schema-locations entries + data.sql
    later = order[n_chain + 1:]          # hand-applied migrations, file-name order
    return init, later


def uncommitted(later):
    r = subprocess.run(['git', '-C', kit.REPO, 'status', '--porcelain', '--',
                        'acquira-core/src/main/resources/db/migration'], capture_output=True, text=True)
    names = {os.path.basename(ln[3:].strip().strip('"')) for ln in r.stdout.splitlines()}
    return [os.path.basename(x) for x in later if os.path.basename(x) in names]


# Migration scripts were first added to git in bulk by this commit, so for files it added the
# repository cannot tell whether the version applied to a database already recorded itself.
BULK_IMPORT_COMMIT = 'ece5e8e'


def may_be_unrecorded(rel):
    """True when a database could have this migration applied WITHOUT its ledger row: the file never
    records itself, gained the INSERT after it was first committed, or its history starts at the
    bulk import. Only files that recorded themselves from a later first commit are trusted."""
    name = os.path.basename(rel)
    if not ('schema_migration_log' in read_sql(rel) and name in read_sql(rel)):
        return True
    path = 'acquira-core/src/main/resources/' + rel
    adds = subprocess.run(['git', '-C', kit.REPO, 'log', '--diff-filter=A', '--format=%h', '--', path],
                          capture_output=True, text=True).stdout.split()
    if not adds:
        return False                      # not in git yet: brand new, records itself
    first = adds[-1]
    if subprocess.run(['git', '-C', kit.REPO, 'merge-base', '--is-ancestor', first, BULK_IMPORT_COMMIT]).returncode == 0:
        return True                       # added at (or before) the bulk import: history unknown
    t = subprocess.run(['git', '-C', kit.REPO, 'show', '%s:%s' % (first, path)], capture_output=True,
                       text=True, encoding='utf-8', errors='replace').stdout
    return not ('schema_migration_log' in t and name in t)


def base_tables(pg, db):
    out = pg.scalar(db, "SELECT string_agg(c.relname, ',' ORDER BY c.relname) FROM pg_class c "
                        "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        "WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND NOT c.relispartition")
    return set(out.split(',')) if out else set()


# ---------------------------------------------------------------------------------------------
# The generated script
# ---------------------------------------------------------------------------------------------
HEADER = r'''-- =============================================================================
-- acquira_prod_upgrade.sql  -  upgrade an EXISTING Acquira/Nexus database (UAT / prod)
--
-- Generated %(stamp)s by scripts/freshdb/build_prod_upgrade.py from the repository at
-- commit %(commit)s. Do not edit by hand - regenerate.
-- Contains %(n)d migrations (%(first)s .. %(last)s).%(dirty)s
--
-- BEFORE RUNNING: take a database snapshot, stop the application (or at least the batch
-- pod), and rehearse on a restored copy of that snapshot. See scripts/freshdb/PROD_UPGRADE.md.
--
-- HOW TO RUN  (psql 12 or later)
--   1. See what would be applied, change nothing:
--        psql -h <host> -U <user> -d <db> -v DRY_RUN=1 -f acquira_prod_upgrade.sql
--   2. Apply:
--        psql -h <host> -U <user> -d <db> -f acquira_prod_upgrade.sql
--   Optional: -v LOCK_TIMEOUT=120s   how long a statement may wait for a table lock (default 60s)
--
-- HOW IT DECIDES
--   A migration runs only if its file name is NOT in schema_migration_log. Each one runs in its
--   own transaction together with its ledger row, so a failure leaves the database at the last
--   migration that succeeded, and running the script again resumes from there.
--   Older migrations did not always record themselves, so a live database can have one without
--   its ledger row; it then runs again. Every migration for which that is possible was verified
--   to change nothing when re-run. A migration that a later one replaces (the draft Egypt
--   Mastercard tiers) is recorded without running when the later one is already applied.
--
-- WHAT IT DOES NOT DO
--   It does not re-run the init chain (schema.sql, data.sql and the 81 scripts in
--   spring.sql.init.schema-locations) - it refuses a database that lacks any of their tables.
--   It does not re-price transactions or rebuild summaries: after rate-card migrations, run the
--   summary rebuild with reprice for the affected months (PROD_UPGRADE.md, step 6).
-- =============================================================================
\set ON_ERROR_STOP on
\pset pager off
\set QUIET on
SET client_min_messages = warning;
\if :{?LOCK_TIMEOUT}
\else
    \set LOCK_TIMEOUT 60s
\endif
SET lock_timeout = :'LOCK_TIMEOUT';

-- ---- 1. refuse an empty database ----------------------------------------------------------
SELECT to_regclass('public.tenant') IS NULL AS acq_empty \gset
\if :acq_empty
    \echo
    \echo '*** REFUSING TO RUN: this database has no Acquira schema (table tenant is missing).'
    \echo '*** For a new database use acquira_fresh_install.sql instead.'
    \echo
    \quit
\endif

-- ---- 2. refuse a database older than the init chain ---------------------------------------
SELECT COUNT(*) AS acq_missing_n, COALESCE(string_agg(t, ', ' ORDER BY t), '') AS acq_missing
  FROM (VALUES %(baseline)s) AS b(t)
 WHERE to_regclass('public.' || t) IS NULL \gset
SELECT :acq_missing_n > 0 AS acq_refuse \gset
\if :acq_refuse
    \echo
    \echo '*** REFUSING TO RUN: this database is missing tables that the init chain creates:'
    \echo :acq_missing
    \echo '*** It is older than this upgrade supports. Bring it to the init-chain level first'
    \echo '*** (see PROD_UPGRADE.md, "Database older than the init chain") and run this again.'
    \echo
    \quit
\endif

-- ---- 3. ledger and plan -------------------------------------------------------------------
''' + LEDGER_DDL + r'''
\echo
\echo '== migrations this database has not recorded (in the order they will be handled) =='
\pset footer on
SELECT m.filename AS pending,
       CASE WHEN EXISTS (SELECT 1 FROM public.schema_migration_log l WHERE l.filename = ANY (m.superseded_by))
            THEN 'record only - superseded by a migration already applied'
            ELSE 'apply' END AS action
  FROM (VALUES %(plan_rows)s) AS m(filename, superseded_by)
 WHERE NOT EXISTS (SELECT 1 FROM public.schema_migration_log l WHERE l.filename = m.filename)
 ORDER BY m.filename;
\if :{?DRY_RUN}
    \echo '== DRY RUN: nothing was changed. =='
    \quit
\endif

-- ---- 4. apply ---------------------------------------------------------------------------
\echo '== applying =='
'''

BLOCK = '''
-- -------------------------------------------------------------------------------------------
-- [%(i)d/%(n)d] %(name)s%(note)s
-- -------------------------------------------------------------------------------------------
SET search_path = public;
SELECT EXISTS (SELECT 1 FROM public.schema_migration_log WHERE filename = '%(name)s') AS acq_done,
       %(superseded_sql)s AS acq_superseded \\gset
\\if :acq_done
\\echo '  [skip ] %(name)s'
\\elif :acq_superseded
\\echo '  [supersd] %(name)s - a later migration that replaces it is already applied; recorded, not run'
INSERT INTO public.schema_migration_log (filename) VALUES ('%(name)s') ON CONFLICT (filename) DO NOTHING;
\\else
\\echo '  [apply] %(name)s'
%(begin)s
%(body)s
;
INSERT INTO public.schema_migration_log (filename) VALUES ('%(name)s') ON CONFLICT (filename) DO NOTHING;
%(commit)s
\\endif
'''

FOOTER = r'''
-- ---- 5. verification ----------------------------------------------------------------------
SET search_path = public;
\echo
\echo '== verification =='
SELECT COUNT(*) AS still_pending
  FROM (VALUES %(names)s) AS m(filename)
 WHERE NOT EXISTS (SELECT 1 FROM public.schema_migration_log l WHERE l.filename = m.filename);
SELECT to_regclass('report_cache_version') IS NOT NULL AS report_cache_version,
       to_regclass('event_outbox')         IS NOT NULL AS event_outbox,
       to_regclass('shedlock')             IS NOT NULL AS shedlock,
       to_regclass('pdf_job_status')       IS NOT NULL AS pdf_job_status,
       to_regclass('fx_rate_daily')        IS NOT NULL AS fx_rate_daily;
\echo '== done. Deploy the new application version now (PROD_UPGRADE.md, step 5). =='
'''


def build_script(init, later, baseline, commit, stamp):
    names = [os.path.basename(x) for x in later]
    vals = lambda xs: ', '.join("('%s')" % x.replace("'", "''") for x in xs)
    dirty = uncommitted(later)
    for old, newer in SUPERSEDED_BY.items():
        if old not in names or any(n not in names or n <= old for n in newer):
            raise SystemExit('SUPERSEDED_BY entry %s -> %s does not match the chain' % (old, newer))
    arr = lambda xs: ("ARRAY[%s]::text[]" % ', '.join("'%s'" % x for x in xs)) if xs else 'ARRAY[]::text[]'
    plan_rows = ', '.join("('%s', %s)" % (n, arr(SUPERSEDED_BY.get(n, []))) for n in names)
    head = HEADER % dict(
        stamp=stamp, commit=commit, n=len(names), first=names[0], last=names[-1],
        dirty=('\n-- NOT YET COMMITTED in git when generated: %s.' % ', '.join(dirty)) if dirty else '',
        baseline=vals(sorted(baseline)), names=vals(names), plan_rows=plan_rows)
    parts = [head]
    for i, rel in enumerate(later, 1):
        name = os.path.basename(rel)
        body = read_sql(rel).rstrip()
        bare = runs_outside_transaction(body)
        sup = SUPERSEDED_BY.get(name)
        superseded_sql = ('EXISTS (SELECT 1 FROM public.schema_migration_log WHERE filename IN (%s))'
                          % ', '.join("'%s'" % x for x in sup)) if sup else 'false'
        parts.append(BLOCK % dict(
            i=i, n=len(later), name=name, body=body, superseded_sql=superseded_sql,
            note=('\n-- runs OUTSIDE a wrapping transaction (CREATE INDEX CONCURRENTLY or its own BEGIN/COMMIT)'
                  if bare else ''),
            begin='' if bare else 'BEGIN;', commit='' if bare else 'COMMIT;'))
    parts.append(FOOTER % dict(names=vals(names)))
    return ''.join(parts), dirty


# ---------------------------------------------------------------------------------------------
# Verification helpers
# ---------------------------------------------------------------------------------------------
def apply_files(pg, db, files):
    for i, rel in enumerate(files, 1):
        r = pg.run(db, '-f', os.path.join(kit.RES, rel), check=False)
        err = r.stderr.decode('utf-8', 'replace')
        if r.returncode != 0 or 'ERROR:' in err:
            raise SystemExit('FAILED building scratch database at %s\n%s' % (rel, err[:1500]))


def run_upgrade(pg, db, path, *extra):
    r = subprocess.run([pg.psql] + pg.conn(db) + list(extra) + ['-f', path], capture_output=True, env=pg.env)
    out, err = r.stdout.decode('utf-8', 'replace'), r.stderr.decode('utf-8', 'replace')
    return r.returncode, out, err


def schema_text(pg, db):
    """Schema dump, with every partition's index renamed to <parent index>@<partition>. PostgreSQL
    names those child indexes itself (..._idx, ..._idx1 when truncated names collide), and which one
    gets the suffix depends on creation order, not on what the index is."""
    txt = kit.sanitize(pg.dump(db, '--schema-only'))
    pairs = pg.scalar(db, """
        SELECT string_agg(ci.relname || '=' || pi.relname || '@' || ct.relname, ';')
          FROM pg_inherits i
          JOIN pg_class ci ON ci.oid = i.inhrelid AND ci.relkind = 'i'
          JOIN pg_class pi ON pi.oid = i.inhparent
          JOIN pg_index x ON x.indexrelid = ci.oid
          JOIN pg_class ct ON ct.oid = x.indrelid""")
    if pairs:
        canon = dict(p.split('=', 1) for p in pairs.split(';'))
        rx = re.compile(r'\b(%s)\b' % '|'.join(re.escape(k) for k in sorted(canon, key=len, reverse=True)))
        txt = rx.sub(lambda m: canon[m.group(1)], txt)
    keep = [ln for ln in txt.split('\n') if ln.strip() and not ln.startswith('--')]
    # pg_dump orders index statements by (the now renamed) index name; they are single-line and
    # order-independent, so compare them as a sorted set. Everything else keeps dump order.
    one_line = re.compile(r'^(ALTER INDEX |CREATE (UNIQUE )?INDEX )')
    return [ln for ln in keep if not one_line.match(ln)] + sorted(ln for ln in keep if one_line.match(ln))


def data_fingerprint(pg, db):
    """Count + md5 of every base table's rows (sorted), by CONTENT:
      - timestamp columns are left out (NOW() defaults differ between databases built at different times);
      - generated ids (serial / identity columns) are left out, and a single-column foreign key to one is
        replaced by the referenced row's content - ids depend on how often a sequence was consumed
        (an INSERT ... ON CONFLICT DO NOTHING re-run consumes values), never on what the data means;
      - the migration ledger and VOLATILE tables are left out."""
    cols = pg.scalar(db, r"""
        SELECT string_agg(c.relname || '.' || a.attname || '.' ||
                 CASE WHEN a.atttypid IN ('timestamp'::regtype, 'timestamptz'::regtype) THEN 't'
                      WHEN a.attidentity <> '' OR pg_get_expr(d.adbin, d.adrelid) LIKE 'nextval(%' THEN 's'
                      ELSE 'v' END, ';' ORDER BY c.relname, a.attnum)
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
          LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
         WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND NOT c.relispartition""")
    fks = pg.scalar(db, """
        SELECT string_agg(c.relname || '.' || a.attname || '>' || r.relname || '.' || ra.attname, ';')
          FROM pg_constraint k
          JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_class r ON r.oid = k.confrelid
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.conkey[1]
          JOIN pg_attribute ra ON ra.attrelid = r.oid AND ra.attnum = k.confkey[1]
         WHERE k.contype = 'f' AND n.nspname = 'public' AND array_length(k.conkey, 1) = 1""")
    tables = {}
    for item in cols.split(';'):
        t, c, kind = item.rsplit('.', 2)
        tables.setdefault(t, []).append((c, kind))
    fk = dict(x.split('>') for x in fks.split(';')) if fks else {}
    surrogate = {(t, c) for t, cs in tables.items() for c, k in cs if k == 's'}
    q = kit_ident

    def plain(t):
        return [c for c, k in tables[t] if k == 'v' and '%s.%s' % (t, c) not in fk]

    fp = {}
    for t, cs in sorted(tables.items()):
        if t == 'schema_migration_log' or t in VOLATILE:
            continue
        exprs = []
        for c, kind in cs:
            if kind != 'v':
                continue
            ref = fk.get('%s.%s' % (t, c))
            if ref:
                rt, rc = ref.split('.')
                if (rt, rc) in surrogate and rt in tables:
                    rcols = ', '.join('r.' + q(x) for x in plain(rt)) or 'NULL'
                    exprs.append('(SELECT ROW(%s)::text FROM public.%s r WHERE r.%s = x.%s)' % (rcols, q(rt), q(rc), q(c)))
                    continue
            exprs.append('x.' + q(c))
        if not exprs:
            fp[t] = pg.scalar(db, 'SELECT COUNT(*) FROM public.%s' % q(t))
            continue
        fp[t] = pg.scalar(db, "SELECT COUNT(*) || '/' || COALESCE(md5(string_agg(r, '|' ORDER BY r)), '-') "
                              "FROM (SELECT ROW(%s)::text AS r FROM public.%s x) s" % (', '.join(exprs), q(t)))
    return fp


def kit_ident(name):
    return '"%s"' % name.replace('"', '""')


def compare(label, pg, db_a, db_b):
    sa, sb = schema_text(pg, db_a), schema_text(pg, db_b)
    problems = []
    if sa != sb:
        diff = list(difflib.unified_diff(sa, sb, db_a, db_b, lineterm='', n=1))
        problems.append('schema differs (%d diff lines):\n%s' % (len(diff), '\n'.join(diff[:60])))
    fa, fb = data_fingerprint(pg, db_a), data_fingerprint(pg, db_b)
    bad = sorted(t for t in set(fa) | set(fb) if fa.get(t) != fb.get(t) and t not in VOLATILE)
    if bad:
        problems.append('data differs in %d table(s): %s' % (len(bad), ', '.join(
            '%s (%s vs %s)' % (t, fa.get(t, 'missing'), fb.get(t, 'missing')) for t in bad[:15])))
    print('  %-11s %s' % (label, 'PASS - schema and data identical' if not problems else 'FAIL'))
    return problems


def expect_clean(label, code, out, err):
    if code != 0 or 'ERROR:' in err:
        raise SystemExit('%s: the upgrade script failed (exit %s)\n%s\n%s' % (label, code, err[-2500:], out[-1500:]))


def main():
    ap = argparse.ArgumentParser(description='Generate (and verify) the single-file upgrade script for an existing database.')
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=5433)
    ap.add_argument('--user', default='postgres')
    ap.add_argument('--password', default=os.environ.get('PGPASSWORD', 'postgres'))
    ap.add_argument('--admin-db', default='postgres')
    ap.add_argument('--pg-bin', default=r'C:\Program Files\PostgreSQL\18\bin' if os.name == 'nt' else '')
    ap.add_argument('--baseline-cutoff', default='V2026_09_26_03',
                    help='"realistic" check: the database has every later migration up to this prefix '
                         '(default: the deploy/kubernetes-aws branch)')
    ap.add_argument('--no-verify', action='store_true')
    ap.add_argument('--keep', action='store_true', help='keep the scratch databases')
    a = ap.parse_args()
    a.scratch_db = 'x'  # Pg() does not use it
    pg = kit.Pg(a)

    init, later = plan()
    print('init chain %d files, later migrations %d' % (len(init), len(later)))
    full, base, empty = 'acq_upg_full', 'acq_upg_base', 'acq_upg_empty'
    dbs = [full, base, empty]
    for d in dbs:
        pg.run(a.admin_db, '-c', 'DROP DATABASE IF EXISTS %s' % d)
    try:
        # Build the current schema the way the fresh kit does, noting which tables the init chain makes.
        pg.run(a.admin_db, '-c', 'CREATE DATABASE %s' % full)
        apply_files(pg, full, init)
        after_init = base_tables(pg, full)
        apply_files(pg, full, later)
        baseline = after_init & base_tables(pg, full)
        print('built current schema; baseline check covers %d init-chain tables' % len(baseline))

        commit = subprocess.run(['git', '-C', kit.REPO, 'rev-parse', '--short', 'HEAD'],
                                capture_output=True, text=True).stdout.strip() or 'unknown'
        stamp = datetime.datetime.now().strftime('%Y-%m-%d %H:%M')
        text, dirty = build_script(init, later, baseline, commit, stamp)
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, OUT_FILE)
        open(path, 'w', encoding='utf-8', newline='\n').write(text)
        print('wrote %s (%d bytes, sha256 %s)' % (OUT_FILE, os.path.getsize(path),
                                                   hashlib.sha256(text.encode('utf-8')).hexdigest()[:16]))
        if dirty:
            print('NOTE: includes %d migration(s) not committed in git: %s' % (len(dirty), ', '.join(dirty)))
        if a.no_verify:
            return

        names = [os.path.basename(x) for x in later]
        in_list = ','.join("'%s'" % n for n in names)
        problems = []
        print('verification:')

        # no-op: complete ledger -> everything skipped, nothing changes
        pg.run(full, '-c', 'INSERT INTO schema_migration_log (filename) SELECT unnest(ARRAY[%s]) ON CONFLICT DO NOTHING' % in_list)
        pg.run(a.admin_db, '-c', 'CREATE DATABASE %s TEMPLATE %s' % (base, full))   # untouched copy to compare against
        code, out, err = run_upgrade(pg, full, path)
        expect_clean('no-op', code, out, err)
        applied = out.count('[apply]')
        print('  no-op       %s - %d applied, %d skipped' % ('PASS' if applied == 0 else 'FAIL', applied, out.count('[skip ]')))
        if applied:
            problems.append('no-op run applied %d migration(s)' % applied)

        # worst case: the database has every migration, but no ledger row for any migration that may
        # have been applied without one -> all of those re-run on a database that already has them
        gaps = [os.path.basename(x) for x in later if may_be_unrecorded(x)]
        pg.run(full, '-c', 'DELETE FROM schema_migration_log WHERE filename IN (%s)' % ','.join("'%s'" % n for n in gaps))
        code, out, err = run_upgrade(pg, full, path)
        expect_clean('worst case', code, out, err)
        print('  worst case  %d of %d migrations may lack a ledger row on a live database; re-ran %d with no SQL error'
              % (len(gaps), len(names), out.count('[apply]')))
        if out.count('[apply]') != len(gaps):
            problems.append('worst case applied %d, expected %d' % (out.count('[apply]'), len(gaps)))
        problems += compare('worst case', pg, base, full)

        # superseded: the replacing migration is recorded, the replaced one is not -> recorded, not run
        for old in SUPERSEDED_BY:
            pg.run(full, '-c', "DELETE FROM schema_migration_log WHERE filename = '%s'" % old)
        code, out, err = run_upgrade(pg, full, path)
        expect_clean('superseded', code, out, err)
        ok = out.count('[supersd]') == len(SUPERSEDED_BY) and '[apply]' not in out
        print('  superseded  %s - %d superseded migration(s) recorded without running'
              % ('PASS' if ok else 'FAIL', out.count('[supersd]')))
        if not ok:
            problems.append('superseded migrations were not handled as record-only')
        problems += compare('superseded', pg, base, full)

        # realistic: database at the Kubernetes branch, migrations applied by hand as they were
        pg.run(a.admin_db, '-c', 'DROP DATABASE %s' % base)
        pg.run(a.admin_db, '-c', 'CREATE DATABASE %s' % base)
        apply_files(pg, base, init)
        older = [x for x in later if os.path.basename(x) <= a.baseline_cutoff + '~']
        apply_files(pg, base, older)
        before = pg.scalar(base, 'SELECT COUNT(*) FROM schema_migration_log')
        code, out, err = run_upgrade(pg, base, path, '-v', 'DRY_RUN=1')
        expect_clean('dry run', code, out, err)
        after = pg.scalar(base, 'SELECT COUNT(*) FROM schema_migration_log')
        dry_ok = before == after and 'DRY RUN' in out and '[apply]' not in out
        print('  dry run     %s - listed pending migrations, ledger unchanged (%s rows)' % ('PASS' if dry_ok else 'FAIL', after))
        if not dry_ok:
            problems.append('dry run changed the database or did not stop')
        code, out, err = run_upgrade(pg, base, path)
        expect_clean('realistic', code, out, err)
        print('  realistic   database at %s + %d hand-applied migrations: upgrade applied %d, skipped %d'
              % (a.baseline_cutoff, len(older), out.count('[apply]'), out.count('[skip ]')))
        # the fresh build ran the init chain + every later migration once, in order
        pg.run(full, '-c', 'INSERT INTO schema_migration_log (filename) SELECT unnest(ARRAY[%s]) ON CONFLICT DO NOTHING' % in_list)
        problems += compare('realistic', pg, full, base)

        # refusals
        pg.run(a.admin_db, '-c', 'CREATE DATABASE %s' % empty)
        code, out, err = run_upgrade(pg, empty, path)
        ok = 'REFUSING' in out and pg.scalar(empty, "SELECT COUNT(*) FROM pg_tables WHERE schemaname='public'") == '0'
        print('  refusal     %s - empty database refused, nothing created' % ('PASS' if ok else 'FAIL'))
        if not ok:
            problems.append('empty database was not refused')
        pg.run(empty, '-c', 'CREATE TABLE tenant (id int)')
        code, out, err = run_upgrade(pg, empty, path)
        ok = 'missing tables that the init chain creates' in out and '[apply]' not in out
        print('  refusal     %s - database older than the init chain refused' % ('PASS' if ok else 'FAIL'))
        if not ok:
            problems.append('database older than the init chain was not refused')

        if problems:
            raise SystemExit('VERIFICATION FAILED:\n- ' + '\n- '.join(problems))
        print('ALL CHECKS PASSED')
    finally:
        if not a.keep:
            for d in dbs:
                pg.run(a.admin_db, '-c', 'DROP DATABASE IF EXISTS %s' % d, check=False)


if __name__ == '__main__':
    main()
