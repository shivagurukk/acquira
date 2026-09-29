-- ============================================================================
-- V2026_09_25_01: Daily Digest — allow the CURRENT business date.
--
-- Phase 1 (I-2) deliberately sent only for days strictly BEFORE the tenant-local
-- today, to avoid emailing part-day intraday data. Per directive (2026-09-25),
-- the digest must run for TODAY's business date, not yesterday's. This adds a
-- per-tenant switch:
--   allow_today = TRUE  → the sweep may send once today's business date has its
--                         required feeds (the feed gate + quiet period +
--                         send_not_before still guard completeness).
--   allow_today = FALSE → the old "completed days only" behaviour.
-- Defaults TRUE so the directive takes effect for every existing tenant; a bank
-- that wants the conservative behaviour turns it off on the admin screen.
--
-- Idempotent; splitter-safe (no $$). Applied once via psql like the other digest
-- scripts (V2026_09_04_01 is not in the startup list either).
-- ============================================================================

ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS allow_today BOOLEAN NOT NULL DEFAULT TRUE;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_09_25_01__digest_allow_today.sql')
ON CONFLICT (filename) DO NOTHING;
