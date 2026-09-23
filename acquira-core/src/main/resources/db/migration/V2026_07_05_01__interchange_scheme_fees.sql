-- ============================================================================
-- V2026_07_05_01: Compute interchange fee AND scheme fee ourselves, at ingest.
--
-- WHY
-- ---
-- Until now interchange_fee was whatever the source feed sent (staging -> fact ->
-- every SUM(interchange_fee) rollup), and total_scheme_fee was hardcoded 0 in
-- every summary. Profit (msf - interchange - scheme_fee) was therefore wrong:
-- interchange was untrusted feed data and scheme fee was missing entirely.
--
-- This migration adds three rate-config tables + one fact column so BOTH fees
-- are computed by us at ingest (stagingToFactStep), off the SETTLEMENT amount
-- (store_base_currency_amount, single-currency) -- never the cardholder amount
-- (txn_currency_amount). Fees/profit are settlement-currency and reconcile
-- against total_base_volume (see project data-sourcing rules).
--
-- TWO INDEPENDENT FEES
-- --------------------
-- 1. INTERCHANGE (interchange_rate_local + mcc_sector_map): resolved by
--    priority (higher wins):
--      30 credit ticket thresholds (Auto >= 36700 AED; MC-only REX >= 3670 AED),
--         channel-specific (POS vs ECOM rates differ)
--      20 MCC/sector override: Govt .35 / Gas .50 / RE .30 / Edu .30 / Trans .45
--      11 JCB / UPI flat 1.75 (any tier/type)
--      10 scheme x tier x card_type base: Debit/Prepaid .75 (+37.5 cap);
--         Visa Std 1.15 / Prem 1.80; MC Std 1.25 / Prem 1.80
--       1 INTERNATIONAL flat 1.85 (scheme-agnostic)
-- 2. SCHEME FEE (scheme_fee_rate): destination x channel percentage
--      DOMESTIC POS .75 / ECOM .12 ; INTERNATIONAL POS .90 / ECOM .90
-- 3. ECOM FLAT FEE: 0.18 (settlement ccy) per transaction on ECOM terminals,
--    applied AFTER scheme fee; net = msf - interchange - scheme_fee - ecom_fee.
--
-- RESOLUTION MODEL
-- ----------------
-- interchange_rate_local rows carry `priority` (higher wins) and nullable match
-- columns (NULL = wildcard). stagingToFactStep selects the highest-priority row
-- whose non-null columns all match the transaction (ORDER BY priority DESC, id
-- LIMIT 1 in a LATERAL). Rates are retunable in-table with no rebuild.
--
-- TIER / CHANNEL / SCHEME derivation
-- ----------------------------------
-- scheme : card_scheme matched against ref_card_scheme by CODE or NAME (feed
--          sends both granular codes like VICP/MCPM and full names like
--          'UnionPay International'); group_name gives Visa/MasterCard/JCB/UPI.
-- tier   : ref_card_scheme.card_subtype (2 = Premium, else Standard). Plain or
--          unknown codes fall back to Standard.
-- channel: dim_terminal.type exact whitelist -> ECOM for 'ECOM PROFILE',
--          'MPGS', 'PAY BY LINK', 'PAY ON'; everything else (incl. SoftPOS,
--          physical devices, None/NULL/no terminal) -> POS.
--
-- SEED IDEMPOTENCY (important)
-- ----------------------------
-- schema-locations scripts run on EVERY startup. interchange_rate_local match
-- columns are nullable, and Postgres unique constraints treat NULLs as
-- distinct, so ON CONFLICT can NOT be used to make its seed idempotent. The
-- seed is therefore guarded with NOT EXISTS (tenant has zero rows): first run
-- seeds, later runs no-op, and in-UI rate edits are never clobbered.
-- mcc_sector_map / scheme_fee_rate keys are NOT NULL, so plain ON CONFLICT
-- works there.
--
-- SEED TARGET TENANT
-- ------------------
-- The default UAE rate card is seeded for the tenant whose bank_short_code =
-- 'ACQ' (Acquira Bank, institution_id BANK001, the code that appears as the
-- feed's Entity Name). Matching on institution_id = 'ACQ' was WRONG -- 'ACQ'
-- is the short code, not the institution_id (which is 'BANK001') -- and seeded
-- ZERO rows, silently leaving every fee on feed-fallback (scheme fee 0,
-- interchange = raw feed value). bank_short_code = 'ACQ' is the correct,
-- stable match.
--
-- HOW THIS RUNS
-- -------------
-- Splitter-safe: NO $$ dollar-quoting (that broke spring.sql.init before), so
-- this file is listed in spring.sql.init.schema-locations. On prod (sql.init
-- disabled), apply once via psql -- all statements are idempotent.
--
-- Unseeded tenants get no rate rows; the ingest UPDATE only touches rows with
-- a matching rate, so the feed value survives untouched and ingestion can
-- never break for an unseeded tenant.
--
-- NOTE 6513: it is 'RE' (flat 0.30 sector override) and NOT 'REX', so Real
-- Estate proper does NOT get the MC 3670-AED threshold -- only Exchange House
-- MCCs (6051, 4829) do. Conscious call: one sector per MCC, and the flat RE
-- override is the lower/safer treatment. Flip the seed row if business wants
-- 6513 on the threshold instead.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. fact_transaction.scheme_fee  (interchange_fee already exists)
-- ---------------------------------------------------------------------------
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS scheme_fee DECIMAL(19, 4);

-- ---------------------------------------------------------------------------
-- 2. mcc_sector_map -- MCC -> sector
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mcc_sector_map (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   INT NOT NULL,
    mcc         VARCHAR(10) NOT NULL,
    sector      VARCHAR(20) NOT NULL,
    UNIQUE (tenant_id, mcc)
);
CREATE INDEX IF NOT EXISTS idx_mcc_sector_map_lookup ON mcc_sector_map (tenant_id, mcc);

-- ---------------------------------------------------------------------------
-- 3. interchange_rate_local -- priority-ordered, channel-aware rate rows
--    NULL match column = wildcard. No unique constraint across the nullable
--    match columns (NULLs defeat it); integrity is by seed guard + admin UI.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS interchange_rate_local (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           INT NOT NULL,
    priority            INT NOT NULL DEFAULT 0,
    dest                VARCHAR(20) NOT NULL,          -- DOMESTIC / INTERNATIONAL
    channel             VARCHAR(20),                   -- POS / ECOM / NULL(any)
    scheme_group        VARCHAR(20),                   -- Visa / MasterCard / JCB / UPI / NULL(any)
    card_type           VARCHAR(20),                   -- CREDIT / DEBIT / PREPAID / NULL(any)
    tier                VARCHAR(20),                   -- Standard / Premium / NULL(any)
    mcc_sector          VARCHAR(20),                   -- Govt/Gas/RE/Edu/Trans/Auto/REX / NULL(any)
    min_ticket      DECIMAL(19, 2),                -- inclusive lower bound / NULL
    max_ticket      DECIMAL(19, 2),                -- exclusive upper bound / NULL
    interchange_pct     DECIMAL(9, 6) NOT NULL,        -- 0.011500 = 1.15%
    cap_amount          DECIMAL(19, 2),                -- 37.5 AED debit cap / NULL
    label               VARCHAR(80)
);
CREATE INDEX IF NOT EXISTS idx_interchange_rate_local_lookup
    ON interchange_rate_local (tenant_id, dest, priority DESC);

-- ---------------------------------------------------------------------------
-- 3b. LEGACY COLUMN RENAME (2026-08-11): min_ticket_aed -> min_ticket.
--
-- These thresholds were never in AED. They are compared RAW against
-- store_base_currency_amount, i.e. the TENANT's settlement currency — so on a
-- Bahraini tenant "20" means 20 BHD, and on an Egyptian one 20 EGP. The '_aed'
-- suffix was actively misleading for a multi-country platform: it invites an
-- operator to enter a converted figure, which would silently mis-band every
-- ticket-threshold rule (Bahrain's BENEFIT government MCCs split at 20 BHD).
--
-- The rename lives HERE, in the migration that creates the table, because
-- every later rate-card migration INSERTs into these columns and they all
-- re-run on each startup — the new name must exist before any of them fires.
--
-- Idempotent WITHOUT a DO block (this project's runner splits on semicolons
-- and cannot take dollar-quoting): re-adding the legacy column is a no-op on
-- the first pass and creates a throwaway empty column on later passes, so the
-- backfill always has something to read and the DROP always converges.
-- ---------------------------------------------------------------------------
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS min_ticket DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS max_ticket DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS min_ticket_aed DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS max_ticket_aed DECIMAL(19,4);
UPDATE interchange_rate_local
   SET min_ticket = COALESCE(min_ticket, min_ticket_aed),
       max_ticket = COALESCE(max_ticket, max_ticket_aed)
 WHERE (min_ticket IS NULL AND min_ticket_aed IS NOT NULL)
    OR (max_ticket IS NULL AND max_ticket_aed IS NOT NULL);
ALTER TABLE interchange_rate_local DROP COLUMN IF EXISTS min_ticket_aed;
ALTER TABLE interchange_rate_local DROP COLUMN IF EXISTS max_ticket_aed;

-- ---------------------------------------------------------------------------
-- 4. scheme_fee_rate -- destination x channel percentage
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheme_fee_rate (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   INT NOT NULL,
    dest        VARCHAR(20) NOT NULL,
    channel     VARCHAR(20) NOT NULL,
    fee_pct     DECIMAL(9, 6) NOT NULL,
    UNIQUE (tenant_id, dest, channel)
);

-- ===========================================================================
-- SEED (tenant bank_short_code = 'ACQ')
-- ===========================================================================

-- MCC -> sector (base sectors + Auto dealers + REX Exchange House)
INSERT INTO mcc_sector_map (tenant_id, mcc, sector)
SELECT t.tenant_id, v.mcc, v.sector
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('8211','Edu'),('8220','Edu'),('8241','Edu'),('8244','Edu'),('8249','Edu'),('8299','Edu'),
    ('5541','Gas'),('5542','Gas'),
    ('9211','Govt'),('9222','Govt'),('9223','Govt'),('9311','Govt'),('9399','Govt'),
    ('4111','Govt'),('4112','Govt'),('4121','Govt'),('4131','Govt'),
    ('4814','Govt'),('4816','Govt'),('4899','Govt'),('4900','Govt'),
    ('6513','RE'),
    ('4784','Trans'),('7523','Trans'),
    ('5511','Auto'),('5521','Auto'),('5551','Auto'),('5561','Auto'),
    ('5571','Auto'),('5592','Auto'),('5598','Auto'),('5599','Auto'),
    ('6051','REX'),('4829','REX')
) AS v(mcc, sector)
-- NOT EXISTS guard, not ON CONFLICT: V2026_07_31_02 drops the inline
-- UNIQUE (tenant_id, mcc) and re-keys the table by country_code, after
-- which ON CONFLICT (tenant_id, mcc) has no arbiter index and every
-- re-run of this file (spring.sql.init runs it on each boot) aborts
-- startup. Same trap documented for the scheme-fee grid below.
WHERE NOT EXISTS (
    SELECT 1 FROM mcc_sector_map x
    WHERE x.tenant_id = t.tenant_id AND x.mcc = v.mcc
);

-- Scheme fee grid
-- NOTE: uses a WHERE NOT EXISTS guard rather than ON CONFLICT. On a DB that has
-- already run the later part of this migration (section that drops the inline
-- (tenant,dest,channel) UNIQUE constraint and replaces it with the 4-col unique
-- INDEX uq_scheme_fee_rate_key incl. scheme_group), CREATE TABLE IF NOT EXISTS
-- above is a no-op and the 3-col constraint no longer exists, so
-- ON CONFLICT (tenant_id, dest, channel) fails with "no unique or exclusion
-- constraint matching the ON CONFLICT specification". A NOT EXISTS guard is
-- idempotent regardless of which unique key the table currently carries.
INSERT INTO scheme_fee_rate (tenant_id, dest, channel, fee_pct)
SELECT t.tenant_id, v.dest, v.channel, v.fee_pct
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('DOMESTIC','POS',       0.007500),
    ('DOMESTIC','ECOM',      0.001200),
    ('INTERNATIONAL','POS',  0.009000),
    ('INTERNATIONAL','ECOM', 0.009000)
) AS v(dest, channel, fee_pct)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_fee_rate x
    WHERE x.tenant_id = t.tenant_id AND x.dest = v.dest AND x.channel = v.channel
);

-- Interchange rates. Guarded seed: only when the tenant has ZERO rows
-- (nullable match columns make ON CONFLICT unusable -- see header).
INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, v.priority, v.dest, v.channel, v.scheme_group, v.card_type, v.tier, v.mcc_sector,
       v.min_ticket, v.max_ticket, v.interchange_pct, v.cap_amount, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    (1,  'INTERNATIONAL', NULL,   NULL,          NULL,      NULL,       NULL,    NULL,      NULL,      0.018500, NULL, 'Intl flat 1.85'),

    (10, 'DOMESTIC', NULL,   NULL,          'DEBIT',   NULL,       NULL,    NULL,      NULL,      0.007500, 37.5, 'Local debit 0.75 (cap 37.5)'),
    (10, 'DOMESTIC', NULL,   NULL,          'PREPAID', NULL,       NULL,    NULL,      NULL,      0.007500, 37.5, 'Local prepaid 0.75 (cap 37.5)'),

    (10, 'DOMESTIC', NULL,   'Visa',        'CREDIT',  'Standard', NULL,    NULL,      NULL,      0.011500, NULL, 'Local Visa Std 1.15'),
    (10, 'DOMESTIC', NULL,   'Visa',        'CREDIT',  'Premium',  NULL,    NULL,      NULL,      0.018000, NULL, 'Local Visa Prem 1.80'),
    (10, 'DOMESTIC', NULL,   'MasterCard',  'CREDIT',  'Standard', NULL,    NULL,      NULL,      0.012500, NULL, 'Local MC Std 1.25'),
    (10, 'DOMESTIC', NULL,   'MasterCard',  'CREDIT',  'Premium',  NULL,    NULL,      NULL,      0.018000, NULL, 'Local MC Prem 1.80'),

    (11, 'DOMESTIC', NULL,   'JCB',         NULL,      NULL,       NULL,    NULL,      NULL,      0.017500, NULL, 'Local JCB flat 1.75'),
    (11, 'DOMESTIC', NULL,   'UnionPay',    NULL,      NULL,       NULL,    NULL,      NULL,      0.017500, NULL, 'Local UPI flat 1.75'),

    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'Govt',  NULL,      NULL,      0.003500, NULL, 'Sector Govt 0.35'),
    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'Gas',   NULL,      NULL,      0.005000, NULL, 'Sector Gas 0.50'),
    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'RE',    NULL,      NULL,      0.003000, NULL, 'Sector RE 0.30'),
    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'Edu',   NULL,      NULL,      0.003000, NULL, 'Sector Edu 0.30'),
    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'Trans', NULL,      NULL,      0.004500, NULL, 'Sector Trans 0.45'),

    (30, 'DOMESTIC', 'POS',  NULL,          'CREDIT',  NULL,       'Auto',  NULL,      36700.00,  0.015000, NULL, 'Auto POS <36700 -> 1.50'),
    (30, 'DOMESTIC', 'POS',  NULL,          'CREDIT',  NULL,       'Auto',  36700.00,  NULL,      0.009000, NULL, 'Auto POS >=36700 -> 0.90'),
    (30, 'DOMESTIC', 'ECOM', NULL,          'CREDIT',  NULL,       'Auto',  NULL,      36700.00,  0.013000, NULL, 'Auto ECOM <36700 -> 1.30'),
    (30, 'DOMESTIC', 'ECOM', NULL,          'CREDIT',  NULL,       'Auto',  36700.00,  NULL,      0.005000, NULL, 'Auto ECOM >=36700 -> 0.50'),

    (30, 'DOMESTIC', 'POS',  'MasterCard',  'CREDIT',  NULL,       'REX',   NULL,      3670.00,   0.006500, NULL, 'MC REX POS <3670 -> 0.65'),
    (30, 'DOMESTIC', 'POS',  'MasterCard',  'CREDIT',  NULL,       'REX',   3670.00,   NULL,      0.011500, NULL, 'MC REX POS >=3670 -> 1.15'),
    (30, 'DOMESTIC', 'ECOM', 'MasterCard',  'CREDIT',  NULL,       'REX',   NULL,      3670.00,   0.005000, NULL, 'MC REX ECOM <3670 -> 0.50'),
    (30, 'DOMESTIC', 'ECOM', 'MasterCard',  'CREDIT',  NULL,       'REX',   3670.00,   NULL,      0.011500, NULL, 'MC REX ECOM >=3670 -> 1.15')
) AS v(priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
       min_ticket, max_ticket, interchange_pct, cap_amount, label)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x WHERE x.tenant_id = t.tenant_id);

-- ===========================================================================
-- ECOM FLAT FEE + POS SCHEME-RATE RE-TUNE (added 2026-07-06)
--
-- 1. fact_transaction.ecom_fee: 0.18 (settlement ccy) flat per ECOM txn,
--    computed in stagingToFactStep AFTER scheme_fee. Net revenue in every
--    rollup is now msf - interchange - scheme_fee - ecom_fee.
-- 2. total_ecom_fee: reporting column on the finance-facing summary tables
--    that already carry total_scheme_fee (bank / merchant / terminal).
-- 3. UPDATE re-tunes POS scheme rates on ALREADY-SEEDED tenants (the seed
--    above is NOT EXISTS/ON CONFLICT-guarded, so it no-ops on a seeded DB).
--    Idempotent: sets absolute values, safe to re-run.
-- All statements idempotent + splitter-safe (no $$).
-- ===========================================================================
ALTER TABLE fact_transaction   ADD COLUMN IF NOT EXISTS ecom_fee       DECIMAL(19, 4);
ALTER TABLE sum_daily_bank     ADD COLUMN IF NOT EXISTS total_ecom_fee DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS total_ecom_fee DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_ecom_fee DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_monthly_bank   ADD COLUMN IF NOT EXISTS total_ecom_fee DECIMAL(19, 2) DEFAULT 0;

-- (POS scheme-rate re-tune from 2026-07-06 was SUPERSEDED by the scheme-aware
--  reseed section below (2026-07-06b). The two flat POS UPDATEs are removed to
--  avoid clobbering the new per-scheme Visa/MC rows.)

-- ===========================================================================
-- SCHEME-AWARE SCHEME FEE (added 2026-07-06b)
--
-- Scheme fee now depends on CARD SCHEME GROUP, not just destination x channel:
--   VISA / MASTERCARD:
--     DOMESTIC POS  0.11%   DOMESTIC ECOM  0.13%
--     INTERNATIONAL POS 0.75%   INTERNATIONAL ECOM 0.90%
--   JCB / UNIONPAY(UPI): flat 0.05% (any destination, any channel)
--
-- This requires a scheme_group dimension on scheme_fee_rate. The old table was
-- keyed (tenant,dest,channel); we add scheme_group and re-key
-- (tenant,dest,channel,scheme_group). scheme_group holds the ref_card_scheme
-- group_name ('Visa','MasterCard','JCB','UnionPay'); a NULL scheme_group row is
-- a wildcard fallback (any scheme) so ingestion never breaks on an unmapped
-- scheme. The ingest UPDATE prefers a scheme-specific row over the wildcard.
--
-- Idempotent + splitter-safe (no dollar-quoting). Reseeds the ACQ scheme grid
-- to the new matrix (DELETE old ACQ rows + INSERT), so re-running lands the
-- same state.
-- ===========================================================================

-- 1. Add scheme_group; widen the unique key to include it.
ALTER TABLE scheme_fee_rate ADD COLUMN IF NOT EXISTS scheme_group VARCHAR(20);

-- Drop the old (tenant,dest,channel) unique constraint if present, replace with
-- a 4-col unique INDEX (index-based uniqueness sidesteps constraint-name
-- guessing; COALESCE folds NULL scheme_group into the wildcard key).
ALTER TABLE scheme_fee_rate DROP CONSTRAINT IF EXISTS scheme_fee_rate_tenant_id_dest_channel_key;
DROP INDEX IF EXISTS uq_scheme_fee_rate_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_scheme_fee_rate_key
    ON scheme_fee_rate (tenant_id, dest, channel, COALESCE(scheme_group, ''));

-- 2. Reseed ACQ scheme grid to the scheme-aware matrix.
DELETE FROM scheme_fee_rate sfr
USING tenant t
WHERE sfr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ';

INSERT INTO scheme_fee_rate (tenant_id, dest, channel, scheme_group, fee_pct)
SELECT t.tenant_id, v.dest, v.channel, v.scheme_group, v.fee_pct
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    -- VISA
    ('DOMESTIC','POS','Visa',            0.001100),
    ('DOMESTIC','ECOM','Visa',           0.001300),
    ('INTERNATIONAL','POS','Visa',       0.007500),
    ('INTERNATIONAL','ECOM','Visa',      0.009000),
    -- MASTERCARD
    ('DOMESTIC','POS','MasterCard',      0.001100),
    ('DOMESTIC','ECOM','MasterCard',     0.001300),
    ('INTERNATIONAL','POS','MasterCard', 0.007500),
    ('INTERNATIONAL','ECOM','MasterCard',0.009000),
    -- JCB flat 0.05 (all dest x channel)
    ('DOMESTIC','POS','JCB',             0.000500),
    ('DOMESTIC','ECOM','JCB',            0.000500),
    ('INTERNATIONAL','POS','JCB',        0.000500),
    ('INTERNATIONAL','ECOM','JCB',       0.000500),
    -- UNIONPAY / UPI flat 0.05 (all dest x channel)
    ('DOMESTIC','POS','UnionPay',        0.000500),
    ('DOMESTIC','ECOM','UnionPay',       0.000500),
    ('INTERNATIONAL','POS','UnionPay',   0.000500),
    ('INTERNATIONAL','ECOM','UnionPay',  0.000500)
) AS v(dest, channel, scheme_group, fee_pct);
