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
