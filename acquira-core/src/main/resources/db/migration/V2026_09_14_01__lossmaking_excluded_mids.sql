-- ============================================================================
-- V2026_09_14_01: hide two BH merchants from Loss-Making Merchants.
--
-- tenant_setting 'lossmaking.excluded_mids' (comma-separated MIDs) is read by
-- BusinessController.buildCeoVolumeRevenue when lossOnly=true: the MIDs are
-- dropped from the /business/loss-making list, its TOTAL row and CSV export.
-- Volume & Revenue, Net Spread and every other screen still show them.
--
-- Tenant 8 = Bahrain. ON CONFLICT DO NOTHING: a later edit of the list (plain
-- UPDATE, no redeploy needed) is never overwritten by a re-run.
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'lossmaking.excluded_mids', '900000000256412,000000000101386', 'STRING'
FROM tenant t
WHERE t.tenant_id = 8
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Verify
SELECT tenant_id, setting_key, setting_value FROM tenant_setting
WHERE setting_key = 'lossmaking.excluded_mids';

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_14_01__lossmaking_excluded_mids.sql') ON CONFLICT (filename) DO NOTHING;
