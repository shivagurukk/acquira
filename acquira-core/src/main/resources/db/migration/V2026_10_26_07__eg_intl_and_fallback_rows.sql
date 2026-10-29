-- ============================================================================
-- V2026_10_26_07: Egypt INTERNATIONAL + any-scheme fallback interchange rows,
--                 same figures as UAE (business instruction 2026-10-26).
--
-- WHY: after V2026_10_26_01..06 a full EG reprice still left 280,753 rows
-- NO_RATE_FOUND (RESOLVED 4,089,343). The V2026_07_31_05 EG seed that
-- carried the INTERNATIONAL 1.85% row and the any-scheme / JCB / UnionPay
-- domestic fallbacks evidently never landed on UAT (same symptom as Meeza),
-- and the Visa / Mastercard reseeds are domestic-only by design.
--
-- Rows (country-level, APPROVED, BUSINESS-APPROVED note so the V2026_08_10_01
-- placeholder sweep never demotes them):
--   priority 1   INTERNATIONAL, any scheme / channel / card      1.85%
--   priority 10  DOMESTIC POS  any scheme (Amex, unknown tokens)  1.75%
--   priority 10  DOMESTIC ECOM any scheme                         1.90%
--   priority 65  DOMESTIC JCB      all channels                   1.75%
--   priority 65  DOMESTIC UnionPay all channels                   1.75%
-- Visa / Mastercard / Meeza domestic rows (priority 15+) still win over the
-- priority-10 wildcard. Idempotent (NOT EXISTS per row), splitter-safe.
-- Re-price EG months after applying.
-- ============================================================================
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', v.priority, v.dest, v.channel, v.scheme_group,
       NULL, NULL, NULL, NULL, NULL, NULL,
       v.pct, 0.0000, NULL, 'APPROVED', NULL, v.label,
       'BUSINESS-APPROVED 2026-10-26: EG ' || v.what || ' same as UAE (re-asserted, UAT had no row).'
FROM (VALUES
  (1,  'INTERNATIONAL', NULL::VARCHAR, NULL::VARCHAR, 0.018500, 'EG intl flat 1.85 (same as UAE)',               'international interchange 1.85%'),
  (10, 'DOMESTIC',      'POS',         NULL::VARCHAR, 0.017500, 'EG any-scheme POS domestic default 1.75',       'any-scheme domestic POS fallback'),
  (10, 'DOMESTIC',      'ECOM',        NULL::VARCHAR, 0.019000, 'EG any-scheme ECOM domestic default 1.90',      'any-scheme domestic ECOM fallback'),
  (65, 'DOMESTIC',      NULL::VARCHAR, 'JCB',         0.017500, 'EG JCB domestic flat 1.75 (same as UAE)',      'JCB domestic 1.75%'),
  (65, 'DOMESTIC',      NULL::VARCHAR, 'UnionPay',    0.017500, 'EG UnionPay domestic flat 1.75 (same as UAE)', 'UnionPay domestic 1.75%')
) AS v(priority, dest, channel, scheme_group, pct, label, what)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.dest = v.dest
                    AND COALESCE(x.channel, '*') = COALESCE(v.channel, '*')
                    AND COALESCE(x.scheme_group, '*') = COALESCE(v.scheme_group, '*')
                    AND x.card_type IS NULL AND x.tier IS NULL AND x.mcc IS NULL AND x.mcc_sector IS NULL);

-- If an older PLACEHOLDER copy of the international row exists, approve it.
UPDATE interchange_rate_local
   SET rate_status = 'APPROVED',
       source_note = 'BUSINESS-APPROVED 2026-10-26: EG international interchange 1.85% same as UAE.'
 WHERE country_code = 'EG' AND tenant_id IS NULL AND dest = 'INTERNATIONAL'
   AND scheme_group IS NULL AND rate_status <> 'APPROVED';

ANALYZE interchange_rate_local;

-- Verify: expect the 5 rows below, all APPROVED.
SELECT priority, dest, channel, scheme_group, interchange_pct, rate_status
FROM interchange_rate_local
WHERE country_code = 'EG' AND tenant_id IS NULL
  AND ((dest = 'INTERNATIONAL' AND scheme_group IS NULL)
    OR (dest = 'DOMESTIC' AND priority IN (10, 65) AND mcc IS NULL))
ORDER BY priority, dest, channel, scheme_group;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_07__eg_intl_and_fallback_rows.sql')
ON CONFLICT (filename) DO NOTHING;
