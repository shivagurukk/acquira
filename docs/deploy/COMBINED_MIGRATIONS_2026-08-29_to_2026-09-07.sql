-- ============================================================================
-- COMBINED MIGRATION SCRIPT: 2026-08-29 -> 2026-09-07
-- Generated 2026-09-07 from acquira-core/src/main/resources/db/migration.
--
-- Contains, in date/version order (run top to bottom in one psql session):
--   V2026_08_29_04  commercial card type (MC UAE+BH commercial bands)
--   V2026_08_30_02  Benefit interchange align + 0.06% scheme fee (BH)
--   V2026_08_31_01  DCC revenue feed (staging/fact/ancillary columns)
--   V2026_08_31_02  Net Spread menu
--   V2026_09_01_01  Scheme Billing Reference menu
--   V2026_09_04_01  Daily Digest (config/dispatch/menu)
--   V2026_09_04_02  Rental sign heal (fact_rental ABS fix + overlay rebuild)
--   V2026_09_05_01  Integration Hub v2 (alerts + indexes)
--   V2026_09_05_02  Pricing Simulator v2 (menu + tenant flag)
--   V2026_09_05_03  Digest send_not_before
--   V2026_09_05_04  Digest require_merchant
--   V2026_09_05_05  Enterprise API v1 (webhooks/idempotency/api_key)
--   V2026_09_07_01  Net Spread ECOM FX income (BH) + rate seed + flag
--   V2026_09_07_02  Industry Analytics menu
--   V2026_09_07_03  Revenue Mix menu
--   V2026_09_07_04  Volume Drop menu
--   V2026_08_30_01  EG MC rate alignment — INCLUDED COMMENTED OUT AT THE END
--                   (DRAFT: placeholder Premium/Elite rates, do not apply
--                    until BIN-weighted values are substituted).
--
-- Every statement is idempotent (IF NOT EXISTS / NOT EXISTS guards /
-- ON CONFLICT DO NOTHING), so re-running the whole script is safe.
-- Splitter-safe: no dollar-quoting, no DO blocks.
--
-- Apply with:  psql -h <host> -U <user> -d <db> -f COMBINED_MIGRATIONS_2026-08-29_to_2026-09-07.sql
--
-- AFTER APPLYING, remember:
--   * V2026_08_29_04 + V2026_08_30_02 change rates -> reprice/re-ingest
--     affected months (rebuild-summaries with reprice:true).
--   * V2026_09_07_01 FX columns start at 0 -> summary rebuild/re-ingest
--     needed for historic days.
--   * Report caches may serve stale zeros up to TTL -> restart pod or run
--     any ingest to evict.
-- ============================================================================


-- ############################################################################
-- ## V2026_08_29_04__commercial_card_type.sql
-- ############################################################################

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


-- ############################################################################
-- ## V2026_08_30_02__benefit_interchange_align_and_scheme_fee_bh.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_30_02: BENEFIT (Bahrain) — align interchange to the official Benefit
--                 acquirer schedule + add the 0.60% Benefit scheme fee.
--
-- SOURCE OF TRUTH
-- ---------------
-- The Benefit "Payment Gateway Transaction" (ECOM) and "Purchase" (POS) fee
-- schedules, user-supplied 2026-08-30. Business instruction: take ONLY the
-- ACQUIRER column as our interchange fee, and configure the Benefit SCHEME fee
-- separately at 0.60%. Bahrain only; applies to scheme_group 'Benefit' AND its
-- QR twin 'Benefit QR' (which mirrors the Benefit rate card).
--
-- WHAT CHANGES (all "corrected in place" per business — history re-prices to the
-- new values on the next reprice/re-ingest; NOT effective-dated):
--
--   1. EXCHANGE HOUSES (MCC 6051, 4829): acquirer rate 0.60% -> 0.45%.
--      The V2026_08_08_05 seed carried 0.60% as a FLAGGED ASSUMPTION; the
--      official schedule says 0.45% (cap BHD 0.029 unchanged, and correct).
--
--   2. PETROL / FUEL: schedule footnote lists MCC 5172, 5983, 5541, 5542. The
--      seed only had 5541/5542. Add 5172 and 5983 at the same 0.60% cap 0.085.
--
--   3. INTERNATIONAL: acquirer rate 1.10% -> 1.00% (flat BHD 0.100 unchanged),
--      per the 2026-08-30 correction ("1% + 0.1 BHD", not 1.10%).
--
--   4. SCHEME FEE (new): Benefit / Benefit QR BH DOMESTIC POS + ECOM = 0.06%.
--      Until now Benefit had no scheme_fee_rate row and silently took the BH
--      any-scheme wildcard (0.11% POS / 0.14% ECOM). A scheme-specific row beats
--      the wildcard in the fee engine's scheme LATERAL, so these now govern.
--
--   5. MCC LISTS made an exact replica of the manual (user 2026-08-30):
--        Govt     footnote 1 : 9211, 9222, 9311, 9399, 9402 -> DROP 9223 (seed extra)
--        Charity  footnote 4 : 8398                          -> DROP 8661 (seed extra)
--        Petrol   footnote 3 : 5172, 5983, 5541, 5542        (5172/5983 added in #2)
--        Exchange footnote 2 : 4829, 6051                     (already correct)
--      A dropped MCC has no other Benefit row, so it falls back to the 0.60%
--      standard purchase rate.
--
--   6. GOVERNMENT CHANNEL SPLIT — the manual charges the 0.100/0.200 government
--      flats on the POS "Purchase" schedule only; the ECOM "Payment Gateway"
--      schedule shows government as an all-blank row (no fees). So the flats are
--      restricted to POS, and government on ECOM becomes a zero row per MCC (same
--      reading as the manual's all-blank charity row = 0).
--
-- DELIBERATELY NOT CHANGED / CANNOT REPLICATE FROM MCC ALONE:
--   * Charity acquirer fee stays 0 (manual Acquirer column is '-'; footnote-4's
--     0.001/txn is not an acquirer charge). MCC 8398 only, after the drop above.
--   * PSP Stored-Value Account Top-Up (ECOM acquirer 0.056 flat), Issuer Not
--     Available, ON-US Contactless from wallet — these are feed SCENARIOS, not
--     MCC categories, so they need a transaction-type/identifier from the feed
--     before they can be seeded. Left out until that mapping is provided.
--   * International scheme fee — still the wildcard (0.75% POS / 0.90% ECOM);
--     no Benefit-specific international scheme fee was requested.
--
-- Splitter-safe (no dollar-quoting). Idempotent (UPDATEs set absolute values;
-- INSERTs guarded by NOT EXISTS) so it is safe to list in schema-locations and
-- safe to re-run via psql on prod.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Exchange houses 0.60% -> 0.45% (Benefit + Benefit QR). Cap 0.029 unchanged.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET interchange_pct = 0.004500,
       label = REPLACE(COALESCE(label,''), '0.60%', '0.45%')
 WHERE country_code = 'BH'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND dest = 'DOMESTIC'
   AND mcc IN ('6051', '4829');

-- ----------------------------------------------------------------------------
-- 2. Petrol / fuel — add MCC 5172, 5983 at 0.60% cap 0.085 (Benefit + QR).
--    Mirrors the existing 5541/5542 petrol rows (priority 70, channel-wildcard).
-- ----------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, rate_status)
SELECT NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, sg.scheme_group,
       NULL, NULL, NULL, m.mcc, NULL, NULL, 0.006000, 0.085,
       sg.pfx || ' Petrol ' || m.mcc || ' 0.60% cap 0.085', 'APPROVED'
FROM (VALUES ('Benefit', 'BENEFIT'), ('Benefit QR', 'BENEFIT QR')) AS sg(scheme_group, pfx)
CROSS JOIN (VALUES ('5172'), ('5983')) AS m(mcc)
WHERE NOT EXISTS (
    SELECT 1 FROM interchange_rate_local x
    WHERE x.country_code = 'BH' AND x.scheme_group = sg.scheme_group
      AND x.dest = 'DOMESTIC' AND x.mcc = m.mcc);

-- ----------------------------------------------------------------------------
-- 3. International interchange 1.10% -> 1.00% (Benefit + QR). Flat 0.100 stays.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET interchange_pct = 0.010000,
       label = REPLACE(COALESCE(label,''), '1.10%', '1.00%'),
       source_note = 'Benefit international 1.00% + BHD 0.100 (2026-08-30 correction from 1.10%).'
 WHERE country_code = 'BH'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND dest = 'INTERNATIONAL';

-- ----------------------------------------------------------------------------
-- 4. Benefit scheme fee 0.06% — BH DOMESTIC, POS + ECOM (Benefit + Benefit QR).
--    Country-level (tenant_id NULL). APPROVED so it prices immediately; a
--    scheme-specific row outranks the NULL wildcard in the scheme LATERAL.
--    flat_fee 0 (pure percentage). Insert-if-absent then converge the value, so
--    a re-run (or any earlier draft value) always lands on 0.000600.
-- ----------------------------------------------------------------------------
INSERT INTO scheme_fee_rate
    (tenant_id, country_code, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT NULL, 'BH', 'DOMESTIC', ch.channel, sg.scheme_group, 0.000600, 0, 'APPROVED',
       'BENEFIT scheme fee 0.06% (business-supplied 2026-08-30), BH domestic.'
FROM (VALUES ('Benefit'), ('Benefit QR')) AS sg(scheme_group)
CROSS JOIN (VALUES ('POS'), ('ECOM')) AS ch(channel)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_fee_rate x
    WHERE x.country_code = 'BH' AND x.dest = 'DOMESTIC'
      AND x.channel = ch.channel AND x.scheme_group = sg.scheme_group);

UPDATE scheme_fee_rate
   SET fee_pct = 0.000600, flat_fee = 0, rate_status = 'APPROVED'
 WHERE country_code = 'BH' AND dest = 'DOMESTIC'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND channel IN ('POS', 'ECOM');

-- ----------------------------------------------------------------------------
-- 5. MCC-list replica of the manual: drop the two seed MCCs absent from the
--    schedule footnotes — 9223 (govt) and 8661 (charity). Neither carries any
--    other Benefit row, so both fall back to the 0.60% standard purchase rate.
--    Benefit + Benefit QR.
-- ----------------------------------------------------------------------------
DELETE FROM interchange_rate_local
 WHERE country_code = 'BH'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND dest = 'DOMESTIC'
   AND mcc IN ('9223', '8661');

-- ----------------------------------------------------------------------------
-- 6. Government channel split — flats on POS only; ECOM government = 0.
-- 6a. Restrict the existing government flat rows (channel-wildcard) to POS.
--     Guard interchange_pct = 1.000000 so only the flat-fee govt rows are hit.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET channel = 'POS'
 WHERE country_code = 'BH'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND dest = 'DOMESTIC'
   AND channel IS NULL
   AND mcc IN ('9211', '9222', '9311', '9399', '9402')
   AND interchange_pct = 1.000000;

-- 6b. Government on ECOM = 0 (manual's all-blank ECOM government row). One zero
--     row per MCC per scheme; priority 70 so it beats the 0.60% base on ECOM.
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, rate_status)
SELECT NULL, 'BH', 'BHD', 70, 'DOMESTIC', 'ECOM', sg.scheme_group,
       NULL, NULL, NULL, m.mcc, NULL, NULL, 0.000000, NULL,
       sg.pfx || ' Govt ' || m.mcc || ' ECOM zero', 'APPROVED'
FROM (VALUES ('Benefit', 'BENEFIT'), ('Benefit QR', 'BENEFIT QR')) AS sg(scheme_group, pfx)
CROSS JOIN (VALUES ('9211'), ('9222'), ('9311'), ('9399'), ('9402')) AS m(mcc)
WHERE NOT EXISTS (
    SELECT 1 FROM interchange_rate_local x
    WHERE x.country_code = 'BH' AND x.scheme_group = sg.scheme_group
      AND x.dest = 'DOMESTIC' AND x.channel = 'ECOM' AND x.mcc = m.mcc);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_30_02__benefit_interchange_align_and_scheme_fee_bh.sql')
ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_08_31_01__dcc_revenue.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_31_01: DCC revenue feed — staging + fact + ancillary summary columns.
--
-- DCC revenue arrives in a DEDICATED file at STORE (SID) level, through the
-- same three channels as rentals: screen upload, Server File Processor, and
-- the scheduled integration pull. DccRevenueJobConfig (dccLoadJob /
-- dbPullDccJob) stages rows here and applies them.
--
-- File shape (header-name mapped, order-independent):
--   SID, Tenant Id, Merchant Share, Acquirer Share, Date
-- Amounts are tenant base currency, MAJOR units. "Tenant Id" (bank short
-- code, institution id, or numeric tenant id) is VALIDATED against the
-- uploading tenant — a mismatched row is REJECTED, it never routes data.
--
-- REPLACE-BY-DATE SEMANTICS (decision 2026-08-31, unlike rentals' dedupe):
-- the apply step DELETEs fact_dcc_revenue for exactly the (tenant, dates)
-- present in the file, then inserts fresh — so re-uploading a corrected day
-- fully supersedes the previous numbers. Idempotent on repeat upload.
--
-- ANCILLARY SUMMARY COLUMNS: sum_daily_merchant and sum_daily_finance_rollup
-- gain dcc_acquirer / dcc_merchant / rental_amount so every dashboard reading
-- the summary layer can show Net Spread
--   (= total_margin + dcc_acquirer + rental_amount)
-- without joining facts. The columns are ALWAYS recomputed from
-- fact_dcc_revenue / fact_rental by AncillarySql (acquira-common) — after
-- every summary rebuild and after every DCC/rental apply — never carried
-- forward. net_spread itself is derived at read time, never stored.
-- Grain rule: dimensionally-sliced summaries (sum_daily_full, terminal,
-- monthly_card, ...) deliberately do NOT get these columns — a store-level
-- charge cannot be honestly attributed to a scheme/card/destination slice.
--
-- Idempotent; splitter-safe (no $$). Listed in spring.sql.init.schema-locations
-- for dev; apply once via psql on prod.
-- ============================================================================

CREATE TABLE IF NOT EXISTS stg_dcc_revenue_raw (
    raw_id         BIGSERIAL PRIMARY KEY,
    tenant_id      INT,
    file_id        BIGINT,
    load_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status         VARCHAR(20) DEFAULT 'PENDING',  -- PENDING|PROCESSED|REJECTED|UNMATCHED
    error_message  TEXT,

    sid            VARCHAR(50),
    file_tenant_id VARCHAR(50),                    -- as filed; validated, never trusted
    merchant_share DECIMAL(19,4),
    acquirer_share DECIMAL(19,4),
    payment_date   DATE
);

CREATE INDEX IF NOT EXISTS idx_stg_dcc_tenant ON stg_dcc_revenue_raw (tenant_id, status);

ALTER TABLE stg_dcc_revenue_raw ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON stg_dcc_revenue_raw;
CREATE POLICY tenant_isolation_policy ON stg_dcc_revenue_raw
    USING (tenant_id = get_current_tenant());

CREATE TABLE IF NOT EXISTS fact_dcc_revenue (
    dcc_id         BIGSERIAL PRIMARY KEY,
    tenant_id      INT NOT NULL,
    merchant_id    BIGINT,
    store_id       BIGINT,
    sid            VARCHAR(50),
    merchant_share DECIMAL(19,4) NOT NULL,
    acquirer_share DECIMAL(19,4) NOT NULL,
    payment_date   DATE NOT NULL,
    file_id        BIGINT,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fact_dcc_tenant_date
    ON fact_dcc_revenue (tenant_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_fact_dcc_tenant_merchant_date
    ON fact_dcc_revenue (tenant_id, merchant_id, payment_date);

ALTER TABLE fact_dcc_revenue ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON fact_dcc_revenue;
CREATE POLICY tenant_isolation_policy ON fact_dcc_revenue
    USING (tenant_id = get_current_tenant());

-- Ancillary columns on the merchant-day summary. NOT NULL DEFAULT 0 so every
-- existing consumer's SUM() keeps working unchanged.
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS dcc_acquirer  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS dcc_merchant  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4) NOT NULL DEFAULT 0;

-- Same three on the tenant-day finance rollup fast path.
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS dcc_acquirer  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS dcc_merchant  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4) NOT NULL DEFAULT 0;

-- One-time seed for rental history already in fact_rental (loaded before this
-- migration existed) — the same statements AncillarySql runs after every
-- apply, so re-running is harmless (it recomputes to the same values).
-- fact_dcc_revenue is empty at migration time; its rows arrive through the
-- apply job, which maintains these columns itself.
INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id,
    total_txns, total_volume, total_base_volume, total_msf, total_interchange,
    total_scheme_fee, total_margin, rental_amount)
SELECT tenant_id, payment_date, merchant_id, 0, 0, 0, 0, 0, 0, 0, SUM(rental_amount)
FROM fact_rental
WHERE merchant_id IS NOT NULL
GROUP BY tenant_id, payment_date, merchant_id
ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

INSERT INTO sum_daily_finance_rollup (tenant_id, business_date, rental_amount)
SELECT tenant_id, payment_date, SUM(rental_amount)
FROM fact_rental
GROUP BY tenant_id, payment_date
ON CONFLICT (tenant_id, business_date) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_31_01__dcc_revenue.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_08_31_02__net_spread_menu.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_31_02: Net Spread dashboard menu entry.
--
-- /executive/net-spread — replica of the Executive Daily Merchant Performance
-- layout at MERCHANT grain over sum_daily_merchant, extended with the
-- ancillary revenue columns (DCC acquirer share, rental income) and the
-- derived Net Spread = net margin + DCC acquirer share + rental income.
-- EXECUTIVE category, display_order 8 (next after Daily Merchant
-- Performance's 7 from V2026_08_19_02).
--
-- The API is gated by @menuAccess.canAccess('/executive/net-spread'), so this
-- grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Net Spread', '/executive/net-spread', 'Layers', 'EXECUTIVE', 8
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/executive/net-spread');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/net-spread'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/net-spread'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_31_02__net_spread_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_01_01__scheme_billing_reference_menu.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_01_01: Scheme Billing Reference menu entry.
--
-- /ops/scheme-billing-reference — read-only, fully static reference of the
-- 74 acquirer-relevant Mastercard Consolidated Billing System (MCBS) report
-- and invoice-file specifications (T0CH/BFIL, TN3A, T0CF, GB/AB reports),
-- extracted offline from the 2 June 2026 DITA-XML manual into
-- frontend/src/data/mcbsAcquirerReports.json. No backend endpoint — the
-- page ships its data in the frontend bundle, so this grant only controls
-- sidebar visibility (RoleGuard on the route gates access).
--
-- OPERATIONS category, display_order 6 (after Ingest Trust's 5).
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Scheme Billing Reference', '/ops/scheme-billing-reference', 'BookOpen', 'OPERATIONS', 6
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/ops/scheme-billing-reference');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/ops/scheme-billing-reference'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/ops/scheme-billing-reference'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_01_01__scheme_billing_reference_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_04_01__daily_digest.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_04_01: Daily Dashboard Digest email.
--
-- One executive summary email per tenant per business day, sent only after
-- ALL required feeds for that day have landed (transactions + DCC + rentals,
-- each individually toggleable). Discovery and gating run on a timer
-- (DigestScheduler); state lives in digest_dispatch so a day is emailed
-- exactly once no matter how many files arrive or how often the timer fires.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. digest_config — one row per tenant that wants the digest
-- ----------------------------------------------------------------------------
-- enabled defaults FALSE: a newly provisioned tenant must never email anyone
-- until an admin has typed the recipient list in (same fail-closed reasoning
-- as ingest_expectation.enabled).
CREATE TABLE IF NOT EXISTS digest_config (
    tenant_id            BIGINT PRIMARY KEY,
    enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    recipients           TEXT,                       -- comma/semicolon-separated email list
    quiet_minutes        INT          NOT NULL DEFAULT 15,  -- debounce after the last ingest run
    require_trx          BOOLEAN      NOT NULL DEFAULT TRUE,
    require_dcc          BOOLEAN      NOT NULL DEFAULT TRUE,
    require_rental       BOOLEAN      NOT NULL DEFAULT TRUE,
    backfill_window_days INT          NOT NULL DEFAULT 3,   -- only days this recent trigger emails
    updated_by           VARCHAR(128),
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed one disabled row per existing tenant so the admin screen has a row to
-- edit rather than a create-form (mirrors the ingest_expectation seeding).
INSERT INTO digest_config (tenant_id)
SELECT t.tenant_id FROM tenant t
ON CONFLICT (tenant_id) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2. digest_dispatch — the once-per-tenant-day state machine
-- ----------------------------------------------------------------------------
-- status: PENDING  discovered, waiting on feeds / quiet period
--         SENT     digest delivered to at least one recipient
--         FAILED   gave up after max attempts (visible on the admin screen)
--         SKIPPED  admin disabled the tenant while days were pending
-- The UNIQUE constraint is the idempotency guarantee.
CREATE TABLE IF NOT EXISTS digest_dispatch (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL,
    business_date  DATE        NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    waiting_on     VARCHAR(64),            -- e.g. 'DCC', 'RENTAL', 'QUIET' — why still pending
    attempts       INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at        TIMESTAMP,
    recipients_sent TEXT,
    error_message  TEXT,
    UNIQUE (tenant_id, business_date)
);

CREATE INDEX IF NOT EXISTS ix_digest_dispatch_status
    ON digest_dispatch (status, tenant_id);
CREATE INDEX IF NOT EXISTS ix_digest_dispatch_tenant_date
    ON digest_dispatch (tenant_id, business_date DESC);

-- ----------------------------------------------------------------------------
-- 3. Menu registration — /ops/daily-digest (OPERATIONS, after Scheme Billing)
-- ----------------------------------------------------------------------------
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Daily Digest', '/ops/daily-digest', 'Mail', 'OPERATIONS', 7
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/ops/daily-digest');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/ops/daily-digest'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_04_01__daily_digest.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_04_02__rental_sign_heal.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_04_02: Heal fact_rental rows that straddle the ABS sign fix.
--
-- THE DEFECT (seen on UAT 2026-09-04): loads made BEFORE commit 14b22bd stored
-- rental amounts with the feed's raw NEGATIVE sign and a row_hash built from
-- that negative amount. Re-uploading the same file on a FIXED backend
-- normalises the amount to positive BEFORE hashing, so the new hash never
-- collides with the old row and ON CONFLICT (tenant_id, row_hash) DO NOTHING
-- appends a SECOND, positive copy of every charge. Every entity then carries
-- -x and +x: all rental sums show exactly 0.000, charge counts double, the
-- one-time-fee coverage panel reports thousands of "billed 2+ times", and
-- Net Spread's rental column (AncillarySql over the same fact) reads 0.
--
-- THE HEAL, in order:
--   1. delete each stale negative row whose positive twin exists;
--   2. ABS the surviving negatives (charges only present in pre-fix loads)
--      and rebuild their hash from the normalised amount, so a future
--      re-upload of the same charge dedupes instead of duplicating again;
--   3. re-derive the rental_amount overlay on sum_daily_merchant and
--      sum_daily_finance_rollup from the healed fact (mirrors AncillarySql —
--      summary-rebuild-drift rule: if that class changes, change this too).
--
-- Idempotent (re-running finds no negatives and re-derives the same overlay).
-- Splitter-safe (no $$). On prod/UAT apply once via psql. Report caches may
-- serve the old zeros for up to the cache TTL — restart the pod or run any
-- ingest to evict immediately.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Drop stale negative rows that were re-appended as positive by a reload
-- ----------------------------------------------------------------------------
DELETE FROM fact_rental n
WHERE n.rental_amount < 0
  AND EXISTS (
    SELECT 1 FROM fact_rental p
    WHERE p.tenant_id = n.tenant_id
      AND p.level = n.level
      AND COALESCE(p.mid, '') = COALESCE(n.mid, '')
      AND COALESCE(p.sid, '') = COALESCE(n.sid, '')
      AND COALESCE(p.tid, '') = COALESCE(n.tid, '')
      AND p.payment_date = n.payment_date
      AND p.rental_amount = ABS(n.rental_amount));

-- ----------------------------------------------------------------------------
-- 2. Normalise negatives that have no positive twin (pre-fix-only charges)
-- ----------------------------------------------------------------------------
-- Hash rebuilt exactly like RentalJobConfig builds it (ids | amount | date,
-- with the NORMALISED amount) so the next upload of the same charge is a
-- dedupe no-op. Step 1 already removed every row whose normalised hash would
-- collide, so the unique (tenant_id, row_hash) constraint cannot trip here.
UPDATE fact_rental
SET rental_amount = ABS(rental_amount),
    row_hash = md5(COALESCE(mid, '') || '|' || COALESCE(sid, '') || '|'
                   || COALESCE(tid, '') || '|' || CAST(ABS(rental_amount) AS TEXT)
                   || '|' || CAST(payment_date AS TEXT))
WHERE rental_amount < 0;

-- ----------------------------------------------------------------------------
-- 3a. Re-derive rental_amount on sum_daily_merchant (AncillarySql mirror)
-- ----------------------------------------------------------------------------
UPDATE sum_daily_merchant SET rental_amount = 0 WHERE rental_amount <> 0;

INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id,
    total_txns, total_volume, total_base_volume, total_msf, total_interchange,
    total_scheme_fee, total_margin, rental_amount)
SELECT tenant_id, payment_date, merchant_id, 0, 0, 0, 0, 0, 0, 0,
       SUM(rental_amount)
FROM fact_rental
WHERE merchant_id IS NOT NULL
GROUP BY tenant_id, payment_date, merchant_id
ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

-- Ancillary-only rows whose ancillary is now 0 again must go (same cleanup
-- rule as AncillarySql.MERCH_CLEANUP).
DELETE FROM sum_daily_merchant
WHERE COALESCE(total_txns, 0) = 0 AND COALESCE(total_volume, 0) = 0
  AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0;

-- ----------------------------------------------------------------------------
-- 3b. Re-derive rental_amount on sum_daily_finance_rollup
-- ----------------------------------------------------------------------------
UPDATE sum_daily_finance_rollup SET rental_amount = 0 WHERE rental_amount <> 0;

INSERT INTO sum_daily_finance_rollup (tenant_id, business_date, rental_amount)
SELECT tenant_id, payment_date, SUM(rental_amount)
FROM fact_rental
GROUP BY tenant_id, payment_date
ON CONFLICT (tenant_id, business_date) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

DELETE FROM sum_daily_finance_rollup
WHERE pivot_built = FALSE AND fees_built = FALSE
  AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_04_02__rental_sign_heal.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_05_01__integration_hub_v2.sql
-- ############################################################################

-- Integration Hub v2: failure alerting + health/forensics query support.
--
-- 1. Per-schedule failure alerts: when a pull's final attempt fails, an email
--    goes to alert_emails through the tenant's own SMTP config. Empty/NULL
--    recipients = no alert; alert_on_failure lets an operator mute a schedule
--    without deleting the address list.
ALTER TABLE integration_schedule ADD COLUMN IF NOT EXISTS alert_emails TEXT;
ALTER TABLE integration_schedule ADD COLUMN IF NOT EXISTS alert_on_failure BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. The schedules list and the new /health endpoint read "recent runs per
--    schedule/report" on every page load; both were sequential scans.
CREATE INDEX IF NOT EXISTS idx_int_run_log_schedule_time
    ON integration_run_log (schedule_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_int_run_log_report_time
    ON integration_run_log (report_id, start_time DESC);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_05_01__integration_hub_v2.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_05_02__pricing_simulator_v2.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_05_02: Pricing Simulator v2 — segment margin matrix.
--
-- Backend reads sum_daily_full (scheme × card_type × destination with the
-- real fee stack) — NO new data tables. This migration only:
--   1. registers the sidebar/menu row for /business/pricing-simulator
--      (the route + page have existed since v1, but no sys_menu row was ever
--      seeded, so the @menuAccess gate on the new controller needs one),
--   2. grants it to Super Admin + Bank Admin,
--   3. seeds the per-tenant enable flag pricing.simulator_enabled (default
--      'true' — the screen predates the flag, so existing tenants keep it;
--      an admin switches a tenant off in Settings → Regional & Data).
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- 1. Menu row -----------------------------------------------------------------
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Pricing Simulator', '/business/pricing-simulator', 'SlidersHorizontal', 'BUSINESS', 19
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/pricing-simulator');

-- 2. Grants: Super Admin + Bank Admin ----------------------------------------
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path = '/business/pricing-simulator'
ON CONFLICT DO NOTHING;

-- 3. Per-tenant calculation flag ---------------------------------------------
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'pricing.simulator_enabled', 'true', 'BOOLEAN'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_05_02__pricing_simulator_v2.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_05_03__digest_send_time.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_05_03: Daily Digest — scheduled send time.
--
-- send_not_before: tenant-local wall-clock time (tenant's locale.timezone
-- setting, else server zone) before which a ready digest is HELD. The feed
-- gates still apply — this only delays a day that is already complete, so
-- "send at 08:00" yields one predictable morning email instead of one at
-- whatever minute the last feed landed. NULL = send as soon as ready
-- (the behaviour every tenant has today, so no seeding needed).
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS send_not_before TIME NULL;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_05_03__digest_send_time.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_05_04__digest_merchant_feed.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_05_04: Daily Digest — merchant master as its own required feed.
--
-- The gate previously tracked TRX / DCC / RENTAL; merchant master is now a
-- fourth, separately-toggleable requirement. Presence = dim_merchant has at
-- least one row for the tenant (the master is an occasional upsert feed, not
-- a daily one) OR a merchant load completed after the business day — so a
-- tenant that has ever loaded its merchant master passes immediately, while
-- a brand-new tenant's digest correctly waits for it.
--
-- Default TRUE to match the other require_* columns: existing tenants all
-- have dim_merchant rows, so nothing already flowing is blocked.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS require_merchant BOOLEAN NOT NULL DEFAULT TRUE;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_05_04__digest_merchant_feed.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_05_05__enterprise_api_v1.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_05_05: Enterprise API v1 — outbound webhooks, API-key lifecycle,
-- idempotency keys, daily quotas. Everything tenant-scoped.
--
--   1. webhook_endpoint  — per-tenant outbound webhook subscriptions
--                          (HMAC secret stored encrypted via CryptoService).
--   2. webhook_delivery  — delivery ledger with retry/backoff state.
--   3. api_idempotency   — Idempotency-Key replay store for external POST/PUT.
--   4. api_key           — + quota_per_day (daily request ceiling per key)
--                          + rotated_at / rotated_by lifecycle columns.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- 1. Webhook endpoints --------------------------------------------------------
CREATE TABLE IF NOT EXISTS webhook_endpoint (
    endpoint_id          BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT NOT NULL,
    name                 VARCHAR(120) NOT NULL,
    url                  VARCHAR(1000) NOT NULL,
    -- enc:v1: token (AES-256-GCM); used to HMAC-SHA256 sign every delivery
    secret               VARCHAR(500) NOT NULL,
    -- JSON array of subscribed event types, e.g. ["ingest.run.completed"]
    events               TEXT NOT NULL DEFAULT '[]',
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_by           VARCHAR(120),
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP,
    last_success_at      TIMESTAMP,
    last_failure_at      TIMESTAMP,
    consecutive_failures INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_webhook_endpoint_tenant
    ON webhook_endpoint (tenant_id, is_active);

-- 2. Delivery ledger ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS webhook_delivery (
    delivery_id     BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    endpoint_id     BIGINT NOT NULL REFERENCES webhook_endpoint(endpoint_id) ON DELETE CASCADE,
    event_type      VARCHAR(80) NOT NULL,
    -- stable id integrators can use to de-duplicate re-deliveries
    event_id        VARCHAR(64) NOT NULL,
    payload         TEXT NOT NULL,
    -- PENDING -> SUCCESS | FAILED (will retry) -> DEAD (retries exhausted)
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response_status INTEGER,
    response_body   VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_due
    ON webhook_delivery (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_tenant
    ON webhook_delivery (tenant_id, created_at DESC);

-- 3. Idempotency replay store -------------------------------------------------
CREATE TABLE IF NOT EXISTS api_idempotency (
    tenant_id       BIGINT NOT NULL,
    idem_key        VARCHAR(120) NOT NULL,
    method          VARCHAR(10) NOT NULL,
    endpoint        VARCHAR(300) NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, idem_key)
);
CREATE INDEX IF NOT EXISTS idx_api_idempotency_age ON api_idempotency (created_at);

-- 4. api_key lifecycle + quota ------------------------------------------------
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS quota_per_day INTEGER;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS rotated_at TIMESTAMP;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS rotated_by VARCHAR(120);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_05_05__enterprise_api_v1.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_07_01__netspread_ecom_fx_revenue.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_07_01: ECOM FX income for Net Spread (Bahrain), tenant-flag gated.
--
-- WHY
-- ---
-- BH acquirer earns an FX margin on e-commerce transactions settled in BHD but
-- transacted in a foreign currency: the merchant is credited at a negotiated
-- COST rate while the acquirer converts at the BOARD rate. Business formula
-- (finance calc sheet, 2026-09-07), per transaction:
--
--   fx_income = multiplier * (settlement/cost_rate - settlement/board_rate)
--             ( = settlement * (1 - cost/board) whenever multiplier = cost_rate,
--               which is every row except the sheet's default-EUR anomaly,
--               preserved verbatim below: mult 0.370 vs cost 0.430 )
--
-- Applies ONLY to ECOM transactions NOT carried by Benefit PG (Benefit is the
-- domestic scheme/gateway — no FX), and only to currencies with a seeded rate
-- row (BHD deliberately has none). Merchant-specific negotiated rates override
-- the tenant defaults (TAP / RAIN / COINMENA / NEXTCORP).
--
-- DESIGN
-- ------
-- * ref_ecom_fx_rate — rate table instead of a hardcoded if-chain; mid NULL =
--   tenant default, a mid-specific row wins. MIDs are compared with leading
--   zeros stripped on BOTH sides (feed MIDs are 15-digit zero-padded).
-- * fx_revenue columns on sum_daily_merchant + sum_daily_finance_rollup,
--   re-derived from fact_transaction by AncillarySql (same lifecycle as
--   dcc_acquirer / rental_amount — clean-slate rebuilds re-derive, never wipe).
-- * tenant_setting 'netspread.fx_enabled' — read by NetSpreadController.
--   OPT-IN: only an explicit 'true' shows the FX column and adds it to the
--   spread (unlike pricing.simulator_enabled's fail-open, because tenants
--   without rate rows would otherwise show a dead all-zero column).
-- * Refunds need no special casing: fact rows are volume-signed (REFUND rows
--   carry a negative store_base_currency_amount), so the formula reverses the
--   FX income on refunds by construction.
-- * Historic days need a summary rebuild (or any re-ingest) to pick up FX —
--   the columns start at 0 and only AncillarySql fills them.
--
-- Idempotent; splitter-safe (no dollar-quoting, no DO blocks).
-- ============================================================================

-- 1. Rate table -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ref_ecom_fx_rate (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    INT NOT NULL,
    mid          VARCHAR(64) NULL,      -- NULL = tenant default for the currency
    txn_currency VARCHAR(10) NOT NULL,  -- fact_transaction.txn_currency, UPPERCASE
    board_rate   NUMERIC(12,6) NOT NULL, -- rate the acquirer converts at (b)
    cost_rate    NUMERIC(12,6) NOT NULL, -- rate the merchant is credited at (c)
    multiplier   NUMERIC(12,6) NOT NULL, -- calc-sheet trailing factor (= c except default EUR)
    label        VARCHAR(120) NULL
);

COMMENT ON TABLE ref_ecom_fx_rate IS
  'ECOM FX income rates: fx = multiplier * (settlement/cost_rate - settlement/board_rate) per ECOM non-Benefit-PG transaction in this currency. mid NULL = tenant default; a mid row overrides. Read-time reference data - DB-seeded only, never uploadable.';

CREATE UNIQUE INDEX IF NOT EXISTS uq_ref_ecom_fx_rate
    ON ref_ecom_fx_rate (tenant_id, COALESCE(mid, '*'), txn_currency);

-- 2. Summary columns --------------------------------------------------------
ALTER TABLE sum_daily_merchant       ADD COLUMN IF NOT EXISTS fx_revenue NUMERIC(21,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS fx_revenue NUMERIC(21,4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN sum_daily_merchant.fx_revenue IS
  'ECOM FX income (excl. Benefit PG), re-derived from fact_transaction x ref_ecom_fx_rate by AncillarySql. Added to Net Spread only when tenant_setting netspread.fx_enabled = true.';

-- 3. Seed: tenant defaults for every BH tenant (guarded, never clobbers) -----
-- Sheet default rows. EUR multiplier 0.370 (not its 0.430 cost rate) and the
-- zero-margin OMR row (0.980/0.980) are VERBATIM from the sheet.
INSERT INTO ref_ecom_fx_rate (tenant_id, mid, txn_currency, board_rate, cost_rate, multiplier, label)
SELECT t.tenant_id, NULL, v.ccy, v.b, v.c, v.m, v.lbl
FROM tenant t
JOIN ( VALUES
  ('USD', 0.377, 0.375, 0.375, 'BH default USD'),
  ('SAR', 0.100, 0.090, 0.090, 'BH default SAR'),
  ('AED', 0.103, 0.090, 0.090, 'BH default AED'),
  ('QAR', 0.104, 0.090, 0.090, 'BH default QAR'),
  ('GBP', 0.510, 0.410, 0.410, 'BH default GBP'),
  ('KWD', 1.230, 1.210, 1.210, 'BH default KWD'),
  ('EUR', 0.440, 0.430, 0.370, 'BH default EUR (sheet multiplier 0.370, verbatim)'),
  ('OMR', 0.980, 0.980, 0.970, 'BH default OMR (equal rates - zero margin, verbatim)')
) AS v(ccy, b, c, m, lbl) ON TRUE
WHERE t.home_country_code = 'BH'
  AND NOT EXISTS (SELECT 1 FROM ref_ecom_fx_rate x
                  WHERE x.tenant_id = t.tenant_id AND x.mid IS NULL
                    AND x.txn_currency = v.ccy);

-- 4. Seed: merchant-specific negotiated rates --------------------------------
-- COINMENA BSC CLOSED 000000000152819 / TAP PAYMENTS COMPANY BSC
-- 000000000126540 / RAIN MANAGEMENT WLL 000000000266544.
INSERT INTO ref_ecom_fx_rate (tenant_id, mid, txn_currency, board_rate, cost_rate, multiplier, label)
SELECT t.tenant_id, v.mid, v.ccy, v.b, v.c, v.m, v.lbl
FROM tenant t
JOIN ( VALUES
  -- COINMENA BSC CLOSED
  ('000000000152819', 'AED', 0.103, 0.099, 0.099, 'COINMENA AED'),
  ('000000000152819', 'KWD', 1.225, 1.210, 1.210, 'COINMENA KWD'),
  ('000000000152819', 'QAR', 0.104, 0.100, 0.100, 'COINMENA QAR'),
  ('000000000152819', 'GBP', 0.510, 0.479, 0.479, 'COINMENA GBP'),
  ('000000000152819', 'USD', 0.377, 0.375, 0.375, 'COINMENA USD'),
  ('000000000152819', 'OMR', 0.979, 0.970, 0.970, 'COINMENA OMR'),
  -- TAP PAYMENTS COMPANY BSC
  ('000000000126540', 'SAR', 0.100, 0.098, 0.098, 'TAP SAR'),
  ('000000000126540', 'AED', 0.103, 0.098, 0.098, 'TAP AED'),
  ('000000000126540', 'KWD', 1.230, 1.210, 1.210, 'TAP KWD'),
  ('000000000126540', 'QAR', 0.104, 0.098, 0.098, 'TAP QAR'),
  ('000000000126540', 'GBP', 0.510, 0.467, 0.467, 'TAP GBP'),
  ('000000000126540', 'USD', 0.377, 0.375, 0.375, 'TAP USD'),
  ('000000000126540', 'OMR', 0.980, 0.980, 0.980, 'TAP OMR (zero margin, verbatim)'),
  ('000000000126540', 'EUR', 0.440, 0.400, 0.400, 'TAP EUR'),
  -- RAIN MANAGEMENT WLL
  ('000000000266544', 'SAR', 0.100, 0.098, 0.098, 'RAIN SAR'),
  ('000000000266544', 'AED', 0.103, 0.098, 0.098, 'RAIN AED'),
  ('000000000266544', 'KWD', 1.230, 1.210, 1.210, 'RAIN KWD'),
  ('000000000266544', 'QAR', 0.104, 0.098, 0.098, 'RAIN QAR'),
  ('000000000266544', 'GBP', 0.479, 0.467, 0.467, 'RAIN GBP'),
  ('000000000266544', 'USD', 0.377, 0.375, 0.375, 'RAIN USD'),
  ('000000000266544', 'OMR', 0.980, 0.980, 0.980, 'RAIN OMR (zero margin, verbatim)'),
  ('000000000266544', 'EUR', 0.440, 0.400, 0.400, 'RAIN EUR')
) AS v(mid, ccy, b, c, m, lbl) ON TRUE
WHERE t.home_country_code = 'BH'
  AND NOT EXISTS (SELECT 1 FROM ref_ecom_fx_rate x
                  WHERE x.tenant_id = t.tenant_id AND x.mid = v.mid
                    AND x.txn_currency = v.ccy);

-- NEXTCORP WLL (EUR only) — the sheet gave no MID, so resolve it from
-- dim_merchant by name at apply time. If NEXTCORP is not onboarded yet this
-- seeds nothing; insert its row manually once the MID is known.
INSERT INTO ref_ecom_fx_rate (tenant_id, mid, txn_currency, board_rate, cost_rate, multiplier, label)
SELECT m.tenant_id, m.mid, 'EUR', 0.440, 0.430, 0.430, 'NEXTCORP EUR (mid resolved by name)'
FROM dim_merchant m
JOIN tenant t ON t.tenant_id = m.tenant_id AND t.home_country_code = 'BH'
WHERE UPPER(m.name) LIKE 'NEXTCORP%'
  AND NOT EXISTS (SELECT 1 FROM ref_ecom_fx_rate x
                  WHERE x.tenant_id = m.tenant_id AND x.mid = m.mid
                    AND x.txn_currency = 'EUR');

-- 5. Tenant flag: ON for BH tenants (guarded — a deliberate later 'false'
--    is never forced back). Absent/false = FX hidden and excluded from spread.
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'netspread.fx_enabled', 'true', 'BOOLEAN'
FROM tenant t
WHERE t.home_country_code = 'BH'
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Verify
SELECT tenant_id, COALESCE(mid,'(default)') AS mid, txn_currency, board_rate, cost_rate, multiplier
FROM ref_ecom_fx_rate ORDER BY tenant_id, mid NULLS FIRST, txn_currency;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_07_01__netspread_ecom_fx_revenue.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_07_02__industry_analytics_menu.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_07_02: Industry Analytics menu entry.
--
-- /business/industry-analytics — the acquiring P&L split by industry (MCC
-- sector, ref_mcc_category vocabulary): volume, MSF, ICF, scheme fee, net
-- revenue and net spread with %-of-volume. Backed by
-- IndustryAnalyticsController over sum_daily_full (fee stack) +
-- sum_daily_merchant (ancillary spread legs). BUSINESS category,
-- display_order 22 (next free after Rentals' 21 from V2026_08_29_02).
--
-- The API is gated by @menuAccess.canAccess('/business/industry-analytics'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Industry Analytics', '/business/industry-analytics', 'Factory', 'BUSINESS', 22
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/industry-analytics');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/industry-analytics'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- Grant to any uppercase-named admin groups if present (defensive,
-- mirrors other menu migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/industry-analytics'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_07_02__industry_analytics_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_07_03__revenue_mix_menu.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_07_03: Revenue Mix menu entry.
--
-- /business/revenue-mix — the finance team's Destination × Card Type P&L
-- matrix (Local / International × Debit / Credit …): #txns, volume and the
-- fee waterfall MSF → ICF → net revenue → scheme fee → net margin, plus net
-- spread on totals. Backed by RevenueMixController over sum_daily_full
-- (fee stack) + sum_daily_merchant (ancillary spread legs). BUSINESS
-- category, display_order 23 (next free after Industry Analytics' 22 from
-- V2026_09_07_02).
--
-- The API is gated by @menuAccess.canAccess('/business/revenue-mix'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Revenue Mix', '/business/revenue-mix', 'PieChart', 'BUSINESS', 23
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/revenue-mix');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/revenue-mix'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- Grant to any uppercase-named admin groups if present (defensive,
-- mirrors other menu migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/revenue-mix'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_07_03__revenue_mix_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_09_07_04__volume_drop_menu.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_07_04: Volume Drop menu entry.
--
-- /business/volume-drop — per-merchant month-over-month volume comparison:
-- last month full volume vs current MTD, projected month-end (linear + pace)
-- and the drop/gain each implies, with RM / lead ownership columns. Backed by
-- VolumeDropController over sum_daily_merchant. BUSINESS category,
-- display_order 24 (next free after Revenue Mix's 23 from V2026_09_07_03).
--
-- The API is gated by @menuAccess.canAccess('/business/volume-drop'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Volume Drop', '/business/volume-drop', 'TrendingDown', 'BUSINESS', 24
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/volume-drop');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/volume-drop'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- Grant to any uppercase-named admin groups if present (defensive,
-- mirrors other menu migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/volume-drop'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_07_04__volume_drop_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ## V2026_08_30_01__mc_manual_rate_alignment_egypt.sql
-- ## !!! SKIPPED — DRAFT, DO NOT APPLY: Premium/Elite pct are PLACEHOLDERS.
-- ## Replace with BIN-weighted output of docs/deploy/EG_MC_BIN_WEIGHTING_2026-08-30.sql
-- ## first, then uncomment this whole section (remove the leading "-- " prefix).
-- ############################################################################

-- -- ============================================================================
-- -- V2026_08_30_01: align Egypt (EG) Mastercard LOCAL interchange with the
-- --                 Mastercard MEA interchange manual (Egypt intracountry
-- --                 consumer Credit rates), tier-wise, BIN-resolved.
-- --
-- -- ****************************************************************************
-- -- *** DRAFT — DO NOT APPLY YET. The Premium and Elite interchange_pct     ***
-- -- *** values below are PLACEHOLDERS (simple average of the folded manual  ***
-- -- *** sub-tiers). Replace them with the BIN-VOLUME-WEIGHTED figures from   ***
-- -- ***   docs/deploy/EG_MC_BIN_WEIGHTING_2026-08-30.sql                    ***
-- -- *** (run against the live EG DB) before this migration is applied.      ***
-- -- *** Standard (1.35%) is exact from the manual and needs no weighting.   ***
-- -- ****************************************************************************
-- --
-- -- SCOPE (user-confirmed 2026-08-30):
-- --   * "Replace" is scoped to the MASTERCARD portion of the EG card only.
-- --     The manual carries NO Visa / JCB / UnionPay / international / sector
-- --     schedule, so those EG rows are LEFT UNTOUCHED — a full wipe would strip
-- --     Egypt Visa pricing (776 rows) and surface NO_RATE_FOUND. Mirrors how
-- --     Bahrain's V2026_08_29_03 touched only MasterCard.
-- --   * Scheme fees: unchanged — V2026_07_31_05 already copied the UAE grid to
-- --     EG verbatim (INTERNATIONAL/DOMESTIC x POS/ECOM x scheme). Re-asserted
-- --     idempotently at the foot of this file in case that copy was cleared.
-- --
-- -- MANUAL -> SYSTEM TIER FOLDING (ape0122755144767, "All Others" general column;
-- -- Card Present == Full UCAF per tier in this manual, so POS == ECOM):
-- --     Standard  <- Standard/Gold ............ 1.35%
-- --     Premium   <- Titanium 1.85 + Platinum 2.00 .... BIN-weighted  [PLACEHOLDER 1.93]
-- --     Elite     <- World 2.15 + World Elite 2.20 .... BIN-weighted  [PLACEHOLDER 2.18]
-- --   Tier resolves from the card BIN via ref_bin_product_tier (global product
-- --   -> Standard/Premium/Elite map, already seeded by V2026_08_29_03) once the
-- --   EG tenant's card_type_source = 'BIN'. Without BIN tiering the engine falls
-- --   to its Premium default (FeeComputationService tier CASE), so unknown MC
-- --   still prices at Premium.
-- --
-- -- PREPAID (user asked for credit AND prepaid): the Egypt manual bundles
-- --   consumer credit (MCT), debit (MET) and prepaid (TPM) into the SAME
-- --   intracountry consumer programs at the SAME tier rate (IRD TS et al.), so
-- --   prepaid tier rows mirror the credit tier rates. DEBIT is not seeded here
-- --   (no separate table; keeps its existing EG treatment).
-- --
-- -- Splitter-safe (no dollar-quoting). Idempotent: the MC delete matches the
-- -- old EG MasterCard rows (re-run finds none once replaced), the tier insert is
-- -- per-row NOT EXISTS-guarded, the scheme-fee re-copy is NOT EXISTS-guarded.
-- -- BACKFILL: fees compute at ingest — re-ingest affected EG months.
-- -- ============================================================================
-- 
-- -- ---------------------------------------------------------------------------
-- -- 1. Remove the existing EG MasterCard interchange rows (per-MCC priority-60
-- --    and the priority-15 scheme default). The any-scheme (scheme_group IS NULL)
-- --    fallback rows are KEPT — they still price domestic Amex/unknown, and the
-- --    new tier rows below out-priority them for MasterCard.
-- -- ---------------------------------------------------------------------------
-- DELETE FROM interchange_rate_local
-- WHERE country_code = 'EG' AND tenant_id IS NULL
--   AND scheme_group = 'MasterCard';
-- 
-- -- ---------------------------------------------------------------------------
-- -- 2. Ensure the EG-relevant Mastercard consumer products bucket to tiers.
-- --    ref_bin_product_tier is global and already carries these (V2026_08_29_03);
-- --    this is a defensive top-up so EG works even if applied standalone.
-- -- ---------------------------------------------------------------------------
-- INSERT INTO ref_bin_product_tier (product_code, card_tier, note) VALUES
--   ('MCS', 'Standard', 'Mastercard Standard'),
--   ('MCG', 'Standard', 'Mastercard Gold'),
--   ('MCC', 'Standard', 'Mastercard Credit (mixed BIN)'),
--   ('MCT', 'Premium',  'Mastercard Titanium'),
--   ('MPL', 'Premium',  'Mastercard Platinum'),
--   ('MCW', 'Elite',    'World Mastercard'),
--   ('MWE', 'Elite',    'World Elite Mastercard'),
--   ('MRG', 'Standard', 'Prepaid Gold'),
--   ('MRH', 'Premium',  'Prepaid Platinum'),
--   ('MRW', 'Elite',    'Prepaid World'),
--   ('MWP', 'Elite',    'World Prepaid')
-- ON CONFLICT (product_code) DO NOTHING;
-- ANALYZE ref_bin_product_tier;
-- 
-- -- ---------------------------------------------------------------------------
-- -- 3. EG Mastercard tier rows (priority 30), credit + prepaid, POS + ECOM.
-- --    interchange_pct is a decimal fraction (0.013500 = 1.35%). cap NULL (the
-- --    manual's AllOth general rate carries no cap). cap_currency_code 'EGP'.
-- --
-- --    !!! Premium/Elite pct = PLACEHOLDER averages — replace with the
-- --        BIN-weighted output before applying (see banner + weighting SQL). !!!
-- -- ---------------------------------------------------------------------------
-- INSERT INTO interchange_rate_local
--     (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
--      card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
--      label, source_note)
-- SELECT v.* FROM ( VALUES
--   -- CREDIT --------------------------------------------------------------------
--   (NULL::INT, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Standard', NULL, NULL, NULL::NUMERIC, NULL::NUMERIC, 0.013500, NULL::NUMERIC,
--      'EG MC credit Standard POS 1.35 (manual Std/Gold AllOth)',
--      'DRAFT 2026-08-30: MC MEA manual Egypt intracountry consumer, tier-wise'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
--      'EG MC credit Standard ECOM 1.35 (manual Std/Gold AllOth, Full UCAF)',
--      'DRAFT 2026-08-30: MC MEA manual Egypt intracountry consumer, tier-wise'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Premium',  NULL, NULL, NULL, NULL, 0.019300, NULL,
--      'EG MC credit Premium POS (PLACEHOLDER 1.93 avg Ti/Pl; set BIN-wtd)',
--      'DRAFT 2026-08-30: PLACEHOLDER 1.93=avg(Ti 1.85,Pl 2.00) - replace pct with EG_MC_BIN_WEIGHTING output'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Premium',  NULL, NULL, NULL, NULL, 0.019300, NULL,
--      'EG MC credit Premium ECOM (PLACEHOLDER 1.93 avg Ti/Pl; set BIN-wtd)',
--      'DRAFT 2026-08-30: PLACEHOLDER 1.93=avg(Ti 1.85,Pl 2.00) - replace pct with EG_MC_BIN_WEIGHTING output'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Elite',    NULL, NULL, NULL, NULL, 0.021800, NULL,
--      'EG MC credit Elite POS (PLACEHOLDER 2.18 avg W/WE; set BIN-wtd)',
--      'DRAFT 2026-08-30: PLACEHOLDER 2.18=avg(World 2.15,World Elite 2.20) - replace pct with EG_MC_BIN_WEIGHTING output'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Elite',    NULL, NULL, NULL, NULL, 0.021800, NULL,
--      'EG MC credit Elite ECOM (PLACEHOLDER 2.18 avg W/WE; set BIN-wtd)',
--      'DRAFT 2026-08-30: PLACEHOLDER 2.18=avg(World 2.15,World Elite 2.20) - replace pct with EG_MC_BIN_WEIGHTING output'),
--   -- PREPAID (mirrors credit — manual bundles credit/debit/prepaid per tier) ----
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
--      'EG MC prepaid Standard POS 1.35 (manual Std/Gold AllOth)',
--      'DRAFT 2026-08-30: MC MEA manual Egypt intracountry consumer, tier-wise'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
--      'EG MC prepaid Standard ECOM 1.35 (manual Std/Gold AllOth, Full UCAF)',
--      'DRAFT 2026-08-30: MC MEA manual Egypt intracountry consumer, tier-wise'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Premium',  NULL, NULL, NULL, NULL, 0.019300, NULL,
--      'EG MC prepaid Premium POS (PLACEHOLDER 1.93; set BIN-wtd)',
--      'DRAFT 2026-08-30: PLACEHOLDER 1.93 - replace pct with EG_MC_BIN_WEIGHTING output'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Premium',  NULL, NULL, NULL, NULL, 0.019300, NULL,
--      'EG MC prepaid Premium ECOM (PLACEHOLDER 1.93; set BIN-wtd)',
--      'DRAFT 2026-08-30: PLACEHOLDER 1.93 - replace pct with EG_MC_BIN_WEIGHTING output'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Elite',    NULL, NULL, NULL, NULL, 0.021800, NULL,
--      'EG MC prepaid Elite POS (PLACEHOLDER 2.18; set BIN-wtd)',
--      'DRAFT 2026-08-30: PLACEHOLDER 2.18 - replace pct with EG_MC_BIN_WEIGHTING output'),
--   (NULL, 'EG', 'EGP', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Elite',    NULL, NULL, NULL, NULL, 0.021800, NULL,
--      'EG MC prepaid Elite ECOM (PLACEHOLDER 2.18; set BIN-wtd)',
--      'DRAFT 2026-08-30: PLACEHOLDER 2.18 - replace pct with EG_MC_BIN_WEIGHTING output')
-- ) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
--        card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
--        label, source_note)
-- WHERE NOT EXISTS (
--   SELECT 1 FROM interchange_rate_local x
--   WHERE x.country_code = 'EG' AND x.tenant_id IS NULL AND x.priority = 30
--     AND x.dest = 'DOMESTIC' AND x.channel = v.channel
--     AND x.scheme_group = v.scheme_group AND x.card_type = v.card_type
--     AND x.tier = v.tier
-- );
-- 
-- -- ---------------------------------------------------------------------------
-- -- 4. Scheme fees — same as UAE (already copied by V2026_07_31_05). Re-assert
-- --    idempotently: seed only if EG has no scheme rows at all.
-- -- ---------------------------------------------------------------------------
-- INSERT INTO scheme_fee_rate (tenant_id, country_code, dest, channel, scheme_group, fee_pct)
-- SELECT NULL, 'EG', s.dest, s.channel, s.scheme_group, s.fee_pct
-- FROM scheme_fee_rate s
-- WHERE s.country_code = 'AE' AND s.tenant_id IS NULL
--   AND NOT EXISTS (SELECT 1 FROM scheme_fee_rate y WHERE y.country_code = 'EG' AND y.tenant_id IS NULL);
-- 
-- ANALYZE interchange_rate_local;
-- ANALYZE scheme_fee_rate;
-- 
-- INSERT INTO schema_migration_log (filename)
-- VALUES ('V2026_08_30_01__mc_manual_rate_alignment_egypt.sql')
-- ON CONFLICT (filename) DO NOTHING;
