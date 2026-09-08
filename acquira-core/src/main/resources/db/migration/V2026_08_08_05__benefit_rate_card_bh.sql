-- ============================================================================
-- V2026_08_08_05: BENEFIT (Bahrain domestic debit switch) — scheme + rate card.
--
-- WHY
-- ---
-- BENEFIT carries the bulk of Bahrain domestic debit POS volume, but had no
-- ref_card_scheme row and no rate rows, so every BENEFIT transaction resolved
-- scheme NULL and fell to the BH any-scheme fallback at 1.75% (the higher of
-- Visa/MC CREDIT) — a massive overstatement of cost.
--
-- BUSINESS RULES (user-confirmed 2026-08-08):
--   * Purchase (default)          : 0.60%
--   * Government MCCs             : ticket < 20 BHD  -> flat BHD 0.100
--                                   ticket >= 20 BHD -> flat BHD 0.200
--   * Exchange houses             : 0.60% capped at BHD 0.029
--   * Petrol / fuel MCCs          : 0.60% capped at BHD 0.085
--   * Charity MCCs                : 0 (zero interchange)
--
-- HOW FLAT FEES ARE ENCODED
-- -------------------------
-- interchange_rate_local has no flat-fee column; a flat fee is expressed as
-- interchange_pct = 100% with cap_amount = the flat value:
--   LEAST(1.0 * amount, 0.100) = BHD 0.100 for any ticket >= BHD 0.10.
-- (For a ticket below the flat value the fee equals the ticket — the fee can
-- never exceed the transaction, which is the safe direction.)
-- The 20-BHD threshold uses min_ticket / max_ticket — the columns are
-- misnamed (AED legacy) but compared raw against the SETTLEMENT amount, which
-- for a BH tenant is BHD. cap_currency_code = 'BHD' on every row.
--
-- MCC LISTS (FLAGGED ASSUMPTION — edit rows if the BENEFIT bulletin differs):
--   Govt     : 9211, 9222, 9223, 9311, 9399, 9402
--   Exchange : 6051, 4829             (money exchange / wire transfer)
--   Petrol   : 5541, 5542             (service stations / automated fuel)
--   Charity  : 8398, 8661             (charitable orgs / religious orgs)
--
-- PRIORITIES: scheme-specific BENEFIT rows must beat the BH any-scheme rows
-- (priority 40 per-MCC / 10 default): base 0.60% at 66, MCC overrides at 70.
-- DOMESTIC only — an international BENEFIT-routed txn keeps the intl flat row.
-- card_type/tier wildcard (BENEFIT is debit; ref_card_scheme maps it DEBIT).
--
-- SCHEME FEE: intentionally NOT seeded — BENEFIT switch fee value not yet
-- provided; BENEFIT domestic txns take the BH wildcard grid (0.11% POS /
-- 0.14% ECOM) until a real figure is configured.
--
-- Idempotent: ref_card_scheme via ON CONFLICT DO NOTHING; rate rows guarded by
-- NOT EXISTS (no BH BENEFIT rows yet) so in-UI/psql edits are never clobbered.
-- Splitter-safe (no dollar-quoting).
-- ============================================================================

-- 0. cap_amount was DECIMAL(19,2) (V2026_07_05_01) — enough for AED caps like
--    137.625? No: even those silently rounded (137.63). For BHD it is fatal:
--    the BENEFIT caps 0.029 / 0.085 would round to 0.03 / 0.09 and every
--    capped fee would compute wrong (verified against local PG before this
--    fix). Widen to 4dp BEFORE seeding. Idempotent (re-ALTER to same type is
--    a no-op); metadata-only, no table rewrite for a widening.
ALTER TABLE interchange_rate_local ALTER COLUMN cap_amount TYPE DECIMAL(19,4);
ALTER TABLE interchange_rate_local ALTER COLUMN min_ticket TYPE DECIMAL(19,4);
ALTER TABLE interchange_rate_local ALTER COLUMN max_ticket TYPE DECIMAL(19,4);

-- 1. BENEFIT scheme reference. card_type=2 (Debit) so the ingest processor
--    coarsens card_type to DEBIT; card_subtype=0 (tier is moot — all BENEFIT
--    rate rows below are tier-wildcard). The fee LATERAL matches code OR name
--    space/case-insensitively, so feed tokens 'BENEFIT' / 'Benefit' resolve.
INSERT INTO ref_card_scheme (id, is_active, code, name, group_code, group_name, status, card_type, card_subtype)
VALUES (18, true, 'BENEFIT', 'Benefit', 'BNFT', 'Benefit', 1, 2, 0)
ON CONFLICT DO NOTHING;

-- 2. BENEFIT interchange rows (country-level: tenant_id NULL).
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT v.* FROM ( VALUES
  -- First row carries explicit casts (VALUES common-type inference; see BH card).
  (NULL::INT, 'BH', 'BHD', 66, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, NULL, NULL::NUMERIC, NULL::NUMERIC, 0.006000, NULL::NUMERIC, 'BENEFIT purchase 0.60%'),

  -- Government: flat BHD 0.100 below 20 BHD, flat BHD 0.200 at/above.
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9211', NULL,  20.00, 1.000000, 0.100, 'BENEFIT Govt 9211 <20 BHD flat 0.100'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9211', 20.00, NULL,  1.000000, 0.200, 'BENEFIT Govt 9211 >=20 BHD flat 0.200'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9222', NULL,  20.00, 1.000000, 0.100, 'BENEFIT Govt 9222 <20 BHD flat 0.100'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9222', 20.00, NULL,  1.000000, 0.200, 'BENEFIT Govt 9222 >=20 BHD flat 0.200'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9223', NULL,  20.00, 1.000000, 0.100, 'BENEFIT Govt 9223 <20 BHD flat 0.100'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9223', 20.00, NULL,  1.000000, 0.200, 'BENEFIT Govt 9223 >=20 BHD flat 0.200'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9311', NULL,  20.00, 1.000000, 0.100, 'BENEFIT Govt 9311 <20 BHD flat 0.100'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9311', 20.00, NULL,  1.000000, 0.200, 'BENEFIT Govt 9311 >=20 BHD flat 0.200'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9399', NULL,  20.00, 1.000000, 0.100, 'BENEFIT Govt 9399 <20 BHD flat 0.100'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9399', 20.00, NULL,  1.000000, 0.200, 'BENEFIT Govt 9399 >=20 BHD flat 0.200'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9402', NULL,  20.00, 1.000000, 0.100, 'BENEFIT Govt 9402 <20 BHD flat 0.100'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '9402', 20.00, NULL,  1.000000, 0.200, 'BENEFIT Govt 9402 >=20 BHD flat 0.200'),

  -- Exchange houses: 0.60% capped BHD 0.029.
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '6051', NULL, NULL, 0.006000, 0.029, 'BENEFIT Exchange 6051 0.60% cap 0.029'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '4829', NULL, NULL, 0.006000, 0.029, 'BENEFIT Exchange 4829 0.60% cap 0.029'),

  -- Petrol / fuel: 0.60% capped BHD 0.085.
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '5541', NULL, NULL, 0.006000, 0.085, 'BENEFIT Petrol 5541 0.60% cap 0.085'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '5542', NULL, NULL, 0.006000, 0.085, 'BENEFIT Petrol 5542 0.60% cap 0.085'),

  -- Charities: zero interchange.
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '8398', NULL, NULL, 0.000000, NULL, 'BENEFIT Charity 8398 zero'),
  (NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, 'Benefit', NULL, NULL, NULL, '8661', NULL, NULL, 0.000000, NULL, 'BENEFIT Charity 8661 zero')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'BH' AND x.scheme_group = 'Benefit');

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_08_05__benefit_rate_card_bh.sql') ON CONFLICT (filename) DO NOTHING;
