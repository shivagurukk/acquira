-- ============================================================================
-- V2026_09_07_01: ECOM FX income for Net Spread (Bahrain), tenant-flag gated.
--
-- WHY
-- ---
-- BH acquirer earns an FX margin on e-commerce transactions settled in BHD but
-- transacted in a foreign currency: the merchant is credited at a negotiated
-- COST rate while the acquirer converts at the BOARD rate. Business formula
-- (finance calc sheet, 2026-09-07), per transaction:
--
--   fx_income = multiplier * (settlement/cost_rate - settlement/board_rate)
--             ( = settlement * (1 - cost/board) whenever multiplier = cost_rate,
--               which is every row except the sheet's default-EUR anomaly,
--               preserved verbatim below: mult 0.370 vs cost 0.430 )
--
-- Applies ONLY to ECOM transactions NOT carried by Benefit PG (Benefit is the
-- domestic scheme/gateway - no FX), and only to currencies with a seeded rate
-- row (BHD deliberately has none). Merchant-specific negotiated rates override
-- the tenant defaults (TAP / RAIN / COINMENA / NEXTCORP).
--
-- DESIGN
-- ------
-- * ref_ecom_fx_rate - rate table instead of a hardcoded if-chain; mid NULL =
--   tenant default, a mid-specific row wins. MIDs are compared with leading
--   zeros stripped on BOTH sides (feed MIDs are 15-digit zero-padded).
-- * fx_revenue columns on sum_daily_merchant + sum_daily_finance_rollup,
--   re-derived from fact_transaction by AncillarySql (same lifecycle as
--   dcc_acquirer / rental_amount - clean-slate rebuilds re-derive, never wipe).
-- * tenant_setting 'netspread.fx_enabled' - read by NetSpreadController.
--   OPT-IN: only an explicit 'true' shows the FX column and adds it to the
--   spread (unlike pricing.simulator_enabled's fail-open, because tenants
--   without rate rows would otherwise show a dead all-zero column).
-- * Refunds need no special casing: fact rows are volume-signed (REFUND rows
--   carry a negative store_base_currency_amount), so the formula reverses the
--   FX income on refunds by construction.
-- * Historic days need a summary rebuild (or any re-ingest) to pick up FX -
--   the columns start at 0 and only AncillarySql fills them.
--
-- Idempotent; splitter-safe (no dollar-quoting, no DO blocks).
-- ============================================================================

-- 1. Rate table -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ref_ecom_fx_rate (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    INT NOT NULL,
    mid          VARCHAR(64) NULL,      -- NULL = tenant default for the currency
    txn_currency VARCHAR(10) NOT NULL,  -- fact_transaction.txn_currency, UPPERCASE
    board_rate   NUMERIC(12,6) NOT NULL, -- rate the acquirer converts at (b)
    cost_rate    NUMERIC(12,6) NOT NULL, -- rate the merchant is credited at (c)
    multiplier   NUMERIC(12,6) NOT NULL, -- calc-sheet trailing factor (= c except default EUR)
    label        VARCHAR(120) NULL
);

COMMENT ON TABLE ref_ecom_fx_rate IS
  'ECOM FX income rates: fx = multiplier * (settlement/cost_rate - settlement/board_rate) per ECOM non-Benefit-PG transaction in this currency. mid NULL = tenant default; a mid row overrides. Read-time reference data - DB-seeded only, never uploadable.';

CREATE UNIQUE INDEX IF NOT EXISTS uq_ref_ecom_fx_rate
    ON ref_ecom_fx_rate (tenant_id, COALESCE(mid, '*'), txn_currency);

-- 2. Summary columns --------------------------------------------------------
ALTER TABLE sum_daily_merchant       ADD COLUMN IF NOT EXISTS fx_revenue NUMERIC(21,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS fx_revenue NUMERIC(21,4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN sum_daily_merchant.fx_revenue IS
  'ECOM FX income (excl. Benefit PG), re-derived from fact_transaction x ref_ecom_fx_rate by AncillarySql. Added to Net Spread only when tenant_setting netspread.fx_enabled = true.';

-- 3. Seed: tenant defaults - the AFSB acquiring tenant ONLY (user decision
--    2026-09-07: FX is for the acquiring tenant, not every BH tenant), guarded.
-- Sheet default rows. EUR multiplier 0.370 (not its 0.430 cost rate) and the
-- zero-margin OMR row (0.980/0.980) are VERBATIM from the sheet.
INSERT INTO ref_ecom_fx_rate (tenant_id, mid, txn_currency, board_rate, cost_rate, multiplier, label)
SELECT t.tenant_id, NULL, v.ccy, v.b, v.c, v.m, v.lbl
FROM tenant t
JOIN ( VALUES
  ('USD', 0.377, 0.375, 0.375, 'BH default USD'),
  ('SAR', 0.100, 0.090, 0.090, 'BH default SAR'),
  ('AED', 0.103, 0.090, 0.090, 'BH default AED'),
  ('QAR', 0.104, 0.090, 0.090, 'BH default QAR'),
  ('GBP', 0.510, 0.410, 0.410, 'BH default GBP'),
  ('KWD', 1.230, 1.210, 1.210, 'BH default KWD'),
  ('EUR', 0.440, 0.430, 0.370, 'BH default EUR (sheet multiplier 0.370, verbatim)'),
  ('OMR', 0.980, 0.980, 0.970, 'BH default OMR (equal rates - zero margin, verbatim)')
) AS v(ccy, b, c, m, lbl) ON TRUE
WHERE t.institution_id = 'AFSB'
  AND NOT EXISTS (SELECT 1 FROM ref_ecom_fx_rate x
                  WHERE x.tenant_id = t.tenant_id AND x.mid IS NULL
                    AND x.txn_currency = v.ccy);

-- 4. Seed: merchant-specific negotiated rates --------------------------------
-- COINMENA BSC CLOSED 000000000152819 / TAP PAYMENTS COMPANY BSC
-- 000000000126540 / RAIN MANAGEMENT WLL 000000000266544.
INSERT INTO ref_ecom_fx_rate (tenant_id, mid, txn_currency, board_rate, cost_rate, multiplier, label)
SELECT t.tenant_id, v.mid, v.ccy, v.b, v.c, v.m, v.lbl
FROM tenant t
JOIN ( VALUES
  -- COINMENA BSC CLOSED
  ('000000000152819', 'AED', 0.103, 0.099, 0.099, 'COINMENA AED'),
  ('000000000152819', 'KWD', 1.225, 1.210, 1.210, 'COINMENA KWD'),
  ('000000000152819', 'QAR', 0.104, 0.100, 0.100, 'COINMENA QAR'),
  ('000000000152819', 'GBP', 0.510, 0.479, 0.479, 'COINMENA GBP'),
  ('000000000152819', 'USD', 0.377, 0.375, 0.375, 'COINMENA USD'),
  ('000000000152819', 'OMR', 0.979, 0.970, 0.970, 'COINMENA OMR'),
  -- TAP PAYMENTS COMPANY BSC
  ('000000000126540', 'SAR', 0.100, 0.098, 0.098, 'TAP SAR'),
  ('000000000126540', 'AED', 0.103, 0.098, 0.098, 'TAP AED'),
  ('000000000126540', 'KWD', 1.230, 1.210, 1.210, 'TAP KWD'),
  ('000000000126540', 'QAR', 0.104, 0.098, 0.098, 'TAP QAR'),
  ('000000000126540', 'GBP', 0.510, 0.467, 0.467, 'TAP GBP'),
  ('000000000126540', 'USD', 0.377, 0.375, 0.375, 'TAP USD'),
  ('000000000126540', 'OMR', 0.980, 0.980, 0.980, 'TAP OMR (zero margin, verbatim)'),
  ('000000000126540', 'EUR', 0.440, 0.400, 0.400, 'TAP EUR'),
  -- RAIN MANAGEMENT WLL
  ('000000000266544', 'SAR', 0.100, 0.098, 0.098, 'RAIN SAR'),
  ('000000000266544', 'AED', 0.103, 0.098, 0.098, 'RAIN AED'),
  ('000000000266544', 'KWD', 1.230, 1.210, 1.210, 'RAIN KWD'),
  ('000000000266544', 'QAR', 0.104, 0.098, 0.098, 'RAIN QAR'),
  ('000000000266544', 'GBP', 0.479, 0.467, 0.467, 'RAIN GBP'),
  ('000000000266544', 'USD', 0.377, 0.375, 0.375, 'RAIN USD'),
  ('000000000266544', 'OMR', 0.980, 0.980, 0.980, 'RAIN OMR (zero margin, verbatim)'),
  ('000000000266544', 'EUR', 0.440, 0.400, 0.400, 'RAIN EUR')
) AS v(mid, ccy, b, c, m, lbl) ON TRUE
WHERE t.institution_id = 'AFSB'
  AND NOT EXISTS (SELECT 1 FROM ref_ecom_fx_rate x
                  WHERE x.tenant_id = t.tenant_id AND x.mid = v.mid
                    AND x.txn_currency = v.ccy);

-- NEXTCORP WLL (EUR only) - the sheet gave no MID, so resolve it from
-- dim_merchant by name at apply time. If NEXTCORP is not onboarded yet this
-- seeds nothing; insert its row manually once the MID is known.
INSERT INTO ref_ecom_fx_rate (tenant_id, mid, txn_currency, board_rate, cost_rate, multiplier, label)
SELECT m.tenant_id, m.mid, 'EUR', 0.440, 0.430, 0.430, 'NEXTCORP EUR (mid resolved by name)'
FROM dim_merchant m
JOIN tenant t ON t.tenant_id = m.tenant_id AND t.institution_id = 'AFSB'
WHERE UPPER(m.name) LIKE 'NEXTCORP%'
  AND NOT EXISTS (SELECT 1 FROM ref_ecom_fx_rate x
                  WHERE x.tenant_id = m.tenant_id AND x.mid = m.mid
                    AND x.txn_currency = 'EUR');

-- 5. Tenant flag: ON for BH tenants (guarded - a deliberate later 'false'
--    is never forced back). Absent/false = FX hidden and excluded from spread.
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'netspread.fx_enabled', 'true', 'BOOLEAN'
FROM tenant t
WHERE t.institution_id = 'AFSB'
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Verify
SELECT tenant_id, COALESCE(mid,'(default)') AS mid, txn_currency, board_rate, cost_rate, multiplier
FROM ref_ecom_fx_rate ORDER BY tenant_id, mid NULLS FIRST, txn_currency;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_07_01__netspread_ecom_fx_revenue.sql') ON CONFLICT (filename) DO NOTHING;

