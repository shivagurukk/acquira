-- ============================================================================
-- V2026_07_07_01: MCC-keyed interchange rate card (UAE) + scheme-fee re-tune.
--
-- BUILDS ON V2026_07_05_01 (interchange_rate_local / scheme_fee_rate / mcc_sector_map).
-- Two independent changes, both idempotent + splitter-safe (NO dollar-quoting,
-- so this file is safe to list in schema-locations; on prod
-- (sql.init disabled) apply once via psql).
--
-- CHANGE 1 - MCC-KEYED INTERCHANGE OVERRIDES (priority 50)
-- --------------------------------------------------------
-- The UAE rate card (Business_case_UAE Template) prices 26 MCCs at a
-- 'Preferential' rate that differs from the Normal scheme/tier base. These land
-- as priority-50 interchange_rate_local rows keyed on a new `mcc` match column
-- (NULL = wildcard, as every existing row). Priority 50 beats the priority-10
-- scheme base, the priority-11 JCB/UPI flat, the priority-20 sector rows, so an
-- MCC-override row wins whenever dim_store.mcc matches. Rows carry the debit/
-- prepaid cap from the card (POS -32.5/-25, ECOM -50/-25 etc.). POS and ECOM are
-- seeded separately because a few MCCs differ by channel.
--
-- Each override MCC emits 6 rows x 2 channels = 12 rows:
--   debit / prepaid (scheme-agnostic, capped) + MC Std/Prem + Visa Std/Prem.
--
-- DEFERRED (non-numeric card cells - NOT fabricated as a %):
--   6051 (Exchange House)  -> debit-local cell is 'AED 2' (a flat per-txn fee,
--                             not a %). Already covered by the REX sector row
--                             (priority 20, MC 3670-AED threshold) from
--                             V2026_07_05_01. Left on that fallback.
--   8398 / 8661            -> MC credit cell is '25 Cents' (flat per-txn fee).
--                             Visa/debit here are numeric but the MC leg is a
--                             flat fee the % model can't express. Left to fall
--                             through to the priority-10 MC base + priority-20
--                             sector, rather than encode a wrong %.
-- These three are a conscious fallback, flagged for a future flat-fee model.
--
-- CHANGE 2 - SCHEME-FEE RE-TUNE + WILDCARD CATCH-ALL
-- ---------------------------------------------------
-- Locked business rates:
--   VISA / MASTERCARD / AMEX (and any unmapped scheme via wildcard):
--     DOMESTIC POS 0.12%  DOMESTIC ECOM 0.14%
--     INTERNATIONAL POS 0.75%  INTERNATIONAL ECOM 0.90%
--   JCB / UNIONPAY(UPI): flat 0.05% (any destination, any channel) - unchanged.
-- Two fixes vs V2026_07_05_01's grid:
--   (a) DOMESTIC POS/ECOM move 0.11/0.13 -> 0.12/0.14.
--   (b) A wildcard scheme_group IS NULL row set is seeded so Amex and any
--       unmapped scheme resolve to 0.12/0.14/0.75/0.90 instead of ZERO. The
--       ingest LATERAL already prefers a scheme-specific row over the wildcard
--       (ORDER BY (scheme_group IS NOT NULL) DESC), so Visa/MC/JCB/UPI still hit
--       their own rows; only Amex/unmapped fall to the wildcard.
--
-- Reseeds the whole ACQ scheme grid (DELETE ACQ rows + INSERT) so re-running
-- lands the same state. uq_scheme_fee_rate_key (tenant,dest,channel,
-- COALESCE(scheme_group,'')) from V2026_07_05_01 keeps the wildcard row unique.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. New match column: interchange_rate_local.mcc (NULL = wildcard)
-- ---------------------------------------------------------------------------
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS mcc VARCHAR(10);
CREATE INDEX IF NOT EXISTS idx_interchange_rate_local_mcc
    ON interchange_rate_local (tenant_id, mcc) WHERE mcc IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 1. Priority-50 MCC-keyed override rows (26 MCCs x 6 x POS/ECOM = 312 rows).
--    Guarded so re-running does not duplicate: delete existing priority-50 mcc
--    rows for ACQ first, then insert. (These rows all have mcc IS NOT NULL, so
--    the delete never touches the base grid.)
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local ilr
USING tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 50 AND ilr.mcc IS NOT NULL;

INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, v.priority, v.dest, v.channel, v.scheme_group, v.card_type, v.tier, v.mcc_sector,
       v.mcc, v.min_ticket::numeric, v.max_ticket::numeric, v.interchange_pct::numeric, v.cap_amount::numeric, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8244', NULL, NULL, 0.006500, 32.50, 'MCC 8244 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8244', NULL, NULL, 0.006500, 32.50, 'MCC 8244 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8220', NULL, NULL, 0.006500, 32.50, 'MCC 8220 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8220', NULL, NULL, 0.006500, 32.50, 'MCC 8220 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8241', NULL, NULL, 0.006500, 32.50, 'MCC 8241 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8241', NULL, NULL, 0.006500, 32.50, 'MCC 8241 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8211', NULL, NULL, 0.006500, 32.50, 'MCC 8211 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8211', NULL, NULL, 0.006500, 32.50, 'MCC 8211 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8299', NULL, NULL, 0.006500, 32.50, 'MCC 8299 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8299', NULL, NULL, 0.006500, 32.50, 'MCC 8299 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8249', NULL, NULL, 0.006500, 32.50, 'MCC 8249 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8249', NULL, NULL, 0.006500, 32.50, 'MCC 8249 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '5542', NULL, NULL, 0.005000, 25.00, 'MCC 5542 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '5542', NULL, NULL, 0.005000, 25.00, 'MCC 5542 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '5542', NULL, NULL, 0.005000, NULL, 'MCC 5542 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '5542', NULL, NULL, 0.005000, NULL, 'MCC 5542 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '5542', NULL, NULL, 0.007000, NULL, 'MCC 5542 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '5542', NULL, NULL, 0.007000, NULL, 'MCC 5542 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '5541', NULL, NULL, 0.005000, 25.00, 'MCC 5541 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '5541', NULL, NULL, 0.005000, 25.00, 'MCC 5541 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '5541', NULL, NULL, 0.005000, NULL, 'MCC 5541 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '5541', NULL, NULL, 0.005000, NULL, 'MCC 5541 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '5541', NULL, NULL, 0.007000, NULL, 'MCC 5541 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '5541', NULL, NULL, 0.007000, NULL, 'MCC 5541 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9223', NULL, NULL, 0.005000, 25.00, 'MCC 9223 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9223', NULL, NULL, 0.005000, 25.00, 'MCC 9223 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9211', NULL, NULL, 0.005000, 25.00, 'MCC 9211 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9211', NULL, NULL, 0.005000, 25.00, 'MCC 9211 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9222', NULL, NULL, 0.005000, 25.00, 'MCC 9222 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9222', NULL, NULL, 0.005000, 25.00, 'MCC 9222 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9399', NULL, NULL, 0.005000, 25.00, 'MCC 9399 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9399', NULL, NULL, 0.005000, 25.00, 'MCC 9399 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9311', NULL, NULL, 0.005000, 25.00, 'MCC 9311 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9311', NULL, NULL, 0.005000, 25.00, 'MCC 9311 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '6513', NULL, NULL, 0.006500, 32.50, 'MCC 6513 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '6513', NULL, NULL, 0.006500, 32.50, 'MCC 6513 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9402', NULL, NULL, 0.005000, 25.00, 'MCC 9402 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9402', NULL, NULL, 0.005000, 25.00, 'MCC 9402 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9405', NULL, NULL, 0.005000, 25.00, 'MCC 9405 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9405', NULL, NULL, 0.005000, 25.00, 'MCC 9405 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9405', NULL, NULL, 0.005000, NULL, 'MCC 9405 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9405', NULL, NULL, 0.005000, NULL, 'MCC 9405 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9405', NULL, NULL, 0.011500, NULL, 'MCC 9405 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9405', NULL, NULL, 0.018000, NULL, 'MCC 9405 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '7523', NULL, NULL, 0.005000, 25.00, 'MCC 7523 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '7523', NULL, NULL, 0.005000, 25.00, 'MCC 7523 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '7523', NULL, NULL, 0.012500, NULL, 'MCC 7523 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '7523', NULL, NULL, 0.018000, NULL, 'MCC 7523 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '7523', NULL, NULL, 0.006500, NULL, 'MCC 7523 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '7523', NULL, NULL, 0.006500, NULL, 'MCC 7523 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4131', NULL, NULL, 0.005000, 25.00, 'MCC 4131 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4131', NULL, NULL, 0.005000, 25.00, 'MCC 4131 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4111', NULL, NULL, 0.005000, 25.00, 'MCC 4111 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4111', NULL, NULL, 0.005000, 25.00, 'MCC 4111 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4112', NULL, NULL, 0.005000, 25.00, 'MCC 4112 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4112', NULL, NULL, 0.005000, 25.00, 'MCC 4112 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4112', NULL, NULL, 0.005000, NULL, 'MCC 4112 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4112', NULL, NULL, 0.005000, NULL, 'MCC 4112 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4112', NULL, NULL, 0.011500, NULL, 'MCC 4112 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4112', NULL, NULL, 0.018000, NULL, 'MCC 4112 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4121', NULL, NULL, 0.005000, 25.00, 'MCC 4121 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4121', NULL, NULL, 0.005000, 25.00, 'MCC 4121 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4784', NULL, NULL, 0.005000, 25.00, 'MCC 4784 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4784', NULL, NULL, 0.005000, 25.00, 'MCC 4784 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4784', NULL, NULL, 0.005000, NULL, 'MCC 4784 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4784', NULL, NULL, 0.005000, NULL, 'MCC 4784 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4784', NULL, NULL, 0.006500, NULL, 'MCC 4784 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4784', NULL, NULL, 0.006500, NULL, 'MCC 4784 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4899', NULL, NULL, 0.005000, 25.00, 'MCC 4899 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4899', NULL, NULL, 0.005000, 25.00, 'MCC 4899 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4899', NULL, NULL, 0.005000, NULL, 'MCC 4899 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4899', NULL, NULL, 0.005000, NULL, 'MCC 4899 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4899', NULL, NULL, 0.011500, NULL, 'MCC 4899 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4899', NULL, NULL, 0.018000, NULL, 'MCC 4899 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4816', NULL, NULL, 0.005000, 25.00, 'MCC 4816 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4816', NULL, NULL, 0.005000, 25.00, 'MCC 4816 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4816', NULL, NULL, 0.005000, NULL, 'MCC 4816 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4816', NULL, NULL, 0.005000, NULL, 'MCC 4816 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4816', NULL, NULL, 0.011500, NULL, 'MCC 4816 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4816', NULL, NULL, 0.018000, NULL, 'MCC 4816 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4814', NULL, NULL, 0.005000, 25.00, 'MCC 4814 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4814', NULL, NULL, 0.005000, 25.00, 'MCC 4814 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4814', NULL, NULL, 0.005000, NULL, 'MCC 4814 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4814', NULL, NULL, 0.005000, NULL, 'MCC 4814 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4814', NULL, NULL, 0.011500, NULL, 'MCC 4814 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4814', NULL, NULL, 0.018000, NULL, 'MCC 4814 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4900', NULL, NULL, 0.005000, 25.00, 'MCC 4900 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4900', NULL, NULL, 0.005000, 25.00, 'MCC 4900 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8244', NULL, NULL, 0.006500, 32.50, 'MCC 8244 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8244', NULL, NULL, 0.006500, 32.50, 'MCC 8244 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8220', NULL, NULL, 0.006500, 32.50, 'MCC 8220 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8220', NULL, NULL, 0.006500, 32.50, 'MCC 8220 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8241', NULL, NULL, 0.006500, 32.50, 'MCC 8241 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8241', NULL, NULL, 0.006500, 32.50, 'MCC 8241 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8211', NULL, NULL, 0.006500, 32.50, 'MCC 8211 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8211', NULL, NULL, 0.006500, 32.50, 'MCC 8211 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8299', NULL, NULL, 0.006500, 32.50, 'MCC 8299 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8299', NULL, NULL, 0.006500, 32.50, 'MCC 8299 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8249', NULL, NULL, 0.006500, 32.50, 'MCC 8249 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8249', NULL, NULL, 0.006500, 32.50, 'MCC 8249 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '5542', NULL, NULL, 0.005000, 25.00, 'MCC 5542 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '5542', NULL, NULL, 0.005000, 25.00, 'MCC 5542 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '5542', NULL, NULL, 0.005000, NULL, 'MCC 5542 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '5542', NULL, NULL, 0.005000, NULL, 'MCC 5542 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '5542', NULL, NULL, 0.007000, NULL, 'MCC 5542 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '5542', NULL, NULL, 0.007000, NULL, 'MCC 5542 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '5541', NULL, NULL, 0.005000, 25.00, 'MCC 5541 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '5541', NULL, NULL, 0.005000, 25.00, 'MCC 5541 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '5541', NULL, NULL, 0.005000, NULL, 'MCC 5541 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '5541', NULL, NULL, 0.005000, NULL, 'MCC 5541 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '5541', NULL, NULL, 0.007000, NULL, 'MCC 5541 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '5541', NULL, NULL, 0.007000, NULL, 'MCC 5541 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9223', NULL, NULL, 0.005000, 25.00, 'MCC 9223 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9223', NULL, NULL, 0.005000, 25.00, 'MCC 9223 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9211', NULL, NULL, 0.005000, 25.00, 'MCC 9211 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9211', NULL, NULL, 0.005000, 25.00, 'MCC 9211 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9222', NULL, NULL, 0.005000, 25.00, 'MCC 9222 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9222', NULL, NULL, 0.005000, 25.00, 'MCC 9222 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9399', NULL, NULL, 0.005000, 25.00, 'MCC 9399 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9399', NULL, NULL, 0.005000, 25.00, 'MCC 9399 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9311', NULL, NULL, 0.005000, 25.00, 'MCC 9311 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9311', NULL, NULL, 0.005000, 25.00, 'MCC 9311 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '6513', NULL, NULL, 0.006500, 32.50, 'MCC 6513 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '6513', NULL, NULL, 0.006500, 32.50, 'MCC 6513 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9402', NULL, NULL, 0.005000, 25.00, 'MCC 9402 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9402', NULL, NULL, 0.005000, 25.00, 'MCC 9402 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9405', NULL, NULL, 0.005000, 25.00, 'MCC 9405 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9405', NULL, NULL, 0.005000, 25.00, 'MCC 9405 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9405', NULL, NULL, 0.005000, NULL, 'MCC 9405 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9405', NULL, NULL, 0.005000, NULL, 'MCC 9405 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9405', NULL, NULL, 0.011500, NULL, 'MCC 9405 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9405', NULL, NULL, 0.018000, NULL, 'MCC 9405 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '7523', NULL, NULL, 0.005000, 25.00, 'MCC 7523 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '7523', NULL, NULL, 0.005000, 25.00, 'MCC 7523 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '7523', NULL, NULL, 0.012500, NULL, 'MCC 7523 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '7523', NULL, NULL, 0.018000, NULL, 'MCC 7523 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '7523', NULL, NULL, 0.006500, NULL, 'MCC 7523 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '7523', NULL, NULL, 0.006500, NULL, 'MCC 7523 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4131', NULL, NULL, 0.005000, 25.00, 'MCC 4131 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4131', NULL, NULL, 0.005000, 25.00, 'MCC 4131 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4111', NULL, NULL, 0.005000, 25.00, 'MCC 4111 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4111', NULL, NULL, 0.005000, 25.00, 'MCC 4111 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4112', NULL, NULL, 0.005000, 25.00, 'MCC 4112 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4112', NULL, NULL, 0.005000, 25.00, 'MCC 4112 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4112', NULL, NULL, 0.005000, NULL, 'MCC 4112 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4112', NULL, NULL, 0.005000, NULL, 'MCC 4112 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4112', NULL, NULL, 0.011500, NULL, 'MCC 4112 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4112', NULL, NULL, 0.018000, NULL, 'MCC 4112 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4121', NULL, NULL, 0.005000, 25.00, 'MCC 4121 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4121', NULL, NULL, 0.005000, 25.00, 'MCC 4121 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4784', NULL, NULL, 0.005000, 25.00, 'MCC 4784 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4784', NULL, NULL, 0.005000, 25.00, 'MCC 4784 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4784', NULL, NULL, 0.005000, NULL, 'MCC 4784 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4784', NULL, NULL, 0.005000, NULL, 'MCC 4784 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4784', NULL, NULL, 0.006500, NULL, 'MCC 4784 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4784', NULL, NULL, 0.006500, NULL, 'MCC 4784 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4899', NULL, NULL, 0.005000, 25.00, 'MCC 4899 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4899', NULL, NULL, 0.005000, 25.00, 'MCC 4899 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4899', NULL, NULL, 0.005000, NULL, 'MCC 4899 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4899', NULL, NULL, 0.005000, NULL, 'MCC 4899 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4899', NULL, NULL, 0.011500, NULL, 'MCC 4899 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4899', NULL, NULL, 0.018000, NULL, 'MCC 4899 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4816', NULL, NULL, 0.005000, 25.00, 'MCC 4816 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4816', NULL, NULL, 0.005000, 25.00, 'MCC 4816 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4816', NULL, NULL, 0.005000, NULL, 'MCC 4816 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4816', NULL, NULL, 0.005000, NULL, 'MCC 4816 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4816', NULL, NULL, 0.011500, NULL, 'MCC 4816 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4816', NULL, NULL, 0.018000, NULL, 'MCC 4816 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4814', NULL, NULL, 0.005000, 25.00, 'MCC 4814 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4814', NULL, NULL, 0.005000, 25.00, 'MCC 4814 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4814', NULL, NULL, 0.005000, NULL, 'MCC 4814 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4814', NULL, NULL, 0.005000, NULL, 'MCC 4814 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4814', NULL, NULL, 0.011500, NULL, 'MCC 4814 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4814', NULL, NULL, 0.018000, NULL, 'MCC 4814 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4900', NULL, NULL, 0.005000, 25.00, 'MCC 4900 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4900', NULL, NULL, 0.005000, 25.00, 'MCC 4900 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 Visa Prem')
) AS v(priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
       mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label);

-- ---------------------------------------------------------------------------
-- 2. Scheme-fee re-tune (0.12/0.14/0.75/0.90 + wildcard) for ACQ.
--    Reseed: delete all ACQ scheme_fee_rate rows, re-insert the full matrix.
-- ---------------------------------------------------------------------------
DELETE FROM scheme_fee_rate sfr
USING tenant t
WHERE sfr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ';

INSERT INTO scheme_fee_rate (tenant_id, dest, channel, scheme_group, fee_pct)
SELECT t.tenant_id, v.dest, v.channel, v.scheme_group, v.fee_pct::numeric
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    -- VISA
    ('DOMESTIC','POS','Visa',             0.001200),
    ('DOMESTIC','ECOM','Visa',            0.001400),
    ('INTERNATIONAL','POS','Visa',        0.007500),
    ('INTERNATIONAL','ECOM','Visa',       0.009000),
    -- MASTERCARD
    ('DOMESTIC','POS','MasterCard',       0.001200),
    ('DOMESTIC','ECOM','MasterCard',      0.001400),
    ('INTERNATIONAL','POS','MasterCard',  0.007500),
    ('INTERNATIONAL','ECOM','MasterCard', 0.009000),
    -- AMEX (explicit, same as Visa/MC)
    ('DOMESTIC','POS','Amex',             0.001200),
    ('DOMESTIC','ECOM','Amex',            0.001400),
    ('INTERNATIONAL','POS','Amex',        0.007500),
    ('INTERNATIONAL','ECOM','Amex',       0.009000),
    -- JCB flat 0.05 (all dest x channel) - unchanged
    ('DOMESTIC','POS','JCB',              0.000500),
    ('DOMESTIC','ECOM','JCB',             0.000500),
    ('INTERNATIONAL','POS','JCB',         0.000500),
    ('INTERNATIONAL','ECOM','JCB',        0.000500),
    -- UNIONPAY / UPI flat 0.05 (all dest x channel) - unchanged
    ('DOMESTIC','POS','UnionPay',         0.000500),
    ('DOMESTIC','ECOM','UnionPay',        0.000500),
    ('INTERNATIONAL','POS','UnionPay',    0.000500),
    ('INTERNATIONAL','ECOM','UnionPay',   0.000500),
    -- WILDCARD (scheme_group NULL): Amex/unmapped fall here -> 0.12/0.14/0.75/0.90
    ('DOMESTIC','POS',NULL,               0.001200),
    ('DOMESTIC','ECOM',NULL,              0.001400),
    ('INTERNATIONAL','POS',NULL,          0.007500),
    ('INTERNATIONAL','ECOM',NULL,         0.009000)
) AS v(dest, channel, scheme_group, fee_pct);
