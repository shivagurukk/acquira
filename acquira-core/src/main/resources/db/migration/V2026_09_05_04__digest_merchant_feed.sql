-- ============================================================================
-- V2026_09_05_04: Daily Digest — merchant master as its own required feed.
--
-- The gate previously tracked TRX / DCC / RENTAL; merchant master is now a
-- fourth, separately-toggleable requirement. Presence = dim_merchant has at
-- least one row for the tenant (the master is an occasional upsert feed, not
-- a daily one) OR a merchant load completed after the business day — so a
-- tenant that has ever loaded its merchant master passes immediately, while
-- a brand-new tenant's digest correctly waits for it.
--
-- Default TRUE to match the other require_* columns: existing tenants all
-- have dim_merchant rows, so nothing already flowing is blocked.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS require_merchant BOOLEAN NOT NULL DEFAULT TRUE;
