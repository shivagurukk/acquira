-- ============================================================================
-- Egypt Mastercard tier BIN-weighting — run against the LIVE DB that has EG data.
-- Produces the two volume-weighted interchange rates to bake into the EG MC
-- tier-alignment migration:
--     Premium bucket = Titanium (MCT, manual 1.85%) + Platinum (MPL, manual 2.00%)
--     Elite   bucket = World    (MCW, manual 2.15%) + World Elite (MWE, 2.20%)
-- POS = ECOM in the Egypt manual (Card Present == Full UCAF per tier), so one
-- weighted rate per bucket serves both channels.
--
-- Weighting basis: transaction VOLUME (store_base_currency_amount) of EG-tenant
-- Mastercard transactions, product code resolved from the card PAN via
-- ref_bin_range. Adjust the date window / tenant filter as needed.
-- ============================================================================

WITH eg_mc AS (
    SELECT ft.card_number, ABS(ft.store_base_currency_amount) AS vol
    FROM fact_transaction ft
    JOIN tenant t ON t.tenant_id = ft.tenant_id
    WHERE t.home_country_code = 'EG'
      AND UPPER(REPLACE(COALESCE(ft.card_scheme,''),' ','')) LIKE 'MASTER%'
      AND ft.card_number ~ '^[0-9]{9}'
      AND COALESCE(ft.store_base_currency_amount,0) <> 0
      -- AND ft.payment_date >= DATE '2025-01-01'   -- optional window
),
resolved AS (
    SELECT e.vol,
           ( SELECT r.product_code
             FROM ref_bin_range r
             WHERE r.scheme = 'MASTERCARD'
               AND r.range_low  <= LEFT(e.card_number,9)
               AND r.range_high >= LEFT(e.card_number,9)
             ORDER BY r.range_low DESC
             LIMIT 1 ) AS product_code
    FROM eg_mc e
)
SELECT
    product_code,
    COUNT(*)      AS txns,
    SUM(vol)      AS volume,
    ROUND(100.0 * SUM(vol) / NULLIF(SUM(SUM(vol)) OVER (), 0), 2) AS pct_of_all_mc
FROM resolved
GROUP BY product_code
ORDER BY volume DESC NULLS LAST;

-- ----------------------------------------------------------------------------
-- Direct weighted-rate answer (Premium + Elite), if you want the two numbers
-- without eyeballing the breakdown above. Uses ref_bin_product_tier's sub-tier
-- via product_code. Prints interchange_pct as a decimal fraction (e.g. 0.019200).
-- ----------------------------------------------------------------------------
WITH eg_mc AS (
    SELECT ft.card_number, ABS(ft.store_base_currency_amount) AS vol
    FROM fact_transaction ft
    JOIN tenant t ON t.tenant_id = ft.tenant_id
    WHERE t.home_country_code = 'EG'
      AND UPPER(REPLACE(COALESCE(ft.card_scheme,''),' ','')) LIKE 'MASTER%'
      AND ft.card_number ~ '^[0-9]{9}'
      AND COALESCE(ft.store_base_currency_amount,0) <> 0
),
resolved AS (
    SELECT e.vol,
           ( SELECT r.product_code FROM ref_bin_range r
             WHERE r.scheme = 'MASTERCARD'
               AND r.range_low  <= LEFT(e.card_number,9)
               AND r.range_high >= LEFT(e.card_number,9)
             ORDER BY r.range_low DESC LIMIT 1 ) AS product_code
    FROM eg_mc e
),
rated AS (
    SELECT vol,
           CASE product_code
               WHEN 'MCT' THEN 'Premium' WHEN 'MPL' THEN 'Premium'
               WHEN 'MCW' THEN 'Elite'   WHEN 'MWE' THEN 'Elite'
               ELSE NULL END AS bucket,
           CASE product_code
               WHEN 'MCT' THEN 0.0185 WHEN 'MPL' THEN 0.0200
               WHEN 'MCW' THEN 0.0215 WHEN 'MWE' THEN 0.0220
               ELSE NULL END AS manual_rate
    FROM resolved
)
SELECT bucket,
       ROUND(SUM(vol * manual_rate) / NULLIF(SUM(vol),0), 6) AS bin_weighted_pct
FROM rated
WHERE bucket IS NOT NULL
GROUP BY bucket
ORDER BY bucket;
