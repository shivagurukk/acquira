-- ============================================================================
-- V2026_09_08_01: normalized channel_class (POS/ECOM) on sum_daily_full.
--
-- WHY
-- ---
-- The executive screens gain a POS / ECOM / All channel selector (user,
-- 2026-09-08). Their source table sum_daily_merchant has no channel dimension,
-- so channel-filtered reads route to sum_daily_full (ChannelSql.merchantDay) -
-- but sum_daily_full.channel holds the RAW terminal type ('PAX A920',
-- 'BENEFIT PG', 'MPGS', ...), not the normalized channel. This adds a
-- channel_class column with the terminal_channel_map resolution applied once
-- at write time, so read queries filter on a plain indexed-prunable equality
-- instead of re-resolving the map per row.
--
-- DESIGN
-- ------
-- * channel_class is FUNCTIONALLY DEPENDENT on (tenant, channel): the raw
--   terminal type determines the class via terminal_channel_map. It is NOT
--   part of the unique key - no grain change, no rebuild required for
--   correctness (this migration backfills every existing row).
-- * Backfill resolution mirrors the ingest fee pass: tenant-specific map row
--   beats country row, exact raw_type beats the '*' wildcard, unmatched
--   defaults to 'POS' (same COALESCE the population SQL uses).
-- * Going forward SummaryPopulationService stamps channel_class from
--   fact_transaction.channel (the ingest-stamped truth), falling back to the
--   same map lookup for pre-stamp fact rows, so a rebuild of old months can
--   never regress a correct class to the 'POS' default.
--
-- Idempotent; splitter-safe (no dollar-quoting, no DO blocks). The backfill
-- UPDATE only touches rows where channel_class IS NULL, so re-runs are no-ops.
-- ============================================================================

ALTER TABLE sum_daily_full ADD COLUMN IF NOT EXISTS channel_class VARCHAR(10) NULL;

COMMENT ON COLUMN sum_daily_full.channel_class IS
  'Normalized channel (POS/ECOM) resolved from the raw terminal type in `channel` via terminal_channel_map. Functionally dependent on (tenant, channel) - not part of the grain. Read path for the executive POS/ECOM selector (ChannelSql).';

-- One-time backfill of existing rows. Correlated lookup per DISTINCT
-- (tenant, raw type) value is fine: the map is tiny and the planner caches
-- the subplan; this scans each partition once.
UPDATE sum_daily_full s SET channel_class = COALESCE(
  (SELECT mm.channel FROM terminal_channel_map mm
    WHERE mm.country_code = (SELECT tt.home_country_code FROM tenant tt
                             WHERE tt.tenant_id = s.tenant_id)
      AND (mm.tenant_id IS NULL OR mm.tenant_id = s.tenant_id)
      AND (mm.raw_type = UPPER(TRIM(COALESCE(s.channel, ''))) OR mm.raw_type = '*')
    ORDER BY (mm.tenant_id IS NULL), (mm.raw_type = '*')
    LIMIT 1),
  'POS')
WHERE s.channel_class IS NULL;

-- Verify: every row classed, and the split looks sane per tenant.
SELECT tenant_id, channel_class, COUNT(*) rows, SUM(total_volume) volume
FROM sum_daily_full GROUP BY tenant_id, channel_class ORDER BY tenant_id, channel_class;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_08_01__sum_daily_full_channel_class.sql') ON CONFLICT (filename) DO NOTHING;
