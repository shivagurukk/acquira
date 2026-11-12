-- ============================================================================
-- V2026_10_05_01: Mastercard UAE changes effective 1 October 2026 (part 1).
-- UAE only (country_code 'AE': country rows + tenant-1 overrides).
--
-- A. INTERCHANGE - MEA 12465.2 "Recategorizing Acceptor Business Codes in the
--    United Arab Emirates" (processing date 1 Oct 2026): MCCs 5193, 5811,
--    5942, 5992, 7210, 7211, 7216, 7217, 7221 leave the preferential segment
--    and become general MCCs. The 'MCC xxxx MC flat 1.00' rows (Mastercard
--    DOMESTIC CREDIT 1.00%) are end-dated 30 Sep 2026; from 1 Oct these
--    transactions fall through to the general Mastercard credit rows
--    (Std 1.25 / Prem 1.80 / Elite 2.125). Nothing is inserted.
--
-- B. SCHEME FEE - MEA 13665.1 "Acquiring Growth Enablement Fund" (effective
--    1 Oct 2026): +0.03% on ALL Mastercard INTERNATIONAL purchase volume
--    (credit, debit, prepaid). The current rows are end-dated 30 Sep 2026 and
--    successors at fee_pct + 0.0003 start 1 Oct 2026. The unique key gains
--    effective_from so a dated successor can coexist with its predecessor.
--
-- The +0.03% on Mastercard DOMESTIC CREDIT is in V2026_10_05_02: it needs
-- the batch build that reads scheme_fee_rate.card_type.
--
-- ORDER: this file -> deploy that batch build -> V2026_10_05_02. This file is
-- safe on the current build and MUST precede the new one (it adds card_type).
--
-- Every statement is idempotent (re-running changes nothing). Splitter-safe
-- (no $$). Apply once via psql, then rebuild-summaries with reprice:true for
-- AE from October 2026 if October data is already loaded.
-- ============================================================================

-- A. Interchange: end the preferential 1.00% for the nine recategorised MCCs.
UPDATE interchange_rate_local
SET effective_to = DATE '2026-09-30',
    source_note  = 'Ended 2026-09-30: MC MEA 12465.2 recategorised this MCC as general from 2026-10-01'
WHERE country_code = 'AE'
  AND (tenant_id IS NULL OR tenant_id = 1)
  AND dest = 'DOMESTIC'
  AND scheme_group = 'MasterCard'
  AND card_type = 'CREDIT'
  AND mcc IN ('5193','5811','5942','5992','7210','7211','7216','7217','7221')
  AND effective_to IS NULL;

-- B1. Scheme fee: let a dated successor share the key of the row it replaces.
--     card_type (NULL = any card type) is added here, unused until
--     V2026_10_05_02, because the batch build that reads it must find the
--     column already present.
ALTER TABLE scheme_fee_rate ADD COLUMN IF NOT EXISTS card_type VARCHAR(20) NULL;

DROP INDEX IF EXISTS uq_scheme_fee_rate_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_scheme_fee_rate_key
    ON scheme_fee_rate (country_code, COALESCE(tenant_id, 0), dest, channel,
                        COALESCE(scheme_group, ''), COALESCE(card_type, ''),
                        COALESCE(effective_from, DATE '1900-01-01'));

-- B2. Successor rows from 1 Oct 2026 at the current rate + 0.03%.
INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, effective_from, source_note)
SELECT s.country_code, s.tenant_id, s.dest, s.channel, s.scheme_group, s.fee_pct + 0.000300, s.flat_fee,
       s.rate_status, DATE '2026-10-01',
       'MC MEA 13665.1 Acquiring Growth Enablement Fund: +0.03% on Mastercard international from 2026-10-01'
FROM scheme_fee_rate s
WHERE s.country_code = 'AE'
  AND (s.tenant_id IS NULL OR s.tenant_id = 1)
  AND s.dest = 'INTERNATIONAL'
  AND s.scheme_group = 'MasterCard'
  AND s.effective_from IS NULL
  AND s.effective_to IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM scheme_fee_rate x
      WHERE x.country_code = s.country_code
        AND COALESCE(x.tenant_id, 0) = COALESCE(s.tenant_id, 0)
        AND x.dest = s.dest AND x.channel = s.channel
        AND x.scheme_group = s.scheme_group
        AND x.effective_from = DATE '2026-10-01');

-- B3. End the predecessors the day before.
UPDATE scheme_fee_rate
SET effective_to = DATE '2026-09-30'
WHERE country_code = 'AE'
  AND (tenant_id IS NULL OR tenant_id = 1)
  AND dest = 'INTERNATIONAL'
  AND scheme_group = 'MasterCard'
  AND effective_from IS NULL
  AND effective_to IS NULL;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_05_01__mc_uae_oct2026_recat_and_intl_scheme_fee.sql')
ON CONFLICT (filename) DO NOTHING;

-- CHECK: expect 18 interchange rows ended 2026-09-30, and for each
-- Mastercard INTERNATIONAL channel an old row (to 2026-09-30) plus a new one
-- (from 2026-10-01, +0.0003) per tenant scope.
SELECT 'interchange' AS tbl, tenant_id, mcc AS key1, CAST(NULL AS VARCHAR) AS channel,
       interchange_pct AS rate, effective_from, effective_to
FROM interchange_rate_local
WHERE country_code = 'AE' AND scheme_group = 'MasterCard' AND card_type = 'CREDIT'
  AND mcc IN ('5193','5811','5942','5992','7210','7211','7216','7217','7221')
UNION ALL
SELECT 'scheme_fee', tenant_id, dest, channel, fee_pct, effective_from, effective_to
FROM scheme_fee_rate
WHERE country_code = 'AE' AND scheme_group = 'MasterCard' AND dest = 'INTERNATIONAL'
ORDER BY 1, 2, 3, 4, 6;
