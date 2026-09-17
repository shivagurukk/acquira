-- ============================================================================
-- BH INTERNATIONAL fee update — interchange + scheme fee
-- Date: 2026-09-01
--
-- EDIT THE RATE VALUES MARKED WITH  <<< EDIT  BEFORE RUNNING.
-- Percentages are stored as fractions: 1.85% = 0.018500, 0.06% = 0.000600.
-- flat_fee / cap_amount are in BHD for country_code='BH'.
--
-- Run inside a transaction so a typo can't leave the card half-updated:
--   psql -h <host> -p <port> -U <user> -d <db> -f BH_INTL_FEE_UPDATE.sql
--
-- AFTER APPLYING: existing fact rows keep their old fees until repriced.
-- Trigger reprice-then-rebuild for tenant 8 (rebuild-summaries with
-- reprice:true) so history is re-priced from the updated card.
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1) INTERCHANGE — scheme-specific INTERNATIONAL rows (interchange_rate_local)
--
-- Today Visa/MC/JCB/UnionPay all hit the generic wildcard id 5001 (1.85%).
-- These INSERTs add scheme-specific rows at priority 66 (same priority the
-- Benefit intl rows use), which outrank the priority-1 wildcard.
-- The wildcard is left in place as the catch-all for anything unlisted.
-- ----------------------------------------------------------------------------

INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     interchange_pct, cap_amount, label, mcc, country_code, cap_currency_code,
     flat_fee, rate_status, effective_from, source_note)
VALUES
    -- Visa international                                v-- <<< EDIT pct
    (NULL, 66, 'INTERNATIONAL', NULL, 'Visa',       NULL, NULL, NULL,
     0.018500, NULL, 'VISA international', NULL, 'BH', 'BHD',
     0.0000, 'APPROVED', '2026-08-01',
     'BH intl Visa — business-supplied 2026-09-01'),

    -- MasterCard international                          v-- <<< EDIT pct
    (NULL, 66, 'INTERNATIONAL', NULL, 'MasterCard', NULL, NULL, NULL,
     0.018500, NULL, 'MC international', NULL, 'BH', 'BHD',
     0.0000, 'APPROVED', '2026-08-01',
     'BH intl MasterCard — business-supplied 2026-09-01'),

    -- JCB international                                 v-- <<< EDIT pct
    (NULL, 66, 'INTERNATIONAL', NULL, 'JCB',        NULL, NULL, NULL,
     0.018500, NULL, 'JCB international', NULL, 'BH', 'BHD',
     0.0000, 'APPROVED', '2026-08-01',
     'BH intl JCB — business-supplied 2026-09-01'),

    -- UnionPay international                            v-- <<< EDIT pct
    (NULL, 66, 'INTERNATIONAL', NULL, 'UnionPay',   NULL, NULL, NULL,
     0.018500, NULL, 'UnionPay international', NULL, 'BH', 'BHD',
     0.0000, 'APPROVED', '2026-08-01',
     'BH intl UnionPay — business-supplied 2026-09-01');

-- If instead you want to CHANGE the existing Benefit intl interchange
-- (currently 1.00% + BHD 0.100 on id 17727 / QR 1.00% on id 37553), uncomment:
-- UPDATE interchange_rate_local
--    SET interchange_pct = 0.010000,          -- <<< EDIT
--        flat_fee        = 0.1000,            -- <<< EDIT
--        source_note     = 'BH intl Benefit — business-supplied 2026-09-01'
--  WHERE id = 17727;

-- ----------------------------------------------------------------------------
-- 2) SCHEME FEE — update existing INTERNATIONAL Visa/MC rows (scheme_fee_rate)
--    Current: Visa/MC 0.75% POS / 0.90% ECOM. Edit only if these are wrong.
-- ----------------------------------------------------------------------------

UPDATE scheme_fee_rate SET fee_pct = 0.007500,   -- <<< EDIT (POS)
       source_note = 'BH intl scheme fee — business-supplied 2026-09-01'
 WHERE country_code='BH' AND dest='INTERNATIONAL' AND channel='POS'
   AND scheme_group IN ('Visa','MasterCard');

UPDATE scheme_fee_rate SET fee_pct = 0.009000,   -- <<< EDIT (ECOM)
       source_note = 'BH intl scheme fee — business-supplied 2026-09-01'
 WHERE country_code='BH' AND dest='INTERNATIONAL' AND channel='ECOM'
   AND scheme_group IN ('Visa','MasterCard');

-- ----------------------------------------------------------------------------
-- 3) SCHEME FEE — NEW Benefit international rows (currently missing; Benefit
--    silently inherits the 0.75/0.90 wildcard). Mirrors the domestic 0.06%
--    pattern — change the pct if AFS supplies a different intl figure.
-- ----------------------------------------------------------------------------

INSERT INTO scheme_fee_rate
    (tenant_id, dest, channel, fee_pct, scheme_group, country_code,
     flat_fee, rate_status, effective_from, source_note)
VALUES
    --                                  v-- <<< EDIT pct
    (NULL, 'INTERNATIONAL', 'POS',  0.000600, 'Benefit',    'BH', 0, 'APPROVED', '2026-08-01',
     'BENEFIT intl scheme fee — business-supplied 2026-09-01'),
    (NULL, 'INTERNATIONAL', 'ECOM', 0.000600, 'Benefit',    'BH', 0, 'APPROVED', '2026-08-01',
     'BENEFIT intl scheme fee — business-supplied 2026-09-01'),
    (NULL, 'INTERNATIONAL', 'POS',  0.000600, 'Benefit QR', 'BH', 0, 'APPROVED', '2026-08-01',
     'BENEFIT QR intl scheme fee — business-supplied 2026-09-01'),
    (NULL, 'INTERNATIONAL', 'ECOM', 0.000600, 'Benefit QR', 'BH', 0, 'APPROVED', '2026-08-01',
     'BENEFIT QR intl scheme fee — business-supplied 2026-09-01');

-- ----------------------------------------------------------------------------
-- 4) Verify before committing
-- ----------------------------------------------------------------------------

SELECT id, dest, scheme_group, channel, interchange_pct, flat_fee, priority, label
  FROM interchange_rate_local
 WHERE country_code='BH' AND dest='INTERNATIONAL'
 ORDER BY priority DESC, scheme_group;

SELECT id, dest, channel, scheme_group, fee_pct, flat_fee
  FROM scheme_fee_rate
 WHERE country_code='BH' AND dest='INTERNATIONAL'
 ORDER BY scheme_group NULLS FIRST, channel;

-- If the two result sets look right:
COMMIT;
-- If not:  ROLLBACK;
