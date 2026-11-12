-- ============================================================================
-- V2026_10_05_02: Mastercard UAE changes effective 1 October 2026 (part 2).
-- UAE only (country_code 'AE': country rows + tenant-1 overrides).
--
-- SCHEME FEE - MEA 13665.1 "Acquiring Growth Enablement Fund": +0.03% on
-- Mastercard DOMESTIC CREDIT purchase volume from 1 Oct 2026. Debit and
-- prepaid domestic are NOT in the fund and keep the existing rate.
--
-- Seeds CREDIT-only rows (scheme_fee_rate.card_type, added by
-- V2026_10_05_01; NULL = any card type) at the current Mastercard domestic
-- rate + 0.0003.
--
-- ORDER MATTERS: run this ONLY AFTER V2026_10_05_01 AND after the batch build
-- that reads scheme_fee_rate.card_type (FeeComputationService) is deployed.
-- On an older build the CREDIT rows would also be picked for debit and prepaid.
--
-- Idempotent. Splitter-safe (no $$). Apply once via psql, then
-- rebuild-summaries with reprice:true for AE from October 2026.
-- ============================================================================

INSERT INTO scheme_fee_rate
    (country_code, tenant_id, dest, channel, scheme_group, card_type, fee_pct, flat_fee, rate_status,
     effective_from, source_note)
SELECT s.country_code, s.tenant_id, s.dest, s.channel, s.scheme_group, 'CREDIT', s.fee_pct + 0.000300,
       s.flat_fee, s.rate_status, DATE '2026-10-01',
       'MC MEA 13665.1 Acquiring Growth Enablement Fund: +0.03% on Mastercard domestic credit from 2026-10-01'
FROM scheme_fee_rate s
WHERE s.country_code = 'AE'
  AND (s.tenant_id IS NULL OR s.tenant_id = 1)
  AND s.dest = 'DOMESTIC'
  AND s.scheme_group = 'MasterCard'
  AND s.card_type IS NULL
  AND s.effective_to IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM scheme_fee_rate x
      WHERE x.country_code = s.country_code
        AND COALESCE(x.tenant_id, 0) = COALESCE(s.tenant_id, 0)
        AND x.dest = s.dest AND x.channel = s.channel
        AND x.scheme_group = s.scheme_group
        AND x.card_type = 'CREDIT');

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_05_02__mc_uae_dom_credit_scheme_fee.sql')
ON CONFLICT (filename) DO NOTHING;

-- CHECK: per tenant scope and channel, the any-type Mastercard domestic row
-- (unchanged) plus a CREDIT row from 2026-10-01 at +0.0003.
SELECT tenant_id, dest, channel, card_type, fee_pct, effective_from, effective_to
FROM scheme_fee_rate
WHERE country_code = 'AE' AND scheme_group = 'MasterCard' AND dest = 'DOMESTIC'
ORDER BY tenant_id, channel, card_type;
