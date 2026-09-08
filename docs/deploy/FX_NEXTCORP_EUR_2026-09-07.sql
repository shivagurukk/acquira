-- ============================================================================
-- NEXTCORP WLL - ecom FX rate row (EUR), MID supplied by business 2026-09-07.
--
-- Companion to V2026_09_07_01__netspread_ecom_fx_revenue.sql, whose name-based
-- NEXTCORP seed found no merchant at apply time. Calc-sheet rates:
--   EUR: board 0.440, cost 0.430, multiplier 0.430
--   fx = 0.430 * (settlement/0.430 - settlement/0.440)  = settlement * (1 - 0.430/0.440)
--
-- Idempotent: guarded per BH tenant, never clobbers an existing row.
-- After applying, rebuild summaries for months where NEXTCORP has ECOM EUR
-- history so fx_revenue backfills; new ingests pick it up automatically.
-- ============================================================================

INSERT INTO ref_ecom_fx_rate (tenant_id, mid, txn_currency, board_rate, cost_rate, multiplier, label)
SELECT t.tenant_id, '000000000565200', 'EUR', 0.440, 0.430, 0.430, 'NEXTCORP EUR'
FROM tenant t
-- AFSB acquiring tenant ONLY (user decision 2026-09-07: FX is not for every BH tenant).
WHERE t.institution_id = 'AFSB'
  AND NOT EXISTS (SELECT 1 FROM ref_ecom_fx_rate x
                  WHERE x.tenant_id = t.tenant_id
                    AND x.mid = '000000000565200'
                    AND x.txn_currency = 'EUR');

-- Verify
SELECT tenant_id, mid, txn_currency, board_rate, cost_rate, multiplier, label
FROM ref_ecom_fx_rate WHERE mid = '000000000565200';
