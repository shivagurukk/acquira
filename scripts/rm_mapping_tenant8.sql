-- ============================================================================
-- RM (sales user) email mapping — TENANT 8
-- Generated 2026-09-15 from the confirmed Q2 2026 RM roster.
--
-- Context: dim_merchant.sales_user_id is already populated for every MID,
-- but sales_email (the column the dashboards filter RMs by) is NULL.
-- This script backs up the current values, clears tenant 8's sales_email,
-- and re-stamps it from the id -> email mapping below.
--
-- NO merchant rows are deleted. Only the sales_email column is touched.
-- ============================================================================

BEGIN;

-- 0. Backup current RM columns (rerunnable: timestamped name, keep once)
CREATE TABLE IF NOT EXISTS bak_dim_merchant_rm_20260915 AS
SELECT merchant_id, tenant_id, mid, sales_user_id, sales_email
FROM dim_merchant
WHERE tenant_id = 8;

-- 1. Clear the old/blank emails for tenant 8
UPDATE dim_merchant
SET sales_email = NULL
WHERE tenant_id = 8;

-- 2. Stamp emails from the confirmed RM roster (id -> name -> email)
WITH rm(rm_id, rm_name, rm_email) AS (
    VALUES
        ('6044', 'Hussain Alawi',       'hussain.al-alawi@afs.com.bh'),
        ('6045', 'Pranay Upadhyay',     'pranay.upadhyay@afs.com.bh'),
        ('6162', 'Eyad AlShehab',       'eyad.alshehab@afs.com.bh'),
        ('6046', 'Lynn Dias',           'lynn.dias@afs.com.bh'),
        ('6194', 'Omaima Sarhan',       'omaima.sarhan@afs.com.bh'),
        ('6158', 'Walaa AlAradi',       'walaa.alaradi@afs.com.bh'),
        ('6047', 'Abdulrasool Hussain', 'abdulrasool.husain@afs.com.bh'),
        ('6114', 'Mohamed Nader',       'mohamed.nader@afs.com.bh'),
        ('6159', 'Amjad Abdulla',       'amjad.abdulla@afs.com.bh'),
        ('6050', 'Adnan Khan',          'adnan.khan@afs.com.bh'),
        ('6205', 'Abdulla Adel',        'abdulla.adel@afs.com.bh'),
        ('6042', 'Anas Qureshi',        'anas.qureshi@afs.com.bh'),
        ('6129', 'Russell James',       'russell.james@afs.com.bh'),
        ('6238', 'Ali Alabyooki',       'ali.alabyooki@afs.com.bh'),
        ('6240', 'Mustafa Rahma',       'mustafa.rahma@afs.com.bh'),
        ('6040', 'Adham Alalami',       'adham.alalami@afs.com.bh'),  -- Team Lead (Existing/E-com)
        ('6039', 'Amira Ismail',        'amira.ismail@afs.com.bh')    -- Head
)
UPDATE dim_merchant dm
SET sales_email = rm.rm_email
FROM rm
WHERE dm.tenant_id = 8
  AND TRIM(dm.sales_user_id) = rm.rm_id;

-- 3. VACANT rule: any tenant-8 MID whose sales_user_id is NOT in the roster
--    (equivalent of "Sales Person 3/4") gets the VACANT bucket under Adham.
--    Review the SELECT in step 4 first if you want to see who this hits.
UPDATE dim_merchant
SET sales_user_id = 'VACANT',
    sales_email   = 'adham.alalami@afs.com.bh'   -- team lead per rule
WHERE tenant_id = 8
  AND sales_user_id IS NOT NULL
  AND sales_email IS NULL;   -- i.e. step 2 found no roster match

COMMIT;

-- 4. Verification: merchants per RM after mapping
SELECT sales_user_id, sales_email, COUNT(*) AS merchants
FROM dim_merchant
WHERE tenant_id = 8
GROUP BY sales_user_id, sales_email
ORDER BY merchants DESC;

-- 5. Anything left fully unmapped (should be only rows with NULL sales_user_id)
SELECT merchant_id, mid, name, sales_user_id, sales_email
FROM dim_merchant
WHERE tenant_id = 8 AND sales_email IS NULL;

-- ROLLBACK (if ever needed): restore from the backup table
-- UPDATE dim_merchant dm
-- SET sales_user_id = b.sales_user_id, sales_email = b.sales_email
-- FROM bak_dim_merchant_rm_20260915 b
-- WHERE dm.merchant_id = b.merchant_id;
