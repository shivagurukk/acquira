-- ============================================================================
-- ANALYSIS (read-only): international Mastercard interchange - flat 1.85%
-- placeholder vs the MC manual's interregional / intra-MEA tier x channel grid.
--
-- Answers: "is replacing the flat 1.85% INTERNATIONAL row with the manual
-- grid material?" Run on UAT/prod psql (any tenant DB). Nothing is written.
--
-- Method: every INTERNATIONAL Mastercard fact row is BIN-matched (first 6
-- digits, hash join - ref_bin_range has no bin6 index, do NOT use a lateral)
-- to get issuer country + product code; product code -> tier via
-- ref_bin_product_tier. Issuer country in the MEA list => intra-MEA rates,
-- else interregional rates:
--   tier          interregional POS/ECOM     intra-MEA POS/ECOM
--   Standard      1.10% / 1.60%              1.16% / 1.69%
--   Premium       1.85% / 1.85%              1.80% / 1.90%
--   Elite (SP)    1.98% / 1.98%              2.05% / 2.15%
--   COMMERCIAL    2.00% / 2.00%              2.00% / 2.00%
--   unmatched BIN or unmapped product -> stays 1.85% (no delta)
-- delta = manual-grid fee - CURRENT fee actually stored on the fact row.
-- ============================================================================

-- ---- PARAMETERS: edit these two lines ---------------------------------------
WITH params AS (
  SELECT (CURRENT_DATE - 1)  AS target_day,     -- the day to analyse (yesterday); e.g. DATE '2026-09-13'
         ARRAY[1, 8]         AS tenant_ids      -- 1 = UAE, 8 = Bahrain
),
-- -----------------------------------------------------------------------------
mea(cc) AS (VALUES  -- Mastercard MEA region issuer countries (edit as needed)
  ('AE'),('SA'),('BH'),('KW'),('QA'),('OM'),('EG'),('JO'),('LB'),('IQ'),('YE'),('PS'),
  ('ZA'),('NG'),('KE'),('MA'),('TN'),('DZ'),('GH'),('TZ'),('UG'),('ET'),('AO'),('CI'),
  ('SN'),('CM'),('ZM'),('ZW'),('BW'),('NA'),('MU'),('LY'),('SD'),('MR'),('DJ'),('SO')
),
intl AS MATERIALIZED (
  SELECT ft.tenant_id,
         DATE(ft.payment_date)                              AS pay_day,
         UPPER(TRIM(COALESCE(ft.channel,'POS')))            AS channel,
         LEFT(ft.card_number, 6)                            AS bin6,
         ABS(COALESCE(ft.store_base_currency_amount, 0))    AS amt,
         COALESCE(ft.interchange_fee, 0)                    AS current_fee
  FROM fact_transaction ft, params p
  WHERE ft.tenant_id = ANY (p.tenant_ids)
    AND ft.payment_date >= p.target_day                     -- sargable day range
    AND ft.payment_date <  p.target_day + 1                 -- (never wrap payment_date in a function)
    AND UPPER(TRIM(COALESCE(ft.destination,''))) = 'INTERNATIONAL'
    AND UPPER(REPLACE(ft.card_scheme,' ','')) IN ('MASTERCARD','MC','MCRD')
),
binmap AS MATERIALIZED (          -- one row per distinct BIN, hash-join friendly
  SELECT DISTINCT ON (r.bin6) r.bin6, r.issuer_country, r.product_code
  FROM ref_bin_range r
  JOIN (SELECT DISTINCT bin6 FROM intl) b ON b.bin6 = r.bin6
  ORDER BY r.bin6, (UPPER(COALESCE(r.scheme,'')) LIKE 'MASTER%') DESC, r.id
),
priced AS (
  SELECT i.tenant_id, i.pay_day, i.channel, i.amt, i.current_fee,
         COALESCE(bpt.card_class, 'UNKNOWN') AS card_class,
         COALESCE(bpt.card_tier,  'UNKNOWN') AS card_tier,
         (bm.issuer_country IN (SELECT cc FROM mea)) AS intra_mea,
         CASE
           WHEN bpt.card_class = 'COMMERCIAL' THEN 0.0200
           WHEN bpt.card_tier = 'Standard' AND bm.issuer_country NOT IN (SELECT cc FROM mea) AND i.channel = 'ECOM' THEN 0.0160
           WHEN bpt.card_tier = 'Standard' AND bm.issuer_country NOT IN (SELECT cc FROM mea)                       THEN 0.0110
           WHEN bpt.card_tier = 'Standard' AND i.channel = 'ECOM' THEN 0.0169
           WHEN bpt.card_tier = 'Standard'                        THEN 0.0116
           WHEN bpt.card_tier = 'Premium' AND bm.issuer_country IN (SELECT cc FROM mea) AND i.channel = 'ECOM' THEN 0.0190
           WHEN bpt.card_tier = 'Premium' AND bm.issuer_country IN (SELECT cc FROM mea)                        THEN 0.0180
           WHEN bpt.card_tier = 'Premium'                                                                      THEN 0.0185
           WHEN bpt.card_tier = 'Elite' AND bm.issuer_country NOT IN (SELECT cc FROM mea)                      THEN 0.0198
           WHEN bpt.card_tier = 'Elite' AND i.channel = 'ECOM' THEN 0.0215
           WHEN bpt.card_tier = 'Elite'                        THEN 0.0205
           ELSE 0.0185
         END AS manual_pct
  FROM intl i
  LEFT JOIN binmap bm ON bm.bin6 = i.bin6
  LEFT JOIN ref_bin_product_tier bpt ON bpt.product_code = bm.product_code
)
SELECT tenant_id,
       CASE tenant_id WHEN 1 THEN 'UAE' WHEN 8 THEN 'BH' ELSE '?' END AS tenant,
       pay_day,
       CASE WHEN GROUPING(intra_mea) = 1 THEN '== TOTAL =='
            WHEN intra_mea THEN 'INTRA-MEA'
            WHEN intra_mea IS NULL THEN 'BIN-UNMATCHED'
            ELSE 'INTERREGIONAL' END AS region,
       card_tier, card_class, channel,
       COUNT(*)                                    AS txns,
       ROUND(SUM(amt), 2)                          AS volume,
       ROUND(SUM(current_fee), 2)                  AS current_interchange,
       ROUND(SUM(amt * manual_pct), 2)             AS manual_grid_interchange,
       ROUND(SUM(amt * manual_pct) - SUM(current_fee), 2) AS delta,
       ROUND(CAST(100.0 * (SUM(amt * manual_pct) - SUM(current_fee))
             / NULLIF(SUM(current_fee), 0) AS numeric), 1) AS delta_pct
FROM priced
GROUP BY tenant_id, pay_day, ROLLUP ((intra_mea, card_tier, card_class, channel))
ORDER BY tenant_id, region NULLS LAST, card_tier, channel;
