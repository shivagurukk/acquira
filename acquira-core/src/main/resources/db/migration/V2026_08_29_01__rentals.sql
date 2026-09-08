-- ============================================================================
-- V2026_08_29_01: Terminal / Store / Merchant Rentals — staging + fact + dim
--   convenience columns.
--
-- Rentals arrive in a DEDICATED file (not the transaction or merchant-master
-- feed), through the same three channels as transactions: screen upload,
-- Server File Processor, and the scheduled integration pull. RentalJobConfig
-- (rentalLoadJob / dbPullRentalJob) stages rows here and applies them.
--
-- LEVEL IS DERIVED, NEVER SUPPLIED (decision 2026-08-29):
--   MID + SID + TID present -> TERMINAL
--   MID + SID present       -> STORE
--   MID only                -> MERCHANT
--   CMM-format tenants send SID only -> always STORE.
-- Invalid combinations (TID without SID, SID without MID on an AMS tenant,
-- no ids at all) are marked REJECTED in staging with a reason and surfaced on
-- the screen — they never reach fact_rental.
--
-- fact_rental holds DATED charge records (each row has a payment_date, like
-- the transaction feed) so Phase 2 can spread a charge across its month.
-- Amounts are tenant base currency, major units — no minor-unit division for
-- either input format. Dedupe is via row_hash (tenant-scoped), so re-uploading
-- the same file is a no-op while a new date or amount lands as a new charge.
--
-- dim_merchant/dim_store/dim_terminal.rental_amount hold the LATEST charge per
-- entity as a convenience for merchant-centric screens; fact_rental is the
-- source of truth.
--
-- Idempotent; splitter-safe (no $$). Listed in spring.sql.init.schema-locations
-- for dev; apply once via psql on prod.
-- ============================================================================

CREATE TABLE IF NOT EXISTS stg_rental_raw (
    raw_id        BIGSERIAL PRIMARY KEY,
    tenant_id     INT,
    file_id       BIGINT,
    load_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    row_hash      VARCHAR(64),
    status        VARCHAR(20) DEFAULT 'PENDING',  -- PENDING|PROCESSED|DUPLICATE|REJECTED|UNMATCHED
    error_message TEXT,

    entity_name   VARCHAR(100),
    mid           VARCHAR(50),
    sid           VARCHAR(50),
    tid           VARCHAR(50),
    level         VARCHAR(20),                    -- derived at apply time
    rental_amount DECIMAL(19,4),
    payment_date  DATE
);

CREATE INDEX IF NOT EXISTS idx_stg_rental_tenant ON stg_rental_raw (tenant_id, status);

ALTER TABLE stg_rental_raw ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON stg_rental_raw;
CREATE POLICY tenant_isolation_policy ON stg_rental_raw
    USING (tenant_id = get_current_tenant());

CREATE TABLE IF NOT EXISTS fact_rental (
    rental_id     BIGSERIAL PRIMARY KEY,
    tenant_id     INT NOT NULL,
    level         VARCHAR(20) NOT NULL,           -- MERCHANT|STORE|TERMINAL
    merchant_id   BIGINT,
    store_id      BIGINT,
    terminal_id   BIGINT,
    mid           VARCHAR(50),
    sid           VARCHAR(50),
    tid           VARCHAR(50),
    rental_amount DECIMAL(19,4) NOT NULL,
    payment_date  DATE NOT NULL,
    row_hash      VARCHAR(64),
    file_id       BIGINT,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Tenant-scoped dedupe: same tenant + same (ids, amount, date) never lands twice.
CREATE UNIQUE INDEX IF NOT EXISTS ux_fact_rental_tenant_hash
    ON fact_rental (tenant_id, row_hash);
CREATE INDEX IF NOT EXISTS idx_fact_rental_tenant_date
    ON fact_rental (tenant_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_fact_rental_tenant_level_date
    ON fact_rental (tenant_id, level, payment_date);

ALTER TABLE fact_rental ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON fact_rental;
CREATE POLICY tenant_isolation_policy ON fact_rental
    USING (tenant_id = get_current_tenant());

-- Latest-charge convenience columns on the dims (nullable; AMS fills all three
-- levels, CMM fills stores only).
ALTER TABLE dim_merchant ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4);
ALTER TABLE dim_store    ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4);
ALTER TABLE dim_terminal ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4);
