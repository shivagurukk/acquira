-- ============================================================================
-- V2026_07_04_02: User account expiry
--   Optional per-user cutoff timestamp. After it passes, the user is blocked at
--   login (AuthController) and auto-deactivated (is_active flipped off). NULL =
--   never expires. Settable/editable in Admin > User Management (Create/Edit).
--
-- This column is also added in schema.sql's users ALTER block (which runs on
-- every startup), so on this platform the column already lands without listing
-- this file in spring.sql.init.schema-locations. This standalone file exists for
-- the record and for any environment that applies db/migration/*.sql directly.
-- Idempotent and safe to run repeatedly on dev and prod.
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS account_expires_at TIMESTAMP;
