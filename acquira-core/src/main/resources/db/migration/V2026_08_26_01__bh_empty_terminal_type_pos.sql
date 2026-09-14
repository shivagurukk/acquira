-- ============================================================================
-- V2026_08_26_01: BH empty terminal type -> POS (business-confirmed).
--
-- WHY
-- ---
-- Terminals auto-created from a transaction upload carry NO type (the txn feed
-- has none; the merchant-master upload is what backfills it). The channel
-- resolver normalizes a NULL/blank type to '' and matches it against
-- terminal_channel_map.raw_type — and BH had no '' row, so every transaction
-- on such a terminal resolved UNMAPPED_CHANNEL and priced NULL. Seen live on
-- UAT tenant 8 (2026-08-26): 45,860 rows per upload, 92,626 cumulative.
--
-- The business already ruled the typeless case in the 2026-08-24 list via the
-- literal feed token 'NONE' -> POS, and confirmed today (2026-08-26) that the
-- unmapped typeless terminals are all POS. This row extends that same ruling
-- to the truly-empty value.
--
-- DESIGN
-- ------
-- * raw_type '' is NOT a wildcard: it matches ONLY terminals with no type at
--   all. A new NAMED product still surfaces loudly as UNMAPPED_CHANNEL until
--   mapped — the deliberate no-'*' rule from V2026_08_24_02 stands.
-- * Country-level (tenant_id NULL); a per-tenant override still wins.
-- * Already-ingested rows do NOT reprice by themselves: re-run the upload (or
--   a backfill) for the affected days so the fee pass re-resolves them.
--
-- Idempotent via ON CONFLICT DO NOTHING against
-- uq_terminal_channel_map (country_code, COALESCE(tenant_id,0), raw_type).
-- Splitter-safe (no dollar-quoting).
-- ============================================================================

INSERT INTO terminal_channel_map (tenant_id, country_code, raw_type, channel, note)
VALUES (NULL, 'BH', '', 'POS', 'BH business ruling 2026-08-26: typeless terminal = POS')
ON CONFLICT (country_code, COALESCE(tenant_id, 0), raw_type) DO NOTHING;

-- Existing BH terminals auto-created from transaction uploads (internal_id
-- 'AUTO_TERM_<sid>_<tid>', type NULL because the txn feed carries none) get
-- the business token 'NONE' so they read consistently with the 2026-08-24
-- list and price POS through its existing row. NOTE the master upsert keys on
-- (tenant_id, internal_id) and master rows use their own internal_ids, so a
-- master upload never touches AUTO_TERM_ rows — this stamp is not at risk of
-- being clobbered, nor will it be auto-corrected; if these TIDs later appear
-- in the master, dedupe/repoint deliberately. Idempotent (type IS NULL
-- guard). LIKE underscores escaped: '_' is a LIKE wildcard.
UPDATE dim_terminal dt SET type = 'NONE'
FROM tenant tn
WHERE tn.tenant_id = dt.tenant_id
  AND tn.home_country_code = 'BH'
  AND dt.internal_id LIKE 'AUTO\_TERM\_%'
  AND dt.type IS NULL;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_26_01__bh_empty_terminal_type_pos.sql') ON CONFLICT (filename) DO NOTHING;
