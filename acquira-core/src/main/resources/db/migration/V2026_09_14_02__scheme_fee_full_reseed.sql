-- ============================================================================
-- V2026_09_14_02: full re-seed of scheme_fee_rate for BOTH tenants (AE + BH),
-- adding Benefit / Benefit QR INTERNATIONAL rows at 0.06%.
--
-- WHY: BH had Benefit rows for DOMESTIC only. FeeComputationService's scheme
-- fee lateral join prefers a scheme-specific row, else falls to the
-- scheme_group IS NULL wildcard — so a Benefit transaction flagged
-- INTERNATIONAL silently priced at 0.75% POS / 0.90% ECOM. Business wants
-- Benefit INTERNATIONAL at the same 0.06% as domestic.
--
-- Grid (unchanged, per business approval 2026-08-11 — BH adopts the UAE grid):
--   Visa / MasterCard / Amex / wildcard: DOM POS 0.11%, DOM ECOM 0.14%,
--                                        INTL POS 0.75%, INTL ECOM 0.90%
--   JCB / UnionPay: 0.05% everywhere
--   Benefit / Benefit QR (BH only):      0.06% everywhere (INTL rows are NEW)
--
-- Seeded as: AE country-level + AE tenant-1 override (matching the existing
-- shape), BH country-level only.
--
-- GUARDED one-time flip (migration ledger convention): the DELETE + INSERTs
-- run only while this file's schema_migration_log row is absent, so a re-run
-- never wipes later manual rate edits. Splitter-safe (no $$).
-- On prod/UAT apply once via psql, then rebuild-summaries with reprice:true
-- for any period holding Benefit INTERNATIONAL transactions.
-- ============================================================================

DELETE FROM scheme_fee_rate
WHERE country_code IN ('AE', 'BH')
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_09_14_02__scheme_fee_full_reseed.sql');

-- --------------------------------------------------------------------------
-- Shared card-scheme grid: 24 combinations x (AE country, AE tenant 1, BH country)
-- --------------------------------------------------------------------------
INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT tgt.country_code, tgt.tenant_id, g.dest, g.channel, g.scheme_group, g.fee_pct, 0, 'APPROVED',
       CASE WHEN tgt.country_code = 'BH'
            THEN 'BUSINESS-APPROVED 2026-08-11: BH adopts the UAE scheme-fee grid by explicit decision.'
            ELSE NULL END
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
CROSS JOIN (VALUES
    ('AE', CAST(NULL AS INT)),
    ('AE', 1),
    ('BH', CAST(NULL AS INT))
) AS tgt(country_code, tenant_id)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_09_14_02__scheme_fee_full_reseed.sql');

-- --------------------------------------------------------------------------
-- BH Benefit / Benefit QR: 0.06% on ALL dest x channel (INTERNATIONAL is NEW)
-- --------------------------------------------------------------------------
INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT 'BH', NULL, b.dest, b.channel, b.scheme_group, 0.0006, 0, 'APPROVED',
       CASE WHEN b.dest = 'DOMESTIC'
            THEN 'BENEFIT scheme fee 0.06% (business-supplied 2026-08-30), BH domestic.'
            ELSE 'BENEFIT scheme fee 0.06% extended to INTERNATIONAL (2026-09-14), same as domestic.' END
FROM (VALUES
    ('DOMESTIC',      'POS',  'Benefit'),
    ('DOMESTIC',      'POS',  'Benefit QR'),
    ('DOMESTIC',      'ECOM', 'Benefit'),
    ('DOMESTIC',      'ECOM', 'Benefit QR'),
    ('INTERNATIONAL', 'POS',  'Benefit'),
    ('INTERNATIONAL', 'POS',  'Benefit QR'),
    ('INTERNATIONAL', 'ECOM', 'Benefit'),
    ('INTERNATIONAL', 'ECOM', 'Benefit QR')
) AS b(dest, channel, scheme_group)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_09_14_02__scheme_fee_full_reseed.sql');

-- Verify: expect AE-null 24, AE-1 24, BH-null 32 (24 grid + 8 Benefit)
SELECT country_code, COALESCE(CAST(tenant_id AS VARCHAR), 'country') AS scope, COUNT(*)
FROM scheme_fee_rate WHERE country_code IN ('AE', 'BH')
GROUP BY country_code, tenant_id ORDER BY country_code, tenant_id NULLS FIRST;

SELECT dest, channel, scheme_group, fee_pct FROM scheme_fee_rate
WHERE country_code = 'BH' AND scheme_group LIKE 'Benefit%'
ORDER BY dest, channel, scheme_group;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_14_02__scheme_fee_full_reseed.sql') ON CONFLICT (filename) DO NOTHING;
