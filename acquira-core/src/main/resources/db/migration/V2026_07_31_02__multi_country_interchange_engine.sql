-- ============================================================================
-- V2026_07_31_02: Multi-country interchange/scheme-fee engine (Phase 2).
--
-- WHY
-- ---
-- Phase 1 (V2026_07_15_01) added interchange_rate_local.country_code,
-- tenant.home_country_code and ref_country FKs but changed NO behaviour: the
-- fee-computation LATERAL in TransactionJobConfig still resolved rate rows by
-- ft.tenant_id, so every tenant used whatever rows its own tenant_id had seeded
-- (in practice the ACQ/UAE card). That does not scale: one COUNTRY can host
-- MANY tenants, and they must all price off the SAME country rate card.
--
-- Phase 2 makes rate cards COUNTRY-LEVEL and resolves them by the transaction's
-- tenant -> home_country_code, so:
--   * Onboarding a new country (OM / BH / EG / ...) is a DATA change (new rate
--     rows keyed by country_code), never a schema/code change.
--   * Every tenant whose home_country_code = 'AE' shares the one AE card.
--
-- MODEL: country-default row + optional per-tenant override
-- --------------------------------------------------------
-- On all three config tables (interchange_rate_local, scheme_fee_rate,
-- mcc_sector_map) tenant_id becomes NULLABLE:
--     tenant_id IS NULL  -> COUNTRY DEFAULT: applies to every tenant whose
--                           home_country_code = this row's country_code.
--     tenant_id = <id>   -> PER-TENANT OVERRIDE for that one tenant, same
--                           country. Resolution prefers the override
--                           ((tenant_id IS NOT NULL) DESC tiebreak) then falls
--                           back to the country default.
-- The existing UAE rows (all seeded under the ACQ tenant, country_code already
-- backfilled 'AE' in Phase 1) are converted here to country defaults
-- (tenant_id -> NULL). Because they were the ONLY rows and every existing
-- tenant is home_country 'AE', the resolved fee for existing data is IDENTICAL
-- before and after this migration.
--
-- ZERO-CHANGE GUARANTEE FOR AE
-- ----------------------------
-- After conversion, the AE card rows all carry tenant_id IS NULL. The companion
-- TransactionJobConfig change matches country_code = home_country_code AND
-- (tenant_id IS NULL OR tenant_id = ft.tenant_id). For an AE tenant the only
-- candidates are the same NULL-tenant AE rows as before; the tenant-override
-- tiebreak is inert (no override rows exist). Same rows -> same fee.
--
-- UNSEEDED COUNTRY = SAFE FALLBACK
-- -------------------------------
-- A tenant whose home_country_code has NO rate rows (e.g. OM/BH/EG until their
-- cards are seeded) matches nothing; the ingest UPDATE keeps the flat-1.85%
-- interchange fallback and a NULL scheme fee (COALESCE'd to 0), exactly like an
-- unseeded tenant today. Ingestion can never break for an unseeded country.
--
-- RUN MODEL
-- ---------
-- Splitter-safe: NO $$ dollar-quoting (Spring ScriptUtils splits on ';'), so
-- this file is safe in spring.sql.init.schema-locations and re-runs on every
-- startup. All structural ALTERs are idempotent. The ONE non-idempotent step --
-- the tenant_id -> NULL data conversion -- is guarded by schema_migration_log so
-- it runs exactly ONCE and never clobbers a per-tenant override added later.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. country_code on the two tables that still lack it (interchange_rate_local
--    got it in Phase 1). NOT NULL DEFAULT 'AE' backfills every existing row to
--    the UAE card, then an explicit UPDATE covers any NULL left by a concurrent
--    insert between column-add and default application.
-- ---------------------------------------------------------------------------
ALTER TABLE scheme_fee_rate  ADD COLUMN IF NOT EXISTS country_code VARCHAR(2) NOT NULL DEFAULT 'AE';
ALTER TABLE mcc_sector_map   ADD COLUMN IF NOT EXISTS country_code VARCHAR(2) NOT NULL DEFAULT 'AE';

UPDATE scheme_fee_rate SET country_code = 'AE' WHERE country_code IS NULL;
UPDATE mcc_sector_map  SET country_code = 'AE' WHERE country_code IS NULL;

-- FK to ref_country (DROP-then-ADD for idempotency; no DO $$ block).
ALTER TABLE scheme_fee_rate DROP CONSTRAINT IF EXISTS fk_scheme_fee_rate_country;
ALTER TABLE scheme_fee_rate
    ADD CONSTRAINT fk_scheme_fee_rate_country
    FOREIGN KEY (country_code) REFERENCES ref_country(country_code);

ALTER TABLE mcc_sector_map DROP CONSTRAINT IF EXISTS fk_mcc_sector_map_country;
ALTER TABLE mcc_sector_map
    ADD CONSTRAINT fk_mcc_sector_map_country
    FOREIGN KEY (country_code) REFERENCES ref_country(country_code);

-- ---------------------------------------------------------------------------
-- 2. tenant_id becomes NULLABLE on all three tables (NULL = country default).
--    DROP NOT NULL is idempotent (a no-op once already dropped).
-- ---------------------------------------------------------------------------
ALTER TABLE interchange_rate_local ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE scheme_fee_rate        ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE mcc_sector_map         ALTER COLUMN tenant_id DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Re-key uniqueness to include country_code and tolerate NULL tenant_id.
--    COALESCE(tenant_id, 0) folds the country-default row into a stable key so
--    a country cannot have two conflicting defaults, while still allowing one
--    override row per (real) tenant.
--
--    scheme_fee_rate old key was the 4-col index uq_scheme_fee_rate_key
--    (tenant_id, dest, channel, COALESCE(scheme_group,'')) from V2026_07_05_01.
--    mcc_sector_map old key was the inline UNIQUE (tenant_id, mcc).
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS uq_scheme_fee_rate_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_scheme_fee_rate_key
    ON scheme_fee_rate (country_code, COALESCE(tenant_id, 0), dest, channel, COALESCE(scheme_group, ''));

ALTER TABLE mcc_sector_map DROP CONSTRAINT IF EXISTS mcc_sector_map_tenant_id_mcc_key;
DROP INDEX IF EXISTS uq_mcc_sector_map_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_mcc_sector_map_key
    ON mcc_sector_map (country_code, COALESCE(tenant_id, 0), mcc);

-- ---------------------------------------------------------------------------
-- 4. Country-keyed lookup indexes mirroring the two UNION ALL branches in
--    TransactionJobConfig (which now filter on country_code, not tenant_id):
--      branch 1 (mcc-specific): (country_code, mcc) WHERE mcc IS NOT NULL
--      branch 2 (generic):      (country_code, dest) WHERE mcc IS NULL
--    plus the general priority-ordered lookup. The old tenant_id-keyed indexes
--    are left in place (harmless; used by any future per-tenant override scan).
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_interchange_rate_local_c_mcc
    ON interchange_rate_local (country_code, mcc) WHERE mcc IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_interchange_rate_local_c_generic
    ON interchange_rate_local (country_code, dest) WHERE mcc IS NULL;
CREATE INDEX IF NOT EXISTS idx_interchange_rate_local_c_lookup
    ON interchange_rate_local (country_code, dest, priority DESC);
CREATE INDEX IF NOT EXISTS idx_scheme_fee_rate_country
    ON scheme_fee_rate (country_code, dest, channel);
CREATE INDEX IF NOT EXISTS idx_mcc_sector_map_country
    ON mcc_sector_map (country_code, mcc);

-- ---------------------------------------------------------------------------
-- 5. ONE-TIME data conversion: existing UAE rows (seeded under the ACQ tenant)
--    become country defaults. Guarded by schema_migration_log so it runs once
--    and never nulls out a per-tenant override created afterwards. Scoped to
--    country_code = 'AE' -- the only card that exists at this migration.
-- ---------------------------------------------------------------------------
UPDATE interchange_rate_local SET tenant_id = NULL
WHERE country_code = 'AE' AND tenant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_07_31_02__multi_country_interchange_engine.sql');

UPDATE scheme_fee_rate SET tenant_id = NULL
WHERE country_code = 'AE' AND tenant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_07_31_02__multi_country_interchange_engine.sql');

UPDATE mcc_sector_map SET tenant_id = NULL
WHERE country_code = 'AE' AND tenant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_07_31_02__multi_country_interchange_engine.sql');

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_31_02__multi_country_interchange_engine.sql') ON CONFLICT (filename) DO NOTHING;
