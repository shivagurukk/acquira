-- ============================================================================
-- V2026_08_29_04: a REAL commercial card type for UAE + Bahrain Mastercard.
--
-- Until now, commercial-product BINs (MCO/MCB/MEO/MEB/MWB/MWO/MAB/MIO/BPD/MCP)
-- were a STOPGAP: V2026_08_29_03 bucketed them to the consumer 'Elite' tier, so
-- a corporate card priced at the consumer World-Elite rate (~2.07%) instead of
-- the manual's commercial schedule. The MC MEA manual (2026-08-04) prices
-- commercial by PRODUCT CODE, on its own General band:
--   MCO, MEO, MWO, MCB, MCP  -> 2.00%   (and the "all other commercial" catch-all)
--   MEB                       -> 2.10%
--   MIO, BPD, MWB             -> 2.15%
--   MAB                       -> 2.20%
-- (UAE and Bahrain share these General bands.)
--
-- MODEL
-- -----
-- 1. ref_bin_product_tier gains a card_class column. Consumer products stay
--    card_class='CONSUMER'; the commercial codes flip to 'COMMERCIAL' and their
--    card_tier becomes the rate-band label Comm200/Comm210/Comm215/Comm220.
--    FeeComputationService's BIN lateral now carries card_class, and when it is
--    'COMMERCIAL' the card prices as card_type='COMMERCIAL' at that band.
-- 2. interchange_rate_local gets country-level (tenant_id NULL) COMMERCIAL rows
--    for AE and BH: the four General bands, priority 30, both channels (the
--    manual's commercial General has no electronic/UCAF split — one rate).
-- 3. Segment overrides that would otherwise MIS-price commercial materially:
--      BH  petrol (MCC 5541/5542): 1.00%  (else the kept consumer petrol row,
--                                          priority 60 @0.25%, would win)
--      UAE petrol (mcc_sector Gas): 0.50% (else the priority-30 General @2.00%
--                                          would shadow the consumer Gas sector)
--      UAE govt   (mcc_sector Govt): 0.50%(same reason; manual UAE commercial
--                                          GvtSvc = 0.50%)
--    BH commercial govt already resolves to 0.75% via the kept priority-60 govt
--    rows (= the manual's commercial GvtServ), so it needs no override.
--
-- DEFERRED (documented, fall to commercial General 2.00-2.20 per the manual's
-- "for rates not specified, the general rates apply" catch-all — a defensible
-- over-approximation, never under-pricing vs consumer):
--   * UAE Commercial Emerging Market (0.80%), Telecom/Computer (0.50%),
--     Real Estate & Wholesale (USD 5k/15k ticket-tiered).
--   * Charities: USD 0.25 flat/txn (the standing flat-fee-model gap).
--   * MDT (manual lists it commercial 2.00% but the BIN file types it DEBIT):
--     left as-is pending a business ruling on its class.
--   * The MBG/MBJ/MKF/MKG/MKH BIN family: not in the manual's table; stays
--     consumer until the T067 product-code doc classifies it.
--
-- Splitter-safe (no dollar-quoting), idempotent. BACKFILL: re-ingest affected
-- months (fees compute at ingest). REQUIRES the matching batch build (the
-- FeeComputationService card_class change) to price commercial at all.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. ref_bin_product_tier.card_class + reclassify the commercial codes.
-- ---------------------------------------------------------------------------
ALTER TABLE ref_bin_product_tier ADD COLUMN IF NOT EXISTS card_class VARCHAR(12) NOT NULL DEFAULT 'CONSUMER';

UPDATE ref_bin_product_tier SET card_class = 'COMMERCIAL', card_tier = v.band
FROM ( VALUES
  ('MCO','Comm200'), ('MEO','Comm200'), ('MWO','Comm200'), ('MCB','Comm200'), ('MCP','Comm200'),
  ('MEB','Comm210'),
  ('MIO','Comm215'), ('BPD','Comm215'), ('MWB','Comm215'),
  ('MAB','Comm220')
) AS v(code, band)
WHERE ref_bin_product_tier.product_code = v.code
  AND (ref_bin_product_tier.card_class <> 'COMMERCIAL' OR ref_bin_product_tier.card_tier <> v.band);

-- MIO/BPD are commercial but were not in the earlier stopgap seed — add them.
INSERT INTO ref_bin_product_tier (product_code, card_tier, card_class, note) VALUES
  ('MIO', 'Comm215', 'COMMERCIAL', 'Corporate variant (manual 2.15%)'),
  ('BPD', 'Comm215', 'COMMERCIAL', 'Business Prepaid/Debit corporate (manual 2.15%)')
ON CONFLICT (product_code) DO NOTHING;

ANALYZE ref_bin_product_tier;

-- ---------------------------------------------------------------------------
-- 2. COMMERCIAL General bands (AE + BH), country-level, priority 30.
--    3. plus the petrol/govt segment overrides at priority 35 / 65.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, source_note)
SELECT v.* FROM ( VALUES
  -- UAE General bands
  (NULL::INT, 'AE', 'AED', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm200', NULL, NULL, NULL::NUMERIC, NULL::NUMERIC, 0.020000, NULL::NUMERIC, 'UAE MC commercial 2.00 (MCO/MEO/MWO/MCB/MCP)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm200', NULL, NULL, NULL, NULL, 0.020000, NULL, 'UAE MC commercial 2.00 (MCO/MEO/MWO/MCB/MCP)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm210', NULL, NULL, NULL, NULL, 0.021000, NULL, 'UAE MC commercial 2.10 (MEB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm210', NULL, NULL, NULL, NULL, 0.021000, NULL, 'UAE MC commercial 2.10 (MEB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm215', NULL, NULL, NULL, NULL, 0.021500, NULL, 'UAE MC commercial 2.15 (MIO/BPD/MWB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm215', NULL, NULL, NULL, NULL, 0.021500, NULL, 'UAE MC commercial 2.15 (MIO/BPD/MWB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm220', NULL, NULL, NULL, NULL, 0.022000, NULL, 'UAE MC commercial 2.20 (MAB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm220', NULL, NULL, NULL, NULL, 0.022000, NULL, 'UAE MC commercial 2.20 (MAB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  -- UAE segment overrides (mcc_sector), priority 35, tier-wildcard (all bands)
  (NULL, 'AE', 'AED', 35, 'DOMESTIC', NULL, 'MasterCard', 'COMMERCIAL', NULL, 'Gas',  NULL, NULL, NULL, 0.005000, NULL, 'UAE MC commercial petrol 0.50', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial Petrol'),
  (NULL, 'AE', 'AED', 35, 'DOMESTIC', NULL, 'MasterCard', 'COMMERCIAL', NULL, 'Govt', NULL, NULL, NULL, 0.005000, NULL, 'UAE MC commercial govt 0.50', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial GvtSvc'),
  -- Bahrain General bands
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm200', NULL, NULL, NULL, NULL, 0.020000, NULL, 'BH MC commercial 2.00 (MCO/MEO/MWO/MCB/MCP)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm200', NULL, NULL, NULL, NULL, 0.020000, NULL, 'BH MC commercial 2.00 (MCO/MEO/MWO/MCB/MCP)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm210', NULL, NULL, NULL, NULL, 0.021000, NULL, 'BH MC commercial 2.10 (MEB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm210', NULL, NULL, NULL, NULL, 0.021000, NULL, 'BH MC commercial 2.10 (MEB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm215', NULL, NULL, NULL, NULL, 0.021500, NULL, 'BH MC commercial 2.15 (MIO/BPD/MWB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm215', NULL, NULL, NULL, NULL, 0.021500, NULL, 'BH MC commercial 2.15 (MIO/BPD/MWB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm220', NULL, NULL, NULL, NULL, 0.022000, NULL, 'BH MC commercial 2.20 (MAB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm220', NULL, NULL, NULL, NULL, 0.022000, NULL, 'BH MC commercial 2.20 (MAB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  -- Bahrain petrol override (per-MCC, priority 65 to beat the kept consumer petrol @0.25 at priority 60)
  (NULL, 'BH', 'BHD', 65, 'DOMESTIC', NULL, 'MasterCard', 'COMMERCIAL', NULL, NULL, '5541', NULL, NULL, 0.010000, NULL, 'BH MC commercial petrol 1.00 (5541)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial Petrol'),
  (NULL, 'BH', 'BHD', 65, 'DOMESTIC', NULL, 'MasterCard', 'COMMERCIAL', NULL, NULL, '5542', NULL, NULL, 0.010000, NULL, 'BH MC commercial petrol 1.00 (5542)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial Petrol')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
       label, source_note)
WHERE NOT EXISTS (
  SELECT 1 FROM interchange_rate_local x
  WHERE x.country_code = v.country_code AND x.tenant_id IS NULL AND x.priority = v.priority
    AND x.dest = 'DOMESTIC' AND x.card_type = 'COMMERCIAL'
    AND x.scheme_group = v.scheme_group
    AND COALESCE(x.channel,'*') = COALESCE(v.channel,'*')
    AND COALESCE(x.tier,'*') = COALESCE(v.tier,'*')
    AND COALESCE(x.mcc_sector,'*') = COALESCE(v.mcc_sector,'*')
    AND COALESCE(x.mcc,'*') = COALESCE(v.mcc,'*')
);

ANALYZE interchange_rate_local;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_29_04__commercial_card_type.sql')
ON CONFLICT (filename) DO NOTHING;
