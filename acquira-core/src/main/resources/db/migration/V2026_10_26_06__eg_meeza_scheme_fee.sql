-- ============================================================================
-- V2026_10_26_06: Egypt Meeza scheme fee = 0.05% on every destination x
--                 channel (business instruction 2026-10-26).
--
-- Until now Meeza had no scheme-specific row and took the EG any-scheme
-- wildcard (0.11% POS / 0.14% ECOM domestic, 0.75 / 0.90 international).
-- A scheme-specific row beats the wildcard in the fee engine's scheme
-- LATERAL, so these four rows now govern. Meeza INTERCHANGE stays 0.50%
-- (V2026_10_26_05).
--
-- Idempotent (NOT EXISTS per row), splitter-safe. Re-price EG months after.
-- ============================================================================
INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT 'EG', NULL, g.dest, g.channel, 'Meeza', 0.0005, 0, 'APPROVED',
       'BUSINESS-APPROVED 2026-10-26: Meeza scheme fee 0.05% all destinations and channels.'
FROM (VALUES
    ('DOMESTIC',      'POS'),
    ('DOMESTIC',      'ECOM'),
    ('INTERNATIONAL', 'POS'),
    ('INTERNATIONAL', 'ECOM')
) AS g(dest, channel)
WHERE NOT EXISTS (SELECT 1 FROM scheme_fee_rate x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.scheme_group = 'Meeza' AND x.dest = g.dest AND x.channel = g.channel);

ANALYZE scheme_fee_rate;

-- Verify: expect 4 rows at 0.0005, APPROVED.
SELECT dest, channel, fee_pct, rate_status FROM scheme_fee_rate
WHERE country_code = 'EG' AND tenant_id IS NULL AND scheme_group = 'Meeza' ORDER BY dest, channel;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_06__eg_meeza_scheme_fee.sql')
ON CONFLICT (filename) DO NOTHING;
