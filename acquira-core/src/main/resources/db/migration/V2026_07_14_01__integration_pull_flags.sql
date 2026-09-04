-- V2026_07_14_01__integration_pull_flags.sql
-- DB-pull (Integration) hardening flags.
--
-- 1) integration_report.amounts_minor_units — when TRUE, amounts pulled from the
--    external DB are in minor units (fils/halalas) and the pull normalization
--    step divides txn/store-base amounts by ref_country.decimal_notation_value
--    (and interchange by 10000), mirroring the CMM file path. Default FALSE:
--    external core-banking queries normally return final decimal amounts.
--
-- 2) integration_connection.trust_server_cert — MSSQL only. Previously the JDBC
--    URL hardcoded trustServerCertificate=true; now configurable per connection
--    (default TRUE preserves existing behaviour).

CREATE TABLE IF NOT EXISTS schema_migration_log (
    filename    VARCHAR(200) PRIMARY KEY,
    applied_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE integration_report     ADD COLUMN IF NOT EXISTS amounts_minor_units BOOLEAN DEFAULT FALSE;
ALTER TABLE integration_connection ADD COLUMN IF NOT EXISTS trust_server_cert   BOOLEAN DEFAULT TRUE;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_14_01__integration_pull_flags.sql') ON CONFLICT (filename) DO NOTHING;
