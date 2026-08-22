-- ============================================================================
-- V2026_07_12_01: Tenant-partition provisioning hook.
--
-- Registers a 'tenant-partitions' script in the tenant_provision_script
-- registry so every NEWLY CREATED tenant automatically gets its own physical
-- partition set (fact + 9 summaries) the moment it is created — via the
-- ensure_tenant_partitions() DB function installed by the psql-only
-- REBUILD_TENANT_LIST_PARTITIONING.sql.
--
-- Safe to land BEFORE the rebuild has been run:
--   * continue_on_error = TRUE, so on a pre-rebuild DB (function absent) the
--     script logs FAILED and the provisioning chain continues.
--   * After the rebuild, the function itself no-ops for any table that is not
--     LIST-partitioned, so partial states are also safe.
--
-- Splitter-safe (no DO $$ blocks). Idempotent.
-- ============================================================================

INSERT INTO tenant_provision_script (script_name, script_order, description, continue_on_error, created_by, script_sql)
VALUES (
  'tenant-partitions', 5,
  'Create the tenant''s physical partition set (fact_transaction + 9 summary tables, current + next year). Requires REBUILD_TENANT_LIST_PARTITIONING.sql to have been applied; fails harmlessly before that.',
  TRUE, 'system',
  'SELECT ensure_tenant_partitions(${TENANT_ID})'
)
ON CONFLICT (script_name) DO NOTHING;

-- Self-register.
INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_07_12_01__tenant_partition_provision_script.sql',
        'Provisioning hook: per-tenant partition creation on tenant create (order 5, before seeds)',
        TRUE, FALSE, 'system')
ON CONFLICT (migration_name) DO NOTHING;
