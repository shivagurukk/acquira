-- V2026_07_15_01__region_readiness_uae.sql
-- Region-readiness prep (Phase 1 of the multi-region interchange/scheme-fee plan).
-- Scope: UAE only, zero behavior change. Adds the columns/indexes needed so a
-- future non-UAE tenant can be onboarded via DATA (new rate-card rows) instead
-- of a schema/code change. Fee computation SQL in TransactionJobConfig is NOT
-- touched in this migration — that's Phase 2, deferred.
--
-- 1) interchange_rate_local.country_code — which country's rate card a row
--    belongs to. Backfilled 'AE' for all existing rows (100% of current data).
-- 2) interchange_rate_local.cap_currency_code — currency the cap_amount is
--    denominated in. Backfilled 'AED' (existing caps are already AED, per the
--    min_ticket_aed/max_ticket_aed column naming).
-- 3) tenant.home_country_code — resolves which rate card + which side of
--    domestic/international a tenant's transactions fall on. Backfilled by
--    matching existing country/currency; defaults 'AE' for anything unmatched
--    (safe today since the only tenant is UAE).
-- 4) idx_interchange_rate_local_generic — TransactionJobConfig's fee-lookup
--    LATERAL has had a UNION ALL branch for the mcc-IS-NULL (generic/wildcard)
--    case since the 2026-07-14 perf split, but the matching partial index was
--    never actually created — that branch has been sequential-scanning ever
--    since. Adding it now closes that gap.
-- =========================================================================

ALTER TABLE interchange_rate_local
    ADD COLUMN IF NOT EXISTS country_code      VARCHAR(2) NOT NULL DEFAULT 'AE',
    ADD COLUMN IF NOT EXISTS cap_currency_code  VARCHAR(3) NOT NULL DEFAULT 'AED';

-- Explicit backfill (belt-and-suspenders alongside the DEFAULT, in case rows
-- were inserted between column-add and this UPDATE in a concurrent deploy).
UPDATE interchange_rate_local SET country_code = 'AE' WHERE country_code IS NULL;
UPDATE interchange_rate_local SET cap_currency_code = 'AED' WHERE cap_currency_code IS NULL;

-- Idempotent via DROP-then-ADD (no DO $$ block — Spring ScriptUtils splits
-- schema-locations files on ';' and breaks on dollar-quoted bodies).
ALTER TABLE interchange_rate_local DROP CONSTRAINT IF EXISTS fk_interchange_rate_local_country;
ALTER TABLE interchange_rate_local
    ADD CONSTRAINT fk_interchange_rate_local_country
    FOREIGN KEY (country_code) REFERENCES ref_country(country_code);

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS home_country_code VARCHAR(2) NOT NULL DEFAULT 'AE';

-- Best-effort backfill from existing free-text country / base_currency for any
-- tenant that predates this migration (only matters if a non-AE tenant was
-- already inserted before this ran; today's only tenant is UAE either way).
UPDATE tenant SET home_country_code = 'AE'
WHERE home_country_code IS NULL
   OR country ILIKE '%united arab emirates%'
   OR base_currency = 'AED';

ALTER TABLE tenant DROP CONSTRAINT IF EXISTS fk_tenant_home_country;
ALTER TABLE tenant
    ADD CONSTRAINT fk_tenant_home_country
    FOREIGN KEY (home_country_code) REFERENCES ref_country(country_code);

-- Closes the missing-index gap noted above (item 4). Partial index mirrors the
-- UNION ALL branch's WHERE i.mcc IS NULL predicate exactly.
CREATE INDEX IF NOT EXISTS idx_interchange_rate_local_generic
    ON interchange_rate_local (tenant_id, dest)
    WHERE mcc IS NULL;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_15_01__region_readiness_uae.sql') ON CONFLICT (filename) DO NOTHING;
