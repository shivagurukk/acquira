-- ======================================================
-- V2026_02_21_05: Fix Partition Strategy (#21)
-- Detach yearly fact_transaction partitions and create monthly ones.
-- Only runs if yearly partitions exist.
-- ======================================================

-- Step 1: Check if yearly partitions exist and detach them
-- We move data from yearly -> monthly via the DEFAULT partition.
-- The PartitionMaintenanceService will create monthly partitions on next startup.

DO $$
DECLARE
    part_name TEXT;
BEGIN
    -- Detach yearly partitions (fact_transaction_y2024, fact_transaction_y2025, etc.)
    FOR part_name IN
        SELECT tablename FROM pg_tables
        WHERE tablename ~ '^fact_transaction_y\d{4}$'
        AND schemaname = 'public'
    LOOP
        RAISE NOTICE 'Detaching yearly partition: %', part_name;
        -- Move data to default partition by detaching
        EXECUTE format('ALTER TABLE fact_transaction DETACH PARTITION %I', part_name);
        -- Copy data into the main table (will land in default or matching monthly partition)
        EXECUTE format('INSERT INTO fact_transaction SELECT * FROM %I ON CONFLICT DO NOTHING', part_name);
        -- Drop the old yearly partition
        EXECUTE format('DROP TABLE IF EXISTS %I', part_name);
        RAISE NOTICE 'Migrated and dropped: %', part_name;
    END LOOP;

    -- Ensure default partition exists
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'fact_transaction_default') THEN
        EXECUTE 'CREATE TABLE fact_transaction_default PARTITION OF fact_transaction DEFAULT';
    END IF;
END $$;
