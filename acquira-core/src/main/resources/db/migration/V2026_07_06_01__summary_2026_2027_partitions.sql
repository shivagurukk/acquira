-- ============================================================================
-- V2026_07_06_01: Create 2026/2027 yearly partitions for the summary tables
--   that were seeded in schema.sql only up to _y2025.
--
-- ⚠ RUN THIS WITH psql DIRECTLY — do NOT add it to spring.sql.init.schema-
--   locations. It uses a DO $mig$ ... $mig$ block, and Spring's sql.init
--   script splitter mis-parses dollar-quoted blocks (the same reason the
--   V2026_06_29_* trio had to be rewritten to single-statement SQL). psql
--   handles dollar quoting correctly.
--
--     dev : psql -h 127.0.0.1 -p 5433 -U postgres    -d postgres    -f V2026_07_06_01__summary_2026_2027_partitions.sql
--     prod: psql -h 127.0.0.1 -p 5432 -U acquira_user -d acquira_db  -f V2026_07_06_01__summary_2026_2027_partitions.sql
--
-- WHY: schema.sql created yearly RANGE partitions for the sum_daily_* tables
-- but only through _y2025, with a catch-all _default. sum_daily_merchant and
-- sum_daily_merchant_attribute already got _y2026 in schema.sql; the other six
-- did NOT. So every 2026 row for:
--     sum_daily_terminal, sum_daily_bank, sum_daily_insight,
--     sum_daily_scheme,   sum_daily_channel, sum_daily_finance
-- landed in the un-prunable *_default heap. Any 2026-window query (e.g. the
-- Executive → Volume & Revenue screen, which reads sum_daily_terminal three
-- times per page load: rows + COUNT + totals) then scans/aggregates the whole
-- default partition with no range pruning — the "takes long to load" symptom.
--
-- PartitionMaintenanceService DOES list these tables and creates y2026/y2027,
-- but only from ensurePartitionsStep at the START of a transaction ingest job.
-- Rows that were loaded before that step ran (or via backfill/migration) are
-- already stranded in _default. This migration fixes the existing data AND
-- guarantees the forward partitions exist even on a box that hasn't ingested
-- 2026 data through the job yet.
--
-- Postgres constraint: you cannot CREATE a _y2026 partition while overlapping
-- rows sit in the DEFAULT partition (ERROR: updated partition constraint for
-- default partition would be violated). So the procedure per table is:
--     1. DETACH default
--     2. CREATE _y2026, _y2027 (bounded ranges)
--     3. MOVE 2026/2027 rows out of the (now standalone) default table into
--        the parent — routed to the correct partition automatically
--     4. RE-ATTACH default
-- All steps are guarded so re-running is a no-op.
--
-- Wrap in a single transaction so a failure leaves the tables consistent
-- (either all detach/create/move/attach steps commit, or none do).
-- ============================================================================

BEGIN;

DO $mig$
DECLARE
    rec          RECORD;
    parent       TEXT;
    prefix       TEXT;
    def_name     TEXT;
    p2026        TEXT;
    p2027        TEXT;
    has_2026     BOOLEAN;
    has_2027     BOOLEAN;
    def_attached BOOLEAN;
BEGIN
    FOR rec IN
        SELECT * FROM (VALUES
            ('sum_daily_terminal', 'sum_daily_terminal'),
            ('sum_daily_bank',     'sum_daily_bank'),
            ('sum_daily_insight',  'sum_daily_insight'),
            ('sum_daily_scheme',   'sum_daily_scheme'),
            ('sum_daily_channel',  'sum_daily_channel'),
            ('sum_daily_finance',  'sum_daily_finance')
        ) AS t(parent_tbl, pfx)
    LOOP
        parent   := rec.parent_tbl;
        prefix   := rec.pfx;
        def_name := prefix || '_default';
        p2026    := prefix || '_y2026';
        p2027    := prefix || '_y2027';

        -- Skip entirely if the parent table doesn't exist (defensive).
        IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = parent) THEN
            RAISE NOTICE 'skip %, parent table missing', parent;
            CONTINUE;
        END IF;

        SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = p2026) INTO has_2026;
        SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = p2027) INTO has_2027;

        -- If both target partitions already exist, nothing to do for this table.
        IF has_2026 AND has_2027 THEN
            RAISE NOTICE 'skip %, y2026 + y2027 already present', parent;
            CONTINUE;
        END IF;

        -- Is the default partition currently attached to the parent?
        SELECT EXISTS (
            SELECT 1
            FROM pg_inherits i
            JOIN pg_class c  ON c.oid = i.inhrelid
            JOIN pg_class p  ON p.oid = i.inhparent
            WHERE p.relname = parent AND c.relname = def_name
        ) INTO def_attached;

        -- 1. Detach default so the new bounded partitions can be created without
        --    the "would violate default partition constraint" error.
        IF def_attached AND EXISTS (SELECT 1 FROM pg_tables WHERE tablename = def_name) THEN
            EXECUTE format('ALTER TABLE %I DETACH PARTITION %I', parent, def_name);
        END IF;

        -- 2. Create the bounded 2026 / 2027 partitions.
        IF NOT has_2026 THEN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (''2026-01-01'') TO (''2027-01-01'')',
                p2026, parent);
        END IF;
        IF NOT has_2027 THEN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (''2027-01-01'') TO (''2028-01-01'')',
                p2027, parent);
        END IF;

        -- 3. Move any 2026/2027 rows the (now standalone) default table caught
        --    back into the parent, which routes them to the right partition.
        IF EXISTS (SELECT 1 FROM pg_tables WHERE tablename = def_name) THEN
            EXECUTE format(
                'INSERT INTO %I SELECT * FROM %I WHERE business_date >= ''2026-01-01'' AND business_date < ''2028-01-01''',
                parent, def_name);
            EXECUTE format(
                'DELETE FROM %I WHERE business_date >= ''2026-01-01'' AND business_date < ''2028-01-01''',
                def_name);
        END IF;

        -- 4. Re-attach the default so future out-of-range rows still have a home.
        IF EXISTS (SELECT 1 FROM pg_tables WHERE tablename = def_name) THEN
            IF NOT EXISTS (
                SELECT 1 FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = parent AND c.relname = def_name
            ) THEN
                EXECUTE format('ALTER TABLE %I ATTACH PARTITION %I DEFAULT', parent, def_name);
            END IF;
        END IF;

        RAISE NOTICE 'partitioned %: created y2026/y2027 and drained default', parent;
    END LOOP;
END
$mig$;

COMMIT;

-- Refresh planner stats on the newly-populated partitions so the first query
-- after deploy picks the indexed, partition-pruned plan (not a stale seq scan).
ANALYZE sum_daily_terminal;
ANALYZE sum_daily_bank;
ANALYZE sum_daily_insight;
ANALYZE sum_daily_scheme;
ANALYZE sum_daily_channel;
ANALYZE sum_daily_finance;
