-- ============================================================================
-- V2026_07_05_03: total_base_volume on sum_daily_bank / sum_monthly_bank.
--
-- WHY
-- ---
-- The CEO landing dashboard (ceo-summary) read volume from
-- sum_daily_bank.total_volume — CARDHOLDER currency — while the CEO
-- Volume & Revenue screen uses SETTLEMENT volume (total_base_volume on
-- sum_daily_terminal), the figure interchange/scheme fees are computed
-- against. On international mix the two "volume" numbers diverge, which is
-- exactly the kind of inconsistency a CEO will notice across two screens.
--
-- FIX: carry settlement volume at bank grain too. populateSummaryStep fills
-- sum_daily_bank.total_base_volume from fact rows and sum_monthly_bank rolls
-- it up from the daily table. ceo-summary switches its volume / avg-ticket /
-- margin math to the settlement figure, making both CEO screens (and any
-- future bank-grain fee reporting) internally consistent: fees, net revenue,
-- and margin all reference the same currency basis.
--
-- total_volume (cardholder) stays untouched — every existing consumer keeps
-- its current semantics.
--
-- Existing rows get 0; environment is being wiped and re-ingested, so no
-- backfill is shipped. Splitter-safe (no $$); idempotent; listed in
-- spring.sql.init.schema-locations after schema.sql. On prod apply once via
-- psql.
-- ============================================================================

ALTER TABLE sum_daily_bank   ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_monthly_bank ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;
