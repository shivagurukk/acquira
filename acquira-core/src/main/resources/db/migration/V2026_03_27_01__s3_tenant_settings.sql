-- ============================================================
-- S3 Report Storage: per-tenant settings in tenant_setting
-- ============================================================
-- Settings stored as key-value rows using existing tenant_setting table.
-- No new tables needed. Keys used by S3SettingsController:
--
--   s3.enabled          BOOLEAN   "true" / "false"
--   s3.region           STRING    AWS region (e.g. "me-south-1")
--   s3.bucket           STRING    S3 bucket name
--   s3.prefix           STRING    Key prefix inside bucket (e.g. "reports")
--   s3.accessKeyId      STRING    IAM access key ID (plain text — not sensitive)
--   s3.secretAccessKey  ENCRYPTED AES-256-GCM encrypted, base64-encoded
--
-- The setting_type column uses "ENCRYPTED" to mark values that must
-- be decrypted before use. Plain values use "STRING" or "BOOLEAN".
-- ============================================================

-- 1. Fast per-tenant key lookup index (idempotent)
CREATE INDEX IF NOT EXISTS idx_tenant_setting_tenant_key
    ON tenant_setting(tenant_id, setting_key);

-- 2. Extend setting_type to include ENCRYPTED (if a CHECK constraint exists)
--    Safe to run even if constraint does not exist.
DO $$
BEGIN
    -- Drop old constraint if it exists and doesn't include ENCRYPTED
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'tenant_setting'
          AND constraint_name = 'setting_type_check'
    ) THEN
        ALTER TABLE tenant_setting DROP CONSTRAINT IF EXISTS setting_type_check;
    END IF;

    -- Re-add with ENCRYPTED included
    BEGIN
        ALTER TABLE tenant_setting
            ADD CONSTRAINT setting_type_check
            CHECK (setting_type IN ('STRING', 'BOOLEAN', 'NUMBER', 'JSON', 'ENCRYPTED'));
    EXCEPTION WHEN duplicate_object THEN
        -- Already exists with the right values
        NULL;
    END;
END
$$;

-- 3. Add comment for clarity
COMMENT ON TABLE tenant_setting IS
    'Per-tenant key-value configuration. Sensitive values (e.g. S3 secret key) '
    'are stored with setting_type=ENCRYPTED using AES-256-GCM encryption. '
    'Encryption key is configured via app.encryption.key in application.properties.';
