-- ============================================================================
-- UAT COMBINED MIGRATION 2026-09-04 — Net Spread / DCC / rentals + rate cards
--
-- Concatenation, in version order, of:
--   V2026_08_29_01__rentals.sql
--   V2026_08_29_02__rentals_menu.sql
--   V2026_08_29_03__mc_manual_rate_alignment.sql
--   V2026_08_29_04__commercial_card_type.sql
--   V2026_08_30_02__benefit_interchange_align_and_scheme_fee_bh.sql
--   V2026_08_31_01__dcc_revenue.sql
--   V2026_08_31_02__net_spread_menu.sql
--   V2026_09_01_01__scheme_billing_reference_menu.sql
--
-- EXCLUDED on purpose: V2026_08_30_01 (Egypt MC draft — awaiting BIN ruling).
--
-- Every statement is idempotent (IF NOT EXISTS / ON CONFLICT DO NOTHING /
-- NOT-EXISTS-guarded inserts / value-keyed updates), so re-running the whole
-- file is safe. Runs in ONE transaction: all-or-nothing.
--
-- Apply:  psql -h <uat-host> -U <user> -d <db> -f UAT_COMBINED_2026-09-04.sql
--
-- AFTER APPLYING:
--   1. Deploy the matching jars (commit 2aec09a) — the commercial card_class
--      and BIN-tier pricing need the new FeeComputationService.
--   2. Upload the DCC + rental files so fact_dcc_revenue / fact_rental fill
--      and AncillarySql populates the new summary columns.
--   3. Rate sections (29_03 / 29_04 / 30_02) change rate cards only — run
--      rebuild-summaries with reprice:true for the affected months so the
--      fact re-prices to the new rates.
-- ============================================================================

BEGIN;

-- Safety: the apply-log table (normally created by V2026_07_14_01) — guarded
-- so this file works even on a database that never ran that migration.
CREATE TABLE IF NOT EXISTS schema_migration_log (
    filename   VARCHAR(200) PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ############################################################################
-- ##  BEGIN V2026_08_29_01__rentals.sql
-- ############################################################################

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


-- ############################################################################
-- ##  BEGIN V2026_08_29_02__rentals_menu.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_29_02: Rentals screen menu entry.
--
-- /business/rentals — terminal/store/merchant rental charges from the
-- dedicated rental feed (fact_rental), level tabs driven by the tenant's
-- input_format (CMM = store only, AMS = merchant/store/terminal).
-- BUSINESS category, display_order 21 (next free after Local Debit Banks'
-- 20 from V2026_08_20_02).
--
-- The API is gated by @menuAccess.canAccess('/business/rentals'), so this
-- grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Rentals', '/business/rentals', 'Receipt', 'BUSINESS', 21
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/rentals');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/rentals'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/rentals'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;


-- ############################################################################
-- ##  BEGIN V2026_08_29_03__mc_manual_rate_alignment.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_29_03: align UAE + Bahrain Mastercard local interchange with the
--                 Mastercard MEA interchange manual (m_mea_inc_customer,
--                 effective 2026-08-04), BIN-weighted where the manual prices
--                 by card product tier.
--
-- SOURCE OF TRUTH (user-confirmed 2026-08-29):
--   * The MC manual governs the rows below. MPGS + PayOn e-com is 100% 3DS,
--     so ECOM prices at the Full-UCAF (non-tokenized) leg. No tokenized
--     incentive modeling (feed carries no token indicator).
--   * Benefit / Benefit QR rows: UNTOUCHED. Visa rows: UNTOUCHED (no Visa
--     schedule reviewed). International + scheme fees: untouched.
--
-- UAE (tenant ACQ)
-- ----------------
--   1. PREPAID POS 0.75% -> 1.00%. The manual's UAE debit&prepaid table
--      prices consumer prepaid General at 1.00% max AED 50.00 in EVERY
--      program, card-present included. (ECOM prepaid/debit 1.00% were already
--      seeded by V2026_07_07_04.)
--   2. Cap currency correction. V2026_07_07_04 multiplied the workbook caps
--      by 3.67 reading them as USD; the manual states them in AED
--      ("0.75%, max AED 37.50"). Caps revert to the manual's AED figures:
--        137.625 -> 37.50   183.50 -> 50.00   119.275 -> 32.50
--         91.75  -> 25.00     3.67 ->  1.00 (charity micro-cap, 8398/8661)
--      V2026_07_07_04's conversion section is edited in the same commit to
--      heal instead of re-convert, so startup sql.init cannot undo this.
--
-- BAHRAIN (country-level rows, MasterCard only)
-- ---------------------------------------------
--   The BH card was a card-type-agnostic blend (POS 1.75 / ECOM 1.90).
--   The manual prices BH by consumer product tier; the BH MC BIN book
--   (ref_bin_range, loaded 2026-08-25) has ZERO Std/Gold consumer credit
--   ranges - the base is Titanium/Platinum/World/World Elite.
--   3. The 720 priority-60 per-MCC rows that just repeat the general blend
--      (POS 1.75 / ECOM 1.90) are DELETED - they were redundant with the
--      priority-15 default and would out-priority the tier rows below.
--      Segment-priced MCC rows (education 0.65, govt 0.75, petrol 0.25 POS /
--      1.35 ECOM, ...) are kept: the manual's segment columns are constant
--      across tiers, so they stay tier-wildcard and keep winning at 60.
--   4. Tier rows at priority 30 (beat the 15-default, lose to segment 60):
--                          POS (Electronic)   ECOM (Full UCAF)
--        CREDIT  Standard      1.16%              1.35%
--        CREDIT  Premium       1.92%              1.74%   (Ti/Pl BIN-weighted)
--        CREDIT  Elite         2.07%              2.07%   (W/WE BIN-weighted)
--        PREPAID Standard      1.16%              1.35%
--        PREPAID Premium       1.95%              1.75%   (Prepaid Platinum)
--        PREPAID Elite         2.05%              2.05%   (Prepaid World)
--      The tier resolves from the BIN: FeeComputationService (2026-08-29)
--      maps the leading 6 PAN digits through ref_bin_range to a product
--      code and buckets it via ref_bin_product_tier (section 4a) - gated to
--      BH + blank card_product_code, so no other country's pricing moves.
--      Unmapped products fall to the legacy Premium bucket.
--   5. The priority-15 MC POS default rises 1.75% -> 1.90% (BIN-weighted
--      fallback incl. Standard ranges); it now only catches what the tier
--      rows cannot: DEBIT (no BH MC debit table exists in the manual -
--      pending business ruling) and unknown card types. MC ECOM default
--      stays 1.90 for the same fallback role.
--
-- Splitter-safe (no dollar-quoting). Idempotent: updates are keyed on the
-- exact old values, the delete matches nothing on re-run, the insert is
-- guarded. BACKFILL: fees compute at ingest - re-ingest affected months.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1+2. UAE - cap currency correction back to the manual's AED figures.
--      Keyed on the exact converted values so a re-run matches nothing.
-- ---------------------------------------------------------------------------
UPDATE interchange_rate_local ilr SET cap_amount = 37.50
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 137.625;

UPDATE interchange_rate_local ilr SET cap_amount = 50.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 183.50;

UPDATE interchange_rate_local ilr SET cap_amount = 32.50
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 119.275;

UPDATE interchange_rate_local ilr SET cap_amount = 25.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 91.75;

UPDATE interchange_rate_local ilr SET cap_amount = 1.00
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 3.67 AND ilr.mcc IN ('8398','8661');

-- UAE PREPAID POS: 0.75% -> 1.00% cap AED 50 (manual: prepaid is 1.00 even CP).
UPDATE interchange_rate_local ilr
   SET interchange_pct = 0.010000,
       cap_amount      = 50.00,
       label           = 'Local prepaid POS 1.00 (cap AED 50, MC manual Aug-2026)',
       source_note     = 'BUSINESS-APPROVED 2026-08-29: MC MEA manual - prepaid 1.00% max AED 50 all programs'
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 10 AND ilr.dest = 'DOMESTIC' AND ilr.channel = 'POS'
  AND ilr.card_type = 'PREPAID' AND ilr.scheme_group IS NULL AND ilr.mcc IS NULL
  AND ilr.interchange_pct = 0.007500;

-- ---------------------------------------------------------------------------
-- 3. BH - drop the redundant general-blend per-MCC MC rows (POS 1.75 /
--    ECOM 1.90). Segment-priced MCC rows survive (different pct values).
-- ---------------------------------------------------------------------------
DELETE FROM interchange_rate_local
WHERE country_code = 'BH' AND tenant_id IS NULL AND dest = 'DOMESTIC'
  AND scheme_group = 'MasterCard' AND priority = 60 AND mcc IS NOT NULL
  AND ( (channel = 'POS'  AND interchange_pct = 0.017500)
     OR (channel = 'ECOM' AND interchange_pct = 0.019000) );

-- The priority-40 ANY-SCHEME per-MCC fallback rows (higher-of-Visa/MC, meant
-- for domestic Amex etc.) are scheme wildcards, so they ALSO match MasterCard
-- and at priority 40 would shadow the tier rows below on those MCCs. The
-- general-blend ones (POS 1.75 / ECOM 1.90) are deleted for the same reason;
-- Amex keeps pricing identically via the priority-10 any-scheme defaults
-- (POS 1.75 / ECOM 1.90), and the segment-priced priority-40 rows survive.
DELETE FROM interchange_rate_local
WHERE country_code = 'BH' AND tenant_id IS NULL AND dest = 'DOMESTIC'
  AND scheme_group IS NULL AND priority = 40 AND mcc IS NOT NULL
  AND ( (channel = 'POS'  AND interchange_pct = 0.017500)
     OR (channel = 'ECOM' AND interchange_pct = 0.019000) );

-- ---------------------------------------------------------------------------
-- 4a. BIN product -> tier bucket map (read by FeeComputationService's
--     BIN-tier LATERAL, 2026-08-29). Consumer Mastercard products bucket to
--     the manual's tiers; commercial products bucket to Elite as a STOPGAP
--     (manual BH commercial General is 2.00-2.15%, closest to Elite's 2.07)
--     until a real COMMERCIAL card_type exists. Unmapped products fall to
--     the engine's legacy Premium fallback.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ref_bin_product_tier (
    product_code VARCHAR(5)  PRIMARY KEY,
    card_tier    VARCHAR(10) NOT NULL,   -- Standard / Premium / Elite
    note         VARCHAR(120)
);

INSERT INTO ref_bin_product_tier (product_code, card_tier, note) VALUES
  ('MCS', 'Standard', 'Mastercard Standard'),
  ('MCG', 'Standard', 'Mastercard Gold'),
  ('MCC', 'Standard', 'Mastercard Credit (mixed BIN)'),
  ('MCT', 'Premium',  'Mastercard Titanium'),
  ('MPL', 'Premium',  'Mastercard Platinum'),
  ('MCW', 'Elite',    'World Mastercard'),
  ('MWE', 'Elite',    'World Elite Mastercard'),
  ('MNW', 'Elite',    'New World Mastercard'),
  ('MRG', 'Standard', 'Prepaid Gold'),
  ('MPG', 'Standard', 'Prepaid Gold variant'),
  ('MGP', 'Standard', 'Gold Prepaid'),
  ('MRH', 'Premium',  'Prepaid Platinum'),
  ('MRW', 'Elite',    'Prepaid World'),
  ('MWP', 'Elite',    'World Prepaid'),
  ('MCO', 'Elite',    'Corporate - STOPGAP until COMMERCIAL card_type (manual 2.00%)'),
  ('MCB', 'Elite',    'Business - STOPGAP until COMMERCIAL card_type (manual 2.00%)'),
  ('MEO', 'Elite',    'Corporate Executive - STOPGAP (manual 2.00%)'),
  ('MEB', 'Elite',    'Executive Business - STOPGAP (manual 2.10%)'),
  ('MWB', 'Elite',    'World Business - STOPGAP (manual 2.15%)'),
  ('MWO', 'Elite',    'World Corporate - STOPGAP (manual 2.00%)'),
  ('MAB', 'Elite',    'World Elite Business - STOPGAP (manual 2.20%)'),
  ('MIO', 'Elite',    'Corporate variant - STOPGAP (manual 2.15%)'),
  ('MCP', 'Elite',    'Purchasing - STOPGAP (manual all-other 2.00%)')
ON CONFLICT (product_code) DO NOTHING;

-- Give the planner stats immediately. The fee-resolution query joins this
-- table inside a per-row LATERAL (FeeComputationService); a brand-new table
-- with reltuples = -1 (never analyzed) can push the planner into a bad plan
-- for the WHOLE fee pass. One ANALYZE on 23 rows is instant and load-bearing.
ANALYZE ref_bin_product_tier;

-- ---------------------------------------------------------------------------
-- 4b. BH - tier rows (priority 30), EXACT manual tiers now that the engine
--     resolves Standard/Premium/Elite from the BIN. An earlier revision of
--     this migration seeded Premium rows that folded Elite in (1.99/1.89
--     credit, 2.01/1.92 prepaid) because the resolver was two-tier; those
--     folded rows are removed here (keyed on their exact pct values) and the
--     per-tier set below replaces them. Guarded per row.
-- ---------------------------------------------------------------------------
-- Match the OLD folded rows by their full (card_type, channel, pct) tuple, NOT
-- by pct alone: the new credit-Premium-POS rate (0.019200 = 1.92%) collides with
-- the old prepaid-Premium-ECOM folded value (also 0.019200), so a pct-only
-- delete would churn — removing and re-inserting the new row every run. The
-- tuple form can only ever hit the four rows the previous revision seeded.
DELETE FROM interchange_rate_local
WHERE country_code = 'BH' AND tenant_id IS NULL AND priority = 30
  AND dest = 'DOMESTIC' AND scheme_group = 'MasterCard' AND tier = 'Premium'
  AND ( (card_type = 'CREDIT'  AND channel = 'POS'  AND interchange_pct = 0.019900)
     OR (card_type = 'CREDIT'  AND channel = 'ECOM' AND interchange_pct = 0.018900)
     OR (card_type = 'PREPAID' AND channel = 'POS'  AND interchange_pct = 0.020100)
     OR (card_type = 'PREPAID' AND channel = 'ECOM' AND interchange_pct = 0.019200) );

INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, source_note)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Standard', NULL, NULL, NULL::NUMERIC, NULL::NUMERIC, 0.011600, NULL::NUMERIC,
     'BH MC credit Standard POS 1.16 (manual Std/Gold electronic)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
     'BH MC credit Standard ECOM 1.35 (manual Std/Gold Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Premium',  NULL, NULL, NULL, NULL, 0.019200, NULL,
     'BH MC credit Premium POS 1.92 (BIN-wtd Titanium 1.80/Platinum 1.95)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Premium',  NULL, NULL, NULL, NULL, 0.017400, NULL,
     'BH MC credit Premium ECOM 1.74 (BIN-wtd Ti 1.70/Pl 1.75 Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'CREDIT',  'Elite',    NULL, NULL, NULL, NULL, 0.020700, NULL,
     'BH MC credit Elite POS 2.07 (BIN-wtd World 2.05/World Elite 2.10)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT',  'Elite',    NULL, NULL, NULL, NULL, 0.020700, NULL,
     'BH MC credit Elite ECOM 2.07 (BIN-wtd W 2.05/WE 2.10 Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer credit, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Standard', NULL, NULL, NULL, NULL, 0.011600, NULL,
     'BH MC prepaid Standard POS 1.16 (manual Std/Gold electronic)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Standard', NULL, NULL, NULL, NULL, 0.013500, NULL,
     'BH MC prepaid Standard ECOM 1.35 (manual Std/Gold Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Premium',  NULL, NULL, NULL, NULL, 0.019500, NULL,
     'BH MC prepaid Premium POS 1.95 (manual Prepaid Platinum electronic)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Premium',  NULL, NULL, NULL, NULL, 0.017500, NULL,
     'BH MC prepaid Premium ECOM 1.75 (manual Prepaid Platinum Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'PREPAID', 'Elite',    NULL, NULL, NULL, NULL, 0.020500, NULL,
     'BH MC prepaid Elite POS 2.05 (manual Prepaid World electronic)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'PREPAID', 'Elite',    NULL, NULL, NULL, NULL, 0.020500, NULL,
     'BH MC prepaid Elite ECOM 2.05 (manual Prepaid World Full UCAF)',
     'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH consumer prepaid, tier-wise')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
       label, source_note)
WHERE NOT EXISTS (
  SELECT 1 FROM interchange_rate_local x
  WHERE x.country_code = 'BH' AND x.tenant_id IS NULL AND x.priority = 30
    AND x.dest = 'DOMESTIC' AND x.channel = v.channel
    AND x.scheme_group = v.scheme_group AND x.card_type = v.card_type
    AND x.tier = v.tier
);

-- ---------------------------------------------------------------------------
-- 5. BH - MC domestic defaults (priority 15) KEEP their original blend
--    (POS 1.75 / ECOM 1.90). With CREDIT and PREPAID priced by the tier rows
--    above, these defaults now only catch MC DEBIT and unknown card types -
--    and the manual has NO BH intracountry MC consumer debit table, so there
--    is no basis to move debit off the original blend. (An earlier revision
--    of this migration raised the POS default to 1.90; this sets it back.)
-- ---------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET interchange_pct = 0.017500,
       label           = 'BH MasterCard POS domestic default (debit/unknown fallback)',
       source_note     = 'BUSINESS-APPROVED 2026-08-29: debit/unknown fallback kept at workbook blend - no MC manual BH debit table'
 WHERE country_code = 'BH' AND tenant_id IS NULL AND priority = 15
   AND dest = 'DOMESTIC' AND channel = 'POS' AND scheme_group = 'MasterCard'
   AND interchange_pct IN (0.017500, 0.019000)
   -- Fire only when something actually differs (a prior revision's 1.90, or the
   -- old label), so re-runs are true no-ops rather than rewriting identical values.
   AND (interchange_pct = 0.019000
        OR label IS DISTINCT FROM 'BH MasterCard POS domestic default (debit/unknown fallback)');

-- Refresh planner stats on the tables this migration reshaped. It deleted
-- ~1462 interchange_rate_local rows and inserted the tier set; without an
-- ANALYZE the planner keeps the old row estimates until autoanalyze fires,
-- which can degrade the per-row interchange lateral's plan in the fee pass.
-- Cheap on these small tables.
ANALYZE interchange_rate_local;
ANALYZE scheme_fee_rate;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_29_03__mc_manual_rate_alignment.sql')
ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ##  BEGIN V2026_08_29_04__commercial_card_type.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_29_04: a REAL commercial card type for UAE + Bahrain Mastercard.
--
-- Until now, commercial-product BINs (MCO/MCB/MEO/MEB/MWB/MWO/MAB/MIO/BPD/MCP)
-- were a STOPGAP: V2026_08_29_03 bucketed them to the consumer 'Elite' tier, so
-- a corporate card priced at the consumer World-Elite rate (~2.07%) instead of
-- the manual's commercial schedule. The MC MEA manual (2026-08-04) prices
-- commercial by PRODUCT CODE, on its own General band:
--   MCO, MEO, MWO, MCB, MCP  -> 2.00%   (and the "all other commercial" catch-all)
--   MEB                       -> 2.10%
--   MIO, BPD, MWB             -> 2.15%
--   MAB                       -> 2.20%
-- (UAE and Bahrain share these General bands.)
--
-- MODEL
-- -----
-- 1. ref_bin_product_tier gains a card_class column. Consumer products stay
--    card_class='CONSUMER'; the commercial codes flip to 'COMMERCIAL' and their
--    card_tier becomes the rate-band label Comm200/Comm210/Comm215/Comm220.
--    FeeComputationService's BIN lateral now carries card_class, and when it is
--    'COMMERCIAL' the card prices as card_type='COMMERCIAL' at that band.
-- 2. interchange_rate_local gets country-level (tenant_id NULL) COMMERCIAL rows
--    for AE and BH: the four General bands, priority 30, both channels (the
--    manual's commercial General has no electronic/UCAF split — one rate).
-- 3. Segment overrides that would otherwise MIS-price commercial materially:
--      BH  petrol (MCC 5541/5542): 1.00%  (else the kept consumer petrol row,
--                                          priority 60 @0.25%, would win)
--      UAE petrol (mcc_sector Gas): 0.50% (else the priority-30 General @2.00%
--                                          would shadow the consumer Gas sector)
--      UAE govt   (mcc_sector Govt): 0.50%(same reason; manual UAE commercial
--                                          GvtSvc = 0.50%)
--    BH commercial govt already resolves to 0.75% via the kept priority-60 govt
--    rows (= the manual's commercial GvtServ), so it needs no override.
--
-- DEFERRED (documented, fall to commercial General 2.00-2.20 per the manual's
-- "for rates not specified, the general rates apply" catch-all — a defensible
-- over-approximation, never under-pricing vs consumer):
--   * UAE Commercial Emerging Market (0.80%), Telecom/Computer (0.50%),
--     Real Estate & Wholesale (USD 5k/15k ticket-tiered).
--   * Charities: USD 0.25 flat/txn (the standing flat-fee-model gap).
--   * MDT (manual lists it commercial 2.00% but the BIN file types it DEBIT):
--     left as-is pending a business ruling on its class.
--   * The MBG/MBJ/MKF/MKG/MKH BIN family: not in the manual's table; stays
--     consumer until the T067 product-code doc classifies it.
--
-- Splitter-safe (no dollar-quoting), idempotent. BACKFILL: re-ingest affected
-- months (fees compute at ingest). REQUIRES the matching batch build (the
-- FeeComputationService card_class change) to price commercial at all.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. ref_bin_product_tier.card_class + reclassify the commercial codes.
-- ---------------------------------------------------------------------------
ALTER TABLE ref_bin_product_tier ADD COLUMN IF NOT EXISTS card_class VARCHAR(12) NOT NULL DEFAULT 'CONSUMER';

UPDATE ref_bin_product_tier SET card_class = 'COMMERCIAL', card_tier = v.band
FROM ( VALUES
  ('MCO','Comm200'), ('MEO','Comm200'), ('MWO','Comm200'), ('MCB','Comm200'), ('MCP','Comm200'),
  ('MEB','Comm210'),
  ('MIO','Comm215'), ('BPD','Comm215'), ('MWB','Comm215'),
  ('MAB','Comm220')
) AS v(code, band)
WHERE ref_bin_product_tier.product_code = v.code
  AND (ref_bin_product_tier.card_class <> 'COMMERCIAL' OR ref_bin_product_tier.card_tier <> v.band);

-- MIO/BPD are commercial but were not in the earlier stopgap seed — add them.
INSERT INTO ref_bin_product_tier (product_code, card_tier, card_class, note) VALUES
  ('MIO', 'Comm215', 'COMMERCIAL', 'Corporate variant (manual 2.15%)'),
  ('BPD', 'Comm215', 'COMMERCIAL', 'Business Prepaid/Debit corporate (manual 2.15%)')
ON CONFLICT (product_code) DO NOTHING;

ANALYZE ref_bin_product_tier;

-- ---------------------------------------------------------------------------
-- 2. COMMERCIAL General bands (AE + BH), country-level, priority 30.
--    3. plus the petrol/govt segment overrides at priority 35 / 65.
-- ---------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, source_note)
SELECT v.* FROM ( VALUES
  -- UAE General bands
  (NULL::INT, 'AE', 'AED', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm200', NULL, NULL, NULL::NUMERIC, NULL::NUMERIC, 0.020000, NULL::NUMERIC, 'UAE MC commercial 2.00 (MCO/MEO/MWO/MCB/MCP)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm200', NULL, NULL, NULL, NULL, 0.020000, NULL, 'UAE MC commercial 2.00 (MCO/MEO/MWO/MCB/MCP)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm210', NULL, NULL, NULL, NULL, 0.021000, NULL, 'UAE MC commercial 2.10 (MEB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm210', NULL, NULL, NULL, NULL, 0.021000, NULL, 'UAE MC commercial 2.10 (MEB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm215', NULL, NULL, NULL, NULL, 0.021500, NULL, 'UAE MC commercial 2.15 (MIO/BPD/MWB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm215', NULL, NULL, NULL, NULL, 0.021500, NULL, 'UAE MC commercial 2.15 (MIO/BPD/MWB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm220', NULL, NULL, NULL, NULL, 0.022000, NULL, 'UAE MC commercial 2.20 (MAB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  (NULL, 'AE', 'AED', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm220', NULL, NULL, NULL, NULL, 0.022000, NULL, 'UAE MC commercial 2.20 (MAB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial General'),
  -- UAE segment overrides (mcc_sector), priority 35, tier-wildcard (all bands)
  (NULL, 'AE', 'AED', 35, 'DOMESTIC', NULL, 'MasterCard', 'COMMERCIAL', NULL, 'Gas',  NULL, NULL, NULL, 0.005000, NULL, 'UAE MC commercial petrol 0.50', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial Petrol'),
  (NULL, 'AE', 'AED', 35, 'DOMESTIC', NULL, 'MasterCard', 'COMMERCIAL', NULL, 'Govt', NULL, NULL, NULL, 0.005000, NULL, 'UAE MC commercial govt 0.50', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 UAE commercial GvtSvc'),
  -- Bahrain General bands
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm200', NULL, NULL, NULL, NULL, 0.020000, NULL, 'BH MC commercial 2.00 (MCO/MEO/MWO/MCB/MCP)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm200', NULL, NULL, NULL, NULL, 0.020000, NULL, 'BH MC commercial 2.00 (MCO/MEO/MWO/MCB/MCP)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm210', NULL, NULL, NULL, NULL, 0.021000, NULL, 'BH MC commercial 2.10 (MEB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm210', NULL, NULL, NULL, NULL, 0.021000, NULL, 'BH MC commercial 2.10 (MEB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm215', NULL, NULL, NULL, NULL, 0.021500, NULL, 'BH MC commercial 2.15 (MIO/BPD/MWB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm215', NULL, NULL, NULL, NULL, 0.021500, NULL, 'BH MC commercial 2.15 (MIO/BPD/MWB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'POS',  'MasterCard', 'COMMERCIAL', 'Comm220', NULL, NULL, NULL, NULL, 0.022000, NULL, 'BH MC commercial 2.20 (MAB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  (NULL, 'BH', 'BHD', 30, 'DOMESTIC', 'ECOM', 'MasterCard', 'COMMERCIAL', 'Comm220', NULL, NULL, NULL, NULL, 0.022000, NULL, 'BH MC commercial 2.20 (MAB)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial General'),
  -- Bahrain petrol override (per-MCC, priority 65 to beat the kept consumer petrol @0.25 at priority 60)
  (NULL, 'BH', 'BHD', 65, 'DOMESTIC', NULL, 'MasterCard', 'COMMERCIAL', NULL, NULL, '5541', NULL, NULL, 0.010000, NULL, 'BH MC commercial petrol 1.00 (5541)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial Petrol'),
  (NULL, 'BH', 'BHD', 65, 'DOMESTIC', NULL, 'MasterCard', 'COMMERCIAL', NULL, NULL, '5542', NULL, NULL, 0.010000, NULL, 'BH MC commercial petrol 1.00 (5542)', 'BUSINESS-APPROVED 2026-08-29: MC MEA manual Aug-2026 BH commercial Petrol')
) AS v(tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
       card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
       label, source_note)
WHERE NOT EXISTS (
  SELECT 1 FROM interchange_rate_local x
  WHERE x.country_code = v.country_code AND x.tenant_id IS NULL AND x.priority = v.priority
    AND x.dest = 'DOMESTIC' AND x.card_type = 'COMMERCIAL'
    AND x.scheme_group = v.scheme_group
    AND COALESCE(x.channel,'*') = COALESCE(v.channel,'*')
    AND COALESCE(x.tier,'*') = COALESCE(v.tier,'*')
    AND COALESCE(x.mcc_sector,'*') = COALESCE(v.mcc_sector,'*')
    AND COALESCE(x.mcc,'*') = COALESCE(v.mcc,'*')
);

ANALYZE interchange_rate_local;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_29_04__commercial_card_type.sql')
ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ##  BEGIN V2026_08_30_02__benefit_interchange_align_and_scheme_fee_bh.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_30_02: BENEFIT (Bahrain) — align interchange to the official Benefit
--                 acquirer schedule + add the 0.60% Benefit scheme fee.
--
-- SOURCE OF TRUTH
-- ---------------
-- The Benefit "Payment Gateway Transaction" (ECOM) and "Purchase" (POS) fee
-- schedules, user-supplied 2026-08-30. Business instruction: take ONLY the
-- ACQUIRER column as our interchange fee, and configure the Benefit SCHEME fee
-- separately at 0.60%. Bahrain only; applies to scheme_group 'Benefit' AND its
-- QR twin 'Benefit QR' (which mirrors the Benefit rate card).
--
-- WHAT CHANGES (all "corrected in place" per business — history re-prices to the
-- new values on the next reprice/re-ingest; NOT effective-dated):
--
--   1. EXCHANGE HOUSES (MCC 6051, 4829): acquirer rate 0.60% -> 0.45%.
--      The V2026_08_08_05 seed carried 0.60% as a FLAGGED ASSUMPTION; the
--      official schedule says 0.45% (cap BHD 0.029 unchanged, and correct).
--
--   2. PETROL / FUEL: schedule footnote lists MCC 5172, 5983, 5541, 5542. The
--      seed only had 5541/5542. Add 5172 and 5983 at the same 0.60% cap 0.085.
--
--   3. INTERNATIONAL: acquirer rate 1.10% -> 1.00% (flat BHD 0.100 unchanged),
--      per the 2026-08-30 correction ("1% + 0.1 BHD", not 1.10%).
--
--   4. SCHEME FEE (new): Benefit / Benefit QR BH DOMESTIC POS + ECOM = 0.06%.
--      Until now Benefit had no scheme_fee_rate row and silently took the BH
--      any-scheme wildcard (0.11% POS / 0.14% ECOM). A scheme-specific row beats
--      the wildcard in the fee engine's scheme LATERAL, so these now govern.
--
--   5. MCC LISTS made an exact replica of the manual (user 2026-08-30):
--        Govt     footnote 1 : 9211, 9222, 9311, 9399, 9402 -> DROP 9223 (seed extra)
--        Charity  footnote 4 : 8398                          -> DROP 8661 (seed extra)
--        Petrol   footnote 3 : 5172, 5983, 5541, 5542        (5172/5983 added in #2)
--        Exchange footnote 2 : 4829, 6051                     (already correct)
--      A dropped MCC has no other Benefit row, so it falls back to the 0.60%
--      standard purchase rate.
--
--   6. GOVERNMENT CHANNEL SPLIT — the manual charges the 0.100/0.200 government
--      flats on the POS "Purchase" schedule only; the ECOM "Payment Gateway"
--      schedule shows government as an all-blank row (no fees). So the flats are
--      restricted to POS, and government on ECOM becomes a zero row per MCC (same
--      reading as the manual's all-blank charity row = 0).
--
-- DELIBERATELY NOT CHANGED / CANNOT REPLICATE FROM MCC ALONE:
--   * Charity acquirer fee stays 0 (manual Acquirer column is '-'; footnote-4's
--     0.001/txn is not an acquirer charge). MCC 8398 only, after the drop above.
--   * PSP Stored-Value Account Top-Up (ECOM acquirer 0.056 flat), Issuer Not
--     Available, ON-US Contactless from wallet — these are feed SCENARIOS, not
--     MCC categories, so they need a transaction-type/identifier from the feed
--     before they can be seeded. Left out until that mapping is provided.
--   * International scheme fee — still the wildcard (0.75% POS / 0.90% ECOM);
--     no Benefit-specific international scheme fee was requested.
--
-- Splitter-safe (no dollar-quoting). Idempotent (UPDATEs set absolute values;
-- INSERTs guarded by NOT EXISTS) so it is safe to list in schema-locations and
-- safe to re-run via psql on prod.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Exchange houses 0.60% -> 0.45% (Benefit + Benefit QR). Cap 0.029 unchanged.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET interchange_pct = 0.004500,
       label = REPLACE(COALESCE(label,''), '0.60%', '0.45%')
 WHERE country_code = 'BH'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND dest = 'DOMESTIC'
   AND mcc IN ('6051', '4829');

-- ----------------------------------------------------------------------------
-- 2. Petrol / fuel — add MCC 5172, 5983 at 0.60% cap 0.085 (Benefit + QR).
--    Mirrors the existing 5541/5542 petrol rows (priority 70, channel-wildcard).
-- ----------------------------------------------------------------------------
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, rate_status)
SELECT NULL, 'BH', 'BHD', 70, 'DOMESTIC', NULL, sg.scheme_group,
       NULL, NULL, NULL, m.mcc, NULL, NULL, 0.006000, 0.085,
       sg.pfx || ' Petrol ' || m.mcc || ' 0.60% cap 0.085', 'APPROVED'
FROM (VALUES ('Benefit', 'BENEFIT'), ('Benefit QR', 'BENEFIT QR')) AS sg(scheme_group, pfx)
CROSS JOIN (VALUES ('5172'), ('5983')) AS m(mcc)
WHERE NOT EXISTS (
    SELECT 1 FROM interchange_rate_local x
    WHERE x.country_code = 'BH' AND x.scheme_group = sg.scheme_group
      AND x.dest = 'DOMESTIC' AND x.mcc = m.mcc);

-- ----------------------------------------------------------------------------
-- 3. International interchange 1.10% -> 1.00% (Benefit + QR). Flat 0.100 stays.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET interchange_pct = 0.010000,
       label = REPLACE(COALESCE(label,''), '1.10%', '1.00%'),
       source_note = 'Benefit international 1.00% + BHD 0.100 (2026-08-30 correction from 1.10%).'
 WHERE country_code = 'BH'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND dest = 'INTERNATIONAL';

-- ----------------------------------------------------------------------------
-- 4. Benefit scheme fee 0.06% — BH DOMESTIC, POS + ECOM (Benefit + Benefit QR).
--    Country-level (tenant_id NULL). APPROVED so it prices immediately; a
--    scheme-specific row outranks the NULL wildcard in the scheme LATERAL.
--    flat_fee 0 (pure percentage). Insert-if-absent then converge the value, so
--    a re-run (or any earlier draft value) always lands on 0.000600.
-- ----------------------------------------------------------------------------
INSERT INTO scheme_fee_rate
    (tenant_id, country_code, dest, channel, scheme_group, fee_pct, flat_fee, rate_status, source_note)
SELECT NULL, 'BH', 'DOMESTIC', ch.channel, sg.scheme_group, 0.000600, 0, 'APPROVED',
       'BENEFIT scheme fee 0.06% (business-supplied 2026-08-30), BH domestic.'
FROM (VALUES ('Benefit'), ('Benefit QR')) AS sg(scheme_group)
CROSS JOIN (VALUES ('POS'), ('ECOM')) AS ch(channel)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_fee_rate x
    WHERE x.country_code = 'BH' AND x.dest = 'DOMESTIC'
      AND x.channel = ch.channel AND x.scheme_group = sg.scheme_group);

UPDATE scheme_fee_rate
   SET fee_pct = 0.000600, flat_fee = 0, rate_status = 'APPROVED'
 WHERE country_code = 'BH' AND dest = 'DOMESTIC'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND channel IN ('POS', 'ECOM');

-- ----------------------------------------------------------------------------
-- 5. MCC-list replica of the manual: drop the two seed MCCs absent from the
--    schedule footnotes — 9223 (govt) and 8661 (charity). Neither carries any
--    other Benefit row, so both fall back to the 0.60% standard purchase rate.
--    Benefit + Benefit QR.
-- ----------------------------------------------------------------------------
DELETE FROM interchange_rate_local
 WHERE country_code = 'BH'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND dest = 'DOMESTIC'
   AND mcc IN ('9223', '8661');

-- ----------------------------------------------------------------------------
-- 6. Government channel split — flats on POS only; ECOM government = 0.
-- 6a. Restrict the existing government flat rows (channel-wildcard) to POS.
--     Guard interchange_pct = 1.000000 so only the flat-fee govt rows are hit.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET channel = 'POS'
 WHERE country_code = 'BH'
   AND scheme_group IN ('Benefit', 'Benefit QR')
   AND dest = 'DOMESTIC'
   AND channel IS NULL
   AND mcc IN ('9211', '9222', '9311', '9399', '9402')
   AND interchange_pct = 1.000000;

-- 6b. Government on ECOM = 0 (manual's all-blank ECOM government row). One zero
--     row per MCC per scheme; priority 70 so it beats the 0.60% base on ECOM.
INSERT INTO interchange_rate_local
    (tenant_id, country_code, cap_currency_code, priority, dest, channel, scheme_group,
     card_type, tier, mcc_sector, mcc, min_ticket, max_ticket, interchange_pct, cap_amount,
     label, rate_status)
SELECT NULL, 'BH', 'BHD', 70, 'DOMESTIC', 'ECOM', sg.scheme_group,
       NULL, NULL, NULL, m.mcc, NULL, NULL, 0.000000, NULL,
       sg.pfx || ' Govt ' || m.mcc || ' ECOM zero', 'APPROVED'
FROM (VALUES ('Benefit', 'BENEFIT'), ('Benefit QR', 'BENEFIT QR')) AS sg(scheme_group, pfx)
CROSS JOIN (VALUES ('9211'), ('9222'), ('9311'), ('9399'), ('9402')) AS m(mcc)
WHERE NOT EXISTS (
    SELECT 1 FROM interchange_rate_local x
    WHERE x.country_code = 'BH' AND x.scheme_group = sg.scheme_group
      AND x.dest = 'DOMESTIC' AND x.channel = 'ECOM' AND x.mcc = m.mcc);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_30_02__benefit_interchange_align_and_scheme_fee_bh.sql')
ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- ##  BEGIN V2026_08_31_01__dcc_revenue.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_31_01: DCC revenue feed — staging + fact + ancillary summary columns.
--
-- DCC revenue arrives in a DEDICATED file at STORE (SID) level, through the
-- same three channels as rentals: screen upload, Server File Processor, and
-- the scheduled integration pull. DccRevenueJobConfig (dccLoadJob /
-- dbPullDccJob) stages rows here and applies them.
--
-- File shape (header-name mapped, order-independent):
--   SID, Tenant Id, Merchant Share, Acquirer Share, Date
-- Amounts are tenant base currency, MAJOR units. "Tenant Id" (bank short
-- code, institution id, or numeric tenant id) is VALIDATED against the
-- uploading tenant — a mismatched row is REJECTED, it never routes data.
--
-- REPLACE-BY-DATE SEMANTICS (decision 2026-08-31, unlike rentals' dedupe):
-- the apply step DELETEs fact_dcc_revenue for exactly the (tenant, dates)
-- present in the file, then inserts fresh — so re-uploading a corrected day
-- fully supersedes the previous numbers. Idempotent on repeat upload.
--
-- ANCILLARY SUMMARY COLUMNS: sum_daily_merchant and sum_daily_finance_rollup
-- gain dcc_acquirer / dcc_merchant / rental_amount so every dashboard reading
-- the summary layer can show Net Spread
--   (= total_margin + dcc_acquirer + rental_amount)
-- without joining facts. The columns are ALWAYS recomputed from
-- fact_dcc_revenue / fact_rental by AncillarySql (acquira-common) — after
-- every summary rebuild and after every DCC/rental apply — never carried
-- forward. net_spread itself is derived at read time, never stored.
-- Grain rule: dimensionally-sliced summaries (sum_daily_full, terminal,
-- monthly_card, ...) deliberately do NOT get these columns — a store-level
-- charge cannot be honestly attributed to a scheme/card/destination slice.
--
-- Idempotent; splitter-safe (no $$). Listed in spring.sql.init.schema-locations
-- for dev; apply once via psql on prod.
-- ============================================================================

CREATE TABLE IF NOT EXISTS stg_dcc_revenue_raw (
    raw_id         BIGSERIAL PRIMARY KEY,
    tenant_id      INT,
    file_id        BIGINT,
    load_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status         VARCHAR(20) DEFAULT 'PENDING',  -- PENDING|PROCESSED|REJECTED|UNMATCHED
    error_message  TEXT,

    sid            VARCHAR(50),
    file_tenant_id VARCHAR(50),                    -- as filed; validated, never trusted
    merchant_share DECIMAL(19,4),
    acquirer_share DECIMAL(19,4),
    payment_date   DATE
);

CREATE INDEX IF NOT EXISTS idx_stg_dcc_tenant ON stg_dcc_revenue_raw (tenant_id, status);

ALTER TABLE stg_dcc_revenue_raw ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON stg_dcc_revenue_raw;
CREATE POLICY tenant_isolation_policy ON stg_dcc_revenue_raw
    USING (tenant_id = get_current_tenant());

CREATE TABLE IF NOT EXISTS fact_dcc_revenue (
    dcc_id         BIGSERIAL PRIMARY KEY,
    tenant_id      INT NOT NULL,
    merchant_id    BIGINT,
    store_id       BIGINT,
    sid            VARCHAR(50),
    merchant_share DECIMAL(19,4) NOT NULL,
    acquirer_share DECIMAL(19,4) NOT NULL,
    payment_date   DATE NOT NULL,
    file_id        BIGINT,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fact_dcc_tenant_date
    ON fact_dcc_revenue (tenant_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_fact_dcc_tenant_merchant_date
    ON fact_dcc_revenue (tenant_id, merchant_id, payment_date);

ALTER TABLE fact_dcc_revenue ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON fact_dcc_revenue;
CREATE POLICY tenant_isolation_policy ON fact_dcc_revenue
    USING (tenant_id = get_current_tenant());

-- Ancillary columns on the merchant-day summary. NOT NULL DEFAULT 0 so every
-- existing consumer's SUM() keeps working unchanged.
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS dcc_acquirer  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS dcc_merchant  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4) NOT NULL DEFAULT 0;

-- Same three on the tenant-day finance rollup fast path.
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS dcc_acquirer  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS dcc_merchant  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4) NOT NULL DEFAULT 0;

-- One-time seed for rental history already in fact_rental (loaded before this
-- migration existed) — the same statements AncillarySql runs after every
-- apply, so re-running is harmless (it recomputes to the same values).
-- fact_dcc_revenue is empty at migration time; its rows arrive through the
-- apply job, which maintains these columns itself.
INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id,
    total_txns, total_volume, total_base_volume, total_msf, total_interchange,
    total_scheme_fee, total_margin, rental_amount)
SELECT tenant_id, payment_date, merchant_id, 0, 0, 0, 0, 0, 0, 0, SUM(rental_amount)
FROM fact_rental
WHERE merchant_id IS NOT NULL
GROUP BY tenant_id, payment_date, merchant_id
ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

INSERT INTO sum_daily_finance_rollup (tenant_id, business_date, rental_amount)
SELECT tenant_id, payment_date, SUM(rental_amount)
FROM fact_rental
GROUP BY tenant_id, payment_date
ON CONFLICT (tenant_id, business_date) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;


-- ############################################################################
-- ##  BEGIN V2026_08_31_02__net_spread_menu.sql
-- ############################################################################

-- ============================================================================
-- V2026_08_31_02: Net Spread dashboard menu entry.
--
-- /executive/net-spread — replica of the Executive Daily Merchant Performance
-- layout at MERCHANT grain over sum_daily_merchant, extended with the
-- ancillary revenue columns (DCC acquirer share, rental income) and the
-- derived Net Spread = net margin + DCC acquirer share + rental income.
-- EXECUTIVE category, display_order 8 (next after Daily Merchant
-- Performance's 7 from V2026_08_19_02).
--
-- The API is gated by @menuAccess.canAccess('/executive/net-spread'), so this
-- grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Net Spread', '/executive/net-spread', 'Layers', 'EXECUTIVE', 8
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/executive/net-spread');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/net-spread'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/net-spread'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;


-- ############################################################################
-- ##  BEGIN V2026_09_01_01__scheme_billing_reference_menu.sql
-- ############################################################################

-- ============================================================================
-- V2026_09_01_01: Scheme Billing Reference menu entry.
--
-- /ops/scheme-billing-reference — read-only, fully static reference of the
-- 74 acquirer-relevant Mastercard Consolidated Billing System (MCBS) report
-- and invoice-file specifications (T0CH/BFIL, TN3A, T0CF, GB/AB reports),
-- extracted offline from the 2 June 2026 DITA-XML manual into
-- frontend/src/data/mcbsAcquirerReports.json. No backend endpoint — the
-- page ships its data in the frontend bundle, so this grant only controls
-- sidebar visibility (RoleGuard on the route gates access).
--
-- OPERATIONS category, display_order 6 (after Ingest Trust's 5).
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Scheme Billing Reference', '/ops/scheme-billing-reference', 'BookOpen', 'OPERATIONS', 6
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/ops/scheme-billing-reference');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/ops/scheme-billing-reference'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/ops/scheme-billing-reference'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;


-- ----------------------------------------------------------------------------
-- Record every bundled script in schema_migration_log (those that do not
-- already log themselves), so a later per-file apply is a visible no-op.
-- ----------------------------------------------------------------------------
INSERT INTO schema_migration_log (filename) VALUES
  ('V2026_08_29_01__rentals.sql'),
  ('V2026_08_29_02__rentals_menu.sql'),
  ('V2026_08_31_01__dcc_revenue.sql'),
  ('V2026_08_31_02__net_spread_menu.sql'),
  ('V2026_09_01_01__scheme_billing_reference_menu.sql')
ON CONFLICT (filename) DO NOTHING;

COMMIT;

ANALYZE sum_daily_merchant;
ANALYZE sum_daily_finance_rollup;