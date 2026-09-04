-- ============================================================================
-- verify_login_mfa.sql
-- Post-deploy check for the email-OTP second factor (V2026_08_21_02).
-- Read-only: safe to run on any environment.
--
--   psql -h <host> -p <port> -U postgres -d postgres -f verify_login_mfa.sql
--
-- Sections 1-2 must pass before MFA is switched on. Section 3 shows who is
-- currently covered. Sections 4-5 are for troubleshooting a live rollout.
-- ============================================================================

\echo ''
\echo '=== 1. Schema present (expect 1 table, 8 columns, 2+ indexes) ==='
SELECT
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_name = 'login_mfa_token')                       AS table_present,
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_name = 'login_mfa_token')                       AS column_count,
    (SELECT COUNT(*) FROM pg_indexes
      WHERE tablename = 'login_mfa_token')                        AS index_count;

\echo ''
\echo '=== 1b. Columns (ticket + otp_hash must be NOT NULL) ==='
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name = 'login_mfa_token'
ORDER BY ordinal_position;

\echo ''
\echo '=== 2. Which tenants have MFA switched on ==='
SELECT t.tenant_id,
       t.bank_name,
       COALESCE(MAX(CASE WHEN s.setting_key = 'security.require_mfa_for_all'
                         THEN s.setting_value END), 'false')      AS mfa_all,
       COALESCE(MAX(CASE WHEN s.setting_key = 'security.require_mfa_for_admins'
                         THEN s.setting_value END), 'false')      AS mfa_admins,
       COALESCE(MAX(CASE WHEN s.setting_key = 'security.mfa_otp_ttl_minutes'
                         THEN s.setting_value END), '5 (default)') AS code_ttl_minutes
FROM tenant t
LEFT JOIN tenant_setting s
       ON s.tenant_id = t.tenant_id
      AND s.setting_key LIKE 'security.%mfa%'
GROUP BY t.tenant_id, t.bank_name
ORDER BY t.tenant_id;

\echo ''
\echo '=== 2b. SMTP readiness — MFA CANNOT work without it ==='
-- A tenant with MFA on and no active SMTP config locks its users out: the code
-- is undeliverable and the sign-in fails closed by design. Any row flagged
-- BLOCKED must be fixed (configure SMTP) or MFA turned back off.
-- email_smtp_config is absent in some older/dev databases, so probe before use
-- rather than aborting the whole script on a missing relation.
SELECT to_regclass('public.email_smtp_config') IS NOT NULL AS has_smtp_table \gset
\if :has_smtp_table
SELECT t.tenant_id,
       t.bank_name,
       (SELECT COUNT(*) FROM email_smtp_config c
         WHERE c.tenant_id = t.tenant_id AND c.is_active)         AS active_smtp,
       CASE
         WHEN COALESCE(MAX(CASE WHEN s.setting_key IN ('security.require_mfa_for_all',
                                                       'security.require_mfa_for_admins')
                                 AND s.setting_value = 'true' THEN 1 END), 0) = 0
              THEN 'MFA off'
         WHEN (SELECT COUNT(*) FROM email_smtp_config c
                WHERE c.tenant_id = t.tenant_id AND c.is_active) > 0
              THEN 'OK'
         ELSE 'BLOCKED - MFA on with no active SMTP'
       END                                                        AS status
FROM tenant t
LEFT JOIN tenant_setting s
       ON s.tenant_id = t.tenant_id
      AND s.setting_key LIKE 'security.%mfa%'
GROUP BY t.tenant_id, t.bank_name
ORDER BY t.tenant_id;
\else
\echo '  email_smtp_config table not present here — check SMTP another way'
\echo '  (the app also accepts a spring.mail.* fallback sender).'
\endif

\echo ''
\echo '=== 3. Users who would be challenged but CANNOT receive a code ==='
-- No email address on file, or SSO-only. SSO users are exempt by design and
-- should appear as EXEMPT; a local user with no email is a real blocker.
SELECT u.username,
       u.role,
       uta.tenant_id,
       COALESCE(u.email, '(none)')                                AS email,
       CASE
         WHEN u.sso_provider IS NOT NULL      THEN 'EXEMPT (SSO)'
         WHEN u.email IS NULL OR u.email = '' THEN 'BLOCKED - no email address'
         ELSE 'OK'
       END                                                        AS status
FROM users u
LEFT JOIN user_tenant_access uta ON uta.user_id = u.user_id AND uta.is_default_tenant
WHERE u.is_active
  AND EXISTS (
        SELECT 1 FROM tenant_setting s
         WHERE s.tenant_id = uta.tenant_id
           AND s.setting_value = 'true'
           AND (s.setting_key = 'security.require_mfa_for_all'
             OR (s.setting_key = 'security.require_mfa_for_admins'
                 AND UPPER(COALESCE(u.role, '')) LIKE '%ADMIN'))
      )
ORDER BY status DESC, u.username;

\echo ''
\echo '=== 4. Live challenges (should be small; rows expire in minutes) ==='
SELECT COUNT(*)                                                   AS total_rows,
       COUNT(*) FILTER (WHERE NOT used AND expires_at > NOW())     AS live,
       COUNT(*) FILTER (WHERE used)                                AS spent,
       COUNT(*) FILTER (WHERE NOT used AND expires_at <= NOW())     AS expired_unused,
       MAX(attempt_count)                                          AS worst_attempt_count
FROM login_mfa_token;

\echo ''
\echo '=== 5. Recent MFA audit activity (last 24h) ==='
-- MFA_SEND_FAILED or MFA_NO_EMAIL appearing here means users are being refused
-- sign-in because the code could not be delivered.
SELECT action_type, COUNT(*) AS events, MAX(event_time) AS most_recent
FROM audit_log
WHERE action_type LIKE 'MFA%'
  AND event_time > NOW() - INTERVAL '24 hours'
GROUP BY action_type
ORDER BY events DESC;

\echo ''
\echo '=== Done. Section 2 and 3 must show no BLOCKED rows before enabling MFA. ==='
