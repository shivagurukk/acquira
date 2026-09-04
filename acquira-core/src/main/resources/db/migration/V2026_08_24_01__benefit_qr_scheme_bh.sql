-- ============================================================================
-- V2026_08_24_01: Benefit QR (Bahrain) — new scheme + rate card.
--
-- WHY
-- ---
-- The BH feed labels QR-routed domestic transactions with card scheme
-- 'No Interchange'. Business rule (user-confirmed 2026-08-24): these ARE the
-- Benefit QR product and must be priced at the Bahrain local (Benefit) rates,
-- not fall to the BH any-scheme wildcard.
--
-- The ingest paths (TransactionJobConfig stagingToFact + BackfillIngestionService,
-- both changed 2026-08-24) normalize the feed token 'No Interchange' to the
-- canonical scheme name 'Benefit QR' at the staging->fact boundary, so:
--   * dashboards/rollups group it as its own scheme, and
--   * the fee engine's scheme LATERAL resolves it via the ref_card_scheme row
--     seeded here (name matched space/case-insensitively: 'BENEFITQR').
--
-- RATES: exact clone of the live BH Benefit card (V2026_08_08_05 seed, or
-- whatever it has been edited to since — cloned from the TABLE, not re-typed,
-- so in-UI corrections to the Benefit card made before this migration runs are
-- inherited). scheme_group 'Benefit QR'.
--
-- SCHEME FEE: like Benefit, intentionally NOT seeded — Benefit QR domestic
-- txns take the BH wildcard scheme-fee grid until a real figure is configured.
--
-- Idempotent: ref_card_scheme via ON CONFLICT DO NOTHING; rate rows guarded by
-- NOT EXISTS (no BH 'Benefit QR' rows yet) so later edits are never clobbered.
-- Splitter-safe (no dollar-quoting).
-- ============================================================================

-- 1. Benefit QR scheme reference. Ids 18 (Benefit) / 19 (Meeza) are taken.
--    card_type=2 (Debit) — same coarsening as Benefit; card_subtype=0 (all
--    rate rows below are tier-wildcard, so tier is moot).
INSERT INTO ref_card_scheme (id, is_active, code, name, group_code, group_name, status, card_type, card_subtype)
VALUES (20, true, 'BQR', 'Benefit QR', 'BQR', 'Benefit QR', 1, 2, 0)
ON CONFLICT DO NOTHING;

-- 2. Benefit QR interchange rows: clone every BH Benefit row (base 0.60%,
--    government flat-fee bands, exchange/petrol caps, charity zero) under
--    scheme_group 'Benefit QR'. Copies rate_status/effective dating verbatim.
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, rate_status, effective_from, effective_to)
SELECT i.tenant_id, i.country_code, i.cap_currency_code, i.priority, i.dest, i.channel, 'Benefit QR',
       i.card_type, i.tier, i.mcc_sector, i.mcc, i.min_ticket, i.max_ticket, i.interchange_pct, i.cap_amount,
       LEFT(REPLACE(COALESCE(i.label,''), 'BENEFIT', 'BENEFIT QR'), 80), i.rate_status, i.effective_from, i.effective_to
FROM interchange_rate_local i
WHERE i.country_code = 'BH' AND i.scheme_group = 'Benefit'
  AND NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'BH' AND x.scheme_group = 'Benefit QR');

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_24_01__benefit_qr_scheme_bh.sql') ON CONFLICT (filename) DO NOTHING;
