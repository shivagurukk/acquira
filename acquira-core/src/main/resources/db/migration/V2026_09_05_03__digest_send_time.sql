-- ============================================================================
-- V2026_09_05_03: Daily Digest — scheduled send time.
--
-- send_not_before: tenant-local wall-clock time (tenant's locale.timezone
-- setting, else server zone) before which a ready digest is HELD. The feed
-- gates still apply — this only delays a day that is already complete, so
-- "send at 08:00" yields one predictable morning email instead of one at
-- whatever minute the last feed landed. NULL = send as soon as ready
-- (the behaviour every tenant has today, so no seeding needed).
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS send_not_before TIME NULL;
