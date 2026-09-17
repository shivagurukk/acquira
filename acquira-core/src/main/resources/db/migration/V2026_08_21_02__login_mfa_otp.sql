-- ============================================================================
-- V2026_08_21_02: Email-OTP two-factor authentication at login.
--
-- WHY
-- ---
-- Admin > Security Settings has carried MFA toggles since the security-settings
-- build, but they were stored-only ("Enforcement pending"). This migration adds
-- the challenge store that makes them live:
--
--   1. POST /api/auth/login            -> password verified; if the tenant policy
--                                         requires MFA, NO tokens are issued.
--                                         A challenge row is written here, the
--                                         6-digit code is emailed, and the client
--                                         gets {mfaRequired:true, mfaTicket}.
--   2. POST /api/auth/login/verify-mfa -> ticket + code -> BCrypt match ->
--                                         the real JWT/refresh session is issued.
--   3. POST /api/auth/login/resend-mfa -> re-issues a code for the same ticket.
--
-- WHY A SEPARATE TABLE (not password_reset_token)
-- -----------------------------------------------
-- password_reset_token rows are accepted by /api/auth/reset-password once
-- verified=true. Reusing that table for login MFA would mean a passed MFA
-- challenge doubles as a password-reset ticket — a privilege the MFA step must
-- never grant. Separate table, separate lifecycle, no cross-redemption.
--
-- The OTP itself is BCrypt-hashed at rest; only the emailed copy is plaintext.
-- Single-use, short TTL, attempt-limited — same hardening as the reset OTP.
--
-- Idempotent and splitter-safe (no DO $$ blocks). On prod (sql.init mode=never)
-- apply once via psql.
-- ============================================================================

CREATE TABLE IF NOT EXISTS login_mfa_token (
    mfa_token_id  BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    -- Opaque challenge handle returned to the browser in place of a JWT. Carries
    -- no authority on its own: it only names the pending challenge.
    ticket        VARCHAR(255) NOT NULL UNIQUE,
    -- BCrypt(6-digit code). Plaintext is emailed and never persisted.
    otp_hash      VARCHAR(255) NOT NULL,
    -- Failed verify attempts against THIS challenge; burned once it hits the cap.
    attempt_count INT DEFAULT 0,
    expires_at    TIMESTAMP NOT NULL,
    used          BOOLEAN DEFAULT FALSE,
    -- Client IP that started the challenge, for audit correlation.
    ip_address    VARCHAR(64),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- verify-mfa looks up by ticket; the login path clears prior rows per user.
CREATE INDEX IF NOT EXISTS idx_login_mfa_ticket ON login_mfa_token (ticket);
CREATE INDEX IF NOT EXISTS idx_login_mfa_user   ON login_mfa_token (user_id, used);

-- ----------------------------------------------------------------------------
-- No seed rows. MFA stays OFF until an admin enables it in
-- Admin > Security Settings, which writes these tenant_setting keys:
--
--   security.require_mfa_for_all     ('true'/'false')  -- every user
--   security.require_mfa_for_admins  ('true'/'false')  -- ROLE_ADMIN/SUPER_ADMIN
--
-- PRE-FLIGHT: MFA delivers over email, so SMTP must already work for the tenant
-- (Admin > SMTP Settings) BEFORE enabling either flag. With no working SMTP the
-- code cannot be delivered and login fails closed for the covered users.
-- Verify with:
--   SELECT setting_key, setting_value FROM tenant_setting
--    WHERE setting_key IN ('security.require_mfa_for_all',
--                          'security.require_mfa_for_admins');
-- ----------------------------------------------------------------------------
