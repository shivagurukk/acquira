-- ============================================================================
-- OPTIONAL / MANUAL MIGRATION -- FORCE ROW LEVEL SECURITY (tenant backstop)
-- ============================================================================
--
-- STATUS: NOT wired into spring.sql.init.schema-locations. This file will NOT
--         run automatically on startup. Apply it by hand, deliberately, only
--         after the precondition below is verified. It is named
--         OPTIONAL__... (double underscore, no version-date prefix) precisely
--         so it is never picked up by any V<date>__ ordering convention.
--
-- ----------------------------------------------------------------------------
-- WHY THIS EXISTS
-- ----------------------------------------------------------------------------
-- schema.sql runs `ALTER TABLE <t> ENABLE ROW LEVEL SECURITY` + a
-- `tenant_isolation_policy USING (tenant_id = get_current_tenant())` on every
-- business table. However, PostgreSQL EXEMPTS THE TABLE OWNER from RLS unless
-- the table is ALSO put into FORCE mode. The application connects as the
-- schema/table owner, so in practice RLS has been a NO-OP for the app: the only
-- thing actually isolating tenants is the explicit `WHERE tenant_id = ?` that
-- each query carries. When getCurrentTenantId() returned the wrong tenant
-- (fixed 2026 in TenantService: it ignored the switched X-Tenant-Id and used
-- the user's DB default), RLS did NOT catch the leak -- because RLS was being
-- bypassed by the owner. This migration turns RLS into a real backstop so a
-- single missed/incorrect tenant filter can no longer leak cross-tenant rows.
--
-- ----------------------------------------------------------------------------
-- PRECONDITION -- MUST be true before applying, or ingestion WILL break
-- ----------------------------------------------------------------------------
-- Under FORCE RLS, EVERY statement -- including batch INSERT ... SELECT into
-- fact/summary/staging tables -- is filtered by the policy, for the owner too.
-- A statement that runs on a DB connection where `app.current_tenant` is NOT
-- set will see get_current_tenant() = NULL, the policy `tenant_id = NULL`
-- evaluates to NULL (not true), and the statement silently affects ZERO rows.
-- That means: reads return empty, and (critically) batch writes insert nothing.
--
-- TenantAspect sets app.current_tenant via set_config(..., false) around every
-- method matching `com.acquira..service..*` and `com.acquira..repository..*`.
-- Before forcing RLS you MUST confirm that ALL write paths to the tables listed
-- below flow through those pointcuts on the SAME connection that runs the SQL.
-- In particular re-check:
--   * acquira-batch tasklets that use raw JdbcTemplate / EntityManager native
--     SQL (staging -> fact, summary population, monthly rollups, delete-day)
--   * any @Async / scheduled worker (context is thread-local; it does NOT
--     propagate to async threads automatically)
--   * MigrationController delete-day + backfill jobs
-- Verify by running one real MERCHANT + TRANSACTION upload on a copy of the DB
-- WITH this migration applied, and confirming fact_transaction + every sum_*
-- table populate exactly as before. If any table comes back empty, a write
-- path is missing tenant context -- fix that FIRST, do not force RLS yet.
--
-- ----------------------------------------------------------------------------
-- HOW TO APPLY (manually, e.g. psql)
-- ----------------------------------------------------------------------------
--   psql "$DATABASE_URL" -f OPTIONAL__force_rls_backstop.sql
-- Roll back with the companion block at the bottom (commented) if ingestion
-- misbehaves.
--
-- Idempotent: re-running is safe. FORCE is set only on tables that already have
-- RLS ENABLED, so this never gets ahead of schema.sql.
-- ============================================================================

DO '
DECLARE
    t text;
    rls_tables text[] := ARRAY[
        ''stg_merchant_master_raw'', ''stg_trnx_raw'',
        ''dim_merchant'', ''dim_store'', ''dim_terminal'', ''dim_bank_account'',
        ''bank_budget_target'', ''merchant_lifecycle_status'',
        ''merchant_activity_summary'', ''merchant_opportunity_score'',
        ''revenue_leakage_flags'', ''merchant_contact'', ''merchant_contract'',
        ''merchant_document'', ''merchant_risk_profile'',
        ''merchant_settlement_config'', ''merchant_note'',
        ''fact_transaction'',
        ''sum_daily_bank'', ''sum_daily_channel'', ''sum_daily_finance'',
        ''sum_daily_insight'', ''sum_daily_merchant'',
        ''sum_daily_merchant_attribute'', ''sum_daily_scheme'',
        ''sum_daily_terminal'', ''sum_monthly_bank'', ''sum_monthly_card'',
        ''sum_monthly_insight'', ''sum_monthly_merchant_metrics'',
        ''merchant_daily_metrics''
    ];
BEGIN
    FOREACH t IN ARRAY rls_tables LOOP
        -- Only force tables that actually exist AND already have RLS enabled,
        -- so this migration can never run ahead of schema.sql.
        IF EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = t AND c.relrowsecurity = true
              AND n.nspname = current_schema()
        ) THEN
            EXECUTE format(''ALTER TABLE %I FORCE ROW LEVEL SECURITY'', t);
            RAISE NOTICE ''FORCE RLS enabled on %'', t;
        ELSE
            RAISE NOTICE ''Skipped % (missing or RLS not enabled)'', t;
        END IF;
    END LOOP;
END;
';

-- ----------------------------------------------------------------------------
-- ROLLBACK (uncomment and run if ingestion or reads come back empty)
-- ----------------------------------------------------------------------------
-- DO '
-- DECLARE
--     t text;
--     rls_tables text[] := ARRAY[
--         ''stg_merchant_master_raw'', ''stg_trnx_raw'',
--         ''dim_merchant'', ''dim_store'', ''dim_terminal'', ''dim_bank_account'',
--         ''bank_budget_target'', ''merchant_lifecycle_status'',
--         ''merchant_activity_summary'', ''merchant_opportunity_score'',
--         ''revenue_leakage_flags'', ''merchant_contact'', ''merchant_contract'',
--         ''merchant_document'', ''merchant_risk_profile'',
--         ''merchant_settlement_config'', ''merchant_note'',
--         ''fact_transaction'',
--         ''sum_daily_bank'', ''sum_daily_channel'', ''sum_daily_finance'',
--         ''sum_daily_insight'', ''sum_daily_merchant'',
--         ''sum_daily_merchant_attribute'', ''sum_daily_scheme'',
--         ''sum_daily_terminal'', ''sum_monthly_bank'', ''sum_monthly_card'',
--         ''sum_monthly_insight'', ''sum_monthly_merchant_metrics'',
--         ''merchant_daily_metrics''
--     ];
-- BEGIN
--     FOREACH t IN ARRAY rls_tables LOOP
--         IF EXISTS (SELECT 1 FROM pg_class WHERE relname = t) THEN
--             EXECUTE format(''ALTER TABLE %I NO FORCE ROW LEVEL SECURITY'', t);
--         END IF;
--     END LOOP;
-- END;
-- ';
