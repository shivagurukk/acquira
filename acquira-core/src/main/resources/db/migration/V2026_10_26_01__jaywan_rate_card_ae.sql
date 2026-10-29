-- ============================================================================
-- V2026_10_26_01: JAYWAN (UAE domestic card scheme, co-badged with Mastercard)
--                 — scheme reference + UAE rate card rows.
-- UAE only (country_code 'AE').
--
-- WHY
-- ---
-- Jaywan had no ref_card_scheme row, so the fee engine resolved scheme NULL for
-- every Jaywan transaction: a DEBIT-typed row priced on the AE local-debit
-- base (0.75% cap 37.5), an unrecognised-type row priced NOTHING
-- (NO_RATE_FOUND). Both wrong.
--
-- BUSINESS RULES (user-confirmed 2026-10-26):
--   * Interchange : 0.50% of settlement amount, DOMESTIC, any channel,
--                   any MCC, no cap ("for now" — a single interim figure).
--   * Scheme fee  : same as Visa / Mastercard local debit, i.e. the AE
--                   DOMESTIC grid (0.12% POS / 0.14% ECOM). Seeded as explicit
--                   Jaywan rows copied from the Visa DOMESTIC any-card-type
--                   rows so the scheme never depends on the wildcard and shows
--                   its own label in rate-resolution reporting.
--
-- PRIORITY: the AE card has scheme-agnostic DEBIT/PREPAID MCC-override rows at
-- priority 50 (V2026_07_07_01) and sector rows at 20. A Jaywan row must beat
-- all of them, so it sits at 66 — same slot the BENEFIT base row uses for BH.
-- DOMESTIC only: a co-badged card used abroad routes via Mastercard and
-- arrives with the Mastercard token, so it keeps the intl rows.
-- card_type / tier wildcard: Jaywan is a debit product (ref_card_scheme
-- card_type=2 so ingest coarsens the type to DEBIT), the rate is flat either way.
--
-- Idempotent: ref_card_scheme via ON CONFLICT DO NOTHING; rate rows guarded by
-- NOT EXISTS so in-UI/psql edits are never clobbered. Splitter-safe (no $$).
-- Apply once via psql, then rebuild-summaries with reprice:true for the AE
-- tenant from the first month that carries Jaywan volume.
-- ============================================================================

-- 1. Jaywan scheme reference. The fee LATERAL matches code OR name
--    space/case-insensitively, so feed tokens 'JAYWAN' / 'Jaywan' resolve;
--    the ingest card-type map is keyed by code, so a 'JAYWAN' card-type token
--    coarsens to DEBIT.
INSERT INTO ref_card_scheme (id, is_active, code, name, group_code, group_name, status, card_type, card_subtype)
VALUES (19, true, 'JAYWAN', 'Jaywan', 'JYWN', 'Jaywan', 1, 2, 0)
ON CONFLICT DO NOTHING;

-- 2. Interchange: 0.50% DOMESTIC, country-level (tenant_id NULL).
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     rate_status, label, source_note)
SELECT NULL::INT, 'AE', 'AED', 66, 'DOMESTIC', NULL, 'Jaywan',
       NULL, NULL, NULL, NULL, NULL::NUMERIC, NULL::NUMERIC, 0.005000, NULL::NUMERIC,
       'APPROVED', 'Jaywan domestic 0.50%',
       'Jaywan interim rate 0.50% (business-confirmed 2026-10-26)'
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'AE' AND x.scheme_group = 'Jaywan');

-- 3. Scheme fee: copy of the Visa DOMESTIC any-card-type rows currently in
--    force (country default AND any tenant override), relabelled Jaywan.
INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, card_type, fee_pct, flat_fee,
     rate_status, effective_from, source_note)
SELECT s.country_code, s.tenant_id, s.dest, s.channel, 'Jaywan', NULL, s.fee_pct, s.flat_fee,
       s.rate_status, s.effective_from,
       'Jaywan = Visa/MC local debit scheme fee (business-confirmed 2026-10-26)'
FROM scheme_fee_rate s
WHERE s.country_code = 'AE'
  AND s.dest = 'DOMESTIC'
  AND s.scheme_group = 'Visa'
  AND s.card_type IS NULL
  AND s.effective_to IS NULL
  AND NOT EXISTS (SELECT 1 FROM scheme_fee_rate x
                  WHERE x.country_code = 'AE' AND x.scheme_group = 'Jaywan'
                    AND x.dest = s.dest AND x.channel = s.channel
                    AND COALESCE(x.tenant_id, 0) = COALESCE(s.tenant_id, 0));

INSERT INTO schema_migration_log (filename) VALUES ('V2026_10_26_01__jaywan_rate_card_ae.sql') ON CONFLICT (filename) DO NOTHING;

-- Verify:
-- SELECT id, code, name, group_name, card_type FROM ref_card_scheme WHERE code = 'JAYWAN';
-- SELECT priority, dest, scheme_group, interchange_pct, rate_status FROM interchange_rate_local
--   WHERE country_code = 'AE' AND scheme_group = 'Jaywan';
-- SELECT tenant_id, dest, channel, fee_pct, effective_from FROM scheme_fee_rate
--   WHERE country_code = 'AE' AND scheme_group = 'Jaywan' ORDER BY tenant_id, channel;
