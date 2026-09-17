-- ============================================================================
-- V2026_09_15_03: BH terminal-type -> channel map corrections (business list
-- supplied 2026-09-15, authoritative).
--
-- Two deltas vs the V2026_08_24_02 seed:
--   1. AFS ONE is a POS product, not ECOM. The 2026-08-24 seed classed it
--      ECOM, so every AFS ONE transaction priced (and earned ecom flat fee /
--      appeared on ECOM dashboards) as e-commerce.
--   2. 'N62 (MINI DEVICE WITH NO PRINTER)' was missing entirely, so those
--      terminals resolved UNMAPPED_CHANNEL and nothing priced.
--   3. A leftover '*' -> POS ASSUMPTION row (E2E 2026-08-15, pre-dating the
--      real 2026-08-24 list) is removed: it violated the deliberate
--      no-wildcard rule of V2026_08_24_02, silently pricing any future
--      unlisted product as POS instead of surfacing UNMAPPED_CHANNEL.
--      Typeless terminals stay covered by the explicit '' (V2026_08_26_01)
--      and 'NONE' rows, so nothing that prices today stops pricing.
--
-- The ECOM set for BH is exactly: ECOM PROFILE, BENEFIT PG, MPGS, PAY ON,
-- PAY BY LINK. Every other supplied token is POS. All other rows in the
-- current map already match the supplied list — verified 2026-09-15.
--
-- AFTER APPLYING: fact_transaction.channel is only rewritten by the fee pass,
-- so affected BH history must be repriced (rebuild-summaries reprice:true)
-- for the correction to reach existing facts and summaries.
--
-- Idempotent; splitter-safe (no dollar-quoting); raw_type stored UPPERCASE
-- (the resolver compares UPPER(TRIM(dim_terminal.type))).
-- ============================================================================

-- 1. AFS ONE: ECOM -> POS (country-level BH row from the 2026-08-24 seed).
UPDATE terminal_channel_map
SET channel = 'POS',
    note = 'BH business list 2026-09-15: AFS One is POS (was misseeded ECOM 2026-08-24)'
WHERE country_code = 'BH'
  AND tenant_id IS NULL
  AND raw_type = 'AFS ONE'
  AND channel = 'ECOM';

-- 2. Missing POS device token.
INSERT INTO terminal_channel_map (tenant_id, country_code, raw_type, channel, note)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'BH', 'N62 (MINI DEVICE WITH NO PRINTER)', 'POS', 'BH business list 2026-09-15')
) AS v(tenant_id, country_code, raw_type, channel, note)
ON CONFLICT (country_code, COALESCE(tenant_id, 0), raw_type) DO NOTHING;

-- 3. Remove the pre-list wildcard (loud-over-wrong: new products must surface
--    as UNMAPPED_CHANNEL until deliberately mapped).
DELETE FROM terminal_channel_map
WHERE country_code = 'BH'
  AND tenant_id IS NULL
  AND raw_type = '*';

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_15_03__bh_terminal_channel_map_corrections.sql') ON CONFLICT (filename) DO NOTHING;
