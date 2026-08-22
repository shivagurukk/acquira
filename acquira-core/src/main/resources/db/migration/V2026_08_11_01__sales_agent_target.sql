-- ============================================================================
-- V2026_08_11_01: Per-sales-agent targets (multi-tenant, admin-managed).
--
-- WHY A NEW TABLE. `sales_agent_profile.monthly_target` is a single flat number
-- per agent with no time dimension: it cannot answer "what was Sara's target for
-- Q2" or "for Aug 1-12", and it cannot be varied by year. Targets are entered
-- ANNUALLY by an admin but STORED MONTHLY — exactly the grain bank_budget_target
-- uses — because monthly grain is what makes MTD / QTD / custom-range proration
-- correct. An annual-only column cannot be prorated without inventing a phasing
-- assumption at read time, in every caller.
--
-- SHIPS EMPTY ON PURPOSE. No seed, no backfill from monthly_target. Until an
-- admin enters targets, every consumer resolves NULL and renders "—". The
-- Executive Sales Pulse page is fully functional with this table empty: momentum
-- and growth come from ingested merchant data (sum_daily_merchant), never from
-- targets. A missing target is a missing target, not underperformance.
--
-- MULTI-TENANT. tenant_id is a hard FK, RLS is enabled with the same
-- tenant_isolation_policy every other tenant-scoped table uses, and the unique
-- key is scoped by tenant so two tenants can hold the same sales_user_id
-- independently. Writers additionally validate the agent belongs to the caller's
-- tenant, so a cross-tenant write fails even if RLS were not in force.
--
-- metric_type is carried from day one: when targets move from net margin to
-- volume, that is a value change, not a schema migration.
--
-- Idempotent; splitter-safe (no $$). Listed in schema-locations after
-- schema.sql. On prod apply once via psql.
-- ============================================================================

CREATE TABLE IF NOT EXISTS sales_agent_target (
    target_id     BIGSERIAL PRIMARY KEY,
    tenant_id     INT NOT NULL REFERENCES tenant(tenant_id),   -- RLS Key
    sales_user_id VARCHAR(100) NOT NULL,

    -- YYYYMM, the same key shape as sum_monthly_bank.month_key.
    month_key     INT NOT NULL,

    -- NULL-able: a row may exist for a month with no number yet (an admin
    -- clearing one month of a year). NULL and 0 are different statements —
    -- 0 means "the target is zero", NULL means "no target is set".
    target_value  DECIMAL(19, 4),

    -- Which measure the number is a target FOR. Matches the Pulse page's sales
    -- metric. Validated against a known set in the controller.
    metric_type   VARCHAR(50) NOT NULL DEFAULT 'NET_REVENUE',

    -- MANUAL (typed by an admin) vs EQUAL/SEASONAL (derived by splitting an
    -- annual figure). Kept so the UI can show "auto-split" vs "hand-set".
    source        VARCHAR(20) NOT NULL DEFAULT 'MANUAL',

    created_by    VARCHAR(100),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_sales_agent_target UNIQUE (tenant_id, sales_user_id, month_key, metric_type)
);

ALTER TABLE sales_agent_target ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_policy ON sales_agent_target;
CREATE POLICY tenant_isolation_policy ON sales_agent_target
    USING (tenant_id = get_current_tenant());

-- The read path: "every target for this tenant in this month window", which is
-- how SalesTargetResolver loads a whole team's targets in one query.
CREATE INDEX IF NOT EXISTS idx_sales_agent_target_window
    ON sales_agent_target (tenant_id, month_key, sales_user_id);

-- The admin path: "this agent's whole year".
CREATE INDEX IF NOT EXISTS idx_sales_agent_target_agent
    ON sales_agent_target (tenant_id, sales_user_id, month_key);
