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
