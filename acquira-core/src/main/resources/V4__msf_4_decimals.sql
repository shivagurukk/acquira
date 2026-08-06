-- =================================================================
-- V4: MSF precision fix — summary tables carried MSF at DECIMAL(19,2)
-- while fact_transaction.msf is DECIMAL(19,4). Every daily rollup
-- rounded MSF to 2 dp, so month totals drifted from the source file
-- by a small amount per merchant per day (reported as ~21k/month on
-- a full book). Widen ONLY the MSF columns to scale 4.
--
-- DECIMAL(21,4) keeps the same 17 integer digits as DECIMAL(19,2).
--
-- Apply manually:  psql "$DB_URL" -v ON_ERROR_STOP=1 -f V4__msf_4_decimals.sql
--
-- AFTER applying: run the super-admin "Summary Rebuild" (Data
-- Migration screen) for the affected months. Historical summary rows
-- were already rounded when stored; the rebuild re-aggregates them
-- from fact_transaction, which still holds the full 4-dp values.
-- =================================================================

ALTER TABLE IF EXISTS sum_daily_bank      ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_merchant  ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_mcc       ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_scheme    ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_channel   ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_terminal  ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_insight   ALTER COLUMN total_msf TYPE DECIMAL(21, 4);

-- Destination/card-mix finance table: all four MSF measures.
ALTER TABLE IF EXISTS sum_daily_finance   ALTER COLUMN dom_debit_msf  TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_finance   ALTER COLUMN dom_credit_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_finance   ALTER COLUMN int_msf        TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_finance   ALTER COLUMN total_msf      TYPE DECIMAL(21, 4);

-- Partitioned pre-aggregates: altering the parent cascades to all partitions.
ALTER TABLE IF EXISTS sum_daily_full      ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_daily_explorer  ALTER COLUMN total_msf TYPE DECIMAL(21, 4);

-- Monthly rollups.
ALTER TABLE IF EXISTS sum_monthly_bank    ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
ALTER TABLE IF EXISTS sum_monthly_insight ALTER COLUMN total_msf TYPE DECIMAL(21, 4);
