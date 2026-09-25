-- BAHRAIN (tenant_id = 8) — Sales TL mapping, generated 2026-09-15
-- Source: Q2 2026 Performance sheets (Existing/E-com Existing/New Sales/E-com New)
--         joined to the tenant-8 user list on name -> email -> user_id.
-- Pattern: docs/deploy/uae_tl_mapping_2026_09_11.sql (per RM_TEAM_MERCHANT_MAPPING_GUIDE.md)
-- Idempotent: safe to re-run; re-running moves agents if leads changed.
--
-- NOTE: sales_user_assignment allows ONE team lead per RM (UNIQUE tenant+sid),
-- but the Q2 sheet lists the same 11 RMs under BOTH Adham (Existing Business)
-- and Pranay (New Sales). Resolution agreed 2026-09-15 ("all went to Adham"):
--   * RMs appearing under Adham anywhere -> Adham (incl. Anas, Russell, and
--     Pranay himself, who is listed as an RM in Adham's existing-business team)
--   * RMs appearing ONLY under Pranay -> Pranay (Ali Alabyooki, Mustafa Rahma,
--     Sales Person 3, Sales Person 4 — the last two are placeholder agents
--     with no user accounts, created here with SIDs 'SP3'/'SP4')
--   * Adham (as an agent) -> Amira, per the original Q2 sheet

BEGIN;

-- Step 0: wipe existing tenant-8 team structure (fresh load)
-- assignments first (FK to sales_team_mapping), then leads. Profiles kept.
DELETE FROM sales_user_assignment WHERE tenant_id = 8;
DELETE FROM sales_team_mapping    WHERE tenant_id = 8;

-- Step 1: ensure agent profile rows exist for every mapped SID
INSERT INTO sales_agent_profile (tenant_id, sales_user_id, sales_email, status, created_at, updated_at)
VALUES
    (8, '6044', 'hussain.al-alawi@afs.com.bh',   'ACTIVE', NOW(), NOW()),
    (8, '6045', 'pranay.upadhyay@afs.com.bh',    'ACTIVE', NOW(), NOW()),
    (8, '6162', 'eyad.alshehab@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6046', 'lynn.dias@afs.com.bh',          'ACTIVE', NOW(), NOW()),
    (8, '6194', 'omaima.sarhan@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6158', 'walaa.alaradi@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6047', 'abdulrasool.husain@afs.com.bh', 'ACTIVE', NOW(), NOW()),
    (8, '6114', 'mohamed.nader@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6159', 'amjad.abdulla@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6050', 'adnan.khan@afs.com.bh',         'ACTIVE', NOW(), NOW()),
    (8, '6205', 'abdulla.adel@afs.com.bh',       'ACTIVE', NOW(), NOW()),
    (8, '6042', 'anas.qureshi@afs.com.bh',       'ACTIVE', NOW(), NOW()),
    (8, '6129', 'russell.james@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6238', 'ali.alabyooki@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6240', 'mustafa.rahma@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6040', 'adham.alalami@afs.com.bh',      'ACTIVE', NOW(), NOW()),
    (8, '6039', 'amira.ismail@afs.com.bh',       'ACTIVE', NOW(), NOW()),
    -- Placeholder agents from the Q2 New Sales sheet (no user accounts yet).
    -- Swap the SID for the real user_id when these positions are filled.
    (8, 'SP3',  'vacant@afs.com.bh',             'ACTIVE', NOW(), NOW()),
    (8, 'SP4',  'vacant@afs.com.bh',             'ACTIVE', NOW(), NOW())
ON CONFLICT (tenant_id, sales_user_id) DO NOTHING;

-- Step 2: team leads (keyed by email; re-run updates name)
INSERT INTO sales_team_mapping (tenant_id, team_lead_name, team_lead_email, is_default)
VALUES
    (8, 'Adham Alalami',       'adham.alalami@afs.com.bh',      false),
    (8, 'Pranay Upadhyay',     'pranay.upadhyay@afs.com.bh',    false),
    (8, 'Abdulrasool Hussain', 'abdulrasool.husain@afs.com.bh', false),  -- "Rasool" (TL under Amira, New Sales)
    (8, 'Amira Ismail',        'amira.ismail@afs.com.bh',       false),
    (8, 'Vacant',              'vacant@afs.com.bh',             true)    -- default bucket (Sales Person 3/4 rule)
ON CONFLICT (tenant_id, team_lead_email)
DO UPDATE SET team_lead_name = EXCLUDED.team_lead_name;

-- Step 3: assign agents (SID -> team lead), upsert moves on re-run
INSERT INTO sales_user_assignment (tenant_id, sales_user_id, team_lead_id, assigned_at)
SELECT 8, v.sid, m.id, NOW()
FROM (VALUES
    -- Existing Business + E-com -> Adham
    ('6044', 'adham.alalami@afs.com.bh'),   -- Hussain Alawi
    ('6162', 'adham.alalami@afs.com.bh'),   -- Eyad AlShehab
    ('6046', 'adham.alalami@afs.com.bh'),   -- Lynn Dias
    ('6194', 'adham.alalami@afs.com.bh'),   -- Omaima Sarhan
    ('6158', 'adham.alalami@afs.com.bh'),   -- Walaa AlAradi
    ('6047', 'adham.alalami@afs.com.bh'),   -- Abdulrasool Hussain (agent role)
    ('6114', 'adham.alalami@afs.com.bh'),   -- Mohamed Nader
    ('6159', 'adham.alalami@afs.com.bh'),   -- Amjad Abdulla
    ('6050', 'adham.alalami@afs.com.bh'),   -- Adnan Khan
    ('6205', 'adham.alalami@afs.com.bh'),   -- Abdulla Adel
    ('6042', 'adham.alalami@afs.com.bh'),   -- Anas Qureshi (E-com)
    ('6129', 'adham.alalami@afs.com.bh'),   -- Russell James (E-com)
    ('6045', 'adham.alalami@afs.com.bh'),   -- Pranay Upadhyay (RM role, per sheet)
    -- New-Sales-only RMs -> Pranay
    ('6238', 'pranay.upadhyay@afs.com.bh'), -- Ali Alabyooki
    ('6240', 'pranay.upadhyay@afs.com.bh'), -- Mustafa Rahma
    ('SP3',  'pranay.upadhyay@afs.com.bh'), -- Sales Person 3 (placeholder)
    ('SP4',  'pranay.upadhyay@afs.com.bh'), -- Sales Person 4 (placeholder)
    -- Team lead reports to Amira
    ('6040', 'amira.ismail@afs.com.bh')     -- Adham Alalami
) AS v(sid, lead_email)
JOIN sales_team_mapping m
  ON m.tenant_id = 8 AND m.team_lead_email = v.lead_email
ON CONFLICT (tenant_id, sales_user_id)
DO UPDATE SET team_lead_id = EXCLUDED.team_lead_id, assigned_at = NOW();

-- Step 4: display names
UPDATE sales_agent_profile p
SET display_name = v.name, updated_at = NOW()
FROM (VALUES
    ('6044', 'Hussain Alawi'),
    ('6045', 'Pranay Upadhyay'),
    ('6162', 'Eyad AlShehab'),
    ('6046', 'Lynn Dias'),
    ('6194', 'Omaima Sarhan'),
    ('6158', 'Walaa AlAradi'),
    ('6047', 'Abdulrasool Hussain'),
    ('6114', 'Mohamed Nader'),
    ('6159', 'Amjad Abdulla'),
    ('6050', 'Adnan Khan'),
    ('6205', 'Abdulla Adel'),
    ('6042', 'Anas Qureshi'),
    ('6129', 'Russell James'),
    ('6238', 'Ali Alabyooki'),
    ('6240', 'Mustafa Rahma'),
    ('6040', 'Adham Alalami'),
    ('6039', 'Amira Ismail'),
    ('SP3',  'Sales Person 3'),
    ('SP4',  'Sales Person 4')
) AS v(sid, name)
WHERE p.tenant_id = 8 AND p.sales_user_id = v.sid;

COMMIT;

-- Verify: full team roster with lead names
SELECT p.sales_user_id,
       p.display_name,
       p.sales_email,
       m.team_lead_name,
       m.team_lead_email
FROM sales_agent_profile p
LEFT JOIN sales_user_assignment a
  ON a.tenant_id = p.tenant_id AND a.sales_user_id = p.sales_user_id
LEFT JOIN sales_team_mapping m ON m.id = a.team_lead_id
WHERE p.tenant_id = 8
ORDER BY m.team_lead_name NULLS LAST, p.display_name;
