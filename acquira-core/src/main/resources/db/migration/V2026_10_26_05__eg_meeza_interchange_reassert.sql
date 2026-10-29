-- ============================================================================
-- V2026_10_26_05: re-assert Egypt Meeza interchange at a flat 0.50%.
--
-- WHY: UAT has NO Meeza rows in interchange_rate_local although
-- V2026_08_10_02 (interim seed) and V2026_08_12_01 (0.50% update + INTL row)
-- are on the startup list. V2026_08_12_01 only UPDATEs the domestic row the
-- earlier seed should have created, so if that seed never landed the update
-- is a no-op and Meeza prices at the EG any-scheme fallback.
--
-- This file inserts BOTH rows directly (DOMESTIC + INTERNATIONAL, all
-- channels, all card types, priority 66 = above every Visa / Mastercard row),
-- each guarded by NOT EXISTS, and makes sure the MEEZA scheme token resolves.
-- Rate per the 2026-08-12 business instruction (MEEZA-FLAT-0.5).
-- Idempotent, splitter-safe. Re-price the affected EG months after applying.
-- ============================================================================

-- Scheme token (global table, same values as V2026_08_10_02). Guarded on the
-- code rather than the id so it also converges if id 19 is already taken.
INSERT INTO ref_card_scheme (id, is_active, code, name, group_code, group_name, status, card_type, card_subtype)
SELECT 19, true, 'MEEZA', 'Meeza', 'MEZA', 'Meeza', 1, 2, 0
WHERE NOT EXISTS (SELECT 1 FROM ref_card_scheme WHERE UPPER(code) = 'MEEZA' OR group_name = 'Meeza');

INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT NULL, 'EG', 'EGP', 66, d.dest, NULL, 'Meeza',
       NULL, NULL, NULL, NULL, NULL, NULL,
       0.005000, 0.0000, NULL, 'APPROVED', NULL,
       'Meeza 0.50% (all channels, all card types)',
       'MEEZA-FLAT-0.5 business instruction 2026-08-12, re-asserted 2026-10-26 (UAT had no Meeza rows).'
FROM (VALUES ('DOMESTIC'), ('INTERNATIONAL')) AS d(dest)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL
                    AND x.scheme_group = 'Meeza' AND x.dest = d.dest);

ANALYZE interchange_rate_local;

-- Verify: expect DOMESTIC and INTERNATIONAL at 0.005000, APPROVED.
SELECT dest, priority, interchange_pct, rate_status FROM interchange_rate_local
WHERE country_code = 'EG' AND tenant_id IS NULL AND scheme_group = 'Meeza' ORDER BY dest;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_05__eg_meeza_interchange_reassert.sql')
ON CONFLICT (filename) DO NOTHING;
