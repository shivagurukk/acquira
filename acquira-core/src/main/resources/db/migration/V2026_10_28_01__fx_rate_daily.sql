-- ============================================================================
-- V2026_10_28_01: Daily FX rates for floating currencies (EGP today).
--
-- WHY: executive USD figures were converted at ONE hardcoded EGP rate
-- (frontend/src/config/fxRates.js + FxRates.java, stamped 2026-09-10) applied
-- to period totals, so January volume was priced at October's rate. Rates now
-- live here, one row per currency per date, and every amount is converted at
-- the rate OF ITS BUSINESS DATE (carry-forward over days without a row).
--
-- Global table (a rate is a market fact shared by all tenants of a currency),
-- so no tenant_id and no RLS — same as ref_country. Pegged Gulf currencies
-- are NOT loaded: FxRates.java keeps their constants.
--
-- units_per_usd is canonical ("1 USD = 48.65 EGP", how the bank's MPS stores
-- its buy rate); usd_per_unit is derived so SQL joins stay a plain multiply.
--
-- source: MPS_SEED  = one-off load from the bank's MPS rate export (EGY.xls)
--         MPS_PULL  = Integration Hub FX_RATE report (daily pull from MPS)
--         MANUAL    = entered/corrected by an admin — never overwritten by pulls
--         CSV       = admin CSV upload
-- Idempotent, splitter-safe.
-- ============================================================================
CREATE TABLE IF NOT EXISTS fx_rate_daily (
    fx_rate_id      BIGSERIAL PRIMARY KEY,
    currency_code   VARCHAR(3)     NOT NULL,
    rate_date       DATE           NOT NULL,
    units_per_usd   NUMERIC(18,8)  NOT NULL CHECK (units_per_usd > 0),
    usd_per_unit    NUMERIC(20,12) GENERATED ALWAYS AS (1 / units_per_usd) STORED,
    source          VARCHAR(16)    NOT NULL,
    source_ref      VARCHAR(200),
    loaded_by       VARCHAR(100),
    loaded_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (currency_code, rate_date)
);
CREATE INDEX IF NOT EXISTS ix_fx_rate_daily_ccy_date ON fx_rate_daily (currency_code, rate_date DESC);

-- Staging for the Integration Hub FX_RATE pull (per-tenant wipe + fill, same
-- convention as stg_dcc_revenue_raw). Several MPS rows can share one date
-- (rate re-entered during the day); the apply step averages them.
CREATE TABLE IF NOT EXISTS stg_fx_rate_raw (
    stg_id          BIGSERIAL PRIMARY KEY,
    tenant_id       INT            NOT NULL,
    currency_code   VARCHAR(3),
    rate_date       DATE           NOT NULL,
    units_per_usd   NUMERIC(18,8)  NOT NULL,
    load_time       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_stg_fx_rate_raw_tenant ON stg_fx_rate_raw (tenant_id);
