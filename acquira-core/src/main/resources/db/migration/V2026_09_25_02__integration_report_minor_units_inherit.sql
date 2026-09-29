-- ============================================================================
-- V2026_09_25_02: integration_report.amounts_minor_units — inherit the tenant
-- format by default.
--
-- (Renamed from V2026_09_25_01 to avoid clashing with the digest allow_today
--  migration that already took that number/date; numbering is a filename
--  convention here, self-registered in schema_migration_log, not Flyway.)
--
-- WHY
-- ---
-- The column defaulted to FALSE ("amounts are already final decimals, do not
-- divide") and the Integration Hub UI has no control for it, so every report
-- created from the UI was silently frozen at FALSE. For a CMM tenant (minor-
-- unit feed) the DB pull therefore skipped the /100 division that the file
-- upload applies: on 2026-09-24 the pulled day loaded with volumes 100x the
-- file's (avg amount 9,298 vs 93; MSF unchanged). IntegrationPullService
-- already treats NULL as "use tenant.input_format" — the same rule as
-- FileUploadService.inputTypeForTenant — so NULL is the correct default.
--
-- DATA: rows set to FALSE by the 2026-08-08 grandfathering keep their value
-- (that migration deliberately froze pre-existing behaviour). Rows created
-- AFTER it with FALSE got that value from the UI default, never from a
-- decision, so they are reset to NULL (inherit). An AMS tenant's report
-- behaves identically either way; a CMM tenant's report is fixed.
--
-- Idempotent; splitter-safe (no $$).
-- ============================================================================

ALTER TABLE integration_report ALTER COLUMN amounts_minor_units DROP DEFAULT;

UPDATE integration_report r
SET amounts_minor_units = NULL
WHERE r.amounts_minor_units = FALSE
  AND r.created_at >= DATE '2026-08-08'
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_09_25_02__integration_report_minor_units_inherit.sql');

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_25_02__integration_report_minor_units_inherit.sql') ON CONFLICT (filename) DO NOTHING;
