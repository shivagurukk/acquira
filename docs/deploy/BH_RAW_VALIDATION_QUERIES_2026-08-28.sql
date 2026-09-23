-- ============================================================================
-- BAHRAIN RAW-FILE vs FACT vs SUMMARY VALIDATION PACK (2026-08-28)
--
-- Purpose: after uploading a BH raw settlement file, run these top-to-bottom
-- to prove the whole chain is correct:
--   raw file (Excel)  ->  fact_transaction  ->  sum_daily_full / sum_daily_*
-- covering row counts, volume, MSF, INTERCHANGE, SCHEME FEE, PG/ECOM fee.
--
-- HOW TO USE (psql):
--   psql -h <host> -U <user> -d <db> -v tenant=<TENANT_ID> \
--        -v from="'2026-08-01'" -v to="'2026-08-31'" \
--        -f BH_RAW_VALIDATION_QUERIES_2026-08-28.sql
-- or paste queries one at a time replacing :tenant / :from / :to by hand.
--
-- The fee math below mirrors FeeComputationService (the single fee engine all
-- ingest paths share):  interchange = LEAST(pct * ABS(settle_amt), cap) + flat
--                       scheme_fee  = pct * ABS(settle_amt) + flat
--                       refunds     = 0 for both
--                       ecom_fee    = per-country ecom_flat_fee on ECOM only
-- All fees are off store_base_currency_amount (settlement BHD), never the
-- cardholder amount.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Q0. Find the BH tenant id (use this for :tenant everywhere below)
-- ---------------------------------------------------------------------------
SELECT tenant_id, bank_name, bank_short_code, home_country_code, input_format
FROM tenant
WHERE home_country_code = 'BH';

-- ---------------------------------------------------------------------------
-- Q1. RAW FILE vs FACT — per-day counts and volume.
-- Compare these numbers against a pivot of the raw Excel:
--   rows per payment date, SUM(settlement amount), SUM(MSF), split by type.
-- Volume is SIGNED the way ingest signs it (refund/reversal negative), and
-- gross ABS volume is shown too so you can match whichever the file gives.
-- ---------------------------------------------------------------------------
SELECT
    DATE(payment_date)                              AS business_date,
    COUNT(*)                                        AS fact_rows,
    SUM(store_base_currency_amount)                 AS signed_settle_volume,
    SUM(ABS(store_base_currency_amount))            AS gross_settle_volume,
    SUM(msf)                                        AS total_msf,
    SUM(interchange_fee)                            AS total_interchange,
    SUM(scheme_fee)                                 AS total_scheme_fee,
    SUM(ecom_fee)                                   AS total_pg_ecom_fee,
    COUNT(*) FILTER (WHERE merchant_id IS NULL)     AS rows_missing_merchant   -- must be 0
FROM fact_transaction
WHERE tenant_id = :tenant
  AND payment_date >= :from::date
  AND payment_date <  (:to::date + 1)
GROUP BY 1 ORDER BY 1;

-- Per transaction-type split (match the file's Purchase/Refund breakdown;
-- BH descriptive tokens are normalized to PURCHASE/REFUND at ingest):
SELECT DATE(payment_date) AS business_date, transaction_type,
       COUNT(*) AS rows, SUM(store_base_currency_amount) AS signed_volume
FROM fact_transaction
WHERE tenant_id = :tenant
  AND payment_date >= :from::date AND payment_date < (:to::date + 1)
GROUP BY 1, 2 ORDER BY 1, 2;

-- ---------------------------------------------------------------------------
-- Q2. FEE RESOLUTION HEALTH — every row must be RESOLVED (or
-- RESOLVED_SCHEME_WILDCARD, which is legitimate but worth eyeballing).
-- Anything else = unpriced money: NO_RATE_FOUND / PLACEHOLDER_RATE /
-- UNMAPPED_CHANNEL / UNMAPPED_DESTINATION.
-- ---------------------------------------------------------------------------
SELECT fee_resolution_status, scheme_fee_status, COUNT(*) AS rows,
       SUM(ABS(store_base_currency_amount)) AS gross_volume
FROM fact_transaction
WHERE tenant_id = :tenant
  AND payment_date >= :from::date AND payment_date < (:to::date + 1)
GROUP BY 1, 2 ORDER BY 3 DESC;

-- If anything is unmapped, see WHICH tokens:
SELECT DISTINCT destination, card_scheme, transaction_type
FROM fact_transaction
WHERE tenant_id = :tenant
  AND payment_date >= :from::date AND payment_date < (:to::date + 1)
  AND fee_resolution_status NOT IN ('RESOLVED','RESOLVED_SCHEME_WILDCARD')
LIMIT 50;

-- ---------------------------------------------------------------------------
-- Q3. INTERCHANGE MATH RE-CHECK — recompute every row's interchange from the
-- provenance columns the engine stamped (pct/cap/flat applied) and diff it
-- against the stored fee. Expect ZERO rows back (tolerance 0.005 BHD).
-- ---------------------------------------------------------------------------
SELECT transaction_id, DATE(payment_date) AS business_date, card_scheme,
       store_base_currency_amount, interchange_pct_applied,
       interchange_cap_applied, interchange_flat_applied,
       interchange_fee AS stored_ic,
       LEAST(interchange_pct_applied * ABS(COALESCE(store_base_currency_amount,0)),
             COALESCE(interchange_cap_applied, 999999999999))
         + COALESCE(interchange_flat_applied,0) AS recomputed_ic
FROM fact_transaction
WHERE tenant_id = :tenant
  AND payment_date >= :from::date AND payment_date < (:to::date + 1)
  AND interchange_pct_applied IS NOT NULL
  AND ABS( interchange_fee -
        ( LEAST(interchange_pct_applied * ABS(COALESCE(store_base_currency_amount,0)),
                COALESCE(interchange_cap_applied, 999999999999))
          + COALESCE(interchange_flat_applied,0) ) ) > 0.005
LIMIT 100;

-- ---------------------------------------------------------------------------
-- Q4. INTERCHANGE RATE-CARD RE-CHECK — did the engine pick the RIGHT rule?
-- Shows each distinct pricing cell actually used (scheme x channel x dest x
-- MCC) with the rule it matched and the card rate. Verify the pct against the
-- Bahrain business-case workbook by eye. rule_pct <> applied_pct must be empty.
-- ---------------------------------------------------------------------------
SELECT ft.card_scheme, ft.channel, ft.destination, ds.mcc,
       ft.interchange_rule_id, irl.label AS rule_label,
       irl.interchange_pct AS card_pct, ft.interchange_pct_applied AS applied_pct,
       COUNT(*) AS rows, SUM(ABS(ft.store_base_currency_amount)) AS gross_volume,
       SUM(ft.interchange_fee) AS interchange
FROM fact_transaction ft
LEFT JOIN dim_store ds ON ds.store_id = ft.store_id AND ds.tenant_id = ft.tenant_id
LEFT JOIN interchange_rate_local irl ON irl.id = ft.interchange_rule_id
WHERE ft.tenant_id = :tenant
  AND ft.payment_date >= :from::date AND ft.payment_date < (:to::date + 1)
GROUP BY 1,2,3,4,5,6,7,8
ORDER BY rows DESC;

-- Any row where the stamped pct no longer matches the card (card edited after
-- ingest, or wrong rule) — expect ZERO rows:
SELECT ft.interchange_rule_id, irl.label, irl.interchange_pct,
       ft.interchange_pct_applied, COUNT(*)
FROM fact_transaction ft
JOIN interchange_rate_local irl ON irl.id = ft.interchange_rule_id
WHERE ft.tenant_id = :tenant
  AND ft.payment_date >= :from::date AND ft.payment_date < (:to::date + 1)
  AND irl.interchange_pct <> ft.interchange_pct_applied
GROUP BY 1,2,3,4;

-- ---------------------------------------------------------------------------
-- Q5. SCHEME FEE RE-CHECK — recompute pct * ABS(amount) + flat from the rule
-- the engine stamped, diff vs stored. Expect ZERO rows (tolerance 0.005).
-- ---------------------------------------------------------------------------
SELECT ft.transaction_id, DATE(ft.payment_date) AS business_date,
       ft.card_scheme, ft.channel, ft.destination,
       ft.store_base_currency_amount, sfr.fee_pct, sfr.flat_fee,
       ft.scheme_fee AS stored_sf,
       (sfr.fee_pct * ABS(COALESCE(ft.store_base_currency_amount,0))
         + COALESCE(sfr.flat_fee,0)) AS recomputed_sf
FROM fact_transaction ft
JOIN scheme_fee_rate sfr ON sfr.id = ft.scheme_fee_rule_id
WHERE ft.tenant_id = :tenant
  AND ft.payment_date >= :from::date AND ft.payment_date < (:to::date + 1)
  AND REPLACE(UPPER(TRIM(COALESCE(ft.transaction_type,''))),' ','')
      NOT IN ('RFND','REFUND','REFUNDREVERSAL','REFUNDVOID','SALEREVERSAL','SALEVOID')
  AND ABS( ft.scheme_fee -
        (sfr.fee_pct * ABS(COALESCE(ft.store_base_currency_amount,0))
          + COALESCE(sfr.flat_fee,0)) ) > 0.005
LIMIT 100;

-- Scheme-fee rate cells actually used (verify pct by eye against the BH grid):
SELECT ft.card_scheme, ft.channel, ft.destination,
       sfr.scheme_group AS matched_group, sfr.fee_pct, sfr.rate_status,
       COUNT(*) AS rows, SUM(ft.scheme_fee) AS scheme_fee
FROM fact_transaction ft
LEFT JOIN scheme_fee_rate sfr ON sfr.id = ft.scheme_fee_rule_id
WHERE ft.tenant_id = :tenant
  AND ft.payment_date >= :from::date AND ft.payment_date < (:to::date + 1)
GROUP BY 1,2,3,4,5,6 ORDER BY rows DESC;

-- ---------------------------------------------------------------------------
-- Q6. REFUND RULE — refunds/reversals must carry ZERO interchange and ZERO
-- scheme fee, and negative signed volume. Expect ZERO rows.
-- ---------------------------------------------------------------------------
SELECT transaction_id, DATE(payment_date), transaction_type,
       store_base_currency_amount, interchange_fee, scheme_fee
FROM fact_transaction
WHERE tenant_id = :tenant
  AND payment_date >= :from::date AND payment_date < (:to::date + 1)
  AND REPLACE(UPPER(TRIM(COALESCE(transaction_type,''))),' ','')
      IN ('RFND','REFUND','REFUNDREVERSAL','REFUNDVOID','SALEREVERSAL','SALEVOID')
  AND (COALESCE(interchange_fee,0) <> 0 OR COALESCE(scheme_fee,0) <> 0
       OR store_base_currency_amount > 0)
LIMIT 100;

-- ---------------------------------------------------------------------------
-- Q7. PG / ECOM FLAT FEE — what BH is configured to charge, and that every
-- ECOM row carries exactly that amount (non-ECOM rows must be NULL).
-- ---------------------------------------------------------------------------
SELECT * FROM ecom_flat_fee WHERE country_code = 'BH';

SELECT channel, COUNT(*) AS rows,
       COUNT(DISTINCT ecom_fee) AS distinct_fee_values,
       MIN(ecom_fee) AS min_fee, MAX(ecom_fee) AS max_fee,
       SUM(ecom_fee) AS total_pg_fee
FROM fact_transaction
WHERE tenant_id = :tenant
  AND payment_date >= :from::date AND payment_date < (:to::date + 1)
GROUP BY 1;

-- ---------------------------------------------------------------------------
-- Q8. FACT vs sum_daily_full — the summary must equal fact exactly, per day.
-- Expect ZERO rows back (every column diff = 0).
-- ---------------------------------------------------------------------------
WITH f AS (
    SELECT DATE(payment_date) AS d,
           COUNT(*) AS txns,
           SUM(COALESCE(store_base_currency_amount,0)) AS vol,
           SUM(COALESCE(msf,0)) AS msf,
           SUM(COALESCE(interchange_fee,0)) AS ic,
           SUM(COALESCE(scheme_fee,0)) AS sf,
           SUM(COALESCE(ecom_fee,0)) AS pg
    FROM fact_transaction
    WHERE tenant_id = :tenant
      AND payment_date >= :from::date AND payment_date < (:to::date + 1)
    GROUP BY 1
), s AS (
    SELECT business_date AS d,
           SUM(total_txns) AS txns, SUM(total_volume) AS vol,
           SUM(total_msf) AS msf, SUM(total_interchange) AS ic,
           SUM(total_scheme_fee) AS sf, SUM(total_ecom_fee) AS pg,
           SUM(total_net_revenue) AS net
    FROM sum_daily_full
    WHERE tenant_id = :tenant
      AND business_date BETWEEN :from::date AND :to::date
    GROUP BY 1
)
SELECT COALESCE(f.d, s.d) AS business_date,
       f.txns AS fact_txns, s.txns AS sum_txns,
       f.vol - s.vol   AS vol_diff,
       f.msf - s.msf   AS msf_diff,
       f.ic  - s.ic    AS interchange_diff,
       f.sf  - s.sf    AS scheme_fee_diff,
       f.pg  - s.pg    AS pg_fee_diff,
       (f.msf - f.ic - f.sf - f.pg) - s.net AS net_revenue_diff
FROM f FULL OUTER JOIN s ON f.d = s.d
WHERE f.d IS NULL OR s.d IS NULL
   OR f.txns <> s.txns
   OR ABS(f.vol - s.vol) > 0.01 OR ABS(f.msf - s.msf) > 0.01
   OR ABS(f.ic  - s.ic)  > 0.01 OR ABS(f.sf  - s.sf)  > 0.01
   OR ABS(f.pg  - s.pg)  > 0.01
   OR ABS((f.msf - f.ic - f.sf - f.pg) - s.net) > 0.05
ORDER BY 1;

-- ---------------------------------------------------------------------------
-- Q9. FACT vs sum_daily_merchant (finance-facing summary) — same idea.
-- Expect ZERO rows.
-- ---------------------------------------------------------------------------
WITH f AS (
    SELECT DATE(payment_date) AS d,
           SUM(COALESCE(store_base_currency_amount,0)) AS vol,
           SUM(COALESCE(msf,0)) AS msf,
           SUM(COALESCE(interchange_fee,0)) AS ic,
           SUM(COALESCE(scheme_fee,0)) AS sf
    FROM fact_transaction
    WHERE tenant_id = :tenant
      AND payment_date >= :from::date AND payment_date < (:to::date + 1)
    GROUP BY 1
), s AS (
    SELECT business_date AS d,
           SUM(total_base_volume) AS vol, SUM(total_msf) AS msf,
           SUM(total_interchange) AS ic,  SUM(total_scheme_fee) AS sf
    FROM sum_daily_merchant
    WHERE tenant_id = :tenant
      AND business_date BETWEEN :from::date AND :to::date
    GROUP BY 1
)
SELECT COALESCE(f.d, s.d) AS business_date,
       f.vol - s.vol AS vol_diff, f.msf - s.msf AS msf_diff,
       f.ic - s.ic AS ic_diff,    f.sf - s.sf  AS sf_diff
FROM f FULL OUTER JOIN s ON f.d = s.d
WHERE f.d IS NULL OR s.d IS NULL
   OR ABS(f.vol - s.vol) > 0.01 OR ABS(f.msf - s.msf) > 0.01
   OR ABS(f.ic - s.ic) > 0.01   OR ABS(f.sf - s.sf) > 0.01
ORDER BY 1;

-- ---------------------------------------------------------------------------
-- Q10. BENEFIT / Benefit QR sanity — BH-specific. Every Benefit row should be
-- DOMESTIC and priced off the Benefit rate card (not the generic wildcard).
-- ---------------------------------------------------------------------------
SELECT ft.card_scheme, ft.destination, ft.channel,
       ft.fee_resolution_status, irl.label AS ic_rule,
       COUNT(*) AS rows, SUM(ABS(ft.store_base_currency_amount)) AS gross_volume,
       SUM(ft.interchange_fee) AS interchange, SUM(ft.scheme_fee) AS scheme_fee
FROM fact_transaction ft
LEFT JOIN interchange_rate_local irl ON irl.id = ft.interchange_rule_id
WHERE ft.tenant_id = :tenant
  AND ft.payment_date >= :from::date AND ft.payment_date < (:to::date + 1)
  AND UPPER(COALESCE(ft.card_scheme,'')) LIKE '%BENEFIT%'
GROUP BY 1,2,3,4,5 ORDER BY rows DESC;

-- ---------------------------------------------------------------------------
-- Q11. NULL-fee residue — non-refund rows that ended up with NULL fees
-- (means a rate gap; cross-reference Q2 statuses). Expect ZERO rows.
-- ---------------------------------------------------------------------------
SELECT DATE(payment_date) AS business_date, card_scheme, channel, destination,
       fee_resolution_status, scheme_fee_status, COUNT(*) AS rows
FROM fact_transaction
WHERE tenant_id = :tenant
  AND payment_date >= :from::date AND payment_date < (:to::date + 1)
  AND REPLACE(UPPER(TRIM(COALESCE(transaction_type,''))),' ','')
      NOT IN ('RFND','REFUND','REFUNDREVERSAL','REFUNDVOID','SALEREVERSAL','SALEVOID')
  AND (interchange_fee IS NULL OR scheme_fee IS NULL)
GROUP BY 1,2,3,4,5,6 ORDER BY rows DESC;
