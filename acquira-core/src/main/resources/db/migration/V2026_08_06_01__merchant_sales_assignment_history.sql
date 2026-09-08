-- =============================================================================
-- V2026_08_06_01 — merchant_sales_assignment_history: audit trail for sales
--                  agent reassignment, plus the sales executive dashboard menu
--
-- WHY
-- ---
-- The merchant master upload changes a merchant's sales agent by a Type-1
-- overwrite (MerchantMasterJobConfig.upsertDimensionsTasklet):
--
--     ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
--         sales_user_id = COALESCE(EXCLUDED.sales_user_id, dim_merchant.sales_user_id),
--         sales_email   = COALESCE(EXCLUDED.sales_email,   dim_merchant.sales_email)
--
-- and the step right after it re-syncs sum_daily_merchant.sales_user_id from
-- dim_merchant, so the change re-attributes ALL of that merchant's historical
-- volume to the new agent across every screen, report and rollup.
--
-- That is the intended business behaviour, but until now the previous agent was
-- destroyed in the process: there was no record of who held the merchant before,
-- when it moved, or which upload moved it. audit_log alone is too coarse for
-- this (free-text `details`, no typed old/new columns to report or reconcile on).
--
-- WHAT
-- ----
-- One append-only row per actual change of sales_user_id / sales_email on
-- dim_merchant, whatever the source. The batch writes these set-based, in the
-- same transaction as the upsert, from a snapshot taken BEFORE it — so the
-- history and the resulting dim_merchant state can never disagree.
--
-- Rows are never updated or deleted. `source` distinguishes an upload-driven
-- move (UPLOAD) from a future admin action (MANUAL) or API call (API).
--
-- Idempotent (IF NOT EXISTS throughout) — this file is listed in
-- spring.sql.init.schema-locations and runs on every startup.
-- =============================================================================

-- ── sales_agent_profile: missing DDL ─────────────────────────────────────────
-- This table is read and written by SalesAgentProfileService, the sales
-- portfolio / leaderboard queries, and step 8 of MerchantMasterJobConfig — and
-- is documented in ACQUIRA_FEATURE_GUIDE.md — but no CREATE TABLE for it exists
-- anywhere in the repo. It only survives because ddl-auto=none means Hibernate
-- never noticed, the batch step wraps its insert in a catch-and-log, and prod
-- had the table created out of band. A clean database therefore boots with the
-- whole Sales suite broken.
--
-- The reassignment validation added in this release queries it directly (to
-- report agent codes the tenant has never seen), so the gap has to close here.
-- Columns match the entity SalesAgentProfile.java field for field.
CREATE TABLE IF NOT EXISTS sales_agent_profile (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    sales_user_id  VARCHAR(100) NOT NULL,
    -- Auto-synced from dim_merchant.sales_email by the batch; not admin-editable.
    sales_email    VARCHAR(255),
    display_name   VARCHAR(255),
    phone          VARCHAR(50),
    country_code   VARCHAR(10),
    hire_date      DATE,
    monthly_target DECIMAL(19, 2),
    status         VARCHAR(30)  DEFAULT 'ACTIVE',
    notes          TEXT,
    created_at     TIMESTAMP    DEFAULT NOW(),
    updated_at     TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_sales_agent_profile_tenant_user UNIQUE (tenant_id, sales_user_id)
);

CREATE INDEX IF NOT EXISTS idx_sales_agent_profile_tenant
    ON sales_agent_profile (tenant_id);


CREATE TABLE IF NOT EXISTS merchant_sales_assignment_history (
    history_id        BIGSERIAL PRIMARY KEY,
    tenant_id         INTEGER      NOT NULL,
    merchant_id       BIGINT       NOT NULL,

    -- The assignment as it stood before the change. NULL means the merchant had
    -- no agent (first-ever assignment), which is a legitimate history row.
    old_sales_user_id VARCHAR(100),
    old_sales_email   VARCHAR(255),

    -- The assignment written by the change. Never NULL: a blank agent column in
    -- an upload file is COALESCEd away by the upsert and is NOT a reassignment.
    new_sales_user_id VARCHAR(100) NOT NULL,
    new_sales_email   VARCHAR(255),

    changed_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Username for a human action; 'BATCH:<jobExecutionId>' for an upload.
    changed_by        VARCHAR(255),
    -- UPLOAD | MANUAL | API
    source            VARCHAR(30)  NOT NULL DEFAULT 'UPLOAD',
    job_execution_id  BIGINT,
    upload_file_name  VARCHAR(500)
);

-- Merchant 360 / drill-down: newest change for one merchant.
CREATE INDEX IF NOT EXISTS idx_msah_merchant
    ON merchant_sales_assignment_history (tenant_id, merchant_id, changed_at DESC);

-- "What did this upload move?" — the reassignment report for one batch run.
CREATE INDEX IF NOT EXISTS idx_msah_job
    ON merchant_sales_assignment_history (tenant_id, job_execution_id);

-- "What has moved to / from this agent?" — agent-level audit.
CREATE INDEX IF NOT EXISTS idx_msah_new_agent
    ON merchant_sales_assignment_history (tenant_id, new_sales_user_id, changed_at DESC);


-- ── Sales Executive dashboard menu entry ─────────────────────────────────────
-- Same shape as V2026_07_10_05__sales_menu.sql. Ordered first in the SALES
-- category: it is the management entry point that the other five screens drill
-- down from.
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
  ('Sales Executive', '/sales/executive', 'LayoutDashboard', 'SALES', 0)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
  AND m.path = '/sales/executive'
ON CONFLICT DO NOTHING;
