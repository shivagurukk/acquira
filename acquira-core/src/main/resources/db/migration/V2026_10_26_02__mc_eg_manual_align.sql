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
