-- ============================================================================
-- V2026_08_08_06: 8-digit BIN mapping table + card-type-source tenant flag
--                 + Super-Admin "BIN Management" menu.
--
-- SCOPE (business-confirmed 2026-08-08): CONFIGURATION ONLY this phase.
--   * ref_bin holds the 8-digit BIN -> scheme / card type / product / issuer
--     country mapping, loaded by Super Admin from uploaded files (CSV/XLSX)
--     on the new BIN Management screen. Full-refresh or append.
--   * tenant.card_type_source selects WHERE the card product/type for that
--     tenant's transactions should come from:
--         'FILE' (default) -> the card type/product columns in the uploaded
--                             transaction file (today's behaviour, unchanged)
--         'BIN'            -> the ref_bin 8-digit mapping
--     Applies to any tenant (CMM or AMS). NO ingestion/fee logic reads this
--     flag yet — wiring it into enrichment is a later, deliberate phase once
--     the default and per-source rules are decided.
--
-- Idempotent + splitter-safe.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ref_bin (
    bin            VARCHAR(8) PRIMARY KEY,   -- exactly 8 digits (2022 mandate)
    scheme         VARCHAR(30),              -- VISA / MASTERCARD / ...
    card_type      VARCHAR(20),              -- DEBIT / CREDIT / PREPAID
    product_code   VARCHAR(30),              -- scheme product (VISD, MCPM, ...)
    issuer_country VARCHAR(2),               -- ISO 3166-1 alpha-2
    issuer_name    VARCHAR(150),
    source_file    VARCHAR(200),             -- filename of the load that wrote this row
    loaded_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ref_bin_scheme ON ref_bin (scheme);
CREATE INDEX IF NOT EXISTS idx_ref_bin_country ON ref_bin (issuer_country);

ALTER TABLE tenant ADD COLUMN IF NOT EXISTS card_type_source VARCHAR(10) NOT NULL DEFAULT 'FILE';
UPDATE tenant SET card_type_source = 'FILE' WHERE card_type_source IS NULL;
ALTER TABLE tenant DROP CONSTRAINT IF EXISTS chk_tenant_card_type_source;
ALTER TABLE tenant
    ADD CONSTRAINT chk_tenant_card_type_source CHECK (card_type_source IN ('FILE', 'BIN'));

-- Menu: Super Admin ONLY (deliberately no 'Admin' grant — BIN data is
-- platform-wide reference data shared by every tenant).
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('BIN Management', '/admin/bin-management', 'CreditCard', 'ADMINISTRATION', 17)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
  AND m.path = '/admin/bin-management'
ON CONFLICT DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_08_06__bin_mapping_menu.sql') ON CONFLICT (filename) DO NOTHING;
