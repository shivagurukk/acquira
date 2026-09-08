-- ============================================================================
-- V2026_07_11_05: sales_team_mapping.country_lead_id reconcile.
--
-- The SalesTeamMapping JPA entity declares a `country_lead_id` column (the tier
-- above team lead — nullable, teams with no country lead roll up to the
-- tenant's default). But NO SQL script ever added it: schema.sql's
-- CREATE TABLE IF NOT EXISTS sales_team_mapping (...) predates the column, and
-- because it's IF NOT EXISTS it silently skips the new definition on any DB
-- where the table already exists. With ddl-auto=none the entity DDL never runs
-- either. Result on both fresh and existing DBs: the column is absent, and:
--
--   * SalesCountryLeadService.getTeamLeadsWithStatus() ->
--     salesTeamMappingRepository.findAllByTenantId() emits
--     `select ... stm1_0.country_lead_id ... from sales_team_mapping`
--     -> ERROR: column stm1_0.country_lead_id does not exist
--     (endpoint: GET /api/sales-country-lead/team-leads)
--
--   * LeaderboardController.getCountryLeaderboard() native CTE does
--     `LEFT JOIN sales_country_lead scl ON scl.id = stm.country_lead_id`
--     -> ERROR: column stm.country_lead_id does not exist
--     (endpoint: GET /api/leaderboard/countries)
--
-- This migration lands the column independently of schema.sql. Additive:
-- nullable, no default backfill needed (NULL == "rolls up to default country
-- lead", exactly the entity's documented semantics). The companion FK target
-- table sales_country_lead is made canonical by V2026_07_11_04.
--
-- Splitter-safe (no DO $$ blocks — plain ADD COLUMN IF NOT EXISTS).
-- Idempotent. On prod (spring.sql.init.mode=never) apply once via psql.
-- ============================================================================

ALTER TABLE sales_team_mapping
    ADD COLUMN IF NOT EXISTS country_lead_id BIGINT;

-- Supports the country-lead rollup joins (LeaderboardController country CTE,
-- SalesCountryLeadService). Partial index — most teams are unmapped early on.
CREATE INDEX IF NOT EXISTS idx_sales_team_country_lead
    ON sales_team_mapping(country_lead_id)
    WHERE country_lead_id IS NOT NULL;

-- Self-register.
INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_07_11_05__sales_team_mapping_country_lead_id.sql',
        'sales_team_mapping: add country_lead_id column + partial index (fixes findAllByTenantId + country leaderboard column-does-not-exist errors)',
        TRUE, FALSE, 'system')
ON CONFLICT (migration_name) DO NOTHING;
