-- ============================================================================
-- V2026_10_03_06: email_smtp_config table.
--
-- The EmailSmtpConfig entity, the SMTP Settings screen (/api/email/smtp-configs)
-- and the campaign / email-queue senders all read this table, but its CREATE
-- only survived in schema.sql.backup - no live schema file or migration creates
-- it, so a database built from the current scripts has no table and the screen
-- returns HTTP 500 (relation "email_smtp_config" does not exist).
--
-- DDL is the original definition, matching the entity column for column.
-- Idempotent; splitter-safe (no $$). Applied once via psql like the other
-- recent scripts (not in the startup schema-locations list).
-- ============================================================================

CREATE TABLE IF NOT EXISTS email_smtp_config (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL,
    config_name           VARCHAR(255) NOT NULL,
    host                  VARCHAR(255) NOT NULL,
    port                  INTEGER      NOT NULL DEFAULT 587,
    username              VARCHAR(255),
    password              VARCHAR(1024),          -- AES-256-GCM encrypted token
    auth_enabled          BOOLEAN      DEFAULT TRUE,
    starttls_enabled      BOOLEAN      DEFAULT TRUE,
    ssl_enabled           BOOLEAN      DEFAULT FALSE,
    from_address          VARCHAR(255),
    from_name             VARCHAR(255),
    reply_to              VARCHAR(255),
    connection_timeout    INTEGER      DEFAULT 10000,
    read_timeout          INTEGER      DEFAULT 10000,
    write_timeout         INTEGER      DEFAULT 10000,
    rate_limit_ms         INTEGER      DEFAULT 200,
    max_retries           INTEGER      DEFAULT 3,
    is_active             BOOLEAN      DEFAULT FALSE,
    auto_send_after_batch BOOLEAN      DEFAULT FALSE,
    created_at            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_smtp_config_tenant
    ON email_smtp_config (tenant_id);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_03_06__email_smtp_config.sql')
ON CONFLICT (filename) DO NOTHING;
