-- ============================================================================
-- V2026_11_12_01: MIS / Price Builder — merchant deal pricing simulator.
--
-- WHAT IT IS: a pre-sale calculator. Given a prospect's MCC, channel, annual
-- volume and the split of that volume across card schemes, it prices the deal
-- bottom-up — interchange (IC) + scheme fee (SF) + transaction fees against
-- MSF revenue, DCC, rental and settlement income — and recommends an MSF that
-- clears a target net margin. It does NOT read fact_transaction: it prices a
-- merchant that does not exist yet. That is what separates it from
-- /business/pricing-simulator, which re-prices the book we already have.
--
-- ---------------------------------------------------------------------------
-- SIGN CONVENTION — read this before touching any rate column.
-- ---------------------------------------------------------------------------
-- EVERY rate column in this module is a SIGNED DECIMAL FRACTION in which a
-- NEGATIVE number is a cost to the acquirer:
--
--      1.80% interchange  ->  -0.018000
--      0.15% scheme fee   ->  -0.001500
--      0.25% target margin->  +0.002500
--
-- No column anywhere in this module holds "1.8 meaning 1.8%". The source
-- material this was ported from mixed the two (an IC matrix in fractions, a
-- scheme-fee matrix in percent) and the resulting 100x errors are exactly what
-- this convention exists to prevent. The API converts once, at the edge: the
-- UI talks percent numbers to the user, the DB and the engine talk fractions.
--
-- ---------------------------------------------------------------------------
-- RATE RESOLUTION — most specific wins:
--   1. pb_special_rule  — per-MCC rules that are not a flat percentage
--                         (per-transaction IC, ticket-size splits, caps)
--   2. pb_mcc_cost      — exact per-MCC IC, as imported from the acquirer's
--                         MCC cost workbook
--   3. pb_sector_cost   — sector-level fallback, seeded below, so the screen
--                         works on a database that has never had an import
-- ---------------------------------------------------------------------------
--
-- Country behaviour (currency, USD rate, e-commerce IC uplift, target margin,
-- fixed international MSF, DCC scheme fee) is DATA in pb_country_config, not
-- Java. Adding Oman or Egypt is a seed row, not a code change.
--
-- Idempotent; splitter-safe (no dollar-quoting). On prod apply once via psql.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Country behaviour
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_country_config (
    country_code        VARCHAR(2)  PRIMARY KEY,
    country_name        VARCHAR(60) NOT NULL,
    currency_code       VARCHAR(3)  NOT NULL,
    -- Local currency -> USD. Volumes are entered in local currency; every
    -- figure the engine returns is USD, as in the source tool.
    usd_rate            DECIMAL(18, 8) NOT NULL,
    -- Added to IC for e-commerce on Visa/Mastercard when the MCC is NOT
    -- preferential. Negative = extra cost. BH -0.20%, AE -0.07%.
    ecom_ic_uplift      DECIMAL(9, 6) NOT NULL DEFAULT 0,
    -- Net margin the recommended MSF is built to clear, over blended IC+SF.
    target_margin       DECIMAL(9, 6) NOT NULL DEFAULT 0.0025,
    -- International MSF is a fixed commercial number, not a build-up.
    intl_msf_pos        DECIMAL(9, 6) NOT NULL DEFAULT 0.025,
    intl_msf_ecom       DECIMAL(9, 6) NOT NULL DEFAULT 0.026,
    -- DCC scheme fee charged on DCC volume.
    dcc_scheme_fee_pos  DECIMAL(9, 6) NOT NULL DEFAULT 0.004,
    dcc_scheme_fee_ecom DECIMAL(9, 6) NOT NULL DEFAULT 0.010,
    -- Per-transaction fee on debit international at POS (BH: USD 0.27).
    debit_intl_txn_fee  DECIMAL(19, 6) NOT NULL DEFAULT 0,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    display_order       INT NOT NULL DEFAULT 0
);

INSERT INTO pb_country_config (country_code, country_name, currency_code, usd_rate,
       ecom_ic_uplift, target_margin, intl_msf_pos, intl_msf_ecom,
       dcc_scheme_fee_pos, dcc_scheme_fee_ecom, debit_intl_txn_fee,
       is_active, display_order)
SELECT 'BH', 'Bahrain', 'BHD', 2.65000000,
       -0.002000, 0.002500, 0.025000, 0.026000, 0.004000, 0.010000, 0.270000, TRUE, 1
WHERE NOT EXISTS (SELECT 1 FROM pb_country_config WHERE country_code = 'BH');

INSERT INTO pb_country_config (country_code, country_name, currency_code, usd_rate,
       ecom_ic_uplift, target_margin, intl_msf_pos, intl_msf_ecom,
       dcc_scheme_fee_pos, dcc_scheme_fee_ecom, debit_intl_txn_fee,
       is_active, display_order)
SELECT 'AE', 'United Arab Emirates', 'AED', 0.27247956,
       -0.000700, 0.002500, 0.025000, 0.026000, 0.004000, 0.010000, 0.000000, TRUE, 2
WHERE NOT EXISTS (SELECT 1 FROM pb_country_config WHERE country_code = 'AE');

-- Listed in the source tool but not yet priced. Inactive: selectable only once
-- a rate set exists, so nobody quotes a deal off an empty cost table.
INSERT INTO pb_country_config (country_code, country_name, currency_code, usd_rate, is_active, display_order)
SELECT 'OM', 'Oman', 'OMR', 2.60000000, FALSE, 3
WHERE NOT EXISTS (SELECT 1 FROM pb_country_config WHERE country_code = 'OM');

INSERT INTO pb_country_config (country_code, country_name, currency_code, usd_rate, is_active, display_order)
SELECT 'EG', 'Egypt', 'EGP', 0.02060000, FALSE, 4
WHERE NOT EXISTS (SELECT 1 FROM pb_country_config WHERE country_code = 'EG');

-- ---------------------------------------------------------------------------
-- 2. The scheme rows a country prices on
--
-- Bahrain quotes 6 rows (Benefit/Visa/MC x local/international); the UAE
-- quotes 8, because local credit splits Standard from Premium and the local
-- debit scheme is "Debit" rather than Benefit. The row set is data so the mix
-- table and the pricing table can be driven straight off it.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_scheme_row (
    id            BIGSERIAL PRIMARY KEY,
    country_code  VARCHAR(2)  NOT NULL REFERENCES pb_country_config(country_code),
    scheme_key    VARCHAR(40) NOT NULL,   -- stable id, e.g. 'visa-local-premium'
    label         VARCHAR(60) NOT NULL,
    scheme        VARCHAR(20) NOT NULL,   -- BENEFIT / DEBIT / VISA / MASTERCARD
    locality      VARCHAR(20) NOT NULL,   -- LOCAL / INTERNATIONAL
    tier          VARCHAR(20),            -- STANDARD / PREMIUM / NULL
    card_type     VARCHAR(20) NOT NULL,   -- DEBIT / CREDIT
    display_order INT NOT NULL DEFAULT 0,
    UNIQUE (country_code, scheme_key)
);

INSERT INTO pb_scheme_row (country_code, scheme_key, label, scheme, locality, tier, card_type, display_order)
SELECT v.* FROM (VALUES
    ('BH','benefit-local','Benefit Local','BENEFIT','LOCAL',NULL,'DEBIT',1),
    ('BH','visa-local','Visa Local','VISA','LOCAL',NULL,'CREDIT',2),
    ('BH','mc-local','MC Local','MASTERCARD','LOCAL',NULL,'CREDIT',3),
    ('BH','benefit-intl','Benefit International','BENEFIT','INTERNATIONAL',NULL,'DEBIT',4),
    ('BH','visa-intl','Visa International','VISA','INTERNATIONAL',NULL,'CREDIT',5),
    ('BH','mc-intl','MC International','MASTERCARD','INTERNATIONAL',NULL,'CREDIT',6),
    ('AE','debit-local','Debit Local','DEBIT','LOCAL',NULL,'DEBIT',1),
    ('AE','visa-local-standard','Visa Local Standard','VISA','LOCAL','STANDARD','CREDIT',2),
    ('AE','visa-local-premium','Visa Local Premium','VISA','LOCAL','PREMIUM','CREDIT',3),
    ('AE','mc-local-standard','MC Local Standard','MASTERCARD','LOCAL','STANDARD','CREDIT',4),
    ('AE','mc-local-premium','MC Local Premium','MASTERCARD','LOCAL','PREMIUM','CREDIT',5),
    ('AE','debit-intl','Debit International','DEBIT','INTERNATIONAL',NULL,'DEBIT',6),
    ('AE','visa-intl','Visa International','VISA','INTERNATIONAL',NULL,'CREDIT',7),
    ('AE','mc-intl','MC International','MASTERCARD','INTERNATIONAL',NULL,'CREDIT',8)
) AS v(country_code, scheme_key, label, scheme, locality, tier, card_type, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM pb_scheme_row r
     WHERE r.country_code = v.country_code AND r.scheme_key = v.scheme_key);

-- ---------------------------------------------------------------------------
-- 3. Scheme fee, by country / scheme / locality
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_scheme_fee (
    id           BIGSERIAL PRIMARY KEY,
    country_code VARCHAR(2)  NOT NULL REFERENCES pb_country_config(country_code),
    scheme       VARCHAR(20) NOT NULL,
    locality     VARCHAR(20) NOT NULL,
    fee_pct      DECIMAL(9, 6) NOT NULL,   -- negative = cost
    UNIQUE (country_code, scheme, locality)
);

INSERT INTO pb_scheme_fee (country_code, scheme, locality, fee_pct)
SELECT v.* FROM (VALUES
    ('BH','BENEFIT','LOCAL',-0.000500),
    ('BH','BENEFIT','INTERNATIONAL',-0.006000),
    ('BH','VISA','LOCAL',-0.001500),
    ('BH','VISA','INTERNATIONAL',-0.007000),
    ('BH','MASTERCARD','LOCAL',-0.001500),
    ('BH','MASTERCARD','INTERNATIONAL',-0.007000),
    ('AE','DEBIT','LOCAL',-0.001500),
    ('AE','DEBIT','INTERNATIONAL',-0.006000),
    ('AE','VISA','LOCAL',-0.001500),
    ('AE','VISA','INTERNATIONAL',-0.007000),
    ('AE','MASTERCARD','LOCAL',-0.001500),
    ('AE','MASTERCARD','INTERNATIONAL',-0.007000)
) AS v(country_code, scheme, locality, fee_pct)
WHERE NOT EXISTS (
    SELECT 1 FROM pb_scheme_fee f
     WHERE f.country_code = v.country_code AND f.scheme = v.scheme AND f.locality = v.locality);

-- ---------------------------------------------------------------------------
-- 4. The MCC catalog the quote is built on
--
-- mcc_type drives the preferential/normal split: preferential MCCs are exempt
-- from the e-commerce IC uplift and take the preferential debit rate.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_mcc (
    id           BIGSERIAL PRIMARY KEY,
    country_code VARCHAR(2)  NOT NULL REFERENCES pb_country_config(country_code),
    mcc          VARCHAR(4)  NOT NULL,
    sector       VARCHAR(40) NOT NULL,   -- joins pb_sector_cost.sector
    description  VARCHAR(160) NOT NULL,
    mcc_type     VARCHAR(20) NOT NULL DEFAULT 'NORMAL',  -- NORMAL / PREFERENTIAL
    UNIQUE (country_code, mcc)
);
CREATE INDEX IF NOT EXISTS idx_pb_mcc_country ON pb_mcc (country_code, sector);

-- ---------------------------------------------------------------------------
-- 5. Sector-level IC — the fallback that makes the screen work out of the box
--
-- These are the sector defaults the source tool shipped with. An import of the
-- acquirer's MCC cost workbook writes exact per-MCC rows into pb_mcc_cost,
-- which override these. Seeded for both countries so a quote is never priced
-- at zero interchange.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_sector_cost (
    id           BIGSERIAL PRIMARY KEY,
    country_code VARCHAR(2)  NOT NULL REFERENCES pb_country_config(country_code),
    sector       VARCHAR(40) NOT NULL,
    scheme       VARCHAR(20) NOT NULL,
    locality     VARCHAR(20) NOT NULL,
    tier         VARCHAR(20),
    ic_pct       DECIMAL(9, 6) NOT NULL,   -- negative = cost
    UNIQUE (country_code, sector, scheme, locality, tier)
);

-- ---------------------------------------------------------------------------
-- 6. Exact per-MCC IC, as imported
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_mcc_cost (
    id           BIGSERIAL PRIMARY KEY,
    country_code VARCHAR(2)  NOT NULL REFERENCES pb_country_config(country_code),
    mcc          VARCHAR(4)  NOT NULL,
    scheme       VARCHAR(20) NOT NULL,
    locality     VARCHAR(20) NOT NULL,
    tier         VARCHAR(20),
    ic_pct       DECIMAL(9, 6) NOT NULL,
    cap_amount   DECIMAL(19, 4),           -- local currency, NULL = uncapped
    source       VARCHAR(40) NOT NULL DEFAULT 'IMPORT',
    imported_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (country_code, mcc, scheme, locality, tier)
);
CREATE INDEX IF NOT EXISTS idx_pb_mcc_cost_lookup ON pb_mcc_cost (country_code, mcc);

-- ---------------------------------------------------------------------------
-- 7. Rules that are not a flat percentage
--
-- rule_type:
--   PER_TXN       IC is an amount per transaction, not a rate on volume.
--                 amount_local is in the country's currency unless
--                 amount_is_usd, which the Mastercard charity rate needs.
--   TICKET_SPLIT  IC differs above and below a ticket threshold. The quote
--                 carries the % of transactions below it, and the engine
--                 blends small_rate and large_rate over that split.
--   FLAT_PCT      a per-MCC override of the resolved rate.
--
-- A NULL scheme / locality / card_type means "any".
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_special_rule (
    id            BIGSERIAL PRIMARY KEY,
    country_code  VARCHAR(2)  NOT NULL REFERENCES pb_country_config(country_code),
    mcc           VARCHAR(4),             -- NULL = applies to every MCC
    scheme        VARCHAR(20),
    locality      VARCHAR(20),
    card_type     VARCHAR(20),
    channel       VARCHAR(20),            -- POS / ECOM / NULL(any)
    mcc_type      VARCHAR(20),            -- restrict to NORMAL / PREFERENTIAL
    rule_type     VARCHAR(20) NOT NULL,
    threshold     DECIMAL(19, 4),         -- ticket threshold, local currency
    small_rate    DECIMAL(9, 6),          -- IC below the threshold
    large_rate    DECIMAL(9, 6),          -- IC at or above it
    flat_rate     DECIMAL(9, 6),
    amount_local  DECIMAL(19, 6),         -- PER_TXN amount
    amount_is_usd BOOLEAN NOT NULL DEFAULT FALSE,
    priority      INT NOT NULL DEFAULT 0, -- higher wins
    note          VARCHAR(200),
    UNIQUE (country_code, mcc, scheme, locality, card_type, channel, rule_type)
);
CREATE INDEX IF NOT EXISTS idx_pb_special_rule_lookup
    ON pb_special_rule (country_code, mcc, priority DESC);

-- ---------------------------------------------------------------------------
-- 8. Payment gateways (e-commerce per-transaction cost, USD)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_payment_gateway (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(30) UNIQUE NOT NULL,
    label        VARCHAR(60) NOT NULL,
    cost_per_txn DECIMAL(19, 6) NOT NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO pb_payment_gateway (code, label, cost_per_txn)
SELECT v.* FROM (VALUES
    ('cybersource','CyberSource',0.100000),
    ('mpgs','MPGS',0.110000),
    ('payon','Payon',0.050000)
) AS v(code, label, cost_per_txn)
WHERE NOT EXISTS (SELECT 1 FROM pb_payment_gateway g WHERE g.code = v.code);

-- ---------------------------------------------------------------------------
-- 9. Saved quotes
--
-- inputs/results are JSONB because a quote is a snapshot of a conversation,
-- not a reporting fact: it must still open unchanged after the rate cards
-- behind it move. The flat columns exist only so the list can be filtered and
-- sorted without opening every document.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pb_quote (
    quote_id        BIGSERIAL PRIMARY KEY,
    tenant_id       INT NOT NULL,
    quote_ref       VARCHAR(30) NOT NULL,
    merchant_name   VARCHAR(160) NOT NULL,
    country_code    VARCHAR(2)  NOT NULL,
    channel         VARCHAR(20) NOT NULL,   -- POS / ECOM
    mcc             VARCHAR(4),
    annual_volume   DECIMAL(19, 2),         -- local currency
    total_txns      BIGINT,
    effective_msf   DECIMAL(9, 6),
    blended_ic      DECIMAL(9, 6),
    blended_sf      DECIMAL(9, 6),
    net_spread_usd  DECIMAL(19, 4),
    margin_per_txn  DECIMAL(19, 6),
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- DRAFT/SUBMITTED/WON/LOST
    inputs          TEXT NOT NULL,
    results         TEXT NOT NULL,
    created_by      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, quote_ref)
);
CREATE INDEX IF NOT EXISTS idx_pb_quote_tenant ON pb_quote (tenant_id, created_at DESC);

-- RLS, same shape as every other tenant-scoped table. DROP-then-CREATE rather
-- than IF NOT EXISTS so the script stays idempotent without dollar-quoting.
ALTER TABLE pb_quote ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON pb_quote;
CREATE POLICY tenant_isolation_policy ON pb_quote USING (tenant_id = get_current_tenant());

-- ---------------------------------------------------------------------------
-- 10. Seed: sector IC defaults
-- ---------------------------------------------------------------------------
INSERT INTO pb_sector_cost (country_code, sector, scheme, locality, tier, ic_pct)
SELECT v.* FROM (VALUES
    -- Bahrain — Benefit is flat across sectors; Visa/MC vary by sector.
    ('BH','government','BENEFIT','LOCAL',NULL,-0.006000),
    ('BH','education','BENEFIT','LOCAL',NULL,-0.006000),
    ('BH','restaurant','BENEFIT','LOCAL',NULL,-0.006000),
    ('BH','qsr','BENEFIT','LOCAL',NULL,-0.006000),
    ('BH','supermarket','BENEFIT','LOCAL',NULL,-0.006000),
    ('BH','retail','BENEFIT','LOCAL',NULL,-0.006000),
    ('BH','government','BENEFIT','INTERNATIONAL',NULL,-0.010000),
    ('BH','education','BENEFIT','INTERNATIONAL',NULL,-0.010000),
    ('BH','restaurant','BENEFIT','INTERNATIONAL',NULL,-0.010000),
    ('BH','qsr','BENEFIT','INTERNATIONAL',NULL,-0.010000),
    ('BH','supermarket','BENEFIT','INTERNATIONAL',NULL,-0.010000),
    ('BH','retail','BENEFIT','INTERNATIONAL',NULL,-0.010000),
    ('BH','government','VISA','LOCAL',NULL,-0.005000),
    ('BH','education','VISA','LOCAL',NULL,-0.005000),
    ('BH','restaurant','VISA','LOCAL',NULL,-0.017500),
    ('BH','qsr','VISA','LOCAL',NULL,-0.017000),
    ('BH','supermarket','VISA','LOCAL',NULL,-0.012000),
    ('BH','retail','VISA','LOCAL',NULL,-0.018000),
    ('BH','government','VISA','INTERNATIONAL',NULL,-0.018500),
    ('BH','education','VISA','INTERNATIONAL',NULL,-0.018500),
    ('BH','restaurant','VISA','INTERNATIONAL',NULL,-0.018500),
    ('BH','qsr','VISA','INTERNATIONAL',NULL,-0.018500),
    ('BH','supermarket','VISA','INTERNATIONAL',NULL,-0.018500),
    ('BH','retail','VISA','INTERNATIONAL',NULL,-0.019000),
    ('BH','government','MASTERCARD','LOCAL',NULL,-0.005000),
    ('BH','education','MASTERCARD','LOCAL',NULL,-0.005000),
    ('BH','restaurant','MASTERCARD','LOCAL',NULL,-0.017500),
    ('BH','qsr','MASTERCARD','LOCAL',NULL,-0.012000),
    ('BH','supermarket','MASTERCARD','LOCAL',NULL,-0.012000),
    ('BH','retail','MASTERCARD','LOCAL',NULL,-0.018000),
    ('BH','government','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('BH','education','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('BH','restaurant','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('BH','qsr','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('BH','supermarket','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('BH','retail','MASTERCARD','INTERNATIONAL',NULL,-0.019000),
    -- UAE — local debit is handled by the TICKET_SPLIT rule below, so the
    -- rows here cover international debit and the credit tiers.
    ('AE','government','DEBIT','INTERNATIONAL',NULL,-0.017500),
    ('AE','education','DEBIT','INTERNATIONAL',NULL,-0.017500),
    ('AE','restaurant','DEBIT','INTERNATIONAL',NULL,-0.017500),
    ('AE','qsr','DEBIT','INTERNATIONAL',NULL,-0.017500),
    ('AE','supermarket','DEBIT','INTERNATIONAL',NULL,-0.017500),
    ('AE','retail','DEBIT','INTERNATIONAL',NULL,-0.017500),
    ('AE','government','VISA','LOCAL','STANDARD',-0.012000),
    ('AE','education','VISA','LOCAL','STANDARD',-0.012000),
    ('AE','restaurant','VISA','LOCAL','STANDARD',-0.019500),
    ('AE','qsr','VISA','LOCAL','STANDARD',-0.019500),
    ('AE','supermarket','VISA','LOCAL','STANDARD',-0.013000),
    ('AE','retail','VISA','LOCAL','STANDARD',-0.019500),
    ('AE','government','VISA','LOCAL','PREMIUM',-0.015000),
    ('AE','education','VISA','LOCAL','PREMIUM',-0.015000),
    ('AE','restaurant','VISA','LOCAL','PREMIUM',-0.023000),
    ('AE','qsr','VISA','LOCAL','PREMIUM',-0.023000),
    ('AE','supermarket','VISA','LOCAL','PREMIUM',-0.016000),
    ('AE','retail','VISA','LOCAL','PREMIUM',-0.023000),
    ('AE','government','MASTERCARD','LOCAL','STANDARD',-0.012000),
    ('AE','education','MASTERCARD','LOCAL','STANDARD',-0.012000),
    ('AE','restaurant','MASTERCARD','LOCAL','STANDARD',-0.019500),
    ('AE','qsr','MASTERCARD','LOCAL','STANDARD',-0.019500),
    ('AE','supermarket','MASTERCARD','LOCAL','STANDARD',-0.013000),
    ('AE','retail','MASTERCARD','LOCAL','STANDARD',-0.019500),
    ('AE','government','MASTERCARD','LOCAL','PREMIUM',-0.015000),
    ('AE','education','MASTERCARD','LOCAL','PREMIUM',-0.015000),
    ('AE','restaurant','MASTERCARD','LOCAL','PREMIUM',-0.023000),
    ('AE','qsr','MASTERCARD','LOCAL','PREMIUM',-0.023000),
    ('AE','supermarket','MASTERCARD','LOCAL','PREMIUM',-0.016000),
    ('AE','retail','MASTERCARD','LOCAL','PREMIUM',-0.023000),
    ('AE','government','VISA','INTERNATIONAL',NULL,-0.018500),
    ('AE','education','VISA','INTERNATIONAL',NULL,-0.018500),
    ('AE','restaurant','VISA','INTERNATIONAL',NULL,-0.018500),
    ('AE','qsr','VISA','INTERNATIONAL',NULL,-0.018500),
    ('AE','supermarket','VISA','INTERNATIONAL',NULL,-0.018500),
    ('AE','retail','VISA','INTERNATIONAL',NULL,-0.019000),
    ('AE','government','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('AE','education','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('AE','restaurant','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('AE','qsr','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('AE','supermarket','MASTERCARD','INTERNATIONAL',NULL,-0.018500),
    ('AE','retail','MASTERCARD','INTERNATIONAL',NULL,-0.019000)
) AS v(country_code, sector, scheme, locality, tier, ic_pct)
WHERE NOT EXISTS (
    SELECT 1 FROM pb_sector_cost c
     WHERE c.country_code = v.country_code AND c.sector = v.sector
       AND c.scheme = v.scheme AND c.locality = v.locality
       AND c.tier IS NOT DISTINCT FROM v.tier);

-- ---------------------------------------------------------------------------
-- 11. Seed: the MCC catalog
--
-- Enough to quote on day one, and every MCC the special rules below name.
-- An import of the acquirer's cost workbook extends this.
-- ---------------------------------------------------------------------------
INSERT INTO pb_mcc (country_code, mcc, sector, description, mcc_type)
SELECT c.country_code, v.mcc, v.sector, v.description, v.mcc_type
FROM pb_country_config c
CROSS JOIN (VALUES
    ('9211','government','Court costs including alimony and child support','PREFERENTIAL'),
    ('9222','government','Fines','PREFERENTIAL'),
    ('9223','government','Bail and bond payments','PREFERENTIAL'),
    ('9311','government','Tax payments','PREFERENTIAL'),
    ('9399','government','Government services, not elsewhere classified','PREFERENTIAL'),
    ('9402','government','Postal services, government only','PREFERENTIAL'),
    ('4111','government','Transportation, suburban and local commuter','PREFERENTIAL'),
    ('4112','government','Passenger railways','PREFERENTIAL'),
    ('4131','government','Bus lines','PREFERENTIAL'),
    ('4814','government','Telecommunication services','PREFERENTIAL'),
    ('4816','government','Computer network / information services','PREFERENTIAL'),
    ('4899','government','Cable, satellite and other pay television','PREFERENTIAL'),
    ('4900','government','Utilities — electric, gas, water, sanitary','PREFERENTIAL'),
    ('8211','education','Elementary and secondary schools','PREFERENTIAL'),
    ('8220','education','Colleges, universities, professional schools','PREFERENTIAL'),
    ('8241','education','Correspondence schools','PREFERENTIAL'),
    ('8244','education','Business and secretarial schools','PREFERENTIAL'),
    ('8249','education','Vocational and trade schools','PREFERENTIAL'),
    ('8299','education','Schools and educational services, not elsewhere classified','PREFERENTIAL'),
    ('5812','restaurant','Eating places and restaurants','NORMAL'),
    ('5813','restaurant','Drinking places — bars, taverns, lounges','NORMAL'),
    ('5814','qsr','Fast food restaurants','NORMAL'),
    ('5411','supermarket','Grocery stores and supermarkets','NORMAL'),
    ('5422','supermarket','Freezer and locker meat provisioners','NORMAL'),
    ('5441','supermarket','Candy, nut and confectionery stores','NORMAL'),
    ('5451','supermarket','Dairy products stores','NORMAL'),
    ('5462','supermarket','Bakeries','NORMAL'),
    ('5499','supermarket','Miscellaneous food stores — convenience and specialty','NORMAL'),
    ('5311','retail','Department stores','NORMAL'),
    ('5331','retail','Variety stores','NORMAL'),
    ('5399','retail','Miscellaneous general merchandise','NORMAL'),
    ('5611','retail','Men''s and boys'' clothing and accessories','NORMAL'),
    ('5621','retail','Women''s ready-to-wear stores','NORMAL'),
    ('5651','retail','Family clothing stores','NORMAL'),
    ('5661','retail','Shoe stores','NORMAL'),
    ('5691','retail','Men''s and women''s clothing stores','NORMAL'),
    ('5712','retail','Furniture and home furnishings','NORMAL'),
    ('5722','retail','Household appliance stores','NORMAL'),
    ('5732','retail','Electronics stores','NORMAL'),
    ('5912','retail','Drug stores and pharmacies','NORMAL'),
    ('5944','retail','Jewellery, watch, clock and silverware stores','NORMAL'),
    ('5945','retail','Hobby, toy and game shops','NORMAL'),
    ('5977','retail','Cosmetic stores','NORMAL'),
    ('5999','retail','Miscellaneous and specialty retail stores','NORMAL'),
    ('7011','retail','Lodging — hotels, motels, resorts','NORMAL'),
    ('7230','retail','Beauty and barber shops','NORMAL'),
    ('7832','retail','Motion picture theatres','NORMAL'),
    ('8011','retail','Doctors and physicians','NORMAL'),
    ('8021','retail','Dentists and orthodontists','NORMAL'),
    ('8062','retail','Hospitals','NORMAL'),
    ('5541','retail','Service stations','PREFERENTIAL'),
    ('5542','retail','Automated fuel dispensers','PREFERENTIAL'),
    ('4784','retail','Tolls and bridge fees','PREFERENTIAL'),
    ('7523','retail','Parking lots and garages','NORMAL'),
    ('5511','retail','Car and truck dealers — sales, service, repairs','NORMAL'),
    ('5521','retail','Car and truck dealers — used only','NORMAL'),
    ('6513','retail','Real estate agents and managers — rentals','PREFERENTIAL'),
    ('6051','retail','Non-financial institutions — money orders, travellers cheques','NORMAL'),
    ('8398','retail','Charitable and social service organisations','PREFERENTIAL')
) AS v(mcc, sector, description, mcc_type)
WHERE c.country_code IN ('BH','AE')
  AND NOT EXISTS (
      SELECT 1 FROM pb_mcc m
       WHERE m.country_code = c.country_code AND m.mcc = v.mcc);

-- ---------------------------------------------------------------------------
-- 12. Seed: the special rules
-- ---------------------------------------------------------------------------

-- UAE local debit: IC splits at a 5,000 AED ticket. Below it, -0.75% normal /
-- -0.50% preferential; at or above it, -0.60% POS / -0.90% e-commerce. The
-- mcc = NULL rows are the default; the per-MCC rows below outrank them.
INSERT INTO pb_special_rule (country_code, mcc, scheme, locality, card_type, channel,
       mcc_type, rule_type, threshold, small_rate, large_rate, priority, note)
SELECT v.* FROM (VALUES
    ('AE',NULL,'DEBIT','LOCAL','DEBIT','POS','NORMAL','TICKET_SPLIT',5000.0000,-0.007500,-0.006000,10,'UAE debit local, POS, standard MCC'),
    ('AE',NULL,'DEBIT','LOCAL','DEBIT','ECOM','NORMAL','TICKET_SPLIT',5000.0000,-0.007500,-0.009000,10,'UAE debit local, e-commerce, standard MCC'),
    ('AE',NULL,'DEBIT','LOCAL','DEBIT','POS','PREFERENTIAL','TICKET_SPLIT',5000.0000,-0.005000,-0.005000,10,'UAE debit local, preferential MCC'),
    ('AE',NULL,'DEBIT','LOCAL','DEBIT','ECOM','PREFERENTIAL','TICKET_SPLIT',5000.0000,-0.005000,-0.005000,10,'UAE debit local, preferential MCC')
) AS v(country_code, mcc, scheme, locality, card_type, channel, mcc_type, rule_type,
       threshold, small_rate, large_rate, priority, note)
WHERE NOT EXISTS (
    SELECT 1 FROM pb_special_rule r
     WHERE r.country_code = v.country_code AND r.mcc IS NULL
       AND r.scheme = v.scheme AND r.locality = v.locality
       AND r.channel = v.channel AND r.mcc_type = v.mcc_type
       AND r.rule_type = v.rule_type);

-- Government / transport MCCs carry their own large-ticket debit rate.
INSERT INTO pb_special_rule (country_code, mcc, scheme, locality, card_type,
       rule_type, threshold, small_rate, large_rate, priority, note)
SELECT 'AE', v.mcc, 'DEBIT', 'LOCAL', 'DEBIT', 'TICKET_SPLIT', 5000.0000, -0.007500, v.large_rate, 20, v.note
FROM (VALUES
    ('9223',-0.003500,'Government large-ticket debit rate'),
    ('9211',-0.003500,'Government large-ticket debit rate'),
    ('9222',-0.003500,'Government large-ticket debit rate'),
    ('9399',-0.003500,'Government large-ticket debit rate'),
    ('9311',-0.003500,'Government large-ticket debit rate'),
    ('4899',-0.003500,'Utilities large-ticket debit rate'),
    ('4816',-0.003500,'Utilities large-ticket debit rate'),
    ('4814',-0.003500,'Utilities large-ticket debit rate'),
    ('4900',-0.003500,'Utilities large-ticket debit rate'),
    ('7523',-0.004500,'Parking large-ticket debit rate'),
    ('4131',-0.004500,'Transport large-ticket debit rate'),
    ('4111',-0.004500,'Transport large-ticket debit rate'),
    ('4112',-0.004500,'Transport large-ticket debit rate')
) AS v(mcc, large_rate, note)
WHERE NOT EXISTS (
    SELECT 1 FROM pb_special_rule r
     WHERE r.country_code = 'AE' AND r.mcc = v.mcc
       AND r.scheme = 'DEBIT' AND r.locality = 'LOCAL'
       AND r.rule_type = 'TICKET_SPLIT' AND r.channel IS NULL);

-- UAE motor trade: local credit splits at a 36,700 AED ticket.
INSERT INTO pb_special_rule (country_code, mcc, locality, card_type,
       rule_type, threshold, small_rate, large_rate, priority, note)
SELECT 'AE', v.mcc, 'LOCAL', 'CREDIT', 'TICKET_SPLIT', 36700.0000, -0.015000, -0.009000, 20, v.note
FROM (VALUES
    ('5511','Motor trade local credit split'),
    ('5521','Motor trade local credit split')
) AS v(mcc, note)
WHERE NOT EXISTS (
    SELECT 1 FROM pb_special_rule r
     WHERE r.country_code = 'AE' AND r.mcc = v.mcc
       AND r.locality = 'LOCAL' AND r.card_type = 'CREDIT'
       AND r.rule_type = 'TICKET_SPLIT');

-- Per-transaction and flat overrides.
INSERT INTO pb_special_rule (country_code, mcc, scheme, locality, card_type,
       rule_type, flat_rate, amount_local, amount_is_usd, priority, note)
SELECT v.* FROM (VALUES
    -- 6051 local debit is priced per transaction, at AED 2. The quote's MSF
    -- input for this row is read as AED per transaction too, not a rate.
    ('AE','6051','DEBIT','LOCAL','DEBIT','PER_TXN',NULL,-2.000000,FALSE,30,'Money transfer: AED 2 per transaction'),
    ('AE','6051','MASTERCARD','LOCAL','CREDIT','FLAT_PCT',-0.011600,NULL,FALSE,30,'Money transfer local credit'),
    ('AE','6051','VISA','LOCAL','CREDIT','FLAT_PCT',-0.011600,NULL,FALSE,30,'Money transfer local credit'),
    -- Charity: Mastercard is a flat USD amount per transaction, Visa a rate.
    ('AE','8398','MASTERCARD','LOCAL','CREDIT','PER_TXN',NULL,-0.250000,TRUE,30,'Charity: USD 0.25 per transaction'),
    ('AE','8398','VISA','LOCAL','CREDIT','FLAT_PCT',-0.006500,NULL,FALSE,30,'Charity local credit'),
    ('AE','6513','MASTERCARD','LOCAL','CREDIT','FLAT_PCT',-0.006500,NULL,FALSE,30,'Real estate local credit'),
    ('AE','6513','VISA','LOCAL','CREDIT','FLAT_PCT',-0.006500,NULL,FALSE,30,'Real estate local credit')
) AS v(country_code, mcc, scheme, locality, card_type, rule_type,
       flat_rate, amount_local, amount_is_usd, priority, note)
WHERE NOT EXISTS (
    SELECT 1 FROM pb_special_rule r
     WHERE r.country_code = v.country_code AND r.mcc = v.mcc
       AND r.scheme = v.scheme AND r.locality = v.locality
       AND r.rule_type = v.rule_type);

-- ---------------------------------------------------------------------------
-- 13. Menu — new MIS category
--
-- MIS holds the pre-sale and cost-control tools: they price and police the
-- scheme relationship rather than report the book, which is why they do not
-- belong under BUSINESS or FINANCE.
--
-- The API is gated by @menuAccess.canAccess('/mis/price-builder'), so this
-- grant IS the access control, not just sidebar visibility.
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Price Builder', '/mis/price-builder', 'Calculator', 'MIS', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/mis/price-builder');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/mis/price-builder'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'Business User')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/mis/price-builder'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_11_12_01__mis_price_builder.sql') ON CONFLICT (filename) DO NOTHING;
