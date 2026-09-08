-- ============================================================================
-- V2026_09_04_01: Daily Dashboard Digest email.
--
-- One executive summary email per tenant per business day, sent only after
-- ALL required feeds for that day have landed (transactions + DCC + rentals,
-- each individually toggleable). Discovery and gating run on a timer
-- (DigestScheduler); state lives in digest_dispatch so a day is emailed
-- exactly once no matter how many files arrive or how often the timer fires.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. digest_config — one row per tenant that wants the digest
-- ----------------------------------------------------------------------------
-- enabled defaults FALSE: a newly provisioned tenant must never email anyone
-- until an admin has typed the recipient list in (same fail-closed reasoning
-- as ingest_expectation.enabled).
CREATE TABLE IF NOT EXISTS digest_config (
    tenant_id            BIGINT PRIMARY KEY,
    enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    recipients           TEXT,                       -- comma/semicolon-separated email list
    quiet_minutes        INT          NOT NULL DEFAULT 15,  -- debounce after the last ingest run
    require_trx          BOOLEAN      NOT NULL DEFAULT TRUE,
    require_dcc          BOOLEAN      NOT NULL DEFAULT TRUE,
    require_rental       BOOLEAN      NOT NULL DEFAULT TRUE,
    backfill_window_days INT          NOT NULL DEFAULT 3,   -- only days this recent trigger emails
    updated_by           VARCHAR(128),
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed one disabled row per existing tenant so the admin screen has a row to
-- edit rather than a create-form (mirrors the ingest_expectation seeding).
INSERT INTO digest_config (tenant_id)
SELECT t.tenant_id FROM tenant t
ON CONFLICT (tenant_id) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2. digest_dispatch — the once-per-tenant-day state machine
-- ----------------------------------------------------------------------------
-- status: PENDING  discovered, waiting on feeds / quiet period
--         SENT     digest delivered to at least one recipient
--         FAILED   gave up after max attempts (visible on the admin screen)
--         SKIPPED  admin disabled the tenant while days were pending
-- The UNIQUE constraint is the idempotency guarantee.
CREATE TABLE IF NOT EXISTS digest_dispatch (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL,
    business_date  DATE        NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    waiting_on     VARCHAR(64),            -- e.g. 'DCC', 'RENTAL', 'QUIET' — why still pending
    attempts       INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at        TIMESTAMP,
    recipients_sent TEXT,
    error_message  TEXT,
    UNIQUE (tenant_id, business_date)
);

CREATE INDEX IF NOT EXISTS ix_digest_dispatch_status
    ON digest_dispatch (status, tenant_id);
CREATE INDEX IF NOT EXISTS ix_digest_dispatch_tenant_date
    ON digest_dispatch (tenant_id, business_date DESC);

-- ----------------------------------------------------------------------------
-- 3. Menu registration — /ops/daily-digest (OPERATIONS, after Scheme Billing)
-- ----------------------------------------------------------------------------
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Daily Digest', '/ops/daily-digest', 'Mail', 'OPERATIONS', 7
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/ops/daily-digest');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/ops/daily-digest'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
