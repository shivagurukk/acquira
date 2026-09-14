-- ============================================================================
-- V2026_08_10_01: Multi-currency precision + normalized fee resolution.
--
-- WHY
-- ---
-- Onboarding an Egypt (EGP, 2dp) and a Bahrain (BHD, 3dp) tenant exposed four
-- structural assumptions that were only ever true for the UAE:
--
--   1. MONEY IS 2 DECIMAL PLACES. Amount columns are DECIMAL(19,2), so even
--      after the ingest processor is taught the right scale the third decimal
--      of a BHD amount cannot be STORED. Verified empirically before this
--      migration: a feed value of 100.505 BHD landed as 100.51 and 99.999
--      landed as 100.00. On BH volumes that is unbounded silent drift.
--      Interchange is worse: fact_transaction.interchange_fee is (19,4) but
--      every sum_*.total_interchange was (19,2), so the rollup re-truncated a
--      correctly computed fee for EVERY tenant, UAE included.
--
--   2. DESTINATION IS ALREADY CANONICAL. The fee engine exact-matches the feed
--      string against 'DOMESTIC'/'INTERNATIONAL'. A Bahraini or Egyptian feed
--      saying 'LOCAL' matched no rate row and silently took a hardcoded 1.85%
--      UAE fallback with a NULL (=0) scheme fee. destination_token_map turns
--      the feed's vocabulary into the engine's, per country and per feed, and
--      anything unmapped is now REPORTED rather than priced.
--
--   3. CHANNEL IS A UAE TERMINAL-TYPE WHITELIST. Four hardcoded strings
--      ('ECOM PROFILE','MPGS','PAY BY LINK','PAY ON') meant ECOM; everything
--      else meant POS. Any other processor's e-commerce therefore priced as
--      POS, making half of each country's rate card unreachable.
--      terminal_channel_map makes that configuration. AE keeps a '*' wildcard
--      row so its behaviour is bit-for-bit unchanged; BH/EG deliberately have
--      no wildcard, so an unrecognised terminal type surfaces as
--      UNMAPPED_CHANNEL instead of being quietly priced as POS.
--
--   4. A FEE IS JUST A PERCENTAGE. There was no flat-fee column, so flat fees
--      were faked as interchange_pct=100% with cap_amount=<flat>. That trick
--      cannot express Bahrain's BENEFIT INTERNATIONAL rate, which is
--      1.10% PLUS BHD 0.100 per transaction. flat_fee makes the pct + flat
--      combination first-class (and the old cap trick keeps working).
--
-- Plus: fee PROVENANCE. Every fee is now traceable — which rule matched, what
-- the pct/flat/cap components were, what the raw and normalized destination
-- were, and a fee_resolution_status explaining any row that did NOT price.
-- Previously an unmatched row was indistinguishable from a genuinely zero fee.
--
-- OPERATIONAL CAVEAT
-- ------------------
-- Section 1 changes numeric SCALE, which Postgres implements as a table
-- rewrite (unlike a varchar widening, which is metadata-only). On a large
-- production warehouse run it in a maintenance window; the partitioned parents
-- propagate to every partition. Everything here is idempotent, so a re-run
-- after an interrupted attempt is safe.
--
-- Splitter-safe: no dollar-quoting, no DO blocks (this project's migration
-- runner splits on semicolons).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. PRECISION: money columns DECIMAL(19,2) -> DECIMAL(21,4).
--    4dp is deliberately two more than BHD needs: fee arithmetic (pct * amount)
--    must not round at the column boundary before the cap/flat is applied.
-- ----------------------------------------------------------------------------
ALTER TABLE stg_trnx_raw ALTER COLUMN txn_currency_amount TYPE DECIMAL(21,4);
ALTER TABLE stg_trnx_raw ALTER COLUMN store_base_currency_amount TYPE DECIMAL(21,4);
ALTER TABLE stg_trnx_raw ALTER COLUMN total_amount_settled TYPE DECIMAL(21,4);

ALTER TABLE fact_transaction ALTER COLUMN txn_currency_amount TYPE DECIMAL(21,4);
ALTER TABLE fact_transaction ALTER COLUMN store_base_currency_amount TYPE DECIMAL(21,4);
ALTER TABLE fact_transaction ALTER COLUMN total_amount_settled TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_bank ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_bank ALTER COLUMN total_base_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_bank ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_bank ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_bank ALTER COLUMN total_ecom_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_bank ALTER COLUMN total_net_revenue TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_bank ALTER COLUMN total_vat TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_merchant ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN total_base_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN total_ecom_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN total_margin TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN total_debit_prepaid_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN total_credit_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN top_spending_amount TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN dcc_eligible_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN dcc_optin_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant ALTER COLUMN dcc_optout_volume TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_mcc ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_mcc ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_mcc ALTER COLUMN total_net_revenue TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_scheme ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_scheme ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_scheme ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_scheme ALTER COLUMN total_net_revenue TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_channel ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_channel ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_channel ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_channel ALTER COLUMN total_net_revenue TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_terminal ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_terminal ALTER COLUMN total_base_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_terminal ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_terminal ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_terminal ALTER COLUMN total_ecom_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_terminal ALTER COLUMN total_revenue TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_finance ALTER COLUMN total_vol TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_finance ALTER COLUMN dom_debit_vol TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_finance ALTER COLUMN dom_credit_vol TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_finance ALTER COLUMN int_vol TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_finance ALTER COLUMN dom_debit_optin TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_finance ALTER COLUMN dom_credit_optin TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_finance ALTER COLUMN int_optin TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_full ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_full ALTER COLUMN total_msf TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_full ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_full ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_full ALTER COLUMN total_ecom_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_full ALTER COLUMN total_net_revenue TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_explorer ALTER COLUMN total_base_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_explorer ALTER COLUMN total_txn_currency_amount TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_explorer ALTER COLUMN total_msf TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_explorer ALTER COLUMN total_vat TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_explorer ALTER COLUMN total_settled TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_explorer ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_explorer ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_insight ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant_attribute ALTER COLUMN metric_volume TYPE DECIMAL(21,4);

ALTER TABLE sum_daily_merchant_destination ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant_destination ALTER COLUMN total_msf TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant_destination ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant_destination ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant_destination ALTER COLUMN total_ecom_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_daily_merchant_destination ALTER COLUMN total_net_revenue TYPE DECIMAL(21,4);

ALTER TABLE sum_monthly_bank ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_bank ALTER COLUMN total_base_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_bank ALTER COLUMN total_interchange TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_bank ALTER COLUMN total_scheme_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_bank ALTER COLUMN total_ecom_fee TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_bank ALTER COLUMN total_net_revenue TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_bank ALTER COLUMN total_vat TYPE DECIMAL(21,4);

ALTER TABLE sum_monthly_card ALTER COLUMN total_spend TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_insight ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_insight ALTER COLUMN total_msf TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_merchant_metrics ALTER COLUMN total_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_merchant_metrics ALTER COLUMN avg_daily_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_merchant_metrics ALTER COLUMN max_daily_volume TYPE DECIMAL(21,4);
ALTER TABLE sum_monthly_merchant_metrics ALTER COLUMN min_daily_volume TYPE DECIMAL(21,4);

ALTER TABLE kpi_snapshot_daily ALTER COLUMN metric_value TYPE DECIMAL(21,4);
ALTER TABLE kpi_snapshot_monthly ALTER COLUMN metric_value TYPE DECIMAL(21,4);
ALTER TABLE bank_budget_target ALTER COLUMN target_value TYPE DECIMAL(21,4);
ALTER TABLE revenue_leakage_flags ALTER COLUMN metric_value TYPE DECIMAL(21,4);
ALTER TABLE revenue_leakage_flags ALTER COLUMN baseline_value TYPE DECIMAL(21,4);
ALTER TABLE revenue_leakage_flags ALTER COLUMN est_monthly_impact TYPE DECIMAL(21,4);

-- ----------------------------------------------------------------------------
-- 2. DESTINATION NORMALIZATION MAP.
--    country + optional feed + optional tenant override -> canonical dest.
--    An unmapped token is NOT defaulted to INTERNATIONAL: the fee engine marks
--    it UNMAPPED_DESTINATION and prices nothing, because guessing a
--    cross-border rate for an unrecognised token is how the 1.85% fallback
--    silently mispriced 100% of Bahraini domestic volume.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS destination_token_map (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     INT NULL,
    country_code  VARCHAR(2)  NOT NULL REFERENCES ref_country(country_code),
    feed_code     VARCHAR(30) NULL,
    raw_token     VARCHAR(40) NOT NULL,
    dest          VARCHAR(20) NOT NULL,
    note          VARCHAR(120) NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_destination_token_map
    ON destination_token_map (country_code, COALESCE(tenant_id, 0), COALESCE(feed_code, '*'), raw_token);

CREATE INDEX IF NOT EXISTS idx_destination_token_map_lookup
    ON destination_token_map (country_code, raw_token);

INSERT INTO destination_token_map (tenant_id, country_code, feed_code, raw_token, dest, note)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'AE', NULL::VARCHAR, 'DOMESTIC',      'DOMESTIC',      'canonical'),
  (NULL, 'AE', NULL, 'LOCAL',          'DOMESTIC',      'local card at local merchant'),
  (NULL, 'AE', NULL, 'ON-US',          'DOMESTIC',      'on-us routing'),
  (NULL, 'AE', NULL, 'ONUS',           'DOMESTIC',      'on-us routing'),
  (NULL, 'AE', NULL, 'INTERNATIONAL',  'INTERNATIONAL', 'canonical'),
  (NULL, 'AE', NULL, 'INTL',           'INTERNATIONAL', 'abbreviation'),
  (NULL, 'AE', NULL, 'CROSS BORDER',   'INTERNATIONAL', 'cross-border'),
  (NULL, 'AE', NULL, 'CROSSBORDER',    'INTERNATIONAL', 'cross-border'),
  (NULL, 'BH', NULL, 'DOMESTIC',       'DOMESTIC',      'canonical'),
  (NULL, 'BH', NULL, 'LOCAL',          'DOMESTIC',      'business rule: local => domestic'),
  (NULL, 'BH', NULL, 'ON-US',          'DOMESTIC',      'on-us routing'),
  (NULL, 'BH', NULL, 'ONUS',           'DOMESTIC',      'on-us routing'),
  (NULL, 'BH', NULL, 'INTERNATIONAL',  'INTERNATIONAL', 'canonical'),
  (NULL, 'BH', NULL, 'INTL',           'INTERNATIONAL', 'abbreviation'),
  (NULL, 'BH', NULL, 'CROSS BORDER',   'INTERNATIONAL', 'cross-border'),
  (NULL, 'BH', NULL, 'CROSSBORDER',    'INTERNATIONAL', 'cross-border'),
  (NULL, 'EG', NULL, 'DOMESTIC',       'DOMESTIC',      'canonical'),
  (NULL, 'EG', NULL, 'LOCAL',          'DOMESTIC',      'business rule: local => domestic'),
  (NULL, 'EG', NULL, 'ON-US',          'DOMESTIC',      'on-us routing'),
  (NULL, 'EG', NULL, 'ONUS',           'DOMESTIC',      'on-us routing'),
  (NULL, 'EG', NULL, 'INTERNATIONAL',  'INTERNATIONAL', 'canonical'),
  (NULL, 'EG', NULL, 'INTL',           'INTERNATIONAL', 'abbreviation'),
  (NULL, 'EG', NULL, 'CROSS BORDER',   'INTERNATIONAL', 'cross-border'),
  (NULL, 'EG', NULL, 'CROSSBORDER',    'INTERNATIONAL', 'cross-border')
) AS v(tenant_id, country_code, feed_code, raw_token, dest, note)
WHERE NOT EXISTS (SELECT 1 FROM destination_token_map x
                  WHERE x.country_code = v.country_code
                    AND x.raw_token = v.raw_token
                    AND x.tenant_id IS NULL
                    AND x.feed_code IS NULL);

-- ----------------------------------------------------------------------------
-- 3. TERMINAL TYPE -> CHANNEL MAP.
--    raw_type '*' is the per-country wildcard. AE gets '*' -> POS so its
--    behaviour is IDENTICAL to the old hardcoded whitelist (no regression).
--    BH/EG intentionally get NO wildcard: until the real processor terminal
--    types are supplied, an unrecognised type must surface as UNMAPPED_CHANNEL
--    rather than silently taking POS pricing (which is the cheaper side, so
--    the error would flatter the P&L and never be noticed).
--    ACTION REQUIRED: obtain the BH and EG processors' terminal-type values
--    and add them here before go-live.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS terminal_channel_map (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     INT NULL,
    country_code  VARCHAR(2)  NOT NULL REFERENCES ref_country(country_code),
    raw_type      VARCHAR(60) NOT NULL,
    channel       VARCHAR(20) NOT NULL,
    note          VARCHAR(120) NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_terminal_channel_map
    ON terminal_channel_map (country_code, COALESCE(tenant_id, 0), raw_type);

INSERT INTO terminal_channel_map (tenant_id, country_code, raw_type, channel, note)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'AE', 'ECOM PROFILE', 'ECOM', 'legacy hardcoded whitelist'),
  (NULL, 'AE', 'MPGS',         'ECOM', 'legacy hardcoded whitelist'),
  (NULL, 'AE', 'PAY BY LINK',  'ECOM', 'legacy hardcoded whitelist'),
  (NULL, 'AE', 'PAY ON',       'ECOM', 'legacy hardcoded whitelist'),
  (NULL, 'AE', '*',            'POS',  'wildcard preserves pre-2026-08-10 UAE behaviour'),
  (NULL, 'BH', 'POS',          'POS',  'ASSUMPTION - confirm against BH processor feed'),
  (NULL, 'BH', 'ECOM',         'ECOM', 'ASSUMPTION - confirm against BH processor feed'),
  (NULL, 'BH', 'ECOM PROFILE', 'ECOM', 'ASSUMPTION - confirm against BH processor feed'),
  (NULL, 'BH', 'MPGS',         'ECOM', 'ASSUMPTION - confirm against BH processor feed'),
  (NULL, 'BH', 'PAY BY LINK',  'ECOM', 'ASSUMPTION - confirm against BH processor feed'),
  (NULL, 'EG', 'POS',          'POS',  'ASSUMPTION - confirm against EG processor feed'),
  (NULL, 'EG', 'ECOM',         'ECOM', 'ASSUMPTION - confirm against EG processor feed'),
  (NULL, 'EG', 'ECOM PROFILE', 'ECOM', 'ASSUMPTION - confirm against EG processor feed'),
  (NULL, 'EG', 'MPGS',         'ECOM', 'ASSUMPTION - confirm against EG processor feed'),
  (NULL, 'EG', 'PAY BY LINK',  'ECOM', 'ASSUMPTION - confirm against EG processor feed')
) AS v(tenant_id, country_code, raw_type, channel, note)
WHERE NOT EXISTS (SELECT 1 FROM terminal_channel_map x
                  WHERE x.country_code = v.country_code
                    AND x.raw_type = v.raw_type
                    AND x.tenant_id IS NULL);

-- ----------------------------------------------------------------------------
-- 4. RATE TABLES: flat fee, approval status, effective dating.
--
--    flat_fee     : per-transaction fixed component ADDED to the percentage.
--                   Needed for BH BENEFIT INTERNATIONAL = 1.10% + BHD 0.100.
--                   The pre-existing "pct=100% + cap=<flat>" trick still works
--                   for pure flat fees (BENEFIT government MCCs).
--    rate_status  : APPROVED  = signed off by the business, safe to price on.
--                   PLACEHOLDER = seeded from another country as a stand-in.
--                   The fee engine matches APPROVED rows ONLY, so a placeholder
--                   now produces an explicit unresolved status instead of
--                   quietly becoming production pricing for a new country.
--    effective_from/to : rate versioning. Needed imminently because the Egypt
--                   Meeza rate is an explicit temporary figure.
-- ----------------------------------------------------------------------------
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS flat_fee DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS rate_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS effective_from DATE NULL;
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS effective_to DATE NULL;
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS source_note VARCHAR(160) NULL;

ALTER TABLE scheme_fee_rate ADD COLUMN IF NOT EXISTS flat_fee DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE scheme_fee_rate ADD COLUMN IF NOT EXISTS rate_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE scheme_fee_rate ADD COLUMN IF NOT EXISTS effective_from DATE NULL;
ALTER TABLE scheme_fee_rate ADD COLUMN IF NOT EXISTS effective_to DATE NULL;
ALTER TABLE scheme_fee_rate ADD COLUMN IF NOT EXISTS source_note VARCHAR(160) NULL;

-- The ticket-threshold columns are named *_aed but hold values in the TENANT's
-- settlement currency (20 in a BH row means 20 BHD). Renaming breaks the fee
-- SQL, so document it on the column itself until a coordinated rename.
COMMENT ON COLUMN interchange_rate_local.min_ticket IS
  'Inclusive lower ticket bound, in the TENANT SETTLEMENT currency (20 = 20 BHD on a BH tenant, 20 EGP on an EG one). Renamed from min_ticket_aed 2026-08-11.';
COMMENT ON COLUMN interchange_rate_local.max_ticket IS
  'Exclusive upper ticket bound, in the TENANT SETTLEMENT currency. Renamed from max_ticket_aed 2026-08-11.';
COMMENT ON COLUMN interchange_rate_local.flat_fee IS
  'Fixed per-transaction component ADDED to interchange_pct * amount, in cap_currency_code.';

-- 4b. Flag the UAE-derived stand-ins. V2026_07_31_03/05 seeded Bahrain and
--     Egypt INTERNATIONAL interchange as "flat 1.85% per UAE" and bulk-copied
--     the entire UAE scheme-fee grid. Those are NOT Bahraini or Egyptian
--     figures and must not price production traffic for those countries.
--     SCOPE: the any-scheme WILDCARD row only (scheme_group IS NULL). That is
--     the row V2026_07_31_03/05 seeded as "flat 1.85% per UAE". Without the
--     scheme_group predicate this statement also demotes genuine
--     scheme-specific international rates added later — re-running this
--     migration after V2026_08_10_02 silently turned Bahrain's approved
--     BENEFIT international rate (1.10% + BHD 0.100) back into a placeholder,
--     so BENEFIT cross-border stopped pricing. Caught by the pricing-matrix
--     test; the two migrations must converge in any order and any repetition.
--     A row whose source_note starts with 'BUSINESS-APPROVED' has been
--     explicitly signed off (see V2026_08_10_04) and is never demoted again.
--     Without that guard this sweep re-runs on every startup and would silently
--     un-approve a rate the business had just accepted.
UPDATE interchange_rate_local
   SET rate_status = 'PLACEHOLDER',
       source_note = COALESCE(source_note, 'UAE 1.85% stand-in seeded by V2026_07_31_03/05 - replace with real cross-border rate')
 WHERE country_code IN ('BH','EG')
   AND dest = 'INTERNATIONAL'
   AND scheme_group IS NULL
   AND rate_status <> 'PLACEHOLDER'
   AND COALESCE(source_note, '') NOT LIKE 'BUSINESS-APPROVED%';

UPDATE scheme_fee_rate
   SET rate_status = 'PLACEHOLDER',
       source_note = COALESCE(source_note, 'Verbatim copy of the UAE grid - replace with real country scheme fees')
 WHERE country_code IN ('BH','EG')
   AND rate_status <> 'PLACEHOLDER'
   AND COALESCE(source_note, '') NOT LIKE 'BUSINESS-APPROVED%';

-- ----------------------------------------------------------------------------
-- 5. FEE PROVENANCE ON THE FACT TABLE.
--    Answers "why is this fee this number?" — previously unanswerable, and the
--    reason a silent 1.85% fallback could run for months unnoticed.
-- ----------------------------------------------------------------------------
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS destination_raw VARCHAR(40) NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS channel VARCHAR(20) NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS fee_resolution_status VARCHAR(30) NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS interchange_rule_id BIGINT NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS scheme_fee_rule_id BIGINT NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS interchange_pct_applied DECIMAL(9,6) NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS interchange_flat_applied DECIMAL(19,4) NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS interchange_cap_applied DECIMAL(19,4) NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS scheme_fee_status VARCHAR(30) NULL;

CREATE INDEX IF NOT EXISTS idx_fact_fee_resolution
    ON fact_transaction (tenant_id, fee_resolution_status)
    WHERE fee_resolution_status IS NOT NULL AND fee_resolution_status <> 'RESOLVED';

-- ----------------------------------------------------------------------------
-- 6. ENTITY/SCHEMA DRIFT.
--    These three columns are mapped by JPA entities but were created by no
--    migration — they exist on the long-lived RDS instance only because
--    someone added them by hand. On a freshly provisioned database (which is
--    exactly what the Egypt and Bahrain environments will be) the Transactions
--    screen 500s on issuer_bank and PDF generation 500s on
--    generate_report_flag. Verified by building this database from migrations.
--    generate_report_flag is INTEGER, not BOOLEAN: the entity maps an int and
--    Postgres reports "Bad value for type int : t" against a boolean column.
-- ----------------------------------------------------------------------------
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS issuer_bank VARCHAR(100) NULL;
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS issuer_country VARCHAR(10) NULL;
ALTER TABLE dim_merchant ADD COLUMN IF NOT EXISTS generate_report_flag INTEGER DEFAULT 1;

-- ----------------------------------------------------------------------------
-- 7. EGYPT CURRENCY SYMBOL.
--    ref_country seeded EG with a bare '£', which reads as pounds sterling on
--    every dashboard and PDF. Use the unambiguous ISO code for reporting.
-- ----------------------------------------------------------------------------
UPDATE ref_country SET currency_symbol = 'EGP' WHERE country_code = 'EG' AND currency_symbol = '£';

-- Guard: the duplicate ref_country seed block in schema.sql has no
-- decimal_notation_value column, so on a database where it wins the race every
-- currency silently becomes 2dp — which would make BHD divide by 100 at ingest
-- and inflate every Bahraini amount tenfold. Re-assert the 3dp currencies.
UPDATE ref_country SET decimal_notation_value = 1000
 WHERE currency_code IN ('BHD','KWD','OMR','JOD','TND','LYD','IQD')
   AND COALESCE(decimal_notation_value, 0) <> 1000;
UPDATE ref_country SET decimal_notation_value = 100
 WHERE currency_code IN ('EGP','AED')
   AND COALESCE(decimal_notation_value, 0) <> 100;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_10_01__multi_currency_precision_and_fee_resolution.sql')
ON CONFLICT (filename) DO NOTHING;
