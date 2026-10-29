-- Egypt fee-resolution diagnostic (read-only). Adjust the date range to the
-- day you repriced. Run on UAT and share the output.

-- 1. Why fees are NULL: status counts for the repriced day(s).
SELECT ft.fee_resolution_status, ft.scheme_fee_status, COUNT(*) AS txns,
       SUM(ft.store_base_currency_amount) AS volume
FROM fact_transaction ft
JOIN tenant tn ON tn.tenant_id = ft.tenant_id
WHERE tn.home_country_code = 'EG'
  AND DATE(ft.payment_date) BETWEEN DATE '2026-10-15' AND DATE '2026-10-20'
GROUP BY 1, 2 ORDER BY 3 DESC;

-- 2. Raw destination tokens the feed carries vs what resolved.
SELECT ft.destination_raw, ft.destination, COUNT(*) AS txns
FROM fact_transaction ft
JOIN tenant tn ON tn.tenant_id = ft.tenant_id
WHERE tn.home_country_code = 'EG'
  AND DATE(ft.payment_date) BETWEEN DATE '2026-10-15' AND DATE '2026-10-20'
GROUP BY 1, 2 ORDER BY 3 DESC;

-- 3. Raw terminal types the feed carries vs the Egypt channel map.
SELECT UPPER(TRIM(COALESCE(dt.type,''))) AS terminal_type,
       (SELECT m.channel FROM terminal_channel_map m
         WHERE m.country_code = 'EG' AND m.raw_type = UPPER(TRIM(COALESCE(dt.type,''))) LIMIT 1) AS mapped_channel,
       COUNT(*) AS txns
FROM fact_transaction ft
JOIN tenant tn ON tn.tenant_id = ft.tenant_id
LEFT JOIN dim_terminal dt ON dt.terminal_id = ft.terminal_id AND dt.tenant_id = ft.tenant_id
WHERE tn.home_country_code = 'EG'
  AND DATE(ft.payment_date) BETWEEN DATE '2026-10-15' AND DATE '2026-10-20'
GROUP BY 1, 2 ORDER BY 3 DESC;

-- 4. Scheme / card type / product code tokens, to confirm the rate rows match.
SELECT ft.card_scheme, ft.card_product_code, ft.card_type, COUNT(*) AS txns
FROM fact_transaction ft
JOIN tenant tn ON tn.tenant_id = ft.tenant_id
WHERE tn.home_country_code = 'EG'
  AND DATE(ft.payment_date) BETWEEN DATE '2026-10-15' AND DATE '2026-10-20'
GROUP BY 1, 2, 3 ORDER BY 4 DESC LIMIT 30;

-- 5. Tenant settings that gate tiering and the country card.
SELECT tenant_id, bank_short_code, home_country_code, base_currency, card_type_source
FROM tenant WHERE home_country_code = 'EG';

-- 6. Confirm the new rate card is present and approved.
SELECT scheme_group, priority, rate_status, COUNT(*)
FROM interchange_rate_local
WHERE country_code = 'EG' AND tenant_id IS NULL AND dest = 'DOMESTIC'
GROUP BY 1, 2, 3 ORDER BY 1, 2;
