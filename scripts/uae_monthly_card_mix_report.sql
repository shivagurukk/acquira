-- UAE monthly card-mix report (one run per month) sourced from sum_daily_full.
-- Change the two dates for each month. Run as a role that bypasses RLS, or
-- SELECT set_config('app.current_tenant', '<tenant_id>', false) first.
SELECT
    st.sid                                                   AS "SID",
    COALESCE(st.legal_name, st.name, m.name)                 AS "Merchant_Store_Legal_Name",
    CASE WHEN s.channel_class = 'ECOM' THEN 'ECOM' ELSE '' END AS "eCommerce MOTO Indicator",
    s.card_scheme                                            AS "Scheme",
    CASE UPPER(s.destination)
         WHEN 'DOMESTIC'      THEN 'Local'
         WHEN 'INTERNATIONAL' THEN 'International'
         ELSE COALESCE(s.destination, 'Unmapped') END         AS "Destination",
    INITCAP(COALESCE(s.card_type, 'Unclassified'))           AS "Card Type Indicator 2",
    UPPER(s.card_scheme || ' ' || COALESCE(s.card_type, ''))  AS "Card Type Name",
    TO_CHAR(s.business_date, 'FMMonth')                      AS "Month Name",
    SUM(s.total_txns)                                        AS "Sum of Count of Transactions",
    ROUND(SUM(s.total_volume), 2)                            AS "Volume - AED",
    ROUND(SUM(s.total_msf), 2)                               AS "MSF - AED",
    ROUND(SUM(s.total_interchange), 2)                       AS "Interchange",
    ROUND(SUM(s.total_msf - s.total_interchange), 2)         AS "Net Revenue - AED",
    ROUND(SUM(s.total_scheme_fee), 2)                        AS "Scheme Fees - AED",
    ROUND(SUM(s.total_msf - s.total_interchange - s.total_scheme_fee), 2) AS "Net Margin - AED"
FROM sum_daily_full s
JOIN tenant t        ON t.tenant_id = s.tenant_id
LEFT JOIN dim_store st    ON st.store_id = s.store_id AND st.tenant_id = s.tenant_id
LEFT JOIN dim_merchant m  ON m.merchant_id = s.merchant_id AND m.tenant_id = s.tenant_id
WHERE t.home_country_code = 'AE'
  AND s.business_date >= DATE '2026-04-01'          -- month start
  AND s.business_date <  DATE '2026-05-01'          -- next month start
GROUP BY st.sid, COALESCE(st.legal_name, st.name, m.name), s.channel_class,
         s.card_scheme, s.destination, s.card_type, TO_CHAR(s.business_date, 'FMMonth')
ORDER BY "SID", "Scheme", "Destination", "Card Type Indicator 2";
