-- ============================================================================
-- ROLLBACK of V2026_10_05_01 + V2026_10_05_02 (Mastercard UAE, 1 Oct 2026):
--   - MEA 13665.1 Acquiring Growth Enablement Fund (+0.03% scheme fee on
--     Mastercard INTERNATIONAL and DOMESTIC CREDIT)
--   - MEA 12465.2 recategorisation of nine UAE MCCs (interchange end-dating)
--
-- Run ONLY on a database where those two scripts were applied (check first:
--   SELECT filename FROM schema_migration_log WHERE filename LIKE 'V2026_10_05_0%';
-- no rows = nothing to roll back). Every statement is idempotent and
-- splitter-safe (no $$). Pair with the batch build that does NOT read
-- scheme_fee_rate.card_type (the FeeComputationService card_type change is
-- reverted in the same rollback). Afterwards rebuild-summaries with
-- reprice:true for AE from October 2026 if October data was repriced.
-- ============================================================================

-- 1. V2026_10_05_02: remove the CREDIT-only Mastercard domestic rows.
DELETE FROM scheme_fee_rate
WHERE country_code = 'AE'
  AND (tenant_id IS NULL OR tenant_id = 1)
  AND dest = 'DOMESTIC'
  AND scheme_group = 'MasterCard'
  AND card_type = 'CREDIT'
  AND effective_from = DATE '2026-10-01'
  AND source_note LIKE 'MC MEA 13665.1%';

-- 2. V2026_10_05_01 B2: remove the +0.03% international successor rows.
DELETE FROM scheme_fee_rate
WHERE country_code = 'AE'
  AND (tenant_id IS NULL OR tenant_id = 1)
  AND dest = 'INTERNATIONAL'
  AND scheme_group = 'MasterCard'
  AND effective_from = DATE '2026-10-01'
  AND source_note LIKE 'MC MEA 13665.1%';

-- 3. V2026_10_05_01 B3: re-open the international predecessors.
UPDATE scheme_fee_rate
SET effective_to = NULL
WHERE country_code = 'AE'
  AND (tenant_id IS NULL OR tenant_id = 1)
  AND dest = 'INTERNATIONAL'
  AND scheme_group = 'MasterCard'
  AND effective_from IS NULL
  AND effective_to = DATE '2026-09-30';

-- 4. V2026_10_05_01 B1: restore the pre-change unique key (V2026_07_31_02)
--    and drop the card_type column. Steps 1-3 must have run first or the
--    old key will collide on the successor rows.
DROP INDEX IF EXISTS uq_scheme_fee_rate_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_scheme_fee_rate_key
    ON scheme_fee_rate (country_code, COALESCE(tenant_id, 0), dest, channel, COALESCE(scheme_group, ''));
ALTER TABLE scheme_fee_rate DROP COLUMN IF EXISTS card_type;

-- 5. V2026_10_05_01 A: re-open the preferential 1.00% interchange rows for
--    the nine recategorised MCCs. The original source_note was overwritten by
--    the forward script and is not recoverable here; it is cleared.
UPDATE interchange_rate_local
SET effective_to = NULL,
    source_note  = NULL
WHERE country_code = 'AE'
  AND (tenant_id IS NULL OR tenant_id = 1)
  AND dest = 'DOMESTIC'
  AND scheme_group = 'MasterCard'
  AND card_type = 'CREDIT'
  AND mcc IN ('5193','5811','5942','5992','7210','7211','7216','7217','7221')
  AND effective_to = DATE '2026-09-30'
  AND source_note LIKE 'Ended 2026-09-30: MC MEA 12465.2%';

-- 6. Ledger.
DELETE FROM schema_migration_log
WHERE filename IN ('V2026_10_05_01__mc_uae_oct2026_recat_and_intl_scheme_fee.sql',
                   'V2026_10_05_02__mc_uae_dom_credit_scheme_fee.sql');

-- CHECK: expect no 2026-10-01 Mastercard rows, no 2026-09-30 end dates, and
-- no card_type column.
SELECT tenant_id, dest, channel, fee_pct, effective_from, effective_to, source_note
FROM scheme_fee_rate
WHERE country_code = 'AE' AND scheme_group = 'MasterCard'
ORDER BY tenant_id, dest, channel, effective_from;

SELECT tenant_id, mcc, interchange_pct, effective_from, effective_to
FROM interchange_rate_local
WHERE country_code = 'AE' AND scheme_group = 'MasterCard' AND card_type = 'CREDIT'
  AND mcc IN ('5193','5811','5942','5992','7210','7211','7216','7217','7221')
ORDER BY tenant_id, mcc;
