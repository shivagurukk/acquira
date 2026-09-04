-- ============================================================================
-- V2026_07_07_04: Rate-card corrections (business-confirmed 2026-07-07).
--
-- CURRENCY RULE (2026-07-07): the Business_case_UAE Template is priced in USD
-- (POS sheet 'Currency: USD'). All CAP amounts are USD and must be stored in
-- AED at 1 USD = 3.67 AED (fees compute off AED settlement amounts):
--     USD 25    -> AED  91.75
--     USD 32.5  -> AED 119.275
--     USD 37.5  -> AED 137.625
--     USD 50    -> AED 183.5
--     USD 1     -> AED   3.67
-- Ticket thresholds were already AED (36700 = 10000x3.67, 3670 = 1000x3.67).
-- Percentages are currency-agnostic - unchanged.
--
-- ORDER DEPENDENCY: this file MUST run AFTER V2026_07_07_01 in
-- schema-locations (it is listed after). Section 4 converts the USD caps that
-- V2026_07_07_01 reseeds on every startup into AED. Keep the order.
--
-- Business rules encoded here:
--   * ALL international interchange = flat 1.85% regardless of card type
--     (the priority-1 row from V2026_07_05_01). Excel rates are LOCAL only.
--     (Excel ECOM sheet shows intl 1.9% - business override says 1.85 flat.)
--   * Tier (companion code change, TransactionJobConfig): card_subtype 1
--     (MCSD/VISD) -> Standard; EVERYTHING else (AMEX/JCB/UPI/VICR/MCCR/MCCP/
--     MCPM/VIPM/VICP/generic/unmatched) -> Premium.
--   * Credit-prepaid (MCCP) PRICED as CREDIT Premium; debit-prepaid (MCDP)
--     and VIDB/MCDB/ZPET on the local debit rate.
--
-- STILL DEFERRED (non-numeric cells, NOT fabricated):
--   6051 debit 'AED 2' (already AED - flat per-txn fee, needs flat-fee model)
--   8398/8661 MC '25 Cents' (USD 0.25 -> AED 0.9175 flat fee, same model gap)
--   5511/5521 Visa '(1.5% & $150+0.30%)'
--
-- All statements idempotent + splitter-safe (no dollar-quoting). On prod
-- (sql.init mode=never) apply V2026_07_07_01 then this file, in order, via
-- psql. BACKFILL: fees compute at ingest - re-upload affected months.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Undo the wrong international debit/prepaid 1.0% rows from the earlier
--    draft of this file (no-op if that draft never ran).
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local ilr
USING tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 2 AND ilr.dest = 'INTERNATIONAL';

-- ---------------------------------------------------------------------------
-- 2. Channel-split local debit/prepaid base.
--    REVISED 2026-08-29 (V2026_08_29_03 alignment): the MC MEA manual states
--    these caps in AED ("0.75%, max AED 37.50"), not USD - the earlier x3.67
--    conversion was a currency misread. Manual figures:
--       POS  debit   0.75% cap AED 37.50
--       POS  prepaid 1.00% cap AED 50.00 (prepaid is 1.00 in every program)
--       ECOM both    1.00% cap AED 50.00
--    Delete covers the original channel-NULL rows, this migration's own rows
--    (re-run safe), and any earlier-draft rows.
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local ilr
USING tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 10 AND ilr.card_type IN ('DEBIT','PREPAID')
  AND ilr.scheme_group IS NULL AND ilr.mcc IS NULL;

INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, v.priority, v.dest, v.channel, v.scheme_group, v.card_type, v.tier, v.mcc_sector,
       v.mcc, v.min_ticket::numeric, v.max_ticket::numeric, v.interchange_pct::numeric, v.cap_amount::numeric, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    (10, 'DOMESTIC', 'POS',  NULL, 'DEBIT',   NULL, NULL, NULL, NULL, NULL, 0.007500,  37.50, 'Local debit POS 0.75 (cap AED 37.50, MC manual Aug-2026)'),
    (10, 'DOMESTIC', 'POS',  NULL, 'PREPAID', NULL, NULL, NULL, NULL, NULL, 0.010000,  50.00, 'Local prepaid POS 1.00 (cap AED 50, MC manual Aug-2026)'),
    (10, 'DOMESTIC', 'ECOM', NULL, 'DEBIT',   NULL, NULL, NULL, NULL, NULL, 0.010000,  50.00, 'Local debit ECOM 1.00 (cap AED 50, MC manual Aug-2026)'),
    (10, 'DOMESTIC', 'ECOM', NULL, 'PREPAID', NULL, NULL, NULL, NULL, NULL, 0.010000,  50.00, 'Local prepaid ECOM 1.00 (cap AED 50, MC manual Aug-2026)')
) AS v(priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
       mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label);

-- ---------------------------------------------------------------------------
-- 3. Second preferential MCC family (missed by V2026_07_07_01, which seeded
--    only MCCs whose DEBIT rate also deviates). Credit-only overrides,
--    identical on POS and ECOM -> channel-NULL, tier-NULL (Excel Std =
--    Premium), priority 50, mcc-keyed. Scoped delete only touches rows THIS
--    migration creates (channel IS NULL; V2026_07_07_01 rows are all
--    channel-explicit).
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local ilr
USING tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 50 AND ilr.mcc IS NOT NULL AND ilr.channel IS NULL;

INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, 50, 'DOMESTIC', NULL, v.scheme_group, 'CREDIT', NULL, NULL,
       v.mcc, NULL, NULL, v.interchange_pct::numeric, NULL, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    -- MC credit flat 1.30
    ('MasterCard', '4468', 0.013000, 'MCC 4468 MC flat 1.30'),
    ('MasterCard', '5013', 0.013000, 'MCC 5013 MC flat 1.30'),
    ('MasterCard', '5511', 0.013000, 'MCC 5511 MC flat 1.30'),
    ('MasterCard', '5521', 0.013000, 'MCC 5521 MC flat 1.30'),
    ('MasterCard', '5531', 0.013000, 'MCC 5531 MC flat 1.30'),
    ('MasterCard', '5532', 0.013000, 'MCC 5532 MC flat 1.30'),
    ('MasterCard', '5533', 0.013000, 'MCC 5533 MC flat 1.30'),
    ('MasterCard', '5551', 0.013000, 'MCC 5551 MC flat 1.30'),
    ('MasterCard', '5561', 0.013000, 'MCC 5561 MC flat 1.30'),
    ('MasterCard', '5571', 0.013000, 'MCC 5571 MC flat 1.30'),
    ('MasterCard', '5592', 0.013000, 'MCC 5592 MC flat 1.30'),
    ('MasterCard', '5599', 0.013000, 'MCC 5599 MC flat 1.30'),
    ('MasterCard', '7531', 0.013000, 'MCC 7531 MC flat 1.30'),
    ('MasterCard', '7534', 0.013000, 'MCC 7534 MC flat 1.30'),
    ('MasterCard', '7535', 0.013000, 'MCC 7535 MC flat 1.30'),
    ('MasterCard', '7538', 0.013000, 'MCC 7538 MC flat 1.30'),
    -- MC credit flat 1.16
    ('MasterCard', '4829', 0.011600, 'MCC 4829 MC flat 1.16'),
    ('MasterCard', '6051', 0.011600, 'MCC 6051 MC flat 1.16'),
    -- MC credit flat 1.00
    ('MasterCard', '5193', 0.010000, 'MCC 5193 MC flat 1.00'),
    ('MasterCard', '5811', 0.010000, 'MCC 5811 MC flat 1.00'),
    ('MasterCard', '5942', 0.010000, 'MCC 5942 MC flat 1.00'),
    ('MasterCard', '5992', 0.010000, 'MCC 5992 MC flat 1.00'),
    ('MasterCard', '7210', 0.010000, 'MCC 7210 MC flat 1.00'),
    ('MasterCard', '7211', 0.010000, 'MCC 7211 MC flat 1.00'),
    ('MasterCard', '7216', 0.010000, 'MCC 7216 MC flat 1.00'),
    ('MasterCard', '7217', 0.010000, 'MCC 7217 MC flat 1.00'),
    ('MasterCard', '7221', 0.010000, 'MCC 7221 MC flat 1.00'),
    ('MasterCard', '5960', 0.010000, 'MCC 5960 MC flat 1.00'),
    ('MasterCard', '6300', 0.010000, 'MCC 6300 MC flat 1.00'),
    -- MC credit flat 1.05
    ('MasterCard', '5411', 0.010500, 'MCC 5411 MC flat 1.05'),
    -- Visa credit flats
    ('Visa',       '5411', 0.010500, 'MCC 5411 Visa flat 1.05'),
    ('Visa',       '5960', 0.010000, 'MCC 5960 Visa flat 1.00'),
    ('Visa',       '6300', 0.010000, 'MCC 6300 Visa flat 1.00'),
    ('Visa',       '8398', 0.006500, 'MCC 8398 Visa flat 0.65')
) AS v(scheme_group, mcc, interchange_pct, label);

-- 8398/8661 DEBIT/PREPAID: Excel 0.65% cap USD 1 -> AED 3.67
-- (charity/religious-org micro-cap; consistent across both channels/MCCs).
-- FLAGGED assumption - delete these 8 rows if business reads '-1' differently.
INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, 50, 'DOMESTIC', NULL, NULL, v.card_type, NULL, NULL,
       v.mcc, NULL, NULL, 0.006500, 1.00, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('DEBIT',   '8398', 'MCC 8398 debit 0.65 (cap AED 1.00, MC manual Aug-2026)'),
    ('PREPAID', '8398', 'MCC 8398 prepaid 0.65 (cap AED 1.00, MC manual Aug-2026)'),
    ('DEBIT',   '8661', 'MCC 8661 debit 0.65 (cap AED 1.00, MC manual Aug-2026)'),
    ('PREPAID', '8661', 'MCC 8661 prepaid 0.65 (cap AED 1.00, MC manual Aug-2026)')
) AS v(card_type, mcc, label);

-- ---------------------------------------------------------------------------
-- 4. Cap healing (REVERSED 2026-08-29, see V2026_08_29_03).
--    This section used to convert the workbook caps USD -> AED (x3.67); the
--    MC MEA manual states the caps in AED, so the conversion was a currency
--    misread. V2026_07_07_01's reseeded 32.50 / 25.00 caps are now correct
--    as-is. This section's remaining job is to HEAL any DB that carries the
--    old converted values back to the manual's AED figures. Keyed on the
--    exact converted values - re-run safe (second run matches nothing).
-- ---------------------------------------------------------------------------
UPDATE interchange_rate_local ilr SET cap_amount = 32.50
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 119.275;

UPDATE interchange_rate_local ilr SET cap_amount = 25.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 91.75;

UPDATE interchange_rate_local ilr SET cap_amount = 37.50
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 137.625;

UPDATE interchange_rate_local ilr SET cap_amount = 50.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 183.50;

UPDATE interchange_rate_local ilr SET cap_amount = 1.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 3.67 AND ilr.mcc IN ('8398','8661');
