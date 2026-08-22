-- ======================================================
-- V2026_02_21_04: Platform Improvements
-- #7  SSO State Tokens table (persist across restart)
-- #14 Refresh Token tracking (rotation + revocation)
-- #15 API Rate Limit tracking
-- #26 Email Queue processor index
-- ======================================================

-- #14: Refresh Token tracking for rotation and revocation
CREATE TABLE IF NOT EXISTS refresh_token (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE, -- SHA-256 of the actual token
    issued_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by     VARCHAR(128), -- hash of the new token (for rotation tracking)
    user_agent      VARCHAR(500),
    ip_address      VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_username ON refresh_token(username);
CREATE INDEX IF NOT EXISTS idx_refresh_token_hash ON refresh_token(token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_token_expires ON refresh_token(expires_at) WHERE revoked = FALSE;

-- #7: SSO State Tokens (persist across restart / multi-instance)
CREATE TABLE IF NOT EXISTS sso_state_token (
    state_token     VARCHAR(100) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_sso_state_expires ON sso_state_token(expires_at);

-- #26: Ensure email_queue has processing indexes
CREATE INDEX IF NOT EXISTS idx_email_queue_pending ON email_queue(status, created_at) WHERE status = 'PENDING';
