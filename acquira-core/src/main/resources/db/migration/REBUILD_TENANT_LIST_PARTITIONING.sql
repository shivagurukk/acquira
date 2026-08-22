-- ============================================================================
-- REBUILD_TENANT_LIST_PARTITIONING.sql          *** PSQL-ONLY — NEVER list in
--                                                   spring.sql.init.schema-locations ***
--
-- Converts fact_transaction + the 9 date-partitioned summary tables from
--   PARTITION BY RANGE (date)                        [shared across tenants]
-- to
--   PARTITION BY LIST (tenant_id)                    [one physical table set
--     -> PARTITION BY RANGE (date)                    per tenant]
--
-- Result: every tenant's transactions and summaries live in physically
-- separate partitions (separate files, indexes, vacuum), while every query in
-- the codebase keeps working unchanged — WHERE tenant_id = ? prunes straight
-- to that tenant's partitions.
--
-- Verified preconditions (Phase 0, 2026-07-11):
--   * JPA entities use a single @Id over the composite physical PK — adding
--     tenant_id to the PK requires no Java changes.
--   * Every summary ON CONFLICT target already begins (tenant_id, business_date
--     ...) — all remain valid arbiters under the new partition keys.
--
-- Run:      psql -U postgres -d postgres -f REBUILD_TENANT_LIST_PARTITIONING.sql
-- Window:   requires app STOPPED (replicas:1 -> single clean stop/start).
-- Space:    ~2x the size of fact_transaction free during the copy.
-- Rollback: old tables are kept as <name>_old_pretenant; to roll back, swap
--           the renames back. Drop the _old_pretenant tables only after the
--           system has been verified for a few days.
--
-- Order of operations per table:
--   1. CREATE <t>_new (LIKE <t> INCLUDING DEFAULTS) PARTITION BY LIST (tenant_id)
--   2. New PK = old PK columns + tenant_id (+ date col) — partition keys must
--      be in the PK
--   3. Copy old UNIQUE constraints verbatim (all already contain tenant+date)
--   4. Create per-tenant LIST partitions, each RANGE-sub-partitioned by date,
--      covering [min(data) .. now + 1 period]; plus a DEFAULT catch-all
--   5. Copy data month-by-month (batched; keeps txns short)
--   6. Recreate secondary indexes (after copy — faster bulk load)
--   7. Verify row counts old vs new — ABORT on mismatch
--   8. Swap renames in one short transaction; re-own serial sequences to the
--      new table so a later DROP of the old table can't kill them
-- ============================================================================

\set ON_ERROR_STOP on

-- ─────────────────────────────────────────────────────────────────────────
-- 0. ensure_tenant_partitions(): the ONE function that creates a tenant's
--    partition set. Used by:
--      * this script (via the internal rebuild procedure)
--      * PartitionMaintenanceService (per tenant per year, at startup/upload)
--      * the 'tenant-partitions' provisioning script (on tenant creation)
-- ─────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION ensure_tenant_partitions(
    p_tenant_id BIGINT,
    p_from_year INT DEFAULT NULL,
    p_to_year   INT DEFAULT NULL
) RETURNS void LANGUAGE plpgsql AS $FN$
DECLARE
    v_from INT := COALESCE(p_from_year, EXTRACT(YEAR FROM CURRENT_DATE)::INT);
    v_to   INT := COALESCE(p_to_year,   EXTRACT(YEAR FROM CURRENT_DATE)::INT + 1);
    r RECORD;
    v_year INT; v_month INT;
    v_tpart TEXT; v_sub TEXT;
    v_start DATE; v_end DATE;
BEGIN
    FOR r IN SELECT * FROM (VALUES
        ('fact_transaction',               'fact_transaction',     'payment_date',  'month'),
        ('sum_daily_merchant',             'sum_daily_merchant',   'business_date', 'year'),
        ('sum_daily_merchant_attribute',   'sum_daily_merch_attr', 'business_date', 'year'),
        ('sum_daily_merchant_destination', 'sum_daily_merchant_destination', 'business_date', 'year'),
        ('sum_daily_terminal',             'sum_daily_terminal',   'business_date', 'year'),
        ('sum_daily_scheme',               'sum_daily_scheme',     'business_date', 'year'),
        ('sum_daily_channel',              'sum_daily_channel',    'business_date', 'year'),
        ('sum_daily_bank',                 'sum_daily_bank',       'business_date', 'year'),
        ('sum_daily_finance',              'sum_daily_finance',    'business_date', 'year'),
        ('sum_daily_insight',              'sum_daily_insight',    'business_date', 'year')
    ) AS t(tbl, prefix, datecol, gran)
    LOOP
        -- Only act when the parent is LIST-partitioned (post-rebuild state).
        CONTINUE WHEN NOT EXISTS (
            SELECT 1 FROM pg_partitioned_table pt
            JOIN pg_class c ON c.oid = pt.partrelid
            WHERE c.relname = r.tbl AND pt.partstrat = 'l');

        v_tpart := r.prefix || '_t' || p_tenant_id;
        IF to_regclass(v_tpart) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF %I FOR VALUES IN (%s) PARTITION BY RANGE (%I)',
                v_tpart, r.tbl, p_tenant_id, r.datecol);
        END IF;

        FOR v_year IN v_from..v_to LOOP
            IF r.gran = 'month' THEN
                FOR v_month IN 1..12 LOOP
                    v_sub   := format('%s_y%sm%s', v_tpart, v_year, lpad(v_month::TEXT, 2, '0'));
                    v_start := make_date(v_year, v_month, 1);
                    v_end   := v_start + INTERVAL '1 month';
                    IF to_regclass(v_sub) IS NULL THEN
                        EXECUTE format(
                            'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                            v_sub, v_tpart, v_start, v_end);
                        PERFORM tune_partition_autovacuum(v_sub, r.tbl = 'fact_transaction');
                    END IF;
                END LOOP;
            ELSE
                v_sub   := format('%s_y%s', v_tpart, v_year);
                v_start := make_date(v_year, 1, 1);
                v_end   := make_date(v_year + 1, 1, 1);
                IF to_regclass(v_sub) IS NULL THEN
                    EXECUTE format(
                        'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                        v_sub, v_tpart, v_start, v_end);
                    PERFORM tune_partition_autovacuum(v_sub, FALSE);
                END IF;
            END IF;
        END LOOP;
    END LOOP;
END $FN$;

-- Autovacuum tuning mirrors PartitionMaintenanceService.applyAutovacuumTuning.
CREATE OR REPLACE FUNCTION tune_partition_autovacuum(p_partition TEXT, p_heavy_fact BOOLEAN)
RETURNS void LANGUAGE plpgsql AS $FN$
BEGIN
    IF p_heavy_fact THEN
        EXECUTE format('ALTER TABLE %I SET (autovacuum_vacuum_scale_factor = 0.01, '
            || 'autovacuum_vacuum_threshold = 50000, autovacuum_analyze_scale_factor = 0.005, '
            || 'autovacuum_analyze_threshold = 50000, autovacuum_vacuum_cost_limit = 3000)', p_partition);
        BEGIN
            EXECUTE format('ALTER TABLE %I SET (autovacuum_vacuum_insert_scale_factor = 0.01, '
                || 'autovacuum_vacuum_insert_threshold = 50000)', p_partition);
        EXCEPTION WHEN OTHERS THEN NULL; -- PG < 13
        END;
    ELSE
        EXECUTE format('ALTER TABLE %I SET (autovacuum_vacuum_scale_factor = 0.02, '
            || 'autovacuum_vacuum_threshold = 20000, autovacuum_analyze_scale_factor = 0.01, '
            || 'autovacuum_analyze_threshold = 20000, fillfactor = 90)', p_partition);
    END IF;
END $FN$;

-- ─────────────────────────────────────────────────────────────────────────
-- 1. The rebuild procedure — builds <t>_new fully, copies, verifies, swaps.
-- ─────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE PROCEDURE rebuild_as_tenant_list(
    p_table TEXT, p_prefix TEXT, p_date_col TEXT, p_gran TEXT
) LANGUAGE plpgsql AS $PR$
DECLARE
    v_new TEXT := p_table || '_new';
    v_old TEXT := p_table || '_old_pretenant';
    v_pk_cols TEXT;
    r RECORD;
    v_min DATE; v_max DATE;
    v_from_year INT; v_to_year INT;
    v_tid BIGINT;
    v_tpart TEXT; v_sub TEXT; v_start DATE; v_end DATE;
    v_year INT; v_month INT;
    v_m DATE;
    v_cnt_old BIGINT; v_cnt_new BIGINT;
    v_def TEXT; v_seq TEXT;
BEGIN
    IF to_regclass(p_table) IS NULL THEN
        RAISE NOTICE '%: table does not exist — skipping', p_table;
        RETURN;
    END IF;
    -- Already converted?
    IF EXISTS (SELECT 1 FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid
               WHERE c.relname = p_table AND pt.partstrat = 'l') THEN
        RAISE NOTICE '%: already LIST-partitioned — skipping', p_table;
        RETURN;
    END IF;
    IF to_regclass(v_new) IS NOT NULL THEN
        RAISE EXCEPTION '%: % already exists — clean up a previous attempt first', p_table, v_new;
    END IF;

    RAISE NOTICE '=== % : rebuild starting ===', p_table;

    -- 1. New parent, same columns/defaults, LIST on tenant_id.
    EXECUTE format('CREATE TABLE %I (LIKE %I INCLUDING DEFAULTS) PARTITION BY LIST (tenant_id)',
                   v_new, p_table);

    -- 2. New PK = old PK columns + tenant_id + date col. The date col is in
    --    both branches (old PKs already contain it), so dedupe by attname with
    --    MIN(o); tenant_id sorts to the end via its high ordering key.
    SELECT string_agg(quote_ident(att), ', ' ORDER BY o) INTO v_pk_cols FROM (
        SELECT att, MIN(o) AS o FROM (
            SELECT a.attname AS att, MIN(u.ord) AS o
            FROM (
                SELECT unnest(conkey) AS attnum, generate_subscripts(conkey, 1) AS ord
                FROM pg_constraint WHERE conrelid = p_table::regclass AND contype = 'p'
            ) u
            JOIN pg_attribute a ON a.attrelid = p_table::regclass AND a.attnum = u.attnum
            GROUP BY a.attname
            UNION ALL
            SELECT 'tenant_id', 998
            UNION ALL
            SELECT p_date_col, 999
        ) raw GROUP BY att
    ) cols;
    EXECUTE format('ALTER TABLE %I ADD PRIMARY KEY (%s)', v_new, v_pk_cols);
    RAISE NOTICE '%: new PK (%)', p_table, v_pk_cols;

    -- 3. Copy UNIQUE constraints verbatim (all already include tenant+date).
    --    Names are regenerated short + deterministic: appending a suffix to an
    --    already-63-char name silently truncates back to the original and
    --    collides with the old table's constraint (caught in live testing).
    FOR r IN SELECT conname, pg_get_constraintdef(oid) AS def
             FROM pg_constraint WHERE conrelid = p_table::regclass AND contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I %s',
                       v_new, 'uq_' || left(md5(p_table || '.' || r.conname), 16), r.def);
        RAISE NOTICE '%: unique % copied', p_table, r.conname;
    END LOOP;

    -- 4. Per-tenant partitions covering the data range + one period ahead.
    EXECUTE format('SELECT MIN(%I)::date, MAX(%I)::date FROM %I', p_date_col, p_date_col, p_table)
        INTO v_min, v_max;
    v_from_year := COALESCE(EXTRACT(YEAR FROM v_min)::INT, EXTRACT(YEAR FROM CURRENT_DATE)::INT);
    v_to_year   := GREATEST(COALESCE(EXTRACT(YEAR FROM v_max)::INT, 0),
                            EXTRACT(YEAR FROM CURRENT_DATE)::INT) + 1;

    FOR v_tid IN SELECT tenant_id FROM tenant ORDER BY tenant_id LOOP
        v_tpart := p_prefix || '_t' || v_tid || '_new';
        EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES IN (%s) PARTITION BY RANGE (%I)',
                       v_tpart, v_new, v_tid, p_date_col);
        FOR v_year IN v_from_year..v_to_year LOOP
            IF p_gran = 'month' THEN
                FOR v_month IN 1..12 LOOP
                    v_sub := format('%s_y%sm%s', v_tpart, v_year, lpad(v_month::TEXT, 2, '0'));
                    v_start := make_date(v_year, v_month, 1); v_end := v_start + INTERVAL '1 month';
                    EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                                   v_sub, v_tpart, v_start, v_end);
                    PERFORM tune_partition_autovacuum(v_sub, p_gran = 'month');
                END LOOP;
            ELSE
                v_sub := format('%s_y%s', v_tpart, v_year);
                v_start := make_date(v_year, 1, 1); v_end := make_date(v_year + 1, 1, 1);
                EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                               v_sub, v_tpart, v_start, v_end);
                PERFORM tune_partition_autovacuum(v_sub, FALSE);
            END IF;
        END LOOP;
    END LOOP;
    EXECUTE format('CREATE TABLE %I PARTITION OF %I DEFAULT', p_prefix || '_tdefault_new', v_new);
    RAISE NOTICE '%: partitions created (years % .. %)', p_table, v_from_year, v_to_year;

    -- 5. Copy data in month batches (short transactions via CALL-level commits
    --    are not available inside a procedure invoked in a txn; batches still
    --    bound memory/WAL per statement).
    IF v_min IS NOT NULL THEN
        v_m := date_trunc('month', v_min)::date;
        WHILE v_m <= v_max LOOP
            EXECUTE format('INSERT INTO %I SELECT * FROM %I WHERE %I >= %L AND %I < %L',
                           v_new, p_table, p_date_col, v_m, p_date_col, v_m + INTERVAL '1 month');
            RAISE NOTICE '%: copied month %', p_table, to_char(v_m, 'YYYY-MM');
            v_m := (v_m + INTERVAL '1 month')::date;
        END LOOP;
    END IF;

    -- 6. Secondary indexes (skip those backing PK/unique constraints).
    FOR r IN
        SELECT i.indexname, i.indexdef FROM pg_indexes i
        WHERE i.schemaname = 'public' AND i.tablename = p_table
          AND i.indexname NOT IN (
              SELECT conname FROM pg_constraint
              WHERE conrelid = p_table::regclass AND contype IN ('p', 'u'))
    LOOP
        v_def := replace(r.indexdef, ' ON public.' || p_table || ' ', ' ON public.' || v_new || ' ');
        v_def := replace(v_def, ' INDEX ' || r.indexname || ' ON ',
                         ' INDEX ' || 'ix_' || left(md5(p_table || '.' || r.indexname), 16) || ' ON ');
        BEGIN
            EXECUTE v_def;
            RAISE NOTICE '%: index % recreated', p_table, r.indexname;
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING '%: index % skipped (%)', p_table, r.indexname, SQLERRM;
        END;
    END LOOP;

    -- 7. Verify.
    EXECUTE format('SELECT COUNT(*) FROM %I', p_table) INTO v_cnt_old;
    EXECUTE format('SELECT COUNT(*) FROM %I', v_new)   INTO v_cnt_new;
    IF v_cnt_old <> v_cnt_new THEN
        RAISE EXCEPTION '%: ROW COUNT MISMATCH old=% new=% — NOT swapping', p_table, v_cnt_old, v_cnt_new;
    END IF;
    RAISE NOTICE '%: verified % rows', p_table, v_cnt_new;

    -- 8. Swap + re-own serial sequences to the new table.
    EXECUTE format('ALTER TABLE %I RENAME TO %I', p_table, v_old);
    EXECUTE format('ALTER TABLE %I RENAME TO %I', v_new, p_table);
    FOR r IN SELECT a.attname FROM pg_attribute a
             WHERE a.attrelid = v_old::regclass AND a.attnum > 0 AND NOT a.attisdropped
    LOOP
        v_seq := pg_get_serial_sequence(v_old, r.attname);
        IF v_seq IS NOT NULL THEN
            EXECUTE format('ALTER SEQUENCE %s OWNED BY %I.%I', v_seq, p_table, r.attname);
        END IF;
    END LOOP;
    -- Rename tenant list partitions to drop the _new suffix (cosmetic; keeps
    -- names aligned with ensure_tenant_partitions()).
    FOR r IN SELECT c.relname FROM pg_inherits h
             JOIN pg_class c ON c.oid = h.inhrelid
             WHERE h.inhparent = p_table::regclass AND c.relname LIKE '%\_new'
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME TO %I', r.relname, left(r.relname, length(r.relname) - 4));
    END LOOP;
    FOR r IN SELECT c.relname FROM pg_class c
             WHERE c.relname LIKE p_prefix || '\_t%\_new\_y%' ESCAPE '\'
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME TO %I', r.relname, replace(r.relname, '_new_y', '_y'));
    END LOOP;
    EXECUTE format('ANALYZE %I', p_table);
    RAISE NOTICE '=== % : DONE (old kept as %) ===', p_table, v_old;
END $PR$;

-- ─────────────────────────────────────────────────────────────────────────
-- 2. Execute — fact first, then the summaries. Each CALL is its own txn.
-- ─────────────────────────────────────────────────────────────────────────
CALL rebuild_as_tenant_list('fact_transaction',               'fact_transaction',     'payment_date',  'month');
CALL rebuild_as_tenant_list('sum_daily_merchant',             'sum_daily_merchant',   'business_date', 'year');
CALL rebuild_as_tenant_list('sum_daily_merchant_attribute',   'sum_daily_merch_attr', 'business_date', 'year');
CALL rebuild_as_tenant_list('sum_daily_merchant_destination', 'sum_daily_merchant_destination', 'business_date', 'year');
CALL rebuild_as_tenant_list('sum_daily_terminal',             'sum_daily_terminal',   'business_date', 'year');
CALL rebuild_as_tenant_list('sum_daily_scheme',               'sum_daily_scheme',     'business_date', 'year');
CALL rebuild_as_tenant_list('sum_daily_channel',              'sum_daily_channel',    'business_date', 'year');
CALL rebuild_as_tenant_list('sum_daily_bank',                 'sum_daily_bank',       'business_date', 'year');
CALL rebuild_as_tenant_list('sum_daily_finance',              'sum_daily_finance',    'business_date', 'year');
CALL rebuild_as_tenant_list('sum_daily_insight',              'sum_daily_insight',    'business_date', 'year');

-- Register in the migration registry (table exists since V2026_07_11_03).
INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('REBUILD_TENANT_LIST_PARTITIONING.sql (psql-only)',
        'Converted fact_transaction + 9 summary tables to per-tenant LIST->RANGE composite partitioning',
        TRUE, FALSE, 'psql')
ON CONFLICT (migration_name) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────
-- AFTER VERIFYING (days later), reclaim space:
--   DROP TABLE fact_transaction_old_pretenant;
--   DROP TABLE sum_daily_merchant_old_pretenant;  ... etc.
-- ─────────────────────────────────────────────────────────────────────────
