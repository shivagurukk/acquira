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
