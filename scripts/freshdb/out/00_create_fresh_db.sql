-- =============================================================================
-- 00_create_fresh_db.sql  -  create a FRESH Acquira/Nexus database (DDL + DML)
--
-- Generated 2026-11-12 11:16 by scripts/freshdb/build_fresh_db_kit.py from the repository at
-- commit 49b891c + uncommitted SQL changes. Do not edit by hand - regenerate.
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
--   213 tables, 677 indexes, 57 row-level-security policies, 129 sequences.
--   Reference data: 236 countries, 21 card schemes, 296 MCC categories,
--   4869 interchange rows, 144 scheme-fee rows, 1833 daily FX rates, 77 menus.
--   One seed institution (ACQ / AE / AED) and one account (admin, password change forced at first login).
--   The migration ledger (schema_migration_log) lists all 162 source scripts as applied.
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
       SET password_hash = CASE WHEN :'ADMIN_HASH' LIKE '{%' THEN :'ADMIN_HASH' ELSE '{bcrypt}' || :'ADMIN_HASH' END,
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
