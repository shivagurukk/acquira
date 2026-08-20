-- ============================================================================
-- Local Debit Bank Dashboard — STEP 3 of 3: post-rebuild verification.
--
-- Run AFTER the summary rebuild has finished (see README_LOCAL_DEBIT_BANK.md
-- step 5). Every check below should pass before you hand the page to users.
--
-- RUN:
--   psql -h <host> -p <port> -U <user> -d <db> -v tenant_id=1 \
--        -f 03_verify_local_debit_bank.sql
-- ============================================================================

\echo '=== 1. BIN list loaded (expect 158 BINs / 40 banks for the UAE seed) ==='
SELECT COUNT(*) AS total_bins, COUNT(DISTINCT bank_name) AS distinct_banks
FROM ref_tenant_bin_bank WHERE tenant_id = :tenant_id;

\echo ''
\echo '=== 2. Summary populated (rows + date coverage) ==='
SELECT COUNT(*) AS rows,
       MIN(business_date) AS first_date,
       MAX(business_date) AS last_date,
       SUM(total_txns)    AS txns,
       SUM(total_volume)  AS volume
FROM sum_daily_local_debit_bin WHERE tenant_id = :tenant_id;

\echo ''
\echo '=== 3. PARITY: must match sum_daily_full DOMESTIC x DEBIT exactly ==='
\echo '    diff_volume and diff_txns MUST both be 0.'
WITH ldb AS (
    SELECT COALESCE(SUM(total_volume),0) AS vol, COALESCE(SUM(total_txns),0) AS txns
    FROM sum_daily_local_debit_bin WHERE tenant_id = :tenant_id
), full_slice AS (
    SELECT COALESCE(SUM(total_volume),0) AS vol, COALESCE(SUM(total_txns),0) AS txns
    FROM sum_daily_full
    WHERE tenant_id = :tenant_id
      AND merchant_id IS NOT NULL
      AND destination = 'DOMESTIC'
      AND UPPER(COALESCE(NULLIF(TRIM(card_type),''),'')) = 'DEBIT'
)
SELECT ldb.vol   AS dashboard_volume, full_slice.vol   AS card_type_page_volume,
       ldb.vol - full_slice.vol   AS diff_volume,
       ldb.txns  AS dashboard_txns,   full_slice.txns  AS card_type_page_txns,
       ldb.txns - full_slice.txns AS diff_txns
FROM ldb, full_slice;

\echo ''
\echo '=== 4. Per-month parity (any non-zero diff row is a problem) ==='
WITH ldb AS (
    SELECT to_char(business_date,'YYYY-MM') AS m,
           SUM(total_volume) AS vol, SUM(total_txns) AS txns
    FROM sum_daily_local_debit_bin WHERE tenant_id = :tenant_id GROUP BY 1
), full_slice AS (
    SELECT to_char(business_date,'YYYY-MM') AS m,
           SUM(total_volume) AS vol, SUM(total_txns) AS txns
    FROM sum_daily_full
    WHERE tenant_id = :tenant_id AND merchant_id IS NOT NULL
      AND destination = 'DOMESTIC'
      AND UPPER(COALESCE(NULLIF(TRIM(card_type),''),'')) = 'DEBIT'
    GROUP BY 1
)
SELECT COALESCE(l.m, f.m) AS month,
       COALESCE(l.vol,0) - COALESCE(f.vol,0)   AS diff_volume,
       COALESCE(l.txns,0) - COALESCE(f.txns,0) AS diff_txns
FROM ldb l FULL OUTER JOIN full_slice f ON l.m = f.m
WHERE COALESCE(l.vol,0) <> COALESCE(f.vol,0) OR COALESCE(l.txns,0) <> COALESCE(f.txns,0)
ORDER BY 1;

\echo ''
\echo '=== 5. Bank split + BIN match coverage ==='
SELECT COALESCE(b.bank_name,'Other Banks') AS bank,
       SUM(s.total_volume) AS volume,
       SUM(s.total_txns)   AS txns,
       ROUND(100.0 * SUM(s.total_volume) / NULLIF(SUM(SUM(s.total_volume)) OVER (),0), 2) AS share_pct
FROM sum_daily_local_debit_bin s
LEFT JOIN ref_tenant_bin_bank b ON b.tenant_id = s.tenant_id AND b.bin = s.bin6
WHERE s.tenant_id = :tenant_id
GROUP BY 1 ORDER BY volume DESC;

\echo ''
\echo '=== 6. Unmatched BINs — add these to the next upload to raise coverage ==='
SELECT s.bin6, SUM(s.total_volume) AS volume, SUM(s.total_txns) AS txns
FROM sum_daily_local_debit_bin s
LEFT JOIN ref_tenant_bin_bank b ON b.tenant_id = s.tenant_id AND b.bin = s.bin6
WHERE s.tenant_id = :tenant_id AND b.bin IS NULL
GROUP BY s.bin6 ORDER BY volume DESC LIMIT 50;

\echo ''
\echo '=== 7. Menu entry + group grants (empty grants = page invisible/403) ==='
SELECT m.menu_name, m.category, m.display_order,
       (SELECT COUNT(*) FROM sys_group_menu gm WHERE gm.menu_id = m.menu_id) AS group_grants
FROM sys_menu m WHERE m.path = '/business/local-debit-bank-dashboard';
