-- ============================================================================
-- V2026_08_30_01: align Egypt (EG) Mastercard LOCAL interchange with the
--                 Mastercard MEA interchange manual (Egypt intracountry
--                 consumer Credit rates), tier-wise, BIN-resolved.
--
-- ****************************************************************************
-- *** DRAFT — DO NOT APPLY YET. The Premium and Elite interchange_pct     ***
-- *** values below are PLACEHOLDERS (simple average of the folded manual  ***
-- *** sub-tiers). Replace them with the BIN-VOLUME-WEIGHTED figures from   ***
-- ***   docs/deploy/EG_MC_BIN_WEIGHTING_2026-08-30.sql                    ***
-- *** (run against the live EG DB) before this migration is applied.      ***
-- *** Standard (1.35%) is exact from the manual and needs no weighting.   ***
-- ****************************************************************************
--
-- SCOPE (user-confirmed 2026-08-30):
--   * "Replace" is scoped to the MASTERCARD portion of the EG card only.
--     The manual carries NO Visa / JCB / UnionPay / international / sector
--     schedule, so those EG rows are LEFT UNTOUCHED — a full wipe would strip
--     Egypt Visa pricing (776 rows) and surface NO_RATE_FOUND. Mirrors how
--     Bahrain's V2026_08_29_03 touched only MasterCard.
--   * Scheme fees: unchanged — V2026_07_31_05 already copied the UAE grid to
--     EG verbatim (INTERNATIONAL/DOMESTIC x POS/ECOM x scheme). Re-asserted
--     idempotently at the foot of this file in case that copy was cleared.
--
-- MANUAL -> SYSTEM TIER FOLDING (ape0122755144767, "All Others" general column;
-- Card Present == Full UCAF per tier in this manual, so POS == ECOM):
--     Standard  <- Standard/Gold ............ 1.35%
--     Premium   <- Titanium 1.85 + Platinum 2.00 .... BIN-weighted  [PLACEHOLDER 1.93]
--     Elite     <- World 2.15 + World Elite 2.20 .... BIN-weighted  [PLACEHOLDER 2.18]
--   Tier resolves from the card BIN via ref_bin_product_tier (global product
--   -> Standard/Premium/Elite map, already seeded by V2026_08_29_03) once the
--   EG tenant's card_type_source = 'BIN'. Without BIN tiering the engine falls
--   to its Premium default (FeeComputationService tier CASE), so unknown MC
--   still prices at Premium.
--
-- PREPAID (user asked for credit AND prepaid): the Egypt manual bundles
--   consumer credit (MCT), debit (MET) and prepaid (TPM) into the SAME
--   intracountry consumer programs at the SAME tier rate (IRD TS et al.), so
--   prepaid tier rows mirror the credit tier rates. DEBIT is not seeded here
--   (no separate table; keeps its existing EG treatment).
--
-- Splitter-safe (no dollar-quoting). Idempotent: the MC delete matches the
-- old EG MasterCard rows (re-run finds none once replaced), the tier insert is
-- per-row NOT EXISTS-guarded, the scheme-fee re-copy is NOT EXISTS-guarded.
-- BACKFILL: fees compute at ingest — re-ingest affected EG months.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Remove the existing EG MasterCard interchange rows (per-MCC priority-60
--    and the priority-15 scheme default). The any-scheme (scheme_group IS NULL)
--    fallback rows are KEPT — they still price domestic Amex/unknown, and the
--    new tier rows below out-priority them for MasterCard.
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local
WHERE country_code = 'EG' AND tenant_id IS NULL
  AND scheme_group = 'MasterCard';

-- ---------------------------------------------------------------------------
-- 2. Ensure the EG-relevant Mastercard consumer products bucket to tiers.
--    ref_bin_product_tier is global and already carries these (V2026_08_29_03);
--    this is a defensive top-up so EG works even if applied standalone.
-- ---------------------------------------------------------------------------
INSERT INTO ref_bin_product_tier (product_code, card_tier, note) VALUES
  ('MCS', 'Standard', 'Mastercard Standard'),
  ('MCG', 'Standard', 'Mastercard Gold'),
  ('MCC', 'Standard', 'Mastercard Credit (mixed BIN)'),
  ('MCT', 'Premium',  'Mastercard Titanium'),
  ('MPL', 'Premium',  'Mastercard Platinum'),
  ('MCW', 'Elite',    'World Mastercard'),
  ('MWE', 'Elite',    'World Elite Mastercard'),
  ('MRG', 'Standard', 'Prepaid Gold'),
  ('MRH', 'Premium',  'Prepaid Platinum'),
  ('MRW', 'Elite',    'Prepaid World'),
  ('MWP', 'Elite',    'World Prepaid')
ON CONFLICT (product_code) DO NOTHING;
ANALYZE ref_bin_product_tier;

-- ---------------------------------------------------------------------------
-- 3. EG Mastercard tier rows (priority 30), credit + prepaid, POS + ECOM.
--    interchange_pct is a decimal fraction (0.013500 = 1.35%). cap NULL (the
--    manual's AllOth general rate carries no cap). cap_currency_code 'EGP'.
--
--    !!! Premium/Elite pct = PLACEHOLDER averages — replace with the
--        BIN-weighted output before applying (see banner + weighting SQL). !!!
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, source_note)
SELECT v.* FROM ( VALUES
  -- CREDIT --------------------------------------------------------------------
  (NULL::INT, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Standard', NULL, NULL, NULL::NUMERIC, NULL::NUMERIC, 0.013500, NULL::NUMERIC,
     'EG MC credit Standard POS 1.35 (manual Std/Gold AllOth)',
     'DRAFT 2026-08-30: MC MEA manual Egypt intracountry consumer, tier-wise'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
     'EG MC credit Standard ECOM 1.35 (manual Std/Gold AllOth, Full UCAF)',
     'DRAFT 2026-08-30: MC MEA manual Egypt intracountry consumer, tier-wise'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Premium',  NULL, NULL, NULL, NULL, 0.019300, NULL,
     'EG MC credit Premium POS (PLACEHOLDER 1.93 avg Ti/Pl; set BIN-wtd)',
     'DRAFT 2026-08-30: PLACEHOLDER 1.93=avg(Ti 1.85,Pl 2.00) - replace pct with EG_MC_BIN_WEIGHTING output'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Premium',  NULL, NULL, NULL, NULL, 0.019300, NULL,
     'EG MC credit Premium ECOM (PLACEHOLDER 1.93 avg Ti/Pl; set BIN-wtd)',
     'DRAFT 2026-08-30: PLACEHOLDER 1.93=avg(Ti 1.85,Pl 2.00) - replace pct with EG_MC_BIN_WEIGHTING output'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Elite',    NULL, NULL, NULL, NULL, 0.021800, NULL,
     'EG MC credit Elite POS (PLACEHOLDER 2.18 avg W/WE; set BIN-wtd)',
     'DRAFT 2026-08-30: PLACEHOLDER 2.18=avg(World 2.15,World Elite 2.20) - replace pct with EG_MC_BIN_WEIGHTING output'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Elite',    NULL, NULL, NULL, NULL, 0.021800, NULL,
     'EG MC credit Elite ECOM (PLACEHOLDER 2.18 avg W/WE; set BIN-wtd)',
     'DRAFT 2026-08-30: PLACEHOLDER 2.18=avg(World 2.15,World Elite 2.20) - replace pct with EG_MC_BIN_WEIGHTING output'),
  -- PREPAID (mirrors credit — manual bundles credit/debit/prepaid per tier) ----
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
     'EG MC prepaid Standard POS 1.35 (manual Std/Gold AllOth)',
     'DRAFT 2026-08-30: MC MEA manual Egypt intracountry consumer, tier-wise'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
     'EG MC prepaid Standard ECOM 1.35 (manual Std/Gold AllOth, Full UCAF)',
     'DRAFT 2026-08-30: MC MEA manual Egypt intracountry consumer, tier-wise'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Premium',  NULL, NULL, NULL, NULL, 0.019300, NULL,
     'EG MC prepaid Premium POS (PLACEHOLDER 1.93; set BIN-wtd)',
     'DRAFT 2026-08-30: PLACEHOLDER 1.93 - replace pct with EG_MC_BIN_WEIGHTING output'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Premium',  NULL, NULL, NULL, NULL, 0.019300, NULL,
     'EG MC prepaid Premium ECOM (PLACEHOLDER 1.93; set BIN-wtd)',
     'DRAFT 2026-08-30: PLACEHOLDER 1.93 - replace pct with EG_MC_BIN_WEIGHTING output'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Elite',    NULL, NULL, NULL, NULL, 0.021800, NULL,
     'EG MC prepaid Elite POS (PLACEHOLDER 2.18; set BIN-wtd)',
     'DRAFT 2026-08-30: PLACEHOLDER 2.18 - replace pct with EG_MC_BIN_WEIGHTING output'),
  (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Elite',    NULL, NULL, NULL, NULL, 0.021800, NULL,
     'EG MC prepaid Elite ECOM (PLACEHOLDER 2.18; set BIN-wtd)',
     'DRAFT 2026-08-30: PLACEHOLDER 2.18 - replace pct with EG_MC_BIN_WEIGHTING output')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
       label, source_note)
WHERE NOT EXISTS (
  SELECT 1 FROM interchange_rate_local x
  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL AND x.priority = 30
    AND x.dest = 'DOMESTIC' AND x.channel = v.channel
    AND x.scheme_group = v.scheme_group AND x.card_type = v.card_type
    AND x.tier = v.tier
);

-- ---------------------------------------------------------------------------
-- 4. Scheme fees — same as UAE (already copied by V2026_07_31_05). Re-assert
--    idempotently: seed only if EG has no scheme rows at all.
-- ---------------------------------------------------------------------------
INSERT INTO scheme_fee_rate (tenant_id, country_code, dest, channel, scheme_group, fee_pct)
SELECT NULL, 'EG', s.dest, s.channel, s.scheme_group, s.fee_pct
FROM scheme_fee_rate s
WHERE s.country_code = 'AE' AND s.tenant_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM scheme_fee_rate y WHERE y.country_code = 'EG' AND y.tenant_id IS NULL);

ANALYZE interchange_rate_local;
ANALYZE scheme_fee_rate;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_30_01__mc_manual_rate_alignment_egypt.sql')
ON CONFLICT (filename) DO NOTHING;
