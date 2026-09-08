-- ============================================================================
-- BH VALIDATION — SINGLE QUERY, SINGLE RESULT SET
-- Tenant 8, business date 2026-08-27. Export the output as ONE CSV and send it
-- back for cross-comparison against the raw file.
--
-- Run:
--   psql -h <host> -U <user> -d acquira --csv \
--     -f BH_VALIDATION_SINGLE_2026-08-27_t8.sql > bh_validation_2026-08-27.csv
--
-- Sections in the "section" column:
--   01_TENANT            tenant identity row
--   02_FACT_DAY_TOTAL    fact_transaction totals for the day
--   03_FACT_BY_TXN_TYPE  per transaction_type
--   04_FEE_STATUS        fee_resolution_status x scheme_fee_status rollup
--   05_IC_RATE_CELLS     interchange cells used: scheme|chan/dest|mcc|rule:pct
--   06_SF_RATE_CELLS     scheme-fee cells used: scheme|chan/dest|group|pct:status
--   07_SUM_FULL_TOTAL    sum_daily_full totals for the day
--   08_SUM_MERCH_TOTAL   sum_daily_merchant totals for the day
--   09_DIFF_FULL         fact MINUS sum_daily_full (every value must be 0)
--   10_DIFF_MERCH        fact MINUS sum_daily_merchant (must be 0)
--   11_ERROR_COUNTS      txn_count>0 on any row here = a defect
--   12_ECOM_FEE_CONFIG   BH PG/ecom flat-fee configuration
--   13_BY_MID            per merchant (MID + name)
--   14_BY_SID            per store (MID | SID | MCC)
--   15_BY_TID            per terminal (TID | terminal type | channel)
--   16_BY_MCC            per MCC (MCC | channel/dest)
-- ============================================================================

WITH params AS (
    SELECT 8::int AS tenant_id, DATE '2026-08-27' AS d
),
f AS (   -- one scan of the day's fact rows, everything else derives from this
    SELECT ft.*,
           REPLACE(UPPER(TRIM(COALESCE(ft.transaction_type,''))),' ','') IN
             ('RFND','REFUND','REFUNDREVERSAL','REFUNDVOID','SALEREVERSAL','SALEVOID') AS is_refund,
           ds.mcc AS store_mcc,
           dm.mid AS mid, dm.name AS merchant_name,
           ds.sid AS sid,
           dt.tid AS tid, dt.type AS terminal_type
    FROM fact_transaction ft
    CROSS JOIN params p
    LEFT JOIN dim_store ds ON ds.store_id = ft.store_id AND ds.tenant_id = ft.tenant_id
    LEFT JOIN dim_merchant dm ON dm.merchant_id = ft.merchant_id AND dm.tenant_id = ft.tenant_id
    LEFT JOIN dim_terminal dt ON dt.terminal_id = ft.terminal_id AND dt.tenant_id = ft.tenant_id
    WHERE ft.tenant_id = p.tenant_id
      AND ft.payment_date >= p.d
      AND ft.payment_date <  p.d + 1
),
sdf AS (
    SELECT SUM(total_txns) AS txns, SUM(total_volume) AS vol, SUM(total_msf) AS msf,
           SUM(total_interchange) AS ic, SUM(total_scheme_fee) AS sf,
           SUM(total_ecom_fee) AS pg, SUM(total_net_revenue) AS net
    FROM sum_daily_full s CROSS JOIN params p
    WHERE s.tenant_id = p.tenant_id AND s.business_date = p.d
),
sdm AS (
    SELECT SUM(total_base_volume) AS vol, SUM(total_msf) AS msf,
           SUM(total_interchange) AS ic, SUM(total_scheme_fee) AS sf,
           SUM(total_ecom_fee) AS pg
    FROM sum_daily_merchant s CROSS JOIN params p
    WHERE s.tenant_id = p.tenant_id AND s.business_date = p.d
),
fact_tot AS (
    SELECT COUNT(*) AS txns,
           SUM(COALESCE(store_base_currency_amount,0)) AS vol,
           SUM(ABS(COALESCE(store_base_currency_amount,0))) AS gross,
           SUM(COALESCE(msf,0)) AS msf,
           SUM(COALESCE(interchange_fee,0)) AS ic,
           SUM(COALESCE(scheme_fee,0)) AS sf,
           SUM(COALESCE(ecom_fee,0)) AS pg
    FROM f
)

-- 01 tenant identity ---------------------------------------------------------
SELECT '01_TENANT' AS section,
       t.bank_short_code AS k1, t.home_country_code AS k2,
       t.bank_name AS k3, 'tenant_id='||t.tenant_id AS k4,
       NULL::numeric AS txn_count, NULL::numeric AS signed_volume,
       NULL::numeric AS gross_volume, NULL::numeric AS msf,
       NULL::numeric AS interchange, NULL::numeric AS scheme_fee,
       NULL::numeric AS pg_fee, NULL::numeric AS net_revenue
FROM tenant t CROSS JOIN params p WHERE t.tenant_id = p.tenant_id

UNION ALL
-- 02 fact day total ----------------------------------------------------------
SELECT '02_FACT_DAY_TOTAL', '2026-08-27', NULL, NULL, NULL,
       txns, ROUND(vol,3), ROUND(gross,3), ROUND(msf,3),
       ROUND(ic,3), ROUND(sf,3), ROUND(pg,3),
       ROUND(msf - ic - sf - pg,3)
FROM fact_tot

UNION ALL
-- 03 by transaction type -----------------------------------------------------
SELECT '03_FACT_BY_TXN_TYPE', transaction_type, NULL, NULL, NULL,
       COUNT(*), ROUND(SUM(COALESCE(store_base_currency_amount,0)),3),
       ROUND(SUM(ABS(COALESCE(store_base_currency_amount,0))),3),
       ROUND(SUM(COALESCE(msf,0)),3),
       ROUND(SUM(COALESCE(interchange_fee,0)),3),
       ROUND(SUM(COALESCE(scheme_fee,0)),3),
       ROUND(SUM(COALESCE(ecom_fee,0)),3), NULL
FROM f GROUP BY transaction_type

UNION ALL
-- 04 fee resolution status ---------------------------------------------------
SELECT '04_FEE_STATUS', fee_resolution_status, scheme_fee_status, NULL, NULL,
       COUNT(*), NULL,
       ROUND(SUM(ABS(COALESCE(store_base_currency_amount,0))),3),
       NULL,
       ROUND(SUM(COALESCE(interchange_fee,0)),3),
       ROUND(SUM(COALESCE(scheme_fee,0)),3), NULL, NULL
FROM f GROUP BY fee_resolution_status, scheme_fee_status

UNION ALL
-- 05 interchange rate cells used ---------------------------------------------
SELECT '05_IC_RATE_CELLS', ff.card_scheme,
       COALESCE(ff.channel,'?')||'/'||COALESCE(ff.destination,'?'),
       ff.store_mcc,
       'rule='||COALESCE(ff.interchange_rule_id::text,'-')
         ||' pct='||COALESCE(ff.interchange_pct_applied::text,'-')
         ||' cap='||COALESCE(ff.interchange_cap_applied::text,'-')
         ||' flat='||COALESCE(ff.interchange_flat_applied::text,'-'),
       COUNT(*), NULL,
       ROUND(SUM(ABS(COALESCE(ff.store_base_currency_amount,0))),3),
       NULL, ROUND(SUM(COALESCE(ff.interchange_fee,0)),3), NULL, NULL, NULL
FROM f ff
GROUP BY ff.card_scheme, 3, ff.store_mcc, 5

UNION ALL
-- 06 scheme-fee rate cells used ----------------------------------------------
SELECT '06_SF_RATE_CELLS', ff.card_scheme,
       COALESCE(ff.channel,'?')||'/'||COALESCE(ff.destination,'?'),
       COALESCE(sfr.scheme_group,'(wildcard)'),
       'pct='||COALESCE(sfr.fee_pct::text,'-')
         ||' flat='||COALESCE(sfr.flat_fee::text,'-')
         ||' status='||COALESCE(sfr.rate_status,'-'),
       COUNT(*), NULL,
       ROUND(SUM(ABS(COALESCE(ff.store_base_currency_amount,0))),3),
       NULL, NULL, ROUND(SUM(COALESCE(ff.scheme_fee,0)),3), NULL, NULL
FROM f ff
LEFT JOIN scheme_fee_rate sfr ON sfr.id = ff.scheme_fee_rule_id
GROUP BY ff.card_scheme, 3, 4, 5

UNION ALL
-- 07 sum_daily_full day total ------------------------------------------------
SELECT '07_SUM_FULL_TOTAL', '2026-08-27', NULL, NULL, NULL,
       txns, ROUND(vol,3), NULL, ROUND(msf,3),
       ROUND(ic,3), ROUND(sf,3), ROUND(pg,3), ROUND(net,3)
FROM sdf

UNION ALL
-- 08 sum_daily_merchant day total --------------------------------------------
SELECT '08_SUM_MERCH_TOTAL', '2026-08-27', NULL, NULL, NULL,
       NULL, ROUND(vol,3), NULL, ROUND(msf,3),
       ROUND(ic,3), ROUND(sf,3), ROUND(pg,3), NULL
FROM sdm

UNION ALL
-- 09 fact minus sum_daily_full (all must be 0) --------------------------------
SELECT '09_DIFF_FULL', 'fact-minus-summary', NULL, NULL, NULL,
       ft.txns - COALESCE(s.txns,0),
       ROUND(ft.vol - COALESCE(s.vol,0),3), NULL,
       ROUND(ft.msf - COALESCE(s.msf,0),3),
       ROUND(ft.ic  - COALESCE(s.ic,0),3),
       ROUND(ft.sf  - COALESCE(s.sf,0),3),
       ROUND(ft.pg  - COALESCE(s.pg,0),3),
       ROUND((ft.msf - ft.ic - ft.sf - ft.pg) - COALESCE(s.net,0),3)
FROM fact_tot ft CROSS JOIN sdf s

UNION ALL
-- 10 fact minus sum_daily_merchant (all must be 0) ----------------------------
SELECT '10_DIFF_MERCH', 'fact-minus-summary', NULL, NULL, NULL,
       NULL,
       ROUND(ft.vol - COALESCE(s.vol,0),3), NULL,
       ROUND(ft.msf - COALESCE(s.msf,0),3),
       ROUND(ft.ic  - COALESCE(s.ic,0),3),
       ROUND(ft.sf  - COALESCE(s.sf,0),3),
       ROUND(ft.pg  - COALESCE(s.pg,0),3), NULL
FROM fact_tot ft CROSS JOIN sdm s

UNION ALL
-- 11 error counts (txn_count MUST be 0 on every row) --------------------------
SELECT '11_ERROR_COUNTS', 'ic_recompute_mismatch',
       'stored ic <> LEAST(pct*|amt|,cap)+flat', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f
WHERE interchange_pct_applied IS NOT NULL AND NOT is_refund
  AND ABS( COALESCE(interchange_fee,0) -
        ( LEAST(interchange_pct_applied * ABS(COALESCE(store_base_currency_amount,0)),
                COALESCE(interchange_cap_applied, 999999999999))
          + COALESCE(interchange_flat_applied,0) ) ) > 0.005

UNION ALL
SELECT '11_ERROR_COUNTS', 'sf_recompute_mismatch',
       'stored sf <> pct*|amt|+flat', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f ff JOIN scheme_fee_rate sfr ON sfr.id = ff.scheme_fee_rule_id
WHERE NOT ff.is_refund
  AND ABS( COALESCE(ff.scheme_fee,0) -
        (sfr.fee_pct * ABS(COALESCE(ff.store_base_currency_amount,0))
          + COALESCE(sfr.flat_fee,0)) ) > 0.005

UNION ALL
SELECT '11_ERROR_COUNTS', 'ic_rule_pct_drift',
       'rate card pct <> pct applied', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f ff JOIN interchange_rate_local irl ON irl.id = ff.interchange_rule_id
WHERE irl.interchange_pct <> ff.interchange_pct_applied

UNION ALL
SELECT '11_ERROR_COUNTS', 'refund_fee_violation',
       'refund with nonzero ic/sf or positive volume', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f
WHERE is_refund
  AND (COALESCE(interchange_fee,0) <> 0 OR COALESCE(scheme_fee,0) <> 0
       OR store_base_currency_amount > 0)

UNION ALL
SELECT '11_ERROR_COUNTS', 'null_fee_nonrefund',
       'non-refund row with NULL ic or sf', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f WHERE NOT is_refund AND (interchange_fee IS NULL OR scheme_fee IS NULL)

UNION ALL
SELECT '11_ERROR_COUNTS', 'unresolved_fee_status',
       'status not RESOLVED/RESOLVED_SCHEME_WILDCARD', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f
WHERE COALESCE(fee_resolution_status,'?') NOT IN ('RESOLVED','RESOLVED_SCHEME_WILDCARD')

UNION ALL
SELECT '11_ERROR_COUNTS', 'missing_merchant',
       'fact row with NULL merchant_id', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f WHERE merchant_id IS NULL

UNION ALL
SELECT '11_ERROR_COUNTS', 'ecom_row_missing_pg_fee',
       'channel=ECOM but ecom_fee NULL', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f WHERE channel = 'ECOM' AND ecom_fee IS NULL

UNION ALL
SELECT '11_ERROR_COUNTS', 'pos_row_with_pg_fee',
       'channel<>ECOM but ecom_fee not null/0', NULL, NULL,
       COUNT(*), NULL, NULL, NULL, NULL, NULL, NULL, NULL
FROM f WHERE COALESCE(channel,'') <> 'ECOM' AND COALESCE(ecom_fee,0) <> 0

UNION ALL
-- 12 BH ecom/PG flat fee configuration ---------------------------------------
SELECT '12_ECOM_FEE_CONFIG', e.country_code,
       'tenant='||COALESCE(e.tenant_id::text,'(country default)'),
       'fee_amount='||e.fee_amount::text, NULL,
       NULL, NULL, NULL, NULL, NULL, NULL, ROUND(e.fee_amount,3), NULL
FROM ecom_flat_fee e WHERE e.country_code = 'BH'

UNION ALL
-- 13 per merchant (MID) -------------------------------------------------------
SELECT '13_BY_MID', COALESCE(mid,'(no merchant)'), merchant_name, NULL, NULL,
       COUNT(*),
       ROUND(SUM(COALESCE(store_base_currency_amount,0)),3),
       ROUND(SUM(ABS(COALESCE(store_base_currency_amount,0))),3),
       ROUND(SUM(COALESCE(msf,0)),3),
       ROUND(SUM(COALESCE(interchange_fee,0)),3),
       ROUND(SUM(COALESCE(scheme_fee,0)),3),
       ROUND(SUM(COALESCE(ecom_fee,0)),3),
       ROUND(SUM(COALESCE(msf,0)) - SUM(COALESCE(interchange_fee,0))
             - SUM(COALESCE(scheme_fee,0)) - SUM(COALESCE(ecom_fee,0)),3)
FROM f GROUP BY mid, merchant_name

UNION ALL
-- 14 per store (MID | SID | MCC) ----------------------------------------------
SELECT '14_BY_SID', COALESCE(mid,'(no merchant)'), COALESCE(sid,'(no store)'),
       store_mcc, NULL,
       COUNT(*),
       ROUND(SUM(COALESCE(store_base_currency_amount,0)),3),
       ROUND(SUM(ABS(COALESCE(store_base_currency_amount,0))),3),
       ROUND(SUM(COALESCE(msf,0)),3),
       ROUND(SUM(COALESCE(interchange_fee,0)),3),
       ROUND(SUM(COALESCE(scheme_fee,0)),3),
       ROUND(SUM(COALESCE(ecom_fee,0)),3), NULL
FROM f GROUP BY mid, sid, store_mcc

UNION ALL
-- 15 per terminal (TID | terminal type | channel) ------------------------------
SELECT '15_BY_TID', COALESCE(tid,'(no terminal)'),
       COALESCE(NULLIF(TRIM(terminal_type),''),'(blank type)'),
       channel, COALESCE(mid,'')||'/'||COALESCE(sid,''),
       COUNT(*),
       ROUND(SUM(COALESCE(store_base_currency_amount,0)),3),
       ROUND(SUM(ABS(COALESCE(store_base_currency_amount,0))),3),
       ROUND(SUM(COALESCE(msf,0)),3),
       ROUND(SUM(COALESCE(interchange_fee,0)),3),
       ROUND(SUM(COALESCE(scheme_fee,0)),3),
       ROUND(SUM(COALESCE(ecom_fee,0)),3), NULL
FROM f GROUP BY tid, 3, channel, 5

UNION ALL
-- 16 per MCC (MCC | channel/dest) ----------------------------------------------
SELECT '16_BY_MCC', COALESCE(store_mcc,'(no mcc)'),
       COALESCE(channel,'?')||'/'||COALESCE(destination,'?'), NULL, NULL,
       COUNT(*),
       ROUND(SUM(COALESCE(store_base_currency_amount,0)),3),
       ROUND(SUM(ABS(COALESCE(store_base_currency_amount,0))),3),
       ROUND(SUM(COALESCE(msf,0)),3),
       ROUND(SUM(COALESCE(interchange_fee,0)),3),
       ROUND(SUM(COALESCE(scheme_fee,0)),3),
       ROUND(SUM(COALESCE(ecom_fee,0)),3), NULL
FROM f GROUP BY store_mcc, 3

ORDER BY 1, 2, 3, 4;
