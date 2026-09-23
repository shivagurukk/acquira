-- ============================================================================
-- V2026_08_12_01: Meeza interchange = 0.50% for ALL Egyptian Meeza traffic.
--
-- Business instruction 2026-08-12: Meeza is priced at a flat 0.50%, with no
-- differentiation by channel (POS/ECOM), card type, tier, MCC, ticket size or
-- destination. This SUPERSEDES the interim 1.85% seeded by V2026_08_10_02.
--
-- IN-PLACE, NOT EFFECTIVE-DATED. V2026_08_10_02 said to close its row with an
-- effective_to and insert a successor when the real rate arrived. That is the
-- right move for a rate CHANGE (history must keep pricing at the rate that was
-- in force). This is not a change — the 1.85% was a placeholder standing in for
-- a number nobody had, so no Meeza transaction was ever correctly priced at it.
-- Rewriting the row therefore reprices ALL Meeza history at 0.50%, which is
-- what "0.5% for all" means. Its effective_from stays 2026-08-01 (Egypt
-- go-live) so nothing before that date is affected either way.
--
-- Repricing already-ingested months is NOT automatic: fact_transaction rows
-- carry the fee resolved at ingest time. Re-ingest, or run the Interchange Fee
-- Normalization apply (V2026_08_09_04), for any Egyptian month already loaded.
--
-- Runs on every startup, so both statements are convergent and idempotent. The
-- guard is the source_note marker below: once a row carries it, this migration
-- stops touching it, so a later operator/business edit to the rate is never
-- silently reverted on the next restart (same contract as the 'BUSINESS-
-- APPROVED' prefix in V2026_08_10_04).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Rewrite every existing Egyptian Meeza rate row to 0.50%.
--    Wildcards everything that could narrow the row's applicability so a single
--    row genuinely covers "all": no channel, card_type, tier, MCC or ticket
--    band, no flat fee and no cap.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET interchange_pct = 0.005000,
       flat_fee        = 0.0000,
       cap_amount      = NULL,
       channel         = NULL,
       card_type       = NULL,
       tier            = NULL,
       mcc_sector      = NULL,
       mcc             = NULL,
       min_ticket      = NULL,
       max_ticket      = NULL,
       effective_to    = NULL,
       rate_status     = 'APPROVED',
       label           = 'Meeza 0.50% (all channels, all card types)',
       source_note     = 'MEEZA-FLAT-0.5 business instruction 2026-08-12: flat 0.50% for all Meeza, superseding the V2026_08_10_02 interim 1.85%.'
 WHERE country_code = 'EG'
   AND scheme_group = 'Meeza'
   AND COALESCE(source_note,'') NOT LIKE 'MEEZA-FLAT-0.5%';

-- ----------------------------------------------------------------------------
-- 2. Cover the destination axis. The seeded row is dest='DOMESTIC' only, and
--    dest is NOT NULLable in the resolver's matching (i.dest = the normalized
--    destination token), so a Meeza transaction arriving as INTERNATIONAL would
--    otherwise fall through to Egypt's any-scheme placeholder rows instead of
--    0.50%. Priority 66 keeps it above the per-MCC (40) and default (10) rows,
--    matching the domestic Meeza row.
-- ----------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
     interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'EG', 'EGP', 66, 'INTERNATIONAL', NULL::VARCHAR, 'Meeza', NULL::VARCHAR, NULL::VARCHAR, NULL::VARCHAR,
   NULL::VARCHAR, NULL::NUMERIC, NULL::NUMERIC, 0.005000, 0.0000, NULL::NUMERIC, 'APPROVED', DATE '2026-08-01',
   'Meeza 0.50% (all channels, all card types)',
   'MEEZA-FLAT-0.5 business instruction 2026-08-12: flat 0.50% for all Meeza, superseding the V2026_08_10_02 interim 1.85%.')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket,
       interchange_pct, flat_fee, cap_amount, rate_status, effective_from, label, source_note)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x
                  WHERE x.country_code = 'EG' AND x.scheme_group = 'Meeza'
                    AND x.dest = 'INTERNATIONAL');

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_12_01__meeza_interchange_half_pct.sql')
ON CONFLICT (filename) DO NOTHING;
