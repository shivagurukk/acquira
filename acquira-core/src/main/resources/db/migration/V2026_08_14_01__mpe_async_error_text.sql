-- ============================================================================
-- V2026_08_14_01: MPE deliveries load asynchronously (T068 fix).
--
-- The T068 full replacement is millions of records; staging it inside the
-- upload HTTP request outran the 600s ingress timeout / JVM heap in UAT and
-- died with no response and no logs. Uploads now register the delivery as
-- status PROCESSING and load on a background thread; terminal states are
-- STAGED / COUNT_MISMATCH / FAILED. error_text carries the failure cause or
-- the count-mismatch details so operators see WHY without server logs.
--
-- Idempotent + splitter-safe.
-- ============================================================================

ALTER TABLE mpe_file ADD COLUMN IF NOT EXISTS error_text TEXT;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_14_01__mpe_async_error_text.sql') ON CONFLICT (filename) DO NOTHING;
