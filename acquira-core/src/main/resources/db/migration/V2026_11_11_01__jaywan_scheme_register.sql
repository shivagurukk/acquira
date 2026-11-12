-- ============================================================================
-- V2026_11_11_01: register the JAYWAN card scheme (corrects V2026_10_26_01).
--
-- WHY
-- ---
-- V2026_10_26_01 inserted the Jaywan scheme reference with a hardcoded
-- ref_card_scheme.id = 19 and ON CONFLICT DO NOTHING. id 19 was already taken
-- by MEEZA (V2026_08_10_02), so the insert was silently skipped on every
-- database that ran the migrations in order. Its rate rows DID land
-- (interchange_rate_local / scheme_fee_rate, scheme_group 'Jaywan'), but with
-- no scheme reference the fee engine resolves group_name NULL for a Jaywan
-- transaction and prices it on the scheme wildcard: AE local-debit 0.75%
-- interchange instead of the confirmed 0.50%, status RESOLVED_SCHEME_WILDCARD.
--
-- WHAT
-- ----
-- Insert the scheme row at the next free id instead of a fixed one. Values are
-- the ones V2026_10_26_01 intended (code JAYWAN, group 'Jaywan', debit).
-- An applied migration is never edited, so the correction lives here.
--
-- Idempotent (NOT EXISTS on code / group name). Splitter-safe (no dollar
-- quoting). Apply once via psql, then rebuild-summaries with reprice:true for
-- the AE tenant from the first month that carries Jaywan volume, and restart
-- (or let the ref-table cache expire on) the batch pod so ingest picks up the
-- new scheme code.
-- ============================================================================

INSERT INTO ref_card_scheme (id, is_active, code, name, group_code, group_name, status, card_type, card_subtype)
SELECT (SELECT COALESCE(MAX(id), 0) + 1 FROM ref_card_scheme),
       true, 'JAYWAN', 'Jaywan', 'JYWN', 'Jaywan', 1, 2, 0
WHERE NOT EXISTS (SELECT 1 FROM ref_card_scheme
                  WHERE UPPER(code) = 'JAYWAN' OR group_name = 'Jaywan');

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_11_11_01__jaywan_scheme_register.sql')
ON CONFLICT (filename) DO NOTHING;

-- CHECK: exactly one Jaywan scheme row, and the rate rows it unlocks.
SELECT 'ref_card_scheme' AS tbl, id::TEXT AS key1, code AS key2, group_name AS key3, CAST(NULL AS NUMERIC) AS rate
FROM ref_card_scheme WHERE code = 'JAYWAN'
UNION ALL
SELECT 'interchange_rate_local', dest, COALESCE(channel, '*'), scheme_group, interchange_pct
FROM interchange_rate_local WHERE country_code = 'AE' AND scheme_group = 'Jaywan'
UNION ALL
SELECT 'scheme_fee_rate', dest, channel, scheme_group, fee_pct
FROM scheme_fee_rate WHERE country_code = 'AE' AND scheme_group = 'Jaywan' AND tenant_id IS NULL
ORDER BY 1, 2, 3;
