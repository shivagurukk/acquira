-- ============================================================================
-- V2026_10_03_05: Spring Batch 6 (Spring Boot 4) job-instance sequence.
--
-- Spring Batch 6 reads job-instance ids from BATCH_JOB_INSTANCE_SEQ; Batch 5
-- used BATCH_JOB_SEQ. Without this every job launch (uploads, integration
-- pulls, PDF batches) fails on the Boot 4 build with
--   duplicate key value violates unique constraint "batch_job_instance_pkey".
--
-- NOT a rename (the upstream migration-postgresql.sql renames): the app runs
-- with spring.batch.jdbc.initialize-schema=always, so the first start of the
-- Boot 4 jar already CREATEs batch_job_instance_seq at 1 — a later rename then
-- fails with "relation already exists" and the new sequence stays at 1. This
-- script is correct in every order:
--   - jar never started : creates the sequence, positions it past the old one
--   - jar started first : repositions the auto-created sequence
--   - re-run            : no-op (never moves the sequence backwards)
-- batch_job_seq is left in place — schema.sql still references it and the
-- Boot 3.5 build needs it. Rolling BACK to 3.5 after jobs ran on Boot 4 needs
-- the mirror image: setval('batch_job_seq', <max job_instance_id>).
--
-- Idempotent; splitter-safe (no $$). Applied once via psql like the other
-- recent scripts (not in the startup schema-locations list).
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS batch_job_instance_seq MAXVALUE 9223372036854775807 NO CYCLE;

SELECT setval('batch_job_instance_seq', GREATEST(
    (SELECT COALESCE(MAX(job_instance_id), 0) FROM batch_job_instance),
    (SELECT COALESCE(MAX(last_value), 0) FROM pg_sequences
      WHERE schemaname = current_schema()
        AND sequencename IN ('batch_job_seq', 'batch_job_instance_seq')),
    1));

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_03_05__spring_batch6_sequence.sql')
ON CONFLICT (filename) DO NOTHING;
