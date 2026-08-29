-- ============================================================================
-- V2026_08_29_03: align UAE + Bahrain Mastercard local interchange with the
--                 Mastercard MEA interchange manual (m_mea_inc_customer,
--                 effective 2026-08-04), BIN-weighted where the manual prices
--                 by card product tier.
--
-- SOURCE OF TRUTH (user-confirmed 2026-08-29):
--   * The MC manual governs the rows below. MPGS + PayOn e-com is 100% 3DS,
--     so ECOM prices at the Full-UCAF (non-tokenized) leg. No tokenized
--     incentive modeling (feed carries no token indicator).
--   * Benefit / Benefit QR rows: UNTOUCHED. Visa rows: UNTOUCHED (no Visa
--     schedule reviewed). International + scheme fees: untouched.
--
-- UAE (tenant ACQ)
-- ----------------
--   1. PREPAID POS 0.75% -> 1.00%. The manual's UAE debit&prepaid table
--      prices consumer prepaid General at 1.00% max AED 50.00 in EVERY
--      program, card-present included. (ECOM prepaid/debit 1.00% were already
--      seeded by V2026_07_07_04.)
--   2. Cap currency correction. V2026_07_07_04 multiplied the workbook caps
--      by 3.67 reading them as USD; the manual states them in AED
--      ("0.75%, max AED 37.50"). Caps revert to the manual's AED figures:
--        137.625 -> 37.50   183.50 -> 50.00   119.275 -> 32.50
--         91.75  -> 25.00     3.67 ->  1.00 (charity micro-cap, 8398/8661)
--      V2026_07_07_04's conversion section is edited in the same commit to
--      heal instead of re-convert, so startup sql.init cannot undo this.
--
-- BAHRAIN (country-level rows, MasterCard only)
-- ---------------------------------------------
--   The BH card was a card-type-agnostic blend (POS 1.75 / ECOM 1.90).
--   The manual prices BH by consumer product tier; the BH MC BIN book
--   (ref_bin_range, loaded 2026-08-25) has ZERO Std/Gold consumer credit
--   ranges - the base is Titanium/Platinum/World/World Elite.
--   3. The 720 priority-60 per-MCC rows that just repeat the general blend
--      (POS 1.75 / ECOM 1.90) are DELETED - they were redundant with the
--      priority-15 default and would out-priority the tier rows below.
--      Segment-priced MCC rows (education 0.65, govt 0.75, petrol 0.25 POS /
--      1.35 ECOM, ...) are kept: the manual's segment columns are constant
--      across tiers, so they stay tier-wildcard and keep winning at 60.
--   4. Tier rows at priority 30 (beat the 15-default, lose to segment 60):
--                          POS (Electronic)   ECOM (Full UCAF)
--        CREDIT  Standard      1.16%              1.35%
--        CREDIT  Premium       1.92%              1.74%   (Ti/Pl BIN-weighted)
--        CREDIT  Elite         2.07%              2.07%   (W/WE BIN-weighted)
--        PREPAID Standard      1.16%              1.35%
--        PREPAID Premium       1.95%              1.75%   (Prepaid Platinum)
--        PREPAID Elite         2.05%              2.05%   (Prepaid World)
--      The tier resolves from the BIN: FeeComputationService (2026-08-29)
--      maps the leading 6 PAN digits through ref_bin_range to a product
--      code and buckets it via ref_bin_product_tier (section 4a) - gated to
--      BH + blank card_product_code, so no other country's pricing moves.
--      Unmapped products fall to the legacy Premium bucket.
--   5. The priority-15 MC POS default rises 1.75% -> 1.90% (BIN-weighted
--      fallback incl. Standard ranges); it now only catches what the tier
--      rows cannot: DEBIT (no BH MC debit table exists in the manual -
--      pending business ruling) and unknown card types. MC ECOM default
--      stays 1.90 for the same fallback role.
--
-- Splitter-safe (no dollar-quoting). Idempotent: updates are keyed on the
-- exact old values, the delete matches nothing on re-run, the insert is
-- guarded. BACKFILL: fees compute at ingest - re-ingest affected months.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1+2. UAE - cap currency correction back to the manual's AED figures.
--      Keyed on the exact converted values so a re-run matches nothing.
-- ---------------------------------------------------------------------------
UPDATE interchange_rate_local ilr SET cap_amount = 37.50
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 137.625;

UPDATE interchange_rate_local ilr SET cap_amount = 50.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 183.50;

UPDATE interchange_rate_local ilr SET cap_amount = 32.50
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 119.275;

UPDATE interchange_rate_local ilr SET cap_amount = 25.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 91.75;

UPDATE interchange_rate_local ilr SET cap_amount = 1.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 3.67 AND ilr.mcc IN ('8398','8661');

-- UAE PREPAID POS: 0.75% -> 1.00% cap AED 50 (manual: prepaid is 1.00 even CP).
UPDATE interchange_rate_local ilr
   SET interchange_pct = 0.010000,
       cap_amount      = 50.00,
       label           = 'Local prepaid POS 1.00 (cap AED 50, MC manual Aug-2026)',
       source_note     = 'BUSINESS-APPROVED 2026-08-29: MC MEA manual - prepaid 1.00% max AED 50 all programs'
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 10 AND ilr.dest = 'DOMESTIC' AND ilr.channel = 'POS'
  AND ilr.card_type = 'PREPAID' AND ilr.scheme_group IS NULL AND ilr.mcc IS NULL
  AND ilr.interchange_pct = 0.007500;

-- ---------------------------------------------------------------------------
-- 3. BH - drop the redundant general-blend per-MCC MC rows (POS 1.75 /
--    ECOM 1.90). Segment-priced MCC rows survive (different pct values).
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local
WHERE country_code = 'BH' AND tenant_id IS NULL AND dest = 'DOMESTIC'
  AND scheme_group = 'MasterCard' AND priority = 60 AND mcc IS NOT NULL
  AND ( (channel = 'POS'  AND interchange_pct = 0.017500)
     OR (channel = 'ECOM' AND interchange_pct = 0.019000) );

-- The priority-40 ANY-SCHEME per-MCC fallback rows (higher-of-Visa/MC, meant
-- for domestic Amex etc.) are scheme wildcards, so they ALSO match MasterCard
-- and at priority 40 would shadow the tier rows below on those MCCs. The
-- general-blend ones (POS 1.75 / ECOM 1.90) are deleted for the same reason;
-- Amex keeps pricing identically via the priority-10 any-scheme defaults
-- (POS 1.75 / ECOM 1.90), and the segment-priced priority-40 rows survive.
DELETE FROM interchange_rate_local
WHERE country_code = 'BH' AND tenant_id IS NULL AND dest = 'DOMESTIC'
  AND scheme_group IS NULL AND priority = 40 AND mcc IS NOT NULL
  AND ( (channel = 'POS'  AND interchange_pct = 0.017500)
     OR (channel = 'ECOM' AND interchange_pct = 0.019000) );

-- ---------------------------------------------------------------------------
-- 4a. BIN product -> tier bucket map (read by FeeComputationService's
--     BIN-tier LATERAL, 2026-08-29). Consumer Mastercard products bucket to
--     the manual's tiers; commercial products bucket to Elite as a STOPGAP
--     (manual BH commercial General is 2.00-2.15%, closest to Elite's 2.07)
--     until a real COMMERCIAL card_type exists. Unmapped products fall to
--     the engine's legacy Premium fallback.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ref_bin_product_tier (
    product_code VARCHAR(5)  PRIMARY KEY,
    card_tier    VARCHAR(10) NOT NULL,   -- Standard / Premium / Elite
    note         VARCHAR(120)
);

INSERT INTO ref_bin_product_tier (product_code, card_tier, note) VALUES
  ('MCS', 'Standard', 'Mastercard Standard'),
  ('MCG', 'Standard', 'Mastercard Gold'),
  ('MCC', 'Standard', 'Mastercard Credit (mixed BIN)'),
  ('MCT', 'Premium',  'Mastercard Titanium'),
  ('MPL', 'Premium',  'Mastercard Platinum'),
  ('MCW', 'Elite',    'World Mastercard'),
  ('MWE', 'Elite',    'World Elite Mastercard'),
  ('MNW', 'Elite',    'New World Mastercard'),
  ('MRG', 'Standard', 'Prepaid Gold'),
  ('MPG', 'Standard', 'Prepaid Gold variant'),
  ('MGP', 'Standard', 'Gold Prepaid'),
  ('MRH', 'Premium',  'Prepaid Platinum'),
  ('MRW', 'Elite',    'Prepaid World'),
  ('MWP', 'Elite',    'World Prepaid'),
  ('MCO', 'Elite',    'Corporate - STOPGAP until COMMERCIAL card_type (manual 2.00%)'),
  ('MCB', 'Elite',    'Business - STOPGAP until COMMERCIAL card_type (manual 2.00%)'),
  ('MEO', 'Elite',    'Corporate Executive - STOPGAP (manual 2.00%)'),
  ('MEB', 'Elite',    'Executive Business - STOPGAP (manual 2.10%)'),
  ('MWB', 'Elite',    'World Business - STOPGAP (manual 2.15%)'),
  ('MWO', 'Elite',    'World Corporate - STOPGAP (manual 2.00%)'),
  ('MAB', 'Elite',    'World Elite Business - STOPGAP (manual 2.20%)'),
  ('MIO', 'Elite',    'Corporate variant - STOPGAP (manual 2.15%)'),
  ('MCP', 'Elite',    'Purchasing - STOPGAP (manual all-other 2.00%)')
ON CONFLICT (product_code) DO NOTHING;

-- Give the planner stats immediately. The fee-resolution query joins this
-- table inside a per-row LATERAL (FeeComputationService); a brand-new table
-- with reltuples = -1 (never analyzed) can push the planner into a bad plan
-- for the WHOLE fee pass. One ANALYZE on 23 rows is instant and load-bearing.
ANALYZE ref_bin_product_tier;

-- ---------------------------------------------------------------------------
-- 4b. BH - tier rows (priority 30), EXACT manual tiers now that the engine
--     resolves Standard/Premium/Elite from the BIN. An earlier revision of
--     this migration seeded Premium rows that folded Elite in (1.99/1.89
--     credit, 2.01/1.92 prepaid) because the resolver was two-tier; those
--     folded rows are removed here (keyed on their exact pct values) and the
--     per-tier set below replaces them. Guarded per row.
-- ---------------------------------------------------------------------------
-- Match the OLD folded rows by their full (card_type, channel, pct) tuple, NOT
-- by pct alone: the new credit-Premium-POS rate (0.019200 = 1.92%) collides with
-- the old prepaid-Premium-ECOM folded value (also 0.019200), so a pct-only
-- delete would churn — removing and re-inserting the new row every run. The
-- tuple form can only ever hit the four rows the previous revision seeded.
DELETE FROM interchange_rate_local
WHERE country_code = 'BH' AND tenant_id IS NULL AND priority = 30
  AND dest = 'DOMESTIC' AND scheme_group = 'MasterCard' AND tier = 'Premium'
  AND ( (card_type = 'CREDIT'  AND channel = 'POS'  AND interchange_pct = 0.019900)
     OR (card_type = 'CREDIT'  AND channel = 'ECOM' AND interchange_pct = 0.018900)
     OR (card_type = 'PREPAID' AND channel = 'POS'  AND interchange_pct = 0.020100)
     OR (card_type = 'PREPAID' AND channel = 'ECOM' AND interchange_pct = 0.019200) );

INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, source_note)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Standard', NULL, NULL, NULL::NUMERIC, NULL::NUMERIC, 0.011600, NULL::NUMERIC,
     'BH MC credit Standard POS 1.16 (manual Std/Gold electronic)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
     'BH MC credit Standard ECOM 1.35 (manual Std/Gold Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Premium',  NULL, NULL, NULL, NULL, 0.019200, NULL,
     'BH MC credit Premium POS 1.92 (BIN-wtd Titanium 1.80/Platinum 1.95)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Premium',  NULL, NULL, NULL, NULL, 0.017400, NULL,
     'BH MC credit Premium ECOM 1.74 (BIN-wtd Ti 1.70/Pl 1.75 Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Elite',    NULL, NULL, NULL, NULL, 0.020700, NULL,
     'BH MC credit Elite POS 2.07 (BIN-wtd World 2.05/World Elite 2.10)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Elite',    NULL, NULL, NULL, NULL, 0.020700, NULL,
     'BH MC credit Elite ECOM 2.07 (BIN-wtd W 2.05/WE 2.10 Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Standard', NULL, NULL, NULL, NULL, 0.011600, NULL,
     'BH MC prepaid Standard POS 1.16 (manual Std/Gold electronic)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
     'BH MC prepaid Standard ECOM 1.35 (manual Std/Gold Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Premium',  NULL, NULL, NULL, NULL, 0.019500, NULL,
     'BH MC prepaid Premium POS 1.95 (manual Prepaid Platinum electronic)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Premium',  NULL, NULL, NULL, NULL, 0.017500, NULL,
     'BH MC prepaid Premium ECOM 1.75 (manual Prepaid Platinum Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Elite',    NULL, NULL, NULL, NULL, 0.020500, NULL,
     'BH MC prepaid Elite POS 2.05 (manual Prepaid World electronic)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Elite',    NULL, NULL, NULL, NULL, 0.020500, NULL,
     'BH MC prepaid Elite ECOM 2.05 (manual Prepaid World Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
       label, source_note)
WHERE NOT EXISTS (
  SELECT 1 FROM interchange_rate_local x
  WHERE x.country_code = 'BH' AND x.tenant_id IS NULL AND x.priority = 30
    AND x.dest = 'DOMESTIC' AND x.channel = v.channel
    AND x.scheme_group = v.scheme_group AND x.card_type = v.card_type
    AND x.tier = v.tier
);

-- ---------------------------------------------------------------------------
-- 5. BH - MC domestic defaults (priority 15) KEEP their original blend
--    (POS 1.75 / ECOM 1.90). With CREDIT and PREPAID priced by the tier rows
--    above, these defaults now only catch MC DEBIT and unknown card types -
--    and the manual has NO BH intracountry MC consumer debit table, so there
--    is no basis to move debit off the original blend. (An earlier revision
--    of this migration raised the POS default to 1.90; this sets it back.)
-- ---------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET interchange_pct = 0.017500,
       label           = 'BH MasterCard POS domestic default (debit/unknown fallback)',
       source_note     = 'BUSINESS-APPROVED 2026-08-29: debit/unknown fallback kept at workbook blend - no MC manual BH debit table'
 WHERE country_code = 'BH' AND tenant_id IS NULL AND priority = 15
   AND dest = 'DOMESTIC' AND channel = 'POS' AND scheme_group = 'MasterCard'
   AND interchange_pct IN (0.017500, 0.019000)
   -- Fire only when something actually differs (a prior revision's 1.90, or the
   -- old label), so re-runs are true no-ops rather than rewriting identical values.
   AND (interchange_pct = 0.019000
        OR label IS DISTINCT FROM 'BH MasterCard POS domestic default (debit/unknown fallback)');

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_29_03__mc_manual_rate_alignment.sql')
ON CONFLICT (filename) DO NOTHING;
