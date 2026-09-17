-- ============================================================================
-- Acquira — PROD GAP Consolidated Migration Bundle
-- Generated: 2026-07-11
--
-- WHAT THIS FILE IS
-- ------------------
-- application-prod.properties' spring.sql.init.schema-locations is missing
-- 6 files that dev's application.properties already has (flagged inline in
-- application-prod.properties as "pre-existing gap, out of scope for this
-- change; reconcile separately before the next prod deploy" — this is that
-- reconciliation). This bundle is those 6 files concatenated, IN THE SAME
-- RELATIVE ORDER they appear in dev's schema-locations list, so prod can be
-- brought to parity in one psql run instead of six.
--
-- SCOPE — files INCLUDED (6, in dev's applied order):
--   V2026_06_27_02__explorer_master_alert.sql          (explorer_master_item, explorer_alert)
--   V2026_07_02_01__budget_targets_menu.sql            (Budget Targets sidebar entry)
--   V2026_07_07_05__domestic_pos_scheme_fee.sql        (DOMESTIC POS scheme fee 0.12%->0.11%)
--   V2026_07_10_01__ref_mcc_category.sql               (ref_mcc_category, 296 MCC->sector rows)
--   V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql (sidebar reorg)
--   V2026_07_10_03__sum_daily_merchant_destination.sql (new partitioned summary table + RLS)
--
-- ORDER DEPENDENCIES
-- ------------------
--   V2026_07_07_05 assumes the DOMESTIC POS row seeded at 0.001200 already
--   exists (from V2026_07_07_01, which IS already on prod per
--   application-prod.properties) — safe. All 6 files are otherwise independent
--   of each other.
--
-- DUPLICATION SAFETY
-- ------------------
-- Every statement is idempotent as originally authored (CREATE TABLE IF NOT
-- EXISTS, ON CONFLICT DO NOTHING, absolute-value UPDATE ... WHERE old-value).
-- Nothing was rewritten. Safe to re-run; second run is a no-op. No DO $$
-- blocks (splitter-safe), matching every other file already in
-- schema-locations.
--
-- AFTER RUNNING THIS
-- -------------------
-- Add the matching 6 lines to application-prod.properties'
-- spring.sql.init.schema-locations (see the diff at the bottom of this
-- comment block / the accompanying note) so future prod deploys with
-- mode=never don't silently skip these on a fresh box, and so the "reconcile
-- separately" gap comment in application-prod.properties can be deleted.
--
-- HOW TO RUN
-- ----------
--   psql -v ON_ERROR_STOP=1 --single-transaction -f PROD_GAP_CONSOLIDATED.sql "%DB_URL%"
-- ============================================================================

-- ── Migration tracking table (audit log only, mirrors ALL_MIGRATIONS_CONSOLIDATED.sql) ──
CREATE TABLE IF NOT EXISTS schema_migration_log (
    filename    VARCHAR(200) PRIMARY KEY,
    applied_at  TIMESTAMP NOT NULL DEFAULT NOW()
);


-- ############################################################################
-- FILE 1/6: V2026_06_27_02__explorer_master_alert.sql
-- ############################################################################
-- ============================================================================
-- V2026_06_27_02 — Data Explorer governance: master items + threshold alerts
--
-- Idempotent. Safe to run on production (spring.sql.init.mode=never), where
-- schema.sql is NOT auto-applied. Run once after deploying the Phase 4.x build.
-- ============================================================================

CREATE TABLE IF NOT EXISTS explorer_master_item (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL,
    item_type    VARCHAR(20) NOT NULL,
    item_key     VARCHAR(120) NOT NULL,
    label        VARCHAR(160) NOT NULL,
    definition   TEXT,
    description  VARCHAR(255),
    created_by   VARCHAR(120),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_master_item UNIQUE (tenant_id, item_type, item_key)
);
CREATE INDEX IF NOT EXISTS idx_master_item_tenant ON explorer_master_item (tenant_id);

CREATE TABLE IF NOT EXISTS explorer_alert (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL,
    name              VARCHAR(160) NOT NULL,
    measure_key       VARCHAR(120) NOT NULL,
    calc_json         TEXT,
    filter_json       TEXT,
    window_days       INTEGER DEFAULT 1,
    operator          VARCHAR(4) NOT NULL,
    threshold         DOUBLE PRECISION NOT NULL,
    severity          VARCHAR(20) DEFAULT 'WARNING',
    recipients        TEXT,
    is_enabled        BOOLEAN DEFAULT TRUE,
    last_value        DOUBLE PRECISION,
    last_checked_at   TIMESTAMP,
    last_triggered_at TIMESTAMP,
    created_by        VARCHAR(120),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_explorer_alert_enabled ON explorer_alert (is_enabled);
CREATE INDEX IF NOT EXISTS idx_explorer_alert_tenant ON explorer_alert (tenant_id);

ANALYZE explorer_master_item;
ANALYZE explorer_alert;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_06_27_02__explorer_master_alert.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 2/6: V2026_07_02_01__budget_targets_menu.sql
-- ############################################################################
-- ============================================================
-- V2026_07_02_01: Budget Targets menu entry
-- Actual-vs-budget attainment page. Targets are entered here and
-- compared against sum_monthly_bank actuals by BudgetTargetController.
-- Route: /business/budget-targets  (ADMIN / SUPER_ADMIN only)
-- Placed in the BUSINESS category, after the analytics screens.
-- ============================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Budget Targets', '/business/budget-targets', 'Target', 'BUSINESS', 18)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path = '/business/budget-targets'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path = '/business/budget-targets'
ON CONFLICT DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_02_01__budget_targets_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 3/6: V2026_07_07_05__domestic_pos_scheme_fee.sql
-- ############################################################################
-- ============================================================================
-- V2026_07_07_05: Domestic POS scheme fee 0.12% -> 0.11%.
--
-- Business change: DOMESTIC POS scheme fee for the card networks
-- (Visa / MasterCard / Amex / unmapped-wildcard) moves 0.12% -> 0.11%.
-- JCB / UnionPay stay on their flat 0.05% (untouched).
--
-- BACKFILL: scheme fee is computed at ingest - re-upload affected months to
-- recompute with the new rate.
-- ============================================================================

UPDATE scheme_fee_rate s
SET fee_pct = 0.001100
FROM tenant t
WHERE s.tenant_id = t.tenant_id
  AND t.bank_short_code = 'ACQ'
  AND s.dest = 'DOMESTIC'
  AND s.channel = 'POS'
  AND (s.scheme_group IN ('Visa', 'MasterCard', 'Amex') OR s.scheme_group IS NULL)
  AND s.fee_pct = 0.001200;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_07_05__domestic_pos_scheme_fee.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 4/6: V2026_07_10_01__ref_mcc_category.sql
-- ############################################################################
-- V2026_07_10_01__ref_mcc_category.sql
-- Global reference table mapping MCC -> business sector/category, seeded from
-- the bank-provided MCC sector sheet (Mcc 1.xlsx, 296 codes, 39 sectors).
-- Replaces the ISO-18245 range-band CASE previously inlined in
-- VolumeRevenueRepository.getMerchantAnalyticsReport (industry column).
-- Reference data (like ref_country / ref_card_scheme): no tenant_id.

CREATE TABLE IF NOT EXISTS ref_mcc_category (
    mcc      VARCHAR(4)   PRIMARY KEY,
    category VARCHAR(100) NOT NULL
);

INSERT INTO ref_mcc_category (mcc, category) VALUES
('742', 'Hospitals/Clinics'),
('763', 'Other services'),
('780', 'Other services'),
('1520', 'Other services'),
('1711', 'Other services'),
('1731', 'Other services'),
('1740', 'Other services'),
('1750', 'Other services'),
('1761', 'Other services'),
('1771', 'Other services'),
('1799', 'Other services'),
('2741', 'Other services'),
('2791', 'Other services'),
('2842', 'Other services'),
('3013', 'Airlines'),
('3026', 'Airlines'),
('3034', 'Airlines'),
('3070', 'Airlines'),
('3266', 'Airlines'),
('3355', 'Car rental'),
('3366', 'Car rental'),
('3381', 'Car rental'),
('3389', 'Car rental'),
('3390', 'Car rental'),
('3395', 'Car rental'),
('3412', 'Car rental'),
('3501', 'Hotels'),
('3503', 'Hotels'),
('3504', 'Hotels'),
('3506', 'Hotels'),
('3509', 'Hotels'),
('3512', 'Hotels'),
('3513', 'Hotels'),
('3519', 'Hotels'),
('3520', 'Hotels'),
('3530', 'Hotels'),
('3533', 'Hotels'),
('3543', 'Hotels'),
('3545', 'Hotels'),
('3553', 'Hotels'),
('3579', 'Hotels'),
('3583', 'Hotels'),
('3590', 'Hotels'),
('3612', 'Hotels'),
('3619', 'Hotels'),
('3640', 'Hotels'),
('3641', 'Hotels'),
('3642', 'Hotels'),
('3645', 'Hotels'),
('3649', 'Hotels'),
('3657', 'Hotels'),
('3690', 'Hotels'),
('3710', 'Hotels'),
('3722', 'Hotels'),
('3741', 'Hotels'),
('3745', 'Hotels'),
('3750', 'Hotels'),
('3778', 'Hotels'),
('3790', 'Hotels'),
('3807', 'Hotels'),
('3811', 'Hotels'),
('3812', 'Hotels'),
('3826', 'Hotels'),
('4011', 'Others'),
('4111', 'Government Services'),
('4119', 'Other services'),
('4121', 'Government Services'),
('4131', 'Government Services'),
('4214', 'Other services'),
('4215', 'Other services'),
('4225', 'Other retail shops'),
('4411', 'Other services'),
('4457', 'Other services'),
('4468', 'Other services'),
('4511', 'Airlines'),
('4582', 'Other services'),
('4722', 'Travel Agencies'),
('4789', 'Car rental'),
('4812', 'Mobile Phones'),
('4814', 'Other services'),
('4816', 'Other services'),
('4899', 'Other services'),
('4900', 'Government Services'),
('5013', 'Car Repairs/Maintenance'),
('5021', 'Furniture'),
('5039', 'Other retail shops'),
('5044', 'Hi Fi/Photo/Camera/Electronics'),
('5045', 'Hi Fi/Photo/Camera/Electronics'),
('5046', 'Other retail shops'),
('5047', 'Other retail shops'),
('5051', 'Other retail shops'),
('5065', 'Other retail shops'),
('5072', 'Other retail shops'),
('5074', 'Other retail shops'),
('5085', 'Others'),
('5094', 'Other retail shops'),
('5099', 'Other retail shops'),
('5111', 'Other retail shops'),
('5122', 'Other retail shops'),
('5131', 'Other retail shops'),
('5137', 'Clothing/Boutiques'),
('5139', 'Other retail shops'),
('5169', 'Other retail shops'),
('5172', 'Others'),
('5192', 'Other retail shops'),
('5193', 'Florist supplies, nursery stock, and flowers'),
('5198', 'Other retail shops'),
('5199', 'Other retail shops'),
('5200', 'Other retail shops'),
('5211', 'Other retail shops'),
('5231', 'Other retail shops'),
('5251', 'Other retail shops'),
('5261', 'Other retail shops'),
('5271', 'Other retail shops'),
('5309', 'Duty Free'),
('5310', 'Other retail shops'),
('5311', 'Other retail shops'),
('5331', 'Other retail shops'),
('5399', 'Other retail shops'),
('5411', 'Supermarkets'),
('5422', 'Other retail shops'),
('5441', 'Other retail shops'),
('5451', 'Other retail shops'),
('5462', 'Other retail shops'),
('5499', 'Other retail shops'),
('5511', 'Automobiles'),
('5521', 'Automobiles'),
('5532', 'Car Repairs/Maintenance'),
('5533', 'Car Repairs/Maintenance'),
('5541', 'Petrol/Gas Stations'),
('5551', 'Other services'),
('5571', 'Car rental'),
('5599', 'Car rental'),
('5611', 'Clothing/Boutiques'),
('5621', 'Clothing/Boutiques'),
('5631', 'Clothing/Boutiques'),
('5641', 'Clothing/Boutiques'),
('5651', 'Clothing/Boutiques'),
('5655', 'Clothing/Boutiques'),
('5661', 'Clothing/Boutiques'),
('5681', 'Clothing/Boutiques'),
('5691', 'Clothing/Boutiques'),
('5697', 'Clothing/Boutiques'),
('5698', 'Clothing/Boutiques'),
('5699', 'Clothing/Boutiques'),
('5712', 'Furniture'),
('5713', 'Other retail shops'),
('5714', 'Other retail shops'),
('5718', 'Other services'),
('5719', 'Furniture'),
('5722', 'Hi Fi/Photo/Camera/Electronics'),
('5732', 'Hi Fi/Photo/Camera/Electronics'),
('5733', 'Hi Fi/Photo/Camera/Electronics'),
('5734', 'Hi Fi/Photo/Camera/Electronics'),
('5735', 'Hi Fi/Photo/Camera/Electronics'),
('5811', 'Restaurants'),
('5812', 'Restaurants'),
('5813', 'Entertainment/Nightclubs'),
('5814', 'QSR'),
('5912', 'Pharmacies'),
('5921', 'Other retail shops'),
('5931', 'Other retail shops'),
('5932', 'Other retail shops'),
('5937', 'Other retail shops'),
('5940', 'Other retail shops'),
('5941', 'Other retail shops'),
('5942', 'Book stores'),
('5943', 'Other retail shops'),
('5944', 'Jewellery/Watches/Clocks'),
('5945', 'Other retail shops'),
('5946', 'Hi Fi/Photo/Camera/Electronics'),
('5947', 'Other retail shops'),
('5948', 'Other retail shops'),
('5949', 'Other retail shops'),
('5950', 'Other retail shops'),
('5960', 'Insurance'),
('5962', 'Other services'),
('5964', 'Other services'),
('5965', 'Other retail shops'),
('5968', 'Other services'),
('5969', 'Other services'),
('5970', 'Other retail shops'),
('5971', 'Other retail shops'),
('5972', 'Other retail shops'),
('5975', 'Other services'),
('5976', 'Other retail shops'),
('5977', 'Cosmetic Stores'),
('5978', 'Other retail shops'),
('5983', 'Petrol/Gas Stations'),
('5992', 'Florists'),
('5993', 'Other retail shops'),
('5994', 'Subscriptions/Memberships'),
('5995', 'Other retail shops'),
('5996', 'Other retail shops'),
('5997', 'Other retail shops'),
('5998', 'Other retail shops'),
('5999', 'Other retail shops'),
('6010', 'Financial'),
('6012', 'Accounting Services'),
('6051', 'Financial'),
('6211', 'Accounting Services'),
('6300', 'Insurance'),
('6513', 'Real Estate Services'),
('7011', 'Hotels'),
('7032', 'Hotels'),
('7210', 'Laundry, cleaning, and garment services'),
('7211', 'Laundry services: Family and commercial'),
('7216', 'Dry cleaners'),
('7221', 'Photographic studios'),
('7230', 'Cosmetic Stores'),
('7251', 'Other retail shops'),
('7276', 'Other services'),
('7277', 'Other services'),
('7296', 'Clothing/Boutiques'),
('7297', 'Health and Beauty'),
('7298', 'Health and Beauty'),
('7299', 'Other services'),
('7311', 'Other services'),
('7321', 'Accounting Services'),
('7333', 'Other services'),
('7338', 'Other services'),
('7339', 'Other services'),
('7342', 'Other services'),
('7349', 'Other services'),
('7361', 'Other services'),
('7372', 'Other services'),
('7375', 'Other retail shops'),
('7379', 'Other services'),
('7392', 'Other services'),
('7393', 'Other services'),
('7394', 'Other retail shops'),
('7395', 'Hi Fi/Photo/Camera/Electronics'),
('7399', 'Accounting Services'),
('7512', 'Car rental'),
('7519', 'Car rental'),
('7523', 'Car Repairs/Maintenance'),
('7531', 'Car Repairs/Maintenance'),
('7534', 'Car Repairs/Maintenance'),
('7535', 'Car Repairs/Maintenance'),
('7538', 'Car Repairs/Maintenance'),
('7542', 'Car Repairs/Maintenance'),
('7549', 'Car Repairs/Maintenance'),
('7622', 'Hi Fi/Photo/Camera/Electronics'),
('7623', 'Hi Fi/Photo/Camera/Electronics'),
('7629', 'Hi Fi/Photo/Camera/Electronics'),
('7631', 'Jewellery/Watches/Clocks'),
('7641', 'Furniture'),
('7699', 'Other services'),
('7829', 'Other services'),
('7832', 'Entertainment/Nightclubs'),
('7841', 'Other retail shops'),
('7911', 'Other services'),
('7922', 'Other retail shops'),
('7929', 'Other services'),
('7932', 'Other services'),
('7933', 'Other services'),
('7941', 'Other services'),
('7991', 'Other services'),
('7992', 'Other services'),
('7993', 'Other retail shops'),
('7994', 'Other services'),
('7996', 'Other services'),
('7997', 'Subscriptions/Memberships'),
('7998', 'Other services'),
('7999', 'Other services'),
('8011', 'Hospitals/Clinics'),
('8021', 'Hospitals/Clinics'),
('8031', 'Hospitals/Clinics'),
('8041', 'Hospitals/Clinics'),
('8042', 'Hospitals/Clinics'),
('8043', 'Hospitals/Clinics'),
('8049', 'Hospitals/Clinics'),
('8050', 'Hospitals/Clinics'),
('8062', 'Hospitals/Clinics'),
('8071', 'Hospitals/Clinics'),
('8099', 'Hospitals/Clinics'),
('8111', 'Other services'),
('8211', 'Education Services'),
('8220', 'Education Services'),
('8244', 'Education Services'),
('8249', 'Education Services'),
('8299', 'Education Services'),
('8351', 'Other services'),
('8398', 'Charity'),
('8641', 'Others'),
('8661', 'Charity'),
('8699', 'Subscriptions/Memberships'),
('8734', 'Other services'),
('8911', 'Other services'),
('8931', 'Accounting Services'),
('8999', 'Accounting Services'),
('9211', 'Other retail shops'),
('9222', 'Government Services'),
('9399', 'Government Services'),
('9402', 'Government Services'),
('9405', 'Government Services')
ON CONFLICT (mcc) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_10_01__ref_mcc_category.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 5/6: V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql
-- ############################################################################
-- V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql
-- Sidebar reorganization (sys_menu is DB-driven; schema.sql seeds are
-- ON CONFLICT (path) DO NOTHING and never UPDATE an existing row, and prod
-- runs spring.sql.init.mode=never — so these changes MUST land as explicit
-- idempotent statements here).
--
--   1. Move "Finance Summary" (/finance/summary) from the FINANCE group to
--      BUSINESS. The route/page are unchanged; only the sidebar grouping moves.
--   2. Remove "Performance Trends" (/business/performance) from the sidebar
--      entirely — delete its group grants first (FK), then the menu row.

-- 1. Finance Summary -> BUSINESS group.
UPDATE sys_menu
   SET category = 'BUSINESS',
       display_order = 17
 WHERE path = '/finance/summary';

-- 2. Drop Performance Trends. Remove grants first to satisfy the
--    sys_group_menu.menu_id FK, then the menu row itself.
DELETE FROM sys_group_menu
 WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE path = '/business/performance');

DELETE FROM sys_menu
 WHERE path = '/business/performance';

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 6/6: V2026_07_10_03__sum_daily_merchant_destination.sql
-- ############################################################################
-- ============================================================================
-- V2026_07_10_03: sum_daily_merchant_destination — merchant x destination
--   (DOMESTIC/INTERNATIONAL) pre-aggregate, WITH real fees.
--
-- GRAIN: one row per (tenant, business_date, merchant_id, destination).
-- MEASURES: settlement currency (store_base_currency_amount), never cardholder.
-- POPULATION: written by populateSummaryStep (TransactionJobConfig), same
-- pass as sum_daily_merchant, straight off fact_transaction.
-- PARTITIONING: yearly RANGE on business_date, y2024..y2027 + _default.
-- ============================================================================

CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination (
    summary_id       BIGSERIAL,
    tenant_id        INT NOT NULL,
    business_date    DATE NOT NULL,
    merchant_id      BIGINT,
    destination      VARCHAR(20) NOT NULL,   -- DOMESTIC / INTERNATIONAL

    total_txns       BIGINT DEFAULT 0,
    total_volume     DECIMAL(19, 2) DEFAULT 0,   -- settlement (store_base_currency_amount)
    total_msf        DECIMAL(19, 2) DEFAULT 0,
    total_interchange DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee DECIMAL(19, 2) DEFAULT 0,
    total_ecom_fee   DECIMAL(19, 2) DEFAULT 0,
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, destination)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2024
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2025
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2026
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2027
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_default
    PARTITION OF sum_daily_merchant_destination DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_merch_dest_tenant_date_merch
    ON sum_daily_merchant_destination (tenant_id, business_date, merchant_id);
CREATE INDEX IF NOT EXISTS idx_sum_merch_dest_tenant_date_dest
    ON sum_daily_merchant_destination (tenant_id, business_date, destination);

ALTER TABLE sum_daily_merchant_destination ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_merchant_destination;
CREATE POLICY tenant_isolation_policy ON sum_daily_merchant_destination
    USING (tenant_id = get_current_tenant());

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_10_03__sum_daily_merchant_destination.sql') ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- END OF BUNDLE — 6/6 files applied. Prod's applied migration set is now at
-- parity with dev's schema-locations list. Update
-- application-prod.properties' schema-locations to add these 6 entries (see
-- accompanying diff) so a fresh prod box lands them automatically too, then
-- delete the "pre-existing gap" reconcile comment there.
-- ============================================================================
