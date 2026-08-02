-- =====================================================================
-- schema-snapshot.sql
-- Read-only schema snapshot as one text line per object, sorted, so two
-- runs (prod vs local) can be diffed directly. Safe to run on prod/RDS.
--
-- Run against RDS from your machine:
--   psql -h <rds-endpoint> -p 5432 -U <user> -d <db> -At \
--        -f schema-snapshot.sql -o prod_snapshot.txt
-- Run against local:
--   psql -h 127.0.0.1 -p 5433 -U postgres -d acquira_expected -At \
--        -f schema-snapshot.sql -o local_snapshot.txt
-- Or paste the SELECT into pgAdmin's query tool and export as CSV/text.
-- =====================================================================

SELECT kind || ' | ' || name || ' | ' || def AS line
FROM (
    -- Tables (r=ordinary, p=partitioned parent) and views
    SELECT 'A_TABLE' AS kind,
           n.nspname || '.' || c.relname AS name,
           CASE c.relkind
               WHEN 'r' THEN 'table'
               WHEN 'p' THEN 'partitioned table'
               WHEN 'v' THEN 'view'
               WHEN 'm' THEN 'materialized view'
           END AS def
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p', 'v', 'm')

    UNION ALL

    -- Columns: type, nullability, default
    SELECT 'B_COLUMN',
           table_name || '.' || column_name,
           data_type
           || COALESCE('(' || character_maximum_length || ')', '')
           || COALESCE('(' || numeric_precision || ',' || numeric_scale || ')', '')
           || ' nullable=' || is_nullable
           || ' default=' || COALESCE(column_default, '-')
    FROM information_schema.columns
    WHERE table_schema = 'public'

    UNION ALL

    -- Indexes (full definition)
    SELECT 'C_INDEX',
           schemaname || '.' || indexname,
           indexdef
    FROM pg_indexes
    WHERE schemaname = 'public'

    UNION ALL

    -- Constraints: PK, FK, unique, check
    SELECT 'D_CONSTRAINT',
           conrelid::regclass::text || '.' || conname,
           pg_get_constraintdef(c.oid)
    FROM pg_constraint c
    JOIN pg_namespace n ON n.oid = c.connamespace
    WHERE n.nspname = 'public'

    UNION ALL

    -- Partitions: which child belongs to which parent, with bounds
    SELECT 'E_PARTITION',
           i.inhparent::regclass::text || ' -> ' || i.inhrelid::regclass::text,
           COALESCE(pg_get_expr(ch.relpartbound, ch.oid), 'inherited')
    FROM pg_inherits i
    JOIN pg_class ch ON ch.oid = i.inhrelid
    JOIN pg_namespace n ON n.oid = ch.relnamespace
    WHERE n.nspname = 'public'

    UNION ALL

    -- Sequences
    SELECT 'F_SEQUENCE',
           sequence_schema || '.' || sequence_name,
           'start=' || start_value || ' inc=' || increment
    FROM information_schema.sequences
    WHERE sequence_schema = 'public'

    UNION ALL

    -- Functions / procedures (signature only, body hash to spot changes)
    SELECT 'G_FUNCTION',
           n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
           'returns ' || pg_get_function_result(p.oid)
           || ' md5=' || md5(pg_get_functiondef(p.oid))
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'

    UNION ALL

    -- Triggers
    SELECT 'H_TRIGGER',
           event_object_table || '.' || trigger_name,
           action_timing || ' ' || event_manipulation || ' -> ' || action_statement
    FROM information_schema.triggers
    WHERE trigger_schema = 'public'
) s
ORDER BY 1, 2, 3;
