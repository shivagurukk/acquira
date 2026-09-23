-- ============================================================================
-- V2026_07_11_01: Password-reset OTP support on password_reset_token.
--
-- WHY
-- ---
-- Forgot-password moves from an emailed reset LINK (single UUID in `token`)
-- to a 6-digit OTP flow:
--   1. forgot-password  -> store BCrypt(otp) + 10-min expiry, email the CODE
--   2. verify-otp       -> BCrypt-match, attempt-limited; on success mark
--                          verified=true and issue a fresh opaque `token`
--                          (single-use reset ticket)
--   3. reset-password   -> require verified=true + ticket match, set password
--
-- Reuses the existing password_reset_token table (no new table). The `token`
-- column is retained: after OTP verification it holds the opaque reset ticket
-- so the set-password call never resends the OTP over the wire.
--
-- Idempotent (ADD COLUMN IF NOT EXISTS). Splitter-safe (no DO $$ blocks) so it
-- may live in spring.sql.init.schema-locations. On prod (mode=never) apply once
-- via psql.
-- ============================================================================

ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS otp_hash      VARCHAR(255);
ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS attempt_count INT DEFAULT 0;
ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS verified      BOOLEAN DEFAULT FALSE;

-- Index the per-user lookup used by verify-otp / resend (newest unused row).
CREATE INDEX IF NOT EXISTS idx_pw_reset_token_user
    ON password_reset_token (user_id, used);
