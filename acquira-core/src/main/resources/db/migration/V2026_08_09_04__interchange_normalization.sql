-- ============================================================================
-- V2026_08_09_04: Interchange Fee Normalization (Super Admin correction tool).
--
-- Business context: the fee engine's computed interchange at merchant level is
-- known to be off for some months. Finance supplies the CORRECT month total;
-- this feature KEEPS every transaction's existing interchange and adds the
-- extra (target - current total) on top weighted by VOLUME — merchant share of
-- month volume, then transaction share of merchant volume
-- (new = old + volume_share * extra, largest-remainder reconciliation so the
-- values sum EXACTLY to the target). fact_transaction.interchange_fee is then
-- updated and every summary table rebuilt so all screens show normalized values.
--
-- interchange_normalization_run    — one row per run; re-running a month adds a
--                                    new version and marks the prior APPLIED run
--                                    SUPERSEDED (history is never overwritten).
-- interchange_normalization_detail — per-merchant snapshot: the OLD interchange
--                                    (only surviving record of pre-normalization
--                                    values), weight, and the normalized amount.
--
-- Idempotent + splitter-safe.
-- ============================================================================

CREATE TABLE IF NOT EXISTS interchange_normalization_run (
    run_id                     BIGSERIAL PRIMARY KEY,
    tenant_id                  BIGINT       NOT NULL,
    month_key                  INTEGER      NOT NULL,          -- YYYYMM
    version_no                 INTEGER      NOT NULL DEFAULT 1,
    original_interchange_total NUMERIC(18,4),                  -- system value at preview time
    target_normalized_total    NUMERIC(18,4) NOT NULL,         -- finance-supplied truth
    difference                 NUMERIC(18,4),                  -- target - original
    unattributed_original      NUMERIC(18,4) DEFAULT 0,        -- interchange on merchant-less fact rows (scaled like the rest)
    unattributed_normalized    NUMERIC(18,4) DEFAULT 0,        -- their share of the target
    weighting_base             VARCHAR(30)  NOT NULL DEFAULT 'VOLUME_EXTRA',
    residual_method            VARCHAR(30)  NOT NULL DEFAULT 'LARGEST_REMAINDER',
    currency_scale             INTEGER      NOT NULL DEFAULT 2,
    merchant_count             INTEGER,
    status                     VARCHAR(20)  NOT NULL DEFAULT 'PREVIEW',
                               -- PREVIEW / APPLYING / APPLIED / SUPERSEDED / CANCELLED / FAILED
    status_detail              VARCHAR(500),
    created_by                 VARCHAR(100),
    created_at                 TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    applied_by                 VARCHAR(100),
    applied_at                 TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ic_norm_run_tenant_month
    ON interchange_normalization_run (tenant_id, month_key);
-- Exactly one live (APPLIED) version per tenant+month; DB-level duplicate guard.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ic_norm_run_applied
    ON interchange_normalization_run (tenant_id, month_key) WHERE status = 'APPLIED';
-- Guard for databases where the table pre-dates the column.
ALTER TABLE interchange_normalization_run
    ADD COLUMN IF NOT EXISTS unattributed_normalized NUMERIC(18,4) DEFAULT 0;

CREATE TABLE IF NOT EXISTS interchange_normalization_detail (
    detail_id              BIGSERIAL PRIMARY KEY,
    run_id                 BIGINT NOT NULL
                           REFERENCES interchange_normalization_run (run_id) ON DELETE CASCADE,
    merchant_id            BIGINT NOT NULL,
    merchant_name          VARCHAR(255),
    txn_count              INTEGER,
    txn_volume             NUMERIC(18,4),
    original_interchange   NUMERIC(18,4),   -- pre-normalization value (audit snapshot)
    weight_pct             NUMERIC(14,10),  -- merchant volume / total volume * 100
    normalized_interchange NUMERIC(18,4),   -- rounded + largest-remainder adjusted
    difference             NUMERIC(18,4)    -- normalized - original
);
CREATE INDEX IF NOT EXISTS idx_ic_norm_detail_run
    ON interchange_normalization_detail (run_id);
CREATE INDEX IF NOT EXISTS idx_ic_norm_detail_merchant
    ON interchange_normalization_detail (merchant_id);

-- Menu: Super Admin ONLY — this tool rewrites fact-level fees.
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Interchange Normalization', '/admin/interchange-normalization', 'Scale', 'ADMINISTRATION', 18)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
  AND m.path = '/admin/interchange-normalization'
ON CONFLICT DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_09_04__interchange_normalization.sql') ON CONFLICT (filename) DO NOTHING;
