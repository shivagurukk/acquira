-- ============================================================================
-- V2026_10_27_02: Egypt Meeza scheme fee = 0.06% (6 bps) on every destination
--                 x channel (business instruction 2026-10-27). Replaces the
--                 0.05% from V2026_10_26_06. Meeza INTERCHANGE stays 0.50%.
--
-- UPDATE existing Meeza rows, then INSERT any of the 4 combinations still
-- missing so the file converges whether or not V2026_10_26_06 was applied.
-- Idempotent, splitter-safe. Re-price EG months after applying.
-- ============================================================================
UPDATE scheme_fee_rate
   SET fee_pct = 0.0006,
       rate_status = 'APPROVED',
       source_note = 'BUSINESS-APPROVED 2026-10-27: Meeza scheme fee 0.06% all destinations and channels.'
 WHERE country_code = 'EG' AND tenant_id IS NULL
   AND scheme_group = 'Meeza'
   AND (fee_pct <> 0.0006 OR rate_status <> 'APPROVED');

INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT 'EG', NULL, g.dest, g.channel, 'Meeza', 0.0006, 0, 'APPROVED',
       'BUSINESS-APPROVED 2026-10-27: Meeza scheme fee 0.06% all destinations and channels.'
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

-- Verify: expect 4 rows at 0.000600, APPROVED.
SELECT dest, channel, fee_pct, rate_status FROM scheme_fee_rate
WHERE country_code = 'EG' AND tenant_id IS NULL AND scheme_group = 'Meeza' ORDER BY dest, channel;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_27_02__eg_meeza_scheme_fee_006.sql')
ON CONFLICT (filename) DO NOTHING;
