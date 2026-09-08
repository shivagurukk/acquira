-- ============================================================================
-- Diagnostic: EXPLAIN ANALYZE the tmp_fee_resolve build (REPLACE-mode path).
--
-- Reproduces exactly what FeeComputationService does: stage the batch rows into
-- tmp_fact_batch (as REPLACE mode does), build tmp_bin_tier, then EXPLAIN
-- ANALYZE the fee-resolution SELECT. Run the WHOLE file in ONE psql session
-- (the temp tables must live for the final EXPLAIN).
--
-- SET THESE for the tenant + window you saw run slow.
-- d_end is EXCLUSIVE (use the day AFTER the last date in the load).
\set tid 8
\set d_start '2026-08-01'
\set d_end '2026-08-11'
-- ============================================================================

-- 1. Snapshot the batch exactly like REPLACE mode's tmp_fact_batch.
DROP TABLE IF EXISTS tmp_fact_batch;
CREATE TEMP TABLE tmp_fact_batch AS
  SELECT * FROM fact_transaction
  WHERE tenant_id = :tid AND payment_date >= DATE :'d_start' AND payment_date < DATE :'d_end';
ANALYZE tmp_fact_batch;
SELECT count(*) AS batch_rows FROM tmp_fact_batch;

-- 2. Build tmp_bin_tier (BH-style; harmless/empty for non-BIN tenants).
DROP TABLE IF EXISTS tmp_bin_tier;
CREATE TEMP TABLE tmp_bin_tier (bin6 VARCHAR(6) PRIMARY KEY, card_tier VARCHAR(10), card_class VARCHAR(12));
INSERT INTO tmp_bin_tier (bin6, card_tier, card_class)
SELECT b.bin6, bt.card_tier, bt.card_class FROM (
   SELECT LEFT(ft.card_number,6) AS bin6,
          MAX(CASE WHEN UPPER(REPLACE(COALESCE(ft.card_scheme,''),' ','')) LIKE 'MASTER%' THEN 'MASTERCARD'
                   WHEN UPPER(REPLACE(COALESCE(ft.card_scheme,''),' ','')) LIKE 'VISA%'   THEN 'VISA' END) AS scheme
   FROM tmp_fact_batch ft
   WHERE ft.card_number ~ '^[0-9]{6}' AND COALESCE(NULLIF(TRIM(ft.card_product_code),''),'') = ''
   GROUP BY LEFT(ft.card_number,6)
) b
JOIN LATERAL (
   SELECT bpt.card_tier, bpt.card_class FROM (
     SELECT rbr.product_code, rbr.range_low, rbr.range_high FROM ref_bin_range rbr
     WHERE rbr.scheme = b.scheme AND rbr.range_low <= b.bin6 || '999'
     ORDER BY rbr.range_low DESC LIMIT 8
   ) cand
   JOIN ref_bin_product_tier bpt ON bpt.product_code = cand.product_code
   WHERE cand.range_high >= b.bin6 || '000'
   ORDER BY cand.range_low DESC LIMIT 1
) bt ON TRUE
WHERE b.scheme IS NOT NULL;
ANALYZE tmp_bin_tier;

-- 3. EXPLAIN ANALYZE the resolution SELECT. Look for: high "loops=" on any
--    LATERAL, Seq Scans executed per row (scheme_fee_rate / dim_store), and any
--    node whose "actual" rows dwarf its estimate.
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT ft.transaction_id, ch.channel, bint.card_tier, bint.card_class, lr.id AS ic, sfr.id AS sf, eff.fee_amount
FROM tmp_fact_batch ft
LEFT JOIN tenant tn ON tn.tenant_id = ft.tenant_id
LEFT JOIN dim_store ds ON ds.store_id = ft.store_id AND ds.tenant_id = ft.tenant_id
LEFT JOIN dim_terminal dt ON dt.terminal_id = ft.terminal_id AND dt.tenant_id = ft.tenant_id
CROSS JOIN LATERAL (SELECT REPLACE(UPPER(TRIM(COALESCE(ft.card_product_code,''))),' ','') AS v) pc
CROSS JOIN LATERAL (SELECT REPLACE(UPPER(TRIM(COALESCE(ft.card_scheme,''))),' ','') AS v) sc
LEFT JOIN LATERAL (
    SELECT r.*, CASE WHEN pc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','')=pc.v OR REPLACE(UPPER(TRIM(r.name)),' ','')=pc.v) THEN 1 ELSE 0 END AS by_product
    FROM ref_card_scheme r
    WHERE (pc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','')=pc.v OR REPLACE(UPPER(TRIM(r.name)),' ','')=pc.v))
       OR (sc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','')=sc.v OR REPLACE(UPPER(TRIM(r.name)),' ','')=sc.v))
    ORDER BY by_product DESC, r.id ASC LIMIT 1) rcs ON TRUE
CROSS JOIN LATERAL (SELECT COALESCE(
      (SELECT t1.channel FROM terminal_channel_map t1 WHERE t1.country_code=COALESCE(tn.home_country_code,'AE') AND (t1.tenant_id IS NULL OR t1.tenant_id=ft.tenant_id) AND t1.raw_type=UPPER(TRIM(COALESCE(dt.type,''))) ORDER BY (t1.tenant_id IS NOT NULL) DESC LIMIT 1),
      (SELECT t2.channel FROM terminal_channel_map t2 WHERE t2.country_code=COALESCE(tn.home_country_code,'AE') AND (t2.tenant_id IS NULL OR t2.tenant_id=ft.tenant_id) AND t2.raw_type='*' ORDER BY (t2.tenant_id IS NOT NULL) DESC LIMIT 1)
    ) AS channel) ch
CROSS JOIN LATERAL (SELECT (REPLACE(UPPER(TRIM(COALESCE(ft.transaction_type,''))),' ','') IN ('RFND','REFUND','REFUNDREVERSAL','REFUNDVOID','SALEREVERSAL','SALEVOID')) AS is_refund) rf
LEFT JOIN LATERAL (SELECT m.sector FROM mcc_sector_map m WHERE m.country_code=COALESCE(tn.home_country_code,'AE') AND (m.tenant_id IS NULL OR m.tenant_id=ft.tenant_id) AND m.mcc=ds.mcc ORDER BY (m.tenant_id IS NOT NULL) DESC LIMIT 1) msm ON TRUE
LEFT JOIN tmp_bin_tier bint ON bint.bin6 = LEFT(ft.card_number,6)
LEFT JOIN LATERAL (
    SELECT ilr.id FROM (
      SELECT i.* FROM interchange_rate_local i WHERE i.country_code=COALESCE(tn.home_country_code,'AE') AND (i.tenant_id IS NULL OR i.tenant_id=ft.tenant_id) AND i.dest=UPPER(TRIM(COALESCE(ft.destination,''))) AND i.mcc=ds.mcc
      UNION ALL
      SELECT i.* FROM interchange_rate_local i WHERE i.country_code=COALESCE(tn.home_country_code,'AE') AND (i.tenant_id IS NULL OR i.tenant_id=ft.tenant_id) AND i.dest=UPPER(TRIM(COALESCE(ft.destination,''))) AND i.mcc IS NULL
    ) ilr
    WHERE (ilr.channel IS NULL OR ilr.channel=ch.channel)
      AND (ilr.scheme_group IS NULL OR ilr.scheme_group=COALESCE(rcs.group_name,''))
      AND (ilr.card_type IS NULL OR ilr.card_type = CASE WHEN pc.v='' AND bint.card_class='COMMERCIAL' THEN 'COMMERCIAL' WHEN rcs.card_type=3 THEN 'CREDIT' ELSE UPPER(TRIM(COALESCE(ft.card_type,''))) END)
      AND (ilr.tier IS NULL OR ilr.tier = CASE WHEN rcs.card_subtype=1 THEN 'Standard' WHEN pc.v='' AND bint.card_tier IS NOT NULL THEN bint.card_tier ELSE 'Premium' END)
      AND (ilr.mcc_sector IS NULL OR ilr.mcc_sector=msm.sector)
      AND (ilr.min_ticket IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) >= ilr.min_ticket)
      AND (ilr.max_ticket IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) < ilr.max_ticket)
    ORDER BY (ilr.rate_status='APPROVED') DESC, (ilr.tenant_id IS NOT NULL) DESC, ilr.priority DESC, ilr.id ASC LIMIT 1) lr ON TRUE
LEFT JOIN LATERAL (SELECT s.id FROM scheme_fee_rate s WHERE s.country_code=COALESCE(tn.home_country_code,'AE') AND (s.tenant_id IS NULL OR s.tenant_id=ft.tenant_id) AND s.dest=UPPER(TRIM(COALESCE(ft.destination,''))) AND s.channel=ch.channel AND (s.scheme_group IS NULL OR s.scheme_group=COALESCE(rcs.group_name,'')) ORDER BY (s.rate_status='APPROVED') DESC, (s.tenant_id IS NOT NULL) DESC, (s.scheme_group IS NOT NULL) DESC LIMIT 1) sfr ON TRUE
LEFT JOIN LATERAL (SELECT e.fee_amount FROM ecom_flat_fee e WHERE e.country_code=COALESCE(tn.home_country_code,'AE') AND (e.tenant_id IS NULL OR e.tenant_id=ft.tenant_id) ORDER BY (e.tenant_id IS NOT NULL) DESC LIMIT 1) eff ON TRUE
WHERE ft.tenant_id = :tid;
