-- ============================================================================
-- EGYPT VISA + MASTERCARD DOMESTIC RATE CARD, combined psql script (2026-10-26, updated 2026-10-27)
-- Concatenation of, in order:
--   1. V2026_10_26_01__visa_eg_irf_guide_align.sql
--   2. V2026_10_26_02__mc_eg_manual_align.sql
--   3. V2026_10_26_03__eg_terminal_channel_map.sql
--   4. V2026_10_26_04__eg_scheme_fee_uae_grid.sql
--   5. V2026_10_26_05__eg_meeza_interchange_reassert.sql
--   6. V2026_10_26_06__eg_meeza_scheme_fee.sql
--   7. V2026_10_26_07__eg_intl_and_fallback_rows.sql
--   8. V2026_10_27_01__eg_domestic_scheme_fee_017.sql
--   9. V2026_10_27_02__eg_meeza_scheme_fee_006.sql
-- Each part is guarded on its own schema_migration_log row, so the script is
-- safe to re-run and each part can also be applied on its own later.
-- Apply:   psql -h <host> -U <user> -d postgres -f EG_VISA_MC_RATE_CARD_2026-10-26.sql
-- After:   re-ingest / rebuild-summaries with reprice for the EG months.
-- ============================================================================


-- ============================================================================
-- 1. V2026_10_26_01__visa_eg_irf_guide_align.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_26_01: Egypt (EG) Visa DOMESTIC interchange aligned to the
--                 "Egypt Domestic IRF Guide - Visa Supplemental Requirements"
--                 v2.8, effective 20 July 2024 (user-supplied 2026-10-26).
--
-- SCOPE: scheme_group = 'Visa', country_code = 'EG', dest = 'DOMESTIC' only.
--   * Mastercard, Meeza, JCB, UnionPay and the any-scheme fallback rows are
--     NOT touched (Mastercard gets its own migration from the MC manual).
--   * INTERNATIONAL stays on the UAE 1.85% figure (business: same as UAE).
--   * scheme_fee_rate is untouched (business: same grid as UAE).
--
-- WHAT IT REPLACES: the V2026_07_31_05 EG Visa card, which was a verbatim copy
-- of the UAE grid (flat 1.75% POS / 1.90% ECOM, 776 per-MCC rows at the same
-- rate). All EG domestic Visa rows (tenant_id IS NULL) are deleted and reseeded.
--
-- HOW THE GUIDE MAPS ONTO THE ENGINE (FeeComputationService, priority DESC wins):
--   55  Industry Fee Program WITH TRANSACTION SIZE LIMIT  (consumer only,
--       amount <= EGP 600, flat EGP 1.50, 0%)             Small Retail/Pharma/QSR/Frontier
--   50  Industry Fee Program (per MCC)                     Transport/Gov/Telecom (all),
--                                                          Fuel (consumer 0.25 / commercial 0.65),
--                                                          Edu/Charity/Medical (consumer),
--                                                          Insurance/Auto/Rental/Construction
--                                                          (consumer, PRODUCT RATE capped EGP 200)
--   49  tier-less fallback for the capped-product-rate MCCs (Classic rate, cap 200)
--   21  Installment Credential (prepaid) ticket bands      < 500 / >= 500 EGP
--   20  Product Rate Fee Program (POS) / Alternative Rate Fee Program (ECOM) per tier
--   15  Visa last-resort row for a BIN with no tier         = Classic (Default) rate
--
--   POS  = Product Rate program (card-present entry modes 05/07/90/91).
--   ECOM = Alternative Rate program (token/ecom entry modes 01/10) = product +0.20%.
--   Same convention as Bahrain V2026_09_15_01. The Acquirer Downgrade ladder
--   (product +0.25%) is NOT modeled - the feed carries no downgrade signal.
--
-- TIERING: product tiers resolve from the scheme BIN file (ref_bin_range ->
--   ref_bin_product_tier) ONLY when tenant.card_type_source = 'BIN'. On a
--   FILE-sourced tenant every Visa card resolves tier 'Premium' (no V* tier
--   row matches) and prices at the priority-15 Classic fallback. Industry MCC
--   rows (50/55) apply either way. This migration does NOT flip the setting.
--   card_type is NULL on tier rows: the guide prices by PRODUCT regardless of
--   funding source (Electron is a debit product and must still get 1.20%).
--
-- CONSUMER vs COMMERCIAL: "Consumer Products Only" rows are seeded once per
--   card_type CREDIT / DEBIT / PREPAID, "Commercial Products Only" rows as
--   card_type = 'COMMERCIAL' (BIN card_class on a BIN-sourced tenant). On a
--   FILE-sourced tenant a commercial card arrives as CREDIT and takes the
--   consumer row - known limitation, same as Bahrain.
--
-- DELIBERATE DECISIONS (flagged for business sign-off):
--   D1  Product code 'L' (Electron) is added to ref_bin_product_tier as VELEC.
--       The table is global - BH has no VELEC rows so BH Electron keeps falling
--       to its flat fallback exactly as before.
--   D2  Infinite Business (G4 -> VBUSINF) has no line in the Egypt guide. It is
--       priced at Signature Business (2.20 / 2.40) so it does not drop to Classic.
--   D3  Platinum Business rows (VBUSPLAT 2.15 / 2.35) are seeded for completeness
--       but no Visa product code is mapped to VBUSPLAT yet - unreachable until
--       the product code is confirmed.
--   D4  ALL Visa prepaid (card_type PREPAID) is priced as the guide's
--       "Installment Credential (prepaid)" product: < EGP 500 -> 1.20 / 1.40,
--       >= EGP 500 -> 1.60 / 1.80. The guide has no other prepaid product line.
--   D5  "Transaction amount EGP 600" limit is read as <= 600.00 inclusive, so
--       max_ticket = 600.01 (engine compares amount < max_ticket, exclusive).
--   D6  Edits that the feed cannot express are ASSUMED MET: central processing
--       date within 3 days, settlement flag / NNSS participation (NNSS and
--       international-settlement rates are identical in this guide anyway).
--   D7  NOT modeled: Visa Partner Program (MVV), BPSP "EG STRATEGIC 1-6" FPI
--       tiers, Global B2B Virtual, Visa Direct, loyalty credits, cash/ATM, AFT,
--       OCT, Mobile Push Payment, cashback IRF (EGP 2.00) - none is identifiable
--       from the clearing feed as ingested.
--
-- Splitter-safe (no dollar quoting, no semicolons inside comments).
-- GUARDED one-time flip (ledger convention): every statement runs only while
-- this file's schema_migration_log row is absent, so a re-run on startup or
-- via psql never wipes later manual edits. Apply once via psql on UAT/prod,
-- then re-ingest / reprice the affected EG months (fees compute at ingest).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A. Visa product code -> tier (global table). Only Electron is new. The
--    consumer/commercial Visa codes A F J3 P B N1 N C I I1 I2 G G1 G3 G4 G5 K
--    K1 S S1 S2 S6 were seeded by V2026_09_15_01 and are re-asserted here so
--    this file also works on a DB that never ran the Bahrain alignment.
-- ---------------------------------------------------------------------------
INSERT INTO ref_bin_product_tier (product_code, card_tier, card_class, note)
SELECT v.* FROM (VALUES
  ('L',  'VELEC',   'CONSUMER',   'Visa Electron (EG guide 1.20)'),
  ('A',  'VCLAS',   'CONSUMER',   'Visa Traditional -> Classic'),
  ('F',  'VCLAS',   'CONSUMER',   'Visa Classic'),
  ('J3', 'VCLAS',   'CONSUMER',   'Visa prepaid -> Classic treatment'),
  ('P',  'VGOLD',   'CONSUMER',   'Visa Gold'),
  ('B',  'VRWD',    'CONSUMER',   'Visa Traditional Rewards -> Rewards'),
  ('N1', 'VRWD',    'CONSUMER',   'Visa Rewards'),
  ('N',  'VPLAT',   'CONSUMER',   'Visa Platinum'),
  ('C',  'VSIG',    'CONSUMER',   'Visa Signature'),
  ('I',  'VINF',    'CONSUMER',   'Visa Infinite'),
  ('I1', 'VINF',    'CONSUMER',   'Visa Infinite Privilege -> Infinite'),
  ('I2', 'VUHNW',   'CONSUMER',   'Visa UHNW'),
  ('G',  'VBUS',    'COMMERCIAL', 'Visa Business'),
  ('G3', 'VBUS',    'COMMERCIAL', 'Visa Business Enhanced -> Business'),
  ('G5', 'VBUS',    'COMMERCIAL', 'Visa Business Rewards -> Business'),
  ('G1', 'VBUSSIG', 'COMMERCIAL', 'Visa Signature Business'),
  ('G4', 'VBUSINF', 'COMMERCIAL', 'Visa Infinite Business'),
  ('K',  'VCORP',   'COMMERCIAL', 'Visa Corporate'),
  ('K1', 'VCORP',   'COMMERCIAL', 'Visa GSA Corporate -> Corporate'),
  ('S',  'VPUR',    'COMMERCIAL', 'Visa Purchasing'),
  ('S1', 'VPUR',    'COMMERCIAL', 'Visa Fleet -> Purchasing'),
  ('S2', 'VPUR',    'COMMERCIAL', 'Visa Purchasing w/ fleet -> Purchasing'),
  ('S6', 'VPUR',    'COMMERCIAL', 'Visa Purchasing variant -> Purchasing')
) AS v(product_code, card_tier, card_class, note)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql')
ON CONFLICT (product_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- B. Drop the UAE-copied EG Visa domestic card (defaults + 776 per-MCC rows).
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local
WHERE country_code = 'EG' AND tenant_id IS NULL
  AND scheme_group = 'Visa' AND dest = 'DOMESTIC'
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- ---------------------------------------------------------------------------
-- C. Priority 15: last resort for a Visa card whose BIN resolves no V* tier
--    (FILE-sourced tenant, unmapped product code). = Classic (Default).
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 15, 'DOMESTIC', c.channel, 'Visa',
       NULL, NULL, NULL, NULL, NULL, NULL,
       c.pct, 0.0000, NULL, 'APPROVED', NULL,
       'EG Visa ' || c.channel || ' default = Classic ' || c.lbl || ' (no BIN tier)',
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: Classic (Default) product rate / alternative rate'
FROM (VALUES ('POS', 0.012000, '1.20'), ('ECOM', 0.014000, '1.40')) AS c(channel, pct, lbl)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- ---------------------------------------------------------------------------
-- D. Priority 20: Product Rate (POS) / Alternative Rate (ECOM) per Visa tier.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 20, 'DOMESTIC', c.channel, 'Visa',
       NULL, t.tier, NULL, NULL, NULL, NULL,
       CASE c.channel WHEN 'POS' THEN t.pos_pct ELSE t.ecom_pct END, 0.0000, NULL, 'APPROVED', NULL,
       'EG Visa ' || t.product || ' ' || c.channel || ' ' || CASE c.channel WHEN 'POS' THEN t.pos_lbl ELSE t.ecom_lbl END,
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: ' || CASE c.channel WHEN 'POS' THEN 'Product Rate' ELSE 'Alternative Rate' END || ' fee program' || t.note
FROM (VALUES
  ('VELEC',   'Electron',          0.012000, '1.20', 0.014000, '1.40', ''),
  ('VCLAS',   'Classic',           0.012000, '1.20', 0.014000, '1.40', ''),
  ('VGOLD',   'Gold',              0.016000, '1.60', 0.018000, '1.80', ''),
  ('VRWD',    'Rewards',           0.017000, '1.70', 0.019000, '1.90', ''),
  ('VPLAT',   'Platinum',          0.018500, '1.85', 0.020500, '2.05', ''),
  ('VSIG',    'Signature',         0.020500, '2.05', 0.022500, '2.25', ''),
  ('VINF',    'Infinite',          0.021000, '2.10', 0.023000, '2.30', ''),
  ('VUHNW',   'UHNW',              0.022000, '2.20', 0.024000, '2.40', ''),
  ('VBUS',    'Business',          0.021000, '2.10', 0.023000, '2.30', ''),
  ('VCORP',   'Corporate',         0.021000, '2.10', 0.023000, '2.30', ''),
  ('VPUR',    'Purchasing',        0.021000, '2.10', 0.023000, '2.30', ''),
  ('VBUSPLAT','Platinum Business', 0.021500, '2.15', 0.023500, '2.35', ' (D3: no product code mapped to VBUSPLAT yet)'),
  ('VBUSSIG', 'Signature Business',0.022000, '2.20', 0.024000, '2.40', ''),
  ('VBUSINF', 'Infinite Business', 0.022000, '2.20', 0.024000, '2.40', ' (D2: not in EG guide, priced as Signature Business)')
) AS t(tier, product, pos_pct, pos_lbl, ecom_pct, ecom_lbl, note)
CROSS JOIN (VALUES ('POS'), ('ECOM')) AS c(channel)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- ---------------------------------------------------------------------------
-- E. Priority 21: Installment Credential (prepaid) ticket bands (D4).
--    min_ticket inclusive, max_ticket exclusive, in EGP (tenant currency).
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 21, 'DOMESTIC', b.channel, 'Visa',
       'PREPAID', NULL, NULL, NULL, b.min_t, b.max_t,
       b.pct, 0.0000, NULL, 'APPROVED', NULL,
       'EG Visa prepaid (Installment Credential) ' || b.channel || ' ' || b.band || ' ' || b.lbl,
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: FLEX 1 / FLEX (D4: all Visa prepaid priced as Installment Credential)'
FROM (VALUES
  ('POS',  NULL::NUMERIC, 500::NUMERIC,  0.012000, '< EGP 500',  '1.20'),
  ('POS',  500::NUMERIC,  NULL::NUMERIC, 0.016000, '>= EGP 500', '1.60'),
  ('ECOM', NULL::NUMERIC, 500::NUMERIC,  0.014000, '< EGP 500',  '1.40'),
  ('ECOM', 500::NUMERIC,  NULL::NUMERIC, 0.018000, '>= EGP 500', '1.80')
) AS b(channel, min_t, max_t, pct, band, lbl)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- ---------------------------------------------------------------------------
-- F1. Priority 50: Industry Fee Program, ALL products (channel NULL = POS+ECOM).
--     Transport 0.50, Government 0.50 cap EGP 150, Telecom 0.50.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', NULL, 'Visa',
       NULL, NULL, NULL, m.mcc, NULL, NULL,
       m.pct, 0.0000, m.cap, 'APPROVED', NULL,
       'EG Visa MCC ' || m.mcc || ' ' || m.industry || ' ' || m.lbl,
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: Industry Fee Program (' || m.industry || ', all products)'
FROM (VALUES
  ('4111', 'Transport',  0.005000, NULL::NUMERIC, '0.50'),
  ('4112', 'Transport',  0.005000, NULL, '0.50'),
  ('4131', 'Transport',  0.005000, NULL, '0.50'),
  ('4784', 'Transport',  0.005000, NULL, '0.50'),
  ('7523', 'Transport',  0.005000, NULL, '0.50'),
  ('4900', 'Government', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('9211', 'Government', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('9222', 'Government', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('9223', 'Government', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('9311', 'Government', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('9399', 'Government', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('9402', 'Government', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('4814', 'Telecom',    0.005000, NULL, '0.50'),
  ('4816', 'Telecom',    0.005000, NULL, '0.50')
) AS m(mcc, industry, pct, cap, lbl)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- ---------------------------------------------------------------------------
-- F2. Priority 50: Industry Fee Program, CONSUMER products only
--     (one row per CREDIT / DEBIT / PREPAID). Fuel 0.25, Education 0.50 cap 150,
--     Charity 0.50 cap 5, Medical 0.70.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', NULL, 'Visa',
       k.card_type, NULL, NULL, m.mcc, NULL, NULL,
       m.pct, 0.0000, m.cap, 'APPROVED', NULL,
       'EG Visa MCC ' || m.mcc || ' ' || m.industry || ' ' || k.card_type || ' ' || m.lbl,
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: Industry Fee Program (' || m.industry || ', consumer products only)'
FROM (VALUES
  ('5541', 'Fuel',      0.002500, NULL::NUMERIC, '0.25'),
  ('5542', 'Fuel',      0.002500, NULL, '0.25'),
  ('8211', 'Education', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8220', 'Education', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8241', 'Education', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8244', 'Education', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8249', 'Education', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8299', 'Education', 0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8398', 'Charity',   0.005000, 5.0000,   '0.50 cap EGP 5'),
  ('5912', 'Medical',   0.007000, NULL, '0.70'),
  ('8011', 'Medical',   0.007000, NULL, '0.70'),
  ('8021', 'Medical',   0.007000, NULL, '0.70'),
  ('8031', 'Medical',   0.007000, NULL, '0.70'),
  ('8041', 'Medical',   0.007000, NULL, '0.70'),
  ('8042', 'Medical',   0.007000, NULL, '0.70'),
  ('8043', 'Medical',   0.007000, NULL, '0.70'),
  ('8049', 'Medical',   0.007000, NULL, '0.70'),
  ('8050', 'Medical',   0.007000, NULL, '0.70'),
  ('8062', 'Medical',   0.007000, NULL, '0.70'),
  ('8071', 'Medical',   0.007000, NULL, '0.70'),
  ('8099', 'Medical',   0.007000, NULL, '0.70')
) AS m(mcc, industry, pct, cap, lbl)
CROSS JOIN (VALUES ('CREDIT'), ('DEBIT'), ('PREPAID')) AS k(card_type)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- ---------------------------------------------------------------------------
-- F3. Priority 50: Fuel, COMMERCIAL products only, 0.65 (FUEL COMCL).
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', NULL, 'Visa',
       'COMMERCIAL', NULL, NULL, m.mcc, NULL, NULL,
       0.006500, 0.0000, NULL, 'APPROVED', NULL,
       'EG Visa MCC ' || m.mcc || ' Fuel COMMERCIAL 0.65',
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: Industry Fee Program (FUEL COMCL, commercial products only)'
FROM (VALUES ('5541'), ('5542')) AS m(mcc)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- ---------------------------------------------------------------------------
-- F4. Priority 50: Insurance / Autodealer / Rental / Construction, CONSUMER
--     products only, PRODUCT RATE capped at EGP 200 - one row per tier x
--     channel x consumer card type x MCC (cap bounds the percentage part).
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', c.channel, 'Visa',
       k.card_type, t.tier, NULL, m.mcc, NULL, NULL,
       CASE c.channel WHEN 'POS' THEN t.pos_pct ELSE t.ecom_pct END, 0.0000, 200.0000, 'APPROVED', NULL,
       'EG Visa MCC ' || m.mcc || ' ' || m.industry || ' ' || t.product || ' ' || c.channel || ' ' || k.card_type || ' cap EGP 200',
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: Industry Fee Program (' || m.industry || ', consumer, product rate capped EGP 200)'
FROM (VALUES
  ('6300', 'Insurance'),
  ('5511', 'Autodealer'),
  ('6513', 'Rental'),
  ('1520', 'Construction'),
  ('1771', 'Construction'),
  ('5039', 'Construction'),
  ('5085', 'Construction')
) AS m(mcc, industry)
CROSS JOIN (VALUES
  ('VELEC',   'Electron',  0.012000, 0.014000),
  ('VCLAS',   'Classic',   0.012000, 0.014000),
  ('VGOLD',   'Gold',      0.016000, 0.018000),
  ('VRWD',    'Rewards',   0.017000, 0.019000),
  ('VPLAT',   'Platinum',  0.018500, 0.020500),
  ('VSIG',    'Signature', 0.020500, 0.022500),
  ('VINF',    'Infinite',  0.021000, 0.023000),
  ('VUHNW',   'UHNW',      0.022000, 0.024000)
) AS t(tier, product, pos_pct, ecom_pct)
CROSS JOIN (VALUES ('POS'), ('ECOM')) AS c(channel)
CROSS JOIN (VALUES ('CREDIT'), ('DEBIT'), ('PREPAID')) AS k(card_type)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- F4b. Priority 49: same MCCs, consumer card with NO resolvable tier
--      (FILE-sourced tenant) -> Classic rate capped at EGP 200. Below the tier
--      rows so a resolved tier still wins, above the priority-20 product rows.
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 49, 'DOMESTIC', c.channel, 'Visa',
       k.card_type, NULL, NULL, m.mcc, NULL, NULL,
       c.pct, 0.0000, 200.0000, 'APPROVED', NULL,
       'EG Visa MCC ' || m.mcc || ' ' || m.industry || ' default=Classic ' || c.channel || ' ' || k.card_type || ' cap EGP 200',
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: Industry Fee Program (' || m.industry || ', consumer, no BIN tier -> Classic capped EGP 200)'
FROM (VALUES
  ('6300', 'Insurance'),
  ('5511', 'Autodealer'),
  ('6513', 'Rental'),
  ('1520', 'Construction'),
  ('1771', 'Construction'),
  ('5039', 'Construction'),
  ('5085', 'Construction')
) AS m(mcc, industry)
CROSS JOIN (VALUES ('POS', 0.012000), ('ECOM', 0.014000)) AS c(channel, pct)
CROSS JOIN (VALUES ('CREDIT'), ('DEBIT'), ('PREPAID')) AS k(card_type)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

-- ---------------------------------------------------------------------------
-- G. Priority 55: Industry Fee Program WITH TRANSACTION SIZE LIMIT. Consumer
--    only, amount <= EGP 600 (D5), flat EGP 1.50 and 0%. Above EGP 600 the
--    transaction falls through: 5912 to Medical 0.70 (F2), the rest to the
--    product rate. channel NULL = POS + ECOM.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 55, 'DOMESTIC', NULL, 'Visa',
       k.card_type, NULL, NULL, m.mcc, NULL, 600.0100,
       0.000000, 1.5000, NULL, 'APPROVED', NULL,
       'EG Visa MCC ' || m.mcc || ' ' || m.industry || ' ' || k.card_type || ' <= EGP 600 flat EGP 1.50',
       'VISA-EG-IRF v2.8 eff 2024-07-20, applied 2026-10-26: Industry Fee Program with Transaction Size Limit (' || m.industry || ', consumer, <= EGP 600)'
FROM (VALUES
  ('5331', 'Small Retail'),
  ('5399', 'Small Retail'),
  ('5499', 'Small Retail'),
  ('5912', 'Pharmacy'),
  ('5814', 'QSR'),
  ('5994', 'Frontier'),
  ('7216', 'Frontier'),
  ('7699', 'Frontier')
) AS m(mcc, industry)
CROSS JOIN (VALUES ('CREDIT'), ('DEBIT'), ('PREPAID')) AS k(card_type)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_01__visa_eg_irf_guide_align.sql');

ANALYZE interchange_rate_local;
ANALYZE ref_bin_product_tier;

-- ---------------------------------------------------------------------------
-- Verify (expected on first apply): default 2, tier 28, prepaid 4,
-- industry_all 14, industry_consumer 63 (21 MCCs x 3), fuel_commercial 2,
-- capped_product 336, capped_default 42, size_limit 24, uae_copy_left 0.
-- ---------------------------------------------------------------------------
SELECT 'default'           AS chk, COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=15
UNION ALL SELECT 'tier',             COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=20
UNION ALL SELECT 'prepaid',          COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=21
UNION ALL SELECT 'industry_all',     COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=50 AND card_type IS NULL
UNION ALL SELECT 'industry_consumer',COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=50 AND card_type IN ('CREDIT','DEBIT','PREPAID') AND tier IS NULL
UNION ALL SELECT 'fuel_commercial',  COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=50 AND card_type='COMMERCIAL'
UNION ALL SELECT 'capped_product',   COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=50 AND tier IS NOT NULL
UNION ALL SELECT 'capped_default',   COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=49
UNION ALL SELECT 'size_limit',       COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND priority=55
UNION ALL SELECT 'uae_copy_left',    COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='Visa' AND dest='DOMESTIC' AND interchange_pct IN (0.017500, 0.019000) AND priority IN (15, 60);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_01__visa_eg_irf_guide_align.sql')
ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- 2. V2026_10_26_02__mc_eg_manual_align.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_26_02: Egypt (EG) Mastercard DOMESTIC interchange aligned to the
--                 Mastercard "Interchange Manual - Middle East/Africa Region"
--                 (Egypt intracountry sections, user-supplied 2026-10-26).
--                 Supersedes the never-applied DRAFT V2026_08_30_01.
--
-- SCOPE: scheme_group = 'MasterCard', country_code = 'EG', dest = 'DOMESTIC'.
--   * Visa (V2026_10_26_01), Meeza, JCB, UnionPay, any-scheme rows: untouched.
--   * INTERNATIONAL: unchanged, same 1.85% as UAE (business instruction).
--   * scheme_fee_rate: unchanged, same grid as UAE (business instruction).
--
-- WHAT IT REPLACES: the V2026_07_31_05 EG Mastercard card (UAE copy, flat
-- 1.75 / 1.90 with per-MCC rows at the same rate) and the DRAFT priority-30
-- rows if V2026_08_30_01 was ever applied. All EG domestic MasterCard rows
-- (tenant_id IS NULL) are deleted and reseeded.
--
-- MANUAL -> ENGINE MAPPING (priority DESC wins):
--   55  Low Ticket Merchants (5441/5462/5814), consumer, <= USD 10 -> 0.50%
--   50  Acceptor business segments per MCC (consumer per card type, commercial)
--   30  Consumer tier rows (CREDIT / DEBIT / PREPAID x Standard/Premium/Elite)
--       and COMMERCIAL product bands (Comm200/210/215/220)
--   29  COMMERCIAL "All Other Products" (no band): 2.00% < USD 15k, 0.50% above
--   15  last resort (unknown card type / tier) = consumer Standard 1.35%
--
--   CHANNEL: the manual's Card Present (IRD 73/83) and Full UCAF (79) columns
--   are identical for every tier and segment, so rows carry channel NULL
--   (POS + ECOM). Merchant UCAF (24) and Standard/downgrade (75/85) are NOT
--   modeled - the feed carries no UCAF-level or downgrade signal.
--
-- TIER BUCKETS: ref_bin_product_tier is global and buckets MC products into
--   Standard / Premium / Elite. The manual prices per product, so a bucket
--   holding two products takes the SIMPLE AVERAGE until the BIN-volume
--   weighting (docs/deploy/EG_MC_BIN_WEIGHTING_2026-08-30.sql) is run:
--     Standard = Standard/Gold       credit 1.35       debit/prepaid 1.30
--     Premium  = avg(Titanium, Platinum)
--                                    credit 1.925      debit/prepaid 1.875
--     Elite    = avg(World, World Elite)
--                                    credit 2.175      debit/prepaid 2.125
--   World Legend (2.30) / Legend Exclusive (2.35) have no product code in the
--   tier table and fold into Elite.
--   On a FILE-sourced tenant the feed product code resolves Standard
--   (subtype 1) or Premium, so Elite is reachable only with card_type_source
--   = 'BIN'. This migration does NOT flip the setting.
--
-- USD -> EGP (A1, BUSINESS-APPROVED 2026-10-26, single figure used throughout):
--   1 USD = EGP 55.85
--     USD 10     -> EGP 558.50    (low-ticket threshold, govt / EMkt caps)
--     USD 0.25   -> EGP 13.96   (charity cap)
--     USD 0.05   -> EGP 2.79    (commercial govt flat fee)
--     USD 15,000 -> EGP 837,750 (commercial wholesale / all-other threshold)
--   The engine compares store_base_currency_amount (EGP) against min/max_ticket
--   and caps in EGP, so the manual's USD figures must be fixed in EGP.
--   "Less than or equal to USD 10" -> max_ticket 558.51 (exclusive bound).
--
-- DELIBERATE DECISIONS (flagged for business sign-off):
--   D1  Debit / prepaid product codes from the manual that were missing from
--       ref_bin_product_tier are added (global table, BH debit rows are
--       tier-wildcard so BH pricing does not move). Tier by product name:
--       MDS/MDG/MDO/MIU/MKA-MKD/MHA/MHH and the plain prepaid programs ->
--       Standard, MET/MDP/MDH -> Premium, MDW/MHB -> Elite. Codes whose
--       product name is not confirmed are marked 'assumed' in the note.
--   D2  Mobile Top-up (MCC 4814, requires MAID 407260) is NOT seeded - the
--       MAID is not in the feed, so 4814 prices at the tier rate.
--   D3  Commercial Comm215 band mixes MIO (wholesale 2.15 flat) with MWB/BPD
--       (wholesale 2.15 < USD 15k / 0.75 above). The split is applied to the
--       whole band.
--   D4  MAB (Comm220) has no wholesale rate (N/A) -> general 2.20 applies.
--   D5  MDT is typed DEBIT by the BIN file (manual lists it commercial) - it
--       prices on the consumer debit ladder, as in UAE/BH.
--   D6  NOT modeled: Merchant UCAF, Standard/downgrade IRDs, Mastercard
--       Initiated Rewards, payment transactions, MoneySend, ATM, cash-in/out,
--       QR, P2P, disbursements, Rapid Merchant Settlement.
--
-- Splitter-safe (no dollar quoting, no semicolons inside comments).
-- GUARDED one-time flip (ledger convention): every statement runs only while
-- this file's schema_migration_log row is absent. Apply once via psql on
-- UAT/prod, then re-ingest / reprice the affected EG months.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A. Mastercard product code -> tier (global). Credit codes already exist
--    (V2026_08_29_03/04). Add the consumer debit / prepaid codes the Egypt
--    manual lists so they stop falling to the engine's 'Premium' default.
-- ---------------------------------------------------------------------------
INSERT INTO ref_bin_product_tier (product_code, card_tier, card_class, note)
SELECT v.* FROM (VALUES
  -- consumer credit (re-asserted, no-op where present)
  ('MCS', 'Standard', 'CONSUMER', 'Mastercard Standard'),
  ('MCG', 'Standard', 'CONSUMER', 'Mastercard Gold'),
  ('MCC', 'Standard', 'CONSUMER', 'Mastercard Credit (mixed BIN)'),
  ('MCE', 'Standard', 'CONSUMER', 'Mastercard Electronic (assumed Standard)'),
  ('GCP', 'Standard', 'CONSUMER', 'Gold Mastercard Card (assumed Standard/Gold)'),
  ('MIU', 'Standard', 'CONSUMER', 'Mastercard Unembossed (assumed Standard)'),
  ('MKE', 'Standard', 'CONSUMER', 'Mastercard credit program variant (assumed Standard)'),
  ('MCT', 'Premium',  'CONSUMER', 'Mastercard Titanium'),
  ('MPL', 'Premium',  'CONSUMER', 'Mastercard Platinum'),
  ('MCW', 'Elite',    'CONSUMER', 'World Mastercard'),
  ('MWE', 'Elite',    'CONSUMER', 'World Elite Mastercard'),
  ('MWJ', 'Elite',    'CONSUMER', 'World Legend family (assumed Elite)'),
  ('MWK', 'Elite',    'CONSUMER', 'World Legend family (assumed Elite)'),
  -- consumer debit (manual: MDG MDH MDO MDP MDS MDW MET MHB MHH MKA MKB MKC MKD)
  ('MDS', 'Standard', 'CONSUMER', 'Debit Mastercard Standard'),
  ('MDG', 'Standard', 'CONSUMER', 'Debit Mastercard Gold'),
  ('MDO', 'Standard', 'CONSUMER', 'Debit Mastercard Other (assumed Standard)'),
  ('MKA', 'Standard', 'CONSUMER', 'Debit program variant (assumed Standard)'),
  ('MKB', 'Standard', 'CONSUMER', 'Debit program variant (assumed Standard)'),
  ('MKC', 'Standard', 'CONSUMER', 'Debit program variant (assumed Standard)'),
  ('MKD', 'Standard', 'CONSUMER', 'Debit program variant (assumed Standard)'),
  ('MHH', 'Standard', 'CONSUMER', 'Debit program variant (assumed Standard)'),
  ('MET', 'Premium',  'CONSUMER', 'Debit Mastercard Titanium'),
  ('MDP', 'Premium',  'CONSUMER', 'Debit Mastercard Platinum'),
  ('MDH', 'Premium',  'CONSUMER', 'Debit Mastercard Platinum variant (assumed Premium)'),
  ('MDW', 'Elite',    'CONSUMER', 'World Debit Mastercard'),
  ('MHB', 'Elite',    'CONSUMER', 'World Elite Debit Mastercard (assumed Elite)'),
  -- consumer prepaid (manual: GPP MGP MHA MIP MPA MPF MPG MPM MPN MPO MPP MPR MPT MPV MPX MPY MRC MRG MRH MWP SUR TPM WPD)
  ('MRG', 'Standard', 'CONSUMER', 'Prepaid Gold'),
  ('MPG', 'Standard', 'CONSUMER', 'Prepaid Gold variant'),
  ('MGP', 'Standard', 'CONSUMER', 'Gold Prepaid'),
  ('GPP', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MHA', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MIP', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPA', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPF', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPM', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPN', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPO', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPP', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPR', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPT', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPV', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPX', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MPY', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MRC', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('SUR', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('TPM', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('WPD', 'Standard', 'CONSUMER', 'Prepaid program (assumed Standard)'),
  ('MRH', 'Premium',  'CONSUMER', 'Prepaid Platinum'),
  ('MRW', 'Elite',    'CONSUMER', 'Prepaid World'),
  ('MWP', 'Elite',    'CONSUMER', 'World Prepaid')
) AS v(product_code, card_tier, card_class, note)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql')
ON CONFLICT (product_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- B. Drop the EG Mastercard domestic card (UAE copy + any applied draft).
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local
WHERE country_code = 'EG' AND tenant_id IS NULL
  AND scheme_group = 'MasterCard' AND dest = 'DOMESTIC'
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- C. Priority 15: last resort (unknown card type / tier) = Standard 1.35.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 15, 'DOMESTIC', NULL, 'MasterCard',
       NULL, NULL, NULL, NULL, NULL, NULL,
       0.013500, 0.0000, NULL, 'APPROVED', NULL,
       'EG MC default = consumer Standard 1.35 (unknown card type / tier)',
       'MC-MEA manual Egypt intracountry, applied 2026-10-26: last resort'
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- D. Priority 30: consumer tier rows, All Others column (CP == Full UCAF).
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 30, 'DOMESTIC', NULL, 'MasterCard',
       t.card_type, t.tier, NULL, NULL, NULL, NULL,
       t.pct, 0.0000, NULL, 'APPROVED', NULL,
       'EG MC ' || LOWER(t.card_type) || ' ' || t.tier || ' ' || t.lbl,
       'MC-MEA manual Egypt intracountry consumer ' || LOWER(t.card_type) || ', AllOth CP/Full UCAF, applied 2026-10-26' || t.note
FROM (VALUES
  ('CREDIT',  'Standard', 0.013500, '1.35 (Std/Gold)',                       ''),
  ('CREDIT',  'Premium',  0.019250, '1.925 (avg Titanium 1.85 / Platinum 2.00)', ' - PLACEHOLDER-AVG, replace with BIN-weighted figure'),
  ('CREDIT',  'Elite',    0.021750, '2.175 (avg World 2.15 / World Elite 2.20)', ' - PLACEHOLDER-AVG, replace with BIN-weighted figure'),
  ('DEBIT',   'Standard', 0.013000, '1.30 (Std/Gold)',                       ''),
  ('DEBIT',   'Premium',  0.018750, '1.875 (avg Titanium 1.80 / Platinum 1.95)', ' - PLACEHOLDER-AVG, replace with BIN-weighted figure'),
  ('DEBIT',   'Elite',    0.021250, '2.125 (avg World 2.10 / Elite 2.15)',   ' - PLACEHOLDER-AVG, replace with BIN-weighted figure'),
  ('PREPAID', 'Standard', 0.013000, '1.30 (Std/Gold)',                       ''),
  ('PREPAID', 'Premium',  0.018750, '1.875 (avg Titanium 1.80 / Platinum 1.95)', ' - PLACEHOLDER-AVG, replace with BIN-weighted figure'),
  ('PREPAID', 'Elite',    0.021250, '2.125 (avg World 2.10 / Elite 2.15)',   ' - PLACEHOLDER-AVG, replace with BIN-weighted figure')
) AS t(card_type, tier, pct, lbl, note)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- E. Priority 30: COMMERCIAL General bands. Priority 29: "All Other
--    Products" (commercial BIN with no band): 2.00 below USD 15k, 0.50 above.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 30, 'DOMESTIC', NULL, 'MasterCard',
       'COMMERCIAL', b.band, NULL, NULL, NULL, NULL,
       b.pct, 0.0000, NULL, 'APPROVED', NULL,
       'EG MC commercial ' || b.lbl,
       'MC-MEA manual Egypt intracountry commercial General, applied 2026-10-26'
FROM (VALUES
  ('Comm200', 0.020000, '2.00 (MCO/MEO/MWO/MCB/MCP)'),
  ('Comm210', 0.021000, '2.10 (MEB)'),
  ('Comm215', 0.021500, '2.15 (MIO/BPD/MWB)'),
  ('Comm220', 0.022000, '2.20 (MAB)')
) AS b(band, pct, lbl)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 29, 'DOMESTIC', NULL, 'MasterCard',
       'COMMERCIAL', NULL, NULL, NULL, b.min_t, b.max_t,
       b.pct, 0.0000, NULL, 'APPROVED', NULL,
       'EG MC commercial all-other-products ' || b.lbl,
       'MC-MEA manual Egypt intracountry commercial All Other Products, applied 2026-10-26 (A1: USD 15,000 = EGP 837,750)'
FROM (VALUES
  (NULL::NUMERIC,   837750::NUMERIC, 0.020000, '2.00 < USD 15k'),
  (837750::NUMERIC, NULL::NUMERIC,   0.005000, '0.50 >= USD 15k')
) AS b(min_t, max_t, pct, lbl)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- F1. Priority 50: consumer acceptor business segments shared by credit,
--     debit and prepaid at the SAME rate (one row per card type per MCC).
--     Charities 0.50 cap USD 0.25, GvtSvc 0.40 cap USD 10, Education 0.50
--     cap EGP 150, InsCon 0.50 cap EGP 200, Petroleum 0.25, Medical 0.65.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', NULL, 'MasterCard',
       k.card_type, NULL, NULL, m.mcc, NULL, NULL,
       m.pct, 0.0000, m.cap, 'APPROVED', NULL,
       'EG MC MCC ' || m.mcc || ' ' || m.segment || ' ' || k.card_type || ' ' || m.lbl,
       'MC-MEA manual Egypt intracountry consumer segment ' || m.segment || ', applied 2026-10-26'
FROM (VALUES
  ('8398', 'Charities',  0.005000, 13.9600, '0.50 cap USD 0.25 = EGP 13.96'),
  ('8661', 'Charities',  0.005000, 13.9600, '0.50 cap USD 0.25 = EGP 13.96'),
  ('4900', 'GvtSvc',     0.004000, 558.5000, '0.40 cap USD 10 = EGP 558.50'),
  ('9211', 'GvtSvc',     0.004000, 558.5000, '0.40 cap USD 10 = EGP 558.50'),
  ('9222', 'GvtSvc',     0.004000, 558.5000, '0.40 cap USD 10 = EGP 558.50'),
  ('9223', 'GvtSvc',     0.004000, 558.5000, '0.40 cap USD 10 = EGP 558.50'),
  ('9311', 'GvtSvc',     0.004000, 558.5000, '0.40 cap USD 10 = EGP 558.50'),
  ('9399', 'GvtSvc',     0.004000, 558.5000, '0.40 cap USD 10 = EGP 558.50'),
  ('9402', 'GvtSvc',     0.004000, 558.5000, '0.40 cap USD 10 = EGP 558.50'),
  ('9405', 'GvtSvc',     0.004000, 558.5000, '0.40 cap USD 10 = EGP 558.50'),
  ('8211', 'Education',  0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8220', 'Education',  0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8241', 'Education',  0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8244', 'Education',  0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8249', 'Education',  0.005000, 150.0000, '0.50 cap EGP 150'),
  ('8299', 'Education',  0.005000, 150.0000, '0.50 cap EGP 150'),
  ('1520', 'InsCon',     0.005000, 200.0000, '0.50 cap EGP 200'),
  ('1771', 'InsCon',     0.005000, 200.0000, '0.50 cap EGP 200'),
  ('5039', 'InsCon',     0.005000, 200.0000, '0.50 cap EGP 200'),
  ('5085', 'InsCon',     0.005000, 200.0000, '0.50 cap EGP 200'),
  ('5511', 'InsCon',     0.005000, 200.0000, '0.50 cap EGP 200'),
  ('6300', 'InsCon',     0.005000, 200.0000, '0.50 cap EGP 200'),
  ('6513', 'InsCon',     0.005000, 200.0000, '0.50 cap EGP 200'),
  ('5541', 'Petroleum',  0.002500, NULL::NUMERIC, '0.25'),
  ('5542', 'Petroleum',  0.002500, NULL, '0.25'),
  ('5960', 'Medical',    0.006500, NULL, '0.65'),
  ('7941', 'Medical',    0.006500, NULL, '0.65'),
  ('8011', 'Medical',    0.006500, NULL, '0.65'),
  ('8021', 'Medical',    0.006500, NULL, '0.65'),
  ('8031', 'Medical',    0.006500, NULL, '0.65'),
  ('8041', 'Medical',    0.006500, NULL, '0.65'),
  ('8042', 'Medical',    0.006500, NULL, '0.65'),
  ('8043', 'Medical',    0.006500, NULL, '0.65'),
  ('8050', 'Medical',    0.006500, NULL, '0.65'),
  ('8062', 'Medical',    0.006500, NULL, '0.65'),
  ('8071', 'Medical',    0.006500, NULL, '0.65'),
  ('8099', 'Medical',    0.006500, NULL, '0.65')
) AS m(mcc, segment, pct, cap, lbl)
CROSS JOIN (VALUES ('CREDIT'), ('DEBIT'), ('PREPAID')) AS k(card_type)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- F2. Priority 50: Transit - credit 0.75, debit/prepaid 0.50.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', NULL, 'MasterCard',
       k.card_type, NULL, NULL, m.mcc, NULL, NULL,
       k.pct, 0.0000, NULL, 'APPROVED', NULL,
       'EG MC MCC ' || m.mcc || ' Transit ' || k.card_type || ' ' || k.lbl,
       'MC-MEA manual Egypt intracountry consumer segment Transit, applied 2026-10-26'
FROM (VALUES ('4011'), ('4111'), ('4121'), ('4131'), ('4411'), ('4789'), ('7523')) AS m(mcc)
CROSS JOIN (VALUES ('CREDIT', 0.007500, '0.75'), ('DEBIT', 0.005000, '0.50'), ('PREPAID', 0.005000, '0.50')) AS k(card_type, pct, lbl)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- F3. Priority 50: Emerging and Micro Merchants - debit/prepaid ONLY,
--     0.50 cap USD 10 (= EGP 558.50). Credit on these MCCs takes the tier rate.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', NULL, 'MasterCard',
       k.card_type, NULL, NULL, m.mcc, NULL, NULL,
       0.005000, 0.0000, 558.5000, 'APPROVED', NULL,
       'EG MC MCC ' || m.mcc || ' EMkt ' || k.card_type || ' 0.50 cap USD 10 = EGP 558.50',
       'MC-MEA manual Egypt intracountry debit/prepaid segment Emerging and Micro Merchants, applied 2026-10-26'
FROM (VALUES ('1740'), ('1750'), ('1799'), ('4225'), ('5065'), ('5099'), ('5200'), ('5521'),
             ('5531'), ('5533'), ('5561'), ('5571'), ('5592'), ('5943'), ('5945'), ('7832')) AS m(mcc)
CROSS JOIN (VALUES ('DEBIT'), ('PREPAID')) AS k(card_type)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- G. Priority 55: Low Ticket Merchants (consumer): <= USD 10 (= EGP 558.50,
--    max_ticket 558.51 exclusive) -> 0.50%. Above falls to the tier rate.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 55, 'DOMESTIC', NULL, 'MasterCard',
       k.card_type, NULL, NULL, m.mcc, NULL, 558.5100,
       0.005000, 0.0000, NULL, 'APPROVED', NULL,
       'EG MC MCC ' || m.mcc || ' Low Ticket ' || k.card_type || ' <= USD 10 (EGP 558.50) 0.50',
       'MC-MEA manual Egypt intracountry consumer segment Low Ticket Merchants, applied 2026-10-26 (A1: USD 10 = EGP 558.50)'
FROM (VALUES ('5441'), ('5462'), ('5814')) AS m(mcc)
CROSS JOIN (VALUES ('CREDIT'), ('DEBIT'), ('PREPAID')) AS k(card_type)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- H1. Priority 50: COMMERCIAL Government Services and Utilities
--     0.10% + USD 0.05 (= EGP 2.79 flat), and Charities 0.50 cap USD 0.25.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', NULL, 'MasterCard',
       'COMMERCIAL', NULL, NULL, m.mcc, NULL, NULL,
       m.pct, m.flat, m.cap, 'APPROVED', NULL,
       'EG MC MCC ' || m.mcc || ' commercial ' || m.segment || ' ' || m.lbl,
       'MC-MEA manual Egypt intracountry commercial segment ' || m.segment || ', applied 2026-10-26 (A1 USD->EGP)'
FROM (VALUES
  ('4900', 'GvtSvc',    0.001000, 2.7900, NULL::NUMERIC, '0.10 + USD 0.05 (EGP 2.79)'),
  ('9211', 'GvtSvc',    0.001000, 2.7900, NULL, '0.10 + USD 0.05 (EGP 2.79)'),
  ('9222', 'GvtSvc',    0.001000, 2.7900, NULL, '0.10 + USD 0.05 (EGP 2.79)'),
  ('9223', 'GvtSvc',    0.001000, 2.7900, NULL, '0.10 + USD 0.05 (EGP 2.79)'),
  ('9311', 'GvtSvc',    0.001000, 2.7900, NULL, '0.10 + USD 0.05 (EGP 2.79)'),
  ('9399', 'GvtSvc',    0.001000, 2.7900, NULL, '0.10 + USD 0.05 (EGP 2.79)'),
  ('9402', 'GvtSvc',    0.001000, 2.7900, NULL, '0.10 + USD 0.05 (EGP 2.79)'),
  ('9405', 'GvtSvc',    0.001000, 2.7900, NULL, '0.10 + USD 0.05 (EGP 2.79)'),
  ('8398', 'Charities', 0.005000, 0.0000, 13.9600, '0.50 cap USD 0.25 (EGP 13.96)'),
  ('8661', 'Charities', 0.005000, 0.0000, 13.9600, '0.50 cap USD 0.25 (EGP 13.96)')
) AS m(mcc, segment, pct, flat, cap, lbl)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

-- ---------------------------------------------------------------------------
-- H2. Priority 50: COMMERCIAL Wholesale (5013/5039/5137/5300): band rate
--     below USD 15k, 0.75 above (Comm200/210/215). Comm220 = N/A -> general.
--     No-band commercial: general all-other rule (E) already splits at 15k.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 50, 'DOMESTIC', NULL, 'MasterCard',
       'COMMERCIAL', b.band, NULL, m.mcc, s.min_t, s.max_t,
       CASE WHEN s.min_t IS NULL THEN b.pct ELSE 0.007500 END, 0.0000, NULL, 'APPROVED', NULL,
       'EG MC MCC ' || m.mcc || ' commercial Wholesale ' || b.band || ' ' || s.lbl,
       'MC-MEA manual Egypt intracountry commercial segment Wholesale, applied 2026-10-26 (A1: USD 15,000 = EGP 837,750, D3)'
FROM (VALUES ('5013'), ('5039'), ('5137'), ('5300')) AS m(mcc)
CROSS JOIN (VALUES ('Comm200', 0.020000), ('Comm210', 0.021000), ('Comm215', 0.021500)) AS b(band, pct)
CROSS JOIN (VALUES (NULL::NUMERIC, 837750::NUMERIC, '< USD 15k band rate'), (837750::NUMERIC, NULL::NUMERIC, '>= USD 15k 0.75')) AS s(min_t, max_t, lbl)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename = 'V2026_10_26_02__mc_eg_manual_align.sql');

ANALYZE interchange_rate_local;
ANALYZE ref_bin_product_tier;

-- ---------------------------------------------------------------------------
-- Verify (expected on first apply): default 1, consumer_tier 9,
-- commercial_band 4, commercial_other 2, segments_consumer 111 (37x3),
-- transit 21, emkt 32, low_ticket 9, commercial_segments 10, wholesale 24,
-- uae_copy_left 0.
-- ---------------------------------------------------------------------------
SELECT 'default'            AS chk, COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=15
UNION ALL SELECT 'consumer_tier',      COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=30 AND card_type<>'COMMERCIAL'
UNION ALL SELECT 'commercial_band',    COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=30 AND card_type='COMMERCIAL'
UNION ALL SELECT 'commercial_other',   COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=29
UNION ALL SELECT 'segments_consumer',  COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=50 AND card_type<>'COMMERCIAL' AND label NOT LIKE '%Transit%' AND label NOT LIKE '%EMkt%'
UNION ALL SELECT 'transit',            COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=50 AND label LIKE '%Transit%'
UNION ALL SELECT 'emkt',               COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=50 AND label LIKE '%EMkt%'
UNION ALL SELECT 'low_ticket',         COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=55
UNION ALL SELECT 'commercial_segments',COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=50 AND card_type='COMMERCIAL' AND tier IS NULL
UNION ALL SELECT 'wholesale',          COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND priority=50 AND card_type='COMMERCIAL' AND tier IS NOT NULL
UNION ALL SELECT 'uae_copy_left',      COUNT(*) FROM interchange_rate_local WHERE country_code='EG' AND tenant_id IS NULL AND scheme_group='MasterCard' AND dest='DOMESTIC' AND interchange_pct IN (0.017500, 0.019000) AND priority IN (15, 60);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_02__mc_eg_manual_align.sql')
ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- 3. V2026_10_26_03__eg_terminal_channel_map.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_26_03: Egypt terminal type -> channel map, from the EG processor
--                 feed (user-supplied list 2026-10-26).
--
-- WHY: every EG transaction resolved fee_resolution_status = UNMAPPED_CHANNEL
-- (25,072 of 25,075 rows on 15-20 Oct 2026), so interchange AND scheme fee
-- were stored NULL and the dashboards showed 0.00. The V2026_08_10_01 EG seed
-- only knew POS / ECOM / ECOM PROFILE / MPGS / PAY BY LINK as assumptions and
-- the real feed carries device model names.
--
-- The engine matches raw_type = UPPER(TRIM(dim_terminal.type)), so values are
-- stored upper-cased. 'None' is mapped both as the literal token and as the
-- empty string (same treatment as BH V2026_08_26_01). No '*' wildcard is
-- added on purpose: a new, unlisted device type must surface as
-- UNMAPPED_CHANNEL rather than silently price as POS.
--
-- Idempotent (NOT EXISTS per row), splitter-safe. After applying, re-price
-- the affected EG months (fees compute at ingest).
-- ============================================================================
INSERT INTO terminal_channel_map (tenant_id, country_code, raw_type, channel, note)
SELECT NULL, 'EG', v.raw_type, v.channel, 'EG processor feed terminal type, confirmed 2026-10-26'
FROM (VALUES
  ('NONE',                               'POS'),
  ('',                                   'POS'),
  ('AISINO A90',                         'POS'),
  ('AISINO V50',                         'POS'),
  ('AISINO A80',                         'POS'),
  ('AISINO A75',                         'POS'),
  ('PAX A920',                           'POS'),
  ('PAX A920 PRO',                       'POS'),
  ('PAX A910',                           'POS'),
  ('PAX A60',                            'POS'),
  ('PAX A50',                            'POS'),
  ('SOFTPOS',                            'POS'),
  ('AFS ONE',                            'POS'),
  ('N62 (MINI DEVICE WITH NO PRINTER)',  'POS'),
  ('N96 (NORMAL POS DEVICE)',            'POS'),
  ('PLANET POS',                         'POS'),
  ('ECOM PROFILE',                       'ECOM'),
  ('PAY ON',                             'ECOM'),
  ('PAY BY LINK',                        'ECOM')
) AS v(raw_type, channel)
WHERE NOT EXISTS (
  SELECT 1 FROM terminal_channel_map x
  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL AND x.raw_type = v.raw_type
);

ANALYZE terminal_channel_map;

-- Verify: every listed type should show a channel.
SELECT raw_type, channel FROM terminal_channel_map
WHERE country_code = 'EG' AND tenant_id IS NULL ORDER BY channel, raw_type;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_03__eg_terminal_channel_map.sql')
ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- 4. V2026_10_26_04__eg_scheme_fee_uae_grid.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_26_04: Egypt scheme fees = the ORIGINAL UAE grid (business
--                 instruction 2026-10-26: "same as UAE, the old one, not the
--                 October 2026 Mastercard changes").
--
-- Grid (V2026_09_14_02 shape, pre-Oct-2026 figures):
--   Visa / MasterCard / Amex / any-scheme wildcard:
--       DOMESTIC POS 0.11%, DOMESTIC ECOM 0.14%,
--       INTERNATIONAL POS 0.75%, INTERNATIONAL ECOM 0.90%
--   JCB / UnionPay: 0.05% everywhere
--   Meeza has no scheme-specific row and takes the wildcard (0.11 / 0.14).
--
-- The V2026_10_05_01 (+0.03% intl) and V2026_10_05_02 (domestic credit
-- enablement fund) Mastercard changes are UAE-only and are NOT copied.
--
-- WHY: on UAT the EG scheme fee resolved NULL. Whatever state the EG rows
-- were in (missing, or PLACEHOLDER from the V2026_08_10_01 sweep), this file
-- deletes every EG country-level scheme_fee_rate row and reseeds the 24
-- combinations as APPROVED with a BUSINESS-APPROVED note, which the
-- placeholder sweep never demotes again.
--
-- GUARDED one-time flip (ledger convention), splitter-safe. Apply once via
-- psql, then re-price the affected EG months.
-- ============================================================================

DELETE FROM scheme_fee_rate
WHERE country_code = 'EG' AND tenant_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_10_26_04__eg_scheme_fee_uae_grid.sql');

INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT 'EG', NULL, g.dest, g.channel, g.scheme_group, g.fee_pct, 0, 'APPROVED',
       'BUSINESS-APPROVED 2026-10-26: EG adopts the original UAE scheme-fee grid (pre-Oct-2026 figures).'
FROM (VALUES
    -- DOMESTIC / POS
    ('DOMESTIC',      'POS',  CAST(NULL AS VARCHAR), 0.0011),
    ('DOMESTIC',      'POS',  'Visa',                0.0011),
    ('DOMESTIC',      'POS',  'MasterCard',          0.0011),
    ('DOMESTIC',      'POS',  'Amex',                0.0011),
    ('DOMESTIC',      'POS',  'JCB',                 0.0005),
    ('DOMESTIC',      'POS',  'UnionPay',            0.0005),
    -- DOMESTIC / ECOM
    ('DOMESTIC',      'ECOM', CAST(NULL AS VARCHAR), 0.0014),
    ('DOMESTIC',      'ECOM', 'Visa',                0.0014),
    ('DOMESTIC',      'ECOM', 'MasterCard',          0.0014),
    ('DOMESTIC',      'ECOM', 'Amex',                0.0014),
    ('DOMESTIC',      'ECOM', 'JCB',                 0.0005),
    ('DOMESTIC',      'ECOM', 'UnionPay',            0.0005),
    -- INTERNATIONAL / POS
    ('INTERNATIONAL', 'POS',  CAST(NULL AS VARCHAR), 0.0075),
    ('INTERNATIONAL', 'POS',  'Visa',                0.0075),
    ('INTERNATIONAL', 'POS',  'MasterCard',          0.0075),
    ('INTERNATIONAL', 'POS',  'Amex',                0.0075),
    ('INTERNATIONAL', 'POS',  'JCB',                 0.0005),
    ('INTERNATIONAL', 'POS',  'UnionPay',            0.0005),
    -- INTERNATIONAL / ECOM
    ('INTERNATIONAL', 'ECOM', CAST(NULL AS VARCHAR), 0.0090),
    ('INTERNATIONAL', 'ECOM', 'Visa',                0.0090),
    ('INTERNATIONAL', 'ECOM', 'MasterCard',          0.0090),
    ('INTERNATIONAL', 'ECOM', 'Amex',                0.0090),
    ('INTERNATIONAL', 'ECOM', 'JCB',                 0.0005),
    ('INTERNATIONAL', 'ECOM', 'UnionPay',            0.0005)
) AS g(dest, channel, scheme_group, fee_pct)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_10_26_04__eg_scheme_fee_uae_grid.sql');

ANALYZE scheme_fee_rate;

-- Verify: expect 24 rows, all APPROVED.
SELECT rate_status, COUNT(*) FROM scheme_fee_rate
WHERE country_code = 'EG' AND tenant_id IS NULL GROUP BY 1;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_04__eg_scheme_fee_uae_grid.sql')
ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- 5. V2026_10_26_05__eg_meeza_interchange_reassert.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_26_05: re-assert Egypt Meeza interchange at a flat 0.50%.
--
-- WHY: UAT has NO Meeza rows in interchange_rate_local although
-- V2026_08_10_02 (interim seed) and V2026_08_12_01 (0.50% update + INTL row)
-- are on the startup list. V2026_08_12_01 only UPDATEs the domestic row the
-- earlier seed should have created, so if that seed never landed the update
-- is a no-op and Meeza prices at the EG any-scheme fallback.
--
-- This file inserts BOTH rows directly (DOMESTIC + INTERNATIONAL, all
-- channels, all card types, priority 66 = above every Visa / Mastercard row),
-- each guarded by NOT EXISTS, and makes sure the MEEZA scheme token resolves.
-- Rate per the 2026-08-12 business instruction (MEEZA-FLAT-0.5).
-- Idempotent, splitter-safe. Re-price the affected EG months after applying.
-- ============================================================================

-- Scheme token (global table, same values as V2026_08_10_02). Guarded on the
-- code rather than the id so it also converges if id 19 is already taken.
INSERT INTO ref_card_scheme (id, is_active, code, name, group_code, group_name, status, card_type, card_subtype)
SELECT 19, true, 'MEEZA', 'Meeza', 'MEZA', 'Meeza', 1, 2, 0
WHERE NOT EXISTS (SELECT 1 FROM ref_card_scheme WHERE UPPER(code) = 'MEEZA' OR group_name = 'Meeza');

INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 66, d.dest, NULL, 'Meeza',
       NULL, NULL, NULL, NULL, NULL, NULL,
       0.005000, 0.0000, NULL, 'APPROVED', NULL,
       'Meeza 0.50% (all channels, all card types)',
       'MEEZA-FLAT-0.5 business instruction 2026-08-12, re-asserted 2026-10-26 (UAT had no Meeza rows).'
FROM (VALUES ('DOMESTIC'), ('INTERNATIONAL')) AS d(dest)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.scheme_group = 'Meeza' AND x.dest = d.dest);

ANALYZE interchange_rate_local;

-- Verify: expect DOMESTIC and INTERNATIONAL at 0.005000, APPROVED.
SELECT dest, priority, interchange_pct, rate_status FROM interchange_rate_local
WHERE country_code = 'EG' AND tenant_id IS NULL AND scheme_group = 'Meeza' ORDER BY dest;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_05__eg_meeza_interchange_reassert.sql')
ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- 6. V2026_10_26_06__eg_meeza_scheme_fee.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_26_06: Egypt Meeza scheme fee = 0.05% on every destination x
--                 channel (business instruction 2026-10-26).
--
-- Until now Meeza had no scheme-specific row and took the EG any-scheme
-- wildcard (0.11% POS / 0.14% ECOM domestic, 0.75 / 0.90 international).
-- A scheme-specific row beats the wildcard in the fee engine's scheme
-- LATERAL, so these four rows now govern. Meeza INTERCHANGE stays 0.50%
-- (V2026_10_26_05).
--
-- Idempotent (NOT EXISTS per row), splitter-safe. Re-price EG months after.
-- ============================================================================
INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT 'EG', NULL, g.dest, g.channel, 'Meeza', 0.0005, 0, 'APPROVED',
       'BUSINESS-APPROVED 2026-10-26: Meeza scheme fee 0.05% all destinations and channels.'
FROM (VALUES
    ('DOMESTIC',      'POS'),
    ('DOMESTIC',      'ECOM'),
    ('INTERNATIONAL', 'POS'),
    ('INTERNATIONAL', 'ECOM')
) AS g(dest, channel)
WHERE NOT EXISTS (SELECT 1 FROM scheme_fee_rate x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.scheme_group = 'Meeza' AND x.dest = g.dest AND x.channel = g.channel);

ANALYZE scheme_fee_rate;

-- Verify: expect 4 rows at 0.0005, APPROVED.
SELECT dest, channel, fee_pct, rate_status FROM scheme_fee_rate
WHERE country_code = 'EG' AND tenant_id IS NULL AND scheme_group = 'Meeza' ORDER BY dest, channel;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_06__eg_meeza_scheme_fee.sql')
ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- 7. V2026_10_26_07__eg_intl_and_fallback_rows.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_26_07: Egypt INTERNATIONAL + any-scheme fallback interchange rows,
--                 same figures as UAE (business instruction 2026-10-26).
--
-- WHY: after V2026_10_26_01..06 a full EG reprice still left 280,753 rows
-- NO_RATE_FOUND (RESOLVED 4,089,343). The V2026_07_31_05 EG seed that
-- carried the INTERNATIONAL 1.85% row and the any-scheme / JCB / UnionPay
-- domestic fallbacks evidently never landed on UAT (same symptom as Meeza),
-- and the Visa / Mastercard reseeds are domestic-only by design.
--
-- Rows (country-level, APPROVED, BUSINESS-APPROVED note so the V2026_08_10_01
-- placeholder sweep never demotes them):
--   priority 1   INTERNATIONAL, any scheme / channel / card      1.85%
--   priority 10  DOMESTIC POS  any scheme (Amex, unknown tokens)  1.75%
--   priority 10  DOMESTIC ECOM any scheme                         1.90%
--   priority 65  DOMESTIC JCB      all channels                   1.75%
--   priority 65  DOMESTIC UnionPay all channels                   1.75%
-- Visa / Mastercard / Meeza domestic rows (priority 15+) still win over the
-- priority-10 wildcard. Idempotent (NOT EXISTS per row), splitter-safe.
-- Re-price EG months after applying.
-- ============================================================================
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', v.priority, v.dest, v.channel, v.scheme_group,
       NULL, NULL, NULL, NULL, NULL, NULL,
       v.pct, 0.0000, NULL, 'APPROVED', NULL, v.label,
       'BUSINESS-APPROVED 2026-10-26: EG ' || v.what || ' same as UAE (re-asserted, UAT had no row).'
FROM (VALUES
  (1,  'INTERNATIONAL', NULL::VARCHAR, NULL::VARCHAR, 0.018500, 'EG intl flat 1.85 (same as UAE)',               'international interchange 1.85%'),
  (10, 'DOMESTIC',      'POS',         NULL::VARCHAR, 0.017500, 'EG any-scheme POS domestic default 1.75',       'any-scheme domestic POS fallback'),
  (10, 'DOMESTIC',      'ECOM',        NULL::VARCHAR, 0.019000, 'EG any-scheme ECOM domestic default 1.90',      'any-scheme domestic ECOM fallback'),
  (65, 'DOMESTIC',      NULL::VARCHAR, 'JCB',         0.017500, 'EG JCB domestic flat 1.75 (same as UAE)',      'JCB domestic 1.75%'),
  (65, 'DOMESTIC',      NULL::VARCHAR, 'UnionPay',    0.017500, 'EG UnionPay domestic flat 1.75 (same as UAE)', 'UnionPay domestic 1.75%')
) AS v(priority, dest, channel, scheme_group, pct, label, what)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.dest = v.dest
                    AND COALESCE(x.channel, '*') = COALESCE(v.channel, '*')
                    AND COALESCE(x.scheme_group, '*') = COALESCE(v.scheme_group, '*')
                    AND x.card_type IS NULL AND x.tier IS NULL AND x.mcc IS NULL AND x.mcc_sector IS NULL);

-- If an older PLACEHOLDER copy of the international row exists, approve it.
UPDATE interchange_rate_local
   SET rate_status = 'APPROVED',
       source_note = 'BUSINESS-APPROVED 2026-10-26: EG international interchange 1.85% same as UAE.'
 WHERE country_code = 'EG' AND tenant_id IS NULL AND dest = 'INTERNATIONAL'
   AND scheme_group IS NULL AND rate_status <> 'APPROVED';

ANALYZE interchange_rate_local;

-- Verify: expect the 5 rows below, all APPROVED.
SELECT priority, dest, channel, scheme_group, interchange_pct, rate_status
FROM interchange_rate_local
WHERE country_code = 'EG' AND tenant_id IS NULL
  AND ((dest = 'INTERNATIONAL' AND scheme_group IS NULL)
    OR (dest = 'DOMESTIC' AND priority IN (10, 65) AND mcc IS NULL))
ORDER BY priority, dest, channel, scheme_group;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_07__eg_intl_and_fallback_rows.sql')
ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- 8. V2026_10_27_01__eg_domestic_scheme_fee_017.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_27_01: Egypt DOMESTIC scheme fee (business instruction 2026-10-27):
--                 POS 0.17% (17 bps), ECOM 0.20% (20 bps), for Visa /
--                 MasterCard / Amex and the any-scheme wildcard.
--
-- Replaces the UAE-copied domestic figures from V2026_10_26_04 (0.11% POS /
-- 0.14% ECOM). Unchanged: INTERNATIONAL (0.75 / 0.90), JCB and UnionPay
-- (0.05%). Meeza is set separately (V2026_10_27_02, 0.06%).
--
-- UPDATE existing rows, then INSERT any of the 8 combinations still missing
-- so the file converges whether or not V2026_10_26_04 was applied.
-- Idempotent, splitter-safe. Re-price EG months after applying.
-- ============================================================================
UPDATE scheme_fee_rate s
   SET fee_pct = v.pct,
       rate_status = 'APPROVED',
       source_note = 'BUSINESS-APPROVED 2026-10-27: EG domestic scheme fee POS 0.17% / ECOM 0.20%.'
  FROM (VALUES ('POS', 0.0017), ('ECOM', 0.0020)) AS v(channel, pct)
 WHERE s.country_code = 'EG' AND s.tenant_id IS NULL
   AND s.dest = 'DOMESTIC' AND s.channel = v.channel
   AND (s.scheme_group IS NULL OR s.scheme_group IN ('Visa', 'MasterCard', 'Amex'))
   AND (s.fee_pct <> v.pct OR s.rate_status <> 'APPROVED');

INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT 'EG', NULL, 'DOMESTIC', c.channel, g.scheme_group, c.pct, 0, 'APPROVED',
       'BUSINESS-APPROVED 2026-10-27: EG domestic scheme fee POS 0.17% / ECOM 0.20%.'
FROM (VALUES ('POS', 0.0017), ('ECOM', 0.0020)) AS c(channel, pct)
CROSS JOIN (VALUES (CAST(NULL AS VARCHAR)), ('Visa'), ('MasterCard'), ('Amex')) AS g(scheme_group)
WHERE NOT EXISTS (SELECT 1 FROM scheme_fee_rate x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.dest = 'DOMESTIC' AND x.channel = c.channel
                    AND COALESCE(x.scheme_group, '*') = COALESCE(g.scheme_group, '*'));

ANALYZE scheme_fee_rate;

-- Verify: expect POS rows at 0.001700 and ECOM rows at 0.002000 for
-- Visa / MasterCard / Amex / <any>, JCB and UnionPay untouched at 0.000500.
SELECT channel, COALESCE(scheme_group, '<any>') AS scheme_group, fee_pct, rate_status
FROM scheme_fee_rate
WHERE country_code = 'EG' AND tenant_id IS NULL AND dest = 'DOMESTIC'
ORDER BY channel, scheme_group NULLS FIRST;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_27_01__eg_domestic_scheme_fee_017.sql')
ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- 9. V2026_10_27_02__eg_meeza_scheme_fee_006.sql
-- ============================================================================

-- ============================================================================
-- V2026_10_27_02: Egypt Meeza scheme fee = 0.06% (6 bps) on every destination
--                 x channel (business instruction 2026-10-27). Replaces the
--                 0.05% from V2026_10_26_06. Meeza INTERCHANGE stays 0.50%.
--
-- UPDATE existing Meeza rows, then INSERT any of the 4 combinations still
-- missing so the file converges whether or not V2026_10_26_06 was applied.
-- Idempotent, splitter-safe. Re-price EG months after applying.
-- ============================================================================
UPDATE scheme_fee_rate
   SET fee_pct = 0.0006,
       rate_status = 'APPROVED',
       source_note = 'BUSINESS-APPROVED 2026-10-27: Meeza scheme fee 0.06% all destinations and channels.'
 WHERE country_code = 'EG' AND tenant_id IS NULL
   AND scheme_group = 'Meeza'
   AND (fee_pct <> 0.0006 OR rate_status <> 'APPROVED');

INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT 'EG', NULL, g.dest, g.channel, 'Meeza', 0.0006, 0, 'APPROVED',
       'BUSINESS-APPROVED 2026-10-27: Meeza scheme fee 0.06% all destinations and channels.'
FROM (VALUES
    ('DOMESTIC',      'POS'),
    ('DOMESTIC',      'ECOM'),
    ('INTERNATIONAL', 'POS'),
    ('INTERNATIONAL', 'ECOM')
) AS g(dest, channel)
WHERE NOT EXISTS (SELECT 1 FROM scheme_fee_rate x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.scheme_group = 'Meeza' AND x.dest = g.dest AND x.channel = g.channel);

ANALYZE scheme_fee_rate;

-- Verify: expect 4 rows at 0.000600, APPROVED.
SELECT dest, channel, fee_pct, rate_status FROM scheme_fee_rate
WHERE country_code = 'EG' AND tenant_id IS NULL AND scheme_group = 'Meeza' ORDER BY dest, channel;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_27_02__eg_meeza_scheme_fee_006.sql')
ON CONFLICT (filename) DO NOTHING;
