-- =================================================================
-- Gap Analysis Fixes — Schema Migration
-- Date: 2026-02-21
-- Run this manually against your PostgreSQL database
-- =================================================================

-- GAP-7: Index on access_request for frequent queries by (email, status)
CREATE INDEX IF NOT EXISTS idx_access_request_email_status
    ON access_request(email, status);

-- GAP-21: Make users.email NOT NULL and UNIQUE
-- Step 1: Fix any null emails first (set to username@placeholder)
UPDATE users SET email = username || '@placeholder.local'
    WHERE email IS NULL OR email = '';

-- Step 2: Handle duplicates — append row id to make unique
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT id, email, ROW_NUMBER() OVER (PARTITION BY LOWER(email) ORDER BY id) as rn
        FROM users
    )
    LOOP
        IF r.rn > 1 THEN
            UPDATE users SET email = r.id || '_' || r.email WHERE id = r.id;
        END IF;
    END LOOP;
END $$;

-- Step 3: Add constraints
ALTER TABLE users ALTER COLUMN email SET NOT NULL;
DO $$ BEGIN
    ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- GAP-25: Ensure role_in_tenant and is_default_tenant columns exist in original DDL
-- (idempotent — safe to run if already present)
DO $$ BEGIN
    ALTER TABLE user_tenant_access ADD COLUMN role_in_tenant VARCHAR(50);
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE user_tenant_access ADD COLUMN is_default_tenant BOOLEAN DEFAULT FALSE;
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;
