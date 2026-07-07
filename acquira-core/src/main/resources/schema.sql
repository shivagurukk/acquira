-- ============================================================
-- Acquira schema.sql  (de-duplicated, dependency-ordered)
-- Cleaned from 9881-line file with 5x duplication. 1 canonical
-- definition per object; email_queue uses the statement_month version.
-- ============================================================

-- ============================================================
-- DROP existing tables
-- ============================================================
-- ==========================================
-- 0. CLEANUP (Dev Mode: Reset Schema)
-- ==========================================
-- Security / Core
DROP TABLE IF EXISTS user_tenant_access CASCADE;
DROP TABLE IF EXISTS user_region_access CASCADE;
DROP TABLE IF EXISTS sys_group_menu CASCADE;
DROP TABLE IF EXISTS sys_menu CASCADE;
DROP TABLE IF EXISTS sys_user_group CASCADE;
DROP TABLE IF EXISTS user_role CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS app_user CASCADE;
DROP TABLE IF EXISTS role CASCADE;
DROP TABLE IF EXISTS tenant_setting CASCADE;
DROP TABLE IF EXISTS dashboard_config CASCADE;
DROP TABLE IF EXISTS tenant CASCADE;
DROP TABLE IF EXISTS region CASCADE;
DROP TABLE IF EXISTS ref_country CASCADE;
DROP TABLE IF EXISTS audit_log CASCADE;
DROP TABLE IF EXISTS saved_filter CASCADE;
-- Business Domain
DROP TABLE IF EXISTS dim_terminal CASCADE;
DROP TABLE IF EXISTS dim_bank_account CASCADE;
DROP TABLE IF EXISTS dim_store CASCADE;
DROP TABLE IF EXISTS dim_merchant CASCADE;
DROP TABLE IF EXISTS dim_aggregator CASCADE;
DROP TABLE IF EXISTS bank_budget_target CASCADE;
DROP TABLE IF EXISTS merchant_lifecycle_status CASCADE;
DROP TABLE IF EXISTS merchant_activity_summary CASCADE;
DROP TABLE IF EXISTS merchant_opportunity_score CASCADE;
DROP TABLE IF EXISTS merchant_churn_score CASCADE;
DROP TABLE IF EXISTS merchant_segment CASCADE;
DROP TABLE IF EXISTS revenue_leakage_flags CASCADE;
DROP TABLE IF EXISTS merchant_contact CASCADE;
DROP TABLE IF EXISTS merchant_document CASCADE;
DROP TABLE IF EXISTS merchant_note CASCADE;
DROP TABLE IF EXISTS merchant_risk_profile CASCADE;
DROP TABLE IF EXISTS merchant_settlement_config CASCADE;
DROP TABLE IF EXISTS merchant_contract CASCADE;
DROP TABLE IF EXISTS merchant_activity CASCADE;
-- Facts
DROP TABLE IF EXISTS fact_transaction CASCADE;
DROP TABLE IF EXISTS sum_daily_merchant CASCADE;
DROP TABLE IF EXISTS sum_daily_merchant_attribute CASCADE;
DROP TABLE IF EXISTS sum_daily_terminal CASCADE;
DROP TABLE IF EXISTS sum_daily_bank CASCADE;
DROP TABLE IF EXISTS sum_daily_finance CASCADE;
DROP TABLE IF EXISTS sum_daily_insight CASCADE;
DROP TABLE IF EXISTS sum_daily_scheme CASCADE;
DROP TABLE IF EXISTS sum_daily_channel CASCADE;
DROP TABLE IF EXISTS sum_daily_mcc CASCADE;
DROP TABLE IF EXISTS sum_monthly_bank CASCADE;
DROP TABLE IF EXISTS sum_monthly_card CASCADE;
DROP TABLE IF EXISTS sum_monthly_merchant_metrics CASCADE;
DROP TABLE IF EXISTS merchant_daily_metrics CASCADE;
DROP TABLE IF EXISTS kpi_snapshot_daily CASCADE;
DROP TABLE IF EXISTS kpi_snapshot_monthly CASCADE;
DROP TABLE IF EXISTS batch_run_log CASCADE;
-- Config
DROP TABLE IF EXISTS data_source_config CASCADE;
DROP TABLE IF EXISTS report_query_config CASCADE;
DROP TABLE IF EXISTS report_run_log CASCADE;
-- Integration Hub
DROP TABLE IF EXISTS integration_run_log CASCADE;
DROP TABLE IF EXISTS integration_schedule CASCADE;
DROP TABLE IF EXISTS integration_report CASCADE;
DROP TABLE IF EXISTS integration_connection CASCADE;
-- Report Builder & Email Campaigns
DROP TABLE IF EXISTS email_queue CASCADE;
DROP TABLE IF EXISTS email_campaign_log CASCADE;
DROP TABLE IF EXISTS email_campaign CASCADE;
DROP TABLE IF EXISTS email_template_config CASCADE;
DROP TABLE IF EXISTS email_smtp_config CASCADE;
DROP TABLE IF EXISTS report_schedule CASCADE;
DROP TABLE IF EXISTS report_template CASCADE;
-- Security / Auth tokens
DROP TABLE IF EXISTS refresh_token CASCADE;
DROP TABLE IF EXISTS sso_state_token CASCADE;
DROP TABLE IF EXISTS password_history CASCADE;
DROP TABLE IF EXISTS password_reset_token CASCADE;
DROP TABLE IF EXISTS access_request CASCADE;
-- Misc
DROP TABLE IF EXISTS ai_chat_history CASCADE;
DROP TABLE IF EXISTS ref_card_scheme CASCADE;
DROP TABLE IF EXISTS sales_user_assignment CASCADE;
DROP TABLE IF EXISTS sales_team_mapping CASCADE;
DROP TABLE IF EXISTS sales_country_lead CASCADE;
DROP TABLE IF EXISTS sales_agent_profile CASCADE;
-- Staging
DROP TABLE IF EXISTS stg_merchant_master_raw CASCADE;
DROP TABLE IF EXISTS stg_trnx_raw CASCADE;

-- FEE_ENGINE_APPEND_ANCHOR
