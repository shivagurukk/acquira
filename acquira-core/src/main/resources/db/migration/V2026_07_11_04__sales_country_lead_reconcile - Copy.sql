-- ============================================================================
-- V2026_07_11_04: sales_country_lead reconcile.
--
-- The sales_country_lead table was never created by any SQL script — only the
-- JPA entity (SalesCountryLead) declared it, and with ddl-auto=none that DDL
-- never runs. Existing DBs have the table from an old ad-hoc run WITHOUT the
-- (tenant_id, country_lead_email) unique constraint the entity declares, so
-- any ON CONFLICT (tenant_id, country_lead_email) — including the
-- 'default-country-lead' tenant provisioning script — fails with
-- "no unique or exclusion constraint matching the ON CONFLICT specification".
--
-- This migration makes the table + uniqueness canonical:
--   1. CREATE TABLE IF NOT EXISTS (fresh DBs get it; existing DBs skip).
--   2. Deduplicate any pre-existing (tenant_id, country_lead_email) dupes
--      (keep lowest id) so the unique index can build.
--   3. CREATE UNIQUE INDEX IF NOT EXISTS — a unique index is a valid
--      ON CONFLICT arbiter and is idempotent without DO $$ blocks.
--
-- Splitter-safe. Idempotent. On prod (mode=never) apply once via psql.
-- ============================================================================

CREATE TABLE IF NOT EXISTS sales_country_lead (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT NOT NULL,
    country_lead_name  VARCHAR(255) NOT NULL,
    country_lead_email VARCHAR(255) NOT NULL,
    country_code       VARCHAR(2),
    is_default         BOOLEAN DEFAULT FALSE,
    created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sales_country_lead_tenant ON sales_country_lead(tenant_id);

-- Deduplicate before enforcing uniqueness (keep the lowest id per pair).
DELETE FROM sales_country_lead a
USING sales_country_lead b
WHERE a.tenant_id = b.tenant_id
  AND a.country_lead_email = b.country_lead_email
  AND a.id > b.id;

-- Unique index = valid ON CONFLICT arbiter; IF NOT EXISTS keeps it idempotent
-- without a DO $$ block (splitter-safe).
CREATE UNIQUE INDEX IF NOT EXISTS uq_sales_country_lead_tenant_email
    ON sales_country_lead(tenant_id, country_lead_email);

-- Self-register.
INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_07_11_04__sales_country_lead_reconcile.sql',
        'sales_country_lead: canonical DDL + dedupe + unique index (fixes ON CONFLICT / provisioning script failure)',
        TRUE, FALSE, 'system')
ON CONFLICT (migration_name) DO NOTHING;
