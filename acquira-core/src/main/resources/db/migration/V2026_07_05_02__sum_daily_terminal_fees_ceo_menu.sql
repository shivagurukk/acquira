-- ============================================================================
-- V2026_07_05_02: Store-grain fee columns + CEO Volume & Revenue screen menu.
--
-- WHY
-- ---
-- The CEO Volume & Revenue report needs MID + SID + interchange + scheme fee +
-- net margin in ONE fast read. No existing summary table has all of them:
--   - sum_daily_merchant has the fees but store_id is always NULL (no SID)
--   - sum_daily_insight has store_id but no interchange / scheme fee columns
-- Scanning fact_transaction per page-load is not acceptable for dashboards.
--
-- FIX: extend sum_daily_terminal (already partitioned + indexed + batch-written
-- at merchant x store x terminal day grain) with the three missing measures.
-- populateSummaryStep (TransactionJobConfig) fills them from fact_transaction
-- in the same rollup pass. Any MID/SID (or terminal) fee report is then a
-- summary read — same speed class as every other page.
--
--   total_base_volume  settlement volume (store_base_currency_amount) — the
--                      figure fees/margin are computed against
--   total_interchange  SUM(interchange_fee)  (computed at ingest, ours)
--   total_scheme_fee   SUM(scheme_fee)       (computed at ingest, ours)
--   total_revenue      (existing) already = msf - interchange - scheme_fee
--
-- Existing rows get 0 in the new columns; environment is being wiped and
-- re-ingested, so no backfill is shipped.
--
-- Also registers the new Executive screen '/business/ceo-volume-revenue' in
-- sys_menu and grants it to Super Admin + Bank Admin (sidebar is DB-driven).
-- Category is 'EXECUTIVE' (uppercase) to match schema.sql's existing Executive
-- rows so all Executive screens group together in the sidebar.
--
-- Splitter-safe (no $$); idempotent; listed in spring.sql.init.schema-locations
-- AFTER schema.sql so the ALTERs land on dev resets too. On prod apply once
-- via psql.
-- ============================================================================

ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_interchange DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_scheme_fee  DECIMAL(19, 2) DEFAULT 0;

-- Covering index for the MID/SID rollup read path (tenant + date range,
-- grouped by merchant/store).
CREATE INDEX IF NOT EXISTS idx_sdt_fee_rollup
    ON sum_daily_terminal (tenant_id, business_date, merchant_id, store_id);

-- ── Sidebar registration ───────────────────────────────────────────────────
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Volume & Revenue', '/business/ceo-volume-revenue', 'TrendingUp', 'EXECUTIVE', 3
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/ceo-volume-revenue');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/ceo-volume-revenue'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;
