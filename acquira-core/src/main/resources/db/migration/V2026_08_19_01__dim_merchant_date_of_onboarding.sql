-- ============================================================================
-- V2026_08_19_01: dim_merchant.date_of_onboarding
--
-- Business onboarding date ("Date of Onboarding" in the merchant master file).
-- Distinct from created_date (CRM/ETL record-creation stamp). Open-date filters
-- and the Top Performers signed-by-RM board key off this column.
--
-- NOTE: contains a DO $$ block, so it must NOT be listed in
-- spring.sql.init.schema-locations (the script splitter cannot parse dollar
-- quotes). Fresh databases get the column from the dim_merchant CREATE TABLE
-- in schema.sql; this standalone file exists for existing databases — apply
-- once via psql. Idempotent.
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'dim_merchant' AND column_name = 'date_of_onboarding'
    ) THEN
        ALTER TABLE dim_merchant ADD COLUMN date_of_onboarding TIMESTAMP;
    END IF;
END $$;
