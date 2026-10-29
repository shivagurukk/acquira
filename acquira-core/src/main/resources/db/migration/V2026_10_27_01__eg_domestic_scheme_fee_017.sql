-- ============================================================================
-- V2026_10_27_01: Egypt DOMESTIC scheme fee (business instruction 2026-10-27):
--                 POS 0.17% (17 bps), ECOM 0.20% (20 bps), for Visa /
--                 MasterCard / Amex and the any-scheme wildcard.
--
-- Replaces the UAE-copied domestic figures from V2026_10_26_04 (0.11% POS /
-- 0.14% ECOM). Unchanged: INTERNATIONAL (0.75 / 0.90), JCB and UnionPay
-- (0.05%). Meeza is set separately (V2026_10_27_02, 0.06%).
--
-- UPDATE existing rows, then INSERT any of the 8 combinations still missing
-- so the file converges whether or not V2026_10_26_04 was applied.
-- Idempotent, splitter-safe. Re-price EG months after applying.
-- ============================================================================
UPDATE scheme_fee_rate s
   SET fee_pct = v.pct,
       rate_status = 'APPROVED',
       source_note = 'BUSINESS-APPROVED 2026-10-27: EG domestic scheme fee POS 0.17% / ECOM 0.20%.'
  FROM (VALUES ('POS', 0.0017), ('ECOM', 0.0020)) AS v(channel, pct)
 WHERE s.country_code = 'EG' AND s.tenant_id IS NULL
   AND s.dest = 'DOMESTIC' AND s.channel = v.channel
   AND (s.scheme_group IS NULL OR s.scheme_group IN ('Visa', 'MasterCard', 'Amex'))
   AND (s.fee_pct <> v.pct OR s.rate_status <> 'APPROVED');

INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT 'EG', NULL, 'DOMESTIC', c.channel, g.scheme_group, c.pct, 0, 'APPROVED',
       'BUSINESS-APPROVED 2026-10-27: EG domestic scheme fee POS 0.17% / ECOM 0.20%.'
FROM (VALUES ('POS', 0.0017), ('ECOM', 0.0020)) AS c(channel, pct)
CROSS JOIN (VALUES (CAST(NULL AS VARCHAR)), ('Visa'), ('MasterCard'), ('Amex')) AS g(scheme_group)
WHERE NOT EXISTS (SELECT 1 FROM scheme_fee_rate x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.dest = 'DOMESTIC' AND x.channel = c.channel
                    AND COALESCE(x.scheme_group, '*') = COALESCE(g.scheme_group, '*'));

ANALYZE scheme_fee_rate;

-- Verify: expect POS rows at 0.001700 and ECOM rows at 0.002000 for
-- Visa / MasterCard / Amex / <any>, JCB and UnionPay untouched at 0.000500.
SELECT channel, COALESCE(scheme_group, '<any>') AS scheme_group, fee_pct, rate_status
FROM scheme_fee_rate
WHERE country_code = 'EG' AND tenant_id IS NULL AND dest = 'DOMESTIC'
ORDER BY channel, scheme_group NULLS FIRST;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_27_01__eg_domestic_scheme_fee_017.sql')
ON CONFLICT (filename) DO NOTHING;
