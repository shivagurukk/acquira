-- ============================================================================
-- V2026_08_10_02: Meeza (Egypt) + BENEFIT INTERNATIONAL (Bahrain) rate rows.
--
-- Both are business-supplied rates, seeded as data — NOT as Java constants —
-- precisely because both are expected to change: the Meeza figure is an
-- explicit interim rate, and BENEFIT's cross-border economics will be
-- renegotiated. Editing a row (or effective-dating a successor) must never
-- require a code change.
--
-- Depends on V2026_08_10_01 for flat_fee / rate_status / effective_from.
-- Splitter-safe, idempotent (NOT EXISTS guards so operator edits survive).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. MEEZA as a first-class scheme.
--    Meeza is Egypt's national debit scheme and carries a large share of
--    Egyptian domestic debit. With no ref_card_scheme row, a feed token of
--    'MEEZA' resolved group_name NULL, so only scheme-wildcard rows could
--    match and Meeza was priced at the generic Visa/MasterCard-derived
--    1.75%/1.90% — overstating cost on the biggest domestic slice.
--    card_type=2 (Debit) so the ingest processor coarsens card_type to DEBIT;
--    card_subtype=0 because the rate row below is tier-wildcard.
--    NOTE: ref_card_scheme is a GLOBAL table with no country dimension. The
--    country isolation that matters happens on the RATE rows, which are
--    country_code='EG'.
-- ----------------------------------------------------------------------------
INSERT INTO ref_card_scheme (id, is_active, code, name, group_code, group_name, status, card_type, card_subtype)
VALUES (19, true, 'MEEZA', 'Meeza', 'MEZA', 'Meeza', 1, 2, 0)
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2. EGYPT DOMESTIC MEEZA — interim 1.85%.
--
--    IMPORTANT: this 1.85% is a DELIBERATE, BUSINESS-APPROVED INTERIM RATE for
--    Egyptian domestic Meeza. It is NOT the generic 1.85% fallback that
--    V2026_08_10_03 removes from the fee engine — that one was the UAE
--    cross-border constant leaking onto every unmatched transaction. This row
--    is explicit, country-scoped, scheme-scoped, effective-dated and
--    attributable, and a transaction priced by it reports the rule id that did
--    it. When the real Meeza rate arrives, close this row with an effective_to
--    and insert its successor; do not edit history in place.
--
--    Priority 66 puts it above Egypt's any-scheme per-MCC rows (40) and
--    any-scheme defaults (10) so Meeza can never be captured by Visa/MC-derived
--    pricing. channel NULL = applies to both POS and ECOM.
-- ----------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'EG', 'EGP', 66, 'DOMESTIC', NULL::VARCHAR, 'Meeza', NULL::VARCHAR, NULL::VARCHAR, NULL::VARCHAR,
   NULL::VARCHAR, NULL::NUMERIC, NULL::NUMERIC, 0.018500, 0.0000, NULL::NUMERIC, 'APPROVED', DATE '2026-08-01',
   'Meeza domestic 1.85% (interim)',
   'Business-approved INTERIM rate 2026-08-10. Replace with the negotiated Meeza schedule; effective-date the successor.')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
       interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'EG' AND x.scheme_group = 'Meeza');

-- ----------------------------------------------------------------------------
-- 3. BAHRAIN INTERNATIONAL BENEFIT — 1.10% + BHD 0.100 per transaction.
--
--    This is the first rate in the system that is genuinely percentage PLUS
--    flat; it is why interchange_rate_local.flat_fee had to exist. The old
--    "encode a flat fee as pct=100% with cap=<flat>" trick can express a pure
--    flat fee but cannot express a percentage and a flat fee together.
--
--    Worked example (the acceptance case):
--        BHD 100.000 x 1.10%      = BHD 1.100
--        + flat                    = BHD 0.100
--        = BHD 1.200
--
--    SCOPE: BENEFIT only. Bahraini INTERNATIONAL Visa and MasterCard must NOT
--    take this rate — their rows remain the UAE-derived placeholders flagged in
--    V2026_08_10_01, so they resolve to NO_RATE_FOUND until real Bahraini
--    cross-border figures are supplied. That is intentional: an unresolved
--    transaction is recoverable, a silently wrong fee is not.
--
--    Priority 66 matches the domestic BENEFIT rows and sits above the
--    (placeholder) any-scheme international row.
-- ----------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'BH', 'BHD', 66, 'INTERNATIONAL', NULL::VARCHAR, 'Benefit', NULL::VARCHAR, NULL::VARCHAR, NULL::VARCHAR,
   NULL::VARCHAR, NULL::NUMERIC, NULL::NUMERIC, 0.011000, 0.1000, NULL::NUMERIC, 'APPROVED', DATE '2026-08-01',
   'BENEFIT international 1.10% + BHD 0.100',
   'User-supplied 2026-08-10: 1.10% + 100 fils per transaction. BENEFIT ONLY - not Visa/MasterCard.')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
       interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'BH' AND x.scheme_group = 'Benefit' AND x.dest = 'INTERNATIONAL');

-- ----------------------------------------------------------------------------
-- 4. Mark the pre-existing Bahrain/Egypt DOMESTIC per-MCC cards as APPROVED.
--    These came from each country's own business-case workbook (776 Visa and
--    772/776 MasterCard rows with genuinely differentiated per-MCC rates), so
--    unlike the international rows they ARE production pricing. Explicit for
--    the avoidance of doubt, since V2026_08_10_01 defaults the column.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET rate_status = 'APPROVED',
       source_note = COALESCE(source_note, 'Country business-case rate card (V2026_07_31_03/05)')
 WHERE country_code IN ('BH','EG')
   AND dest = 'DOMESTIC'
   AND rate_status IS DISTINCT FROM 'PLACEHOLDER';

-- 5. Re-assert APPROVED on the two rows this migration owns. Belt-and-braces so
--    the pair of migrations converges no matter the order or number of re-runs:
--    V2026_08_10_01's placeholder sweep is now scoped to scheme_group IS NULL,
--    but these rows are business-approved by definition and their status must
--    never depend on which migration ran last.
UPDATE interchange_rate_local
   SET rate_status = 'APPROVED'
 WHERE country_code = 'BH' AND scheme_group = 'Benefit' AND dest = 'INTERNATIONAL'
   AND rate_status <> 'APPROVED';

UPDATE interchange_rate_local
   SET rate_status = 'APPROVED'
 WHERE country_code = 'EG' AND scheme_group = 'Meeza'
   AND rate_status <> 'APPROVED';

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_10_02__meeza_and_benefit_international.sql')
ON CONFLICT (filename) DO NOTHING;
